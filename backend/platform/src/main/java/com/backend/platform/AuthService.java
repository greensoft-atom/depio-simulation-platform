package com.backend.platform;

import java.sql.SQLException;
import java.util.concurrent.ExecutionException;

import com.backend.handoff.SessionStore;
import com.backend.handoff.StoreUnavailableException;
import com.backend.persistence.AccountRepository;
import com.backend.persistence.AccountRepository.Credentials;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logging in, and guests (docs detailed-design/04-platform-services.md §1, 10-accounts.md §4; D-117).
 *
 * <p>A player logs in with an access token of the account service's, taken once for a session of the backend's own
 * ({@link #exchange}): its subject names the player, made at the account's first login. A guest logs in with its key
 * (D-46), and links an account later ({@link #link}). The backend keeps no username and no password.
 *
 * <h2>Blocking on purpose</h2>
 *
 * Every call blocks, on MySQL and on the store. They are meant to run on virtual threads. They must never be called
 * from a Netty event loop or a room thread.
 */
public final class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * {@code INVALID_CREDENTIALS}: a guest key no guest has; {@code INVALID_TOKEN}: an access token this backend does
     * not take, or one of an account deleted.
     */
    public enum Login { OK, INVALID_CREDENTIALS, INVALID_TOKEN, BANNED }

    /** {@code ttlSeconds}: how long the session lasts, when there is one; {@code newPlayer}: made by this login. */
    public record LoginResult(Login outcome, long playerId, String token, int ttlSeconds, boolean newPlayer) {
        static LoginResult refused(Login outcome, long playerId) {
            return new LoginResult(outcome, playerId, null, 0, false);
        }
    }

    /** A guest made (Q-22): the key the device keeps, and the name it was given. */
    public record GuestResult(long playerId, String guestKey, String displayName) { }

    public enum Link { OK, NO_SESSION, INVALID_TOKEN, NOT_A_GUEST, ACCOUNT_IN_USE }

    public enum Rename { OK, NO_SESSION, INVALID_DISPLAY_NAME, TOO_SOON }

    /** {@code name}: the display name as stored, when renamed; {@code problem}: the rule broken, when not. */
    public record RenameResult(Rename outcome, long playerId, String name, String problem) { }

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
    private static final int GUEST_KEY_BYTES = 32;

    private final AccountRepository accounts;
    private final SessionStore sessions;
    private final AccountTokens tokens;

    public AuthService(AccountRepository accounts, SessionStore sessions, AccountTokens tokens) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.tokens = tokens;
    }

    /**
     * An access token of the account service's, taken for a session (D-117, 10 §4.3): its subject's player, or at the
     * account's first login a new one, named from the token's username by the display name's rules, or {@code Player}
     * and four digits when they refuse it. A subject recorded as erased is refused, so a token exchanged in the minutes
     * it is still good after its account's deletion makes no new player.
     *
     * @throws AccountTokens.KeysUnavailable when the account service's key set cannot be had: the caller's 503
     */
    public LoginResult exchange(String accessToken) throws SQLException {
        AccountTokens.Access access = tokens.access(accessToken);
        if (access == null || accounts.erasedSubject(access.subject())) {
            return LoginResult.refused(Login.INVALID_TOKEN, -1);
        }
        boolean made = false;
        Credentials credentials = accounts.findBySubject(access.subject());
        if (credentials == null) {
            long id = accounts.createForSubject(access.subject(), firstName(access.username()));
            made = id > 0;
            // Two first logins at once: the subject's unique index made one player, and the other finds it.
            credentials = accounts.findBySubject(access.subject());
            if (made) {
                log.info("player {} made for an account's first login", id);
            }
        }
        if (isBanned(credentials)) {
            return LoginResult.refused(Login.BANNED, credentials.playerId());
        }
        LoginResult opened = opened(credentials.playerId());
        return new LoginResult(opened.outcome(), opened.playerId(), opened.token(), opened.ttlSeconds(), made);
    }

    /** The name a player made at its account's first login is given: its username, if a display name may be it. */
    static String firstName(String username) {
        if (username != null) {
            DisplayName.Result name = DisplayName.fromUsername(username);
            if (name.ok()) {
                return name.name();
            }
        }
        return String.format("Player%04d", RANDOM.nextInt(10_000));
    }

    /**
     * The session's guest made the account service's account, the same player (D-117): its name, progress and wallet
     * kept, its key stopped. An account another player has already is {@code ACCOUNT_IN_USE}.
     *
     * @throws AccountTokens.KeysUnavailable as {@link #exchange}
     */
    public Link link(String sessionToken, String accessToken) throws SQLException {
        long playerId = playerIdOf(sessionToken);
        if (playerId < 0) {
            return Link.NO_SESSION;
        }
        AccountTokens.Access access = tokens.access(accessToken);
        if (access == null || accounts.erasedSubject(access.subject())) {
            return Link.INVALID_TOKEN;
        }
        AccountRepository.Linked linked = accounts.link(playerId, access.subject());
        if (linked == AccountRepository.Linked.OK) {
            log.info("guest {} linked to an account", playerId);
        }
        return Link.valueOf(linked.name());
    }

    /**
     * An event of the account service's, checked (10 §4.2): null when it is not one this backend takes.
     *
     * @throws AccountTokens.KeysUnavailable as {@link #exchange}
     */
    public AccountTokens.Event event(String token) {
        return tokens.event(token);
    }

    /** A session for a player who has proved who they are, and the login time recorded. */
    private LoginResult opened(long playerId) {
        SessionStore.Created session = await(sessions.open(playerId));
        try {
            accounts.touchLogin(playerId);
        } catch (SQLException e) {
            // An audit column is not worth failing a login over.
            log.warn("could not record the login time for {}: {}", playerId, e.toString());
        }
        return new LoginResult(Login.OK, playerId, session.token(), session.ttlSeconds(), false);
    }

    /**
     * A guest (Q-22, D-46): an account with a random key and no subject, named "Guest" and four digits. The key is the
     * device's to keep; the account keeps only its SHA-256.
     */
    public GuestResult createGuest() throws SQLException {
        byte[] key = new byte[GUEST_KEY_BYTES];
        RANDOM.nextBytes(key);
        String name = String.format("Guest%04d", RANDOM.nextInt(10_000));
        long id = accounts.createGuest(name, sha256(key));
        log.info("made guest {}", id);
        return new GuestResult(id, java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(key), name);
    }

    /** A guest's login by its key: no slow hash, since the key cannot be guessed (D-46). */
    public LoginResult loginGuest(String guestKey) throws SQLException {
        byte[] key;
        try {
            key = java.util.Base64.getUrlDecoder().decode(guestKey == null ? "" : guestKey);
        } catch (IllegalArgumentException malformed) {
            return LoginResult.refused(Login.INVALID_CREDENTIALS, -1);
        }
        Credentials credentials = accounts.findGuest(sha256(key));
        if (credentials == null) {
            return LoginResult.refused(Login.INVALID_CREDENTIALS, -1);
        }
        if (isBanned(credentials)) {
            return LoginResult.refused(Login.BANNED, credentials.playerId());
        }
        return opened(credentials.playerId());
    }

    /**
     * The player's display name, by a display name's rules, once in {@link AccountRepository#RENAME_EVERY}
     * (04 §1, plan item 63). The score boards' copy is the caller's to write.
     */
    public RenameResult rename(String token, String displayName) throws SQLException {
        long playerId = playerIdOf(token);
        if (playerId < 0) {
            return new RenameResult(Rename.NO_SESSION, -1, null, null);
        }
        DisplayName.Result name = DisplayName.check(displayName == null ? "" : displayName);
        if (!name.ok()) {
            return new RenameResult(Rename.INVALID_DISPLAY_NAME, playerId, null, name.problem());
        }
        return switch (accounts.rename(playerId, name.name(), java.time.Instant.now())) {
            case OK -> new RenameResult(Rename.OK, playerId, name.name(), null);
            case TOO_SOON -> new RenameResult(Rename.TOO_SOON, playerId, null, null);
            case NO_SUCH_PLAYER -> new RenameResult(Rename.NO_SESSION, -1, null, null);
        };
    }

    private static byte[] sha256(byte[] key) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(key);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK has SHA-256", e);
        }
    }

    /** @return the player id behind a session token, or -1. */
    public long playerIdOf(String sessionToken) {
        return await(sessions.playerIdOf(sessionToken));
    }

    public boolean logout(String sessionToken) {
        return await(sessions.revoke(sessionToken));
    }

    private static boolean isBanned(Credentials c) {
        return switch (c.status()) {
            case ACTIVE -> false;
            // A suspension with an end date stops when that date passes; the row is tidied up
            // by whoever next writes it, not on the login path.
            case SUSPENDED -> c.bannedUntilMillis() == null
                    || c.bannedUntilMillis() > System.currentTimeMillis();
            case BANNED, DELETED -> true;
        };
    }

    private static <T> T await(java.util.concurrent.CompletableFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreUnavailableException(e);
        } catch (ExecutionException e) {
            throw new StoreUnavailableException(e.getCause());
        }
    }
}
