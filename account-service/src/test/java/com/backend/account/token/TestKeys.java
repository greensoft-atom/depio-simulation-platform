package com.backend.account.token;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/** Keys as an operator gives them: a private key PKCS #8 and a public key SubjectPublicKeyInfo, each base64. */
public final class TestKeys {

    public record Pair(String privateKey, String publicKey) { }

    public static Pair newPair() {
        return of("secp256r1");
    }

    public static Pair of(String curve) {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec(curve));
            KeyPair kp = g.generateKeyPair();
            return new Pair(Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()),
                    Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private TestKeys() {
    }
}
