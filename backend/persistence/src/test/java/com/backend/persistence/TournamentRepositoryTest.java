package com.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.backend.persistence.TournamentRepository.Match;
import com.backend.persistence.TournamentRepository.Registration;
import com.backend.persistence.TournamentRepository.Tournament;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tournaments' first slice (docs 04 §6, 06 §3, D-40), against a real MySQL. */
class TournamentRepositoryTest {

    private static final String URL = System.getenv().getOrDefault("JDBC_URL",
            "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "backend");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD",
            "backend-dev-password");
    private static final Instant T = Instant.parse("2026-10-01T12:00:00Z");

    private static Database db;
    private static TournamentRepository tournaments;

    @BeforeAll
    static void setUp() {
        db = new Database(URL, USER, PASSWORD, 4);
        tournaments = new TournamentRepository(db.dataSource());
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

    /** A player who has played enough to enter (Q-43); {@link #entrantsHavePlayed} takes some away. */
    private static long player(String name, int rating) throws SQLException {
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
                    "INSERT INTO player (id, public_code, display_name, rating_duel, rated_duels) VALUES (?,?,?,?,?)")) {
                ps.setLong(1, id);
                ps.setString(2, String.format("P%011d", id));
                ps.setString(3, name);
                ps.setInt(4, rating);
                ps.setInt(5, RatingBoards.MIN_RATED);
                ps.executeUpdate();
            }
            return id;
        }
    }

    private static long open(int maxEntries) throws SQLException {
        return tournaments.create("Cup", maxEntries, T.plus(Duration.ofHours(1)), T.plus(Duration.ofHours(2)), 5,
                1_000, 500, 250);
    }

    @Test
    @DisplayName("registration: once a player, until the deadline, up to the entries allowed; withdrawn before it")
    void registering() throws Exception {
        long cup = open(2);
        long ada = player("ada", 1_200);
        long bob = player("bob", 1_300);
        long cy = player("cyd", 1_100);
        assertThat(tournaments.register(cup, ada, T)).isEqualTo(Registration.OK);
        assertThat(tournaments.register(cup, ada, T)).isEqualTo(Registration.ALREADY);
        assertThat(tournaments.register(cup, bob, T)).isEqualTo(Registration.OK);
        assertThat(tournaments.register(cup, cy, T)).isEqualTo(Registration.FULL);
        assertThat(tournaments.withdraw(cup, bob, T)).isTrue();
        assertThat(tournaments.register(cup, cy, T.plus(Duration.ofHours(1)))).as("the deadline").isEqualTo(Registration.CLOSED);
        assertThat(tournaments.withdraw(cup, ada, T.plus(Duration.ofHours(1)))).isFalse();
        assertThat(tournaments.register(999_999, cy, T)).isEqualTo(Registration.NO_SUCH_TOURNAMENT);
        assertThat(tournaments.entries(cup)).extracting(TournamentRepository.Entry::playerId).containsExactly(ada);
    }

    @Test
    @DisplayName("seeding orders by duel rating, places the bracket, and sends the top seeds' byes on")
    void seeding() throws Exception {
        long cup = open(8);
        List<Long> p = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            p.add(player("p" + i + "x", 1_000 + 100 * i));          // p4 rated highest
            tournaments.register(cup, p.get(i), T.plusSeconds(i));
        }
        List<Long> order = tournaments.bySeed(cup);
        assertThat(order).as("by rating").containsExactly(p.get(4), p.get(3), p.get(2), p.get(1), p.get(0));
        Tournament t = tournaments.get(cup);
        assertThat(tournaments.seed(cup, t.version() + 1, order)).as("a stale version").isFalse();
        assertThat(tournaments.seed(cup, t.version(), order)).isTrue();
        assertThat(tournaments.get(cup).state()).isEqualTo(TournamentRepository.SEEDED);
        assertThat(tournaments.seed(cup, t.version(), order)).as("twice").isFalse();

        long s1 = p.get(4);
        long s2 = p.get(3);
        long s3 = p.get(2);
        long s4 = p.get(1);
        long s5 = p.get(0);
        List<Match> m = tournaments.matches(cup);
        // Eight places: 1 v 8, 4 v 5, 2 v 7, 3 v 6; seeds 6 to 8 are nobody, so 1, 2 and 3 go through.
        assertThat(m).hasSize(4 + 2 + 1);
        assertThat(m.get(0)).isEqualTo(new Match(1, 0, s1, null, TournamentRepository.DONE, null, null, s1));
        assertThat(m.get(1)).isEqualTo(new Match(1, 1, s4, s5, TournamentRepository.PENDING, null, null, null));
        assertThat(m.get(2).winner()).isEqualTo(s2);
        assertThat(m.get(3).winner()).isEqualTo(s3);
        assertThat(m.get(4)).isEqualTo(new Match(2, 0, s1, null, TournamentRepository.PENDING, null, null, null));
        assertThat(m.get(5)).isEqualTo(new Match(2, 1, s2, s3, TournamentRepository.PENDING, null, null, null));
        assertThat(m.get(6)).isEqualTo(new Match(3, 0, null, null, TournamentRepository.PENDING, null, null, null));
        assertThat(tournaments.entries(cup)).extracting(TournamentRepository.Entry::seed).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    @DisplayName("the deadline seeds from the entries it locks: a registration committing meanwhile is in the bracket (T-36)")
    void closingSeedsTheEntriesItLocks() throws Exception {
        long cup = open(8);
        long a = player("cla", 1_400);
        long b = player("clb", 1_300);
        long d = player("cld", 1_200);
        tournaments.register(cup, a, T);
        tournaments.register(cup, b, T);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (Connection other = db.dataSource().getConnection()) {
            other.setAutoCommit(false);                        // a registration in flight, as register() makes one
            try (PreparedStatement ps = other.prepareStatement("SELECT id FROM tournament WHERE id = ? FOR UPDATE")) {
                ps.setLong(1, cup);
                ps.executeQuery().close();
            }
            try (PreparedStatement ps = other.prepareStatement(
                    "INSERT INTO tournament_entry (tournament_id, player_id, registered_at) VALUES (?, ?, ?)")) {
                ps.setLong(1, cup);
                ps.setLong(2, d);
                ps.setTimestamp(3, java.sql.Timestamp.from(T));
                ps.executeUpdate();
            }
            java.util.concurrent.Future<TournamentRepository.Closed> closed =
                    pool.submit(() -> tournaments.close(cup, tournaments.get(cup).version()));
            Thread.sleep(500);                                  // the deadline waits on the tournament's row
            other.commit();
            assertThat(closed.get(20, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(TournamentRepository.Closed.SEEDED);
        } finally {
            pool.shutdownNow();
        }
        assertThat(tournaments.entries(cup)).extracting(TournamentRepository.Entry::seed).containsExactly(1, 2, 3);
        assertThat(tournaments.matches(cup)).as("all three in the bracket").flatExtracting(Match::playerA, Match::playerB)
                .contains(a, b, d);
    }

    @Test
    @DisplayName("the deadline with fewer than two entries cancels, once; a stale version does nothing")
    void closingCancels() throws Exception {
        long cup = open(8);
        tournaments.register(cup, player("onl", 1_000), T);
        int version = tournaments.get(cup).version();
        assertThat(tournaments.close(cup, version + 1)).isEqualTo(TournamentRepository.Closed.STALE);
        assertThat(tournaments.close(cup, version)).isEqualTo(TournamentRepository.Closed.CANCELLED);
        assertThat(tournaments.get(cup).state()).isEqualTo(TournamentRepository.CANCELLED);
        assertThat(tournaments.close(cup, version)).isEqualTo(TournamentRepository.Closed.STALE);
    }

    @Test
    @DisplayName("a match is made ready once, decided once, and its winner fills the next round")
    void readyAndDecide() throws Exception {
        long cup = open(4);
        long a = player("aaa", 1_400);
        long b = player("bbb", 1_300);
        long c = player("ccc", 1_200);
        long d = player("ddd", 1_100);
        for (long x : List.of(a, b, c, d)) {
            tournaments.register(cup, x, T);
        }
        tournaments.seed(cup, 0, tournaments.bySeed(cup));
        assertThat(tournaments.start(cup, 1)).isTrue();
        assertThat(tournaments.get(cup).currentRound()).isEqualTo(1);

        assertThat(tournaments.ready(cup, 1, 0, "01JCMATCH00000000000000001", T)).isTrue();
        assertThat(tournaments.ready(cup, 1, 0, "01JCMATCH00000000000000009", T)).as("once").isFalse();
        assertThat(tournaments.matches(cup).get(0).matchUid()).isEqualTo("01JCMATCH00000000000000001");
        assertThat(tournaments.decide(cup, 1, 0, d)).isTrue();                  // 1 v 4: 4 wins
        assertThat(tournaments.decide(cup, 1, 0, a)).as("once").isFalse();
        assertThat(tournaments.matches(cup).get(2)).extracting(Match::playerA, Match::playerB).containsExactly(d, null);
        tournaments.ready(cup, 1, 1, "01JCMATCH00000000000000002", T);
        tournaments.decide(cup, 1, 1, b);                                       // 2 v 3: 2 wins
        assertThat(tournaments.matches(cup).get(2)).extracting(Match::playerA, Match::playerB).containsExactly(d, b);

        Tournament t = tournaments.get(cup);
        assertThat(tournaments.endRound(cup, t.version(), T.plusSeconds(60))).isTrue();
        assertThat(tournaments.get(cup).currentRound()).isEqualTo(2);
        assertThat(tournaments.get(cup).roundEndedAt()).isEqualTo(T.plusSeconds(60));
        assertThat(tournaments.endRound(cup, t.version(), T.plusSeconds(61))).as("a stale version").isFalse();
        assertThat(tournaments.finish(cup, tournaments.get(cup).version())).isTrue();
        assertThat(tournaments.get(cup).state()).isEqualTo(TournamentRepository.FINISHED);
        assertThat(tournaments.inStates(TournamentRepository.REGISTRATION, TournamentRepository.RUNNING)).isEmpty();
    }

    @Test
    @DisplayName("what MySQL recorded for a match: nothing yet, one winner, or a draw")
    void results() throws Exception {
        long a = player("aaa", 1_200);
        long b = player("bbb", 1_200);
        assertThat(tournaments.resultOf("01JCMATCH00000000000000001")).isNull();
        record(a, b, "01JCMATCH00000000000000001", 1, 2);
        assertThat(tournaments.resultOf("01JCMATCH00000000000000001")).containsExactly(a);
        record(a, b, "01JCMATCH00000000000000002", 1, 1);
        assertThat(tournaments.resultOf("01JCMATCH00000000000000002")).as("a draw").containsExactlyInAnyOrder(a, b);
        recordOne(a, "01JCMATCH00000000000000003");
        assertThat(tournaments.resultOf("01JCMATCH00000000000000003")).as("a walkover").containsExactly(a);
    }

    // ---- teams' tournaments (the second slice, Q-19, D-44) ---------------------------------

    private static long teamOf(String name, int rating, long... players) throws SQLException {
        TeamRepository teams = new TeamRepository(db.dataSource(), 30);
        long id = teams.create(players[0], name, T).teamId();
        for (int i = 1; i < players.length; i++) {
            teams.invite(players[0], players[i], T);
            teams.answer(players[i], id, true, T);
        }
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE team SET rating = ?, rated_matches = ? WHERE id = ?")) {
            ps.setInt(1, rating);
            ps.setInt(2, RatingBoards.MIN_RATED);              // played enough to enter (Q-43)
            ps.setLong(3, id);
            ps.executeUpdate();
        }
        return id;
    }

    /** The match between {@code x} and {@code y}, made and then decided as {@code winner} says: null a draw. */
    private static void play(long id, long x, long y, Long winner, int n) throws SQLException {
        for (Match m : tournaments.matches(id)) {
            if (java.util.Set.of(m.playerA(), m.playerB()).equals(java.util.Set.of(x, y))) {
                assertThat(tournaments.ready(id, m.round(), m.slot(), String.format("01JCLEAGUE%016d", n), T)).isTrue();
                assertThat(winner == null ? tournaments.draw(id, m.round(), m.slot())
                        : tournaments.decide(id, m.round(), m.slot(), winner)).isTrue();
                return;
            }
        }
        throw new AssertionError("no match between " + x + " and " + y);
    }

    @Test
    @DisplayName("a round robin is seeded whole, every pair once and a bye no match; a draw is a match done with no winner, nothing moving on; the standings by points, wins, seed (04 §6, plan item 66)")
    void roundRobin() throws Exception {
        long league = tournaments.create("League", 8, T.plus(Duration.ofHours(1)), T.plus(Duration.ofHours(2)), 5,
                300, 200, 100, MatchResultRepository.MODE_DUEL, TournamentRepository.ROUND_ROBIN);
        assertThat(tournaments.get(league).format()).isEqualTo(TournamentRepository.ROUND_ROBIN);
        assertThat(tournaments.get(open(4)).format()).as("elimination, unless said").isEqualTo(TournamentRepository.ELIMINATION);
        long a = player("rra", 1_300);
        long b = player("rrb", 1_200);
        long c = player("rrc", 1_100);
        for (long p : new long[] {a, b, c}) {
            assertThat(tournaments.register(league, p, T)).isEqualTo(Registration.OK);
        }
        assertThat(tournaments.seed(league, tournaments.get(league).version(), tournaments.bySeed(league))).isTrue();
        List<Match> m = tournaments.matches(league);
        assertThat(m).as("three rounds of one, each sitting one out").hasSize(3)
                .allSatisfy(x -> assertThat(List.of(x.state(), x.playerA() != null, x.playerB() != null))
                        .containsExactly(TournamentRepository.PENDING, true, true));
        assertThat(m).extracting(Match::round).containsExactly(1, 2, 3);
        assertThat(tournaments.start(league, tournaments.get(league).version())).isTrue();

        play(league, a, b, a, 1);                              // a beats b
        play(league, b, c, null, 2);                           // b and c draw
        assertThat(tournaments.matches(league)).as("nothing moved on").extracting(Match::playerA, Match::playerB)
                .containsExactlyElementsOf(m.stream().map(x -> org.assertj.core.groups.Tuple.tuple(x.playerA(), x.playerB())).toList());
        Match drawn = tournaments.matches(league).stream().filter(x -> java.util.Set.of(x.playerA(), x.playerB())
                .equals(java.util.Set.of(b, c))).findFirst().orElseThrow();
        assertThat(List.of(drawn.state(), drawn.winner() == null)).as("a draw: done, no winner")
                .containsExactly(TournamentRepository.DONE, true);
        play(league, a, c, c, 3);                              // c beats a

        assertThat(tournaments.standings(league)).extracting(TournamentRepository.Standing::entry, TournamentRepository.Standing::points,
                TournamentRepository.Standing::wins, TournamentRepository.Standing::draws, TournamentRepository.Standing::losses)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(c, 4, 1, 1, 0), org.assertj.core.groups.Tuple.tuple(a, 3, 1, 0, 1),
                        org.assertj.core.groups.Tuple.tuple(b, 1, 0, 1, 1));
        assertThat(tournaments.standings(league).get(0).name()).isEqualTo("rrc");

        long pair = tournaments.create("Pair", 8, T.plus(Duration.ofHours(1)), T.plus(Duration.ofHours(2)), 5,
                0, 0, 0, MatchResultRepository.MODE_DUEL, TournamentRepository.ROUND_ROBIN);
        long d = player("rrd", 1_150);
        long e = player("rre", 1_250);
        tournaments.register(pair, d, T);
        tournaments.register(pair, e, T);
        tournaments.seed(pair, tournaments.get(pair).version(), tournaments.bySeed(pair));
        tournaments.start(pair, tournaments.get(pair).version());
        play(pair, d, e, null, 4);
        assertThat(tournaments.standings(pair)).as("level on points and wins: the higher seed first")
                .extracting(TournamentRepository.Standing::entry).containsExactly(e, d);

        long five = tournaments.create("Five", 8, T.plus(Duration.ofHours(1)), T.plus(Duration.ofHours(2)), 5,
                0, 0, 0, MatchResultRepository.MODE_DUEL, TournamentRepository.ROUND_ROBIN);
        long u = player("rru", 1_500);
        long v = player("rrv", 1_400);
        long w = player("rrw", 1_300);
        long y = player("rry", 1_200);                         // the higher seed of the two level on points
        long x = player("rrx", 1_100);
        for (long p : new long[] {u, v, w, y, x}) {
            tournaments.register(five, p, T);
        }
        tournaments.seed(five, tournaments.get(five).version(), tournaments.bySeed(five));
        tournaments.start(five, tournaments.get(five).version());
        int n = 10;
        play(five, x, y, x, n++);                              // x: one win, three losses, 3
        for (long o : new long[] {u, v, w}) {
            play(five, x, o, o, n++);
            play(five, y, o, null, n++);                       // y: three draws and a loss, 3
        }
        play(five, u, v, u, n++);
        play(five, u, w, u, n++);
        play(five, v, w, v, n++);
        assertThat(tournaments.standings(five)).as("level on points: the one with more wins first, though seeded lower")
                .extracting(TournamentRepository.Standing::entry).containsExactly(u, v, w, x, y);
    }

    @Test
    @DisplayName("an entrant has played: ten rated duels for a player, ten rated team matches for a team; refused after closed and before full (Q-43)")
    void entrantsHavePlayed() throws Exception {
        long cup = open(1);
        long ada = player("ada", 1_200);
        long bob = player("bob", 1_200);
        count("UPDATE player SET rated_duels = ? WHERE id = ?", RatingBoards.MIN_RATED - 1, ada);
        assertThat(tournaments.register(cup, ada, T)).isEqualTo(Registration.TOO_FEW_RATED);
        assertThat(tournaments.register(cup, ada, T.plus(Duration.ofHours(1)))).as("closed, first").isEqualTo(Registration.CLOSED);
        assertThat(tournaments.register(cup, bob, T)).isEqualTo(Registration.OK);
        assertThat(tournaments.register(cup, ada, T)).as("before full").isEqualTo(Registration.TOO_FEW_RATED);
        count("UPDATE player SET rated_duels = ? WHERE id = ?", RatingBoards.MIN_RATED, ada);
        assertThat(tournaments.register(cup, ada, T)).as("ten: then the one place is taken").isEqualTo(Registration.FULL);

        long teams = teamsCup(2);
        long[] a = three("ha");
        long ta = teamOf("TeamH", 1_200, a);
        count("UPDATE team SET rated_matches = ? WHERE id = ?", RatingBoards.MIN_RATED - 1, ta);
        assertThat(tournaments.registerTeam(teams, ta, list(a), T)).as("the team's own count").isEqualTo(Registration.TOO_FEW_RATED);
        count("UPDATE team SET rated_matches = ? WHERE id = ?", RatingBoards.MIN_RATED, ta);
        count("UPDATE player SET rated_duels = ? WHERE id = ?", 0, a[1]);
        assertThat(tournaments.registerTeam(teams, ta, list(a), T)).as("its players' duels not counted").isEqualTo(Registration.OK);
    }

    private static void count(String sql, int count, long id) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, count);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    @Test
    @DisplayName("a team's roster is entered in ascending player id, the order every path locks players in (D-37, 06 §6)")
    void aRosterIsEnteredInOrder() throws Exception {
        long cup = teamsCup(2);
        long[] a = three("ro");
        long ta = teamOf("TeamR", 1_200, a);
        RecordingDataSource recorded = new RecordingDataSource(db.dataSource());
        assertThat(new TournamentRepository(recorded.dataSource).registerTeam(cup, ta, List.of(a[2], a[0], a[1]), T))
                .isEqualTo(Registration.OK);
        assertThat(recorded.executed.stream().filter(s -> s.startsWith("INSERT INTO tournament_roster"))
                .map(s -> Long.parseLong(s.substring(s.lastIndexOf(' ') + 1, s.length() - 1))).toList())
                .containsExactly(a[0], a[1], a[2]);
    }

    @Test
    @DisplayName("a finished or cancelled tournament is deleted 90 days after it was to start, with its entries, rosters and bracket; one running is kept (D-38, Q-47)")
    void oldTournamentsAreDeleted() throws Exception {
        long done = open(4);
        long ada = player("ada", 1_200);
        long bob = player("bob", 1_200);
        assertThat(tournaments.register(done, ada, T)).isEqualTo(Registration.OK);
        assertThat(tournaments.register(done, bob, T)).isEqualTo(Registration.OK);
        long cup = teamsCup(2);
        long[] a = three("pa");
        assertThat(tournaments.registerTeam(cup, teamOf("TeamP", 1_200, a), list(a), T)).isEqualTo(Registration.OK);
        long recent = open(4);
        long running = open(4);
        Instant now = T.plus(Duration.ofDays(365));
        Instant cutoff = now.minus(TournamentRepository.KEPT);
        try (Connection c = db.dataSource().getConnection();
             java.sql.Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO tournament_match (tournament_id, round, slot, player_a, player_b, state)"
                    + " VALUES (" + done + ", 1, 0, " + ada + ", " + bob + ", 2)");
        }
        aged(done, TournamentRepository.FINISHED, cutoff.minusSeconds(60));
        aged(cup, TournamentRepository.CANCELLED, cutoff.minusSeconds(60));
        aged(recent, TournamentRepository.FINISHED, cutoff.plusSeconds(60));
        aged(running, TournamentRepository.RUNNING, cutoff.minus(Duration.ofDays(100)));

        assertThat(tournaments.purge(now, 1)).as("two, one at a time").isEqualTo(2);
        assertThat(rows("SELECT COUNT(*) FROM tournament WHERE id IN (" + recent + "," + running + ")")).isEqualTo(2);
        for (String table : new String[] {"tournament", "tournament_entry", "tournament_match", "tournament_team_entry",
                "tournament_roster"}) {
            assertThat(rows("SELECT COUNT(*) FROM " + table + " WHERE " + (table.equals("tournament") ? "id" : "tournament_id")
                    + " IN (" + done + "," + cup + ")")).as(table).isZero();
        }
        assertThat(tournaments.purge(now, 1)).as("and nothing the second time").isZero();
    }

    /** The tournament put in {@code state}, as if it was to start at {@code startsAt}. */
    private static void aged(long id, int state, Instant startsAt) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE tournament SET state = ?, starts_at = ? WHERE id = ?")) {
            ps.setInt(1, state);
            ps.setTimestamp(2, java.sql.Timestamp.from(startsAt));
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    private static int rows(String sql) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             java.sql.Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static long[] three(String prefix) throws SQLException {
        return new long[] {player(prefix + "1", 1_200), player(prefix + "2", 1_200), player(prefix + "3", 1_200)};
    }

    private static List<Long> list(long[] ids) {
        return java.util.Arrays.stream(ids).boxed().toList();
    }

    private static long teamsCup(int maxEntries) throws SQLException {
        return tournaments.create("Team cup", maxEntries, T.plus(Duration.ofHours(1)), T.plus(Duration.ofHours(2)), 5,
                300, 200, 100, MatchResultRepository.MODE_TEAMS);
    }

    @Test
    @DisplayName("a teams' tournament: a team entered once with its roster, until the deadline and up to the entries; withdrawn before it")
    void teamEntries() throws Exception {
        assertThat(tournaments.get(open(4)).mode()).as("a duel's, unless said").isEqualTo(MatchResultRepository.MODE_DUEL);
        long cup = teamsCup(2);
        assertThat(tournaments.get(cup).mode()).isEqualTo(MatchResultRepository.MODE_TEAMS);
        long[] a = three("aa");
        long[] b = three("bb");
        long[] c = three("cc");
        long ta = teamOf("TeamA", 1_200, a);
        long tb = teamOf("TeamB", 1_200, b);
        long tc = teamOf("TeamC", 1_200, c);

        assertThat(tournaments.registerTeam(cup, ta, list(a), T)).isEqualTo(Registration.OK);
        assertThat(tournaments.registerTeam(cup, ta, list(a), T)).isEqualTo(Registration.ALREADY);
        assertThat(tournaments.registerTeam(cup, tc, List.of(c[0], c[1], a[0]), T)).as("a player on another's roster")
                .isEqualTo(Registration.ALREADY);
        assertThat(tournaments.teamEntries(cup)).as("and no entry left half made").extracting(TournamentRepository.TeamEntry::teamId)
                .containsExactly(ta);
        assertThat(tournaments.registerTeam(cup, tb, list(b), T)).isEqualTo(Registration.OK);
        assertThat(tournaments.registerTeam(cup, tc, list(c), T)).isEqualTo(Registration.FULL);
        assertThat(tournaments.teamEntries(cup)).extracting(TournamentRepository.TeamEntry::teamId).containsExactly(ta, tb);
        assertThat(tournaments.teamEntries(cup).get(0).name()).isEqualTo("TeamA");
        assertThat(tournaments.rosterOf(cup, tb)).extracting(TournamentRepository.Rostered::playerId)
                .containsExactlyInAnyOrder(b[0], b[1], b[2]);
        assertThat(tournaments.rosterOf(cup, tb)).extracting(TournamentRepository.Rostered::name)
                .containsExactlyInAnyOrder("bb1", "bb2", "bb3");

        assertThat(tournaments.withdrawTeam(cup, tb, T)).isTrue();
        assertThat(tournaments.teamEntries(cup)).extracting(TournamentRepository.TeamEntry::teamId).containsExactly(ta);
        assertThat(tournaments.rosterOf(cup, tb)).as("the roster goes with its entry").isEmpty();
        assertThat(tournaments.registerTeam(cup, tc, list(c), T.plus(Duration.ofHours(1)))).isEqualTo(Registration.CLOSED);
        assertThat(tournaments.withdrawTeam(cup, ta, T.plus(Duration.ofHours(1)))).as("after the deadline").isFalse();
    }

    @Test
    @DisplayName("a teams' tournament is seeded by team rating, then by who registered first, its bracket holding the teams' ids")
    void teamSeeding() throws Exception {
        long cup = teamsCup(8);
        long[] a = three("aa");
        long[] b = three("bb");
        long[] c = three("cc");
        long ta = teamOf("TeamA", 1_250, a);
        long tb = teamOf("TeamB", 1_250, b);
        long tc = teamOf("TeamC", 1_300, c);
        tournaments.registerTeam(cup, ta, list(a), T);
        tournaments.registerTeam(cup, tb, list(b), T.plusSeconds(1));
        tournaments.registerTeam(cup, tc, list(c), T.plusSeconds(2));

        List<Long> bySeed = tournaments.teamsBySeed(cup);
        assertThat(bySeed).containsExactly(tc, ta, tb);
        new TeamRepository(db.dataSource(), 30).disband(c[0], T);
        assertThat(tournaments.teamsBySeed(cup)).as("a team disbanded since: last").containsExactly(ta, tb, tc);
        assertThat(tournaments.teamEntries(cup)).as("its entry stays, nameless").hasSize(3)
                .filteredOn(e -> e.teamId() == tc).singleElement().extracting(TournamentRepository.TeamEntry::name).isNull();
        assertThat(tournaments.seed(cup, 0, bySeed)).isTrue();
        assertThat(tournaments.teamEntries(cup)).extracting(TournamentRepository.TeamEntry::teamId,
                TournamentRepository.TeamEntry::seed).containsExactly(org.assertj.core.groups.Tuple.tuple(tc, 1),
                org.assertj.core.groups.Tuple.tuple(ta, 2), org.assertj.core.groups.Tuple.tuple(tb, 3));
        List<Match> bracket = tournaments.matches(cup);
        assertThat(bracket.get(0)).extracting(Match::playerA, Match::playerB, Match::winner)
                .as("the top seed's bye").containsExactly(tc, null, tc);
        assertThat(bracket.get(1)).extracting(Match::playerA, Match::playerB).containsExactly(ta, tb);
        assertThat(tournaments.entries(cup)).as("no player entries").isEmpty();
    }

    @Test
    @DisplayName("a team match's result by side: nothing yet, the side placed first, both for a draw")
    void resultsBySide() throws Exception {
        long a = player("aaa", 1_200);
        long b = player("bbb", 1_200);
        assertThat(tournaments.sidesPlacedFirst("01JCMATCH00000000000000011")).isNull();
        long match = matchRow("01JCMATCH00000000000000011");
        placedOnSide(match, a, 1, 1);
        placedOnSide(match, b, 2, 2);
        assertThat(tournaments.sidesPlacedFirst("01JCMATCH00000000000000011")).containsExactly(1);
        long draw = matchRow("01JCMATCH00000000000000012");
        placedOnSide(draw, a, 1, 1);
        placedOnSide(draw, b, 2, 1);
        assertThat(tournaments.sidesPlacedFirst("01JCMATCH00000000000000012")).containsExactlyInAnyOrder(1, 2);
        placedOnSide(matchRow("01JCMATCH00000000000000013"), b, 2, 1);
        assertThat(tournaments.sidesPlacedFirst("01JCMATCH00000000000000013")).as("a walkover").containsExactly(2);
    }

    private static void placedOnSide(long match, long player, int side, int placement) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO match_player (match_id, player_id, team, placement,"
                     + " kills, deaths, score, xp_gained, rating_delta) VALUES (?, ?, ?, ?, 0, 0, 0, 0, 0)")) {
            ps.setLong(1, match);
            ps.setLong(2, player);
            ps.setInt(3, side);
            ps.setInt(4, placement);
            ps.executeUpdate();
        }
    }

    private static void record(long a, long b, String uid, int placeA, int placeB) throws SQLException {
        long match = matchRow(uid);
        placed(match, a, placeA);
        placed(match, b, placeB);
    }

    private static void recordOne(long a, String uid) throws SQLException {
        placed(matchRow(uid), a, 1);
    }

    private static long matchRow(String uid) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO matches (match_uid, mode, kind, arena, started_at, ended_at)"
                     + " VALUES (?, 1, 1, 'arena-1', NOW(3), NOW(3))", PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, uid);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static void placed(long match, long player, int placement) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO match_player (match_id, player_id, team, placement,"
                     + " kills, deaths, score, xp_gained, rating_delta) VALUES (?, ?, 0, ?, 0, 0, 0, 0, 0)")) {
            ps.setLong(1, match);
            ps.setLong(2, player);
            ps.setInt(3, placement);
            ps.executeUpdate();
        }
    }
}
