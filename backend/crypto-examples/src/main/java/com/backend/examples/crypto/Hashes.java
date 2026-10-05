package com.backend.examples.crypto;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Digests: a fingerprint of some bytes or of a whole file, and comparing secrets safely.
 *
 * <p>A digest proves nothing about who made the data: anyone can compute it again. For that, a
 * key is needed ({@link Hmacs}, {@link Signatures}). Never a digest for passwords: those take
 * Argon2id ({@code platform}'s {@code PasswordHasher}).
 */
public final class Hashes {

    private Hashes() {
    }

    /** SHA-256 of some bytes. "SHA-512" and "SHA3-256" are the same call with another name. */
    public static byte[] sha256(byte[] data) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    /** SHA-256 of a stream, read a piece at a time: a file of any size in a few kilobytes. */
    public static byte[] sha256(InputStream in) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (DigestInputStream reading = new DigestInputStream(in, digest)) {
            reading.transferTo(OutputStream.nullOutputStream());
        }
        return digest.digest();
    }

    /** Lower-case hexadecimal, as {@code sha256sum} and {@code openssl dgst} print it. */
    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Two secrets (MACs, tokens, digests of secrets) compared in a time that does not depend on
     * where they first differ: {@code Arrays.equals} returns at the first difference, which lets an
     * attacker who can time it learn a valid value a byte at a time.
     */
    public static boolean sameSecret(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }
}
