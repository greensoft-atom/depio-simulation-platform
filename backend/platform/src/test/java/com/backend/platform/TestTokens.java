package com.backend.platform;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * The account service's tokens, as the tests make them (D-117): one key of the tests' own, an {@link AccountTokens} that
 * takes what it signs, and access tokens and events as the account service writes them. Nothing of the account
 * service's code: the backend's tests make their tokens by the profile, as any issuer would.
 */
public final class TestTokens {

    public static final String ISSUER = "https://203.0.113.7/account";
    private static final ECKey KEY;

    static {
        try {
            KEY = new ECKeyGenerator(Curve.P_256).keyIDFromThumbprint(true).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** What takes this class's tokens. */
    public static AccountTokens tokens() {
        return new AccountTokens(ISSUER, "backend", new ImmutableJWKSet<>(new JWKSet(KEY.toPublicJWK())));
    }

    /** An access token for {@code subject}, its username {@code username}. */
    public static String access(String subject, String username) {
        long t = System.currentTimeMillis();
        return sign("at+jwt", new JWTClaimsSet.Builder().issuer(ISSUER).subject(subject).audience(List.of("backend", ISSUER))
                .claim("client_id", "app").issueTime(new Date(t)).expirationTime(new Date(t + 900_000))
                .jwtID(Long.toHexString(System.nanoTime())).claim("auth_time", t / 1000)
                .claim("preferred_username", username).build());
    }

    /** An event of {@code kind}, made at {@code at}. */
    public static String event(AccountTokens.Kind kind, String subject, Instant at) {
        String uri = kind == AccountTokens.Kind.DELETED ? AccountTokens.DELETED : AccountTokens.SESSIONS_REVOKED;
        // As the account service makes them: a revocation's moment to the millisecond, iat's whole seconds not enough.
        Map<String, Object> payload = kind == AccountTokens.Kind.DELETED ? Map.of() : Map.of("before_ms", at.toEpochMilli());
        return sign("secevent+jwt", new JWTClaimsSet.Builder().issuer(ISSUER).audience("backend").issueTime(Date.from(at))
                .jwtID(Long.toHexString(System.nanoTime())).subject(subject).claim("events", Map.of(uri, payload)).build());
    }

    private static String sign(String type, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).type(new JOSEObjectType(type))
                    .keyID(KEY.getKeyID()).build(), claims);
            jwt.sign(new ECDSASigner(KEY));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private TestTokens() {
    }
}
