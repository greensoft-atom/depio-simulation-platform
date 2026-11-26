package com.backend.account.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ThrottleTest {

    private static Database db;
    private TestClock clock;
    private Throttle throttle;

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
        clock = new TestClock(Instant.parse("2026-11-26T10:00:00Z"));
        throttle = new Throttle(db.dataSource(), clock);
    }

    @Test
    @DisplayName("30 a minute from one address, then refused until its minute is over; another address is its own")
    void perAddress() throws Exception {
        for (int i = 0; i < 30; i++) {
            assertThat(throttle.attempt("198.51.100.7", null).allowed()).isTrue();
        }
        clock.advance(Duration.ofSeconds(15));
        Throttle.Verdict refused = throttle.attempt("198.51.100.7", null);
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isEqualTo(45);
        assertThat(throttle.attempt("198.51.100.8", null).allowed()).isTrue();
        clock.advance(Duration.ofSeconds(45));
        assertThat(throttle.attempt("198.51.100.7", null).allowed()).as("a new minute").isTrue();
    }

    @Test
    @DisplayName("10 a quarter hour against one account, from any number of addresses")
    void perAccount() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(throttle.attempt("203.0.113." + i, "ada").allowed()).isTrue();
        }
        Throttle.Verdict refused = throttle.attempt("203.0.113.99", "ada");
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isEqualTo(15 * 60);
        assertThat(throttle.attempt("203.0.113.99", "bob").allowed()).isTrue();
    }

    @Test
    @DisplayName("an IPv4 address is counted as itself, an IPv6 address by its /64")
    void addressKeys() throws Exception {
        assertThat(Throttle.addressKey(InetAddress.getByName("198.51.100.7"))).isEqualTo("198.51.100.7");
        assertThat(Throttle.addressKey(InetAddress.getByName("2001:db8:1:2:3:4:5:6")))
                .isEqualTo(Throttle.addressKey(InetAddress.getByName("2001:db8:1:2:ffff::1")))
                .isEqualTo("20010db800010002/64");
        assertThat(Throttle.addressKey(InetAddress.getByName("2001:db8:1:3::1"))).isEqualTo("20010db800010003/64");
    }

    @Test
    @DisplayName("MySQL not answering refuses the attempt: a limit that fails open is off when it is attacked hardest")
    void failsClosed() {
        Database closed = TestDatabase.open();
        closed.close();
        Throttle broken = new Throttle(closed.dataSource(), clock);
        assertThatThrownBy(() -> broken.attempt("198.51.100.7", "ada")).isInstanceOf(SQLException.class);
    }
}
