package com.backend.examples.crypto;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * PEM with Java alone: the text form of keys and certificates, base64 of their DER between
 * {@code -----BEGIN type-----} and {@code -----END type-----}. Java 21 reads and writes the two
 * standard key forms this way, a public key as {@code PUBLIC KEY} (X.509 SubjectPublicKeyInfo) and
 * an unencrypted private key as {@code PRIVATE KEY} (PKCS#8); certificates it reads as PEM directly
 * ({@link Certificates}). The other forms OpenSSL writes ({@code RSA PRIVATE KEY},
 * {@code EC PRIVATE KEY}, {@code ENCRYPTED PRIVATE KEY}) take BouncyCastle: {@link AnyPem}.
 *
 * <p>The algorithm is not in a PEM's header: the caller names it ("RSA", "EC", "Ed25519", "X25519").
 */
public final class Pem {

    private Pem() {
    }

    public static String write(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    /** The DER inside the first block of that type; anything around it (comments, other blocks) is ignored. */
    public static byte[] read(String pem, String type) {
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        int from = pem.indexOf(begin);
        int to = from < 0 ? -1 : pem.indexOf(end, from);
        if (to < 0) {
            throw new IllegalArgumentException("no " + type + " in it");
        }
        return Base64.getMimeDecoder().decode(pem.substring(from + begin.length(), to));
    }

    public static String publicKey(PublicKey key) {
        return write("PUBLIC KEY", key.getEncoded());
    }

    public static String privateKey(PrivateKey key) {
        return write("PRIVATE KEY", key.getEncoded());
    }

    public static PublicKey readPublicKey(String pem, String algorithm) throws GeneralSecurityException {
        return KeyFactory.getInstance(algorithm).generatePublic(new X509EncodedKeySpec(read(pem, "PUBLIC KEY")));
    }

    public static PrivateKey readPrivateKey(String pem, String algorithm) throws GeneralSecurityException {
        return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(read(pem, "PRIVATE KEY")));
    }
}
