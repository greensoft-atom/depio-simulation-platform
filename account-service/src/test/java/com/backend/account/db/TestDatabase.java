package com.backend.account.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** The tests' database, {@code account_test} on the development MySQL unless {@code ACCOUNT_JDBC_URL} names another. */
public final class TestDatabase {

    public static final String URL = System.getenv().getOrDefault("ACCOUNT_JDBC_URL", DatabaseSettings.TEST_URL);

    /** Migrated from nothing: the tests empty it, so its name must end in {@code _test}. */
    public static Database open() {
        String path = URL.substring(URL.lastIndexOf('/') + 1);
        String name = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (!name.endsWith("_test")) {
            throw new IllegalStateException("the tests empty their database, and " + name + " is not named *_test");
        }
        Database db = new Database(URL, DatabaseSettings.DEV_USER, DatabaseSettings.DEV_PASSWORD, 4);
        db.resetForTests();
        return db;
    }

    /** Every table dropped: a database as one before its first migration. */
    public static void dropEverything() {
        try (Database db = open()) {
            db.clean();
        }
    }

    /** Every row of every table but the epoch's, children first. */
    public static void empty(Database db) throws SQLException {
        try (Connection c = db.dataSource().getConnection(); Statement s = c.createStatement()) {
            for (String t : new String[] {"refresh_token", "attempt", "event_outbox", "account"}) {
                s.executeUpdate("DELETE FROM " + t);
            }
        }
    }

    private TestDatabase() {
    }
}
