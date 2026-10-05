package com.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The growing tables' rows, as every worker reports them (docs 06 §9, D-72). */
class TableSizesTest {

    private static final String URL = System.getenv().getOrDefault("JDBC_URL",
            "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "backend");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD",
            "backend-dev-password");

    private static Database db;

    @BeforeAll
    static void setUp() {
        db = new Database(URL, USER, PASSWORD, 2);
        db.resetForTests();
    }

    @AfterAll
    static void tearDown() {
        db.close();
    }

    @Test
    @DisplayName("each growing table named, with InnoDB's estimate of its rows, read fresh rather than as cached for a day")
    void rows() throws SQLException {
        AccountRepository accounts = new AccountRepository(db.dataSource());
        for (int i = 0; i < 40; i++) {
            accounts.register("ts" + i, "ts" + i, "$argon2id$fake".getBytes(StandardCharsets.UTF_8));
        }
        TableSizes sizes = new TableSizes(db.dataSource());
        assertThat(sizes.rows()).containsOnlyKeys(TableSizes.WATCHED).allSatisfy((table, rows) ->
                assertThat(rows).as(table).isNotNegative());
        try (Connection c = db.dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("ANALYZE TABLE player");           // what InnoDB's statistics would do in time
        }
        assertThat(TableSizes.WATCHED).contains("ledger", "match_player", "player");
        assertThat(sizes.rows().get("player")).as("an estimate, fresh after the statistics change").isBetween(30L, 50L);

        RecordingDataSource recorded = new RecordingDataSource(db.dataSource());
        new TableSizes(recorded.dataSource).rows();
        assertThat(recorded.executed.get(0)).as("the day's cache set aside first, on the connection that reads")
                .startsWith("SET SESSION information_schema_stats_expiry = 0");
        assertThat(recorded.executed.get(1)).contains("FROM information_schema.tables");
    }
}
