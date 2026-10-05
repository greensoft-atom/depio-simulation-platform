package com.backend.persistence;

import java.util.Properties;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.flywaydb.core.Flyway;

/**
 * The connection pool and the schema.
 *
 * Pool size is chosen for the database, not the application: making it large does not make
 * MySQL faster, it makes it thrash. Java 21 invites the opposite mistake, so it is worth
 * saying plainly — virtual threads do not change how many queries a database can run at
 * once, and 10 000 virtual threads waiting on a pool of 16 is the correct arrangement
 * (docs detailed-design/06 §7).
 */
public final class Database implements AutoCloseable {

    private final HikariDataSource dataSource;
    private final HikariConfig config;

    /** Each call's limits (06 §7, O-9). */
    record Limits(int connectMillis, int socketMillis, int probeMillis, int lockWaitSeconds) {
        static final Limits DEFAULT = new Limits(2_000, 30_000, 2_000, 20);
    }

    public Database(String jdbcUrl, String user, String password, int poolSize) {
        this(jdbcUrl, user, password, poolSize, Limits.DEFAULT);
    }

    Database(String jdbcUrl, String user, String password, int poolSize, Limits limits) {
        HikariConfig cfg = new HikariConfig();
        cfg.setMaximumPoolSize(poolSize);
        cfg.setMinimumIdle(Math.min(4, poolSize));
        cfg.setConnectionTimeout(3_000);          // fail fast; the caller can retry
        cfg.setMaxLifetime(1_700_000);            // under MySQL's wait_timeout
        cfg.setPoolName("backend");
        // Server-side prepared statements: the point of prepared statements is that the
        // server keeps the plan, and the driver does not do that by default.
        cfg.addDataSourceProperty("cachePrepStmts", "true");
        cfg.addDataSourceProperty("prepStmtCacheSize", "256");
        cfg.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        cfg.addDataSourceProperty("useServerPrepStmts", "true");
        // Every time stored is UTC, on any machine. A DATETIME carries no zone, and the
        // driver's default is the process's own local time: a match's end then meant
        // something different on each machine, and in a zone with summer time one hour a
        // year was written twice. Here rather than in the URL, where it could be left out.
        // And forced onto the session too: the driver's zone covers only what it binds and
        // reads, while CURRENT_TIMESTAMP, the default of every created_at, is the session's.
        // On a server left on local time a ledger row came out two hours after the match
        // it paid for. An offset rather than a name, since a server need not have its zone
        // tables loaded to understand one.
        cfg.addDataSourceProperty("connectionTimeZone", "+00:00");
        cfg.addDataSourceProperty("forceConnectionTimeZoneToSession", "true");
        // A limit on every call (06 §7, O-9): the driver's default is to wait on a socket for as
        // long as TCP does, hours for a primary whose machine vanished. And a lock wait gives up
        // before the socket does, as 1205, which Tx retries.
        cfg.addDataSourceProperty("connectTimeout", Integer.toString(limits.connectMillis()));
        cfg.addDataSourceProperty("socketTimeout", Integer.toString(limits.socketMillis()));
        cfg.addDataSourceProperty("sessionVariables", "innodb_lock_wait_timeout=" + limits.lockWaitSeconds());
        if (PrimaryDataSource.hosts(jdbcUrl).size() > 1) {
            // The primary and its replica: each new connection goes to the writable one with the
            // highest epoch (06 §10, D-35). The pool's driver properties become the source's.
            Properties p = new Properties();
            p.putAll(cfg.getDataSourceProperties());
            p.setProperty("user", user);
            p.setProperty("password", password);
            cfg.setDataSource(new PrimaryDataSource(jdbcUrl, p, limits.probeMillis()));
        } else {
            cfg.setJdbcUrl(jdbcUrl);
            cfg.setUsername(user);
            cfg.setPassword(password);
        }
        this.config = cfg;
        this.dataSource = new HikariDataSource(cfg);
    }

    public DataSource dataSource() {
        return dataSource;
    }

    /** Stamps the heartbeat on the primary, which replication carries to the replica (D-58). */
    public void heartbeat() throws java.sql.SQLException {
        try (java.sql.Connection c = dataSource.getConnection(); java.sql.Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE ha_heartbeat SET at = NOW(6) WHERE id = 1");
        }
    }

    /** Each replica's lag behind the primary, in seconds, by its host; none for a single host (D-58). */
    public java.util.Map<String, Double> replicaLags() {
        return config.getDataSource() instanceof PrimaryDataSource primary ? primary.replicaLags() : java.util.Map.of();
    }

    /**
     * Applies pending migrations. Called at every platform and worker start; Flyway serialises
     * starts that race with a lock in the database.
     */
    public void migrate() {
        migrate("classpath:db/migration");
    }

    void migrate(String location) {
        try (HikariDataSource migrations = migrationDataSource()) {
            Flyway.configure()
                    .dataSource(migrations)
                    .locations(location)
                    .load()
                    .migrate();
        }
    }

    /** The connection migrations run on: one of its own, its statements not cut (06 §7). */
    HikariDataSource migrationDataSource() {
        HikariConfig m = new HikariConfig();
        m.setMaximumPoolSize(1);
        m.setMinimumIdle(0);
        m.setPoolName("backend-migrations");
        if (config.getDataSource() instanceof PrimaryDataSource primary) {
            m.setDataSource(primary.withoutSocketLimit());
        } else {
            // A copy: the pool's own properties are not to be touched.
            Properties p = new Properties();
            p.putAll(config.getDataSourceProperties());
            p.setProperty("socketTimeout", "0");
            m.setDataSourceProperties(p);
            m.setJdbcUrl(config.getJdbcUrl());
            m.setUsername(config.getUsername());
            m.setPassword(config.getPassword());
        }
        return new HikariDataSource(m);
    }

    /** Test helper: drops everything and re-migrates. Never call this against a real database. */
    public void resetForTests() {
        clean();
        migrate();
    }

    /** Test helper: drops everything. */
    void clean() {
        try (HikariDataSource migrations = migrationDataSource()) {
            Flyway.configure()
                    .dataSource(migrations)
                    .locations("classpath:db/migration")
                    .cleanDisabled(false)
                    .load()
                    .clean();
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
