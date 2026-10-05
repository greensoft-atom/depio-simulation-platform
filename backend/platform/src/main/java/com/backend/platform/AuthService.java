package com.backend.platform;

import java.sql.SQLException;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

import com.backend.handoff.SessionStore;
import com.backend.handoff.StoreUnavailableException;
import com.backend.persistence.AccountRepository;
import com.backend.persistence.AccountRepository.Credentials;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Register and login (docs detailed-design/04-platform-services.md §1).
 *
 * <h2>Blocking on purpose</h2>
 *
 * Both calls block: on MySQL, and on Argon2 for tens of milliseconds. They are meant to run
 * on virtual threads, which is what virtual threads are for. They must never be called from
 * a Netty event loop or a room thread.
 */
public final class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final int USERNAME_MIN = 3;
    private static final int USERNAME_MAX = 32;
    private static final int PASSWORD_MIN = 8;
    /** Argon2 does not care, but an unbounded password is an unbounded request body. */
    private static final int PASSWORD_MAX = 128;

    public enum Register { OK, USERNAME_TAKEN, INVALID_USERNAME, INVALID_PASSWORD, INVALID_DISPLAY_NAME }

    /**
     * {@code INVALID_CREDENTIALS} covers both "no such user" and "wrong password" on
     * purpose. Telling them apart tells an attacker which usernames exist, which is the
     * first half of the work.
     */
    public enum Login { OK, INVALID_CREDENTIALS, BANNED }

    /** {@code problem} says why a display name was refused, in words a player can act on. */
    public record RegisterResult(Register outcome, long playerId, String problem) {
        RegisterResult(Register outcome, long playerId) {
            this(outcome, playerId, null);
        }
    }

    /** {@code ttlSeconds}: how long the session lasts, when there is one; 0 otherwise. */
    public record LoginResult(Login outcome, long playerId, String token, int ttlSeconds) { }

    /** A guest made (Q-22): the key the device keeps, and the name it was given. */
    public record GuestResult(long playerId, String guestKey, String displayName) { }

    public enum Upgrade { OK, NO_SESSION, INVALID_USERNAME, INVALID_PASSWORD, INVALID_DISPLAY_NAME, USERNAME_TAKEN, NOT_A_GUEST }

    public record UpgradeResult(Upgrade outcome, String problem) { }

    public enum Rename { OK, NO_SESSION, INVALID_DISPLAY_NAME, TOO_SOON }

    /** {@code name}: the display name as stored, when renamed; {@code problem}: the rule broken, when not. */
    public record RenameResult(Rename outcome, long playerId, String name, String problem) { }

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
    private static final int GUEST_KEY_BYTES = 32;

    private final AccountRepository accounts;
    private final PasswordHasher hasher;
    private final SessionStore sessions;

    /**
     * A real hash of a password nobody has, verified against when the username is unknown.
     *
     * Without it, an unknown username returns in microseconds and a known one takes tens of
     * milliseconds, so anyone with a clock can enumerate the user base.
     */
    private final byte[] decoyHash;

    public AuthService(AccountRepository accounts, PasswordHasher hasher, SessionStore sessions) {
        this.accounts = accounts;
        this.hasher = hasher;
        this.sessions = sessions;
        this.decoyHash = hasher.hash("decoy-for-constant-time-login".toCharArray());
    }

    public RegisterResult register(String username, String displayName, char[] password)
            throws SQLException {
        if (!isValidUsername(username)) {
            return new RegisterResult(Register.INVALID_USERNAME, -1);
        }
        if (password == null || password.length < PASSWORD_MIN || password.length > PASSWORD_MAX
                || !wellFormed(password)) {
            return new RegisterResult(Register.INVALID_PASSWORD, -1);
        }
        // Before the password is hashed: every check that can refuse a request runs first, so
        // a request that was going to be refused never costs 88 ms of Argon2.
        DisplayName.Result name = (displayName == null || displayName.isBlank())
                ? DisplayName.fromUsername(username)
                : DisplayName.check(displayName);
        if (!name.ok()) {
            return new RegisterResult(Register.INVALID_DISPLAY_NAME, -1, name.problem());
        }

        byte[] hash = hasher.hash(password);
        long playerId = accounts.register(username, name.name(), hash);
        if (playerId < 0) {
            return new RegisterResult(Register.USERNAME_TAKEN, -1);
        }
        log.info("registered player {}", playerId);
        return new RegisterResult(Register.OK, playerId);
    }

    /**
     * No half of a surrogate pair on its own. JSON can carry one ({@code "\ud800"}); hashing
     * converts the password to UTF-8, which cannot encode it, and the exception came out as a
     * 500 with a stack trace in the log. Refused like any other password that breaks the rules.
     */
    static boolean wellFormed(char[] password) {
        for (int i = 0; i < password.length; i++) {
            if (Character.isHighSurrogate(password[i]) && i + 1 < password.length
                    && Character.isLowSurrogate(password[i + 1])) {
                i++;
            } else if (Character.isSurrogate(password[i])) {
                return false;
            }
        }
        return true;
    }

    public LoginResult login(String username, char[] password) throws SQLException {
        // Only a name registration would accept can be an account, so nothing else is looked
        // up. The lookup compares accent-insensitively (utf8mb4_0900_ai_ci), so "ada" with an
        // acute on the first letter found and logged into "ada": one account, spelled as many
        // ways as it has letters to accent, and a per-account login limit counted per
        // spelling. Refusing early reveals nothing: the username rules are public.
        if (!isValidUsername(username)) {
            return new LoginResult(Login.INVALID_CREDENTIALS, -1, null, 0);
        }
        Credentials credentials = accounts.findForLogin(username);

        if (credentials == null) {
            hasher.verify(password == null ? new char[0] : password, decoyHash);
            return new LoginResult(Login.INVALID_CREDENTIALS, -1, null, 0);
        }
        if (!hasher.verify(password == null ? new char[0] : password, credentials.passwordHash())) {
            return new LoginResult(Login.INVALID_CREDENTIALS, -1, null, 0);
        }
        if (isBanned(credentials)) {
            return new LoginResult(Login.BANNED, credentials.playerId(), null, 0);
        }
        if (hasher.needsRehash(credentials.passwordHash())) {
            rehash(credentials, password);
        }

        return opened(credentials.playerId());
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
        return new LoginResult(Login.OK, playerId, session.token(), session.ttlSeconds());
    }

    /**
     * A guest (Q-22, D-46): an account with a random key in place of a password, under a
     * username nobody can register, named "Guest" and four digits. The key is the device's to
     * keep; the account keeps only its SHA-256.
     */
    public GuestResult createGuest() throws SQLException {
        byte[] key = new byte[GUEST_KEY_BYTES];
        RANDOM.nextBytes(key);
        String name = String.format("Guest%04d", RANDOM.nextInt(10_000));
        long id = accounts.createGuest("~" + com.backend.handoff.Ulid.generate(), name, sha256(key));
        log.info("made guest {}", id);
        return new GuestResult(id, java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(key), name);
    }

    /** A guest's login by its key: no slow hash, since the key cannot be guessed (D-46). */
    public LoginResult loginGuest(String guestKey) throws SQLException {
        byte[] key;
        try {
            key = java.util.Base64.getUrlDecoder().decode(guestKey == null ? "" : guestKey);
        } catch (IllegalArgumentException malformed) {
            return new LoginResult(Login.INVALID_CREDENTIALS, -1, null, 0);
        }
        Credentials credentials = accounts.findGuest(sha256(key));
        if (credentials == null) {
            return new LoginResult(Login.INVALID_CREDENTIALS, -1, null, 0);
        }
        if (isBanned(credentials)) {
            return new LoginResult(Login.BANNED, credentials.playerId(), null, 0);
        }
        return opened(credentials.playerId());
    }

    /**
     * A guest made a full account, the same player (Q-22): the rules of registering checked
     * before the password is hashed, then the username, the hash and a display name if one is
     * given, set at once, and the key cleared.
     */
    public UpgradeResult upgrade(String token, String username, String displayName, char[] password)
            throws SQLException {
        long playerId = playerIdOf(token);
        if (playerId < 0) {
            return new UpgradeResult(Upgrade.NO_SESSION, null);
        }
        if (!isValidUsername(username)) {
            return new UpgradeResult(Upgrade.INVALID_USERNAME, null);
        }
        if (password == null || password.length < PASSWORD_MIN || password.length > PASSWORD_MAX
                || !wellFormed(password)) {
            return new UpgradeResult(Upgrade.INVALID_PASSWORD, null);
        }
        DisplayName.Result name = displayName == null || displayName.isBlank() ? null : DisplayName.check(displayName);
        if (name != null && !name.ok()) {
            return new UpgradeResult(Upgrade.INVALID_DISPLAY_NAME, name.problem());
        }
        // A guest, and the name free, before the password is hashed: a refusal costs no hash (S-18).
        AccountRepository.Upgraded possible = accounts.upgradable(playerId, username);
        if (possible != AccountRepository.Upgraded.OK) {
            return new UpgradeResult(Upgrade.valueOf(possible.name()), null);
        }
        AccountRepository.Upgraded upgraded = accounts.upgrade(playerId, username, hasher.hash(password),
                name == null ? null : name.name());
        return new UpgradeResult(Upgrade.valueOf(upgraded.name()), null);
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

    /**
     * Makes the stored hash again under the current policy, now that the password is known,
     * which a login is the only moment of. Without it, raising the cost protected new
     * passwords only. It costs this one login a second hash. A failure costs nothing but the
     * upgrade, which the next login tries again.
     */
    private void rehash(Credentials credentials, char[] password) {
        try {
            if (!accounts.replacePasswordHash(credentials.playerId(), credentials.passwordHash(),
                    hasher.hash(password))) {
                log.info("the password of {} changed while it was being checked; not rehashed",
                        credentials.playerId());
            }
        } catch (SQLException e) {
            log.warn("could not rehash the password of {}: {}", credentials.playerId(), e.toString());
        }
    }

    /**
     * The one spelling of a username that names an account, for counting attempts against
     * it; null if the name could not belong to any account. Usernames are ASCII, so lower
     * case here is exactly the database's {@code username_key}.
     */
    public static String usernameKey(String username) {
        return isValidUsername(username) ? username.toLowerCase(Locale.ROOT) : null;
    }

    /**
     * A place in line for the password hasher, or null when it is full; see
     * {@link PasswordHasher#admit}. Callers take it before counting an attempt against the
     * throttle, so an attempt turned away for load does not count against the account.
     */
    public PasswordHasher.Admission admit() {
        return hasher.admit();
    }

    /** How many requests hold or wait for a place in the hasher's line. */
    public int hasherLine() {
        return hasher.inLine();
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
            case BANNED -> true;
        };
    }

    private static boolean isValidUsername(String username) {
        if (username == null || username.length() < USERNAME_MIN || username.length() > USERNAME_MAX) {
            return false;
        }
        for (int i = 0; i < username.length(); i++) {
            char ch = username.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
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
