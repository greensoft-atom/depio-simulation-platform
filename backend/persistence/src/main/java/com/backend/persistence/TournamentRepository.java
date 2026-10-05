package com.backend.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

/**
 * Tournaments' first slice (docs detailed-design/04-platform-services.md §6, 06 §3, D-40): duels,
 * single elimination. Every change of a tournament's state is a conditional update on its state
 * and version, and every change of a match's on the match's state: a second attempt, from a
 * second worker or after a restart, matches no row and changes nothing.
 */
public final class TournamentRepository {

    public static final int REGISTRATION = 0;
    public static final int SEEDED = 1;
    public static final int RUNNING = 2;
    public static final int FINISHED = 3;
    public static final int CANCELLED = 4;

    /** A finished or cancelled tournament, kept as long as a match's detail is (06 §9, Q-47). */
    public static final Duration KEPT = Duration.ofDays(90);

    public static final int PENDING = 0;
    public static final int READY = 1;
    public static final int DONE = 2;

    private static final int DUPLICATE_ENTRY = 1062;

    public enum Registration { OK, NO_SUCH_TOURNAMENT, CLOSED, TOO_FEW_RATED, FULL, ALREADY }

    /** {@code mode}: {@link MatchResultRepository#MODE_DUEL}, or {@link MatchResultRepository#MODE_TEAMS} (D-44). */
    public record Tournament(long id, String name, int state, int version, int maxEntries, Instant registrationEnds,
                             Instant startsAt, int roundMinutes, long prize1, long prize2, long prize3,
                             int currentRound, Instant roundEndedAt, int mode, int format) { }

    public static final int ELIMINATION = 0;
    public static final int ROUND_ROBIN = 1;

    /** An entry's place in a round robin's standings (04 §6). */
    public record Standing(long entry, String name, int seed, int points, int wins, int draws, int losses) { }

    /**
     * A ready match of a round robin decided as a draw (04 §6): done, with no winner, and a point
     * each. Nothing moves on, as nothing does in a round robin.
     */
    public boolean draw(long id, int round, int slot) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE tournament_match SET state = ?, winner = NULL"
                    + " WHERE tournament_id = ? AND round = ? AND slot = ? AND state = ?")) {
                ps.setInt(1, DONE);
                ps.setLong(2, id);
                ps.setInt(3, round);
                ps.setInt(4, slot);
                ps.setInt(5, READY);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** A round robin's points: a win 3, a draw 1, a loss 0, football's (04 §6). */
    static final int WIN_POINTS = 3;
    static final int DRAW_POINTS = 1;

    /**
     * A round robin's standings, by points, then wins, then seed (04 §6): from its matches done,
     * each entry with its name, a player's or a team's.
     */
    public List<Standing> standings(long id) throws SQLException {
        boolean teams = tx.execute(c -> modeOf(c, id)) == MatchResultRepository.MODE_TEAMS;
        java.util.Map<Long, int[]> table = new java.util.LinkedHashMap<>();   // seed, wins, draws, losses
        java.util.Map<Long, String> names = new java.util.HashMap<>();
        if (teams) {
            for (TeamEntry e : teamEntries(id)) {
                table.put(e.teamId(), new int[] {e.seed(), 0, 0, 0});
                names.put(e.teamId(), e.name());
            }
        } else {
            for (Entry e : entries(id)) {
                table.put(e.playerId(), new int[] {e.seed(), 0, 0, 0});
                names.put(e.playerId(), e.name());
            }
        }
        for (Match m : matches(id)) {
            if (m.state() != DONE || m.playerA() == null || m.playerB() == null) {
                continue;
            }
            if (m.winner() == null) {
                table.get(m.playerA())[2]++;
                table.get(m.playerB())[2]++;
            } else {
                table.get(m.winner())[1]++;
                table.get(m.winner().equals(m.playerA()) ? m.playerB() : m.playerA())[3]++;
            }
        }
        List<Standing> out = new ArrayList<>();
        table.forEach((entry, r) -> out.add(new Standing(entry, names.get(entry), r[0],
                r[1] * WIN_POINTS + r[2] * DRAW_POINTS, r[1], r[2], r[3])));
        out.sort(java.util.Comparator.comparingInt(Standing::points).reversed()
                .thenComparing(java.util.Comparator.comparingInt(Standing::wins).reversed())
                .thenComparingInt(Standing::seed));
        return out;
    }

    /** An entry; {@code seed} 0 before seeding. */
    public record Entry(long playerId, String name, int seed) { }

    /** In a teams' tournament, {@code playerA}, {@code playerB} and {@code winner} are teams' ids (D-44). */
    public record Match(int round, int slot, Long playerA, Long playerB, int state, String matchUid, Instant readyAt,
                        Long winner) { }

    /** A team's entry in a teams' tournament; {@code seed} 0 before seeding. */
    public record TeamEntry(long teamId, String name, int seed, List<Rostered> roster) { }

    /** A player on a team's roster. */
    public record Rostered(long playerId, String name) { }

    private final Tx tx;

    public TournamentRepository(DataSource dataSource) {
        this.tx = new Tx(dataSource);
    }

    /** A duels' tournament. */
    public long create(String name, int maxEntries, Instant registrationEnds, Instant startsAt, int roundMinutes,
                       long prize1, long prize2, long prize3) throws SQLException {
        return create(name, maxEntries, registrationEnds, startsAt, roundMinutes, prize1, prize2, prize3,
                MatchResultRepository.MODE_DUEL);
    }

    public long create(String name, int maxEntries, Instant registrationEnds, Instant startsAt, int roundMinutes,
                       long prize1, long prize2, long prize3, int mode) throws SQLException {
        return create(name, maxEntries, registrationEnds, startsAt, roundMinutes, prize1, prize2, prize3, mode, ELIMINATION);
    }

    /** {@code format}: {@link #ELIMINATION} or {@link #ROUND_ROBIN} (04 §6). */
    public long create(String name, int maxEntries, Instant registrationEnds, Instant startsAt, int roundMinutes,
                       long prize1, long prize2, long prize3, int mode, int format) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO tournament (name, state, max_entries,"
                    + " registration_ends, starts_at, round_minutes, prize_1, prize_2, prize_3, mode, format)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setInt(2, REGISTRATION);
                ps.setInt(3, maxEntries);
                ps.setTimestamp(4, Timestamp.from(registrationEnds));
                ps.setTimestamp(5, Timestamp.from(startsAt));
                ps.setInt(6, roundMinutes);
                ps.setLong(7, prize1);
                ps.setLong(8, prize2);
                ps.setLong(9, prize3);
                ps.setInt(10, mode);
                ps.setInt(11, format);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        });
    }

    /** One entry a player, while registering and before the deadline, up to the entries allowed. */
    public Registration register(long id, long playerId, Instant now) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT state, registration_ends, max_entries FROM tournament WHERE id = ? FOR UPDATE")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Registration.NO_SUCH_TOURNAMENT;
                    }
                    if (rs.getInt(1) != REGISTRATION || !now.isBefore(rs.getTimestamp(2).toInstant())) {
                        return Registration.CLOSED;
                    }
                    // Ten rated duels, the mark that lists a player on the duel board (Q-43, Q-40).
                    if (count(c, "SELECT COUNT(*) FROM player WHERE id = ? AND rated_duels >= " + RatingBoards.MIN_RATED,
                            playerId) == 0) {
                        return Registration.TOO_FEW_RATED;
                    }
                    if (count(c, "SELECT COUNT(*) FROM tournament_entry WHERE tournament_id = ?", id) >= rs.getInt(3)) {
                        return Registration.FULL;
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO tournament_entry (tournament_id, player_id, registered_at) VALUES (?, ?, ?)")) {
                ps.setLong(1, id);
                ps.setLong(2, playerId);
                ps.setTimestamp(3, Timestamp.from(now));
                ps.executeUpdate();
            } catch (SQLException e) {
                if (e.getErrorCode() == DUPLICATE_ENTRY) {
                    return Registration.ALREADY;
                }
                throw e;
            }
            return Registration.OK;
        });
    }

    /** Withdraws the player's entry while registering and before the deadline. */
    public boolean withdraw(long id, long playerId, Instant now) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE e FROM tournament_entry e JOIN tournament t"
                    + " ON t.id = e.tournament_id WHERE e.tournament_id = ? AND e.player_id = ? AND t.state = ?"
                    + " AND t.registration_ends > ?")) {
                ps.setLong(1, id);
                ps.setLong(2, playerId);
                ps.setInt(3, REGISTRATION);
                ps.setTimestamp(4, Timestamp.from(now));
                return ps.executeUpdate() == 1;
            }
        });
    }

    /**
     * A team's entry, with its roster (Q-19): as a player's, one a team, while registering and
     * before the deadline, up to the entries allowed; a roster player already on another fails it
     * as {@code ALREADY}.
     */
    public Registration registerTeam(long id, long teamId, List<Long> roster, Instant now) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT state, registration_ends, max_entries FROM tournament WHERE id = ? FOR UPDATE")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Registration.NO_SUCH_TOURNAMENT;
                    }
                    if (rs.getInt(1) != REGISTRATION || !now.isBefore(rs.getTimestamp(2).toInstant())) {
                        return Registration.CLOSED;
                    }
                    // The team's own ten rated team matches: a team match rates the team, not its players (Q-43).
                    if (count(c, "SELECT COUNT(*) FROM team WHERE id = ? AND rated_matches >= " + RatingBoards.MIN_RATED,
                            teamId) == 0) {
                        return Registration.TOO_FEW_RATED;
                    }
                    if (count(c, "SELECT COUNT(*) FROM tournament_team_entry WHERE tournament_id = ?", id)
                            >= rs.getInt(3)) {
                        return Registration.FULL;
                    }
                }
            }
            try (PreparedStatement entry = c.prepareStatement("INSERT INTO tournament_team_entry (tournament_id,"
                    + " team_id, registered_at) VALUES (?, ?, ?)");
                 PreparedStatement member = c.prepareStatement(
                         "INSERT INTO tournament_roster (tournament_id, team_id, player_id) VALUES (?, ?, ?)")) {
                entry.setLong(1, id);
                entry.setLong(2, teamId);
                entry.setTimestamp(3, Timestamp.from(now));
                entry.executeUpdate();
                // Ascending, as every path locks players: each insert's foreign key locks one (D-37).
                for (long player : roster.stream().sorted().toList()) {
                    member.setLong(1, id);
                    member.setLong(2, teamId);
                    member.setLong(3, player);
                    member.executeUpdate();
                }
            } catch (SQLException e) {
                if (e.getErrorCode() == DUPLICATE_ENTRY) {
                    c.rollback();                      // the entry too, if a roster player failed it
                    return Registration.ALREADY;
                }
                throw e;
            }
            return Registration.OK;
        });
    }

    /** Withdraws a team's entry, and its roster, while registering and before the deadline. */
    public boolean withdrawTeam(long id, long teamId, Instant now) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE e FROM tournament_team_entry e JOIN tournament t"
                    + " ON t.id = e.tournament_id WHERE e.tournament_id = ? AND e.team_id = ? AND t.state = ?"
                    + " AND t.registration_ends > ?")) {
                ps.setLong(1, id);
                ps.setLong(2, teamId);
                ps.setInt(3, REGISTRATION);
                ps.setTimestamp(4, Timestamp.from(now));
                if (ps.executeUpdate() != 1) {
                    return false;
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM tournament_roster WHERE tournament_id = ? AND team_id = ?")) {
                ps.setLong(1, id);
                ps.setLong(2, teamId);
                ps.executeUpdate();
            }
            return true;
        });
    }

    /** A teams' tournament's entries, by seed once seeded, by registration before, each with its roster. */
    public List<TeamEntry> teamEntries(long id) throws SQLException {
        return tx.execute(c -> {
            List<TeamEntry> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT e.team_id, t.name, e.seed FROM tournament_team_entry e"
                    + " LEFT JOIN team t ON t.id = e.team_id WHERE e.tournament_id = ?"
                    + " ORDER BY e.seed IS NULL, e.seed, e.registered_at, e.team_id")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new TeamEntry(rs.getLong(1), rs.getString(2), rs.getInt(3), roster(c, id, rs.getLong(1))));
                    }
                }
            }
            return List.copyOf(out);
        });
    }

    /** A team's roster in a tournament; empty for a team not entered. */
    public List<Rostered> rosterOf(long id, long teamId) throws SQLException {
        return tx.execute(c -> roster(c, id, teamId));
    }

    /** Those of a team's roster still in the team: whom its match's tickets are for (Q-19). */
    public List<Rostered> stillOnTeam(long id, long teamId) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT r.player_id, p.display_name FROM tournament_roster r"
                    + " JOIN player p ON p.id = r.player_id JOIN team_member m ON m.player_id = r.player_id"
                    + " AND m.team_id = r.team_id WHERE r.tournament_id = ? AND r.team_id = ? ORDER BY r.player_id")) {
                ps.setLong(1, id);
                ps.setLong(2, teamId);
                List<Rostered> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Rostered(rs.getLong(1), rs.getString(2)));
                    }
                }
                return List.copyOf(out);
            }
        });
    }

    /** A teams' tournament's entries in seed order: by team rating, then by who registered first (Q-19). */
    public List<Long> teamsBySeed(long id) throws SQLException {
        return tx.execute(c -> seedOrder(c, id, true, false));
    }

    /** The entries, first seed first; {@code locked}, with a locking read, as the deadline reads them. */
    private static List<Long> seedOrder(Connection c, long id, boolean teams, boolean locked) throws SQLException {
        String sql = teams
                ? "SELECT e.team_id FROM tournament_team_entry e LEFT JOIN team t ON t.id = e.team_id"
                        + " WHERE e.tournament_id = ? ORDER BY t.rating DESC, e.registered_at, e.team_id" // a disbanded team's NULL last
                : "SELECT e.player_id FROM tournament_entry e JOIN player p ON p.id = e.player_id"
                        + " WHERE e.tournament_id = ? ORDER BY p.rating_duel DESC, e.registered_at, e.player_id";
        try (PreparedStatement ps = c.prepareStatement(sql + (locked ? " FOR SHARE OF e" : ""))) {
            ps.setLong(1, id);
            List<Long> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getLong(1));
                }
            }
            return List.copyOf(out);
        }
    }

    /** The tournament, or null. */
    public Tournament get(long id) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(SELECT_TOURNAMENT + " WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? tournament(rs) : null;
                }
            }
        });
    }

    /** Tournaments in any of these states, by id. */
    public List<Tournament> inStates(int... states) throws SQLException {
        return tx.execute(c -> {
            StringBuilder marks = new StringBuilder();
            for (int i = 0; i < states.length; i++) {
                marks.append(i == 0 ? "?" : ",?");
            }
            try (PreparedStatement ps = c.prepareStatement(SELECT_TOURNAMENT + " WHERE state IN (" + marks + ") ORDER BY id")) {
                for (int i = 0; i < states.length; i++) {
                    ps.setInt(i + 1, states[i]);
                }
                List<Tournament> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(tournament(rs));
                    }
                }
                return List.copyOf(out);
            }
        });
    }

    /** Its entries, by seed once seeded, by registration before. */
    public List<Entry> entries(long id) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT e.player_id, p.display_name, e.seed"
                    + " FROM tournament_entry e JOIN player p ON p.id = e.player_id WHERE e.tournament_id = ?"
                    + " ORDER BY e.seed IS NULL, e.seed, e.registered_at, e.player_id")) {
                ps.setLong(1, id);
                List<Entry> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Entry(rs.getLong(1), rs.getString(2), rs.getInt(3)));
                    }
                }
                return List.copyOf(out);
            }
        });
    }

    /** The entries in seed order: by duel rating, then by who registered first. */
    public List<Long> bySeed(long id) throws SQLException {
        return tx.execute(c -> seedOrder(c, id, false, false));
    }

    /** Its matches, by round and slot. */
    public List<Match> matches(long id) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT round, slot, player_a, player_b, state, match_uid,"
                    + " ready_at, winner FROM tournament_match WHERE tournament_id = ? ORDER BY round, slot")) {
                ps.setLong(1, id);
                List<Match> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Timestamp ready = rs.getTimestamp(7);
                        out.add(new Match(rs.getInt(1), rs.getInt(2), nullable(rs, 3), nullable(rs, 4), rs.getInt(5),
                                rs.getString(6), ready == null ? null : ready.toInstant(), nullable(rs, 8)));
                    }
                }
                return List.copyOf(out);
            }
        });
    }

    /** What the deadline came to: seeded, cancelled for fewer than two entries, or moved on by another. */
    public enum Closed { SEEDED, CANCELLED, STALE }

    /**
     * The deadline: fewer than two entries, cancelled; otherwise seeded, from the entries as they
     * are once the tournament's row is held. A registration and a withdrawal take that row too, so
     * none changes the entries after it is taken, and every one committed before is read (T-36).
     */
    public Closed close(long id, int version) throws SQLException {
        return tx.execute(c -> {
            boolean teams;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT mode FROM tournament WHERE id = ? AND state = ? AND version = ? FOR UPDATE")) {
                ps.setLong(1, id);
                ps.setInt(2, REGISTRATION);
                ps.setInt(3, version);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Closed.STALE;
                    }
                    teams = rs.getInt(1) == MatchResultRepository.MODE_TEAMS;
                }
            }
            List<Long> bySeed = seedOrder(c, id, teams, true);
            if (bySeed.size() < 2) {
                advance(c, id, REGISTRATION, version, CANCELLED, "");
                return Closed.CANCELLED;
            }
            advance(c, id, REGISTRATION, version, SEEDED, "");
            seedWith(c, id, teams, formatOf(c, id) == ROUND_ROBIN, bySeed);
            return Closed.SEEDED;
        });
    }

    public boolean cancel(long id, int version) throws SQLException {
        return tx.execute(c -> advance(c, id, REGISTRATION, version, CANCELLED, ""));
    }

    /**
     * Seeds it, {@code bySeed} first to last: the seeds written, and every round's matches, the
     * first round's with their players, a seed with nobody against it through to the second.
     */
    public boolean seed(long id, int version, List<Long> bySeed) throws SQLException {
        return tx.execute(c -> {
            if (!advance(c, id, REGISTRATION, version, SEEDED, "")) {
                return false;
            }
            seedWith(c, id, modeOf(c, id) == MatchResultRepository.MODE_TEAMS, formatOf(c, id) == ROUND_ROBIN, bySeed);
            return true;
        });
    }

    private static void seedWith(Connection c, long id, boolean teams, boolean roundRobin, List<Long> bySeed)
            throws SQLException {
        for (int i = 0; i < bySeed.size(); i++) {
            try (PreparedStatement ps = c.prepareStatement(teams
                    ? "UPDATE tournament_team_entry SET seed = ? WHERE tournament_id = ? AND team_id = ?"
                    : "UPDATE tournament_entry SET seed = ? WHERE tournament_id = ? AND player_id = ?")) {
                ps.setInt(1, i + 1);
                ps.setLong(2, id);
                ps.setLong(3, bySeed.get(i));
                ps.executeUpdate();
            }
        }
        if (roundRobin) {                               // every round, every pair once, written whole (04 §6)
            List<List<int[]>> rounds = RoundRobin.schedule(bySeed.size());
            for (int round = 0; round < rounds.size(); round++) {
                for (int slot = 0; slot < rounds.get(round).size(); slot++) {
                    int[] pair = rounds.get(round).get(slot);
                    insertMatch(c, id, round + 1, slot);
                    try (PreparedStatement ps = c.prepareStatement("UPDATE tournament_match SET player_a = ?, player_b = ?"
                            + " WHERE tournament_id = ? AND round = ? AND slot = ?")) {
                        ps.setLong(1, bySeed.get(pair[0] - 1));
                        ps.setLong(2, bySeed.get(pair[1] - 1));
                        ps.setLong(3, id);
                        ps.setInt(4, round + 1);
                        ps.setInt(5, slot);
                        ps.executeUpdate();
                    }
                }
            }
            return;
        }
        int size = Bracket.sizeFor(bySeed.size());
        int[] order = Bracket.order(size);
        for (int round = 1, matches = size / 2; matches >= 1; round++, matches /= 2) {
            for (int slot = 0; slot < matches; slot++) {
                insertMatch(c, id, round, slot);
            }
        }
        for (int slot = 0; slot < size / 2; slot++) {
            Long a = order[2 * slot] <= bySeed.size() ? bySeed.get(order[2 * slot] - 1) : null;
            Long b = order[2 * slot + 1] <= bySeed.size() ? bySeed.get(order[2 * slot + 1] - 1) : null;
            try (PreparedStatement ps = c.prepareStatement("UPDATE tournament_match SET player_a = ?, player_b = ?"
                    + " WHERE tournament_id = ? AND round = 1 AND slot = ?")) {
                ps.setObject(1, a);
                ps.setObject(2, b);
                ps.setLong(3, id);
                ps.setInt(4, slot);
                ps.executeUpdate();
            }
            if (a == null || b == null) {
                finishMatch(c, id, 1, slot, a != null ? a : b);        // a bye
            }
        }
    }

    /** Seeded to running, at round 1. */
    public boolean start(long id, int version) throws SQLException {
        return tx.execute(c -> advance(c, id, SEEDED, version, RUNNING, ", current_round = 1"));
    }

    /** A pending match made: its match id, and when its tickets were written. */
    public boolean ready(long id, int round, int slot, String matchUid, Instant now) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE tournament_match SET state = ?, match_uid = ?,"
                    + " ready_at = ? WHERE tournament_id = ? AND round = ? AND slot = ? AND state = ?")) {
                ps.setInt(1, READY);
                ps.setString(2, matchUid);
                ps.setTimestamp(3, Timestamp.from(now));
                ps.setLong(4, id);
                ps.setInt(5, round);
                ps.setInt(6, slot);
                ps.setInt(7, PENDING);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** A ready match decided: its winner, placed in the next round. */
    public boolean decide(long id, int round, int slot, long winner) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM tournament_match WHERE tournament_id = ?"
                    + " AND round = ? AND slot = ? AND state = ? FOR UPDATE")) {
                ps.setLong(1, id);
                ps.setInt(2, round);
                ps.setInt(3, slot);
                ps.setInt(4, READY);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                }
            }
            if (formatOf(c, id) == ROUND_ROBIN) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE tournament_match SET state = ?, winner = ?"
                        + " WHERE tournament_id = ? AND round = ? AND slot = ?")) {
                    ps.setInt(1, DONE);                 // and nothing moves on (04 §6)
                    ps.setLong(2, winner);
                    ps.setLong(3, id);
                    ps.setInt(4, round);
                    ps.setInt(5, slot);
                    ps.executeUpdate();
                }
                return true;
            }
            finishMatch(c, id, round, slot, winner);
            return true;
        });
    }

    /** The current round over: the next one, and when this one ended, for the minutes between. */
    public boolean endRound(long id, int version, Instant now) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE tournament SET current_round = current_round + 1,"
                    + " round_ended_at = ?, version = version + 1 WHERE id = ? AND state = ? AND version = ?")) {
                ps.setTimestamp(1, Timestamp.from(now));
                ps.setLong(2, id);
                ps.setInt(3, RUNNING);
                ps.setInt(4, version);
                return ps.executeUpdate() == 1;
            }
        });
    }

    public boolean finish(long id, int version) throws SQLException {
        return tx.execute(c -> advance(c, id, RUNNING, version, FINISHED, ""));
    }

    /**
     * What MySQL recorded for a match (D-40): null before any result; the players placed first
     * otherwise, one for a win or a walkover, both for a draw.
     */
    public List<Long> resultOf(String matchUid) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT mp.player_id, mp.placement FROM matches m"
                    + " JOIN match_player mp ON mp.match_id = m.id WHERE m.match_uid = ?")) {
                ps.setString(1, matchUid);
                List<Long> first = new ArrayList<>();
                boolean any = false;
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        any = true;
                        if (rs.getInt(2) == 1) {
                            first.add(rs.getLong(1));
                        }
                    }
                }
                return any ? List.copyOf(first) : null;
            }
        });
    }

    /** Whether the match's result was cut short (V18, Q-45): its placements are not a finish. */
    public boolean cutShort(String matchUid) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT cut_short FROM matches WHERE match_uid = ?")) {
                ps.setString(1, matchUid);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getBoolean(1);
                }
            }
        });
    }

    /**
     * A team match's result by side (D-44): null before any result; the sides placed first
     * otherwise, one for a win or a walkover, both for a draw.
     */
    public List<Integer> sidesPlacedFirst(String matchUid) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT mp.team, mp.placement FROM matches m"
                    + " JOIN match_player mp ON mp.match_id = m.id WHERE m.match_uid = ?")) {
                ps.setString(1, matchUid);
                java.util.TreeSet<Integer> first = new java.util.TreeSet<>();
                boolean any = false;
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        any = true;
                        if (rs.getInt(2) == 1) {
                            first.add(rs.getInt(1));
                        }
                    }
                }
                return any ? List.copyOf(first) : null;
            }
        });
    }

    // ---- inside a transaction ------------------------------------------------------------

    private static final String SELECT_TOURNAMENT = "SELECT id, name, state, version, max_entries, registration_ends,"
            + " starts_at, round_minutes, prize_1, prize_2, prize_3, current_round, round_ended_at, mode, format FROM tournament";

    private static Tournament tournament(ResultSet rs) throws SQLException {
        Timestamp ended = rs.getTimestamp(13);
        return new Tournament(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                rs.getTimestamp(6).toInstant(), rs.getTimestamp(7).toInstant(), rs.getInt(8), rs.getLong(9),
                rs.getLong(10), rs.getLong(11), rs.getInt(12), ended == null ? null : ended.toInstant(), rs.getInt(14),
                rs.getInt(15));
    }

    private static int formatOf(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT format FROM tournament WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static int modeOf(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT mode FROM tournament WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static List<Rostered> roster(Connection c, long id, long teamId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT r.player_id, p.display_name FROM tournament_roster r"
                + " JOIN player p ON p.id = r.player_id WHERE r.tournament_id = ? AND r.team_id = ? ORDER BY r.player_id")) {
            ps.setLong(1, id);
            ps.setLong(2, teamId);
            List<Rostered> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Rostered(rs.getLong(1), rs.getString(2)));
                }
            }
            return List.copyOf(out);
        }
    }

    /** The conditional update every transition is: from a state, at a version, to the next. */
    private static boolean advance(Connection c, long id, int from, int version, int to, String also) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE tournament SET state = ?, version = version + 1" + also
                + " WHERE id = ? AND state = ? AND version = ?")) {
            ps.setInt(1, to);
            ps.setLong(2, id);
            ps.setInt(3, from);
            ps.setInt(4, version);
            return ps.executeUpdate() == 1;
        }
    }

    private static void insertMatch(Connection c, long id, int round, int slot) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO tournament_match (tournament_id, round, slot, state) VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, id);
            ps.setInt(2, round);
            ps.setInt(3, slot);
            ps.setInt(4, PENDING);
            ps.executeUpdate();
        }
    }

    /** Done, with its winner, who goes into the next round's slot, as a or b by which half they came from. */
    private static void finishMatch(Connection c, long id, int round, int slot, long winner) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE tournament_match SET state = ?, winner = ?"
                + " WHERE tournament_id = ? AND round = ? AND slot = ?")) {
            ps.setInt(1, DONE);
            ps.setLong(2, winner);
            ps.setLong(3, id);
            ps.setInt(4, round);
            ps.setInt(5, slot);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE tournament_match SET "
                + (slot % 2 == 0 ? "player_a" : "player_b") + " = ? WHERE tournament_id = ? AND round = ? AND slot = ?")) {
            ps.setLong(1, winner);
            ps.setLong(2, id);
            ps.setInt(3, round + 1);
            ps.setInt(4, slot / 2);
            ps.executeUpdate();                         // none after the final
        }
    }

    private static int count(Connection c, String sql, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static Long nullable(ResultSet rs, int column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    /**
     * Deletes the finished and cancelled tournaments that were to start {@link #KEPT} before
     * {@code now}, each with its entries, rosters and bracket, one a transaction (D-38, Q-47).
     * Nothing changes a tournament once it is either, so nothing is locked first: workers purging
     * the same one delete in the same order and queue. The prizes paid stay in the ledger.
     *
     * @return the tournaments deleted
     */
    public int purge(Instant now, int batch) throws SQLException {
        Timestamp before = Timestamp.from(now.minus(KEPT));
        int total = 0;
        while (true) {
            List<Long> ids = tx.execute(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT id FROM tournament WHERE state IN (?, ?)"
                        + " AND starts_at < ? ORDER BY id LIMIT ?")) {
                    ps.setInt(1, FINISHED);
                    ps.setInt(2, CANCELLED);
                    ps.setTimestamp(3, before);
                    ps.setInt(4, batch);
                    List<Long> found = new ArrayList<>();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            found.add(rs.getLong(1));
                        }
                    }
                    return found;
                }
            });
            for (long id : ids) {
                total += tx.execute(c -> {
                    for (String table : new String[] {"tournament_roster", "tournament_team_entry", "tournament_entry",
                            "tournament_match"}) {
                        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE tournament_id = ?")) {
                            ps.setLong(1, id);
                            ps.executeUpdate();
                        }
                    }
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM tournament WHERE id = ?")) {
                        ps.setLong(1, id);
                        return ps.executeUpdate();
                    }
                });
            }
            if (ids.size() < batch) {
                return total;
            }
        }
    }
}
