package com.backend.persistence;

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
 */
final class PrimaryDataSource implements DataSource {

    /** What one server said. */
    record Probe(boolean writable, long epoch) { }

    /** A server's heartbeat (D-58): whether it is writable, and the last stamp it has applied. */
    record Heartbeat(boolean writable, java.time.Instant at) { }

    /**
     * Each host's lag behind the primary, in seconds (D-58): the newest heartbeat a writable host
     * has is the primary's, and every other host's lag is that less its own; NaN for a host not
     * read, and for every host when no primary was.
     */
    static java.util.Map<String, Double> lags(java.util.Map<String, Heartbeat> read) {
        String primary = null;
        java.time.Instant newest = null;
        for (java.util.Map.Entry<String, Heartbeat> e : read.entrySet()) {
            Heartbeat h = e.getValue();
            if (h != null && h.writable() && (newest == null || h.at().isAfter(newest))) {
                primary = e.getKey();
                newest = h.at();
            }
        }
        java.util.Map<String, Double> out = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, Heartbeat> e : read.entrySet()) {
            Heartbeat h = e.getValue();
            if (!e.getKey().equals(primary)) {
                out.put(e.getKey(), h == null || newest == null ? Double.NaN
                        : java.time.Duration.between(h.at(), newest).toNanos() / 1e9);
            }
        }
        return out;
    }

    /** Every host's heartbeat, read with the probe's limits, and each replica's lag from them. */
    java.util.Map<String, Double> replicaLags() {
        java.util.Map<String, Heartbeat> read = new java.util.LinkedHashMap<>();
        for (String host : hosts) {
            try (Connection c = open(host, probing); Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT @@global.read_only, at FROM ha_heartbeat WHERE id = 1")) {
                read.put(host, r.next() ? new Heartbeat(r.getInt(1) == 0, r.getTimestamp(2).toInstant()) : null);
            } catch (SQLException e) {
                read.put(host, null);                    // not read: its lag is not known
            }
        }
        return lags(read);
    }

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
