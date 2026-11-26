package com.backend.account.token;

import java.text.ParseException;
import java.time.Instant;
import java.util.Set;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An access token on the account service's own routes, checked as the backend checks one (10 §3.2, §4.2): signed
 * ES256 alone, by a key this service publishes; typed {@code at+jwt}; its issuer this service, its audience holding
 * this service; not expired, 60 seconds allowed for the clocks; with {@code sub}, {@code iat}, {@code exp},
 * {@code jti} and {@code client_id}. Why one is refused is logged, never told.
 */
public final class BearerCheck {

    private static final Logger log = LoggerFactory.getLogger(BearerCheck.class);

    /** Whose token it is, and when its password was last given. */
    public record Bearer(String subject, Instant authTime) { }

    private final DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();

    public BearerCheck(SigningKeys keys, String issuer) {
        processor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(new JOSEObjectType(TokenIssuer.ACCESS_TYPE)));
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.ES256,
                new ImmutableJWKSet<>(keys.publicSet())));
        DefaultJWTClaimsVerifier<SecurityContext> claims = new DefaultJWTClaimsVerifier<>(Set.of(issuer),
                new JWTClaimsSet.Builder().issuer(issuer).build(), Set.of("sub", "iat", "exp", "jti", "client_id"), null);
        claims.setMaxClockSkew(60);
        processor.setJWTClaimsSetVerifier(claims);
    }

    /** @return the bearer, or null when the token is not one this service takes */
    public Bearer check(String token) {
        if (token == null) {
            return null;
        }
        try {
            JWTClaimsSet c = processor.process(token, null);
            Long authTime = c.getLongClaim("auth_time");
            return new Bearer(c.getSubject(), authTime == null ? c.getIssueTime().toInstant() : Instant.ofEpochSecond(authTime));
        } catch (ParseException | BadJOSEException | JOSEException e) {
            log.info("a bearer token refused: {}", e.getMessage());
            return null;
        }
    }
}
