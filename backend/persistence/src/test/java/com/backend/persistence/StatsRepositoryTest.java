package com.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;

import com.backend.persistence.StatsRepository.Day;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Whether players come back (05 §11, Q-24, D-47), against a real MySQL. */
class StatsRepositoryTest {

    private static final String URL = System.getenv().getOrDefault("JDBC_URL",
            "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "backend");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD",
            "backend-dev-password");
    private static final LocalDate TODAY = LocalDate.parse("2026-10-01");

    private static Database db;
    private static AccountRepository accounts;
    private static StatsRepository stats;

    @BeforeAll
    static void setUp() {
        db = new Database(URL, USER, PASSWORD, 4);
        accounts = new AccountRepository(db.dataSource());
        stats = new StatsRepository(db.dataSource());
    }

    @AfterAll
    static void tearDown() {
        db.close();
    }

    @BeforeEach
    void fresh() {
        db.resetForTests();
    }

    /** A player first active on {@code first} and active on each of {@code days}. */
    private static long player(String name, String first, String... days) throws SQLException {
        long id = accounts.register(name, name, "$argon2id$fake".getBytes(StandardCharsets.UTF_8));
        try (Connection c = db.dataSource().getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE player SET first_played_on = ? WHERE id = ?")) {
                ps.setString(1, first);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            for (String day : days) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO player_day (day, player_id) VALUES (?, ?)")) {
                    ps.setString(1, day);
                    ps.setLong(2, id);
                    ps.executeUpdate();
                }
            }
        }
        return id;
    }

    private static Day on(java.util.List<Day> days, String day) {
        return days.stream().filter(d -> d.day().equals(LocalDate.parse(day))).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("by day, newest first: the active, the new, and the new who came back, empty until the day has ended")
    void comingBack() throws Exception {
        player("ada", "2026-09-01", "2026-09-01", "2026-09-02", "2026-09-08", "2026-10-01");
        player("bob", "2026-09-01", "2026-09-01");
        player("cyd", "2026-09-30", "2026-09-30", "2026-10-01");
        player("dee", "2026-10-01", "2026-10-01");
        player("eve", "2026-08-31", "2026-08-31", "2026-09-01");

        java.util.List<Day> days = stats.daily(TODAY, 31);
        assertThat(days).hasSize(31);
        assertThat(days.get(0).day()).as("newest first, today so far").isEqualTo(TODAY);
        assertThat(days.get(30).day()).isEqualTo(LocalDate.parse("2026-09-01"));

        assertThat(on(days, "2026-10-01")).isEqualTo(new Day(TODAY, 3, 1, null, null, null));
        assertThat(on(days, "2026-09-30")).as("its next day is today, not ended")
                .isEqualTo(new Day(LocalDate.parse("2026-09-30"), 1, 1, null, null, null));
        assertThat(on(days, "2026-09-01")).as("eve was active, not new; ada came back on days 1 and 7, not 30 yet")
                .isEqualTo(new Day(LocalDate.parse("2026-09-01"), 3, 2, 1L, 1L, null));
        assertThat(on(days, "2026-09-02")).as("nobody new: nobody to come back")
                .isEqualTo(new Day(LocalDate.parse("2026-09-02"), 1, 0, 0L, 0L, null));
        assertThat(on(days, "2026-09-15")).as("a quiet day")
                .isEqualTo(new Day(LocalDate.parse("2026-09-15"), 0, 0, 0L, 0L, null));

        assertThat(on(stats.daily(TODAY.plusDays(1), 32), "2026-09-01").d30())
                .as("the day after, day 30 of 09-01 has ended: ada came back on it").isEqualTo(1L);
        assertThat(stats.daily(TODAY, 1)).extracting(Day::day).containsExactly(TODAY);
        assertThat(stats.daily(TODAY, 60)).hasSize(60);
        assertThatThrownBy(() -> stats.daily(TODAY, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stats.daily(TODAY, 61)).isInstanceOf(IllegalArgumentException.class);
    }

    private static void sql(String statement, Object... args) throws SQLException {
        try (Connection c = db.dataSource().getConnection(); PreparedStatement ps = c.prepareStatement(statement)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    /** An account's day made, and whether it is still a guest. */
    private static void made(long id, String at, boolean guest) throws SQLException {
        sql("UPDATE account SET created_at = ?, guest_key_hash = IF(?, UNHEX(SHA2(?, 256)), NULL) WHERE id = ?",
                at, guest, "key-" + id, id);
    }

    @Test
    @DisplayName("the funnel: of the accounts made each day, how many played, came back within a week, progressed, bought and paid, by now (05 §11, plan item 76 (c))")
    void funnel() throws Exception {
        long a1 = player("a1", null);                                                       // made, nothing more
        long a2 = player("a2", "2026-09-30", "2026-09-30", "2026-10-02");                 // back within the week
        long a3 = player("a3", "2026-09-30", "2026-09-30");                               // a guest who played once
        long a4 = player("a4", "2026-09-30", "2026-09-30", "2026-10-08");                 // back after eight days
        long a5 = player("a5", "2026-09-30", "2026-09-30", "2026-10-07");                 // back on the seventh day
        long b1 = player("b1", null);
        for (long id : new long[] {a1, a2, a3, a4, a5}) {
            made(id, "2026-09-30 10:00:00", id == a3);
        }
        made(b1, "2026-10-01 09:00:00", false);
        sql("UPDATE player SET level = 6, rated_tvts = 1 WHERE id = ?", a2);
        sql("UPDATE player SET level = 5 WHERE id = ?", a4);
        sql("UPDATE player SET rated_duels = 1 WHERE id = ?", a3);
        sql("INSERT INTO ledger (player_id, currency, delta, balance_after, reason, ref, idem_key) VALUES (?, 1, -20, 0, 1, 'boost', 'f1')", a2);
        sql("INSERT INTO ledger (player_id, currency, delta, balance_after, reason, ref, idem_key) VALUES (?, 0, 10, 10, 0, 'match', 'f2')", a3);
        sql("INSERT INTO ledger (player_id, currency, delta, balance_after, reason, ref, idem_key) VALUES (?, 0, 10, 10, 0, 'match', 'f3')", a5);
        sql("INSERT INTO payment_order (id, player_id, client_key, product_id, gems, price_cents, currency, provider, state, created_at)"
                + " VALUES ('00000000-0000-4000-8000-0000000000f1', ?, 'k1', 'gems_80', 80, 99, 'USD', 'simulated', 3, NOW(3))", a4);
        sql("INSERT INTO payment_order (id, player_id, client_key, product_id, gems, price_cents, currency, provider, state, created_at)"
                + " VALUES ('00000000-0000-4000-8000-0000000000f2', ?, 'k2', 'gems_80', 80, 99, 'USD', 'simulated', 2, NOW(3))", a2);

        java.util.List<StatsRepository.Funnel> days = stats.funnel(TODAY.plusDays(10), 12);       // back to 09-30
        assertThat(days).hasSize(12);
        assertThat(days.get(0).day()).as("newest first").isEqualTo(TODAY.plusDays(10));
        StatsRepository.Funnel a = days.stream().filter(d -> d.day().equals(LocalDate.parse("2026-09-30"))).findFirst().orElseThrow();
        assertThat(a).as("a2 and a5 came back, a4 too late; a4's order refunded was paid, a2's declined was not; a3 earned, never bought")
                .isEqualTo(new StatsRepository.Funnel(LocalDate.parse("2026-09-30"), 5, 1, 4, 2, 2, 2, 1, 1));
        StatsRepository.Funnel b = days.stream().filter(d -> d.day().equals(TODAY)).findFirst().orElseThrow();
        assertThat(b).isEqualTo(new StatsRepository.Funnel(TODAY, 1, 0, 0, 0, 0, 0, 0, 0));
        assertThat(days.stream().filter(d -> d.day().equals(LocalDate.parse("2026-10-05"))).findFirst().orElseThrow().registered())
                .as("a quiet day").isZero();
        assertThat(stats.funnel(TODAY, 1)).as("today so far").extracting(StatsRepository.Funnel::registered).containsExactly(1L);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> stats.funnel(TODAY, 61)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("guests measured: those never upgraded, and of them those made 90 days ago and idle since, with no day played and no login (Q-22)")
    void guests() throws Exception {
        long idle = player("g1", "2026-06-01");
        long played = player("g2", "2026-06-01", "2026-09-21");
        long young = player("g3", null);
        long loggedIn = player("g4", "2026-06-01");
        long registered = player("r1", null);
        long idleToo = player("g5", null);
        made(idleToo, "2026-05-01 00:00:00", true);
        made(idle, "2026-06-01 00:00:00", true);
        made(played, "2026-06-01 00:00:00", true);
        made(young, "2026-09-21 00:00:00", true);
        made(loggedIn, "2026-06-01 00:00:00", true);
        made(registered, "2026-06-01 00:00:00", false);
        sql("UPDATE account SET last_login_at = '2026-06-01 00:00:00' WHERE id = ?", idle);
        sql("UPDATE account SET last_login_at = '2026-09-26 00:00:00' WHERE id = ?", loggedIn);
        assertThat(stats.guests(TODAY)).as("g1, and g5 who never logged in").isEqualTo(new StatsRepository.Guests(6, 5, 2));
    }

    @Test
    @DisplayName("each day's new players split by what they did that first day, and how many of those came back (Q-26)")
    void byFeature() throws Exception {
        long ada = player("ada", "2026-09-01", "2026-09-01", "2026-09-02");
        long bob = player("bob", "2026-09-01", "2026-09-01");
        long cyd = player("cyd", "2026-09-01", "2026-09-01", "2026-09-08");
        long dee = player("dee", "2026-09-01", "2026-09-01");
        // ada: a duel on day one, and a purchase
        sql("INSERT INTO matches (id, match_uid, mode, arena, started_at, ended_at) VALUES"
                + " (1, '01JBFEAT00000000000000000A', 1, 'arena-1', '2026-09-01 10:00:00', '2026-09-01 10:03:00'),"
                + " (2, '01JBFEAT00000000000000000B', 0, 'arena-1', '2026-09-01 11:00:00', '2026-09-01 11:05:00')");
        sql("INSERT INTO match_player (match_id, player_id, team, placement, kills, deaths, score, xp_gained)"
                + " VALUES (1, ?, 0, 1, 0, 0, 0, 0), (2, ?, 0, 0, 0, 0, 0, 0)", ada, bob);
        sql("INSERT INTO ledger (player_id, currency, delta, balance_after, reason, ref, idem_key, created_at)"
                + " VALUES (?, 0, -5, 0, 1, 'x', 'buy-a', '2026-09-01 12:00:00'),"
                + " (?, 0, -5, 0, 1, 'x', 'buy-b', '2026-09-02 12:00:00'),"
                + " (?, 0, -5, 0, 1, 'x', 'buy-d', '2026-08-31 23:59:00')", ada, bob, dee);   // dee, the day before
        // bob: a friend on day one (and bought only on day two, above)
        sql("INSERT INTO friend (player_id, friend_id, since) VALUES (?, ?, '2026-09-01 13:00:00'),"
                + " (?, ?, '2026-09-01 13:00:00')", bob, ada, ada, bob);
        // cyd: a boost, a team and a duel tournament on day one
        sql("INSERT INTO boost (player_id, kind, item_id, percent, started_at, ends_at)"
                + " VALUES (?, 0, 'b', 100, '2026-09-01 14:00:00', '2026-09-01 15:00:00')", cyd);
        sql("INSERT INTO team (id, name, member_count) VALUES (1, 'tm', 1)");
        sql("INSERT INTO team_member (team_id, player_id, role, joined_at) VALUES (1, ?, 0, '2026-09-01 15:00:00')", cyd);
        sql("INSERT INTO tournament (id, name, state, max_entries, registration_ends, starts_at, round_minutes,"
                + " prize_1, prize_2, prize_3) VALUES (1, 't', 0, 8, '2026-09-02', '2026-09-02', 5, 1, 1, 1),"
                + " (2, 'u', 0, 8, '2026-09-02', '2026-09-02', 5, 1, 1, 1)");
        sql("INSERT INTO tournament_entry (tournament_id, player_id, registered_at) VALUES (1, ?, '2026-09-01 16:00:00')", cyd);
        // dee: on a team's roster for a teams' tournament registered on day one
        sql("INSERT INTO tournament_team_entry (tournament_id, team_id, registered_at) VALUES (2, 1, '2026-09-01 17:00:00')");
        sql("INSERT INTO tournament_roster (tournament_id, team_id, player_id) VALUES (2, 1, ?)", dee);

        StatsRepository.FeatureDay day = stats.byFeature(TODAY, 31).stream()
                .filter(d -> d.day().equals(LocalDate.parse("2026-09-01"))).findFirst().orElseThrow();
        assertThat(day.newPlayers()).isEqualTo(4);
        assertThat(day.features()).as("in a fixed order").containsOnlyKeys(StatsRepository.FEATURES);
        assertThat(day.features().get("queued")).as("ada's duel, not bob's public stay")
                .isEqualTo(new StatsRepository.Used(1, 1L, 0L, null));
        assertThat(day.features().get("bought")).as("ada on day one; bob's was day two, dee's the day before")
                .isEqualTo(new StatsRepository.Used(1, 1L, 0L, null));
        assertThat(day.features().get("friend")).isEqualTo(new StatsRepository.Used(2, 1L, 0L, null));
        assertThat(day.features().get("boosted")).isEqualTo(new StatsRepository.Used(1, 0L, 1L, null));
        assertThat(day.features().get("team")).isEqualTo(new StatsRepository.Used(1, 0L, 1L, null));
        assertThat(day.features().get("tournament")).as("cyd alone, dee on a roster")
                .isEqualTo(new StatsRepository.Used(2, 0L, 1L, null));
        StatsRepository.FeatureDay quiet = stats.byFeature(TODAY, 31).get(0);
        assertThat(quiet.features().get("bought")).as("today: nobody, and nothing known yet")
                .isEqualTo(new StatsRepository.Used(0, null, null, null));
        assertThat(stats.byFeature(TODAY, 60)).hasSize(60);
        assertThatThrownBy(() -> stats.byFeature(TODAY, 61)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stats.byFeature(TODAY, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("days before the cutoff are deleted, in batches; the first day a player played is kept")
    void retention() throws Exception {
        long ada = player("ada", "2026-06-01", "2026-06-01", "2026-06-02", "2026-09-30");
        assertThat(stats.purgeActivityBefore(LocalDate.parse("2026-07-01"), 1)).as("batches of one").isEqualTo(2);
        assertThat(stats.daily(TODAY, 2)).extracting(Day::active).containsExactly(0L, 1L);
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT first_played_on FROM player WHERE id = ?")) {
            ps.setLong(1, ada);
            try (var rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("2026-06-01");
            }
        }
        assertThat(StatsRepository.KEPT_DAYS).isEqualTo(90);
    }
}
