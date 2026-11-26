package com.backend.account;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

import javax.sql.DataSource;

import com.backend.account.db.Accounts;
import com.backend.account.db.Outbox;
import com.backend.account.db.RefreshTokens;
import com.backend.account.db.Throttle;
import com.backend.account.db.Tx;
import com.backend.account.token.BearerCheck;
import com.backend.account.token.TokenIssuer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the account service does (10 §3): sign-up, login, refresh, logout, a password's change, a deletion.
 *
 * <p>In the platform's order (04 §1): the body's rules first, which cost nothing; then a place in the hasher's line,
 * so an attempt turned away for load counts against nobody; then the limits; then the hash; then the change, in one
 * transaction with its refresh token and its event. An unknown username is checked against a decoy hash, so it costs
 * what a wrong password costs, and both are {@code INVALID_CREDENTIALS}.
 *
 * <p>Every call blocks, on MySQL and on Argon2: it runs on the HTTP server's virtual threads. A {@link SQLException}
 * is the caller's to answer 503.
 */
public final class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    public enum Outcome { OK, INVALID_USERNAME, INVALID_PASSWORD, USERNAME_TAKEN, INVALID_CREDENTIALS, INVALID_GRANT,
        INVALID_TOKEN, TOO_MANY_ATTEMPTS, BUSY }

    /** A token answer's content (RFC 6749 §5.1). */
    public record Tokens(String accessToken, String refreshToken, long expiresIn, String accountId) { }

    /** An outcome, its tokens when it gives some, and how long to wait when it is a limit's or the line's. */
    public record Result(Outcome outcome, Tokens tokens, int retryAfterSeconds) {
        static Result of(Outcome outcome) {
            return new Result(outcome, null, 0);
        }
    }

    private final DataSource dataSource;
    private final Tx tx;
    private final PasswordHasher hasher;
    private final Throttle throttle;
    private final TokenIssuer issuer;
    private final BearerCheck bearers;
    private final Outbox outbox;
    private final Runnable eventAdded;
    private final Clock clock;
    private final Accounts accounts = new Accounts();
    private final RefreshTokens refreshTokens = new RefreshTokens();
    /** A real hash of a password nobody has: an unknown username is checked against it, for the time it takes. */
    private final byte[] decoy;

    public AccountService(DataSource dataSource, PasswordHasher hasher, Throttle throttle, TokenIssuer issuer,
                          BearerCheck bearers, Outbox outbox, Runnable eventAdded, Clock clock) {
        this.dataSource = dataSource;
        this.tx = new Tx(dataSource);
        this.hasher = hasher;
        this.throttle = throttle;
        this.issuer = issuer;
        this.bearers = bearers;
        this.outbox = outbox;
        this.eventAdded = eventAdded;
        this.clock = clock;
        this.decoy = hasher.hash("decoy-for-constant-time-login".toCharArray());
    }

    public Result signUp(String address, String username, char[] password) throws SQLException {
        if (!CredentialRules.validUsername(username)) {
            return Result.of(Outcome.INVALID_USERNAME);
        }
        if (!CredentialRules.validPassword(password)) {
            return Result.of(Outcome.INVALID_PASSWORD);
        }
        try (PasswordHasher.Admission place = hasher.admit()) {
            if (place == null) {
                return busy();
            }
            Throttle.Verdict v = throttle.attempt(address, null);
            if (!v.allowed()) {
                return limited(v);
            }
            byte[] hash = hasher.hash(password);
            Instant now = clock.instant();
            Result made = tx.execute(c -> {
                Accounts.Account a = accounts.create(c, username, hash, TokenIssuer.randomId(), now);
                if (a == null) {
                    return Result.of(Outcome.USERNAME_TAKEN);
                }
                return ok(a, refreshTokens.issue(c, a.id(), now, now), now, now);
            });
            if (made.outcome() == Outcome.OK) {
                log.info("account made: {}", made.tokens().accountId());
            }
            return made;
        }
    }

    public Result logIn(String address, String username, char[] password) throws SQLException {
        try (PasswordHasher.Admission place = hasher.admit()) {
            if (place == null) {
                return busy();
            }
            // A name no account can have is counted against its address alone, and refused without a lookup: the
            // rules are public, so refusing early tells nothing.
            String key = CredentialRules.usernameKey(username);
            Throttle.Verdict v = throttle.attempt(address, key);
            if (!v.allowed()) {
                return limited(v);
            }
            if (key == null) {
                return Result.of(Outcome.INVALID_CREDENTIALS);
            }
            char[] given = password == null ? new char[0] : password;
            Accounts.Account a;
            try (var c = dataSource.getConnection()) {
                a = accounts.byUsername(c, username);
            }
            if (a == null) {
                hasher.verify(given, decoy);
                return Result.of(Outcome.INVALID_CREDENTIALS);
            }
            if (!hasher.verify(given, a.passwordHash())) {
                return Result.of(Outcome.INVALID_CREDENTIALS);
            }
            if (hasher.needsRehash(a.passwordHash())) {
                rehash(a, given);
            }
            Instant now = clock.instant();
            return tx.execute(c -> {
                accounts.touchLogin(c, a.id(), now);
                return ok(a, refreshTokens.issue(c, a.id(), now, now), now, now);
            });
        }
    }

    public Result refresh(String refreshToken) throws SQLException {
        Instant now = clock.instant();
        return tx.execute(c -> {
            RefreshTokens.Rotation r = refreshTokens.rotate(c, refreshToken, now);
            if (r.outcome() != RefreshTokens.Outcome.ROTATED) {
                return Result.of(Outcome.INVALID_GRANT);    // committed: a reuse's revocation with it
            }
            // Read, not locked: a password's change locks the account, then its tokens, and this holds a token's
            // row; a deletion revokes every token in its own transaction, so a live token's account is never one.
            return ok(accounts.byId(c, r.accountId()), r.token(), r.authTime(), now);
        });
    }

    public void logOut(String refreshToken) throws SQLException {
        Instant now = clock.instant();
        tx.execute(c -> {
            refreshTokens.revokeFamily(c, refreshToken, now);
            return null;
        });
    }

    /** Every family of the account revoked and the backend told, the current device given a new pair. */
    public Result changePassword(String address, String bearer, char[] current, char[] fresh) throws SQLException {
        BearerCheck.Bearer who = bearers.check(bearer);
        if (who == null) {
            return Result.of(Outcome.INVALID_TOKEN);
        }
        if (!CredentialRules.validPassword(fresh)) {
            return Result.of(Outcome.INVALID_PASSWORD);
        }
        try (PasswordHasher.Admission place = hasher.admit()) {
            if (place == null) {
                return busy();
            }
            Accounts.Account a = active(who.subject());
            if (a == null) {
                return Result.of(Outcome.INVALID_TOKEN);
            }
            Throttle.Verdict v = throttle.attempt(address, CredentialRules.usernameKey(a.username()));
            if (!v.allowed()) {
                return limited(v);
            }
            if (!hasher.verify(current == null ? new char[0] : current, a.passwordHash())) {
                return Result.of(Outcome.INVALID_CREDENTIALS);
            }
            byte[] hash = hasher.hash(fresh);
            Instant now = clock.instant();
            Result changed = tx.execute(c -> {
                Accounts.Account locked = accounts.lock(c, a.id());
                if (locked == null || locked.status() != Accounts.Status.ACTIVE) {
                    return Result.of(Outcome.INVALID_TOKEN);
                }
                accounts.changePassword(c, a.id(), hash, now);
                refreshTokens.revokeAll(c, a.id(), now);
                TokenIssuer.Event e = issuer.event(TokenIssuer.SESSIONS_REVOKED, a.subject(), now);
                outbox.add(c, e.jti(), "sessions-revoked", e.token(), now);
                return ok(locked, refreshTokens.issue(c, a.id(), now, now), now, now);
            });
            if (changed.outcome() == Outcome.OK) {
                log.info("account {} changed its password: every session revoked", a.subject());
                eventAdded.run();
            }
            return changed;
        }
    }

    /** The name and the hash cleared, every family revoked, the backend told; a deleted account again changes nothing. */
    public Result delete(String address, String bearer, char[] password) throws SQLException {
        BearerCheck.Bearer who = bearers.check(bearer);
        if (who == null) {
            return Result.of(Outcome.INVALID_TOKEN);
        }
        try (PasswordHasher.Admission place = hasher.admit()) {
            if (place == null) {
                return busy();
            }
            Accounts.Account a;
            try (var c = dataSource.getConnection()) {
                a = accounts.bySubject(c, who.subject());
            }
            if (a == null) {
                return Result.of(Outcome.INVALID_TOKEN);
            }
            if (a.status() == Accounts.Status.DELETED) {
                return Result.of(Outcome.OK);
            }
            Throttle.Verdict v = throttle.attempt(address, CredentialRules.usernameKey(a.username()));
            if (!v.allowed()) {
                return limited(v);
            }
            if (!hasher.verify(password == null ? new char[0] : password, a.passwordHash())) {
                return Result.of(Outcome.INVALID_CREDENTIALS);
            }
            Instant now = clock.instant();
            tx.execute(c -> {
                Accounts.Account locked = accounts.lock(c, a.id());
                if (locked.status() == Accounts.Status.DELETED) {
                    return null;
                }
                accounts.delete(c, a.id(), now);
                refreshTokens.revokeAll(c, a.id(), now);
                TokenIssuer.Event e = issuer.event(TokenIssuer.DELETED, a.subject(), now);
                outbox.add(c, e.jti(), "deleted", e.token(), now);
                return null;
            });
            log.info("account {} deleted", a.subject());
            eventAdded.run();
            return Result.of(Outcome.OK);
        }
    }

    /** How many requests hold or wait for a place in the hasher's line. */
    public int hasherLine() {
        return hasher.inLine();
    }

    private Accounts.Account active(String subject) throws SQLException {
        try (var c = dataSource.getConnection()) {
            Accounts.Account a = accounts.bySubject(c, subject);
            return a == null || a.status() != Accounts.Status.ACTIVE ? null : a;
        }
    }

    private Result ok(Accounts.Account a, String refreshToken, Instant authTime, Instant now) {
        String access = issuer.accessToken(a.subject(), a.username(), authTime, now);
        return new Result(Outcome.OK, new Tokens(access, refreshToken, TokenIssuer.ACCESS_LIFETIME.toSeconds(), a.subject()), 0);
    }

    /**
     * Made again under the current policy, now that the password is known; only if the stored hash is still the one
     * checked, so a change made meanwhile is never put back (04 §1). A failure costs the upgrade alone.
     */
    private void rehash(Accounts.Account a, char[] password) {
        try {
            byte[] fresh = hasher.hash(password);
            tx.execute(c -> accounts.replaceHash(c, a.id(), a.passwordHash(), fresh));
        } catch (SQLException e) {
            log.warn("could not rehash the password of {}: {}", a.subject(), e.toString());
        }
    }

    /** The line full: come back in one to five seconds, spread so those turned away do not return together (04 §1). */
    private static Result busy() {
        return new Result(Outcome.BUSY, null, 1 + ThreadLocalRandom.current().nextInt(5));
    }

    private static Result limited(Throttle.Verdict v) {
        return new Result(Outcome.TOO_MANY_ATTEMPTS, null, v.retryAfterSeconds());
    }
}
