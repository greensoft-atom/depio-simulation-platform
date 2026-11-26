package com.backend.account.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TokenIssuerTest {

    private static final String ISSUER = "https://203.0.113.7/account";
    private final SigningKeys keys = SigningKeys.from(TestKeys.newPair().privateKey(), null);
    private final TokenIssuer issuer = new TokenIssuer(keys, ISSUER, "backend");

    @Test
    @DisplayName("an access token is RFC 9068's: ES256, at+jwt, the key's id, and every claim the backend checks")
    void anAccessToken() throws Exception {
        Instant now = Instant.parse("2026-11-26T10:00:00Z");
        Instant authTime = Instant.parse("2026-11-20T08:00:00Z");
        SignedJWT jwt = SignedJWT.parse(issuer.accessToken("sub-of-ada-0123456789ab", "Ada", authTime, now));
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
        assertThat(jwt.getHeader().getType().getType()).isEqualTo("at+jwt");
        assertThat(jwt.getHeader().getKeyID()).isEqualTo(keys.current().getKeyID());
        assertThat(jwt.verify(new ECDSAVerifier(keys.current().toECPublicKey()))).isTrue();
        JWTClaimsSet c = jwt.getJWTClaimsSet();
        assertThat(c.getIssuer()).isEqualTo(ISSUER);
        assertThat(c.getSubject()).isEqualTo("sub-of-ada-0123456789ab");
        assertThat(c.getAudience()).containsExactly("backend", ISSUER);
        assertThat(c.getStringClaim("client_id")).isEqualTo("app");
        assertThat(c.getIssueTime().toInstant()).isEqualTo(now);
        assertThat(c.getExpirationTime().toInstant()).isEqualTo(now.plusSeconds(900));
        assertThat(c.getJWTID()).hasSize(22).matches("[A-Za-z0-9_-]+");
        assertThat(c.getLongClaim("auth_time")).isEqualTo(authTime.getEpochSecond());
        assertThat(c.getStringClaim("preferred_username")).isEqualTo("Ada");
        assertThat(issuer.accessToken("s", "a", authTime, now)).as("a new jti each time")
                .isNotEqualTo(issuer.accessToken("s", "a", authTime, now));
    }

    @Test
    @DisplayName("an event is RFC 8417's: secevent+jwt, the backend's audience alone, one event, no expiry")
    void anEvent() throws Exception {
        Instant now = Instant.parse("2026-11-26T10:00:00Z");
        TokenIssuer.Event e = issuer.event(TokenIssuer.DELETED, "sub-of-ada-0123456789ab", now);
        SignedJWT jwt = SignedJWT.parse(e.token());
        assertThat(jwt.getHeader().getType().getType()).isEqualTo("secevent+jwt");
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
        assertThat(jwt.verify(new ECDSAVerifier(keys.current().toECPublicKey()))).isTrue();
        JWTClaimsSet c = jwt.getJWTClaimsSet();
        assertThat(c.getIssuer()).isEqualTo(ISSUER);
        assertThat(c.getAudience()).isEqualTo(List.of("backend"));
        assertThat(c.getSubject()).isEqualTo("sub-of-ada-0123456789ab");
        assertThat(c.getJWTID()).isEqualTo(e.jti());
        assertThat(c.getIssueTime().toInstant()).isEqualTo(now);
        assertThat(c.getExpirationTime()).isNull();
        assertThat(c.getJSONObjectClaim("events")).isEqualTo(Map.of(TokenIssuer.DELETED, Map.of()));
    }

    @Test
    @DisplayName("an id is 16 random bytes in base64url: 22 characters, never the same twice")
    void ids() {
        assertThat(TokenIssuer.randomId()).hasSize(22).matches("[A-Za-z0-9_-]{22}").isNotEqualTo(TokenIssuer.randomId());
    }
}
