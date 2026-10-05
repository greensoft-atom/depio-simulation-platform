package com.backend.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import com.backend.persistence.AccountRepository;
import com.backend.persistence.Database;
import com.backend.persistence.EconomyRepository;
import com.jredis.client.JRedisClient;
import com.jredis.embedded.JRedisEmbedded;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The daily check that every balance is what its ledger says (05 §9). Until it existed, the
 * only thing that ever compared them was the monthly restore drill.
 */
class LedgerCheckTest {

    private static final String URL = System.getenv().getOrDefault("JDBC_URL",
            "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "backend");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD",
            "backend-dev-password");

    private static Database db;
    private static JRedisEmbedded server;
    private static JRedisClient store;
    private static EconomyRepository economy;
    private static AccountRepository accounts;

    @BeforeAll
    static void setUp() {
        db = new Database(URL, USER, PASSWORD, 4);
        server = JRedisEmbedded.start();
        store = server.newClient();
        economy = new EconomyRepository(db.dataSource());
        accounts = new AccountRepository(db.dataSource());
    }

    @AfterAll
    static void tearDown() {
        server.close();
        db.close();
    }

    @BeforeEach
    void fresh() {
        db.resetForTests();
        store.sync().send("FLUSHALL");
    }

    private static long register(String name) throws SQLException {
        return accounts.register(name, name, "$argon2id$fake".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("the fleet checks once a day, and every worker reports what it found")
    void oncePerDayAcrossTheFleet() throws Exception {
        long ada = register("ada");
        economy.credit(ada, 50, EconomyRepository.REASON_MATCH_REWARD, "m1", "reward:m1:ada");
        LedgerCheck first = new LedgerCheck(economy, store, "w1");
        LedgerCheck second = new LedgerCheck(economy, store, "w2");

        assertThat(first.runIfDue()).as("nothing out of place").isZero();
        assertThat(second.runIfDue()).as("already checked today, by another worker")
                .isEqualTo(LedgerCheck.NOT_RUN);

        long[] seen = second.latest();
        assertThat(seen).as("the result is the fleet's, not the worker's").isNotNull();
        assertThat(seen[0]).isZero();
        assertThat(seen[1]).isCloseTo(System.currentTimeMillis() / 1000,
                org.assertj.core.data.Offset.offset(5L));
    }

    @Test
    @DisplayName("a balance its ledger does not explain, coins or gems, is found, and every worker reports it")
    void driftIsReported() throws Exception {
        long ada = register("ada");
        long bob = register("bob");
        economy.credit(ada, 50, EconomyRepository.REASON_MATCH_REWARD, "m1", "reward:m1:ada");
        try (Connection c = db.dataSource().getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE player SET coins = coins + 5 WHERE id = " + ada);
            st.executeUpdate("UPDATE player SET gems = 2 WHERE id = " + bob);
        }

        assertThat(new LedgerCheck(economy, store, "w1").runIfDue()).isEqualTo(2);
        assertThat(new LedgerCheck(economy, store, "w2").latest()[0]).isEqualTo(2);
    }

    @Test
    @DisplayName("a check that fails gives the day back, so the next attempt runs it")
    void failureReleasesTheDay() throws Exception {
        register("ada");
        DataSource down = (DataSource) java.lang.reflect.Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    throw new SQLException("the database is down");
                });
        LedgerCheck failing = new LedgerCheck(new EconomyRepository(down), store, "w3");

        assertThat(failing.runIfDue()).isEqualTo(LedgerCheck.FAILED);
        assertThat(failing.latest()).as("nothing reported, rather than a false all-clear").isNull();
        // Held for 23 hours, the day would simply have gone unchecked.
        assertThat(new LedgerCheck(economy, store, "w1").runIfDue()).isZero();
    }
}
