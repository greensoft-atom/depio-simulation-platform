package com.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.backend.persistence.EconomyRepository.Outcome;
import com.backend.persistence.MatchResultRepository.MatchResult;
import com.backend.persistence.MatchResultRepository.PlayerResult;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runs against a real MySQL, on purpose.
 *
 * Everything worth testing here is transactional: FOR UPDATE ordering, a plain INSERT whose
 * duplicate-key error (1062) is the duplicate check, unique-key violations as the idempotency path, and the
 * CHECK constraint as the last line of defence. An in-memory fake reproduces none of it,
 * so tests against one would pass while production broke
 * (docs development/01 §3).
 *
 * Set JDBC_URL / DB_USER / DB_PASSWORD to point elsewhere.
 */
class PersistenceTest {

    private static final String URL = System.getenv().getOrDefault("JDBC_URL",
            "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "backend");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD",
            "backend-dev-password");

    private static Database db;
    private static MatchResultRepository matches;
    private static EconomyRepository economy;

    @BeforeAll
    static void setUp() {
        db = new Database(URL, USER, PASSWORD, 8);
        db.resetForTests();
        // A level every 1 000 xp: plain enough that every expected level below is obvious.
        // The real curve is worker's AccountLevels, which this module cannot see.
        matches = new MatchResultRepository(db.dataSource(), xp -> 1 + (int) (xp / 1_000));
        economy = new EconomyRepository(db.dataSource());
    }

    @AfterAll
    static void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void freshSchema() {
        db.resetForTests();
    }

    // ---- helpers ---------------------------------------------------------------------

    private static long createPlayer(String name, long coins) throws SQLException {
        try (Connection c = db.dataSource().getConnection()) {
            long id;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO account (username, password_hash) VALUES (?, ?)",
                    PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setBytes(2, new byte[] {1, 2, 3});
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO player (id, public_code, display_name, coins) VALUES (?,?,?,?)")) {
                ps.setLong(1, id);
                ps.setString(2, String.format("P%011d", id));
                ps.setString(3, name);
                ps.setLong(4, coins);
                ps.executeUpdate();
            }
            return id;
        }
    }

    private static MatchResult resultFor(String uid, List<Long> playerIds, int coinsEach) {
        List<PlayerResult> players = playerIds.stream()
                .map(id -> new PlayerResult(id, 0, 1, 5, 2, 1000, 250, 12, coinsEach, true, 300))
                .toList();
        long now = System.currentTimeMillis();
        return new MatchResult(uid, 1, 0, "arena-1", now - 300_000, now, players);
    }

    private static long scalar(String sql, long arg) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    // ---- match results ----------------------------------------------------------------

    private static MatchResult duel(String uid, long first, int firstPlaced, long second, int secondPlaced) {
        long now = System.currentTimeMillis();
        // A delta in each player's result that the repository must ignore: a rated match's is its own.
        return new MatchResult(uid, 1, 1, "arena-1", now - 60_000, now, List.of(
                new PlayerResult(first, 0, firstPlaced, 3, 0, 300, 315, 999, 60, firstPlaced == 1, 60),
                new PlayerResult(second, 1, secondPlaced, 0, 3, 20, 20, 999, 10, secondPlaced == 1, 60)), true);
    }

    @Test
    @DisplayName("a rated duel moves both ratings from the rows it locked, counts the duel, and not twice")
    void aRatedDuelMovesRatings() throws Exception {
        long ada = createPlayer("ada", 0);
        long bob = createPlayer("bob", 0);
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE player SET rating_duel = 1400, rated_duels = 31 WHERE id = ?")) {
            ps.setLong(1, bob);
            ps.executeUpdate();
        }
        List<String> given = new java.util.concurrent.CopyOnWriteArrayList<>();
        MatchResultRepository rated = new MatchResultRepository(db.dataSource(), xp -> 1,
                (ratings, before, placements) -> {
                    given.add(java.util.Arrays.toString(ratings) + java.util.Arrays.toString(before)
                            + java.util.Arrays.toString(placements));
                    return new int[] {placements[0] < placements[1] ? 20 : 0, placements[0] < placements[1] ? -9 : 0};
                });

        MatchResult won = duel("01JCDUEL000000000000000001", ada, 1, bob, 2);
        rated.apply(won);
        assertThat(given).containsExactly("[1200, 1400][0, 31][1, 2]");
        assertThat(scalar("SELECT rating_duel FROM player WHERE id = ?", ada)).isEqualTo(1_220);
        assertThat(scalar("SELECT rating_duel FROM player WHERE id = ?", bob)).isEqualTo(1_391);
        assertThat(scalar("SELECT rated_duels FROM player WHERE id = ?", ada)).isEqualTo(1);
        assertThat(scalar("SELECT rated_duels FROM player WHERE id = ?", bob)).isEqualTo(32);
        assertThat(scalar("SELECT rating_delta FROM match_player WHERE player_id = ?", bob))
                .as("what was applied is what is recorded").isEqualTo(-9);

        // Redelivered: the deltas are worked out again, since only the insert can tell a
        // redelivery, and none is applied.
        rated.apply(won);
        assertThat(given).hasSize(2);
        assertThat(scalar("SELECT rating_duel FROM player WHERE id = ?", ada)).isEqualTo(1_220);
        assertThat(scalar("SELECT rated_duels FROM player WHERE id = ?", ada)).isEqualTo(1);

        rated.apply(duel("01JCDUEL000000000000000002", ada, 1, bob, 1));
        assertThat(given).last().as("a draw, from the ratings as they now are").isEqualTo("[1220, 1391][1, 32][1, 1]");

        long now = System.currentTimeMillis();
        rated.apply(new MatchResult("01JCDUEL000000000000000003", 1, 1, "arena-1", now - 1_000, now,
                List.of(new PlayerResult(ada, 0, 1, 0, 0, 0, 0, 999, 50, true, 30)), true));
        assertThat(given).as("a walkover is not rated").hasSize(3);
        assertThat(scalar("SELECT rating_duel FROM player WHERE id = ?", ada)).isEqualTo(1_220);
        assertThat(scalar("SELECT rated_duels FROM player WHERE id = ?", ada)).isEqualTo(2);
    }

    private static MatchResult teams(String uid, long[] one, int onePlaced, long[] two, int twoPlaced) {
        long now = System.currentTimeMillis();
        List<PlayerResult> players = new java.util.ArrayList<>();
        for (long id : one) {
            players.add(new PlayerResult(id, 1, onePlaced, 1, 0, 100, 100, 999, 10, onePlaced == 1, 60));
        }
        for (long id : two) {
            players.add(new PlayerResult(id, 2, twoPlaced, 0, 1, 20, 20, 999, 10, twoPlaced == 1, 60));
        }
        return new MatchResult(uid, 1, MatchResultRepository.MODE_TVT, "arena-1", now - 60_000, now, players, true);
    }

    @Test
    @DisplayName("a rated team match moves each player by the teams' means and their own count, on the team rating, and one team alone moves nothing (D-26)")
    void aRatedTeamMatchMovesTeamRatings() throws Exception {
        long[] one = {createPlayer("t1a", 0), createPlayer("t1b", 0), createPlayer("t1c", 0)};
        long[] two = {createPlayer("t2a", 0), createPlayer("t2b", 0), createPlayer("t2c", 0)};
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE player SET rating_tvt = 1500, rated_tvts = 40 WHERE id = ?")) {
            ps.setLong(1, two[0]);
            ps.executeUpdate();
        }
        List<String> given = new java.util.concurrent.CopyOnWriteArrayList<>();
        MatchResultRepository rated = new MatchResultRepository(db.dataSource(), xp -> 1,
                (ratings, before, placements) -> {
                    given.add(java.util.Arrays.toString(ratings) + before[0] + java.util.Arrays.toString(placements));
                    return new int[] {placements[0] < placements[1] ? 20 : placements[0] > placements[1] ? -15 : 0, 0};
                });

        rated.apply(teams("01JCTVT0000000000000000001", one, 1, two, 2));
        // Team 1's mean is 1 200, team 2's (1 500 + 1 200 + 1 200) / 3 = 1 300.
        assertThat(given).containsExactlyInAnyOrder(
                "[1200, 1300]0[1, 2]", "[1200, 1300]0[1, 2]", "[1200, 1300]0[1, 2]",
                "[1300, 1200]40[2, 1]", "[1300, 1200]0[2, 1]", "[1300, 1200]0[2, 1]");
        for (long id : one) {
            assertThat(scalar("SELECT rating_tvt FROM player WHERE id = ?", id)).isEqualTo(1_220);
            assertThat(scalar("SELECT rated_tvts FROM player WHERE id = ?", id)).isEqualTo(1);
            assertThat(scalar("SELECT rating_duel FROM player WHERE id = ?", id)).as("the duel's, untouched").isEqualTo(1_200);
        }
        assertThat(scalar("SELECT rating_tvt FROM player WHERE id = ?", two[0])).isEqualTo(1_485);
        assertThat(scalar("SELECT rated_tvts FROM player WHERE id = ?", two[0])).isEqualTo(41);
        assertThat(scalar("SELECT rating_delta FROM match_player WHERE player_id = ?", two[1])).isEqualTo(-15);

        rated.apply(teams("01JCTVT0000000000000000002", one, 1, new long[0], 2));
        assertThat(given).as("one team alone is a walkover: not rated").hasSize(6);
        assertThat(scalar("SELECT rating_tvt FROM player WHERE id = ?", one[0])).isEqualTo(1_220);
        assertThat(scalar("SELECT rated_tvts FROM player WHERE id = ?", one[0])).isEqualTo(1);
    }

    @Test
    @DisplayName("a team match moves no player's own rating, a duel's nor a team-vs-team's, even one against one (Q-18)")
    void aTeamsMatchMovesNoPlayersRating() throws Exception {
        long a = createPlayer("tm1", 0);
        long b = createPlayer("tm2", 0);
        List<String> given = new java.util.concurrent.CopyOnWriteArrayList<>();
        MatchResultRepository rated = new MatchResultRepository(db.dataSource(), xp -> 1,
                (ratings, before, placements) -> {
                    given.add(java.util.Arrays.toString(ratings));
                    return new int[] {20, -20};
                });
        long now = System.currentTimeMillis();
        rated.apply(new MatchResult("01JCTEAMS00000000000000001", 1, MatchResultRepository.MODE_TEAMS, "arena-1",
                now - 60_000, now, List.of(new PlayerResult(a, 1, 1, 1, 0, 100, 100, 999, 10, true, 60),
                        new PlayerResult(b, 2, 2, 0, 1, 20, 20, 999, 10, false, 60)), true));

        assertThat(given).as("no player's rating worked out").isEmpty();
        for (long id : new long[] {a, b}) {
            assertThat(scalar("SELECT rating_duel FROM player WHERE id = ?", id)).isEqualTo(1_200);
            assertThat(scalar("SELECT rated_duels FROM player WHERE id = ?", id)).isZero();
            assertThat(scalar("SELECT rating_tvt FROM player WHERE id = ?", id)).isEqualTo(1_200);
            assertThat(scalar("SELECT rated_tvts FROM player WHERE id = ?", id)).isZero();
            assertThat(scalar("SELECT rating_delta FROM match_player WHERE player_id = ?", id)).isZero();
        }
    }

    /** A team match's result: side 1's players placed {@code onePlaced}, side 2's {@code twoPlaced}. */
    private static MatchResult teamMatch(String uid, long[] one, int onePlaced, long[] two, int twoPlaced) {
        long now = System.currentTimeMillis();
        List<PlayerResult> players = new java.util.ArrayList<>();
        for (long id : one) {
            players.add(new PlayerResult(id, 1, onePlaced, 1, 0, 100, 100, 999, 10, onePlaced == 1, 60));
        }
        for (long id : two) {
            players.add(new PlayerResult(id, 2, twoPlaced, 0, 1, 20, 20, 999, 10, twoPlaced == 1, 60));
        }
        return new MatchResult(uid, 1, MatchResultRepository.MODE_TEAMS, "arena-1", now - 60_000, now, players, true);
    }

    /** A team led by the first, the rest invited and in. */
    private static long team(TeamRepository teams, String name, long... players) throws SQLException {
        java.time.Instant now = java.time.Instant.now();
        long id = teams.create(players[0], name, now).teamId();
        for (int i = 1; i < players.length; i++) {
            teams.invite(players[0], players[i], now);
            teams.answer(players[i], id, true, now);
        }
        return id;
    }

    private static String record(long team) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT rating, rated_matches, wins, losses, draws FROM team WHERE id = ?")) {
            ps.setLong(1, team);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) + " " + rs.getInt(2) + " " + rs.getInt(3) + "/" + rs.getInt(4) + "/" + rs.getInt(5);
            }
        }
    }

    @Test
    @DisplayName("a team match moves the two teams' ratings by Elo between them and counts the result, once (Q-18)")
    void aTeamMatchRatesTheTeams() throws Exception {
        TeamRepository teams = new TeamRepository(db.dataSource(), 30);
        long[] one = {createPlayer("ta1", 0), createPlayer("ta2", 0), createPlayer("ta3", 0)};
        long[] two = {createPlayer("tb1", 0), createPlayer("tb2", 0), createPlayer("tb3", 0)};
        long tanks = team(teams, "Tanks", one);
        long rams = team(teams, "Rams", two);
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE team SET rating = 1300, rated_matches = 40 WHERE id = ?")) {
            ps.setLong(1, rams);
            ps.executeUpdate();
        }
        List<String> given = new java.util.concurrent.CopyOnWriteArrayList<>();
        MatchResultRepository rated = new MatchResultRepository(db.dataSource(), xp -> 1,
                (ratings, before, placements) -> {
                    given.add(java.util.Arrays.toString(ratings) + java.util.Arrays.toString(before)
                            + java.util.Arrays.toString(placements));
                    return placements[0] == placements[1] ? new int[] {3, -3} : new int[] {20, -15};
                });

        rated.apply(teamMatch("01JCTEAMS00000000000000002", one, 1, two, 2));
        assertThat(given).as("the teams, not the players").containsExactly("[1200, 1300][0, 40][1, 2]");
        assertThat(record(tanks)).isEqualTo("1220 1 1/0/0");
        assertThat(record(rams)).isEqualTo("1285 41 0/1/0");
        assertThat(scalar("SELECT COUNT(*) FROM match_team WHERE team_id = ?", tanks)).isEqualTo(1);
        assertThat(scalar("SELECT rating_delta FROM match_team WHERE team_id = ?", rams)).isEqualTo(-15);
        assertThat(scalar("SELECT placement FROM match_team WHERE team_id = ?", rams)).isEqualTo(2);
        assertThat(scalar("SELECT side FROM match_team WHERE team_id = ?", rams)).isEqualTo(2);

        rated.apply(teamMatch("01JCTEAMS00000000000000002", one, 1, two, 2));
        assertThat(record(tanks)).as("applied once").isEqualTo("1220 1 1/0/0");
        assertThat(record(rams)).isEqualTo("1285 41 0/1/0");
        given.clear();

        rated.apply(teamMatch("01JCTEAMS00000000000000003", two, 1, one, 1));
        assertThat(given).as("a draw, the sides as they played").containsExactly("[1285, 1220][41, 1][1, 1]");
        assertThat(record(rams)).isEqualTo("1288 42 0/1/1");
        assertThat(record(tanks)).isEqualTo("1217 2 1/0/1");

        rated.apply(teamMatch("01JCTEAMS00000000000000004", one, 1, new long[0], 2));
        assertThat(given).as("one side alone, a walkover: nothing for the teams").hasSize(1);
        assertThat(record(tanks)).isEqualTo("1217 2 1/0/1");

        MatchResult cut = teamMatch("01JCTEAMS00000000000000010", one, 1, two, 2);
        rated.apply(new MatchResult(cut.matchUid(), cut.kind(), cut.mode(), cut.arena(), cut.startedAtMillis(),
                cut.endedAtMillis(), cut.players(), false));
        assertThat(record(tanks)).as("cut short, so unrated (D-29): nothing for the teams").isEqualTo("1217 2 1/0/1");
    }

    @Test
    @DisplayName("a result cut short is recorded so, and read so by a tournament: its placements are not a finish (V18, Q-45)")
    void aResultCutShortIsRecordedSo() throws Exception {
        MatchResult whole = duel("01JCCUT0000000000000000001", createPlayer("cu1", 0), 1, createPlayer("cu2", 0), 2);
        MatchResultRepository repo = new MatchResultRepository(db.dataSource(), xp -> 1);
        repo.apply(whole);
        repo.apply(new MatchResult("01JCCUT0000000000000000002", whole.kind(), whole.mode(), whole.arena(),
                whole.startedAtMillis(), whole.endedAtMillis(), whole.players(), false, true));
        TournamentRepository tournaments = new TournamentRepository(db.dataSource());
        assertThat(tournaments.cutShort("01JCCUT0000000000000000001")).isFalse();
        assertThat(tournaments.cutShort("01JCCUT0000000000000000002")).isTrue();
        assertThat(tournaments.cutShort("01JCNOSUCHMATCH00000000001")).as("no result").isFalse();
    }

    @Test
    @DisplayName("a side's team is the one its players still share: one who left is passed over; none, two, or one team on both sides is not rated (D-43)")
    void aSidesTeamIsReadWhenApplied() throws Exception {
        TeamRepository teams = new TeamRepository(db.dataSource(), 30);
        long[] one = {createPlayer("sa1", 0), createPlayer("sa2", 0), createPlayer("sa3", 0)};
        long[] two = {createPlayer("sb1", 0), createPlayer("sb2", 0), createPlayer("sb3", 0)};
        long stray = createPlayer("sc1", 0);
        long tanks = team(teams, "Tanks", one);
        long rams = team(teams, "Rams", two);
        team(teams, "Other", stray);
        MatchResultRepository rated = new MatchResultRepository(db.dataSource(), xp -> 1,
                (ratings, before, placements) -> new int[] {10, -10});

        assertThat(teams.leave(one[1], java.time.Instant.now())).isEqualTo(TeamRepository.Outcome.OK);
        rated.apply(teamMatch("01JCTEAMS00000000000000005", one, 1, two, 2));
        assertThat(record(tanks)).as("the two who stayed still name it").isEqualTo("1210 1 1/0/0");
        assertThat(record(rams)).isEqualTo("1190 1 0/1/0");

        rated.apply(teamMatch("01JCTEAMS00000000000000006", new long[] {one[0], stray}, 1, two, 2));
        assertThat(record(rams)).as("a side in two teams").isEqualTo("1190 1 0/1/0");
        rated.apply(teamMatch("01JCTEAMS00000000000000007", new long[] {one[1]}, 1, two, 2));
        assertThat(record(rams)).as("a side in no team").isEqualTo("1190 1 0/1/0");
        rated.apply(teamMatch("01JCTEAMS00000000000000008", new long[] {two[0]}, 1, new long[] {two[1]}, 2));
        assertThat(record(rams)).as("one team on both sides").isEqualTo("1190 1 0/1/0");
        assertThat(scalar("SELECT COUNT(*) FROM match_team WHERE match_id > ?", 0)).isEqualTo(2);
    }

    @Test
    @DisplayName("a team disbanded while its result waits on the team's lock: the match applied, the teams not rated, nothing failed (D-36)")
    void aTeamDisbandedUnderTheLockIsNotRated() throws Exception {
        TeamRepository teams = new TeamRepository(db.dataSource(), 30);
        long[] one = {createPlayer("da1", 0), createPlayer("da2", 0)};
        long[] two = {createPlayer("db1", 0), createPlayer("db2", 0)};
        long tanks = team(teams, "Tanks", one);
        long rams = team(teams, "Rams", two);
        MatchResult result = teamMatch("01JCTEAMS00000000000000011", one, 1, two, 2);
        assertThat(applyWhileLocked(result, tanks, held -> {
            // Tanks disbanded meanwhile, as TeamRepository does it.
            for (String sql : new String[] {"UPDATE player SET team_id = NULL WHERE id IN (" + one[0] + "," + one[1] + ")",
                    "DELETE FROM team_member WHERE team_id = " + tanks, "DELETE FROM team_invite WHERE team_id = " + tanks,
                    "DELETE FROM team WHERE id = " + tanks}) {
                try (java.sql.Statement st = held.createStatement()) {
                    st.executeUpdate(sql);
                }
            }
        }).newPlayers()).as("applied").isEqualTo(4);
        assertThat(record(rams)).as("its opponent gone: not rated").isEqualTo("1200 0 0/0/0");
        assertThat(scalar("SELECT COUNT(*) FROM match_team WHERE match_id > ?", 0)).isZero();
    }

    @Test
    @DisplayName("a side whose players formed a team while its result waited on a lock is not rated: that team was not locked (D-36)")
    void aTeamFormedUnderTheLockIsNotRated() throws Exception {
        TeamRepository teams = new TeamRepository(db.dataSource(), 30);
        long[] one = {createPlayer("fa1", 0), createPlayer("fa2", 0)};
        long[] two = {createPlayer("fb1", 0), createPlayer("fb2", 0)};
        long rams = team(teams, "Rams", two);
        MatchResult result = teamMatch("01JCTEAMS00000000000000013", one, 1, two, 2);
        assertThat(applyWhileLocked(result, rams, held -> team(teams, "Late", one)).newPlayers()).isEqualTo(4);
        assertThat(record(rams)).isEqualTo("1200 0 0/0/0");
        assertThat(scalar("SELECT COUNT(*) FROM match_team WHERE match_id > ?", 0)).isZero();
    }

    private interface WhileLocked {
        void meanwhile(Connection held) throws Exception;
    }

    /**
     * Applies a team match's result, rated as the duel's rule would, while another transaction
     * holds {@code team}'s lock: the apply reads the teams, then waits on it; the other does
     * {@code meanwhile} and commits.
     */
    private static MatchResultRepository.ApplyResult applyWhileLocked(MatchResult result, long team, WhileLocked other)
            throws Exception {
        MatchResultRepository rated = new MatchResultRepository(db.dataSource(), xp -> 1, (r, b, p) -> new int[] {10, -10});
        java.util.concurrent.CompletableFuture<MatchResultRepository.ApplyResult> applied;
        try (Connection held = db.dataSource().getConnection()) {
            held.setAutoCommit(false);
            try (PreparedStatement ps = held.prepareStatement("SELECT id FROM team WHERE id = ? FOR UPDATE")) {
                ps.setLong(1, team);
                ps.executeQuery().close();
            }
            applied = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return rated.apply(result);
                } catch (SQLException e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            });
            awaitStatement("SELECT id, rating, rated_matches FROM team");
            other.meanwhile(held);
            held.commit();
        }
        return applied.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("a redelivery rates no team: sides that did not resolve when it was applied stay unrated, whatever the teams are now (D-36)")
    void aRedeliveryRatesNoTeam() throws Exception {
        TeamRepository teams = new TeamRepository(db.dataSource(), 30);
        long[] one = {createPlayer("ea1", 0), createPlayer("ea2", 0)};
        long[] two = {createPlayer("eb1", 0), createPlayer("eb2", 0)};
        MatchResultRepository rated = new MatchResultRepository(db.dataSource(), xp -> 1, (r, b, p) -> new int[] {10, -10});
        MatchResult result = teamMatch("01JCTEAMS00000000000000012", one, 1, two, 2);
        assertThat(rated.apply(result).newPlayers()).as("in no team when it was applied").isEqualTo(4);

        team(teams, "Tanks", one);
        long rams = team(teams, "Rams", two);
        assertThat(rated.apply(result).duplicatePlayers()).as("delivered again").isEqualTo(4);
        assertThat(record(rams)).as("not rated by the redelivery").isEqualTo("1200 0 0/0/0");
        assertThat(scalar("SELECT COUNT(*) FROM match_team WHERE match_id > ?", 0)).isZero();
    }

    /**
     * Waits until another of this account's connections is running a statement that starts so,
     * as one waiting on a lock a test holds is: the account sees its own threads without PROCESS.
     */
    private static void awaitStatement(String start) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM information_schema.PROCESSLIST"
                     + " WHERE ID <> CONNECTION_ID() AND INFO LIKE ?")) {
            ps.setString(1, start + "%");
            while (System.nanoTime() < deadline) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(20);
            }
        }
        throw new AssertionError("no connection ran " + start);
    }

    @Test
    @DisplayName("an apply gives each player's name as the database holds it, a redelivery too, not as the result carries it (D-60)")
    void anApplyGivesTheNamesItRead() throws Exception {
        long ada = createPlayer("na1", 0);
        long bob = createPlayer("na2", 0);
        MatchResult result = duel("01JCNAMES00000000000000001", ada, 1, bob, 2);
        MatchResultRepository repo = new MatchResultRepository(db.dataSource(), xp -> 1);
        assertThat(repo.apply(result).names()).containsEntry(ada, "na1").containsEntry(bob, "na2");
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE player SET display_name = 'Renamed' WHERE id = ?")) {
            ps.setLong(1, ada);
            ps.executeUpdate();
        }
        assertThat(repo.apply(result).names()).as("delivered again, after a rename").containsEntry(ada, "Renamed")
                .containsEntry(bob, "na2");
    }

    @Test
    @DisplayName("a result's assists are kept with the match and added to the player's totals (01 §7, V25)")
    void assistsAreKept() throws Exception {
        long ada = createPlayer("as1", 0);
        long bob = createPlayer("as2", 0);
        long now = System.currentTimeMillis();
        MatchResultRepository repo = new MatchResultRepository(db.dataSource(), xp -> 1);
        for (String uid : new String[] {"01JCASSISTS000000000000001", "01JCASSISTS000000000000002"}) {
            repo.apply(new MatchResult(uid, 1, 0, "arena-1", now - 60_000, now, List.of(
                    new PlayerResult(ada, 0, 1, 1, 0, 300, 10, 0, 5, true, 60, 2),
                    new PlayerResult(bob, 0, 2, 0, 1, 100, 10, 0, 5, false, 60)), false));
        }
        assertThat(scalar("SELECT assists FROM match_player WHERE player_id = ? ORDER BY match_id LIMIT 1", ada)).isEqualTo(2);
        assertThat(scalar("SELECT assists FROM player_stat WHERE player_id = ?", ada)).as("two matches").isEqualTo(4);
        assertThat(scalar("SELECT assists FROM player_stat WHERE player_id = ?", bob)).as("none given, none counted").isZero();
    }

    @Test
    @DisplayName("retention deletes a team match's team rows with it")
    void retentionDeletesTeamRows() throws Exception {
        TeamRepository teams = new TeamRepository(db.dataSource(), 30);
        long[] one = {createPlayer("ra1", 0), createPlayer("ra2", 0), createPlayer("ra3", 0)};
        long[] two = {createPlayer("rb1", 0), createPlayer("rb2", 0), createPlayer("rb3", 0)};
        team(teams, "Tanks", one);
        long rams = team(teams, "Rams", two);
        new MatchResultRepository(db.dataSource(), xp -> 1, (r, b, p) -> new int[] {10, -10})
                .apply(teamMatch("01JCTEAMS00000000000000009", one, 1, two, 2));
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE matches SET ended_at = ended_at - INTERVAL 200 DAY")) {
            ps.executeUpdate();
        }
        assertThat(matches.purgeMatchesEndedBefore(System.currentTimeMillis() - MatchResultRepository.MATCH_RETENTION_MILLIS,
                100)).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM match_team WHERE match_id > ?", 0)).isZero();
        assertThat(record(rams)).as("the team's record stays").isEqualTo("1190 1 0/1/0");
    }

    private static MatchResult field(String uid, long[] players, int[] placements) {
        long now = System.currentTimeMillis();
        List<PlayerResult> results = new java.util.ArrayList<>();
        for (int i = 0; i < players.length; i++) {
            results.add(new PlayerResult(players[i], 0, placements[i], 0, 0, 50, 50, 999, 10, placements[i] == 1, 60));
        }
        return new MatchResult(uid, 1, MatchResultRepository.MODE_RFFA, "arena-1", now - 60_000, now, results, true);
    }

    @Test
    @DisplayName("a rated free-for-all moves every player by the whole field, on its own rating, and one player alone moves nothing (D-28)")
    void aRatedFreeForAllMovesItsRatings() throws Exception {
        long[] field = {createPlayer("ff1", 0), createPlayer("ff2", 0), createPlayer("ff3", 0)};
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE player SET rating_rffa = 1300, rated_rffas = 5 WHERE id = ?")) {
            ps.setLong(1, field[2]);
            ps.executeUpdate();
        }
        List<String> given = new java.util.concurrent.CopyOnWriteArrayList<>();
        MatchResultRepository rated = new MatchResultRepository(db.dataSource(), xp -> 1,
                (ratings, before, placements) -> {
                    given.add(java.util.Arrays.toString(ratings) + java.util.Arrays.toString(before)
                            + java.util.Arrays.toString(placements));
                    int[] deltas = new int[ratings.length];
                    for (int i = 0; i < deltas.length; i++) {
                        deltas[i] = 10 - 5 * placements[i];              // first +5, second 0, third −5
                    }
                    return deltas;
                });

        rated.apply(field("01JCRFFA000000000000000001", field, new int[] {2, 1, 3}));
        assertThat(given).containsExactly("[1200, 1200, 1300][0, 0, 5][2, 1, 3]");
        assertThat(scalar("SELECT rating_rffa FROM player WHERE id = ?", field[0])).isEqualTo(1_200);
        assertThat(scalar("SELECT rating_rffa FROM player WHERE id = ?", field[1])).isEqualTo(1_205);
        assertThat(scalar("SELECT rating_rffa FROM player WHERE id = ?", field[2])).isEqualTo(1_295);
        assertThat(scalar("SELECT rated_rffas FROM player WHERE id = ?", field[2])).isEqualTo(6);
        assertThat(scalar("SELECT rated_rffas FROM player WHERE id = ?", field[0])).isEqualTo(1);
        assertThat(scalar("SELECT rating_delta FROM match_player WHERE player_id = ?", field[2])).isEqualTo(-5);
        for (long id : field) {
            assertThat(scalar("SELECT rating_duel FROM player WHERE id = ?", id)).as("the duel's, untouched").isEqualTo(1_200);
            assertThat(scalar("SELECT rating_tvt FROM player WHERE id = ?", id)).as("the team's, untouched").isEqualTo(1_200);
        }

        rated.apply(field("01JCRFFA000000000000000002", new long[] {field[0]}, new int[] {1}));
        assertThat(given).as("one player alone is a walkover: not rated").hasSize(1);
        assertThat(scalar("SELECT rated_rffas FROM player WHERE id = ?", field[0])).isEqualTo(1);

        // One named in the result has gone, and sorts before the rest: the field is the rest,
        // and each delta lands on its own player.
        long gone = createPlayer("ff0", 0);
        long[] after = {createPlayer("ff4", 0), createPlayer("ff5", 0)};
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM player WHERE id = ?")) {
            ps.setLong(1, gone);
            ps.executeUpdate();
        }
        rated.apply(field("01JCRFFA000000000000000003", new long[] {gone, after[0], after[1]}, new int[] {1, 3, 2}));
        assertThat(given).last().isEqualTo("[1200, 1200][0, 0][3, 2]");
        assertThat(scalar("SELECT rating_rffa FROM player WHERE id = ?", after[0])).isEqualTo(1_195);
        assertThat(scalar("SELECT rating_rffa FROM player WHERE id = ?", after[1])).isEqualTo(1_200);
    }

    @Test
    @DisplayName("the account level follows lifetime xp, once per match, and never goes down")
    void accountLevelIsWritten() throws Exception {
        long alice = createPlayer("alice", 0);
        // 250 xp a match against a level every 1 000: level 1, then 2 after the fourth match.
        for (int i = 1; i <= 4; i++) {
            MatchResult r = resultFor(String.format("01JBLEVEL0000000000000000%d", i), List.of(alice), 0);
            matches.apply(r);
            matches.apply(r);                                   // a redelivery must not count
            assertThat(scalar("SELECT level FROM player WHERE id = ?", alice))
                    .as("after match %d, %d xp", i, 250 * i).isEqualTo(1 + 250 * i / 1_000);
        }
        // Until this change nothing wrote the column, and every player was level 1 for ever.
        assertThat(scalar("SELECT level FROM player WHERE id = ?", alice)).isEqualTo(2);

        // A rebalance that would lower it does not: nobody loses a level they earned.
        MatchResultRepository harsher = new MatchResultRepository(db.dataSource(), xp -> 1);
        harsher.apply(resultFor("01JBLEVEL00000000000000005", List.of(alice), 0));
        assertThat(scalar("SELECT level FROM player WHERE id = ?", alice)).isEqualTo(2);
        assertThat(scalar("SELECT xp FROM player WHERE id = ?", alice)).isEqualTo(1_250);
    }

    /** A player's xp and the level stored for it, as a fixture. */
    private static void progressed(long player, long xp, int level) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE player SET xp = ?, level = ? WHERE id = ?")) {
            ps.setLong(1, xp);
            ps.setInt(2, level);
            ps.setLong(3, player);
            ps.executeUpdate();
        }
    }

    /** A player's counted stats, as a fixture: the row their results would have written. */
    private static void counted(long player, long matches, long wins, long kills, long bestScore) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("REPLACE INTO player_stat (player_id, matches, wins, kills, deaths,"
                     + " best_score, playtime_s, assists) VALUES (?, ?, ?, ?, 0, ?, 0, 0)")) {
            ps.setLong(1, player);
            ps.setLong(2, matches);
            ps.setLong(3, wins);
            ps.setLong(4, kills);
            ps.setLong(5, bestScore);
            ps.executeUpdate();
        }
    }

    /** A result whose one player scored and killed so much, and won. */
    private static MatchResult scored(String uid, long player, int kills, int score) {
        long now = System.currentTimeMillis();
        return new MatchResult(uid, 1, 0, "arena-1", now - 300_000, now,
                List.of(new PlayerResult(player, 0, 1, kills, 2, score, 250, 12, 0, true, 300)));
    }

    /** A result this player won, rated, big enough for any kind's hard goal but stays, which is one a result. */
    private static MatchResult aBigDay(String uid, long player, long other, long endedAt) {
        return new MatchResult(uid, 1, MatchResultRepository.MODE_DUEL, "arena-1", endedAt - 600_000, endedAt,
                List.of(new PlayerResult(player, 0, 1, 30, 0, 15_000, 250, 0, 0, true, 3_600, 15),
                        new PlayerResult(other, 0, 2, 0, 1, 0, 50, 0, 0, false, 3_600, 0)), true);
    }

    @Test
    @DisplayName("daily goals: each met by results is paid its coins once; the result meeting the third pays the set's gems; another day is another day's (04 §8, D-66)")
    void dailyGoalsArePaidOnce() throws Exception {
        MatchResultRepository counting = new MatchResultRepository(db.dataSource(), xp -> 1,
                (ratings, before, placements) -> new int[ratings.length], DailyGoals::of);
        long ada = createPlayer("ada", 0);
        long bob = createPlayer("bob", 0);
        // Two days ago, UTC: in the past and inside the 30 days a result is accepted for, whenever this runs (T-43).
        java.time.LocalDate day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(2);
        long noon = day.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        List<DailyGoals.Goal> three = DailyGoals.of(ada, day);
        List<String> met = new java.util.ArrayList<>();
        long told = 0;
        int setGems = 0;
        MatchResult last = null;
        for (int i = 0; i < 6 && met.size() < 3; i++) {                  // stays count one a result: six at most
            last = aBigDay(String.format("01JBDAILY%017d", i), ada, bob, noon + i * 60_000L);
            MatchResultRepository.Paid paid = counting.apply(last).paid().get(0);
            met.addAll(paid.goals());
            told += paid.goalCoins();
        }
        assertThat(met).as("each of the day's three met once").containsExactlyInAnyOrderElementsOf(
                three.stream().map(DailyGoals.Goal::id).toList());
        long coins = three.stream().mapToLong(DailyGoals.Goal::coins).sum();
        assertThat(told).as("told").isEqualTo(coins);
        assertThat(scalar("SELECT SUM(delta) FROM ledger WHERE player_id = ? AND currency = 0 AND reason = "
                + EconomyRepository.REASON_DAILY_GOAL, ada)).as("paid").isEqualTo(coins);
        assertThat(scalar("SELECT SUM(delta) FROM ledger WHERE player_id = ? AND currency = 1 AND reason = "
                + EconomyRepository.REASON_DAILY_GOAL + " AND idem_key = CONCAT('daily:', player_id, ':" + day + ":set')", ada))
                .as("the set's gems, once").isEqualTo(DailyGoals.SET_GEMS);
        assertThat(counting.apply(last).paid()).as("a redelivery pays nobody").isEmpty();
        MatchResultRepository.Paid more = counting.apply(aBigDay("01JBDAILY00000000000000099", ada, bob, noon + 3_600_000L))
                .paid().get(0);
        assertThat(List.of(more.goals(), more.goalCoins())).as("the day's three met: nothing more").containsExactly(List.of(), 0L);

        long tomorrow = day.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() + 1_000;
        counting.apply(aBigDay("01JBDAILY00000000000000100", ada, bob, tomorrow));
        assertThat(scalar("SELECT COUNT(*) FROM daily_goal WHERE player_id = ? AND day = '" + day.plusDays(1) + "'", ada))
                .as("tomorrow's three, counted apart").isEqualTo(3);
        // One such result meets a goal unless it is a stays goal, wins_3 or rated_3: it counts one of each.
        long oneMeets = DailyGoals.of(ada, day.plusDays(1)).stream()
                .filter(g -> !g.id().startsWith("stays_") && !g.id().equals("wins_3") && !g.id().equals("rated_3")).count();
        assertThat(scalar("SELECT COUNT(*) FROM ledger WHERE player_id = ? AND reason = "
                + EconomyRepository.REASON_DAILY_GOAL + " AND idem_key LIKE CONCAT('daily:', player_id, ':" + day.plusDays(1) + ":%')", ada))
                .as("and paid apart: its goals met, and the set if all three were").isEqualTo(oneMeets + (oneMeets == 3 ? 1 : 0));
        assertThat(scalar("SELECT COUNT(*) FROM ledger WHERE player_id = ? AND reason = " + EconomyRepository.REASON_DAILY_GOAL
                + " AND idem_key LIKE CONCAT('daily:', player_id, ':" + day + ":%')", ada)).as("the first day's, still four").isEqualTo(4);
    }

    @Test
    @DisplayName("a goal counts what the result counts: an open stay moves stays and time, not wins or rated matches (D-66)")
    void aStayCountsAsAStay() throws Exception {
        java.time.LocalDate day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(2);      // T-43
        // A goal set holding every kind's easy goal, so each kind's count is seen.
        MatchResultRepository counting = new MatchResultRepository(db.dataSource(), xp -> 1,
                (ratings, before, placements) -> new int[ratings.length], (player, d) -> List.of(
                        DailyGoals.byId("stays_3"), DailyGoals.byId("kills_10"), DailyGoals.byId("wins_1"),
                        DailyGoals.byId("assists_5"), DailyGoals.byId("score_5000"), DailyGoals.byId("playtime_20m"),
                        DailyGoals.byId("rated_1")));
        long cyd = createPlayer("cyd", 0);
        long noon = day.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        counting.apply(new MatchResult("01JBDAILYSTAY0000000000001", 0, 0, "arena-1", noon - 300_000, noon,
                List.of(new PlayerResult(cyd, 0, 0, 2, 1, 700, 50, 0, 10, false, 300, 1)), false));
        java.util.Map<String, Long> progress = new DailyGoalRepository(db.dataSource()).progress(cyd, day);
        assertThat(progress).containsEntry("stays_3", 1L).containsEntry("kills_10", 2L).containsEntry("assists_5", 1L)
                .containsEntry("score_5000", 700L).containsEntry("playtime_20m", 300L)
                .containsEntry("wins_1", 0L).containsEntry("rated_1", 0L);
    }

    @Test
    @DisplayName("a goal's progress is added where it is stored and read back under lock: none read plainly is written back (D-46)")
    void goalProgressIsAddedNotReadAndWritten() throws Exception {
        java.time.LocalDate day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(2);      // T-43
        RecordingDataSource recorded = new RecordingDataSource(db.dataSource());
        MatchResultRepository counting = new MatchResultRepository(recorded.dataSource, xp -> 1,
                (ratings, before, placements) -> new int[ratings.length], (player, d) -> List.of(
                        DailyGoals.byId("stays_3"), DailyGoals.byId("kills_10"), DailyGoals.byId("wins_1")));
        long eve = createPlayer("eve", 0);
        long noon = day.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        for (int i = 1; i <= 3; i++) {
            counting.apply(new MatchResult("01JBDAILYADD00000000000000".substring(0, 25) + i, 0, 0, "arena-1",
                    noon - 300_000 + i, noon + i, List.of(new PlayerResult(eve, 0, 0, 2, 1, 70, 5, 0, 1, false, 300, 0)), false));
        }
        assertThat(new DailyGoalRepository(db.dataSource()).progress(eve, day)).containsEntry("stays_3", 3L)
                .containsEntry("kills_10", 6L);
        List<String> goals = recorded.executed.stream().filter(q -> q.contains("daily_goal")).toList();
        assertThat(goals).anyMatch(q -> q.contains("progress = progress + VALUES(progress)"));
        assertThat(goals).filteredOn(q -> q.startsWith("SELECT")).allMatch(q -> q.contains("FOR UPDATE"));
        assertThat(goals).noneMatch(q -> q.contains("progress = VALUES(progress)"));
    }

    @Test
    @DisplayName("the rated goal counts a rated match played against someone: a walkover is a win, not a rated match (D-51)")
    void aWalkoverIsNotARatedMatch() throws Exception {
        java.time.LocalDate day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(2);      // T-43
        MatchResultRepository counting = new MatchResultRepository(db.dataSource(), xp -> 1,
                (ratings, before, placements) -> new int[ratings.length], (player, d) -> List.of(
                        DailyGoals.byId("wins_1"), DailyGoals.byId("rated_1"), DailyGoals.byId("stays_3")));
        long ada = createPlayer("ada", 0);
        long bob = createPlayer("bob", 0);
        long noon = day.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        counting.apply(new MatchResult("01JBDAILYWALKOVER000000001", 1, 1, "arena-1", noon - 60_000, noon,
                List.of(new PlayerResult(ada, 1, 1, 0, 0, 0, 0, 0, 0, true, 60, 0)), true));
        DailyGoalRepository goals = new DailyGoalRepository(db.dataSource());
        assertThat(goals.progress(ada, day)).as("came alone: a win, no rated match").containsEntry("wins_1", 1L)
                .containsEntry("rated_1", 0L);
        counting.apply(new MatchResult("01JBDAILYRATED000000000001", 1, 1, "arena-1", noon - 60_000, noon + 1,
                List.of(new PlayerResult(ada, 1, 1, 1, 0, 100, 0, 0, 0, true, 60, 0),
                        new PlayerResult(bob, 2, 2, 0, 1, 10, 0, 0, 0, false, 60, 0)), true));
        assertThat(goals.progress(ada, day)).as("played against bob").containsEntry("rated_1", 1L);
        assertThat(goals.progress(bob, day)).as("and bob, who lost it").containsEntry("rated_1", 1L);
    }

    @Test
    @DisplayName("a result earns pass points for the season being played, 10 and 50 a daily goal it meets; the tiers it crosses paid and told; a redelivery earns none (04 §8, D-69)")
    void passPointsFromResults() throws Exception {
        // Goals the big day meets two of, kills and score, and not the third, stays; its loser none.
        MatchResultRepository counting = new MatchResultRepository(db.dataSource(), xp -> 1,
                (ratings, before, placements) -> new int[ratings.length], (player, d) -> List.of(
                        DailyGoals.byId("kills_10"), DailyGoals.byId("score_5000"), DailyGoals.byId("stays_3")));
        long ada = createPlayer("ada", 0);
        long bob = createPlayer("bob", 0);
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO season_pass (player_id, season_id, points) VALUES (?, 1, 240)")) {
            ps.setLong(1, ada);
            ps.executeUpdate();
        }
        java.time.LocalDate day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(2);      // T-43
        MatchResult r = aBigDay("01JBPASS000000000000000001", ada, bob, day.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli());
        List<MatchResultRepository.Paid> paid = counting.apply(r).paid();
        MatchResultRepository.Paid a = paid.get(0).playerId() == ada ? paid.get(0) : paid.get(1);
        MatchResultRepository.Paid b = paid.get(0).playerId() == ada ? paid.get(1) : paid.get(0);
        assertThat(List.of(a.goals().size(), b.goals().size())).containsExactly(2, 0);
        assertThat(a.pass().points()).as("10, and 50 a goal met").isEqualTo(110);
        assertThat(List.of(a.pass().tier(), a.pass().coins(), a.pass().gems())).as("past 250: the first tier")
                .containsExactly(1, 150L, 0);
        assertThat(b.pass().points()).isEqualTo(10);
        assertThat(List.of(b.pass().tier(), b.pass().coins())).containsExactly(0, 0L);
        assertThat(scalar("SELECT points FROM season_pass WHERE player_id = ? AND season_id = 1", ada))
                .isEqualTo(240 + a.pass().points());
        assertThat(scalar("SELECT SUM(delta) FROM ledger WHERE player_id = ? AND reason = " + EconomyRepository.REASON_PASS_TIER, ada))
                .as("paid, through the ledger").isEqualTo(150);
        assertThat(counting.apply(r).paid()).as("a redelivery pays nobody").isEmpty();
        assertThat(scalar("SELECT points FROM season_pass WHERE player_id = ? AND season_id = 1", ada)).as("and earns nothing")
                .isEqualTo(240 + a.pass().points());

        try (Connection c = db.dataSource().getConnection();            // the next season, as its close makes it
             PreparedStatement placed = c.prepareStatement("UPDATE season SET placed_at = NOW(3) WHERE id = 1");
             PreparedStatement next = c.prepareStatement(
                     "INSERT INTO season (id, starts_at, ends_at) VALUES (2, NOW(3), NOW(3) + INTERVAL 61 DAY)")) {
            placed.executeUpdate();
            next.executeUpdate();
        }
        counting.apply(aBigDay("01JBPASS000000000000000002", ada, bob, day.atTime(13, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()));
        assertThat(scalar("SELECT points FROM season_pass WHERE player_id = ? AND season_id = 2", ada))
                .as("the season being played's, whenever its result ended").isEqualTo(10);
        assertThat(scalar("SELECT points FROM season_pass WHERE player_id = ? AND season_id = 1", ada)).isEqualTo(350);
    }

    @Test
    @DisplayName("the achievements: seventeen, 430 gems, each id once and each stat's thresholds rising; reached on a crossing only (04 §8, D-64)")
    void theAchievements() {
        assertThat(Achievements.ALL).hasSize(17);
        assertThat(Achievements.ALL.stream().mapToInt(Achievements.Achievement::gems).sum()).isEqualTo(430);
        assertThat(Achievements.ALL.stream().map(Achievements.Achievement::id).distinct()).hasSize(17);
        for (int i = 1; i < Achievements.ALL.size(); i++) {
            Achievements.Achievement before = Achievements.ALL.get(i - 1), a = Achievements.ALL.get(i);
            if (before.stat() == a.stat()) {
                assertThat(a.threshold()).as(a.id()).isGreaterThan(before.threshold());
                assertThat(a.gems()).as(a.id()).isGreaterThan(before.gems());
            }
        }
        Achievements.Counts none = Achievements.Counts.NONE;
        assertThat(Achievements.crossed(new Achievements.Counts(99, 0, 0, 0, 0, 0), new Achievements.Counts(100, 0, 0, 0, 0, 0)))
                .extracting(Achievements.Achievement::id).as("at the threshold: reached").containsExactly("kills_100");
        assertThat(Achievements.crossed(new Achievements.Counts(95, 0, 0, 0, 0, 0), new Achievements.Counts(1_000, 0, 0, 0, 0, 0)))
                .extracting(Achievements.Achievement::id).containsExactly("kills_100", "kills_1000");
        assertThat(Achievements.crossed(new Achievements.Counts(100, 0, 0, 0, 0, 0), new Achievements.Counts(150, 0, 0, 0, 0, 0)))
                .as("reached before: not again").isEmpty();
        assertThat(Achievements.crossed(none, new Achievements.Counts(0, 0, 0, 0, 0, 36_000)))
                .extracting(Achievements.Achievement::id).as("ten hours, in seconds").containsExactly("playtime_10h");
        assertThat(Achievements.crossed(none, new Achievements.Counts(0, 10, 50, 100, 10_000, 0)))
                .extracting(Achievements.Achievement::id).as("each stat its own, in the table's order")
                .containsExactly("wins_10", "matches_50", "assists_100", "best_score_10000");
    }

    @Test
    @DisplayName("a result that carries a stat across a threshold pays the achievement's gems through the ledger, in its transaction, once, and names it (04 §8, D-64)")
    void anAchievementIsPaidOnce() throws Exception {
        long ada = createPlayer("ada", 0);
        counted(ada, 49, 0, 0, 0);                               // a stay more: the fiftieth
        MatchResult r = resultFor("01JBACHIEVE000000000000001", List.of(ada), 0);
        MatchResultRepository.ApplyResult applied = matches.apply(r);
        assertThat(applied.paid()).extracting(MatchResultRepository.Paid::achievements).containsExactly(List.of("matches_50"));
        assertThat(applied.paid()).extracting(MatchResultRepository.Paid::gems).containsExactly(10);
        assertThat(economy.wallet(ada).gems()).isEqualTo(10);
        assertThat(scalar("SELECT COUNT(*) FROM ledger WHERE player_id = ? AND currency = 1 AND delta = 10"
                + " AND reason = " + EconomyRepository.REASON_ACHIEVEMENT
                + " AND ref = 'achievement:matches_50' AND idem_key = CONCAT('achievement:', player_id, ':matches_50')", ada))
                .isEqualTo(1);
        assertThat(matches.apply(r).paid()).as("a redelivery pays nobody").isEmpty();
        MatchResultRepository.ApplyResult next = matches.apply(resultFor("01JBACHIEVE000000000000002", List.of(ada), 0));
        assertThat(next.paid()).extracting(MatchResultRepository.Paid::achievements).as("the fifty-first: nothing new")
                .containsExactly(List.of());
        assertThat(economy.wallet(ada).gems()).isEqualTo(10);
    }

    @Test
    @DisplayName("an achievement's key pays it once, whatever happens to the stats: crossed again, neither paid nor told")
    void anAchievementOnceAKey() throws Exception {
        long fay = createPlayer("fay", 0);
        counted(fay, 49, 0, 0, 0);
        matches.apply(resultFor("01JBACHIEVE000000000000008", List.of(fay), 0));
        try (Connection c = db.dataSource().getConnection();       // an operator's repair, say: back under 50
             PreparedStatement ps = c.prepareStatement("UPDATE player_stat SET matches = 49 WHERE player_id = ?")) {
            ps.setLong(1, fay);
            ps.executeUpdate();
        }
        MatchResultRepository.ApplyResult again = matches.apply(resultFor("01JBACHIEVE000000000000009", List.of(fay), 0));
        assertThat(again.paid()).extracting(MatchResultRepository.Paid::achievements, MatchResultRepository.Paid::gems)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(List.of(), 0));
        assertThat(economy.wallet(fay).gems()).isEqualTo(10);
    }

    @Test
    @DisplayName("achievements: two crossed at once pay both; a first result counts from nothing; a best score is a maximum; with a milestone, both paid (D-64)")
    void achievementsTogetherFromNothingAndAsAMaximum() throws Exception {
        long bob = createPlayer("bob", 0);                       // no stats yet: this is his first result
        long cat = createPlayer("cat", 0);
        long dee = createPlayer("dee", 0);
        long eve = createPlayer("eve", 0);
        counted(cat, 10, 9, 95, 0);                              // a win more: 10; five kills more: 100
        counted(dee, 10, 0, 0, 9_000);
        counted(eve, 49, 0, 0, 0);
        progressed(eve, 3_900, 4);                               // and level 5's milestone
        assertThat(matches.apply(scored("01JBACHIEVE000000000000003", bob, 0, 12_000)).paid())
                .extracting(MatchResultRepository.Paid::achievements).containsExactly(List.of("best_score_10000"));
        assertThat(matches.apply(scored("01JBACHIEVE000000000000004", cat, 5, 100)).paid())
                .extracting(MatchResultRepository.Paid::achievements, MatchResultRepository.Paid::gems)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(List.of("kills_100", "wins_10"), 20));
        assertThat(matches.apply(scored("01JBACHIEVE000000000000005", dee, 0, 1_000)).paid())
                .extracting(MatchResultRepository.Paid::achievements).as("9 000 still the best").containsExactly(List.of());
        assertThat(matches.apply(scored("01JBACHIEVE000000000000006", dee, 0, 10_000)).paid())
                .extracting(MatchResultRepository.Paid::achievements).containsExactly(List.of("best_score_10000"));
        assertThat(matches.apply(scored("01JBACHIEVE000000000000007", eve, 0, 100)).paid())
                .extracting(MatchResultRepository.Paid::gems).as("20 for level 5, 10 for the fiftieth").containsExactly(30);
        assertThat(List.of(economy.wallet(bob).gems(), economy.wallet(cat).gems(), economy.wallet(dee).gems(),
                economy.wallet(eve).gems())).containsExactly(10L, 20L, 10L, 30L);
        // What the result was judged by is what it wrote: the counts after are the row's.
        assertThat(scalar("SELECT kills * 1000000 + wins * 1000 + matches FROM player_stat WHERE player_id = ?", cat))
                .isEqualTo(100L * 1_000_000 + 10 * 1_000 + 11);
    }

    @Test
    @DisplayName("level 5 and every tenth level pay 20 gems each, none between (04 §8, plan item 68)")
    void theMilestones() {
        for (int level : new int[] {5, 10, 20, 50, 90, 100}) {
            assertThat(MatchResultRepository.milestoneGems(level)).as("level %d", level).isEqualTo(20);
        }
        for (int level : new int[] {1, 4, 6, 9, 11, 15, 25, 95, 99}) {
            assertThat(MatchResultRepository.milestoneGems(level)).as("level %d", level).isZero();
        }
    }

    @Test
    @DisplayName("a result that lifts a player to a milestone pays its gems through the ledger, in its transaction, once (04 §8, D-61)")
    void aMilestonePaysGemsOnce() throws Exception {
        long ada = createPlayer("ada", 0);
        progressed(ada, 3_900, 4);                              // 250 more is 4 150: level 5
        MatchResult r = resultFor("01JBMILESTONE0000000000001", List.of(ada), 0);
        MatchResultRepository.ApplyResult applied = matches.apply(r);
        assertThat(applied.paid()).extracting(MatchResultRepository.Paid::gems).as("told").containsExactly(20);
        assertThat(economy.wallet(ada).gems()).isEqualTo(20);
        assertThat(scalar("SELECT COUNT(*) FROM ledger WHERE player_id = ? AND currency = 1 AND delta = 20"
                + " AND balance_after = 20 AND reason = " + EconomyRepository.REASON_MILESTONE
                + " AND ref = 'level:5' AND idem_key = CONCAT('milestone:', player_id, ':5')", ada)).isEqualTo(1);

        assertThat(matches.apply(r).paid()).as("a redelivery pays nobody").isEmpty();
        assertThat(matches.apply(resultFor("01JBMILESTONE0000000000002", List.of(ada), 0)).paid())
                .extracting(MatchResultRepository.Paid::gems).as("level 5 again, no milestone crossed").containsExactly(0);
        assertThat(economy.wallet(ada).gems()).isEqualTo(20);
    }

    @Test
    @DisplayName("a result past two milestones pays both; from a stored level above the curve's, or at the milestone already, none (D-61)")
    void milestonesFromTheStoredLevel() throws Exception {
        long bob = createPlayer("bob", 0);
        long cat = createPlayer("cat", 0);
        long eve = createPlayer("eve", 0);
        progressed(bob, 8_900, 1);                              // 9 150: level 10, past 5 and 10
        progressed(cat, 3_900, 7);                              // 4 150 is worth 5, but 7 is held (D-12)
        progressed(eve, 4_150, 5);                              // at 5 before milestones were paid
        MatchResultRepository.ApplyResult applied =
                matches.apply(resultFor("01JBMILESTONE0000000000003", List.of(bob, cat, eve), 0));
        assertThat(applied.paid()).extracting(MatchResultRepository.Paid::gems).containsExactly(40, 0, 0);
        assertThat(List.of(economy.wallet(bob).gems(), economy.wallet(cat).gems(), economy.wallet(eve).gems()))
                .containsExactly(40L, 0L, 0L);
        assertThat(scalar("SELECT COUNT(*) FROM ledger WHERE player_id = ? AND reason = "
                + EconomyRepository.REASON_MILESTONE, bob)).as("a row a milestone").isEqualTo(2);
    }

    @Test
    @DisplayName("a milestone's key pays it once, whatever happens to the level column")
    void aMilestoneOnceAKey() throws Exception {
        long dee = createPlayer("dee", 0);
        progressed(dee, 3_900, 4);
        matches.apply(resultFor("01JBMILESTONE0000000000004", List.of(dee), 0));
        progressed(dee, 3_900, 4);                              // the column set back, by hand
        MatchResultRepository.ApplyResult again = matches.apply(resultFor("01JBMILESTONE0000000000005", List.of(dee), 0));
        assertThat(again.paid()).extracting(MatchResultRepository.Paid::gems).as("not paid, not told").containsExactly(0);
        assertThat(economy.wallet(dee).gems()).isEqualTo(20);
    }

    @Test
    @DisplayName("a result that arrives more than 30 days late is refused, and nothing is written")
    void tooLateResultIsRefused() throws Exception {
        long alice = createPlayer("alice", 0);
        long ended = System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(31);
        MatchResult late = new MatchResult("01JBLATE000000000000000001", 1, 0, "arena-1",
                ended - 300_000, ended,
                List.of(new PlayerResult(alice, 0, 1, 5, 2, 1000, 250, 0, 50, true, 300)));

        // Past the window, a redelivery can no longer be told from a new result: its
        // match_player rows may have been deleted by retention (D-14).
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> matches.apply(late))
                .isInstanceOf(MatchResultRepository.InvalidResult.class)
                .hasMessageContaining("30 days");
        assertThat(scalar("SELECT xp FROM player WHERE id = ?", alice)).isZero();
        assertThat(economy.coins(alice)).isZero();
    }

    @Test
    @DisplayName("retention deletes old matches and their rows, and nothing a player owns")
    void retentionDeletesOnlyHistory() throws Exception {
        long alice = createPlayer("alice", 0);
        matches.apply(resultFor("01JBOLD0000000000000000001", List.of(alice), 40));
        matches.apply(resultFor("01JBNEW0000000000000000001", List.of(alice), 60));
        // Age the first one past retention, as if it had been played 200 days ago.
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE matches SET ended_at = ended_at - INTERVAL 200 DAY WHERE match_uid = ?")) {
            ps.setString(1, "01JBOLD0000000000000000001");
            ps.executeUpdate();
        }
        long cutoff = System.currentTimeMillis() - MatchResultRepository.MATCH_RETENTION_MILLIS;

        assertThat(matches.purgeMatchesEndedBefore(cutoff, 1)).as("one batch of one, then none")
                .isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM matches WHERE id > ?", 0)).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM match_player WHERE player_id = ?", alice)).isEqualTo(1);
        // The durable record is untouched: totals, stats and every ledger row.
        assertThat(scalar("SELECT xp FROM player WHERE id = ?", alice)).isEqualTo(500);
        assertThat(scalar("SELECT matches FROM player_stat WHERE player_id = ?", alice)).isEqualTo(2);
        assertThat(economy.ledgerSum(alice)).isEqualTo(economy.coins(alice)).isEqualTo(100);
    }

    @Test
    @DisplayName("retention refuses to delete matches a result could still arrive for")
    void retentionRespectsTheAcceptWindow() {
        long tooRecent = System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(10);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> matches.purgeMatchesEndedBefore(tooRecent, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("applying the same match result twice credits the player once")
    void matchResultIsIdempotent() throws Exception {
        long alice = createPlayer("alice", 100);
        long bob = createPlayer("bob", 100);
        MatchResult result = resultFor("01JBMATCH00000000000000001", List.of(alice, bob), 50);

        assertThat(matches.apply(result).anythingNew()).as("first delivery applies").isTrue();
        assertThat(matches.apply(result).anythingNew()).as("redelivery is a no-op").isFalse();
        assertThat(matches.apply(result).anythingNew()).isFalse();

        assertThat(economy.coins(alice)).isEqualTo(150);
        assertThat(economy.coins(bob)).isEqualTo(150);
        assertThat(scalar("SELECT xp FROM player WHERE id = ?", alice)).isEqualTo(250);
        assertThat(scalar("SELECT matches FROM player_stat WHERE player_id = ?", alice)).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM match_player WHERE player_id = ?", alice)).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM matches WHERE match_uid = ?", alice))
                .as("no second match row").isNotEqualTo(2);
    }

    private static MatchResult endingAt(String uid, long endedAt, long... playerIds) {
        List<PlayerResult> players = java.util.Arrays.stream(playerIds)
                .mapToObj(id -> new PlayerResult(id, 0, 1, 1, 1, 100, 10, 0, 5, false, 60))
                .toList();
        return new MatchResult(uid, 1, 0, "arena-1", endedAt - 60_000, endedAt, players);
    }

    private static List<String> days(long player) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT day FROM player_day WHERE player_id = ? ORDER BY day")) {
            ps.setLong(1, player);
            List<String> out = new java.util.ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        }
    }

    private static String firstDay(long player) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT first_played_on FROM player WHERE id = ?")) {
            ps.setLong(1, player);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    @Test
    @DisplayName("a result records each new player's day, once, in UTC, and the earliest day as their first (05 §11, D-47)")
    void aResultRecordsItsPlayersDays() throws Exception {
        long alice = createPlayer("alice", 0);
        long bob = createPlayer("bob", 0);
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        long midnight = today.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        assertThat(firstDay(alice)).as("nobody has played yet").isNull();

        matches.apply(endingAt("01JBDAYS000000000000000001", midnight + 500, alice, bob));
        matches.apply(endingAt("01JBDAYS000000000000000001", midnight + 500, alice, bob));   // a redelivery
        assertThat(days(alice)).containsExactly(today.toString());
        assertThat(days(bob)).containsExactly(today.toString());
        assertThat(firstDay(alice)).isEqualTo(today.toString());

        // A result from just before midnight arrives late: its day, and alice's first moves back to it.
        matches.apply(endingAt("01JBDAYS000000000000000002", midnight - 500, alice));
        assertThat(days(alice)).containsExactly(today.minusDays(1).toString(), today.toString());
        assertThat(firstDay(alice)).isEqualTo(today.minusDays(1).toString());
        assertThat(firstDay(bob)).as("bob's own first day").isEqualTo(today.toString());

        // Another match the same day adds no day, and a later day does not move the first.
        matches.apply(endingAt("01JBDAYS000000000000000003", midnight + 60_000, alice));
        assertThat(days(alice)).hasSize(2);
        assertThat(firstDay(alice)).isEqualTo(today.minusDays(1).toString());
    }

    @Test
    @DisplayName("concurrent deliveries of one result still apply it exactly once")
    void concurrentDeliveriesApplyOnce() throws Exception {
        long alice = createPlayer("alice", 0);
        MatchResult result = resultFor("01JBMATCH00000000000000002", List.of(alice), 100);

        int threads = 8;
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    go.await();
                    if (matches.apply(result).anythingNew()) {
                        applied.incrementAndGet();
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        go.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(failures.get()).as("no thread failed").isZero();
        assertThat(applied.get()).as("exactly one delivery applied").isEqualTo(1);
        assertThat(economy.coins(alice)).isEqualTo(100);
        assertThat(scalar("SELECT matches FROM player_stat WHERE player_id = ?", alice)).isEqualTo(1);
    }

    // ---- economy ----------------------------------------------------------------------

    @Test
    @DisplayName("many different matches sharing the same players all apply, with no deadlock")
    void overlappingMatchesDoNotDeadlock() throws Exception {
        long ada = createPlayer("ada", 0);
        long bob = createPlayer("bob", 0);
        // As many threads as the pool has connections, and no more. With locks taken up
        // front these transactions correctly queue behind one another on the player rows;
        // more threads than connections then also queue for a connection, behind a 3-second
        // acquire timeout, and the test would measure pool sizing instead of deadlock.
        int threads = 8;
        int coinsEach = 7;

        // Every result names both players, and every one is a DIFFERENT match, so none of
        // them is a duplicate of another: each must apply. This is the shape that exposed a
        // shared-then-exclusive lock upgrade on the same player row — the foreign-key check
        // on the insert takes a shared lock, the balance update then wants an exclusive one,
        // and two transactions each holding the shared lock wait on each other for ever.
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<Throwable> errors = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        for (int i = 0; i < threads; i++) {
            final String uid = String.format("01JDEADLOCK%015d", i);
            new Thread(() -> {
                try {
                    go.await();
                    matches.apply(resultFor(uid, List.of(ada, bob), coinsEach));
                } catch (Throwable t) {
                    failures.incrementAndGet();
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        go.countDown();
        assertThat(done.await(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(failures.get())
                .as("results that failed to apply: %s", errors.stream()
                        .map(Throwable::toString).limit(3).toList())
                .isZero();
        assertThat(scalar("SELECT coins FROM player WHERE id = ?", ada))
                .isEqualTo((long) threads * coinsEach);
        assertThat(scalar("SELECT coins FROM player WHERE id = ?", bob))
                .isEqualTo((long) threads * coinsEach);
    }

    @Test
    @DisplayName("an item held is raised a level for its price, once a key, up to the fifth; not one not held, nor past the coins (04 §8, plan item 67)")
    void itemLevels() throws Exception {
        assertThat(List.of(EconomyRepository.levelCost(1), EconomyRepository.levelCost(2), EconomyRepository.levelCost(3),
                EconomyRepository.levelCost(4))).containsExactly(500L, 1_000L, 2_000L, 4_000L);
        long ada = createPlayer("lv-ada", 10_000);
        assertThat(economy.raiseLevel(ada, "barrel_steel", "k0").outcome()).isEqualTo(EconomyRepository.Raised.NOT_HELD);
        assertThat(economy.purchase(ada, "barrel_steel", 100, "buy-1")).isEqualTo(Outcome.APPLIED);
        EconomyRepository.Raise raised = economy.raiseLevel(ada, "barrel_steel", "k1");
        assertThat(List.of(raised.outcome(), raised.level(), raised.coins()))
                .containsExactly(EconomyRepository.Raised.RAISED, 2, 9_400L);
        EconomyRepository.Raise again = economy.raiseLevel(ada, "barrel_steel", "k1");
        assertThat(List.of(again.outcome(), again.level(), again.coins())).as("a key once")
                .containsExactly(EconomyRepository.Raised.ALREADY, 2, 9_400L);
        assertThat(economy.raiseLevel(ada, "barrel_steel", "k2").coins()).isEqualTo(8_400L);
        assertThat(economy.raiseLevel(ada, "barrel_steel", "k3").coins()).isEqualTo(6_400L);
        EconomyRepository.Raise top = economy.raiseLevel(ada, "barrel_steel", "k4");
        assertThat(List.of(top.level(), top.coins())).containsExactly(5, 2_400L);
        EconomyRepository.Raise past = economy.raiseLevel(ada, "barrel_steel", "k5");
        assertThat(List.of(past.outcome(), past.level(), past.coins()))
                .containsExactly(EconomyRepository.Raised.MAX_LEVEL, 5, 2_400L);
        assertThat(scalar("SELECT item_level FROM inventory_item WHERE player_id = ? AND item_id = 'barrel_steel'", ada)).isEqualTo(5);
        assertThat(scalar("SELECT COUNT(*) FROM ledger WHERE player_id = ? AND reason = " + EconomyRepository.REASON_ITEM_LEVEL,
                ada)).isEqualTo(4);

        long bob = createPlayer("lv-bob", 499);
        assertThat(economy.purchase(bob, "barrel_steel", 0, "buy-b")).isEqualTo(Outcome.APPLIED);
        EconomyRepository.Raise poor = economy.raiseLevel(bob, "barrel_steel", "kb");
        assertThat(List.of(poor.outcome(), poor.level(), poor.coins()))
                .containsExactly(EconomyRepository.Raised.INSUFFICIENT_FUNDS, 1, 499L);
        assertThat(scalar("SELECT item_level FROM inventory_item WHERE player_id = ? AND item_id = 'barrel_steel'", bob)).isEqualTo(1);
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE inventory_item SET qty = 0 WHERE player_id = ?")) {
            ps.setLong(1, bob);
            ps.executeUpdate();
        }
        assertThat(economy.raiseLevel(bob, "barrel_steel", "kc").outcome()).as("its row left, none held").isEqualTo(EconomyRepository.Raised.NOT_HELD);
    }

    @Test
    @DisplayName("a purchase in gems takes gems through the ledger as coins are taken, once a key; none past what is held (04 §8, plan item 68)")
    void aPurchaseInGems() throws Exception {
        long ada = createPlayer("gem-ada", 100);
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE player SET gems = 30 WHERE id = ?")) {
            ps.setLong(1, ada);
            ps.executeUpdate();
        }
        assertThat(economy.purchase(ada, "boost_xp_hour", 20, "gem-1", EconomyRepository.CURRENCY_GEMS)).isEqualTo(Outcome.APPLIED);
        assertThat(economy.purchase(ada, "boost_xp_hour", 20, "gem-1", EconomyRepository.CURRENCY_GEMS))
                .isEqualTo(Outcome.ALREADY_APPLIED);
        assertThat(List.of(economy.wallet(ada).coins(), economy.wallet(ada).gems())).as("gems, not coins").containsExactly(100L, 10L);
        assertThat(scalar("SELECT COUNT(*) FROM ledger WHERE player_id = ? AND currency = 1 AND delta = -20 AND balance_after = 10",
                ada)).isEqualTo(1);
        assertThat(economy.purchase(ada, "boost_xp_hour", 20, "gem-2", EconomyRepository.CURRENCY_GEMS))
                .isEqualTo(Outcome.INSUFFICIENT_FUNDS);
        assertThat(scalar("SELECT qty FROM inventory_item WHERE player_id = ? AND item_id = 'boost_xp_hour'", ada)).isEqualTo(1);
    }

    @Test
    @DisplayName("a repeated purchase with the same key charges once")
    void purchaseIsIdempotent() throws Exception {
        long alice = createPlayer("alice", 500);

        assertThat(economy.purchase(alice, "eq_barrel_steel", 200, "txn-1")).isEqualTo(Outcome.APPLIED);
        assertThat(economy.purchase(alice, "eq_barrel_steel", 200, "txn-1"))
                .isEqualTo(Outcome.ALREADY_APPLIED);

        assertThat(economy.coins(alice)).isEqualTo(300);
        assertThat(scalar("SELECT qty FROM inventory_item WHERE player_id = ? AND item_id = 'eq_barrel_steel'",
                alice)).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM ledger WHERE player_id = ?", alice)).isEqualTo(1);
    }

    @Test
    @DisplayName("a purchase key says what it bought; the wallet and the inventory say what is held")
    void purchasesCanBeReadBack() throws Exception {
        long ada = createPlayer("ada", 500);
        long bob = createPlayer("bob", 500);
        economy.purchase(ada, "eq_core", 120, "key-1");
        economy.purchase(ada, "eq_core", 120, "key-2");
        economy.purchase(ada, "boost_xp", 30, "key-3");

        assertThat(economy.findPurchase(ada, "key-1"))
                .isEqualTo(new EconomyRepository.Purchase("eq_core", 120));
        assertThat(economy.findPurchase(ada, "key-9")).isNull();
        assertThat(economy.findPurchase(bob, "key-1")).as("another player's key is not theirs").isNull();

        assertThat(economy.wallet(ada)).isEqualTo(new EconomyRepository.Wallet(230, 0));
        assertThat(economy.wallet(999_999)).isNull();
        assertThat(economy.inventory(ada)).containsExactly(
                new EconomyRepository.Holding("boost_xp", 1, 1),
                new EconomyRepository.Holding("eq_core", 2, 1));
        assertThat(economy.inventory(bob)).isEmpty();
    }

    @Test
    @DisplayName("a retried purchase is told it succeeded, even when it spent the balance below the price")
    void retriedPurchaseIsAlreadyApplied() throws Exception {
        long buyer = createPlayer("buyer", 150);
        assertThat(economy.purchase(buyer, "hat", 100, "buy-hat-once"))
                .isEqualTo(EconomyRepository.Outcome.APPLIED);
        // The client's connection dropped before the answer, so it asks again. 50 is less
        // than 100, and the funds check used to come first: the purchase that had succeeded
        // was reported as INSUFFICIENT_FUNDS, and a client would offer to sell coins for it.
        assertThat(economy.purchase(buyer, "hat", 100, "buy-hat-once"))
                .isEqualTo(EconomyRepository.Outcome.ALREADY_APPLIED);
        assertThat(economy.coins(buyer)).isEqualTo(50);
        assertThat(scalar("SELECT qty FROM inventory_item WHERE player_id = ?", buyer)).isEqualTo(1);
    }

    @Test
    @DisplayName("two players who happen to send the same purchase key both get what they paid for")
    void purchaseKeysArePerPlayer() throws Exception {
        long ada = createPlayer("ada", 500);
        long bob = createPlayer("bob", 500);
        // A client's key is unique to that client, not to the world; the ledger's unique
        // index is global, so the key has to be made per player before it reaches it.
        assertThat(economy.purchase(ada, "hat", 100, "txn-1")).isEqualTo(Outcome.APPLIED);
        assertThat(economy.purchase(bob, "hat", 100, "txn-1")).isEqualTo(Outcome.APPLIED);
        assertThat(economy.coins(bob)).isEqualTo(400);
        assertThat(scalar("SELECT qty FROM inventory_item WHERE player_id = ?", bob)).isEqualTo(1);
    }

    @Test
    @DisplayName("a purchase beyond the balance is refused and writes nothing")
    void insufficientFundsChangesNothing() throws Exception {
        long alice = createPlayer("alice", 50);

        assertThat(economy.purchase(alice, "eq_barrel_steel", 200, "txn-2"))
                .isEqualTo(Outcome.INSUFFICIENT_FUNDS);

        assertThat(economy.coins(alice)).isEqualTo(50);
        assertThat(scalar("SELECT COUNT(*) FROM ledger WHERE player_id = ?", alice)).isZero();
        assertThat(scalar("SELECT COUNT(*) FROM inventory_item WHERE player_id = ?", alice)).isZero();
    }

    @Test
    @DisplayName("concurrent purchases cannot overdraw the balance")
    void concurrentPurchasesCannotDoubleSpend() throws Exception {
        long alice = createPlayer("alice", 1000);   // affords exactly 5 at 200

        int threads = 12;
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int n = i;
            new Thread(() -> {
                try {
                    go.await();
                    Outcome o = economy.purchase(alice, "boost_xp", 200, "buy-" + n);
                    if (o == Outcome.APPLIED) {
                        applied.incrementAndGet();
                    } else if (o == Outcome.INSUFFICIENT_FUNDS) {
                        refused.incrementAndGet();
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        go.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(failures.get()).as("no thread failed").isZero();
        assertThat(applied.get()).as("exactly five purchases fit in the balance").isEqualTo(5);
        assertThat(refused.get()).isEqualTo(threads - 5);
        assertThat(economy.coins(alice)).isZero();
        assertThat(economy.coins(alice)).as("balance never went negative").isNotNegative();
    }

    @Test
    @DisplayName("the ledger re-derives the balance exactly")
    void ledgerReconciles() throws Exception {
        long alice = createPlayer("alice", 0);

        assertThat(economy.credit(alice, 500, EconomyRepository.REASON_MATCH_REWARD,
                "m1", "reward-1")).isEqualTo(Outcome.APPLIED);
        assertThat(economy.purchase(alice, "eq_core", 120, "buy-1")).isEqualTo(Outcome.APPLIED);
        assertThat(economy.credit(alice, 75, EconomyRepository.REASON_MATCH_REWARD,
                "m2", "reward-2")).isEqualTo(Outcome.APPLIED);
        // And the way coins really arrive: a match result. This test used to pay only through
        // credit(), so a match path that paid without a ledger row passed it (D-13).
        matches.apply(resultFor("01JBLEDGER0000000000000001", List.of(alice), 50));

        // A divergence here means a code path moved a balance without a ledger row,
        // which is the bug that is impossible to find after the fact (06 §3).
        assertThat(economy.ledgerSum(alice)).isEqualTo(economy.coins(alice)).isEqualTo(505);
    }

    @Test
    @DisplayName("the CHECK constraint refuses a negative balance even if the code is wrong")
    void checkConstraintIsTheLastLineOfDefence() throws Exception {
        long alice = createPlayer("alice", 10);
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE player SET coins = coins - 999 WHERE id = ?")) {
            ps.setLong(1, alice);
            assertThat(catchSqlException(ps)).as("MySQL rejects the overdraw").isTrue();
        }
        assertThat(economy.coins(alice)).isEqualTo(10);
    }

    private static boolean catchSqlException(PreparedStatement ps) {
        try {
            ps.executeUpdate();
            return false;
        } catch (SQLException expected) {
            return true;
        }
    }

    @Test
    @DisplayName("reconciliation finds exactly the balances their ledger does not explain")
    void reconciliationFindsDrift() throws Exception {
        long alice = createPlayer("alice", 0);
        long bob = createPlayer("bob", 0);
        long carol = createPlayer("carol", 0);
        economy.credit(alice, 100, EconomyRepository.REASON_MATCH_REWARD, "m1", "reward:m1:alice");
        economy.credit(bob, 40, EconomyRepository.REASON_MATCH_REWARD, "m1", "reward:m1:bob");
        economy.purchase(alice, "eq_barrel_steel", 30, "buy-1");

        long dan = createPlayer("dan", 0);
        economy.credit(dan, 30, EconomyRepository.REASON_TOURNAMENT_PRIZE, "t1", "tourney:t1:1:dan:gems",
                EconomyRepository.CURRENCY_GEMS);
        economy.purchase(dan, "boost_xp_hour", 20, "buy-2", EconomyRepository.CURRENCY_GEMS);

        assertThat(economy.reconcile(10).count()).as("every balance explained, coins and gems").isZero();

        // A balance moved without a ledger row, which is the bug the ledger exists to expose;
        // one that was never given anything but has coins all the same; and gems moved so.
        try (Connection c = db.dataSource().getConnection();
             Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE player SET coins = coins + 5 WHERE id = " + bob);
            st.executeUpdate("UPDATE player SET coins = 7 WHERE id = " + carol);
            st.executeUpdate("UPDATE player SET gems = gems + 3 WHERE id = " + dan);
        }
        EconomyRepository.Reconciliation r = economy.reconcile(1);
        assertThat(r.count()).as("all three, although only one sample was asked for").isEqualTo(3);
        assertThat(r.samples()).singleElement()
                .isEqualTo(new EconomyRepository.Mismatch(bob, 45, 40, 0, 0));
        assertThat(economy.reconcile(10).samples()).as("gems against their own rows")
                .contains(new EconomyRepository.Mismatch(dan, 0, 0, 13, 10));
    }

    @Test
    @DisplayName("reconciliation reads the ledger a range of players at a time, each its own statement, and finds the same (D-44)")
    void reconciliationGoesByRanges() throws Exception {
        long[] ids = new long[5];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = createPlayer("rng" + i, 0);
            economy.credit(ids[i], 10 + i, EconomyRepository.REASON_MATCH_REWARD, "m1", "reward:m1:rng" + i);
        }
        try (Connection c = db.dataSource().getConnection();
             Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE player SET coins = coins + 1 WHERE id IN (" + ids[1] + ", " + ids[4] + ")");
        }
        RecordingDataSource recorded = new RecordingDataSource(db.dataSource());
        EconomyRepository.Reconciliation r = new EconomyRepository(recorded.dataSource).reconcile(10, 2);
        assertThat(r.count()).isEqualTo(2);
        assertThat(r.samples()).extracting(EconomyRepository.Mismatch::playerId).containsExactly(ids[1], ids[4]);
        assertThat(r).as("the same as one statement over all").isEqualTo(economy.reconcile(10, Integer.MAX_VALUE));
        long ranges = recorded.executed.stream().filter(s -> s.contains("FROM ledger")).count();
        assertThat(ranges).as("one statement a range of two ids, none over the whole ledger").isGreaterThanOrEqualTo(3);
        assertThat(recorded.executed.stream().filter(s -> s.contains("FROM ledger")))
                .allMatch(s -> s.contains("player_id >= ?") && s.contains("player_id < ?"));
        assertThat(recorded.executed.stream().filter(s -> s.contains("FROM ledger")))
                .as("the ledger bounded as the players are, two ids, not left open (a mutation check)")
                .allMatch(s -> {
                    String[] v = s.substring(s.lastIndexOf('[') + 1, s.length() - 1).split(", ");
                    return v[0].equals(v[2]) && v[1].equals(v[3]) && Long.parseLong(v[1]) - Long.parseLong(v[0]) == 2;
                });
    }
}
