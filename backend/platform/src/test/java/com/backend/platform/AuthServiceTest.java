package com.backend.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import com.backend.handoff.SessionStore;
import com.backend.persistence.AccountRepository;
import com.backend.persistence.Database;

import com.jredis.client.JRedisClient;
import com.jredis.embedded.JRedisEmbedded;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Register and login against a real MySQL and a real j-redis.
 *
 * Both stores are the mechanism here, not a detail behind it: the unique index decides
 * contested usernames and the session TTL decides how long a token lives.
 */
class AuthServiceTest {

    private static final String URL = System.getenv().getOrDefault("JDBC_URL",
            "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "backend");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD",
            "backend-dev-password");

    private static Database db;
    private static JRedisEmbedded store;
    private static JRedisClient client;
    private static AccountRepository accounts;
    private static SessionStore sessions;
    private static AuthService auth;

    @BeforeAll
    static void setUp() {
        db = new Database(URL, USER, PASSWORD, 8);
        db.resetForTests();
        store = JRedisEmbedded.start();
        client = store.newClient();
        accounts = new AccountRepository(db.dataSource());
        sessions = new SessionStore(client);
        auth = new AuthService(accounts, new PasswordHasher(), sessions);
    }

    @AfterAll
    static void tearDown() {
        if (store != null) {
            store.close();
        }
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void fresh() {
        db.resetForTests();
        client.sync().send("FLUSHALL");
    }

    @Test
    @DisplayName("register then log in, and the session names the player")
    void registerThenLogin() throws Exception {
        AuthService.RegisterResult registered =
                auth.register("ada", "Ada", "hunter2-hunter2".toCharArray());
        assertThat(registered.outcome()).isEqualTo(AuthService.Register.OK);

        AuthService.LoginResult login = auth.login("ada", "hunter2-hunter2".toCharArray());

        assertThat(login.outcome()).isEqualTo(AuthService.Login.OK);
        assertThat(login.playerId()).isEqualTo(registered.playerId());
        assertThat(login.token()).isNotBlank();
        assertThat(auth.playerIdOf(login.token())).isEqualTo(registered.playerId());
    }

    @Test
    @DisplayName("a wrong password and an unknown user are the same answer")
    void wrongCredentialsAreIndistinguishable() throws Exception {
        auth.register("ada", "Ada", "hunter2-hunter2".toCharArray());

        AuthService.LoginResult wrongPassword = auth.login("ada", "not-the-password".toCharArray());
        AuthService.LoginResult noSuchUser = auth.login("nobody", "not-the-password".toCharArray());

        // Telling these apart hands out a list of which usernames exist.
        assertThat(wrongPassword.outcome()).isEqualTo(AuthService.Login.INVALID_CREDENTIALS);
        assertThat(noSuchUser.outcome()).isEqualTo(AuthService.Login.INVALID_CREDENTIALS);
        assertThat(wrongPassword.token()).isNull();
        assertThat(noSuchUser.token()).isNull();
    }

    @Test
    @DisplayName("an unknown username still costs a hash, so timing does not reveal it")
    void unknownUserIsNotFasterThanAWrongPassword() throws Exception {
        auth.register("ada", "Ada", "hunter2-hunter2".toCharArray());
        auth.login("ada", "warm-up-the-jit".toCharArray());

        long wrongPassword = time(() -> auth.login("ada", "not-the-password".toCharArray()));
        long unknownUser = time(() -> auth.login("nobody-at-all", "not-the-password".toCharArray()));

        // Without the decoy hash the unknown-user path returns in microseconds while the
        // real one takes tens of milliseconds, which is a username oracle for anyone with a
        // clock. The bound is loose because this machine is shared; the failure it catches
        // is the decoy being removed entirely, which is a 1000x difference, not a 2x one.
        System.out.printf("login: wrong password %d ms, unknown user %d ms%n",
                wrongPassword, unknownUser);
        assertThat(unknownUser).as("unknown user must not short-circuit")
                .isGreaterThan(wrongPassword / 4);
    }

    @Test
    @DisplayName("registration refuses a taken name and obviously bad input")
    void registrationValidation() throws Exception {
        assertThat(auth.register("ada", "Ada", "hunter2-hunter2".toCharArray()).outcome())
                .isEqualTo(AuthService.Register.OK);

        assertThat(auth.register("ada", "Other", "hunter2-hunter2".toCharArray()).outcome())
                .isEqualTo(AuthService.Register.USERNAME_TAKEN);
        assertThat(auth.register("ab", "Short", "hunter2-hunter2".toCharArray()).outcome())
                .isEqualTo(AuthService.Register.INVALID_USERNAME);
        assertThat(auth.register("has space", "Spaced", "hunter2-hunter2".toCharArray()).outcome())
                .isEqualTo(AuthService.Register.INVALID_USERNAME);
        assertThat(auth.register("bob", "Bob", "short".toCharArray()).outcome())
                .isEqualTo(AuthService.Register.INVALID_PASSWORD);
    }

    @Test
    @DisplayName("logging out revokes the session immediately")
    void logoutRevokes() throws Exception {
        auth.register("ada", "Ada", "hunter2-hunter2".toCharArray());
        String token = auth.login("ada", "hunter2-hunter2".toCharArray()).token();
        assertThat(auth.playerIdOf(token)).isPositive();

        assertThat(auth.logout(token)).isTrue();

        assertThat(auth.playerIdOf(token)).as("a revoked token is nobody").isEqualTo(-1);
        assertThat(auth.logout(token)).as("revoking twice is not an error").isFalse();
    }

    @Test
    @DisplayName("a session expires on its own")
    void sessionHasExpiry() throws Exception {
        auth.register("ada", "Ada", "hunter2-hunter2".toCharArray());
        String token = auth.login("ada", "hunter2-hunter2".toCharArray()).token();

        long ttl = client.sync().ttl("sess:" + token);
        assertThat(ttl).isBetween((long) (SessionStore.TTL_SECONDS * 0.89),
                (long) (SessionStore.TTL_SECONDS * 1.11));
        assertThat(client.sync().hget("sess:" + token, "playerId")).isNotNull();
    }

    @Test
    @DisplayName("session lifetimes are spread out, not identical")
    void sessionTtlIsJittered() throws Exception {
        auth.register("ada", "Ada", "hunter2-hunter2".toCharArray());

        java.util.Set<Long> ttls = new java.util.HashSet<>();
        for (int i = 0; i < 12; i++) {
            ttls.add(client.sync().ttl(
                    "sess:" + auth.login("ada", "hunter2-hunter2".toCharArray()).token()));
        }

        // Identical TTLs mean every session issued in one window expires in one window, and
        // the path they all return to is the most expensive one in the system.
        assertThat(ttls).as("twelve sessions should not share one expiry second")
                .hasSizeGreaterThan(1);
        assertThat(ttls).allSatisfy(ttl -> assertThat(ttl)
                .isBetween((long) (SessionStore.TTL_SECONDS * 0.89),
                        (long) (SessionStore.TTL_SECONDS * 1.11)));
    }

    @Test
    @DisplayName("a banned account cannot log in, but only after the password is checked")
    void bannedAccountIsRefused() throws Exception {
        long id = auth.register("ada", "Ada", "hunter2-hunter2".toCharArray()).playerId();
        setStatus(id, 1, null);

        assertThat(auth.login("ada", "hunter2-hunter2".toCharArray()).outcome())
                .isEqualTo(AuthService.Login.BANNED);
        // The ban is not an oracle either: a wrong password on a banned account still reads
        // as invalid credentials, so it cannot be used to probe who is banned.
        assertThat(auth.login("ada", "wrong-password-x".toCharArray()).outcome())
                .isEqualTo(AuthService.Login.INVALID_CREDENTIALS);
    }

    @Test
    @DisplayName("a ban that has expired no longer blocks a login")
    void expiredBanIsNotABan() throws Exception {
        long id = auth.register("ada", "Ada", "hunter2-hunter2".toCharArray()).playerId();
        setStatus(id, 1, System.currentTimeMillis() - 60_000);

        assertThat(auth.login("ada", "hunter2-hunter2".toCharArray()).outcome())
                .isEqualTo(AuthService.Login.OK);
    }

    @Test
    @DisplayName("a username spelled with an accent is not the account spelled without one")
    void accentedUsernameIsNotTheAccount() throws Exception {
        auth.register("ada", "Ada", "hunter2-hunter2".toCharArray());
        // The lookup compares accent-insensitively, so this logged in as ada.
        assertThat(auth.login("\u00e1da", "hunter2-hunter2".toCharArray()).outcome())
                .isEqualTo(AuthService.Login.INVALID_CREDENTIALS);
        assertThat(auth.login("ADA", "hunter2-hunter2".toCharArray()).outcome())
                .as("case never mattered and still does not").isEqualTo(AuthService.Login.OK);
    }

    @Test
    @DisplayName("a permanent ban, or a status this code does not know, blocks whatever the date says")
    void permanentBanAndUnknownStatusIgnoreTheDate() throws Exception {
        long id = auth.register("ada", "Ada", "hunter2-hunter2".toCharArray()).playerId();
        long past = System.currentTimeMillis() - 60_000;

        // 2 is a ban, and a ban does not lapse: a date left over from an earlier suspension
        // let this account straight back in.
        setStatus(id, 2, past);
        assertThat(auth.login("ada", "hunter2-hunter2".toCharArray()).outcome())
                .isEqualTo(AuthService.Login.BANNED);

        // A value added later without teaching the login path about it reads as a ban.
        setStatus(id, 7, past);
        assertThat(auth.login("ada", "hunter2-hunter2".toCharArray()).outcome())
                .isEqualTo(AuthService.Login.BANNED);

        setStatus(id, 0, past);
        assertThat(auth.login("ada", "hunter2-hunter2".toCharArray()).outcome())
                .as("active, whatever banned_until holds").isEqualTo(AuthService.Login.OK);
    }

    private static void setStatus(long id, int status, Long untilMillis) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE account SET status = ?, banned_until = ? WHERE id = ?")) {
            ps.setInt(1, status);
            if (untilMillis == null) {
                ps.setNull(2, java.sql.Types.TIMESTAMP);
            } else {
                ps.setTimestamp(2, new java.sql.Timestamp(untilMillis));
            }
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    private interface Action {
        void run() throws Exception;
    }

    private static long time(Action action) throws Exception {
        long start = System.nanoTime();
        action.run();
        return (System.nanoTime() - start) / 1_000_000;
    }

    // ---- rehashing ---------------------------------------------------------------------

    /** What an account hashed under an older, cheaper policy has stored. */
    private static byte[] registerAtOldCost(String name) throws Exception {
        AuthService before = new AuthService(accounts, new PasswordHasher(8 * 1024, 1, 1), sessions);
        assertThat(before.register(name, name, "hunter2-hunter2".toCharArray()).outcome())
                .isEqualTo(AuthService.Register.OK);
        byte[] stored = accounts.findForLogin(name).passwordHash();
        assertThat(new String(stored, java.nio.charset.StandardCharsets.UTF_8)).contains("m=8192,t=1,p=1");
        return stored;
    }

    @Test
    @DisplayName("a password hashed at an older cost is rehashed at the current one when its owner logs in")
    void olderCostIsRehashedOnLogin() throws Exception {
        registerAtOldCost("ada");

        assertThat(auth.login("ada", "hunter2-hunter2".toCharArray()).outcome())
                .isEqualTo(AuthService.Login.OK);

        // Until this, raising the cost protected new passwords only: an old hash verified
        // under its own parameters for ever.
        byte[] now = accounts.findForLogin("ada").passwordHash();
        assertThat(new String(now, java.nio.charset.StandardCharsets.UTF_8)).contains(
                "m=" + PasswordHasher.MEMORY_KIB + ",t=" + PasswordHasher.ITERATIONS
                        + ",p=" + PasswordHasher.PARALLELISM);
        assertThat(auth.login("ada", "hunter2-hunter2".toCharArray()).outcome())
                .as("and the new hash is the same password").isEqualTo(AuthService.Login.OK);
        assertThat(accounts.findForLogin("ada").passwordHash())
                .as("rewritten once, not at every login").isEqualTo(now);
    }

    @Test
    @DisplayName("a wrong password never rewrites a hash, however old")
    void wrongPasswordNeverRehashes() throws Exception {
        byte[] old = registerAtOldCost("ada");

        assertThat(auth.login("ada", "not-the-password".toCharArray()).outcome())
                .isEqualTo(AuthService.Login.INVALID_CREDENTIALS);

        assertThat(accounts.findForLogin("ada").passwordHash()).isEqualTo(old);
    }

    @Test
    @DisplayName("a hash is replaced only if it is still the one that was verified")
    void rehashDoesNotOverwriteAChange() throws Exception {
        byte[] old = registerAtOldCost("ada");
        long id = accounts.findForLogin("ada").playerId();
        byte[] changedMeanwhile = new PasswordHasher().hash("a-new-password".toCharArray());
        assertThat(accounts.replacePasswordHash(id, old, changedMeanwhile)).isTrue();

        // The login that verified the old one arrives late: it must not put an old password back.
        byte[] late = new PasswordHasher().hash("hunter2-hunter2".toCharArray());
        assertThat(accounts.replacePasswordHash(id, old, late)).isFalse();
        assertThat(accounts.findForLogin("ada").passwordHash()).isEqualTo(changedMeanwhile);
    }

    @Test
    @org.junit.jupiter.api.Timeout(60)
    @DisplayName("an upgrade refuses an account that is no guest, or a name taken, before it hashes the password (S-18)")
    void anUpgradeChecksBeforeHashing() throws Exception {
        PasswordHasher slow = new PasswordHasher(PasswordHasher.MEMORY_KIB, 60, 1);
        long started = System.nanoTime();
        slow.hash("hunter2-hunter2".toCharArray());
        long hashNanos = System.nanoTime() - started;
        AuthService slowly = new AuthService(accounts, slow, sessions);

        auth.register("up-taken", "Taken", "hunter2-hunter2".toCharArray());
        String registered = auth.login("up-taken", "hunter2-hunter2".toCharArray()).token();
        AuthService.GuestResult guest = auth.createGuest();
        String guestToken = auth.loginGuest(guest.guestKey()).token();

        started = System.nanoTime();
        assertThat(slowly.upgrade(registered, "up-new", null, "hunter2-hunter2".toCharArray()).outcome())
                .isEqualTo(AuthService.Upgrade.NOT_A_GUEST);
        assertThat(slowly.upgrade(guestToken, "up-taken", null, "hunter2-hunter2".toCharArray()).outcome())
                .isEqualTo(AuthService.Upgrade.USERNAME_TAKEN);
        assertThat(System.nanoTime() - started).as("two refusals, no hash: a hash takes %d ms", hashNanos / 1_000_000)
                .isLessThan(hashNanos / 2);
        assertThat(auth.upgrade(guestToken, "up-guest", null, "hunter2-hunter2".toCharArray()).outcome())
                .as("a guest, a free name: upgraded").isEqualTo(AuthService.Upgrade.OK);
    }
}
