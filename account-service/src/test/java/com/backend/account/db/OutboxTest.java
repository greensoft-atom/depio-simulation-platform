package com.backend.account.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboxTest {

    private static Database db;
    private Outbox outbox;
    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");

    @BeforeAll
    static void setUp() {
        db = TestDatabase.open();
    }

    @AfterAll
    static void tearDown() {
        db.close();
    }

    @BeforeEach
    void fresh() throws Exception {
        TestDatabase.empty(db);
        outbox = new Outbox(db.dataSource());
    }

    @Test
    @DisplayName("an event is written in its change's transaction: rolled back with it, it was never there")
    void withItsChange() throws Exception {
        try {
            new Tx(db.dataSource()).execute(c -> {
                outbox.add(c, "jti-000000000000000001", "deleted", "a.b.c", NOW);
                throw new SQLException("the change failed");
            });
        } catch (SQLException expected) {
            // the change and its event, both gone
        }
        assertThat(outbox.pending()).isZero();
        new Tx(db.dataSource()).execute(c -> {
            outbox.add(c, "jti-000000000000000002", "deleted", "a.b.c", NOW);
            return null;
        });
        assertThat(outbox.pending()).isEqualTo(1);
    }

    @Test
    @DisplayName("an event due is claimed for a lease, passed over meanwhile, taken or put off")
    void claimed() throws Exception {
        new Tx(db.dataSource()).execute(c -> {
            outbox.add(c, "jti-000000000000000001", "deleted", "one", NOW);
            outbox.add(c, "jti-000000000000000002", "sessions-revoked", "two", NOW);
            return null;
        });
        List<Outbox.Due> due = outbox.claim(10, NOW, Duration.ofSeconds(30));
        assertThat(due).extracting(Outbox.Due::token).containsExactly("one", "two");
        assertThat(due.get(1).kind()).isEqualTo("sessions-revoked");
        assertThat(due.get(0).attempts()).isZero();
        assertThat(outbox.claim(10, NOW.plusSeconds(29), Duration.ofSeconds(30))).as("leased").isEmpty();
        outbox.taken(due.get(0).id(), NOW.plusSeconds(1));
        outbox.failed(due.get(1).id(), 1, NOW.plusSeconds(120));
        assertThat(outbox.pending()).isEqualTo(1);
        assertThat(outbox.claim(10, NOW.plusSeconds(119), Duration.ofSeconds(30))).as("put off").isEmpty();
        List<Outbox.Due> again = outbox.claim(10, NOW.plusSeconds(120), Duration.ofSeconds(30));
        assertThat(again).extracting(Outbox.Due::token).containsExactly("two");
        assertThat(again.get(0).attempts()).isEqualTo(1);
        assertThat(outbox.claim(1, NOW.plusSeconds(500), Duration.ofSeconds(30))).hasSize(1);
    }
}
