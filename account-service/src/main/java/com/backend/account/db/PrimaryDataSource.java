package com.backend.account.db;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * Connections to MySQL's primary when a process is given the primary and its replica
 * (docs detailed-design/06 §10, D-35): each new connection asks every host whether it is
 * writable and at which epoch, and goes to the writable one with the highest epoch, never below
 * the highest this process has seen on any host. The pool calls it only when it opens a
 * connection, which after a promotion is when its old ones have broken.
 *
 * <p>The backend's com.backend.persistence.PrimaryDataSource, copied without the heartbeat the backend's watch
 * reads: the account service builds on nothing of the backend's (D-117). Its database has its own {@code ha_epoch},
 * which a promotion raises with the backend's.
 */
final class PrimaryDataSource implements DataSource {

    /** What one server said. */
    record Probe(boolean writable, long epoch) { }

    interface Prober {
        Probe probe(String host) throws SQLException;
    }

    private static final int NO_SUCH_TABLE = 1146;

    private final List<String> hosts;
    private final String tail;
    private final Properties properties;
    private final Properties probing;
    private final int probeMillis;
    private final AtomicLong seen = new AtomicLong(-1);
    private int loginTimeout;

    /**
     * @param jdbcUrl {@code jdbc:mysql://host:port,host:port/database?options}
     * @param properties the driver's properties, user and password among them
     * @param probeMillis the probe's limit, connect and socket both: a frozen server accepts TCP,
     *                    its kernel answering, and never greets, so a connect limit alone would
     *                    leave each new connection waiting on the socket's (06 §7)
     */
    PrimaryDataSource(String jdbcUrl, Properties properties, int probeMillis) {
        this.hosts = hosts(jdbcUrl);
        String rest = jdbcUrl.substring("jdbc:mysql://".length());
        int slash = rest.indexOf('/');
        this.tail = slash < 0 ? "/" : rest.substring(slash);
        this.properties = properties;
        this.probeMillis = probeMillis;
        this.probing = new Properties();
        probing.putAll(properties);
        probing.setProperty("connectTimeout", Integer.toString(probeMillis));
        probing.setProperty("socketTimeout", Integer.toString(probeMillis));
    }

    /** The same, its connections' statements not cut: for migrations (06 §7). */
    PrimaryDataSource withoutSocketLimit() {
        Properties p = new Properties();
        p.putAll(properties);
        p.setProperty("socketTimeout", "0");
        return new PrimaryDataSource("jdbc:mysql://" + String.join(",", hosts) + tail, p, probeMillis);
    }

    /** The hosts a URL names, as {@code host:port}, with MySQL's port where none is given. */
    static List<String> hosts(String jdbcUrl) {
        String rest = jdbcUrl.substring("jdbc:mysql://".length());
        int end = rest.indexOf('/');
        List<String> out = new ArrayList<>();
        for (String h : (end < 0 ? rest : rest.substring(0, end)).split(",")) {
            out.add(h.contains(":") ? h : h + ":3306");
        }
        return out;
    }

    /**
     * The host to use: the writable one with the highest epoch, if that epoch is not below the
     * highest seen, which every answer raises.
     */
    static String choose(List<String> hosts, Prober prober, AtomicLong seen) throws SQLException {
        String best = null;
        long bestEpoch = -1;
        long highest = seen.get();
        List<String> failures = new ArrayList<>();
        for (String h : hosts) {
            Probe p;
            try {
                p = prober.probe(h);
            } catch (SQLException e) {
                failures.add(h + ": " + e.getMessage());
                continue;
            }
            highest = Math.max(highest, p.epoch());
            if (p.writable() && p.epoch() > bestEpoch) {
                best = h;
                bestEpoch = p.epoch();
            }
        }
        seen.accumulateAndGet(highest, Math::max);
        if (best == null) {
            throw new SQLException("no writable primary among " + hosts + (failures.isEmpty() ? "" : " " + failures));
        }
        if (bestEpoch < highest) {
            throw new SQLException(best + " is writable at epoch " + bestEpoch + ", but epoch " + highest
                    + " has been seen: it is an old primary, not used");
        }
        return best;
    }

    private Connection open(String host, Properties with) throws SQLException {
        return DriverManager.getConnection("jdbc:mysql://" + host + tail, with);
    }

    private Probe probe(String host) throws SQLException {
        try (Connection c = open(host, probing); Statement s = c.createStatement()) {
            boolean writable;
            try (ResultSet r = s.executeQuery("SELECT @@global.read_only")) {   // on whenever super_read_only is
                r.next();
                writable = !r.getBoolean(1);
            }
            long epoch = 0;                      // before V8, or before the first migration: never promoted
            try (ResultSet r = s.executeQuery("SELECT epoch FROM ha_epoch WHERE id = 1")) {
                if (r.next()) {
                    epoch = r.getLong(1);
                }
            } catch (SQLException e) {
                if (e.getErrorCode() != NO_SUCH_TABLE) {
                    throw e;
                }
            }
            return new Probe(writable, epoch);
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        return open(choose(hosts, this::probe, seen), properties);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLFeatureNotSupportedException("the user and password are the pool's");
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
    }

    @Override
    public void setLoginTimeout(int seconds) {
        loginTimeout = seconds;
    }

    @Override
    public int getLoginTimeout() {
        return loginTimeout;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("not a wrapper for " + iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
