package com.backend.examples.crypto;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256: proof that a message comes from someone holding the same secret key, unchanged.
 * Both sides hold the key, so either could have made it: to prove which one, sign
 * ({@link Signatures}).
 *
 * <p>Where: a callback from a payment provider, signed with the secret it gave us; a value the
 * server hands out and wants back unchanged ({@link SignedTokens}).
 */
public final class Hmacs {

    public static final String ALGORITHM = "HmacSHA256";

    private Hmacs() {
    }

    /** A new key: 32 random bytes, 256 bits, as strong as HMAC-SHA256 gets. */
    public static SecretKey newKey() {
        return key(RandomTokens.bytes(32));
    }

    /** A key from its bytes: read from a credential file, never from the source code. */
    public static SecretKey key(byte[] bytes) {
        return new SecretKeySpec(bytes, ALGORITHM);
    }

    /** The tag, 32 bytes, sent with the message. */
    public static byte[] tag(SecretKey key, byte[] message) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(ALGORITHM);
        mac.init(key);
        return mac.doFinal(message);
    }

    /** Whether the tag is this message's under this key; compared in constant time. */
    public static boolean verify(SecretKey key, byte[] message, byte[] tag) throws GeneralSecurityException {
        return MessageDigest.isEqual(tag(key, message), tag);
    }
}
