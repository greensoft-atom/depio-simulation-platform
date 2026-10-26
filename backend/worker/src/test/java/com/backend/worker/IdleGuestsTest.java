package com.backend.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.backend.handoff.LeaderboardStore;
import com.backend.persistence.AccountRepository;
import com.backend.persistence.Database;
import com.backend.persistence.DatabaseSettings;
import com.backend.persistence.TeamRepository;
import com.jredis.client.JRedisClient;
import com.jredis.embedded.JRedisEmbedded;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Retention's last step (D-87, 04 §1): the guests nobody has played for 180 days, erased as a player's own deletion
 * erases them; against a real MySQL and an embedded store.
 */
class IdleGuestsTest {

    private static final String URL = System.getenv().getOrDefault("JDBC_URL", DatabaseSettings.TEST_URL);
    private static final String USER = System.getenv().getOrDefault("DB_USER", "backend");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD", "backend-dev-password");
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    private static final byte[] HASH = "$argon2id$fake".getBytes(StandardCharsets.UTF_8);

    private static Database db;
    private static JRedisEmbedded server;
    private static JRedisClient client;
    private static AccountRepository accounts;
    private static TeamRepository teams;
    private static LeaderboardStore boards;

    @BeforeAll
    static void setUp() {
        db = new Database(URL, USER, PASSWORD, 8);
        db.resetForTests();
        server = JRedisEmbedded.start();
        client = server.newClient();
        accounts = new AccountRepository(db.dataSource());
        teams = new TeamRepository(db.dataSource(), 30);
        boards = new LeaderboardStore(client);
    }

    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.close();
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
    @DisplayName("the idle guests erased as a player's own deletion erases them: off the boards, out of the team, which a member"
            + " takes over, the key gone; a recent guest and a registered account untouched; the next run finds none (D-87)")
    void erased() throws Exception {
        long ghost = idleGuest(1);
        long other = idleGuest(2);
        long recent = idleGuest(3);
        jdbc("UPDATE account SET last_login_at = '2026-10-20 00:00:00' WHERE id = ?", recent);
        long midway = idleGuest(4);                                                  // 120 days idle: the measure's 90, not the rule's 180
        jdbc("UPDATE account SET last_login_at = '2026-06-28 00:00:00' WHERE id = ?", midway);
        long ada = accounts.register("ada", "Ada", HASH);
        jdbc("UPDATE account SET created_at = '2025-10-01 00:00:00' WHERE id = ?", ada);
        boards.record(ghost, "Guest1", 500, NOW.minus(200, ChronoUnit.DAYS).toEpochMilli()).join();
        long team = teams.create(ghost, "Ghosts", NOW).teamId();
        assertThat(teams.invite(ghost, ada, NOW)).isEqualTo(TeamRepository.Outcome.OK);
        assertThat(teams.answer(ada, team, true, NOW)).isEqualTo(TeamRepository.Outcome.OK);
        assertThat(client.sync().zscore("lb:score:alltime", Long.toString(ghost))).as("on the board to start with").isEqualTo(500.0);

        IdleGuests idle = new IdleGuests(accounts, teams, boards, 1000);
        assertThat(idle.erase(NOW)).isEqualTo(2);
        assertThat(idle.erasedTotal()).isEqualTo(2);
        for (long id : new long[] {ghost, other}) {
            AccountRepository.Owner owner = accounts.owner(id);
            assertThat(owner.status()).as("" + id).isEqualTo(AccountRepository.Status.DELETED);
            assertThat(owner.guest()).isFalse();
            assertThat(owner.usernameKey()).isEqualTo("~deleted~" + id);
        }
        assertThat(accounts.findGuest(key(1))).as("the key logs in no more").isNull();
        assertThat(accounts.findGuest(key(3))).as("the recent guest's still does").isNotNull();
        assertThat(accounts.owner(recent).status()).isEqualTo(AccountRepository.Status.ACTIVE);
        assertThat(accounts.owner(midway).guest()).as("idle 120 days: kept").isTrue();
        assertThat(accounts.owner(ada).status()).isEqualTo(AccountRepository.Status.ACTIVE);
        assertThat(client.sync().zscore("lb:score:alltime", Long.toString(ghost))).as("off the all-time board").isNull();
        assertThat(client.sync().hget("lb:name", Long.toString(ghost))).as("and its name").isNull();
        assertThat(teams.teamOf(ada).members()).extracting(TeamRepository.Member::playerId, TeamRepository.Member::role)
                .as("the team handed to its one member").containsExactly(tuple(ada, TeamRepository.LEADER));
        assertThat(idle.erase(NOW)).as("nothing left").isZero();
        assertThat(idle.erasedTotal()).isEqualTo(2);
    }

    @Test
    @DisplayName("a batch at a time: a batch of one still erases every idle guest in one run")
    void batches() throws Exception {
        idleGuest(1);
        idleGuest(2);
        idleGuest(3);
        assertThat(new IdleGuests(accounts, teams, boards, 1).erase(NOW)).isEqualTo(3);
    }

    @Test
    @DisplayName("the store first: one that fails stops the step before anything is erased, and the guest stays for the next run")
    void storeFirst() throws Exception {
        long ghost = idleGuest(1);
        JRedisEmbedded down = JRedisEmbedded.start();
        JRedisClient lost = down.newClient();
        down.close();
        IdleGuests idle = new IdleGuests(accounts, teams, new LeaderboardStore(lost), 1000);
        assertThatThrownBy(() -> idle.erase(NOW)).isInstanceOf(RuntimeException.class);
        assertThat(idle.erasedTotal()).isZero();
        assertThat(accounts.owner(ghost).guest()).as("still a guest").isTrue();
        assertThat(accounts.findGuest(key(1))).isNotNull();
        lost.close();
    }

    private static byte[] key(int n) {
        byte[] key = new byte[32];
        key[0] = (byte) n;
        return key;
    }

    /** A guest made a year ago that never logged in. */
    private static long idleGuest(int n) throws SQLException {
        long id = accounts.createGuest("~01JCIDLE0000000000000000" + n, "Guest" + n, key(n));
        jdbc("UPDATE account SET created_at = '2025-10-01 00:00:00' WHERE id = ?", id);
        return id;
    }

    private static void jdbc(String sql, long arg) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, arg);
            ps.executeUpdate();
        }
    }
}
