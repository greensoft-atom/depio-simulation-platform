package com.backend.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    @DisplayName("the right password verifies and the wrong one does not")
    void roundTrip() {
        byte[] stored = hasher.hash("correct horse battery".toCharArray());

        assertThat(hasher.verify("correct horse battery".toCharArray(), stored)).isTrue();
        assertThat(hasher.verify("correct horse batter".toCharArray(), stored)).isFalse();
        assertThat(hasher.verify("".toCharArray(), stored)).isFalse();
    }

    @Test
    @DisplayName("the same password hashes differently every time")
    void saltMakesHashesUnique() {
        byte[] a = hasher.hash("same-password".toCharArray());
        byte[] b = hasher.hash("same-password".toCharArray());

        // Identical hashes would mean no salt, which makes the whole table crackable at once
        // with one rainbow table rather than one attack per account.
        assertThat(a).isNotEqualTo(b);
        assertThat(hasher.verify("same-password".toCharArray(), a)).isTrue();
        assertThat(hasher.verify("same-password".toCharArray(), b)).isTrue();
    }

    @Test
    @DisplayName("a hash stored with different parameters still verifies")
    void parametersTravelWithTheHash() {
        // This is the property that makes the cost changeable later. Built by hand at a
        // deliberately different cost from the current constants, the way an older row would
        // look after the parameters were raised.
        int memoryKib = 8 * 1024;
        int iterations = 1;
        byte[] salt = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] raw = new byte[32];
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt).withMemoryAsKB(memoryKib).withIterations(iterations)
                .withParallelism(1).build());
        generator.generateBytes("old-password".toCharArray(), raw);

        Base64.Encoder e = Base64.getEncoder().withoutPadding();
        byte[] stored = ("$argon2id$v=19$m=" + memoryKib + ",t=" + iterations + ",p=1$"
                + e.encodeToString(salt) + "$" + e.encodeToString(raw))
                .getBytes(StandardCharsets.UTF_8);

        assertThat(memoryKib).isNotEqualTo(PasswordHasher.MEMORY_KIB);
        assertThat(hasher.verify("old-password".toCharArray(), stored)).isTrue();
        assertThat(hasher.verify("wrong".toCharArray(), stored)).isFalse();
    }

    @Test
    @DisplayName("a corrupt or foreign hash is refused, never thrown on")
    void malformedHashIsRefused() {
        // A row that cannot be parsed must not let anyone in, and must not take the login
        // endpoint down for everyone else either.
        assertThat(hasher.verify("x".toCharArray(), null)).isFalse();
        assertThat(hasher.verify("x".toCharArray(), new byte[0])).isFalse();
        assertThat(hasher.verify("x".toCharArray(), "nonsense".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(hasher.verify("x".toCharArray(),
                "$argon2id$v=19$m=bad,t=2,p=1$aaaa$bbbb".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(hasher.verify("x".toCharArray(),
                "$argon2id$v=19$m=19456,t=2,p=1$not-base64!$bbbb".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(hasher.verify("x".toCharArray(),
                "$2b$12$abcdefghijklmnopqrstuv".getBytes(StandardCharsets.UTF_8)))
                .as("a bcrypt hash from some other system is refused, not misread").isFalse();
    }

    @Test
    @DisplayName("the line for the hasher is eight hashing and a hundred and sixty waiting, and no more")
    void admissionIsBounded() {
        int room = PasswordHasher.MAX_CONCURRENT + PasswordHasher.MAX_QUEUED;
        java.util.List<PasswordHasher.Admission> places = new java.util.ArrayList<>();
        for (int i = 0; i < room; i++) {
            places.add(hasher.admit());
        }
        assertThat(places).doesNotContainNull();
        assertThat(hasher.admit()).as("one more than there is room for").isNull();

        places.get(0).close();
        places.get(0).close();                  // closing twice gives back one place, not two
        PasswordHasher.Admission next = hasher.admit();
        assertThat(next).as("a place given back is a place").isNotNull();
        assertThat(hasher.admit()).isNull();

        next.close();
        places.forEach(PasswordHasher.Admission::close);
        assertThat(hasher.admit()).as("all given back").isNotNull().satisfies(PasswordHasher.Admission::close);
    }

    @Test
    @DisplayName("a flipped bit in the stored hash invalidates it")
    void tamperingIsDetected() {
        String stored = new String(hasher.hash("password-1234".toCharArray()), StandardCharsets.UTF_8);
        int cut = stored.lastIndexOf('$') + 1;
        byte[] raw = Base64.getDecoder().decode(stored.substring(cut));
        // In the hash itself, not in its text. The text's last character carries two bits
        // base64 leaves unused, and flipping it there turned a final 0, 4 or 8 into 1, 5 or
        // 9 — the same bytes. So this test failed about one run in five (measured: 36 of
        // 200) while the hasher was right every time.
        raw[raw.length - 1] ^= 0x01;
        byte[] tampered = (stored.substring(0, cut)
                + Base64.getEncoder().withoutPadding().encodeToString(raw))
                .getBytes(StandardCharsets.UTF_8);

        assertThat(hasher.verify("password-1234".toCharArray(), tampered)).isFalse();
    }

    @Test
    @DisplayName("hashing costs what it is supposed to cost")
    void costIsMeasured() {
        char[] password = "measure-me-please".toCharArray();
        hasher.hash(password);                                  // warm up the JIT

        long start = System.nanoTime();
        int rounds = 5;
        for (int i = 0; i < rounds; i++) {
            hasher.hash(password);
        }
        double millis = (System.nanoTime() - start) / 1e6 / rounds;
        System.out.printf("Argon2id m=%d KiB t=%d p=%d: %.1f ms per hash%n",
                PasswordHasher.MEMORY_KIB, PasswordHasher.ITERATIONS,
                PasswordHasher.PARALLELISM, millis);

        // Not a benchmark, a guard rail in both directions. Too slow and a login storm
        // queues; too fast and the parameters have silently been weakened.
        assertThat(millis).as("a hash should not be free").isGreaterThan(1.0);
        assertThat(millis).as("a hash should not take a second").isLessThan(1000.0);
    }

    @Test
    @DisplayName("concurrent hashing stays correct and bounded")
    void concurrentHashing() throws Exception {
        int threads = PasswordHasher.MAX_CONCURRENT * 3;        // more than the permits
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger verified = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final String password = "password-" + i;
            new Thread(() -> {
                try {
                    go.await();
                    byte[] stored = hasher.hash(password.toCharArray());
                    if (hasher.verify(password.toCharArray(), stored)
                            && !hasher.verify("password-other".toCharArray(), stored)) {
                        verified.incrementAndGet();
                    }
                } catch (Exception e) {
                    failed.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        go.countDown();
        assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();

        assertThat(failed.get()).isZero();
        assertThat(verified.get()).as("every thread hashed and verified its own password")
                .isEqualTo(threads);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("a hash made under other parameters needs redoing, one made under these does not")
    void needsRehash() {
        byte[] current = hasher.hash("hunter2-hunter2".toCharArray());
        byte[] cheaper = new PasswordHasher(8 * 1024, 1, 1).hash("hunter2-hunter2".toCharArray());
        org.assertj.core.api.Assertions.assertThat(hasher.needsRehash(current)).isFalse();
        org.assertj.core.api.Assertions.assertThat(hasher.needsRehash(cheaper)).isTrue();
        org.assertj.core.api.Assertions.assertThat(hasher.needsRehash(
                "not a hash".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .as("nothing to redo: it never verifies").isFalse();
    }
}
