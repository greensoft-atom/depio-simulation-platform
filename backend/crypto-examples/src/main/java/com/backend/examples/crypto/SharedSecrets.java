package com.backend.examples.crypto;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;

import javax.crypto.KEM;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;

/**
 * Two parties reaching the same key without sending it: key agreement (X25519, ECDH on P-256),
 * HKDF to make keys of the result, and Java 21's KEM API to encrypt to someone's public key in one
 * step. Where: two services that only know each other's public keys; data encrypted to a key whose
 * private half is kept offline.
 *
 * <p>A shared secret is not a key: its bytes are not uniform. Always through HKDF, with an
 * {@code info} naming the purpose, one key per purpose. Java 21 has no HKDF (Java 25 adds one):
 * BouncyCastle's, which the backend has already.
 */
public final class SharedSecrets {

    private SharedSecrets() {
    }

    public static KeyPair x25519() throws GeneralSecurityException {
        return KeyPairGenerator.getInstance("X25519").generateKeyPair();
    }

    /** Own private key, the other's public key: both sides reach the same bytes. Keys of the same kind, X25519 or EC. */
    public static byte[] agree(PrivateKey own, PublicKey theirs) throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("EC".equals(own.getAlgorithm()) ? "ECDH" : "XDH");
        agreement.init(own);
        agreement.doPhase(theirs, true);
        return agreement.generateSecret();
    }

    /** HKDF-SHA256 (RFC 5869). {@code salt} may be null; {@code info} names what the key is for. */
    public static byte[] hkdf(byte[] secret, byte[] salt, byte[] info, int length) {
        HKDFBytesGenerator generator = new HKDFBytesGenerator(new SHA256Digest());
        generator.init(new HKDFParameters(secret, salt, info));
        byte[] key = new byte[length];
        generator.generateBytes(key, 0, length);
        return key;
    }

    /**
     * Encrypts to the holder of an X25519 private key, with nothing agreed beforehand: Java 21's KEM
     * (DHKEM, RFC 9180) gives a fresh AES key and the bytes that let the recipient find it again.
     * {@code encapsulation (32 bytes) || AES-GCM's output}; {@code context} is bound as the
     * associated data, so a message for one purpose does not open as another.
     */
    public static byte[] sealTo(PublicKey recipient, byte[] plaintext, byte[] context) throws GeneralSecurityException {
        KEM.Encapsulated encapsulated = KEM.getInstance("DHKEM").newEncapsulator(recipient).encapsulate(0, 32, "AES");
        byte[] encapsulation = encapsulated.encapsulation();
        byte[] body = Aead.encrypt(Aead.AES_GCM, encapsulated.key(), plaintext, context);
        byte[] sealed = Arrays.copyOf(encapsulation, encapsulation.length + body.length);
        System.arraycopy(body, 0, sealed, encapsulation.length, body.length);
        return sealed;
    }

    public static byte[] openWith(PrivateKey own, byte[] sealed, byte[] context) throws GeneralSecurityException {
        KEM.Decapsulator decapsulator = KEM.getInstance("DHKEM").newDecapsulator(own);
        int size = decapsulator.encapsulationSize();
        SecretKey key = decapsulator.decapsulate(Arrays.copyOf(sealed, size), 0, 32, "AES");
        return Aead.decrypt(Aead.AES_GCM, key, Arrays.copyOfRange(sealed, size, sealed.length), context);
    }
}
