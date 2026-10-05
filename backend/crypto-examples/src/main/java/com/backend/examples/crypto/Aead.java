package com.backend.examples.crypto;

import java.security.GeneralSecurityException;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;

/**
 * Encryption that also proves the data unchanged (AEAD): AES-256-GCM or ChaCha20-Poly1305. The
 * output is {@code nonce (12 bytes) || ciphertext || tag (16 bytes)}, so it is 28 bytes longer than
 * the input, and decrypting anything changed fails rather than returning something wrong.
 *
 * <p>Where: a value kept encrypted at rest (a column, a file, a backup's key), a message between
 * two services that share a key.
 *
 * <p>The rules: a fresh random nonce for every encryption, never one reused with the same key
 * (with GCM that gives the key away); with random nonces, at most about 2^32 encryptions per key,
 * then a new key. "Associated data" is authenticated but not encrypted: give the row's ID there,
 * and a ciphertext copied to another row will not decrypt. Not ECB, and not CBC or CTR alone: they
 * do not notice changes.
 */
public final class Aead {

    public static final String AES_GCM = "AES/GCM/NoPadding";
    public static final String CHACHA20_POLY1305 = "ChaCha20-Poly1305";

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private Aead() {
    }

    public static SecretKey newAesKey() throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        return generator.generateKey();
    }

    public static SecretKey newChaChaKey() throws GeneralSecurityException {
        return KeyGenerator.getInstance("ChaCha20").generateKey();
    }

    /** {@code associated} may be null: nothing bound. */
    public static byte[] encrypt(String algorithm, SecretKey key, byte[] plaintext, byte[] associated)
            throws GeneralSecurityException {
        byte[] nonce = RandomTokens.bytes(NONCE_BYTES);
        Cipher cipher = Cipher.getInstance(algorithm);
        cipher.init(Cipher.ENCRYPT_MODE, key, parameters(algorithm, nonce));
        if (associated != null) {
            cipher.updateAAD(associated);
        }
        byte[] sealed = Arrays.copyOf(nonce, NONCE_BYTES + cipher.getOutputSize(plaintext.length));
        cipher.doFinal(plaintext, 0, plaintext.length, sealed, NONCE_BYTES);
        return sealed;
    }

    /** The plaintext, or {@link AEADBadTagException} when anything differs: a byte, the key, the associated data. */
    public static byte[] decrypt(String algorithm, SecretKey key, byte[] sealed, byte[] associated)
            throws GeneralSecurityException {
        if (sealed.length < NONCE_BYTES + TAG_BITS / 8) {
            throw new AEADBadTagException("too short to be sealed");
        }
        Cipher cipher = Cipher.getInstance(algorithm);
        cipher.init(Cipher.DECRYPT_MODE, key, parameters(algorithm, Arrays.copyOf(sealed, NONCE_BYTES)));
        if (associated != null) {
            cipher.updateAAD(associated);
        }
        return cipher.doFinal(sealed, NONCE_BYTES, sealed.length - NONCE_BYTES);
    }

    private static AlgorithmParameterSpec parameters(String algorithm, byte[] nonce) {
        return AES_GCM.equals(algorithm) ? new GCMParameterSpec(TAG_BITS, nonce) : new IvParameterSpec(nonce);
    }
}
