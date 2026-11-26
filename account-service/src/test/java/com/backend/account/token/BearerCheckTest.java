package com.backend.account.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BearerCheckTest {

    private static final String ISSUER = "https://203.0.113.7/account";
    private final TestKeys.Pair old = TestKeys.newPair();
    private final TestKeys.Pair now = TestKeys.newPair();
    private final SigningKeys keys = SigningKeys.from(now.privateKey(), old.publicKey());
    private final TokenIssuer issuer = new TokenIssuer(keys, ISSUER, "backend");
    private final BearerCheck check = new BearerCheck(keys, ISSUER);

    @Test
    @DisplayName("its own access token is taken: whose, and when the password was given")
    void taken() {
        Instant authTime = Instant.now().minusSeconds(3600);
        BearerCheck.Bearer b = check.check(issuer.accessToken("sub-1", "ada", authTime, Instant.now()));
        assertThat(b.subject()).isEqualTo("sub-1");
        assertThat(b.authTime().getEpochSecond()).isEqualTo(authTime.getEpochSecond());
    }

    @Test
    @DisplayName("a token signed by a retired key, still published, is taken")
    void aRetiredKey() throws Exception {
        SigningKeys before = SigningKeys.from(old.privateKey(), null);
        String token = new TokenIssuer(before, ISSUER, "backend").accessToken("sub-1", "ada", Instant.now(), Instant.now());
        assertThat(check.check(token)).isNotNull();
    }

    @Test
    @DisplayName("an event is not an access token, though the same key signed it (RFC 8725 §3.11)")
    void anEventIsRefused() throws Exception {
        assertThat(check.check(issuer.event(TokenIssuer.DELETED, "sub-1", Instant.now()).token())).isNull();
        // Every claim an access token's, the type alone not: refused for it alone.
        assertThat(check.check(signed(keys.current(), "secevent+jwt", claims().build()))).isNull();
        assertThat(check.check(signed(keys.current(), "JWT", claims().build()))).isNull();
        assertThat(check.check(signed(keys.current(), "at+jwt", claims().build()))).as("the same, typed so").isNotNull();
    }

    @Test
    @DisplayName("another issuer, an audience without this service, an expired token: refused")
    void theClaimsAreChecked() throws Exception {
        ECKey key = keys.current();
        assertThat(check.check(signed(key, "at+jwt", claims().issuer("https://elsewhere/account").build()))).isNull();
        assertThat(check.check(signed(key, "at+jwt", claims().audience(List.of("backend")).build()))).isNull();
        long past = System.currentTimeMillis() - 120_000;
        assertThat(check.check(signed(key, "at+jwt", claims().issueTime(new Date(past - 900_000))
                .expirationTime(new Date(past)).build()))).as("two minutes past its end, beyond the leeway").isNull();
        assertThat(check.check(signed(key, "at+jwt", claims().jwtID(null).build()))).as("no jti").isNull();
        assertThat(check.check(signed(key, "at+jwt", claims().claim("client_id", null).build()))).as("no client_id").isNull();
        assertThat(check.check(signed(key, "at+jwt", claims().build()))).as("the same, whole: taken").isNotNull();
    }

    @Test
    @DisplayName("no signature, an HMAC made with the public key as its secret, or another key: refused")
    void signatures() throws Exception {
        assertThat(check.check(new PlainJWT(claims().build()).serialize())).isNull();
        byte[] publicKey = keys.current().toECPublicKey().getEncoded();
        SignedJWT hs = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(new JOSEObjectType("at+jwt"))
                .keyID(keys.current().getKeyID()).build(), claims().build());
        hs.sign(new MACSigner(publicKey));
        assertThat(check.check(hs.serialize())).isNull();
        ECKey stranger = SigningKeys.from(TestKeys.newPair().privateKey(), null).current();
        assertThat(check.check(signed(stranger, "at+jwt", claims().build()))).isNull();
        assertThat(check.check("not.a.token")).isNull();
        assertThat(check.check(null)).isNull();
    }

    private static JWTClaimsSet.Builder claims() {
        long t = System.currentTimeMillis();
        return new JWTClaimsSet.Builder().issuer(ISSUER).subject("sub-1").audience(List.of("backend", ISSUER))
                .claim("client_id", "app").issueTime(new Date(t)).expirationTime(new Date(t + 900_000))
                .jwtID("jti-0123456789abcdefghij").claim("auth_time", t / 1000);
    }

    private static String signed(ECKey key, String type, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).type(new JOSEObjectType(type))
                .keyID(key.getKeyID()).build(), claims);
        jwt.sign(new ECDSASigner(key));
        return jwt.serialize();
    }
}
