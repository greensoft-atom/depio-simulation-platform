package com.backend.examples.crypto;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;

/**
 * Signatures: only the private key's holder can make one, anyone with the public key can check
 * it. Where: a release or a manifest signed by whoever builds it; a message a partner signs and
 * we verify; a certificate ({@link CertificateAuthority}).
 *
 * <p>Which: Ed25519 for anything new between parties we choose (small, fast, no parameters to get
 * wrong); ECDSA P-256 where a standard names it (TLS, X.509, JWT's ES256); RSA where the other
 * side has nothing else, at 3072 bits, RSASSA-PSS for new formats and PKCS#1 v1.5
 * ({@code SHA256withRSA}) where compatibility asks for it.
 */
public final class Signatures {

    public static final String RSA_SHA256 = "SHA256withRSA";
    public static final String RSA_PSS = "RSASSA-PSS";
    /** DER-encoded, about 70 to 72 bytes for P-256: what OpenSSL, X.509 and TLS use. */
    public static final String ECDSA_SHA256 = "SHA256withECDSA";
    /** The same signature as r || s, 64 bytes: what JWT (ES256), WebCrypto and most hardware use. */
    public static final String ECDSA_SHA256_RAW = "SHA256withECDSAinP1363Format";
    public static final String ED25519 = "Ed25519";

    /**
     * PSS's parameters are not in the key, so both sides must name the same: SHA-256 for the digest
     * and for MGF1, and a salt as long as the digest, as RFC 8017 recommends. OpenSSL signs so with
     * {@code -sigopt rsa_padding_mode:pss -sigopt rsa_pss_saltlen:digest}; WebCrypto, with
     * {@code saltLength: 32}.
     */
    public static final PSSParameterSpec PSS_SHA256 =
            new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, PSSParameterSpec.TRAILER_FIELD_BC);

    private Signatures() {
    }

    public static KeyPair rsa(int bits) throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    public static KeyPair ecP256() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    public static KeyPair ed25519() throws GeneralSecurityException {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    public static byte[] sign(String algorithm, PrivateKey key, byte[] data) throws GeneralSecurityException {
        Signature signature = instance(algorithm);
        signature.initSign(key);
        signature.update(data);
        return signature.sign();
    }

    /**
     * Signs a stream a piece at a time. RSA and ECDSA keep only a digest, so a file of any size takes
     * a few kilobytes; Ed25519 reads the message twice and so holds all of it: for a large file, sign
     * its SHA-256 ({@link Hashes}) instead.
     */
    public static byte[] sign(String algorithm, PrivateKey key, InputStream in) throws GeneralSecurityException, IOException {
        Signature signature = instance(algorithm);
        signature.initSign(key);
        byte[] buffer = new byte[64 * 1024];
        for (int n; (n = in.read(buffer)) > 0; ) {
            signature.update(buffer, 0, n);
        }
        return signature.sign();
    }

    /** False for a wrong signature, and for one that is not even well-formed. */
    public static boolean verify(String algorithm, PublicKey key, byte[] data, byte[] signature)
            throws GeneralSecurityException {
        Signature verifier = instance(algorithm);
        verifier.initVerify(key);
        verifier.update(data);
        try {
            return verifier.verify(signature);
        } catch (SignatureException malformed) {
            return false;
        }
    }

    private static Signature instance(String algorithm) throws GeneralSecurityException {
        Signature signature = Signature.getInstance(algorithm);
        if (RSA_PSS.equals(algorithm)) {
            signature.setParameter(PSS_SHA256);
        }
        return signature;
    }
}
