package com.backend.handoff;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.jredis.client.JRedisClient;

/**
 * Lobby sessions in j-redis (docs detailed-design/04-platform-services.md §1).
 *
 * The token is 32 random bytes because it is the only thing standing between a stranger and
 * someone's account for the next day. Sessions live in j-redis rather than MySQL: they are
 * ephemeral by nature, read on every request, and losing them costs a re-login and nothing
 * else.
 *
 * It lives here rather than in {@code platform} because two processes read it: {@code
 * platform} writes a session at login, and {@code gateway} checks one on every lobby
 * connection. A gateway that had to depend on {@code platform} for this would inherit a
 * database driver it must never use.
 *
 * A session is deliberately <em>not</em> re-validated against MySQL on each use — that would
 * turn a store blip into a mass logout (docs 03 §9). A ban or a suspension ends every session
 * the player has at once ({@link #revokeAll}), a push closes the lobby connection, and the next
 * login is refused (04 §10).
 */
public final class SessionStore {

    /** A day. Long enough that a player is not asked to log in between sessions. */
    public static final int TTL_SECONDS = 86_400;

    /**
     * Spread, as a fraction of the TTL, so sessions do not all expire at the same moment.
     *
     * A launch, a push notification or a restart concentrates logins into a window; a fixed
     * TTL then reproduces that window exactly one day later, when every one of those
     * sessions expires together. That matters here more than it usually would, because the
     * path they all land on is password verification, which is deliberately expensive:
     * Argon2id costs about 88 ms of CPU per login and the hasher admits
     * a bounded number at a time, so a herd queues rather than scales.
     * Ten per cent either way spreads a day's expiries over about five hours, which is enough.
     */
    private static final double TTL_JITTER = 0.10;

    private static final String KEY_PREFIX = "sess:";
    /** A player's sessions, so a ban ends every one (04 §10). */
    private static final String INDEX_PREFIX = "sess:of:";
    /** Past the longest a session lives, so the index outlasts every session it names. */
    static final int INDEX_TTL_SECONDS = (int) (TTL_SECONDS * (1 + TTL_JITTER)) + 60;
    private static final String F_PLAYER_ID = "playerId";
    private static final String F_CREATED_AT = "createdAt";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final JRedisClient client;

    public SessionStore(JRedisClient client) {
        this.client = client;
    }

    /** A new session: its token, and how long it lasts, which is what a client is told. */
    public record Created(String token, int ttlSeconds) { }

    /** @return the new session token. */
    public CompletableFuture<String> create(long playerId) {
        return open(playerId).thenApply(Created::token);
    }

    /**
     * Creates a session and says how long it really lasts. The TTL is spread by ±10 %, so the
     * nominal {@link #TTL_SECONDS} is not it: a client told that was wrong by up to 2.4 hours.
     */
    public CompletableFuture<Created> open(long playerId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = ENCODER.encodeToString(bytes);
        String key = KEY_PREFIX + token;
        int ttl = jitteredTtlSeconds();
        return client.multi()
                .send("HSET", key, F_PLAYER_ID, Long.toString(playerId),
                        F_CREATED_AT, Long.toString(System.currentTimeMillis()))
                .send("EXPIRE", key, ttl)
                .send("SADD", INDEX_PREFIX + playerId, token)
                .send("EXPIRE", INDEX_PREFIX + playerId, INDEX_TTL_SECONDS)
                .exec()
                .thenApply(replies -> new Created(token, ttl));
    }

    /**
     * Ends every session the player has, at once: a ban (04 §10). A token already ended, by a
     * logout or its expiry, is left in the index until this, and not counted.
     *
     * @return how many were live
     */
    public CompletableFuture<Integer> revokeAll(long playerId) {
        String index = INDEX_PREFIX + playerId;
        return client.smembers(index).thenCompose(tokens -> {
            var tx = client.multi();
            for (String token : tokens) {
                tx.send("DEL", KEY_PREFIX + token);
            }
            tx.send("DEL", index);
            return tx.exec().thenApply(replies -> {
                int live = 0;
                for (int i = 0; i < tokens.size(); i++) {
                    live += (int) replies.get(i).asLong();
                }
                return live;
            });
        });
    }

    /**
     * Whether a token has the form {@link #open} mints: 32 bytes, 43 base64url characters. Anything else
     * names no session, and must name no key: {@code of:42} would make {@code sess:of:42}, a player's
     * index, which a logout would delete and a ban would then find empty (S-20).
     */
    static boolean wellFormed(String token) {
        if (token == null || token.length() != 43) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!(c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_')) {
                return false;
            }
        }
        return true;
    }

    /** @return the player id, or -1 when the token is unknown, expired, or not a token at all. */
    public CompletableFuture<Long> playerIdOf(String token) {
        if (!wellFormed(token)) {
            return CompletableFuture.completedFuture(-1L);
        }
        return client.hget(KEY_PREFIX + token, F_PLAYER_ID)
                .thenApply(value -> {
                    if (value == null) {
                        return -1L;
                    }
                    try {
                        return Long.parseLong(value);
                    } catch (NumberFormatException corrupt) {
                        return -1L;
                    }
                });
    }

    /** Ends a session. Logging out has to actually revoke, or the token outlives the intent. */
    public CompletableFuture<Boolean> revoke(String token) {
        if (!wellFormed(token)) {
            return CompletableFuture.completedFuture(false);
        }
        return client.del(KEY_PREFIX + token).thenApply(n -> n > 0);
    }

    /** @return a TTL within ±{@value #TTL_JITTER} of {@link #TTL_SECONDS}. */
    static int jitteredTtlSeconds() {
        int spread = (int) (TTL_SECONDS * TTL_JITTER);
        return TTL_SECONDS - spread + RANDOM.nextInt(2 * spread + 1);
    }

    /** Test and diagnostic access to the raw record. */
    CompletableFuture<Map<String, String>> fields(String token) {
        return client.hgetall(KEY_PREFIX + token);
    }
}
