package com.backend.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongToIntFunction;

import javax.sql.DataSource;

/**
 * Applies a finished match (docs detailed-design/06 §4).
 *
 * Consumed from the queue by {@code worker}, which may deliver the same result more than
 * once (NFR-7). The whole transaction is therefore idempotent, and the mechanism is the
 * natural key rather than a separate dedupe table: the insert into {@code match_player} is
 * both the write and the duplicate check.
 *
 * <h2>Why not {@code INSERT IGNORE}</h2>
 *
 * It used to be. {@code IGNORE} turns <em>every</em> error on that statement into a warning
 * and zero rows, not only the duplicate key it was there for. A foreign-key violation — a
 * player who no longer exists — came back as "already applied", so the result was
 * acknowledged, counted as a redelivery and logged at debug; nothing was ever paid. An
 * out-of-range value was silently clamped into the row while the balances were updated
 * with the <em>un</em>clamped one, so the two disagreed for good and the ledger still
 * reconciled, because it was wrong the same way. Both measured against MySQL 8.0.46.
 *
 * Now the insert is plain, and exactly one error is treated as "already applied": 1062,
 * duplicate key. Everything else is an error, and is either prevented by
 * {@link #validate} before the transaction starts or reported as one.
 *
 * <h2>Lock the players first</h2>
 *
 * Every player row the result touches is locked {@code FOR UPDATE}, in ascending id order,
 * before anything else is written. Sorting the loop was not enough: the insert's
 * foreign-key check takes a <em>shared</em> lock on the player row and the balance update
 * then wants an <em>exclusive</em> one, so two transactions each holding the shared lock
 * waited on each other. Measured: 24 concurrent matches sharing two players failed on
 * three runs out of three, several exhausting every retry. Taking the exclusive lock up
 * front removes the upgrade, and it puts this path in the same lock order as a purchase.
 *
 * The locking read also answers which players exist, which is how a missing one is
 * reported as missing rather than mistaken for a duplicate.
 */
public final class MatchResultRepository {

    /**
     * Below this a player was barely there: the worker pays nothing for it, or quitting would be profitable, and a
     * result this short counts for nothing a stay counts for either, beyond its row and a rated match's rating: no
     * match in the stats, no goal's progress, no pass points, no achievement, no active day (D-45). A stay of a
     * second, again and again, earned them as playing does.
     */
    public static final long COUNTED_FROM_SECONDS = 5;

    public record PlayerResult(long playerId, int team, int placement, int kills, int deaths,
                               int score, int xpGained, int ratingDelta, long coins,
                               boolean won, long playtimeSeconds, int assists) {

        public PlayerResult(long playerId, int team, int placement, int kills, int deaths,
                            int score, int xpGained, int ratingDelta, long coins,
                            boolean won, long playtimeSeconds) {
            this(playerId, team, placement, kills, deaths, score, xpGained, ratingDelta, coins, won, playtimeSeconds, 0);
        }
    }

    /**
     * {@code rated}: whether the mode moves a rating. The deltas are then worked out here, from
     * the ratings the transaction has locked, by the {@link Rating} {@code worker} supplies,
     * and each player's {@link PlayerResult#ratingDelta} is not used.
     */
    public record MatchResult(String matchUid, int kind, int mode, String arena,
                              long startedAtMillis, long endedAtMillis,
                              List<PlayerResult> players, boolean rated, boolean cutShort) {

        public MatchResult(String matchUid, int kind, int mode, String arena,
                           long startedAtMillis, long endedAtMillis, List<PlayerResult> players, boolean rated) {
            this(matchUid, kind, mode, arena, startedAtMillis, endedAtMillis, players, rated, false);
        }

        public MatchResult(String matchUid, int kind, int mode, String arena,
                           long startedAtMillis, long endedAtMillis, List<PlayerResult> players) {
            this(matchUid, kind, mode, arena, startedAtMillis, endedAtMillis, players, false);
        }
    }

    /**
     * How a rated match moves its ratings (04 §4): given each player's rating and rated matches
     * so far, as locked, and their placements, equal for a draw. Two for a duel, or for a team
     * match at each team's mean; the whole field for a free-for-all (D-28). A rule of
     * {@code worker}'s, as the rewards are; this class only applies it.
     */
    @FunctionalInterface
    public interface Rating {
        int[] deltas(int[] ratings, int[] ratedBefore, int[] placements);
    }

    /** For a repository that is never given a rated result. */
    private static final Rating UNRATED = (ratings, ratedBefore, placements) -> new int[ratings.length];

    /**
     * The rated modes' ids, as {@code handoff/MatchMode} numbers them: this module does not
     * depend on it, and {@code worker}'s tests hold that the two agree.
     */
    public static final int MODE_DUEL = 1;
    public static final int MODE_TVT = 2;
    public static final int MODE_RFFA = 3;
    /** A team match (Q-18): the teams are rated, and no player's own rating moves. */
    public static final int MODE_TEAMS = 5;

    /** A team's rating and rated matches, read under its lock (D-43). */
    private record LockedTeam(int rating, int ratedMatches) { }

    /**
     * What the locking read found for a player: its xp, each rated mode's rating and count, and
     * its team, null for none: read under the lock every change of team takes (D-59); and its display
     * name as it is now, for the boards (D-60); and the account level stored, which a milestone is
     * judged from (D-61).
     */
    private record Locked(long xp, int ratingDuel, int ratedDuels, int ratingTvt, int ratedTvts,
                          int ratingRffa, int ratedRffas, Long teamId, String name, int level,
                          Achievements.Counts stats) { }

    /** What one player was paid by this call, as applied: told to them as {@code evt.rewards} (05 §6). */
    public record Paid(long playerId, int placement, long coins, int xp, int ratingDelta, int gems,
                       List<String> achievements, List<String> goals, long goalCoins, SeasonPassRepository.Earned pass) { }

    /**
     * What applying a result did.
     *
     * {@code missingPlayers} is kept apart from duplicates on purpose: "this was already
     * applied" is the normal path for a redelivery, while "this player does not exist" means
     * a result that can never be paid, and conflating them is how the second went unnoticed.
     * {@code names}: each existing player's display name as the apply read it, for the boards (D-60).
     */
    public record ApplyResult(int newPlayers, int duplicatePlayers, List<Long> missingPlayers, List<Paid> paid,
                              Map<Long, String> names) {

        public ApplyResult(int newPlayers, int duplicatePlayers, List<Long> missingPlayers) {
            this(newPlayers, duplicatePlayers, missingPlayers, List.of(), Map.of());
        }

        /** True if this call changed anything for anybody. */
        public boolean anythingNew() {
            return newPlayers > 0;
        }
    }

    /**
     * A result that cannot be applied as it stands, whatever the database is doing.
     *
     * Thrown before any transaction starts, so nothing is half-written. The caller should
     * set the entry aside rather than retry it: no number of retries makes 70 000 kills fit
     * in a {@code SMALLINT UNSIGNED}.
     */
    public static final class InvalidResult extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InvalidResult(String message) {
            super(message);
        }
    }

    // Column widths from V1/V2. Validation checks against these rather than letting MySQL
    // decide, because under INSERT IGNORE it decided by clamping, and under a plain insert it
    // decides by failing the whole transaction for a reason no retry can fix.
    private static final int TINYINT_UNSIGNED_MAX = 255;
    private static final int SMALLINT_UNSIGNED_MAX = 65_535;
    private static final int UID_LENGTH = 26;
    private static final int ARENA_MAX_LENGTH = 32;
    private static final int DUPLICATE_KEY = 1062;

    private final Tx tx;

    /** What a milestone pays: a boost's worth (04 §8). A first cut, as every balance number. */
    static final int MILESTONE_GEMS = 20;

    /** The gems reaching an account level pays (04 §8): level 5, then every tenth; none otherwise. */
    static int milestoneGems(int level) {
        return level == 5 || level % 10 == 0 ? MILESTONE_GEMS : 0;
    }

    /**
     * The account level a lifetime xp total is worth. A balance rule, so it belongs to
     * whoever pays rewards ({@code worker}) and is handed in rather than decided here.
     */
    private final LongToIntFunction accountLevel;
    private final Rating rating;
    private final GoalDraw goals;

    /**
     * How late a result may arrive and still be applied. Older ones are refused, and the
     * consumer dead-letters them as evidence.
     *
     * This is half of the retention rule (D-14). A result is recognised as a redelivery by its
     * players' rows in {@code match_player}, and those rows are deleted after
     * {@link #MATCH_RETENTION_MILLIS}. A result arriving after its rows were deleted would
     * be applied a second time — the coins are still caught by the ledger's key, but xp,
     * level and stats are not. So the window in which a result is accepted must end well
     * before its evidence of having been applied is deleted: 30 days against 90.
     */
    public static final long RESULT_ACCEPT_WINDOW_MILLIS = TimeUnit.DAYS.toMillis(30);

    /**
     * How long a match and its players' rows are kept (06 §9): a player's match history.
     * Everything they see beyond it — totals, best score, matches played — is in
     * {@code player_stat}, written at the same time.
     */
    public static final long MATCH_RETENTION_MILLIS = TimeUnit.DAYS.toMillis(90);

    public MatchResultRepository(DataSource dataSource, LongToIntFunction accountLevel) {
        this(dataSource, accountLevel, UNRATED);
    }

    public MatchResultRepository(DataSource dataSource, LongToIntFunction accountLevel, Rating rating) {
        this(dataSource, accountLevel, rating, NO_GOALS);
    }

    /**
     * @param goals a player's goals for a day: {@code DailyGoals::of} in production, so each result counts
     *              toward them (D-66); {@link #NO_GOALS} where a test is about something else, whatever the day
     */
    public MatchResultRepository(DataSource dataSource, LongToIntFunction accountLevel, Rating rating, GoalDraw goals) {
        this.tx = new Tx(dataSource);
        this.accountLevel = accountLevel;
        this.rating = rating;
        this.goals = goals;
    }

    /** A player's goals for a day. */
    @FunctionalInterface
    public interface GoalDraw {
        List<DailyGoals.Goal> of(long playerId, java.time.LocalDate day);
    }

    /** No goals counted or paid. */
    public static final GoalDraw NO_GOALS = (player, day) -> List.of();

    /**
     * Applies a result, exactly once per player however often it is delivered.
     *
     * @throws InvalidResult if a value cannot be stored as it is. Nothing is written.
     */
    public ApplyResult apply(MatchResult result) throws SQLException {
        validate(result);
        long oldest = System.currentTimeMillis() - RESULT_ACCEPT_WINDOW_MILLIS;
        if (result.endedAtMillis() < oldest) {
            throw new InvalidResult("match " + result.matchUid() + " ended more than "
                    + TimeUnit.MILLISECONDS.toDays(RESULT_ACCEPT_WINDOW_MILLIS) + " days ago:"
                    + " too late to tell a redelivery from a new result, so not applied");
        }
        return tx.execute(c -> {
            List<PlayerResult> ordered = new ArrayList<>(result.players());
            ordered.sort(Comparator.comparingLong(PlayerResult::playerId));

            // A team match's teams are locked first, as every team action locks them (D-39, D-43).
            boolean teamMatch = result.mode() == MODE_TEAMS && result.rated();
            Map<Long, LockedTeam> teams = teamMatch ? lockTeams(c, ordered) : Map.of();
            Map<Long, Locked> existing = lockPlayers(c, ordered);
            List<Long> missing = new ArrayList<>();
            for (PlayerResult p : ordered) {
                if (!existing.containsKey(p.playerId())) {
                    missing.add(p.playerId());
                }
            }
            if (existing.isEmpty()) {
                // Nothing to pay and no row worth keeping: a match row with no players in it
                // is an orphan that every later query has to step around.
                return new ApplyResult(0, 0, missing);
            }

            long matchId = upsertMatch(c, result);
            int[] rated = ratingDeltas(result, ordered, existing);
            // A rated match played against someone, as the daily goal counts it (D-51): a duel, a team-vs-team or a
            // ranked free-for-all whose ratings moved, or a team match both teams' players were in; never a walkover.
            boolean ratedPlayed = rated != null
                    || teamMatch && ordered.stream().map(PlayerResult::team).distinct().count() > 1;
            // The day the match ended, UTC: the day its players were active (05 §11, D-47).
            java.time.LocalDate day = java.time.LocalDate.ofInstant(
                    java.time.Instant.ofEpochMilli(result.endedAtMillis()), java.time.ZoneOffset.UTC);
            // The season pass's season: the one being played as the result is applied, as the boards count it (D-69).
            int season = SeasonPassRepository.seasonBeingPlayed(c);
            List<Long> active = new ArrayList<>();
            int fresh = 0;
            int duplicate = 0;
            List<Paid> paid = new java.util.ArrayList<>();
            for (int i = 0; i < ordered.size(); i++) {
                PlayerResult p = ordered.get(i);
                if (!existing.containsKey(p.playerId())) {
                    continue;
                }
                // A rated mode's rating moves only by the rule: not at all for a walkover.
                int ratingDelta = rated != null ? rated[i] : result.rated() ? 0 : p.ratingDelta();
                if (!insertMatchPlayer(c, matchId, p, ratingDelta)) {
                    duplicate++;                    // this player's row already existed
                    continue;
                }
                fresh++;
                boolean counts = p.playtimeSeconds() >= COUNTED_FROM_SECONDS;
                if (counts) {
                    active.add(p.playerId());
                }
                Locked locked = existing.get(p.playerId());
                int levelAfter = accountLevel.applyAsInt(locked.xp() + p.xpGained());
                updateProgression(c, p, levelAfter, ratingDelta, rated != null, result.mode(), day, counts);
                int gems = payMilestones(c, p.playerId(), locked.level(), levelAfter);
                List<String> reached = new ArrayList<>();
                List<String> met = new ArrayList<>();
                long[] goalsPaid = {0, 0};
                if (counts) {
                    upsertStats(c, p);
                    gems += payAchievements(c, p.playerId(), locked.stats(), counted(locked.stats(), p), reached);
                    goalsPaid = payDailyGoals(c, p, day, ratedPlayed, met);
                    gems += (int) goalsPaid[1];
                }
                SeasonPassRepository.Earned pass = SeasonPassRepository.earn(c, p.playerId(), season, counts
                        ? SeasonPass.POINTS_A_RESULT + SeasonPass.POINTS_A_GOAL * met.size() : 0);
                paid.add(new Paid(p.playerId(), p.placement(), p.coins(), p.xpGained(), ratingDelta, gems, reached,
                        met, goalsPaid[0], pass));
                if (p.coins() != 0) {
                    creditCoins(c, p, result.matchUid());
                }
            }
            if (!active.isEmpty()) {
                recordDay(c, day, active);
            }
            // Only by the delivery that first applies it: a redelivery never rates teams (D-59).
            if (!teams.isEmpty() && fresh > 0) {
                rateTeams(c, matchId, ordered, teams, existing);
            }
            Map<Long, String> names = new HashMap<>();
            existing.forEach((id, locked) -> names.put(id, locked.name()));
            return new ApplyResult(fresh, duplicate, missing, paid, names);
        });
    }

    /**
     * Refuses a result that no database state could store as it is.
     *
     * Every bound here is a column width in V1/V2, or a value no arena can produce: a
     * negative score, coins paid out as a debit, a match that ended before it began. Such a
     * result is not a transient problem; it is evidence of a bug upstream, and it is better
     * set aside intact than clamped, which is what used to happen.
     */
    public static void validate(MatchResult r) {
        if (r.matchUid() == null || r.matchUid().length() != UID_LENGTH) {
            throw new InvalidResult("match uid must be " + UID_LENGTH + " characters: " + r.matchUid());
        }
        requireRange("kind", r.kind(), 0, TINYINT_UNSIGNED_MAX);
        requireRange("mode", r.mode(), 0, TINYINT_UNSIGNED_MAX);
        if (r.arena() == null || r.arena().isEmpty() || r.arena().length() > ARENA_MAX_LENGTH) {
            throw new InvalidResult("arena name must be 1 to " + ARENA_MAX_LENGTH + " characters");
        }
        if (r.startedAtMillis() <= 0 || r.endedAtMillis() < r.startedAtMillis()) {
            throw new InvalidResult("a match cannot end before it starts: "
                    + r.startedAtMillis() + " .. " + r.endedAtMillis());
        }
        if (r.players() == null || r.players().isEmpty()) {
            throw new InvalidResult("a match with nobody in it");
        }
        for (PlayerResult p : r.players()) {
            if (p.playerId() <= 0) {
                throw new InvalidResult("player id " + p.playerId());
            }
            requireRange("team", p.team(), 0, TINYINT_UNSIGNED_MAX);
            requireRange("placement", p.placement(), 0, SMALLINT_UNSIGNED_MAX);
            requireRange("kills", p.kills(), 0, SMALLINT_UNSIGNED_MAX);
            requireRange("deaths", p.deaths(), 0, SMALLINT_UNSIGNED_MAX);
            requireRange("score", p.score(), 0, Integer.MAX_VALUE);
            requireRange("xp gained", p.xpGained(), 0, Integer.MAX_VALUE);
            requireRange("rating delta", p.ratingDelta(), Short.MIN_VALUE, Short.MAX_VALUE);
            if (p.coins() < 0) {
                throw new InvalidResult("a match result cannot debit coins: " + p.coins());
            }
            if (p.playtimeSeconds() < 0) {
                throw new InvalidResult("negative playtime: " + p.playtimeSeconds());
            }
        }
    }

    /**
     * The rating deltas for a rated result, in the order of {@code ordered}, from the ratings
     * this transaction has locked: the result was written minutes before, and another match may
     * have moved them since. Null when nothing is rated: an unrated mode, a walkover (one player,
     * or one team), or a duel with a player who no longer exists.
     */
    private int[] ratingDeltas(MatchResult result, List<PlayerResult> ordered, Map<Long, Locked> existing) {
        if (result.mode() == MODE_TEAMS) {
            return null;
        }
        if (result.rated() && result.mode() == MODE_TVT) {
            return teamDeltas(ordered, existing);
        }
        if (result.rated() && result.mode() == MODE_RFFA) {
            return fieldDeltas(ordered, existing);
        }
        if (!result.rated() || ordered.size() != 2 || existing.size() != 2) {
            return null;
        }
        PlayerResult a = ordered.get(0);
        PlayerResult b = ordered.get(1);
        Locked la = existing.get(a.playerId());
        Locked lb = existing.get(b.playerId());
        return rating.deltas(new int[] {la.ratingDuel(), lb.ratingDuel()},
                new int[] {la.ratedDuels(), lb.ratedDuels()}, new int[] {a.placement(), b.placement()});
    }

    /**
     * A free-for-all's deltas (D-28), in the order of {@code ordered}: the whole field of players
     * still here, each against each other by placement. Null when fewer than two are: a walkover.
     */
    private int[] fieldDeltas(List<PlayerResult> ordered, Map<Long, Locked> existing) {
        List<Integer> here = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            if (existing.containsKey(ordered.get(i).playerId())) {
                here.add(i);
            }
        }
        if (here.size() < 2) {
            return null;
        }
        int[] ratings = new int[here.size()];
        int[] before = new int[here.size()];
        int[] placements = new int[here.size()];
        for (int k = 0; k < here.size(); k++) {
            PlayerResult p = ordered.get(here.get(k));
            Locked l = existing.get(p.playerId());
            ratings[k] = l.ratingRffa();
            before[k] = l.ratedRffas();
            placements[k] = p.placement();
        }
        int[] field = rating.deltas(ratings, before, placements);
        int[] deltas = new int[ordered.size()];
        for (int k = 0; k < here.size(); k++) {
            deltas[here.get(k)] = field[k];
        }
        return deltas;
    }

    /**
     * A team match's deltas (D-26), in the order of {@code ordered}: each team rated as its
     * players' mean, from the ratings this transaction locked, and each player moved by the
     * rule on the two means with their own count of rated matches, so their own K. Null when
     * only one team's players are in the result: a walkover, which moves nothing.
     */
    private int[] teamDeltas(List<PlayerResult> ordered, Map<Long, Locked> existing) {
        Map<Integer, long[]> sums = new java.util.TreeMap<>();       // team → {rating sum, players}
        Map<Integer, Integer> placed = new HashMap<>();
        for (PlayerResult p : ordered) {
            Locked l = existing.get(p.playerId());
            if (l != null) {
                long[] s = sums.computeIfAbsent(p.team(), t -> new long[2]);
                s[0] += l.ratingTvt();
                s[1]++;
                placed.merge(p.team(), p.placement(), Math::min);
            }
        }
        if (sums.size() != 2) {
            return null;
        }
        Map<Integer, Integer> mean = new HashMap<>();
        sums.forEach((team, s) -> mean.put(team, (int) Math.round((double) s[0] / s[1])));
        int[] deltas = new int[ordered.size()];
        for (int i = 0; i < ordered.size(); i++) {
            PlayerResult p = ordered.get(i);
            Locked l = existing.get(p.playerId());
            if (l == null) {
                continue;
            }
            int other = sums.keySet().stream().filter(t -> t != p.team()).findFirst().orElseThrow();
            deltas[i] = rating.deltas(new int[] {mean.get(p.team()), mean.get(other)},
                    new int[] {l.ratedTvts(), 0}, new int[] {placed.get(p.team()), placed.get(other)})[0];
        }
        return deltas;
    }

    private static void requireRange(String field, long value, long min, long max) {
        if (value < min || value > max) {
            throw new InvalidResult(field + " " + value + " is outside " + min + ".." + max);
        }
    }

    /**
     * Deletes matches that ended before {@code cutoffMillis}, with their players' rows, oldest
     * first, {@code batch} matches per transaction so no one transaction holds many locks.
     *
     * Refuses a cutoff inside {@link #RESULT_ACCEPT_WINDOW_MILLIS}: those rows are what
     * recognises a redelivered result, and deleting them while a result could still be
     * accepted would let it be applied twice. Player totals, stats and the ledger are not
     * touched; they are the durable record, and these rows are history.
     *
     * @return how many matches were deleted
     */
    public int purgeMatchesEndedBefore(long cutoffMillis, int batch) throws SQLException {
        if (cutoffMillis > System.currentTimeMillis() - RESULT_ACCEPT_WINDOW_MILLIS) {
            throw new IllegalArgumentException("refusing to delete matches a result could still"
                    + " be accepted for: the cutoff must be at least "
                    + TimeUnit.MILLISECONDS.toDays(RESULT_ACCEPT_WINDOW_MILLIS) + " days ago");
        }
        int total = 0;
        while (true) {
            int[] run = tx.execute(c -> {
                List<Long> ids = new ArrayList<>(batch);
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id FROM matches WHERE ended_at < ? ORDER BY ended_at LIMIT ?")) {
                    ps.setTimestamp(1, new Timestamp(cutoffMillis));
                    ps.setInt(2, batch);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            ids.add(rs.getLong(1));
                        }
                    }
                }
                if (ids.isEmpty()) {
                    return new int[] {0, 0};
                }
                String in = "?" + ",?".repeat(ids.size() - 1);
                int removed = 0;
                for (String sql : List.of("DELETE FROM match_player WHERE match_id IN (" + in + ")",
                                          "DELETE FROM match_team WHERE match_id IN (" + in + ")",
                                          "DELETE FROM matches WHERE id IN (" + in + ")")) {
                    try (PreparedStatement ps = c.prepareStatement(sql)) {
                        for (int i = 0; i < ids.size(); i++) {
                            ps.setLong(i + 1, ids.get(i));
                        }
                        removed = ps.executeUpdate();
                    }
                }
                // What this run deleted, not what it chose: every worker purges, and one that
                // chose the same matches as another waits on its locks and then deletes none.
                // Counting its choice reported them twice. A batch another worker had is not
                // short for it, so it carries on to the next.
                return new int[] {removed, ids.size()};
            });
            total += run[0];
            if (run[1] < batch) {
                return total;
            }
        }
    }

    /**
     * Takes exclusive locks on every player in the result, lowest id first, and reports
     * which of them exist.
     *
     * Under REPEATABLE READ a locking read of an id that does not exist takes a gap lock,
     * which briefly blocks a registration landing in that gap. It lasts as long as this
     * transaction — milliseconds — and a missing player is itself rare.
     *
     * @return each existing player's xp, rating and rated duels, read under the lock, so the
     *         level and rating written below are computed from what this transaction stores.
     */
    private static Map<Long, Locked> lockPlayers(Connection c, List<PlayerResult> ordered)
            throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT p.id, p.xp, p.rating_duel, p.rated_duels, p.rating_tvt, p.rated_tvts, p.rating_rffa,"
                        + " p.rated_rffas, p.team_id, p.display_name, p.level,"
                        // The counted stats an achievement is judged by (D-64), under the same lock: the
                        // row this transaction updates below, so no new lock order.
                        + " s.kills, s.wins, s.matches, s.assists, s.best_score, s.playtime_s"
                        + " FROM player p LEFT JOIN player_stat s ON s.player_id = p.id WHERE p.id IN (");
        for (int i = 0; i < ordered.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
        }
        sql.append(") ORDER BY p.id FOR UPDATE");
        Map<Long, Locked> existing = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < ordered.size(); i++) {
                ps.setLong(i + 1, ordered.get(i).playerId());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    existing.put(rs.getLong(1), new Locked(rs.getLong(2), rs.getInt(3), rs.getInt(4),
                            rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getObject(9, Long.class),
                            rs.getString(10), rs.getInt(11), new Achievements.Counts(rs.getLong(12), rs.getLong(13),
                            rs.getLong(14), rs.getLong(15), rs.getLong(16), rs.getLong(17))));
                }
            }
        }
        return existing;
    }

    /** The team of each of these players who is in one. */
    private static Map<Long, Long> teamsOf(Connection c, List<PlayerResult> players) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT player_id, team_id FROM team_member WHERE player_id IN (");
        for (int i = 0; i < players.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
        }
        sql.append(")");
        Map<Long, Long> teams = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < players.size(); i++) {
                ps.setLong(i + 1, players.get(i).playerId());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    teams.put(rs.getLong(1), rs.getLong(2));
                }
            }
        }
        return teams;
    }

    /** The players' teams, read, then locked, lowest id first: before the players, as D-39 orders. */
    private static Map<Long, LockedTeam> lockTeams(Connection c, List<PlayerResult> ordered) throws SQLException {
        List<Long> ids = new ArrayList<>(new java.util.TreeSet<>(teamsOf(c, ordered).values()));
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, LockedTeam> teams = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT id, rating, rated_matches FROM team WHERE id IN ("
                + "?" + ",?".repeat(ids.size() - 1) + ") ORDER BY id FOR UPDATE")) {
            for (int i = 0; i < ids.size(); i++) {
                ps.setLong(i + 1, ids.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    teams.put(rs.getLong(1), new LockedTeam(rs.getInt(2), rs.getInt(3)));
                }
            }
        }
        return teams;
    }

    /**
     * A team match's part for its teams (D-43): each side's team is the one its players are in
     * now, read again under the players' locks (D-59), passing over any in no team; a side in none
     * or in two, one team on both sides, or a team not locked before the players (disbanded or
     * joined since), and the teams are not rated. Otherwise the
     * duel's rule between the two teams, and each side's row in {@code match_team}, whose key is
     * the duplicate check: a team is rated once, however often the result comes.
     */
    private void rateTeams(Connection c, long matchId, List<PlayerResult> ordered, Map<Long, LockedTeam> locked,
                           Map<Long, Locked> players) throws SQLException {
        long[] team = new long[2];
        int[] placement = new int[2];
        for (int side = 1; side <= 2; side++) {
            java.util.Set<Long> named = new java.util.HashSet<>();
            for (PlayerResult p : ordered) {
                Locked l = players.get(p.playerId());
                if (p.team() == side && l != null && l.teamId() != null) {
                    named.add(l.teamId());
                    placement[side - 1] = p.placement();
                }
            }
            if (named.size() != 1) {
                return;
            }
            team[side - 1] = named.iterator().next();
        }
        if (team[0] == team[1] || !locked.containsKey(team[0]) || !locked.containsKey(team[1])) {
            return;
        }
        LockedTeam one = locked.get(team[0]);
        LockedTeam two = locked.get(team[1]);
        int[] deltas = rating.deltas(new int[] {one.rating(), two.rating()},
                new int[] {one.ratedMatches(), two.ratedMatches()}, placement);
        for (int i = 0; i < 2; i++) {
            if (!insertMatchTeam(c, matchId, i + 1, team[i], placement[i], deltas[i])) {
                continue;                           // this side's part was applied before
            }
            int other = placement[1 - i];
            try (PreparedStatement ps = c.prepareStatement("UPDATE team SET"
                    + " rating = GREATEST(0, CAST(rating AS SIGNED) + ?), rated_matches = rated_matches + 1,"
                    + " wins = wins + ?, losses = losses + ?, draws = draws + ? WHERE id = ?")) {
                ps.setInt(1, deltas[i]);
                ps.setInt(2, placement[i] < other ? 1 : 0);
                ps.setInt(3, placement[i] > other ? 1 : 0);
                ps.setInt(4, placement[i] == other ? 1 : 0);
                ps.setLong(5, team[i]);
                ps.executeUpdate();
            }
        }
    }

    private static boolean insertMatchTeam(Connection c, long matchId, int side, long team, int placement,
                                           int ratingDelta) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO match_team (match_id, side, team_id, placement,"
                + " rating_delta) VALUES (?,?,?,?,?)")) {
            ps.setLong(1, matchId);
            ps.setInt(2, side);
            ps.setLong(3, team);
            ps.setInt(4, placement);
            ps.setInt(5, ratingDelta);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (e.getErrorCode() == DUPLICATE_KEY) {
                return false;
            }
            throw e;
        }
    }

    private static long upsertMatch(Connection c, MatchResult r) throws SQLException {
        // LAST_INSERT_ID(id) makes the existing id available on a duplicate, so a
        // redelivery finds the same match row instead of creating a second one.
        String sql = """
                INSERT INTO matches (match_uid, kind, mode, arena, started_at, ended_at, cut_short)
                VALUES (?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, r.matchUid());
            ps.setInt(2, r.kind());
            ps.setInt(3, r.mode());
            ps.setString(4, r.arena());
            ps.setTimestamp(5, new Timestamp(r.startedAtMillis()));
            ps.setTimestamp(6, new Timestamp(r.endedAtMillis()));
            ps.setBoolean(7, r.cutShort());                // its placements not a finish (V18, Q-45)
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM matches WHERE match_uid = ?")) {
            ps.setString(1, r.matchUid());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        throw new SQLException("match row vanished: " + r.matchUid());
    }

    /**
     * @return true if the row was inserted; false means this player's result was already
     *         applied. Any other failure is an error, not a duplicate.
     */
    private static boolean insertMatchPlayer(Connection c, long matchId, PlayerResult p,
                                             int ratingDelta) throws SQLException {
        String sql = """
                INSERT INTO match_player
                  (match_id, player_id, team, placement, kills, deaths, score, xp_gained, rating_delta, assists)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, matchId);
            ps.setLong(2, p.playerId());
            ps.setInt(3, p.team());
            ps.setInt(4, p.placement());
            ps.setInt(5, p.kills());
            ps.setInt(6, p.deaths());
            ps.setInt(7, p.score());
            ps.setInt(8, p.xpGained());
            ps.setInt(9, ratingDelta);
            ps.setInt(10, p.assists());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            // A duplicate key rolls back this statement only; InnoDB keeps the transaction
            // and its locks, so the rest of the result carries on. Nothing else is swallowed.
            if (e.getErrorCode() == DUPLICATE_KEY) {
                return false;
            }
            throw e;
        }
    }

    /**
     * Adds the xp and sets the account level it is worth (D-12: nothing wrote {@code level},
     * so every player was level 1 for ever). {@code GREATEST}, because a level is never taken
     * back: if the curve is rebalanced, nobody loses what they earned, and anyone the new
     * curve raises is raised at their next match. The same correction reaches rows written
     * before levels existed.
     */
    private static void updateProgression(Connection c, PlayerResult p, int levelAfter,
                                          int ratingDelta, boolean rated, int mode,
                                          java.time.LocalDate day, boolean counts) throws SQLException {
        // Each rated mode moves its own rating; anything else, as before, the duel's, by nothing.
        String[] columns = switch (mode) {
            case MODE_TVT -> new String[] {"rating_tvt", "rated_tvts"};
            case MODE_RFFA -> new String[] {"rating_rffa", "rated_rffas"};
            default -> new String[] {"rating_duel", "rated_duels"};
        };
        String sql = """
                UPDATE player
                   SET xp = xp + ?,
                       level = GREATEST(level, ?),
                       %1$s = GREATEST(0, CAST(%1$s AS SIGNED) + ?),
                       %2$s = %2$s + ?,
                       first_played_on = IF(?, LEAST(COALESCE(first_played_on, ?), ?), first_played_on)
                 WHERE id = ?
                """.formatted(columns[0], columns[1]);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, p.xpGained());
            ps.setInt(2, levelAfter);
            ps.setInt(3, ratingDelta);
            ps.setInt(4, rated ? 1 : 0);
            // The earliest day kept: a result from an earlier day, arriving late, moves it back. Not one too short
            // to count (D-45).
            ps.setBoolean(5, counts);
            ps.setObject(6, day);
            ps.setObject(7, day);
            ps.setLong(8, p.playerId());
            ps.executeUpdate();
        }
    }

    /**
     * Pays each milestone above the level stored, up to the level the new xp is worth, through the
     * ledger's one path in gems (D-61), keyed by the level so that it is paid once whatever the
     * column says later.
     *
     * @return the gems this call paid
     */
    private static int payMilestones(Connection c, long playerId, int levelBefore, int levelAfter)
            throws SQLException {
        int paid = 0;
        for (int level = levelBefore + 1; level <= levelAfter; level++) {
            int gems = milestoneGems(level);
            if (gems > 0 && EconomyRepository.move(c, playerId, EconomyRepository.CURRENCY_GEMS, gems,
                    EconomyRepository.REASON_MILESTONE, "level:" + level, "milestone:" + playerId + ":" + level)
                    == EconomyRepository.Outcome.APPLIED) {
                paid += gems;
            }
        }
        return paid;
    }

    /** The counted stats after this result, as {@link #upsertStats} writes them. */
    static Achievements.Counts counted(Achievements.Counts before, PlayerResult p) {
        return new Achievements.Counts(before.kills() + p.kills(), before.wins() + (p.won() ? 1 : 0),
                before.matches() + 1, before.assists() + p.assists(), Math.max(before.bestScore(), p.score()),
                before.playtime() + p.playtimeSeconds());
    }

    /**
     * Pays each achievement this result carried a stat across, through the ledger's one path in gems
     * (D-64), keyed by the achievement, so it is paid once whatever happens later.
     *
     * @param reached filled with the ids paid, for {@code evt.rewards}
     * @return the gems this call paid
     */
    private static int payAchievements(Connection c, long playerId, Achievements.Counts before,
                                       Achievements.Counts after, List<String> reached) throws SQLException {
        int paid = 0;
        for (Achievements.Achievement a : Achievements.crossed(before, after)) {
            if (EconomyRepository.move(c, playerId, EconomyRepository.CURRENCY_GEMS, a.gems(),
                    EconomyRepository.REASON_ACHIEVEMENT, "achievement:" + a.id(), "achievement:" + playerId + ":" + a.id())
                    == EconomyRepository.Outcome.APPLIED) {
                paid += a.gems();
                reached.add(a.id());
            }
        }
        return paid;
    }

    /**
     * The player's goals for the result's day (D-66): each of the day's three moved by what the result
     * counts, a goal reaching its target paid its coins, and the result that meets the third paid the
     * set's gems; through the ledger's one path, keyed by the day and the goal.
     *
     * @param met filled with the goals this result met, for {@code evt.rewards}
     * @return the coins and the gems this call paid
     */
    private long[] payDailyGoals(Connection c, PlayerResult p, java.time.LocalDate day, boolean rated,
                                        List<String> met) throws SQLException {
        DailyGoals.Counted counted = new DailyGoals.Counted(1, p.kills(), p.won() ? 1 : 0, p.assists(), p.score(),
                p.playtimeSeconds(), rated ? 1 : 0);
        long coins = 0;
        long gems = 0;
        boolean allBefore = true;
        boolean allAfter = true;
        String key = "daily:" + p.playerId() + ":" + day + ":";
        List<DailyGoals.Goal> three = goals.of(p.playerId(), day);
        if (three.isEmpty()) {
            return new long[] {0, 0};
        }
        // Added where stored, then read back under lock: each goal's progress as the latest commit and this result
        // leave it, whatever this transaction's snapshot saw (D-46). A goal this result does not move is added 0, so
        // its row is there to be read under lock, for whether all three are met.
        for (DailyGoals.Goal goal : three) {
            DailyGoalRepository.add(c, p.playerId(), day, goal.id(), counted.of(goal.kind()));
        }
        Map<String, Long> after = DailyGoalRepository.progressLocked(c, p.playerId(), day,
                three.stream().map(DailyGoals.Goal::id).toList());
        for (DailyGoals.Goal goal : three) {
            long now = after.getOrDefault(goal.id(), 0L);
            long was = now - counted.of(goal.kind());
            allBefore &= was >= goal.target();
            allAfter &= now >= goal.target();
            if (was < goal.target() && now >= goal.target()
                    && EconomyRepository.move(c, p.playerId(), EconomyRepository.CURRENCY_COINS, goal.coins(),
                    EconomyRepository.REASON_DAILY_GOAL, "daily:" + goal.id(), key + goal.id())
                    == EconomyRepository.Outcome.APPLIED) {
                coins += goal.coins();
                met.add(goal.id());
            }
        }
        if (!allBefore && allAfter && EconomyRepository.move(c, p.playerId(), EconomyRepository.CURRENCY_GEMS,
                DailyGoals.SET_GEMS, EconomyRepository.REASON_DAILY_GOAL, "daily:set", key + "set")
                == EconomyRepository.Outcome.APPLIED) {
            gems += DailyGoals.SET_GEMS;
        }
        return new long[] {coins, gems};
    }

    /** A row a player a day, for the players whose part was new; a day already there is kept. */
    private static void recordDay(Connection c, java.time.LocalDate day, List<Long> players) throws SQLException {
        StringBuilder sql = new StringBuilder("INSERT IGNORE INTO player_day (day, player_id) VALUES ");
        for (int i = 0; i < players.size(); i++) {
            sql.append(i == 0 ? "(?, ?)" : ", (?, ?)");
        }
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = 1;
            for (long player : players) {
                ps.setObject(i++, day);
                ps.setLong(i++, player);
            }
            ps.executeUpdate();
        }
    }

    private static void upsertStats(Connection c, PlayerResult p) throws SQLException {
        String sql = """
                INSERT INTO player_stat
                  (player_id, matches, wins, kills, deaths, best_score, playtime_s, assists)
                VALUES (?, 1, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  matches    = matches + 1,
                  wins       = wins + VALUES(wins),
                  kills      = kills + VALUES(kills),
                  deaths     = deaths + VALUES(deaths),
                  assists    = assists + VALUES(assists),
                  best_score = GREATEST(best_score, VALUES(best_score)),
                  playtime_s = playtime_s + VALUES(playtime_s)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, p.playerId());
            ps.setInt(2, p.won() ? 1 : 0);
            ps.setInt(3, p.kills());
            ps.setInt(4, p.deaths());
            ps.setInt(5, p.score());
            ps.setLong(6, p.playtimeSeconds());
            ps.setInt(7, p.assists());
            ps.executeUpdate();
        }
    }

    /**
     * Pays the match's coins through the one path that moves a balance (D-13); this used to
     * be a second implementation of it. ALREADY_APPLIED cannot happen here — the player's
     * match row, inserted first, is what makes a redelivery a no-op — and if it did, not
     * paying twice would be the right answer anyway.
     */
    private static void creditCoins(Connection c, PlayerResult p, String matchUid)
            throws SQLException {
        EconomyRepository.moveCoins(c, p.playerId(), p.coins(),
                EconomyRepository.REASON_MATCH_REWARD, matchUid,
                "match:" + matchUid + ":" + p.playerId());
    }
}
