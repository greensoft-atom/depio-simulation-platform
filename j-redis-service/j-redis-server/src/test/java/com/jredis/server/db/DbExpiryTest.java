package com.jredis.server.db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class DbExpiryTest {

    private static final long NOW = 1_767_225_600_000L;     // 2026-01-01T00:00:00Z

    private final AtomicLong clock = new AtomicLong(NOW);
    private Db db;

    @BeforeEach
    void setUp() {
        db = new Db(new SipHash(1, 2), clock::get);
        db.hooks(new DbHooks() {
            @Override
            public void keyModified(byte[] key) {
            }

            @Override
            public void keyExpired(byte[] key) {
            }
        });
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private KeyEntry stringWithTtl(String key, int valueBytes, long ttlMillis) {
        KeyEntry e = db.add(b(key), KeyEntry.STRING, new byte[valueBytes]);
        db.setExpire(e, NOW + ttlMillis);
        return e;
    }

    /** A deleted key with a long TTL must not stay reachable through its expiry registration. */
    @Test
    void deletingAKeyReleasesItsRegistrationAndValue() {
        KeyEntry e = stringWithTtl("sess:1", 1 << 20, 86_400_000L);
        assertThat(db.expiryBuckets().registrations()).isEqualTo(1);
        db.deleteEntry(e);
        assertThat(db.expiryBuckets().registrations()).isZero();
        assertThat(e.value).isNull();
        assertThat(db.usedMemory()).isZero();
    }

    @Test
    void persistAndShorteningKeepExactlyOneRegistration() {
        KeyEntry e = stringWithTtl("k", 10, 3_600_000L);
        db.setExpire(e, NOW + 10_000);                          // earlier bucket: moves
        assertThat(db.expiryBuckets().registrations()).isEqualTo(1);
        db.setExpire(e, NOW + 7_200_000L);                      // later: stays where it is
        assertThat(db.expiryBuckets().registrations()).isEqualTo(1);
        assertThat(db.persist(e)).isTrue();
        assertThat(db.expiryBuckets().registrations()).isZero();
    }

    @Test
    void activeExpiryExpiresDueKeysAndReRegistersExtendedOnes() {
        for (int i = 0; i < 100; i++) {
            stringWithTtl("short:" + i, 10, 1_000);
        }
        KeyEntry extended = stringWithTtl("extended", 10, 1_000);
        db.setExpire(extended, NOW + 60_000);                   // extended: keeps its early registration
        clock.set(NOW + 5_000);
        assertThat(db.activeExpire(System.nanoTime() + 1_000_000_000L)).isEqualTo(100);
        assertThat(db.size()).isEqualTo(1);
        assertThat(db.expiryBuckets().registrations()).isEqualTo(1);   // re-registered at its new bucket
        clock.set(NOW + 61_000);
        db.activeExpire(System.nanoTime() + 1_000_000_000L);
        assertThat(db.size()).isZero();
        assertThat(db.expiryBuckets().registrations()).isZero();
    }

    /** An interrupted pass, then an earlier bucket appearing, must not corrupt the counter. */
    @Test
    void interruptedPassesKeepTheCounterExact() {
        for (int i = 0; i < 1_000; i++) {
            KeyEntry e = stringWithTtl("k:" + i, 10, 1_000);
            if (i % 2 == 0) {
                db.setExpire(e, NOW + 3_600_000L);            // extended: will be re-registered
            }
        }
        clock.set(NOW + 5_000);
        db.activeExpire(System.nanoTime() - 1);               // deadline already passed: stops after 64
        KeyEntry past = db.add(b("past"), KeyEntry.STRING, new byte[1]);
        db.setExpire(past, 1_000);                             // bucket 0, before everything else
        db.activeExpire(System.nanoTime() + 1_000_000_000L);
        assertThat(db.size()).isEqualTo(500);                  // the extended ones
        assertThat(db.expiryBuckets().registrations()).isEqualTo(500);
    }

    @Test
    void flushAllClearsRegistrations() {
        stringWithTtl("a", 1, 10_000);
        stringWithTtl("b", 1, 10_000);
        db.flushAll();
        assertThat(db.expiryBuckets().registrations()).isZero();
        assertThat(db.hasDueExpiries()).isFalse();
    }
}
