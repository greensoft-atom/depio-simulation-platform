package com.backend.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.backend.handoff.LobbyPush;
import com.backend.persistence.AccountRepository;
import com.backend.persistence.Database;
import com.backend.persistence.EconomyRepository;
import com.backend.persistence.InboxRepository;
import com.backend.persistence.SeasonRepository;
import com.jredis.client.JRedisClient;
import com.jredis.embedded.JRedisEmbedded;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The season job (docs 04 §7, 05 §9, D-63): an ended season's places, gems and reset, once whoever runs it. */
class SeasonKeeperTest {

    private static final String URL = System.getenv().getOrDefault("JDBC_URL",
            "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "backend");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD",
            "backend-dev-password");
    private static final Instant END = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant AFTER = END.plusSeconds(30);

    private static Database db;
    private static JRedisEmbedded server;
    private static JRedisClient store;
    private static AccountRepository accounts;
    private static EconomyRepository economy;
    private static InboxRepository inbox;
    private static SeasonRepository seasons;

    @BeforeAll
    static void setUp() {
        db = new Database(URL, USER, PASSWORD, 4);
        server = JRedisEmbedded.start();
        store = server.newClient();
        accounts = new AccountRepository(db.dataSource());
        economy = new EconomyRepository(db.dataSource());
        inbox = new InboxRepository(db.dataSource());
        seasons = new SeasonRepository(db.dataSource());
    }

    @AfterAll
    static void tearDown() {
        server.close();
        db.close();
    }

    @BeforeEach
    void fresh() throws SQLException {
        db.resetForTests();
        store.sync().send("FLUSHALL");
        sql("UPDATE season SET starts_at = '2026-09-01 00:00:00', ends_at = '2026-11-01 00:00:00' WHERE id = 1");
    }

    private static void sql(String statement, Object... args) throws SQLException {
        try (Connection c = db.dataSource().getConnection(); PreparedStatement ps = c.prepareStatement(statement)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private static long count(String query, Object... args) throws SQLException {
        try (Connection c = db.dataSource().getConnection(); PreparedStatement ps = c.prepareStatement(query)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long player(String name, int duel, int duels) throws SQLException {
        long id = accounts.register(name, name, "$argon2id$fake".getBytes(StandardCharsets.UTF_8));
        sql("UPDATE player SET rating_duel = ?, rated_duels = ? WHERE id = ?", duel, duels, id);
        return id;
    }

    private static long gems(long player) throws SQLException {
        return economy.wallet(player).gems();
    }

    /** Small pages and batches, so a run goes through several of each. */
    private static SeasonKeeper keeper(String workerId) {
        return new SeasonKeeper(seasons, economy, inbox, new LobbyPush(store), store, workerId, 2, 1);
    }

    @Test
    @DisplayName("an ended season closed: its places paid in gems, each with an inbox item and a push, then every rating reset; a second run does nothing")
    void anEndedSeasonIsClosed() throws Exception {
        long ada = player("ada", 1_600, 12);
        long bob = player("bob", 1_500, 10);
        long cyd = player("cyd", 1_400, 9);                    // not listed: no place, but reset
        long dee = player("dee", 1_300, 10);
        JRedisClient gateway = server.newClient();
        LinkedBlockingQueue<LobbyPush.Delivery> heard = new LinkedBlockingQueue<>();
        gateway.pubSub().subscribe("push:gw-1", (ch, msg) -> heard.add(LobbyPush.Delivery.parse(msg)))
                .get(5, TimeUnit.SECONDS);
        for (long p : new long[] {ada, bob, dee}) {
            store.sync().set("conn:" + p, "gw-1#x-" + p);
        }
        try {
            assertThat(keeper("w1").runIfDue(AFTER)).as("one season finished").isEqualTo(1);

            assertThat(seasons.current().id()).isEqualTo(2);
            assertThat(List.of(gems(ada), gems(bob), gems(dee), gems(cyd))).containsExactly(100L, 60L, 40L, 0L);
            assertThat(count("SELECT COUNT(*) FROM ledger WHERE player_id = ? AND reason = ? AND currency = 1"
                    + " AND ref = 'season:1:1' AND idem_key = ?", ada, EconomyRepository.REASON_SEASON, "season:1:1:" + ada))
                    .as("through the ledger, keyed by the season, board and player").isEqualTo(1);
            assertThat(inbox.itemsOf(ada)).extracting(InboxRepository.Item::kind, InboxRepository.Item::ref)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(InboxRepository.SEASON_REWARD, 11L));
            List<Long> told = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                LobbyPush.Delivery d = heard.poll(5, TimeUnit.SECONDS);
                assertThat(d).isNotNull();
                assertThat(d.type()).isEqualTo("evt.inbox");
                told.add(d.to());
            }
            assertThat(told).containsExactlyInAnyOrder(ada, bob, dee);

            assertThat(count("SELECT rating_duel * 1000 + rated_duels FROM player WHERE id = ?", ada)).isEqualTo(1_400_000);
            assertThat(count("SELECT rating_duel * 1000 + rated_duels FROM player WHERE id = ?", cyd)).isEqualTo(1_300_000);
            assertThat(seasons.unfinished()).isEmpty();

            long rows = count("SELECT COUNT(*) FROM ledger");
            assertThat(keeper("w2").runIfDue(AFTER.plusSeconds(60))).as("nothing left").isZero();
            assertThat(count("SELECT COUNT(*) FROM ledger")).isEqualTo(rows);
            assertThat(count("SELECT rating_duel FROM player WHERE id = ?", ada)).as("nobody halved twice").isEqualTo(1_400);
            assertThat(heard.poll(300, TimeUnit.MILLISECONDS)).as("nobody told twice").isNull();
        } finally {
            gateway.close();
        }
    }

    /** A team and its members, the first its leader, rated so; and a rated team match played for it by {@code played}. */
    private static long teamThatPlayed(String name, int rating, int rated, long played, long... members) throws SQLException {
        long team;
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO team (name, member_count, rating, rated_matches)"
                     + " VALUES (?, ?, ?, ?)", java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setInt(2, members.length);
            ps.setInt(3, rating);
            ps.setInt(4, rated);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                team = keys.getLong(1);
            }
        }
        for (int i = 0; i < members.length; i++) {
            sql("INSERT INTO team_member (team_id, player_id, role) VALUES (?, ?, ?)", team, members[i], i == 0 ? 2 : 0);
        }
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO matches (match_uid, mode, kind, arena, started_at, ended_at)"
                     + " VALUES (?, 5, 1, 'arena-1', '2026-10-10 12:00:00', '2026-10-10 12:05:00')",
                     java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, String.format("01JBKEEPERTEAM%012d", team));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                long match = keys.getLong(1);
                sql("INSERT INTO match_team (match_id, side, team_id, placement, rating_delta) VALUES (?, 1, ?, 1, 0)", match, team);
                sql("INSERT INTO match_player (match_id, player_id, team, placement, kills, deaths, score, xp_gained)"
                        + " VALUES (?, ?, 1, 1, 0, 0, 0, 0)", match, played);
            }
        }
        return team;
    }

    @Test
    @DisplayName("a team's place paid to the member who played for it, with an inbox item; its rating reset; a second run does nothing (D-65)")
    void aTeamsPlaceIsPaidToWhoPlayed() throws Exception {
        long ada = player("ada", 1_200, 0);
        long amy = player("amy", 1_200, 0);
        long alpha = teamThatPlayed("Alpha", 1_600, 12, ada, ada, amy);
        assertThat(keeper("w1").runIfDue(AFTER)).isEqualTo(1);
        assertThat(List.of(gems(ada), gems(amy))).as("the place's 100 to who played, none to who did not").containsExactly(100L, 0L);
        assertThat(count("SELECT COUNT(*) FROM ledger WHERE player_id = ? AND reason = ? AND idem_key = ?",
                ada, EconomyRepository.REASON_SEASON, "season:1:5:" + ada)).isEqualTo(1);
        assertThat(inbox.itemsOf(ada)).extracting(InboxRepository.Item::kind, InboxRepository.Item::ref)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(InboxRepository.SEASON_REWARD, 15L));
        assertThat(count("SELECT rating * 1000 + rated_matches FROM team WHERE id = ?", alpha)).isEqualTo(1_400_000);
        assertThat(seasons.unfinished()).isEmpty();
        long rows = count("SELECT COUNT(*) FROM ledger");
        assertThat(keeper("w2").runIfDue(AFTER.plusSeconds(60))).isZero();
        assertThat(count("SELECT COUNT(*) FROM ledger")).isEqualTo(rows);
        assertThat(count("SELECT rating FROM team WHERE id = ?", alpha)).as("not halved twice").isEqualTo(1_400);
    }

    @Test
    @DisplayName("not before the end; and while another worker holds the job, this one leaves it alone")
    void notBeforeTheEndAndOneAtATime() throws Exception {
        long ada = player("ada", 1_600, 12);
        assertThat(keeper("w1").runIfDue(END.minusSeconds(1))).as("still running").isZero();
        assertThat(seasons.current().id()).isEqualTo(1);

        store.sync().send("SET", SeasonKeeper.LOCK_KEY, "w2");
        assertThat(keeper("w1").runIfDue(AFTER)).isEqualTo(SeasonKeeper.NOT_RUN);
        assertThat(seasons.current().id()).isEqualTo(1);
        assertThat(store.sync().get(SeasonKeeper.LOCK_KEY)).as("another's lock, kept").isEqualTo("w2");

        store.sync().del(SeasonKeeper.LOCK_KEY);
        assertThat(keeper("w1").runIfDue(AFTER)).isEqualTo(1);
        assertThat(store.sync().get(SeasonKeeper.LOCK_KEY)).as("its own, handed back").isNull();
        assertThat(gems(ada)).isEqualTo(100);
    }

    @Test
    @DisplayName("the lock is extended while it is held, and a run that finds it another's stops (D-47)")
    void theLockIsHeldWhileTheCloseRuns() throws Exception {
        SeasonKeeper w1 = keeper("w1");
        store.sync().send("SET", SeasonKeeper.LOCK_KEY, "w1", "EX", "5");
        assertThat(w1.holdLock()).isTrue();
        assertThat(store.sync().ttl(SeasonKeeper.LOCK_KEY)).as("extended to the lock's whole time again")
                .isGreaterThan(SeasonKeeper.LOCK_SECONDS - 5);
        store.sync().send("SET", SeasonKeeper.LOCK_KEY, "w2", "EX", "5");
        assertThat(w1.holdLock()).as("another's now: this run stops").isFalse();
        assertThat(store.sync().get(SeasonKeeper.LOCK_KEY)).isEqualTo("w2");
        assertThat(store.sync().ttl(SeasonKeeper.LOCK_KEY)).as("and its time is not touched").isLessThanOrEqualTo(5);
    }

    @Test
    @DisplayName("a close whose lock another worker took stops at the next page, and leaves that worker's lock alone (D-47)")
    void aCloseStopsWhenItsLockIsTaken() throws Exception {
        long ada = player("ada", 1_600, 12);
        long bob = player("bob", 1_500, 10);
        // Another worker takes the lock as this one pays its first place: one place a page, so the page ends there.
        EconomyRepository paying = new EconomyRepository(onFirstConnection(db.dataSource(),
                () -> store.sync().send("SET", SeasonKeeper.LOCK_KEY, "w2", "EX", "60")));
        SeasonKeeper w1 = new SeasonKeeper(seasons, paying, inbox, new LobbyPush(store), store, "w1", 1, 1);
        assertThat(w1.runIfDue(AFTER)).as("stopped, not finished").isEqualTo(SeasonKeeper.NOT_RUN);
        assertThat(List.of(gems(ada), gems(bob))).as("the first page paid, the second left").containsExactly(100L, 0L);
        assertThat(store.sync().get(SeasonKeeper.LOCK_KEY)).as("the other's lock left alone").isEqualTo("w2");
        store.sync().del(SeasonKeeper.LOCK_KEY);
        assertThat(keeper("w2").runIfDue(AFTER)).isEqualTo(1);
        assertThat(List.of(gems(ada), gems(bob))).as("finished by the next, nobody paid twice").containsExactly(100L, 60L);
    }

    /** {@code real}, running {@code first} once, as the first connection is taken. */
    private static javax.sql.DataSource onFirstConnection(javax.sql.DataSource real, Runnable first) {
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        return (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(javax.sql.DataSource.class.getClassLoader(),
                new Class<?>[] {javax.sql.DataSource.class}, (proxy, m, args) -> {
                    if (m.getName().equals("getConnection") && done.compareAndSet(false, true)) {
                        first.run();
                    }
                    try {
                        return m.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @Test
    @DisplayName("a run that stopped part-way is finished by the next: nobody paid twice, nobody halved twice")
    void aRunCutShortIsFinished() throws Exception {
        long ada = player("ada", 1_600, 12);
        long bob = player("bob", 1_500, 10);
        assertThat(seasons.place(1, Instant.parse("2027-01-01T00:00:00Z"), AFTER)).isTrue();
        // Ada was paid, and the first batch reset, before the worker died.
        economy.credit(ada, 100, EconomyRepository.REASON_SEASON, "season:1:1", "season:1:1:" + ada,
                EconomyRepository.CURRENCY_GEMS);
        assertThat(seasons.resetBatch(1, 1, AFTER)).isTrue();

        assertThat(keeper("w2").runIfDue(AFTER.plusSeconds(60))).isEqualTo(1);
        assertThat(List.of(gems(ada), gems(bob))).containsExactly(100L, 60L);
        assertThat(count("SELECT rating_duel FROM player WHERE id = ?", ada)).isEqualTo(1_400);
        assertThat(count("SELECT rating_duel FROM player WHERE id = ?", bob)).isEqualTo(1_350);
        assertThat(seasons.unfinished()).isEmpty();
    }
}
