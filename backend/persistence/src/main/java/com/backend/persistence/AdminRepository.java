package com.backend.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;

import javax.sql.DataSource;

/**
 * What the admin API changes, and its audit (docs detailed-design/04-platform-services.md §10,
 * D-30): every call is a row in {@code admin_audit}, refusals included, and a change is written
 * with its row in one transaction, so neither is ever there without the other.
 */
public final class AdminRepository {

    /** The longest request an audit row keeps: an admin's body is a reason, not a document. */
    static final int REQUEST_LIMIT = 1024;
    /** {@code admin_audit.target}'s width: a longer one, a path an unauthorised caller padded, failed the insert (D-30). */
    static final int TARGET_LIMIT = 128;

    public enum Change { DONE, NO_SUCH_PLAYER }

    private final Tx tx;

    public AdminRepository(DataSource dataSource) {
        this.tx = new Tx(dataSource);
    }

    /**
     * Sets an account's status, and until when for a suspension (null for none), auditing it:
     * {@code outcome} when the account exists, {@code no_such_player} when it does not.
     */
    public Change setStatus(long playerId, AccountRepository.Status status, Long untilMillis,
                            String action, String request, String outcome) throws SQLException {
        return tx.execute(c -> {
            int changed;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE account SET status = ?, banned_until = ? WHERE id = ?")) {
                ps.setInt(1, status.ordinal());
                ps.setTimestamp(2, untilMillis == null ? null : new Timestamp(untilMillis));
                ps.setLong(3, playerId);
                changed = ps.executeUpdate();
            }
            insert(c, action, Long.toString(playerId), request, changed == 0 ? "no_such_player" : outcome);
            return changed == 0 ? Change.NO_SUCH_PLAYER : Change.DONE;
        });
    }

    /**
     * A paid order refunded and the call audited, in one transaction (04 §8, D-68): {@code refunded},
     * {@code refused_not_paid}, or {@code refused_no_such_order}.
     *
     * @return null for no such order; not {@code now} for one that is not paid
     */
    public PaymentRepository.Refunded refund(String orderId, java.time.Instant now, String request) throws SQLException {
        return tx.execute(c -> {
            PaymentRepository.Refunded r = PaymentRepository.refund(c, orderId, now);
            insert(c, "refund", orderId, request, r == null ? "refused_no_such_order" : r.now() ? "refunded" : "refused_not_paid");
            return r;
        });
    }

    /** A player's refund debt cleared and the call audited, in one transaction (D-68). @return the gems of debt cleared */
    public long clearDebt(long playerId, String request) throws SQLException {
        return tx.execute(c -> {
            long cleared = PaymentRepository.clearDebt(c, playerId);
            insert(c, "refund-debt", Long.toString(playerId), request, "cleared");
            return cleared;
        });
    }

    /** A call that changed nothing here: a listing, or a refusal. */
    public void audit(String action, String target, String request, String outcome) throws SQLException {
        tx.execute(c -> {
            insert(c, action, target, request, outcome);
            return null;
        });
    }

    private static void insert(Connection c, String action, String target, String request, String outcome)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO admin_audit (at, action, target, request, outcome) VALUES (NOW(3), ?, ?, ?, ?)")) {
            ps.setString(1, action);
            ps.setString(2, target == null || target.length() <= TARGET_LIMIT ? target : target.substring(0, TARGET_LIMIT));
            ps.setString(3, request == null || request.length() <= REQUEST_LIMIT ? request : request.substring(0, REQUEST_LIMIT));
            ps.setString(4, outcome);
            ps.executeUpdate();
        }
    }
}
