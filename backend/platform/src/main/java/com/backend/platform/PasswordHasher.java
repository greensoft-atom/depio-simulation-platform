package com.backend.platform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Argon2id password hashing (docs detailed-design/04-platform-services.md §1).
 *
 * <h2>The stored form describes itself</h2>
 *
 * {@code $argon2id$v=19$m=19456,t=2,p=1$<salt>$<hash>} — the parameters live in the hash,
 * so raising the cost later does not invalidate existing passwords. Without that, changing
 * the cost is a flag day: every stored hash becomes unverifiable at once. With it, an old
 * hash still verifies under its own parameters, and {@link #needsRehash} says so, so the
 * login that proves the password rewrites it under the current cost (built 2026-09-26;
 * before, raising the cost protected new passwords only).
 *
 * <h2>Why the concurrency limit exists</h2>
 *
 * Argon2 is memory-hard on purpose: each hash holds {@value #MEMORY_KIB} KiB for its whole
 * duration. That is the defence, and it is also a denial-of-service surface pointed at
 * ourselves — unbounded concurrent logins would each claim 19 MiB, so a login storm becomes
 * an OutOfMemoryError rather than a queue. The permit count bounds the memory this class can
 * hold at {@value #MAX_CONCURRENT} × {@value #MEMORY_KIB} KiB; callers queue instead.
 *
 * <h2>Why the queue is bounded too</h2>
 *
 * A queue with no end is the same failure, slower. When every session is lost at once — a
 * store flushed or restored from an old file — every player logs in again together, and a
 * process gets through well under a hundred a second. Once the line is longer than a client
 * will wait, every hash the server finishes is for a client that has already left, because
 * the server cannot tell; the retries join the back of the line, and nobody gets in again.
 * Measured on one process: 3 000 logins at once, clients giving up after 10 s and retrying.
 * Unbounded, 405 logged in, all in the first ten seconds and none in the sixty after, for 20
 * CPU-minutes of hashing nearly all for nobody. Bounded as here, all 3 000 were in within 47
 * s, no client gave up, and no hash was wasted.
 *
 * So a request must be {@link #admit admitted} before it may hash: at most
 * {@value #MAX_CONCURRENT} hashing and {@value #MAX_QUEUED} waiting. Beyond that it is
 * turned away at once and told to come back. The line must drain well inside a client's
 * login timeout, so the two are a pair: this depth was measured against 10 s, and against
 * 3 s it was too deep for the development VM, where contended hashes take about 200 ms.
 *
 * <h2>Threading</h2>
 *
 * Hashing is CPU- and memory-bound for tens of milliseconds. It must never run on a Netty
 * event loop or a room thread.
 */
public final class PasswordHasher {

    /** OWASP's minimum for Argon2id (19 MiB, t=2, p=1), not the 64 MiB desktop profile. */
    static final int MEMORY_KIB = 19 * 1024;
    static final int ITERATIONS = 2;
    static final int PARALLELISM = 1;

    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;

    /** 8 × 19 MiB ≈ 152 MiB held at the worst moment, which the 3 GB heap absorbs. */
    static final int MAX_CONCURRENT = 8;

    /**
     * Twenty rounds of eight: about 1.8 s of waiting at 88 ms a hash, and 3–4 s on the loaded
     * development VM. Clients must allow a login 10 s (04 §1).
     */
    static final int MAX_QUEUED = MAX_CONCURRENT * 20;

    private static final String PREFIX = "$argon2id$v=19$";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    private final Semaphore permits = new Semaphore(MAX_CONCURRENT, true);

    private final AtomicInteger admitted = new AtomicInteger();

    /**
     * A request's place in line for the hasher. Taken once the body has been read, before
     * anything else is done for the request, and closed when it is finished with.
     */
    public final class Admission implements AutoCloseable {
        private boolean closed;

        private Admission() {
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                admitted.decrementAndGet();
            }
        }
    }

    /** How many requests hold a place in line now: hashing, or waiting to. */
    public int inLine() {
        return admitted.get();
    }

    /**
     * @return a place in line, or null when the line is full and the request should be told
     *         to come back.
     *
     * A counter rather than a look at the semaphore's queue: checking the queue and then
     * joining it is two steps, and a burst of six hundred arriving together would all see a
     * short queue before any of them joined it.
     */
    public Admission admit() {
        if (admitted.incrementAndGet() > MAX_CONCURRENT + MAX_QUEUED) {
            admitted.decrementAndGet();
            return null;
        }
        return new Admission();
    }

    private final int memoryKib;
    private final int iterations;
    private final int parallelism;

    /** The current policy: {@value #MEMORY_KIB} KiB, {@value #ITERATIONS} passes. */
    public PasswordHasher() {
        this(MEMORY_KIB, ITERATIONS, PARALLELISM);
    }

    /** Another policy: tests, which need a hash made under an older one. */
    PasswordHasher(int memoryKib, int iterations, int parallelism) {
        this.memoryKib = memoryKib;
        this.iterations = iterations;
        this.parallelism = parallelism;
    }

    /**
     * Whether a stored hash was made under other parameters than these, and so should be
     * made again the next time its password is known: at a successful login. A hash this
     * class cannot read never verifies, so there is nothing to redo.
     */
    public boolean needsRehash(byte[] storedHash) {
        int[] p = parameters(storedHash);
        return p != null && (p[0] != memoryKib || p[1] != iterations || p[2] != parallelism);
    }

    /** @return the encoded hash, safe to store as-is. */
    public byte[] hash(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] raw = derive(password, salt, memoryKib, iterations, parallelism);
        String encoded = PREFIX
                + "m=" + memoryKib + ",t=" + iterations + ",p=" + parallelism
                + "$" + ENCODER.encodeToString(salt)
                + "$" + ENCODER.encodeToString(raw);
        return encoded.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * @return true if the password matches. A stored hash that cannot be parsed returns
     *         false rather than throwing: a corrupt row must not let anyone in, and must
     *         not take the login endpoint down either.
     */
    public boolean verify(char[] password, byte[] storedHash) {
        int[] p = parameters(storedHash);
        if (p == null) {
            return false;
        }
        try {
            String[] parts = new String(storedHash, StandardCharsets.UTF_8).split("\\$");
            byte[] salt = DECODER.decode(parts[4]);
            byte[] expected = DECODER.decode(parts[5]);
            byte[] actual = derive(password, salt, p[0], p[1], p[2]);
            // Constant time: a byte-by-byte compare that returns early leaks how much of the
            // hash was right, which is enough to reconstruct it one byte at a time.
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    /**
     * The {@code m, t, p} a stored hash was made with, or null if it is not one this class
     * wrote: {@code $argon2id$v=19$m=…,t=…,p=…$<salt>$<hash>}.
     */
    private static int[] parameters(byte[] storedHash) {
        if (storedHash == null || storedHash.length == 0) {
            return null;
        }
        String encoded = new String(storedHash, StandardCharsets.UTF_8);
        if (!encoded.startsWith(PREFIX)) {
            return null;
        }
        String[] parts = encoded.split("\\$");          // "", argon2id, v=19, params, salt, hash
        if (parts.length != 6) {
            return null;
        }
        int[] p = new int[3];
        try {
            for (String kv : parts[3].split(",")) {
                int value = Integer.parseInt(kv.substring(kv.indexOf('=') + 1));
                switch (kv.charAt(0)) {
                    case 'm' -> p[0] = value;
                    case 't' -> p[1] = value;
                    case 'p' -> p[2] = value;
                    default -> {
                        return null;
                    }
                }
            }
        } catch (RuntimeException malformed) {
            return null;
        }
        return p;
    }

    private byte[] derive(char[] password, byte[] salt, int memoryKib, int iterations,
                          int parallelism) {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(memoryKib)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .build();
        byte[] out = new byte[HASH_BYTES];
        permits.acquireUninterruptibly();
        try {
            Argon2BytesGenerator generator = new Argon2BytesGenerator();
            generator.init(params);
            generator.generateBytes(password, out);
        } finally {
            permits.release();
        }
        return out;
    }
}
