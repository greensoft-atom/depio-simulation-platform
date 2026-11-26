package com.backend.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The account service's tokens as the platform takes them (D-117, 10 §4.2): every rule broken once. */
class AccountTokensTest {

    private static final String ISSUER = "https://203.0.113.7/account";
    private static final String DELETED = "urn:backend:account:deleted";
    private static final String REVOKED = "urn:backend:account:sessions-revoked";

    private final ECKey key = ec();
    private final RSAKey rsa = rsa();
    private final AccountTokens tokens = new AccountTokens(ISSUER, "backend",
            new ImmutableJWKSet<>(new JWKSet(List.<JWK>of(key.toPublicJWK(), rsa.toPublicJWK()))));

    private static ECKey ec() {
        try {
            return new ECKeyGenerator(Curve.P_256).keyIDFromThumbprint(true).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey rsa() {
        try {
            return new RSAKeyGenerator(2048).keyIDFromThumbprint(true).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JWTClaimsSet.Builder access() {
        long t = System.currentTimeMillis();
        return new JWTClaimsSet.Builder().issuer(ISSUER).subject("sub-of-ada-0123456789ab").audience(List.of("backend", ISSUER))
                .claim("client_id", "app").issueTime(new Date(t)).expirationTime(new Date(t + 900_000))
                .jwtID("jti-0123456789abcdefghij").claim("auth_time", t / 1000 - 60).claim("preferred_username", "Ada");
    }

    private static JWTClaimsSet.Builder event(String uri) {
        Map<String, Object> payload = REVOKED.equals(uri) ? Map.of("before_ms", System.currentTimeMillis()) : Map.of();
        return new JWTClaimsSet.Builder().issuer(ISSUER).audience("backend").issueTime(new Date())
                .jwtID("jti-event-0123456789ab").subject("sub-of-ada-0123456789ab").claim("events", Map.of(uri, payload));
    }

    private static String es256(ECKey k, String type, JWTClaimsSet claims) {
        try {
            JWSHeader.Builder h = new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(k.getKeyID());
            if (type != null) {
                h.type(new JOSEObjectType(type));
            }
            SignedJWT jwt = new SignedJWT(h.build(), claims);
            jwt.sign(new ECDSASigner(k));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("an access token of the account service's, by its published key: whose, its name, its auth_time")
    void anAccessTokenIsTaken() {
        AccountTokens.Access a = tokens.access(es256(key, "at+jwt", access().build()));
        assertThat(a.subject()).isEqualTo("sub-of-ada-0123456789ab");
        assertThat(a.username()).isEqualTo("Ada");
        assertThat(a.authTime()).isBefore(Instant.now());
        assertThat(tokens.access(es256(key, "at+jwt", access().claim("preferred_username", null).build())).username())
                .as("no name given: none").isNull();
    }

    @Test
    @DisplayName("ES256 alone, the algorithm the key's: no signature, an HMAC with the public key as its secret, RS256 by a key the set holds")
    void theAlgorithm() throws Exception {
        assertThat(tokens.access(new PlainJWT(access().build()).serialize())).isNull();
        SignedJWT hs = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(new JOSEObjectType("at+jwt"))
                .keyID(key.getKeyID()).build(), access().build());
        hs.sign(new MACSigner(key.toECPublicKey().getEncoded()));
        assertThat(tokens.access(hs.serialize())).isNull();
        SignedJWT rs = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(new JOSEObjectType("at+jwt"))
                .keyID(rsa.getKeyID()).build(), access().build());
        rs.sign(new RSASSASigner(rsa));
        assertThat(tokens.access(rs.serialize())).isNull();
        assertThat(tokens.access(es256(ec(), "at+jwt", access().build()))).as("a key not published").isNull();
        assertThat(tokens.access("not.a.token")).isNull();
        assertThat(tokens.access(null)).isNull();
    }

    @Test
    @DisplayName("typed at+jwt: an event, a plain JWT or no type at all is not an access token (RFC 8725 §3.11)")
    void theType() {
        assertThat(tokens.access(es256(key, "secevent+jwt", access().build()))).isNull();
        assertThat(tokens.access(es256(key, "JWT", access().build()))).isNull();
        assertThat(tokens.access(es256(key, null, access().build()))).isNull();
    }

    @Test
    @DisplayName("the issuer exactly, this backend in its audience, its times with 60 s for the clocks")
    void theClaims() {
        assertThat(tokens.access(es256(key, "at+jwt", access().issuer(ISSUER + "/").build()))).as("a slash more").isNull();
        assertThat(tokens.access(es256(key, "at+jwt", access().issuer("https://elsewhere/account").build()))).isNull();
        assertThat(tokens.access(es256(key, "at+jwt", access().audience(List.of(ISSUER)).build()))).isNull();
        assertThat(tokens.access(es256(key, "at+jwt", access().audience(List.of("backend")).build())))
                .as("this backend alone is enough").isNotNull();
        long past = System.currentTimeMillis() - 120_000;
        assertThat(tokens.access(es256(key, "at+jwt", access().issueTime(new Date(past - 900_000)).expirationTime(new Date(past)).build())))
                .as("two minutes past its end").isNull();
        long nearly = System.currentTimeMillis() - 30_000;
        assertThat(tokens.access(es256(key, "at+jwt", access().issueTime(new Date(nearly - 900_000)).expirationTime(new Date(nearly)).build())))
                .as("thirty seconds past: within the leeway").isNotNull();
        assertThat(tokens.access(es256(key, "at+jwt", access().issueTime(new Date(System.currentTimeMillis() + 300_000)).build())))
                .as("made five minutes from now").isNull();
    }

    @Test
    @DisplayName("a subject of 1 to 64 printable ASCII characters, a jti and a client_id")
    void theRequiredClaims() {
        assertThat(tokens.access(es256(key, "at+jwt", access().subject(null).build()))).isNull();
        assertThat(tokens.access(es256(key, "at+jwt", access().subject("").build()))).isNull();
        assertThat(tokens.access(es256(key, "at+jwt", access().subject("x".repeat(65)).build()))).isNull();
        assertThat(tokens.access(es256(key, "at+jwt", access().subject("x".repeat(64)).build()))).isNotNull();
        assertThat(tokens.access(es256(key, "at+jwt", access().subject("séb").build()))).isNull();
        assertThat(tokens.access(es256(key, "at+jwt", access().subject("a b").build()))).isNull();
        assertThat(tokens.access(es256(key, "at+jwt", access().jwtID(null).build()))).isNull();
        assertThat(tokens.access(es256(key, "at+jwt", access().claim("client_id", null).build()))).isNull();
        assertThat(tokens.access(es256(key, "at+jwt", access().claim("client_id", 7).build()))).as("not a string").isNull();
    }

    @Test
    @DisplayName("an event: typed secevent+jwt, one event of the two it knows, its subject, made in the past")
    void anEvent() {
        AccountTokens.Event e = tokens.event(es256(key, "secevent+jwt", event(DELETED).build()));
        assertThat(e.kind()).isEqualTo(AccountTokens.Kind.DELETED);
        assertThat(e.subject()).isEqualTo("sub-of-ada-0123456789ab");
        assertThat(e.jti()).isEqualTo("jti-event-0123456789ab");
        assertThat(tokens.event(es256(key, "secevent+jwt", event(REVOKED).build())).kind())
                .isEqualTo(AccountTokens.Kind.SESSIONS_REVOKED);
        assertThat(tokens.event(es256(key, "at+jwt", access().build()))).as("an access token is not an event").isNull();
        assertThat(tokens.access(es256(key, "secevent+jwt", event(DELETED).build()))).as("nor the other way").isNull();
        assertThat(tokens.event(es256(key, "secevent+jwt", event("urn:elsewhere:x").build()))).isNull();
        assertThat(tokens.event(es256(key, "secevent+jwt", event(DELETED).claim("events", Map.of(DELETED, Map.of(),
                REVOKED, Map.of("before_ms", System.currentTimeMillis()))).build()))).as("two at once, each one as it should be").isNull();
        assertThat(tokens.event(es256(key, "secevent+jwt", event(DELETED).claim("events", null).build()))).isNull();
        assertThat(tokens.event(es256(key, "secevent+jwt", event(DELETED).subject(null).build()))).isNull();
        assertThat(tokens.event(es256(key, "secevent+jwt", event(DELETED).audience("elsewhere").build()))).isNull();
        assertThat(tokens.event(es256(key, "secevent+jwt", event(DELETED).issuer("https://elsewhere").build()))).isNull();
        assertThat(tokens.event(es256(key, "secevent+jwt", event(DELETED).issueTime(new Date(System.currentTimeMillis() + 300_000)).build())))
                .isNull();
        assertThat(tokens.event(es256(key, "secevent+jwt", event(DELETED).issueTime(new Date(System.currentTimeMillis() - 86_400_000L * 30)).build())))
                .as("however late it is delivered").isNotNull();
    }

    @Test
    @DisplayName("a sessions-revoked event's moment to the millisecond, its payload's before_ms, since iat is whole seconds; without one, refused")
    void aRevocationsMoment() {
        long ms = System.currentTimeMillis() / 1000 * 1000 - 1000 + 123;     // within the second before, not on it
        AccountTokens.Event e = tokens.event(es256(key, "secevent+jwt",
                event(REVOKED).claim("events", Map.of(REVOKED, Map.of("before_ms", ms))).build()));
        assertThat(e.before()).isEqualTo(Instant.ofEpochMilli(ms));
        assertThat(tokens.event(es256(key, "secevent+jwt", event(REVOKED).claim("events", Map.of(REVOKED, Map.of())).build())))
                .as("no moment").isNull();
        assertThat(tokens.event(es256(key, "secevent+jwt",
                event(REVOKED).claim("events", Map.of(REVOKED, Map.of("before_ms", "soon"))).build()))).as("not a number").isNull();
        assertThat(tokens.event(es256(key, "secevent+jwt", event(DELETED).build())).before()).as("a deletion has none").isNull();
    }

    @Test
    @DisplayName("the key set fetched, kept, and fetched again for a key it lacks: a rotated key's token taken")
    void aKeySetFetched() throws Exception {
        AtomicReference<byte[]> served = new AtomicReference<>(new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8));
        int[] fetched = {0};
        HttpServer jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwks.createContext("/.well-known/jwks.json", ex -> {
            fetched[0]++;
            byte[] b = served.get();
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        jwks.start();
        try {
            AccountTokenSettings at = new AccountTokenSettings(ISSUER, "backend",
                    URI.create("http://127.0.0.1:" + jwks.getAddress().getPort() + "/.well-known/jwks.json"));
            AccountTokens limited = new AccountTokens(at);
            assertThat(limited.access(es256(key, "at+jwt", access().build()))).isNotNull();
            // A key it lacks: fetched again, and refused; Nimbus fetches at most twice in its interval, so keys named at
            // random make no more fetches after that.
            assertThat(limited.access(es256(ec(), "at+jwt", access().build()))).isNull();
            assertThat(limited.access(es256(ec(), "at+jwt", access().build()))).isNull();
            assertThat(limited.access(es256(ec(), "at+jwt", access().build()))).isNull();
            assertThat(fetched[0]).as("twice in 30 seconds, whatever is asked").isEqualTo(2);
            fetched[0] = 0;
            AccountTokens remote = new AccountTokens(at, 1);
            assertThat(remote.access(es256(key, "at+jwt", access().build()))).isNotNull();
            assertThat(remote.access(es256(key, "at+jwt", access().build()))).isNotNull();
            assertThat(fetched[0]).as("kept").isEqualTo(1);
            ECKey next = ec();
            served.set(new JWKSet(List.<JWK>of(next.toPublicJWK(), key.toPublicJWK())).toString().getBytes(StandardCharsets.UTF_8));
            assertThat(remote.access(es256(next, "at+jwt", access().build()))).as("a new kid: fetched again").isNotNull();
            assertThat(fetched[0]).isEqualTo(2);
        } finally {
            jwks.stop(0);
        }
    }

    @Test
    @DisplayName("a key set that cannot be had is said so: not a token refused")
    void keysUnavailable() throws Exception {
        int dead;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            dead = s.getLocalPort();
        }
        AccountTokens remote = new AccountTokens(new AccountTokenSettings(ISSUER, "backend",
                URI.create("http://127.0.0.1:" + dead + "/.well-known/jwks.json")));
        assertThatThrownBy(() -> remote.access(es256(key, "at+jwt", access().build())))
                .isInstanceOf(AccountTokens.KeysUnavailable.class);
    }

    @Test
    @DisplayName("the settings: the development account service's by default; an issuer exact; the key set by https, or by http to this machine or the private network alone")
    void settings() {
        AccountTokenSettings dev = AccountTokenSettings.fromEnvironment(Map.of());
        assertThat(dev.issuer()).isEqualTo("http://127.0.0.1:8083");
        assertThat(dev.audience()).isEqualTo("backend");
        assertThat(dev.jwks()).isEqualTo(URI.create("http://127.0.0.1:8083/.well-known/jwks.json"));
        for (String ok : new String[] {"http://127.0.0.1:8070/.well-known/jwks.json", "http://10.0.0.1:8070/.well-known/jwks.json",
                "http://192.168.1.5:8070/k", "http://[::1]:8070/k", "http://[fd00::1]:8070/k", "https://accounts.example/k"}) {
            assertThat(AccountTokenSettings.fromEnvironment(Map.of("BACKEND_ACCOUNT_JWKS_URL", ok)).jwks()).hasToString(ok);
        }
        for (String[] bad : new String[][] {
                {"BACKEND_ACCOUNT_JWKS_URL", "http://203.0.113.7/account/.well-known/jwks.json"},
                {"BACKEND_ACCOUNT_JWKS_URL", "http://accounts.internal:8070/k"},
                {"BACKEND_ACCOUNT_JWKS_URL", "http://localhost:8083/.well-known/jwks.json"},       // a name, even loopback's: DNS decides nothing
                {"BACKEND_ACCOUNT_JWKS_URL", "ftp://10.0.0.1/k"},
                {"BACKEND_ACCOUNT_ISSUER", "https://203.0.113.7/account/"},
                {"BACKEND_ACCOUNT_ISSUER", "not a url"},
                {"BACKEND_ACCOUNT_AUDIENCE", "back end"},
                {"BACKEND_ACCOUNT_AUDIENCE", ""}}) {
            assertThatThrownBy(() -> AccountTokenSettings.fromEnvironment(Map.of(bad[0], bad[1])))
                    .as("%s=%s", bad[0], bad[1]).isInstanceOf(com.backend.common.RefusedConfiguration.class).hasMessageContaining(bad[0]);
        }
    }
}
