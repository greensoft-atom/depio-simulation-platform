package com.backend.account.token;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * The access tokens and the events the account service signs (10 §3.4, §3.5), each ES256 by the current key and
 * typed, so that one can never pass for the other (RFC 8725 §3.11).
 *
 * <p>An access token is RFC 9068's profile: {@code at+jwt}; {@code iss}, {@code sub}, {@code aud} (the backend's
 * name and this service's own issuer, whose own routes take it too), {@code client_id}, {@code iat}, {@code exp}
 * fifteen minutes on, {@code jti}; and {@code auth_time} and {@code preferred_username}. An event is RFC 8417's
 * Security Event Token: {@code secevent+jwt}, the backend's name alone in {@code aud}, one event in {@code events},
 * and no {@code exp}: it is delivered however late the backend takes it.
 */
public final class TokenIssuer {

    public static final Duration ACCESS_LIFETIME = Duration.ofMinutes(15);
    public static final String CLIENT_ID = "app";
    public static final String ACCESS_TYPE = "at+jwt";
    public static final String EVENT_TYPE = "secevent+jwt";
    public static final String DELETED = "urn:backend:account:deleted";
    public static final String SESSIONS_REVOKED = "urn:backend:account:sessions-revoked";

    private static final SecureRandom RANDOM = new SecureRandom();

    /** A signed event and its id. */
    public record Event(String jti, String token) { }

    private final SigningKeys keys;
    private final String issuer;
    private final String audience;
    private final ECDSASigner signer;

    public TokenIssuer(SigningKeys keys, String issuer, String audience) {
        this.keys = keys;
        this.issuer = issuer;
        this.audience = audience;
        try {
            this.signer = new ECDSASigner(keys.current());
        } catch (JOSEException e) {
            throw new IllegalStateException("a P-256 key that cannot sign", e);
        }
    }

    public String accessToken(String subject, String username, Instant authTime, Instant now) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(subject)
                .audience(List.of(audience, issuer))
                .claim("client_id", CLIENT_ID)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ACCESS_LIFETIME)))
                .jwtID(randomId())
                .claim("auth_time", authTime.getEpochSecond())
                .claim("preferred_username", username)
                .build();
        return sign(ACCESS_TYPE, claims);
    }

    public Event event(String eventUri, String subject, Instant now) {
        String jti = randomId();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(now))
                .jwtID(jti)
                .subject(subject)
                .claim("events", Map.of(eventUri, Map.of()))
                .build();
        return new Event(jti, sign(EVENT_TYPE, claims));
    }

    /** 16 random bytes, base64url: 22 characters. An account's subject, a token's id, an event's. */
    public static String randomId() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private String sign(String type, JWTClaimsSet claims) {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).type(new JOSEObjectType(type))
                .keyID(keys.current().getKeyID()).build(), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("signing with the current key", e);
        }
        return jwt.serialize();
    }
}
