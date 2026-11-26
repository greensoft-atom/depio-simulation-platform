package com.backend.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.backend.handoff.SessionStore;
import com.backend.persistence.AccountRepository;
import com.backend.persistence.Database;
import com.backend.persistence.DatabaseSettings;
import com.jredis.client.JRedisClient;
import com.jredis.embedded.JRedisEmbedded;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Logging in by the account service's access token, and guests, against a real MySQL and a real j-redis (D-117):
 * the subject's unique index decides who makes a player at a first login, and the session's TTL how long a token lives.
 */
class AuthServiceTest {

    private static final String URL = System.getenv().getOrDefault("JDBC_URL", DatabaseSettings.TEST_URL);
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
        auth = new AuthService(accounts, sessions, TestTokens.tokens());
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
    @DisplayName("an access token makes its subject's player at the first login, named by its username, and a session names it")
    void aFirstLoginMakesThePlayer() throws Exception {
        AuthService.LoginResult first = auth.exchange(TestTokens.access("sub-ada", "Ada"));
        assertThat(first.outcome()).isEqualTo(AuthService.Login.OK);
        assertThat(first.newPlayer()).isTrue();
        assertThat(auth.playerIdOf(first.token())).isEqualTo(first.playerId());
        assertThat(first.ttlSeconds()).isPositive();
        assertThat(accounts.findProfile(first.playerId()).displayName()).isEqualTo("Ada");

        AuthService.LoginResult again = auth.exchange(TestTokens.access("sub-ada", "renamed-there"));
        assertThat(again.playerId()).as("the same player").isEqualTo(first.playerId());
        assertThat(again.newPlayer()).isFalse();
        assertThat(again.token()).isNotEqualTo(first.token());
        assertThat(accounts.findProfile(first.playerId()).displayName()).as("named once, renamed here alone").isEqualTo("Ada");
    }

    @Test
    @DisplayName("a username the display name's rules refuse, or none, names the player Player and four digits")
    void aRefusedName() throws Exception {
        long admin = auth.exchange(TestTokens.access("sub-admin", "admin")).playerId();
        assertThat(accounts.findProfile(admin).displayName()).matches("Player[0-9]{4}");
        long none = auth.exchange(TestTokens.access("sub-none", null)).playerId();
        assertThat(accounts.findProfile(none).displayName()).matches("Player[0-9]{4}");
    }

    @Test
    @DisplayName("a token not taken makes no player and opens no session")
    void aTokenNotTaken() throws Exception {
        assertThat(auth.exchange("not-a-token").outcome()).isEqualTo(AuthService.Login.INVALID_TOKEN);
        assertThat(auth.exchange(null).outcome()).isEqualTo(AuthService.Login.INVALID_TOKEN);
        assertThat(players()).isZero();
    }

    @Test
    @DisplayName("an erased subject's token, exchanged in the minutes it is still good, makes no new player")
    void anErasedSubject() throws Exception {
        long ada = auth.exchange(TestTokens.access("sub-ada", "ada")).playerId();
        accounts.erase(ada);
        assertThat(auth.exchange(TestTokens.access("sub-ada", "ada")).outcome()).isEqualTo(AuthService.Login.INVALID_TOKEN);
        accounts.recordErasedSubject("sub-never");
        assertThat(auth.exchange(TestTokens.access("sub-never", "never")).outcome()).isEqualTo(AuthService.Login.INVALID_TOKEN);
        assertThat(players()).as("the erased one alone").isEqualTo(1);
    }

    @Test
    @DisplayName("two first logins of one account at once: one player, both logged in")
    void twoFirstLoginsAtOnce() throws Exception {
        int threads = 8;
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<AuthService.LoginResult> results = new CopyOnWriteArrayList<>();
        for (int i = 0; i < threads; i++) {
            String token = TestTokens.access("sub-contested", "contested");
            new Thread(() -> {
                try {
                    go.await();
                    results.add(auth.exchange(token));
                } catch (Exception e) {
                    results.add(null);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        go.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(results).hasSize(threads).allSatisfy(r -> assertThat(r.outcome()).isEqualTo(AuthService.Login.OK));
        assertThat(results.stream().map(AuthService.LoginResult::playerId).distinct()).hasSize(1);
        assertThat(results.stream().filter(AuthService.LoginResult::newPlayer)).as("made once").hasSize(1);
        assertThat(players()).isEqualTo(1);
    }

    @Test
    @DisplayName("logging out revokes the session immediately")
    void logoutRevokes() throws Exception {
        String token = auth.exchange(TestTokens.access("sub-ada", "ada")).token();
        assertThat(auth.playerIdOf(token)).isPositive();

        assertThat(auth.logout(token)).isTrue();

        assertThat(auth.playerIdOf(token)).as("a revoked token is nobody").isEqualTo(-1);
        assertThat(auth.logout(token)).as("revoking twice is not an error").isFalse();
    }

    @Test
    @DisplayName("a session expires on its own")
    void sessionHasExpiry() throws Exception {
        String token = auth.exchange(TestTokens.access("sub-ada", "ada")).token();

        long ttl = client.sync().ttl("sess:" + token);
        assertThat(ttl).isBetween((long) (SessionStore.TTL_SECONDS * 0.89),
                (long) (SessionStore.TTL_SECONDS * 1.11));
        assertThat(client.sync().hget("sess:" + token, "playerId")).isNotNull();
    }

    @Test
    @DisplayName("session lifetimes are spread out, not identical")
    void sessionTtlIsJittered() throws Exception {
        java.util.Set<Long> ttls = new java.util.HashSet<>();
        for (int i = 0; i < 12; i++) {
            ttls.add(client.sync().ttl("sess:" + auth.exchange(TestTokens.access("sub-ada", "ada")).token()));
        }
        // Identical TTLs mean every session issued in one window expires in one window, and every
        // player comes back for a new one together.
        assertThat(ttls).as("twelve sessions should not share one expiry second").hasSizeGreaterThan(1);
        assertThat(ttls).allSatisfy(ttl -> assertThat(ttl)
                .isBetween((long) (SessionStore.TTL_SECONDS * 0.89), (long) (SessionStore.TTL_SECONDS * 1.11)));
    }

    @Test
    @DisplayName("a banned player's account signs in at the account service, and here is refused a session")
    void bannedAccountIsRefused() throws Exception {
        long id = auth.exchange(TestTokens.access("sub-ada", "ada")).playerId();
        setStatus(id, 1, null);
        AuthService.LoginResult r = auth.exchange(TestTokens.access("sub-ada", "ada"));
        assertThat(r.outcome()).isEqualTo(AuthService.Login.BANNED);
        assertThat(r.token()).isNull();
    }

    @Test
    @DisplayName("a ban that has expired no longer blocks a login")
    void expiredBanIsNotABan() throws Exception {
        long id = auth.exchange(TestTokens.access("sub-ada", "ada")).playerId();
        setStatus(id, 1, System.currentTimeMillis() - 60_000);
        assertThat(auth.exchange(TestTokens.access("sub-ada", "ada")).outcome()).isEqualTo(AuthService.Login.OK);
    }

    @Test
    @DisplayName("a permanent ban, or a status this code does not know, blocks whatever the date says")
    void permanentBanAndUnknownStatusIgnoreTheDate() throws Exception {
        long id = auth.exchange(TestTokens.access("sub-ada", "ada")).playerId();
        long past = System.currentTimeMillis() - 60_000;
        // 2 is a ban, and a ban does not lapse: a date left over from an earlier suspension let this account back in.
        setStatus(id, 2, past);
        assertThat(auth.exchange(TestTokens.access("sub-ada", "ada")).outcome()).isEqualTo(AuthService.Login.BANNED);
        // A value added later without teaching the login path about it reads as a ban.
        setStatus(id, 7, past);
        assertThat(auth.exchange(TestTokens.access("sub-ada", "ada")).outcome()).isEqualTo(AuthService.Login.BANNED);
        setStatus(id, 0, past);
        assertThat(auth.exchange(TestTokens.access("sub-ada", "ada")).outcome())
                .as("active, whatever banned_until holds").isEqualTo(AuthService.Login.OK);
    }

    @Test
    @DisplayName("a guest links an account once: the same player, its name kept, its key stopped; none other may")
    void aGuestLinks() throws Exception {
        AuthService.GuestResult guest = auth.createGuest();
        String session = auth.loginGuest(guest.guestKey()).token();
        assertThat(auth.link(session, TestTokens.access("sub-gina", "gina"))).isEqualTo(AuthService.Link.OK);
        assertThat(auth.loginGuest(guest.guestKey()).outcome()).as("its key stopped").isEqualTo(AuthService.Login.INVALID_CREDENTIALS);
        AuthService.LoginResult in = auth.exchange(TestTokens.access("sub-gina", "gina"));
        assertThat(in.playerId()).as("the account's player is the guest").isEqualTo(guest.playerId());
        assertThat(in.newPlayer()).isFalse();
        assertThat(accounts.findProfile(guest.playerId()).displayName()).isEqualTo(guest.displayName());
        assertThat(auth.link(session, TestTokens.access("sub-other", "other"))).isEqualTo(AuthService.Link.NOT_A_GUEST);

        String second = auth.loginGuest(auth.createGuest().guestKey()).token();
        assertThat(auth.link(second, TestTokens.access("sub-gina", "gina"))).isEqualTo(AuthService.Link.ACCOUNT_IN_USE);
        assertThat(auth.link(second, "not-a-token")).isEqualTo(AuthService.Link.INVALID_TOKEN);
        accounts.recordErasedSubject("sub-gone");
        assertThat(auth.link(second, TestTokens.access("sub-gone", "gone"))).as("a deleted account's late token")
                .isEqualTo(AuthService.Link.INVALID_TOKEN);
        assertThat(auth.link("no-such-session-xxxxxxxxxxxxxxxxxxxxxxxxxxx", TestTokens.access("sub-x", "x")))
                .isEqualTo(AuthService.Link.NO_SESSION);
    }

    @Test
    @DisplayName("the account service's keys not to be had: said so, for a 503, not taken for a token refused")
    void keysUnavailable() throws Exception {
        int dead;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            dead = s.getLocalPort();
        }
        AuthService blind = new AuthService(accounts, sessions, new AccountTokens(new AccountTokenSettings(TestTokens.ISSUER,
                "backend", URI.create("http://127.0.0.1:" + dead + "/.well-known/jwks.json"))));
        assertThatThrownBy(() -> blind.exchange(TestTokens.access("sub-ada", "ada")))
                .isInstanceOf(AccountTokens.KeysUnavailable.class);
        assertThat(players()).isZero();
    }

    private static long players() throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM player");
             ResultSet r = ps.executeQuery()) {
            r.next();
            return r.getLong(1);
        }
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
}
