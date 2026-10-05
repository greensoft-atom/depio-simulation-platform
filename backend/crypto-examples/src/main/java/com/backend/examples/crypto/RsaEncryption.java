package com.backend.examples.crypto;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypting to the holder of an RSA private key: RSA-OAEP with SHA-256 for a few bytes, and a
 * fresh AES key wrapped by it for anything larger ({@link #seal}). Where: a partner's public key
 * that only takes RSA; a backup's key encrypted to an offline recovery key.
 *
 * <p>The trap: Java's {@code "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"} keeps MGF1 on SHA-1, while
 * OpenSSL ({@code rsa_oaep_md:sha256}), WebCrypto and most others put it on SHA-256 too; each side
 * then fails on the other's ciphertexts. Name both, as {@link #OAEP_SHA256} does. Never
 * {@code "RSA/ECB/PKCS1Padding"} for new work: it is open to padding-oracle attacks.
 */
public final class RsaEncryption {

    public static final OAEPParameterSpec OAEP_SHA256 =
            new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

    private static final String TRANSFORMATION = "RSA/ECB/OAEPPadding";

    private RsaEncryption() {
    }

    /** At most the key's size in bytes less 66: 190 bytes with 2048 bits, 318 with 3072. */
    public static byte[] encrypt(PublicKey key, byte[] small) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key, OAEP_SHA256);
        return cipher.doFinal(small);
    }

    public static byte[] decrypt(PrivateKey key, byte[] ciphertext) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key, OAEP_SHA256);
        return cipher.doFinal(ciphertext);
    }

    /**
     * Anything, of any size: a fresh AES-256 key encrypts it ({@link Aead}) and RSA-OAEP encrypts that
     * key. {@code length of the wrapped key (2 bytes) || wrapped key || AES-GCM's output}, the wrapped
     * key bound as the associated data.
     */
    public static byte[] seal(PublicKey key, byte[] plaintext) throws GeneralSecurityException {
        SecretKey contentKey = Aead.newAesKey();
        byte[] wrapped = encrypt(key, contentKey.getEncoded());
        byte[] body = Aead.encrypt(Aead.AES_GCM, contentKey, plaintext, wrapped);
        return ByteBuffer.allocate(2 + wrapped.length + body.length)
                .putShort((short) wrapped.length).put(wrapped).put(body).array();
    }

    public static byte[] open(PrivateKey key, byte[] sealed) throws GeneralSecurityException {
        ByteBuffer in = ByteBuffer.wrap(sealed);
        byte[] wrapped = new byte[in.getShort() & 0xffff];
        in.get(wrapped);
        byte[] body = new byte[in.remaining()];
        in.get(body);
        SecretKey contentKey = new SecretKeySpec(decrypt(key, wrapped), "AES");
        return Aead.decrypt(Aead.AES_GCM, contentKey, body, wrapped);
    }
}
