package com.backend.examples.crypto;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Values nobody can guess: session tokens, invitation codes, nonces, salts.
 *
 * <p>One {@code SecureRandom}, made once and shared: it is safe across threads and seeds itself
 * from the operating system. Not {@code SecureRandom.getInstanceStrong()}: on Linux it reads
 * {@code /dev/random} and may block a server for as long as the kernel likes. Never
 * {@code java.util.Random} or {@code Math.random()} for anything secret: their next values follow
 * from a few earlier ones.
 */
public final class RandomTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private RandomTokens() {
    }

    /** 32 random bytes, URL-safe base64 without padding: 43 characters, 256 bits nobody can guess. */
    public static String token() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(32));
    }

    public static byte[] bytes(int count) {
        byte[] bytes = new byte[count];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
