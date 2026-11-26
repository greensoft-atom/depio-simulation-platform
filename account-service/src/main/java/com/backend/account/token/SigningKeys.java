package com.backend.account.token;

import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import com.backend.account.RefusedConfiguration;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;

import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.math.ec.FixedPointCombMultiplier;

/**
 * The key that signs, and the keys published (10 §3.4): {@code ACCOUNT_SIGNING_KEY}, an EC P-256 private key as
 * PKCS #8, base64 on one line, as {@code scripts/account-key.sh} makes it; and {@code ACCOUNT_RETIRED_KEYS}, public
 * keys as SubjectPublicKeyInfo, base64, separated by commas, published so that tokens they signed still check after a
 * rotation. Each is named by its RFC 7638 thumbprint, the {@code kid} a token carries.
 *
 * <p>A PKCS #8 key holds the private scalar alone as Java reads it, so the public point is made from it here, d·G on
 * P-256, by Bouncy Castle (the JDK has no call for it): one setting, and no public half that could disagree with it.
 */
public final class SigningKeys {

    private static final String SIGNING = "ACCOUNT_SIGNING_KEY";
    private static final String RETIRED = "ACCOUNT_RETIRED_KEYS";

    private final ECKey current;
    private final JWKSet published;

    private SigningKeys(ECKey current, List<JWK> published) {
        this.current = current;
        this.published = new JWKSet(published);
    }

    public static SigningKeys from(String signingKey, String retired) {
        if (signingKey == null || signingKey.isBlank()) {
            throw new RefusedConfiguration(SIGNING + " is not set: make one with scripts/account-key.sh");
        }
        ECKey current = signing(signingKey.strip());
        List<JWK> published = new ArrayList<>();
        published.add(current.toPublicJWK());
        if (retired != null && !retired.isBlank()) {
            for (String entry : retired.split(",")) {
                ECKey old = retiredKey(entry.strip());
                if (published.stream().noneMatch(k -> k.getKeyID().equals(old.getKeyID()))) {
                    published.add(old);
                }
            }
        }
        return new SigningKeys(current, published);
    }

    /** The key that signs, its private half with it. */
    public ECKey current() {
        return current;
    }

    /** What {@code /.well-known/jwks.json} serves: the public keys alone, the current one first. */
    public JWKSet publicSet() {
        return published;
    }

    public List<String> keyIds() {
        return published.getKeys().stream().map(JWK::getKeyID).toList();
    }

    private static ECKey signing(String base64) {
        ECPrivateKey key;
        try {
            key = (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(decode(SIGNING, base64)));
        } catch (java.security.GeneralSecurityException | ClassCastException e) {
            throw new RefusedConfiguration(SIGNING + " is not an EC private key in PKCS #8: " + e.getMessage(), e);
        }
        if (Curve.forECParameterSpec(key.getParams()) != Curve.P_256) {
            throw new RefusedConfiguration(SIGNING + " is not a P-256 key, which ES256 signs with");
        }
        X9ECParameters p256 = CustomNamedCurves.getByName("P-256");
        org.bouncycastle.math.ec.ECPoint q = new FixedPointCombMultiplier().multiply(p256.getG(), key.getS()).normalize();
        try {
            ECPublicKey pub = (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(
                    new ECPoint(q.getAffineXCoord().toBigInteger(), q.getAffineYCoord().toBigInteger()),
                    key.getParams()));
            return named(new ECKey.Builder(Curve.P_256, pub).privateKey(key));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("a public point made from a valid P-256 key was refused", e);
        }
    }

    private static ECKey retiredKey(String base64) {
        ECPublicKey key;
        try {
            key = (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(decode(RETIRED, base64)));
        } catch (java.security.GeneralSecurityException | ClassCastException e) {
            throw new RefusedConfiguration(RETIRED + " holds what is not an EC public key (SubjectPublicKeyInfo): "
                    + e.getMessage(), e);
        }
        if (Curve.forECParameterSpec(key.getParams()) != Curve.P_256) {
            throw new RefusedConfiguration(RETIRED + " holds a key that is not P-256");
        }
        return named(new ECKey.Builder(Curve.P_256, key));
    }

    private static ECKey named(ECKey.Builder builder) {
        try {
            return builder.keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.ES256).keyIDFromThumbprint().build();
        } catch (JOSEException e) {
            throw new IllegalStateException("the thumbprint of a P-256 key", e);
        }
    }

    private static byte[] decode(String setting, String base64) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new RefusedConfiguration(setting + " is not base64: " + e.getMessage(), e);
        }
    }
}
