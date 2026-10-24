package com.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.backend.persistence.AccountRepository.Credentials;
import com.backend.persistence.AccountRepository.Profile;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Against a real MySQL: the unique index is the thing being tested, and a fake has none. */
class AccountRepositoryTest {

    private static final String URL = System.getenv().getOrDefault("JDBC_URL", DatabaseSettings.TEST_URL);
    private static final String USER = System.getenv().getOrDefault("DB_USER", "backend");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD",
            "backend-dev-password");

    private static Database db;
    private static AccountRepository accounts;

    private static final byte[] HASH = "$argon2id$fake".getBytes(StandardCharsets.UTF_8);

    @BeforeAll
    static void setUp() {
        db = new Database(URL, USER, PASSWORD, 8);
        db.resetForTests();
        accounts = new AccountRepository(db.dataSource());
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

    @Test
    @DisplayName("a player has a stats row from the start, so a first result locks a row, not a gap two first results deadlock in (D-43)")
    void aPlayerHasItsStatsRowFromTheStart() throws Exception {
        long registered = accounts.register("ada", "Ada", HASH);
        long guest = accounts.createGuest("~guest", "Guest 0001", new byte[32]);
        try (java.sql.Connection c = db.dataSource().getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT player_id, matches, wins, kills FROM player_stat WHERE player_id IN (?, ?) ORDER BY player_id")) {
            ps.setLong(1, registered);
            ps.setLong(2, guest);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                java.util.List<Long> ids = new java.util.ArrayList<>();
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                    assertThat(rs.getLong(2) + rs.getLong(3) + rs.getLong(4)).as("empty").isZero();
                }
                assertThat(ids).containsExactly(registered, guest);
            }
        }
    }

    @Test
    @DisplayName("a player renames at once, then once in 30 days: refused until they have passed (04 §1, plan item 63)")
    void renaming() throws Exception {
        long ada = accounts.register("ada", "Ada", HASH);
        java.time.Instant t = java.time.Instant.parse("2026-10-01T12:00:00Z");
        java.time.Duration every = AccountRepository.RENAME_EVERY;
        assertThat(every).isEqualTo(java.time.Duration.ofDays(30));
        assertThat(accounts.rename(ada, "Ada Two", t)).as("the first at once").isEqualTo(AccountRepository.Renamed.OK);
        assertThat(accounts.findProfile(ada).displayName()).isEqualTo("Ada Two");
        assertThat(accounts.rename(ada, "Ada Three", t.plus(every).minusMillis(1))).isEqualTo(AccountRepository.Renamed.TOO_SOON);
        assertThat(accounts.findProfile(ada).displayName()).as("unchanged").isEqualTo("Ada Two");
        assertThat(accounts.rename(ada, "Ada Three", t.plus(every))).isEqualTo(AccountRepository.Renamed.OK);
        assertThat(accounts.findProfile(ada).displayName()).isEqualTo("Ada Three");
        assertThat(accounts.rename(ada + 1_000, "Nobody", t)).isEqualTo(AccountRepository.Renamed.NO_SUCH_PLAYER);
    }

    @Test
    @DisplayName("registering creates the account and its player row together")
    void registerCreatesBothRows() throws Exception {
        long id = accounts.register("ada", "Ada", HASH);

        assertThat(id).isPositive();
        Profile profile = accounts.findProfile(id);
        assertThat(profile).isNotNull();
        assertThat(profile.displayName()).isEqualTo("Ada");
        assertThat(profile.publicCode()).hasSize(12);

        assertThat(accounts.rating(id, MatchResultRepository.MODE_DUEL)).as("every player starts at 1 200").isEqualTo(1_200);
        assertThat(accounts.rating(id, MatchResultRepository.MODE_TVT)).isEqualTo(1_200);
        assertThat(accounts.rating(id, MatchResultRepository.MODE_RFFA)).isEqualTo(1_200);
        assertThat(accounts.rating(id + 1_000, MatchResultRepository.MODE_DUEL)).isEqualTo(-1);
        jdbc("UPDATE player SET rating_tvt = 1300 WHERE id = ?", id);
        assertThat(accounts.rating(id, MatchResultRepository.MODE_TVT)).as("each mode its own").isEqualTo(1_300);
        assertThat(accounts.rating(id, MatchResultRepository.MODE_DUEL)).isEqualTo(1_200);
        jdbc("UPDATE player SET rating_rffa = 1250 WHERE id = ?", id);
        assertThat(accounts.rating(id, MatchResultRepository.MODE_RFFA)).isEqualTo(1_250);

        // An account without a player row is unusable, and every later read assumes the pair.
        assertThat(count("SELECT COUNT(*) FROM account WHERE id = ?", id)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM player WHERE id = ?", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("a taken username is refused, including in a different case")
    void duplicateUsernameIsRefused() throws Exception {
        assertThat(accounts.register("ada", "Ada", HASH)).isPositive();

        assertThat(accounts.register("ada", "Ada Two", HASH)).isEqualTo(-1);
        assertThat(accounts.register("ADA", "Ada Three", HASH))
                .as("username_key is LOWER(username), so case cannot be used to duplicate")
                .isEqualTo(-1);

        assertThat(count("SELECT COUNT(*) FROM account WHERE username_key = 'ada'", null))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a refused registration leaves no half-made account behind")
    void refusedRegistrationRollsBack() throws Exception {
        long first = accounts.register("ada", "Ada", HASH);
        accounts.register("ada", "Ada Two", HASH);

        // The account insert fails, so the player insert never runs; but if registration
        // were two transactions, a second player row could exist with no account.
        assertThat(count("SELECT COUNT(*) FROM player", null)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM account", null)).isEqualTo(1);
        assertThat(accounts.findProfile(first)).isNotNull();
    }

    @Test
    @DisplayName("two simultaneous registrations of one username: exactly one succeeds")
    void concurrentRegistrationsOfOneName() throws Exception {
        int threads = 8;
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    go.await();
                    if (accounts.register("contested", "Contested", HASH) > 0) {
                        created.incrementAndGet();
                    } else {
                        refused.incrementAndGet();
                    }
                } catch (Exception e) {
                    failed.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        go.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(failed.get()).as("a contested name is an answer, not an error").isZero();
        assertThat(created.get()).as("the unique index decides, not a prior SELECT").isEqualTo(1);
        assertThat(refused.get()).isEqualTo(threads - 1);
        assertThat(count("SELECT COUNT(*) FROM account", null)).isEqualTo(1);
    }

    @Test
    @DisplayName("login lookup returns the stored hash and status, and is case-insensitive")
    void findForLogin() throws Exception {
        long id = accounts.register("ada", "Ada", HASH);

        Credentials c = accounts.findForLogin("AdA");
        assertThat(c).isNotNull();
        assertThat(c.playerId()).isEqualTo(id);
        assertThat(c.passwordHash()).isEqualTo(HASH);
        assertThat(c.status()).isEqualTo(AccountRepository.Status.ACTIVE);
        assertThat(c.bannedUntilMillis()).isNull();

        assertThat(accounts.findForLogin("nobody")).isNull();
    }

    @Test
    @DisplayName("the login timestamp is recorded")
    void touchLogin() throws Exception {
        long id = accounts.register("ada", "Ada", HASH);
        assertThat(scalarIsNull("SELECT last_login_at FROM account WHERE id = ?", id)).isTrue();

        accounts.touchLogin(id);

        assertThat(scalarIsNull("SELECT last_login_at FROM account WHERE id = ?", id)).isFalse();
    }

    // ---- helpers ---------------------------------------------------------------------

    private static long count(String sql, Long arg) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            if (arg != null) {
                ps.setLong(1, arg);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    private static void jdbc(String sql, long arg) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, arg);
            ps.executeUpdate();
        }
    }

    private static boolean scalarIsNull(String sql, long arg) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                rs.getObject(1);
                return rs.wasNull();
            }
        }
    }

    @Test
    @DisplayName("a guest: made under a name nobody can choose, found by its key's hash, upgraded once, keeping its player (D-46)")
    void guests() throws Exception {
        byte[] key = new byte[32];
        key[0] = 7;
        long id = accounts.createGuest("~01JCGUEST0000000000000001", "Guest1234", key);
        assertThat(accounts.findProfile(id).displayName()).isEqualTo("Guest1234");
        AccountRepository.Credentials found = accounts.findGuest(key);
        assertThat(found.playerId()).isEqualTo(id);
        assertThat(found.status()).isEqualTo(AccountRepository.Status.ACTIVE);
        assertThat(accounts.findGuest(new byte[32])).as("another key").isNull();
        assertThat(accounts.findForLogin("~01JCGUEST0000000000000001").passwordHash()).as("no password").isEmpty();

        accounts.register("taken", "Taken", HASH);
        assertThat(accounts.upgrade(id, "taken", HASH, null)).isEqualTo(AccountRepository.Upgraded.USERNAME_TAKEN);
        assertThat(accounts.findGuest(key)).as("still a guest").isNotNull();
        assertThat(accounts.upgrade(id, "adaline", HASH, "Adaline")).isEqualTo(AccountRepository.Upgraded.OK);
        assertThat(accounts.findGuest(key)).as("the key stops working").isNull();
        AccountRepository.Credentials upgraded = accounts.findForLogin("adaline");
        assertThat(upgraded.playerId()).as("the same player").isEqualTo(id);
        assertThat(upgraded.passwordHash()).isEqualTo(HASH);
        assertThat(accounts.findProfile(id).displayName()).isEqualTo("Adaline");
        assertThat(accounts.upgrade(id, "adaline2", HASH, null)).isEqualTo(AccountRepository.Upgraded.NOT_A_GUEST);

        long other = accounts.createGuest("~01JCGUEST0000000000000002", "Guest5678", new byte[] {1, 2, 3, 4, 5, 6, 7, 8,
                9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32});
        assertThat(accounts.upgrade(other, "bobby", HASH, null)).isEqualTo(AccountRepository.Upgraded.OK);
        assertThat(accounts.findProfile(other).displayName()).as("kept when none is given").isEqualTo("Guest5678");
    }

    @Test
    @DisplayName("an erased account keeps no name, password or last login, and no one it knew; what others' history needs stays (D-84)")
    void erasing() throws Exception {
        long ada = accounts.register("ada", "Ada", HASH);
        long bob = accounts.register("bob", "Bob", HASH);
        long cy = accounts.register("cy", "Cy", HASH);
        accounts.touchLogin(ada);
        jdbc("UPDATE player SET rated_duels = 12, rated_rffas = 11, rated_tvts = 10 WHERE id = ?", ada);
        exec("INSERT INTO friend (player_id, friend_id, since) VALUES (" + ada + ", " + bob + ", NOW(3)), ("
                + bob + ", " + ada + ", NOW(3)), (" + bob + ", " + cy + ", NOW(3)), (" + cy + ", " + bob + ", NOW(3))");
        exec("INSERT INTO friend_request (from_id, to_id, expires_at) VALUES (" + ada + ", " + cy + ", NOW(3) + INTERVAL 1 DAY), ("
                + cy + ", " + ada + ", NOW(3) + INTERVAL 1 DAY)");
        exec("INSERT INTO block (player_id, blocked_id, since) VALUES (" + bob + ", " + ada + ", NOW(3)), ("
                + ada + ", " + cy + ", NOW(3))");
        exec("INSERT INTO inbox (player_id, kind, ref, created_at) VALUES (" + ada + ", 1, " + cy + ", NOW(3)), ("
                + bob + ", 2, " + ada + ", NOW(3))");
        exec("INSERT INTO team (name, member_count) VALUES ('Reds', 1)");
        long team = count("SELECT id FROM team WHERE name = 'Reds'", null);
        exec("INSERT INTO team_invite (team_id, player_id, invited_by, expires_at) VALUES (" + team + ", " + ada + ", "
                + bob + ", NOW(3) + INTERVAL 1 DAY)");
        exec("INSERT INTO team_application (team_id, player_id, expires_at) VALUES (" + team + ", " + ada
                + ", NOW(3) + INTERVAL 1 DAY)");
        exec("INSERT INTO ledger (player_id, currency, delta, balance_after, reason, ref, idem_key) VALUES (" + ada
                + ", 0, 50, 50, 0, 'm1', 'm1:" + ada + "')");

        AccountRepository.Owner before = accounts.owner(ada);
        assertThat(before.usernameKey()).isEqualTo("ada");
        assertThat(before.passwordHash()).isEqualTo(HASH);
        assertThat(before.status()).isEqualTo(AccountRepository.Status.ACTIVE);
        assertThat(before.guest()).isFalse();
        assertThat(accounts.erase(ada)).isEqualTo(AccountRepository.Erased.ERASED);

        assertThat(accounts.findForLogin("ada")).as("no login by the old name").isNull();
        assertThat(count("SELECT COUNT(*) FROM account WHERE id = ? AND username = CONCAT('~deleted~', id)"
                + " AND status = 3 AND password_hash = '' AND guest_key_hash IS NULL AND last_login_at IS NULL"
                + " AND banned_until IS NULL", ada)).isEqualTo(1);
        assertThat(accounts.owner(ada).status()).isEqualTo(AccountRepository.Status.DELETED);
        assertThat(accounts.findProfile(ada).displayName()).isEqualTo(AccountRepository.ERASED_NAME);
        assertThat(count("SELECT rated_duels + rated_rffas + rated_tvts FROM player WHERE id = ?", ada))
                .as("off the rating boards").isZero();
        assertThat(count("SELECT COUNT(*) FROM friend WHERE ? IN (player_id, friend_id)", ada)).isZero();
        assertThat(count("SELECT COUNT(*) FROM friend WHERE player_id = ?", bob)).as("bob's other friend").isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM friend_request WHERE ? IN (from_id, to_id)", ada)).isZero();
        assertThat(count("SELECT COUNT(*) FROM block WHERE ? IN (player_id, blocked_id)", ada)).isZero();
        assertThat(count("SELECT COUNT(*) FROM inbox WHERE player_id = ?", ada)).isZero();
        assertThat(count("SELECT COUNT(*) FROM inbox WHERE player_id = ?", bob)).as("others' items lapse as they would").isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM team_invite WHERE player_id = ?", ada)).isZero();
        assertThat(count("SELECT COUNT(*) FROM team_application WHERE player_id = ?", ada)).isZero();
        assertThat(count("SELECT COUNT(*) FROM ledger WHERE player_id = ?", ada)).as("the ledger is kept").isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM player_stat WHERE player_id = ?", ada)).as("and the stats").isEqualTo(1);

        long again = accounts.register("ada", "Ada", HASH);
        assertThat(again).as("the name is free again, for a new account").isPositive().isNotEqualTo(ada);
        assertThat(accounts.erase(ada)).as("a retry").isEqualTo(AccountRepository.Erased.ALREADY);
        assertThat(accounts.erase(again + 1000)).isEqualTo(AccountRepository.Erased.NO_SUCH_PLAYER);
        assertThat(accounts.owner(again + 1000)).isNull();
    }

    @Test
    @DisplayName("an erased guest's key logs in no more (D-84)")
    void erasingAGuest() throws Exception {
        byte[] key = new byte[32];
        key[0] = 9;
        long id = accounts.createGuest("~01JCGUEST0000000000000003", "Guest4321", key);
        assertThat(accounts.owner(id).guest()).isTrue();
        assertThat(accounts.erase(id)).isEqualTo(AccountRepository.Erased.ERASED);
        assertThat(accounts.findGuest(key)).isNull();
        assertThat(accounts.owner(id).guest()).as("no key, so no guest").isFalse();
    }

    @Test
    @DisplayName("to the admin API an erased account is no account: no ban, no unban (D-84)")
    void anErasedAccountHasNoStatusToSet() throws Exception {
        long ada = accounts.register("ada", "Ada", HASH);
        accounts.erase(ada);
        AdminRepository admin = new AdminRepository(db.dataSource());
        assertThat(admin.setStatus(ada, AccountRepository.Status.ACTIVE, null, "unban", "{}", "active"))
                .isEqualTo(AdminRepository.Change.NO_SUCH_PLAYER);
        assertThat(accounts.owner(ada).status()).isEqualTo(AccountRepository.Status.DELETED);
    }
}
