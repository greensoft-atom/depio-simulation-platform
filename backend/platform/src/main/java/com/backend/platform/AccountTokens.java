package com.backend.platform;

import java.net.MalformedURLException;
import java.text.ParseException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The account service's tokens, checked (D-117, 10 §4.2), by Nimbus JOSE + JWT. A token is taken only if all of this
 * holds, and otherwise is refused, its reason logged and never told:
 *
 * <ol>
 * <li>signed ES256 and nothing else: no {@code none}, no HMAC, no RSA; the algorithm is the key's, not the token's
 *     to choose (RFC 8725 §3.1);</li>
 * <li>typed {@code at+jwt} for an access token, {@code secevent+jwt} for an event, so one cannot pass for the other
 *     (RFC 8725 §3.11);</li>
 * <li>by a key in the account service's set: fetched from {@code BACKEND_ACCOUNT_JWKS_URL}, kept five minutes and
 *     fetched again ahead of its end, at once for a {@code kid} it lacks but at most twice in 30 seconds (Nimbus's
 *     limit), so tokens naming keys at random cannot make this fetch them; while the account service cannot be
 *     reached, the set last fetched still serves for an hour;</li>
 * <li>{@code iss} the setting exactly, and this backend's name in {@code aud};</li>
 * <li>{@code exp} to come and {@code iat} past, with 60 seconds' leeway for the clocks (an event has no {@code exp}:
 *     it is taken however late it is delivered);</li>
 * <li>{@code sub} 1 to 64 printable ASCII characters, and {@code jti} there; an access token's {@code client_id}; an
 *     event's {@code events} holding one of the two events this backend knows, a revocation with its moment to the
 *     millisecond, {@code before_ms}.</li>
 * </ol>
 *
 * A key set that cannot be had at all is {@link KeysUnavailable}: nothing is wrong with the token, and the caller
 * answers 503.
 */
public final class AccountTokens {

    private static final Logger log = LoggerFactory.getLogger(AccountTokens.class);

    static final String DELETED = "urn:backend:account:deleted";
    static final String SESSIONS_REVOKED = "urn:backend:account:sessions-revoked";
    private static final int LEEWAY_SECONDS = 60;
    private static final long KEPT_MILLIS = 5 * 60_000L;
    private static final long REFETCH_EVERY_MILLIS = 30_000L;
    private static final long OUTAGE_MILLIS = 60 * 60_000L;

    /** An access token taken: whose, the name it gives, when its password was given. */
    public record Access(String subject, String username, Instant authTime) { }

    /** What an event tells. */
    public enum Kind { DELETED, SESSIONS_REVOKED }

    /** An event taken: of whom, what, when it was made, and for sessions-revoked the moment before which they end. */
    public record Event(String subject, Kind kind, Instant issuedAt, String jti, Instant before) { }

    /** The key set could not be had: nothing is wrong with the token, and the caller is to come back. */
    public static final class KeysUnavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public KeysUnavailable(Throwable cause) {
            super(cause);
        }
    }

    private final DefaultJWTProcessor<SecurityContext> accessTokens;
    private final DefaultJWTProcessor<SecurityContext> events;

    public AccountTokens(AccountTokenSettings settings) {
        this(settings, REFETCH_EVERY_MILLIS);
    }

    /** A refetch at most every {@code refetchMillis}: a test's shorter. */
    AccountTokens(AccountTokenSettings settings, long refetchMillis) {
        this(settings.issuer(), settings.audience(), remote(settings, refetchMillis));
    }

    AccountTokens(String issuer, String audience, JWKSource<SecurityContext> keys) {
        // client_id is checked in access(), there as a string: listed here too, each check would make the other redundant.
        this.accessTokens = processor(keys, "at+jwt", issuer, audience, Set.of("sub", "iat", "exp", "jti"));
        this.events = processor(keys, "secevent+jwt", issuer, audience, Set.of("sub", "iat", "jti", "events"));
    }

    private static JWKSource<SecurityContext> remote(AccountTokenSettings settings, long refetchMillis) {
        try {
            return JWKSourceBuilder.<SecurityContext>create(settings.jwks().toURL(), new DefaultResourceRetriever(2_000, 2_000, 51_200))
                    .cache(KEPT_MILLIS, 15_000L)
                    .refreshAheadCache(true)
                    .rateLimited(refetchMillis)
                    .outageTolerant(OUTAGE_MILLIS)
                    .build();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(settings.jwks().toString(), e);
        }
    }

    private static DefaultJWTProcessor<SecurityContext> processor(JWKSource<SecurityContext> keys, String type, String issuer,
                                                                  String audience, Set<String> required) {
        DefaultJWTProcessor<SecurityContext> p = new DefaultJWTProcessor<>();
        p.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(new JOSEObjectType(type)));
        p.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.ES256, keys));
        DefaultJWTClaimsVerifier<SecurityContext> claims = new DefaultJWTClaimsVerifier<>(Set.of(audience),
                new JWTClaimsSet.Builder().issuer(issuer).build(), required, null);
        claims.setMaxClockSkew(LEEWAY_SECONDS);
        p.setJWTClaimsSetVerifier(claims);
        return p;
    }

    /** @return the access token's, or null when it is not one this backend takes */
    public Access access(String token) {
        JWTClaimsSet c = checked(accessTokens, token, "an access token");
        if (c == null) {
            return null;
        }
        try {
            if (c.getStringClaim("client_id") == null) {
                return refused("an access token", "no client_id");
            }
            Long authTime = c.getLongClaim("auth_time");
            return new Access(c.getSubject(), c.getStringClaim("preferred_username"),
                    authTime == null ? c.getIssueTime().toInstant() : Instant.ofEpochSecond(authTime));
        } catch (ParseException e) {
            return refused("an access token", e.getMessage());
        }
    }

    /** @return the event's, or null when it is not one this backend takes */
    public Event event(String token) {
        JWTClaimsSet c = checked(events, token, "an event");
        if (c == null) {
            return null;
        }
        Map<String, Object> told;
        try {
            told = c.getJSONObjectClaim("events");
        } catch (ParseException e) {
            return refused("an event", "its events are not an object");
        }
        if (told == null || told.size() != 1) {
            return refused("an event", "not one event");
        }
        Kind kind = switch (told.keySet().iterator().next()) {
            case DELETED -> Kind.DELETED;
            case SESSIONS_REVOKED -> Kind.SESSIONS_REVOKED;
            default -> null;
        };
        if (kind == null) {
            return refused("an event", "an event this backend does not know: " + told.keySet());
        }
        Instant before = null;
        if (kind == Kind.SESSIONS_REVOKED) {
            // iat is whole seconds: the moment the sessions end before, to the millisecond, is the event's own.
            Object moment = told.get(SESSIONS_REVOKED) instanceof Map<?, ?> payload ? payload.get("before_ms") : null;
            if (!(moment instanceof Long ms)) {
                return refused("an event", "a revocation without its moment, before_ms");
            }
            before = Instant.ofEpochMilli(ms);
        }
        return new Event(c.getSubject(), kind, c.getIssueTime().toInstant(), c.getJWTID(), before);
    }

    private static JWTClaimsSet checked(DefaultJWTProcessor<SecurityContext> processor, String token, String what) {
        if (token == null) {
            return null;
        }
        JWTClaimsSet c;
        try {
            c = processor.process(token, null);
        } catch (RateLimitReachedException e) {
            return refused(what, "a key it names, not in the set, which was fetched twice in the last 30 s");
        } catch (KeySourceException e) {
            throw new KeysUnavailable(e);
        } catch (ParseException | BadJOSEException | JOSEException e) {
            return refused(what, e.getMessage());
        }
        if (!subject(c.getSubject())) {
            return refused(what, "its sub is not 1 to 64 printable ASCII characters");
        }
        if (c.getIssueTime().toInstant().isAfter(Instant.now().plusSeconds(LEEWAY_SECONDS))) {
            return refused(what, "made in the future, " + c.getIssueTime().toInstant());
        }
        return c;
    }

    private static boolean subject(String sub) {
        if (sub == null || sub.isEmpty() || sub.length() > 64) {
            return false;
        }
        for (int i = 0; i < sub.length(); i++) {
            char ch = sub.charAt(i);
            if (ch <= 0x20 || ch >= 0x7f) {
                return false;
            }
        }
        return true;
    }

    private static <T> T refused(String what, String why) {
        log.info("{} refused: {}", what, why);
        return null;
    }
}
