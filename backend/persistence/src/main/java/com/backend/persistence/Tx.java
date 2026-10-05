package com.backend.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import java.util.concurrent.ThreadLocalRandom;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs work in a transaction, retrying the two failures that are expected rather than
 * exceptional: deadlock (1213) and lock wait timeout (1205).
 *
 * A retry is safe by construction. The whole transaction is rolled back here before it is
 * tried again - InnoDB itself rolls all of it back only on a deadlock, and on a lock wait
 * timeout just the statement - so nothing is half-applied; and every write in this package
 * is idempotent, so the second attempt produces the same result as a first that had
 * succeeded (docs detailed-design/06 §6).
 */
public final class Tx {

    private static final Logger log = LoggerFactory.getLogger(Tx.class);

    private static final int MAX_ATTEMPTS = 3;
    private static final int DEADLOCK = 1213;
    private static final int LOCK_WAIT_TIMEOUT = 1205;

    @FunctionalInterface
    public interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private final DataSource dataSource;

    public Tx(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public <T> T execute(Work<T> work) throws SQLException {
        SQLException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try (Connection c = dataSource.getConnection()) {
                boolean previousAutoCommit = c.getAutoCommit();
                c.setAutoCommit(false);
                try {
                    T result = work.run(c);
                    c.commit();
                    return result;
                } catch (SQLException | RuntimeException e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(previousAutoCommit);
                }
            } catch (SQLException e) {
                if (!isRetryable(e) || attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                last = e;
                long backoffMs = (1L << (attempt - 1)) * 10
                        + ThreadLocalRandom.current().nextLong(10);   // jitter, or they collide again
                log.debug("retrying after {} (attempt {})", e.getErrorCode(), attempt);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;                       // unreachable: the loop either returns or throws
    }

    private static boolean isRetryable(SQLException e) {
        return e instanceof SQLTransactionRollbackException
                || e.getErrorCode() == DEADLOCK
                || e.getErrorCode() == LOCK_WAIT_TIMEOUT;
    }
}
