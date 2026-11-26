package com.backend.account.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;

/** The accounts (10 §3.6). Every method runs on the caller's connection, so the service composes them in one transaction. */
public final class Accounts {

    public enum Status { ACTIVE, DELETED }

    /** An account as stored. A deleted one has no username and no hash. */
    public record Account(long id, String subject, String username, byte[] passwordHash, Status status) { }

    private static final String COLUMNS = "id, subject, username, password_hash, status";

    /** @return the account made, or null when the username is taken, whatever its case */
    public Account create(Connection c, String username, byte[] passwordHash, String subject, Instant now)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO account (subject, username, password_hash, created_at) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, subject);
            ps.setString(2, username);
            ps.setBytes(3, passwordHash);
            ps.setTimestamp(4, Timestamp.from(now));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return new Account(keys.getLong(1), subject, username, passwordHash, Status.ACTIVE);
            }
        } catch (SQLIntegrityConstraintViolationException e) {
            // The name's index alone can be broken here: a subject is 128 random bits.
            if (e.getMessage() != null && e.getMessage().contains("uq_account_username")) {
                return null;
            }
            throw e;
        }
    }

    /** @return the account of that name, whatever its case, or null; a deleted one has no name to find it by */
    public Account byUsername(Connection c, String username) throws SQLException {
        return one(c, "SELECT " + COLUMNS + " FROM account WHERE username = ?", username);
    }

    public Account byId(Connection c, long id) throws SQLException {
        return one(c, "SELECT " + COLUMNS + " FROM account WHERE id = ?", id);
    }

    public Account bySubject(Connection c, String subject) throws SQLException {
        return one(c, "SELECT " + COLUMNS + " FROM account WHERE subject = ?", subject);
    }

    /** The account, its row locked until the transaction ends: for a change that must not race another. */
    public Account lock(Connection c, long id) throws SQLException {
        return one(c, "SELECT " + COLUMNS + " FROM account WHERE id = ? FOR UPDATE", id);
    }

    public void touchLogin(Connection c, long id, Instant now) throws SQLException {
        update(c, "UPDATE account SET last_login_at = ? WHERE id = ?", Timestamp.from(now), id);
    }

    /** @return whether the hash was still {@code old}, and so replaced */
    public boolean replaceHash(Connection c, long id, byte[] old, byte[] fresh) throws SQLException {
        return update(c, "UPDATE account SET password_hash = ? WHERE id = ? AND password_hash = ? AND status = 0",
                fresh, id, old) == 1;
    }

    public void changePassword(Connection c, long id, byte[] hash, Instant now) throws SQLException {
        update(c, "UPDATE account SET password_hash = ?, password_changed_at = ? WHERE id = ?", hash, Timestamp.from(now), id);
    }

    /** The name and the hash cleared, the row and its subject kept: the name free again, the subject never reused. */
    public void delete(Connection c, long id, Instant now) throws SQLException {
        update(c, "UPDATE account SET username = NULL, password_hash = NULL, status = 1, deleted_at = ? WHERE id = ?",
                Timestamp.from(now), id);
    }

    private static Account one(Connection c, String sql, Object key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, key);
            try (ResultSet r = ps.executeQuery()) {
                if (!r.next()) {
                    return null;
                }
                return new Account(r.getLong(1), r.getString(2), r.getString(3), r.getBytes(4),
                        r.getInt(5) == 0 ? Status.ACTIVE : Status.DELETED);
            }
        }
    }

    private static int update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps.executeUpdate();
        }
    }
}
