package com.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;

import com.backend.persistence.InboxRepository.Item;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The inbox: what a player should learn on their return (04 §9, Q-20), against a real MySQL. */
class InboxRepositoryTest {

    private static final String URL = System.getenv().getOrDefault("JDBC_URL",
            "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "backend");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD",
            "backend-dev-password");
    private static final Instant T = Instant.parse("2026-10-01T12:00:00Z");

    private static Database db;
    private static AccountRepository accounts;
    private static InboxRepository inbox;

    @BeforeAll
    static void setUp() {
        db = new Database(URL, USER, PASSWORD, 4);
        accounts = new AccountRepository(db.dataSource());
        inbox = new InboxRepository(db.dataSource());
    }

    @AfterAll
    static void tearDown() {
        db.close();
    }

    @BeforeEach
    void fresh() {
        db.resetForTests();
    }

    private static long player(String name) throws SQLException {
        return accounts.register(name, name, "$argon2id$fake".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("an item a player, kind and reference, a repeat ignored; newest first; read up to an id")
    void itemsAndReading() throws Exception {
        long ada = player("ada");
        long bob = player("bob");
        assertThat(inbox.add(ada, InboxRepository.FRIEND_REQUEST, bob, T)).isTrue();
        assertThat(inbox.add(ada, InboxRepository.FRIEND_REQUEST, bob, T.plusSeconds(5))).as("a repeat").isFalse();
        assertThat(inbox.add(ada, InboxRepository.TEAM_INVITE, 7, T.plusSeconds(10))).isTrue();
        assertThat(inbox.add(ada, InboxRepository.TOURNAMENT_PRIZE, 3, T.plusSeconds(20))).isTrue();
        assertThat(inbox.add(bob, InboxRepository.FRIEND_ACCEPTED, ada, T)).isTrue();

        assertThat(inbox.itemsOf(ada)).extracting(Item::kind, Item::ref).as("newest first").containsExactly(
                org.assertj.core.groups.Tuple.tuple(InboxRepository.TOURNAMENT_PRIZE, 3L),
                org.assertj.core.groups.Tuple.tuple(InboxRepository.TEAM_INVITE, 7L),
                org.assertj.core.groups.Tuple.tuple(InboxRepository.FRIEND_REQUEST, bob));
        assertThat(inbox.itemsOf(ada).get(2).at()).as("the first's time, not the repeat's").isEqualTo(T);
        assertThat(inbox.itemsOf(ada)).extracting(Item::read).containsOnly(false);

        long middle = inbox.itemsOf(ada).get(1).id();
        assertThat(inbox.markRead(ada, middle, T.plusSeconds(30))).isEqualTo(2);
        assertThat(inbox.itemsOf(ada)).extracting(Item::read).containsExactly(false, true, true);
        assertThat(inbox.markRead(bob, Long.MAX_VALUE, T)).as("one's own only").isEqualTo(1);
        assertThat(inbox.itemsOf(ada).get(0).read()).isFalse();
    }

    @Test
    @DisplayName("items older than 30 days are deleted, in batches, and nothing newer")
    void retention() throws Exception {
        long ada = player("ada");
        inbox.add(ada, InboxRepository.TEAM_INVITE, 1, T);
        inbox.add(ada, InboxRepository.TEAM_INVITE, 2, T.plus(Duration.ofDays(1)));
        inbox.add(ada, InboxRepository.TEAM_INVITE, 3, T.plus(Duration.ofDays(40)));
        Instant cutoff = T.plus(Duration.ofDays(40)).minus(InboxRepository.KEPT);
        assertThat(inbox.purgeOlderThan(cutoff, 1)).as("batches of one").isEqualTo(2);
        assertThat(inbox.itemsOf(ada)).extracting(Item::ref).containsExactly(3L);
        assertThat(InboxRepository.KEPT).isEqualTo(Duration.ofDays(30));
    }
}
