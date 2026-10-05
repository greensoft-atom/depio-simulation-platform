package com.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The season pass (docs 04 §8, revenue (b), D-69), against a real MySQL. */
class SeasonPassRepositoryTest {

    private static final String URL = System.getenv().getOrDefault("JDBC_URL",
            "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "backend");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD",
            "backend-dev-password");

    private static Database db;
    private static AccountRepository accounts;
    private static EconomyRepository economy;
    private static SeasonPassRepository passes;

    @BeforeAll
    static void setUp() {
        db = new Database(URL, USER, PASSWORD, 4);
        accounts = new AccountRepository(db.dataSource());
        economy = new EconomyRepository(db.dataSource());
        passes = new SeasonPassRepository(db.dataSource());
    }

    @AfterAll
    static void tearDown() {
        db.close();
    }

    @BeforeEach
    void fresh() {
        db.resetForTests();
    }

    private static long player(String name) throws SQLException {
        return accounts.register(name, name, "$argon2id$fake".getBytes(StandardCharsets.UTF_8));
    }

    private static long count(String query, Object... args) throws SQLException {
        try (Connection c = db.dataSource().getConnection(); PreparedStatement ps = c.prepareStatement(query)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void sql(String statement, Object... args) throws SQLException {
        try (Connection c = db.dataSource().getConnection(); PreparedStatement ps = c.prepareStatement(statement)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    /** Points earned as a result's transaction earns them: the player locked, the season being played. */
    private static SeasonPassRepository.Earned earn(long player, int points) throws SQLException {
        try (Connection c = db.dataSource().getConnection()) {
            c.setAutoCommit(false);
            sql(c, "SELECT id FROM player WHERE id = ? FOR UPDATE", player);
            SeasonPassRepository.Earned earned = SeasonPassRepository.earn(c, player,
                    SeasonPassRepository.seasonBeingPlayed(c), points);
            c.commit();
            return earned;
        }
    }

    private static void sql(Connection c, String query, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(query)) {
            ps.setLong(1, id);
            ps.executeQuery().close();
        }
    }

    /** Gems given through the ledger's one path, as a reward would. */
    private static void gems(long player, int gems) throws SQLException {
        try (Connection c = db.dataSource().getConnection()) {
            c.setAutoCommit(false);
            EconomyRepository.move(c, player, EconomyRepository.CURRENCY_GEMS, gems, EconomyRepository.REASON_MILESTONE,
                    "test", "test:" + player);
            c.commit();
        }
    }

    private static int held(long player, String itemId) throws SQLException {
        return economy.inventory(player).stream().filter(h -> h.itemId().equals(itemId))
                .mapToInt(EconomyRepository.Holding::qty).sum();
    }

    @Test
    @DisplayName("no open season is said for what it is, not read off an empty row as a failure to retry for ever (D-50)")
    void noOpenSeasonIsSaid() throws Exception {
        sql("UPDATE season SET placed_at = UTC_TIMESTAMP(3)");
        try (Connection c = db.dataSource().getConnection()) {
            assertThatThrownBy(() -> SeasonPassRepository.seasonBeingPlayed(c)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no season is being played");
        }
    }

    @Test
    @DisplayName("the table: forty tiers of 250; each reward coins or gems, never both, as one ledger key pays a tier's; never coins on the premium track (D-67)")
    void theTable() {
        assertThat(List.of(SeasonPass.TIERS, SeasonPass.TIER_POINTS, SeasonPass.PREMIUM_GEMS)).containsExactly(40, 250, 500);
        assertThat(List.of(SeasonPass.tierOf(249), SeasonPass.tierOf(250), SeasonPass.tierOf(10_000), SeasonPass.tierOf(99_999)))
                .containsExactly(0, 1, 40, 40);
        for (int t = 1; t <= SeasonPass.TIERS; t++) {
            assertThat(SeasonPass.free(t).coins() > 0 && SeasonPass.free(t).gems() > 0).as("free " + t).isFalse();
            assertThat(SeasonPass.premium(t).coins()).as("premium " + t).isZero();
        }
        assertThat(SeasonPass.items()).containsExactlyInAnyOrder(SeasonPass.premium(5).itemId(), SeasonPass.premium(10).itemId());
    }

    @Test
    @DisplayName("each tier is paid as its points cross it, once, to the 40th: the free track's coins and gems through the ledger (D-69)")
    void freeTiersPaidOnCrossing() throws SQLException {
        long ada = player("ada");
        SeasonPassRepository.Earned e = earn(ada, 240);
        assertThat(List.of(e.points(), e.tier(), e.coins(), e.gems())).containsExactly(240, 0, 0L, 0);
        e = earn(ada, 10);
        assertThat(List.of(e.points(), e.tier(), e.coins(), e.gems())).as("250: the first tier").containsExactly(10, 1, 150L, 0);
        e = earn(ada, 1_000);
        assertThat(List.of(e.tier(), e.coins(), e.gems())).as("tiers 2 to 5: coins, then the fifth's gems")
                .containsExactly(5, 450L, 5);
        assertThat(e.items()).isEmpty();
        assertThat(List.of(economy.wallet(ada).coins(), economy.wallet(ada).gems())).containsExactly(600L, 5L);
        assertThat(count("SELECT COUNT(*) FROM ledger WHERE player_id = ? AND reason = ?", ada,
                EconomyRepository.REASON_PASS_TIER)).isEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM ledger WHERE idem_key = ?", "pass:1:free:5:" + ada)).isEqualTo(1);

        e = earn(ada, 100_000);
        assertThat(List.of(e.tier(), e.coins(), e.gems())).as("to the last").containsExactly(40, 28 * 150L, 35);
        e = earn(ada, 250);
        assertThat(List.of(e.tier(), e.coins(), e.gems())).as("nothing past the last").containsExactly(40, 0L, 0);
        assertThat(List.of(economy.wallet(ada).coins(), economy.wallet(ada).gems())).as("the free track whole")
                .containsExactly(4_800L, 40L);
        SeasonPassRepository.Pass pass = passes.of(ada);
        assertThat(List.of(pass.season(), pass.points(), pass.tier(), pass.premium())).containsExactly(1, 101_500L, 40, false);
        assertThat(economy.reconcile(10).count()).as("every balance its ledger's").isZero();
    }

    @Test
    @DisplayName("premium, 500 gems, pays the premium tiers reached at once, a boost every fifth; once a season; refused short of gems or once the season has ended")
    void premium() throws SQLException {
        Instant now = Instant.now();
        long bob = player("bob");
        gems(bob, 520);
        earn(bob, 1_250);                                                  // tier 5: 600 coins, 5 gems
        SeasonPassRepository.Premium bought = passes.buyPremium(bob, now);
        assertThat(List.of(bought.result(), bought.gems(), bought.pass().premium())).as("525 less 500, and 5 tiers' 75")
                .containsExactly(SeasonPassRepository.Bought.BOUGHT, 100L, true);
        assertThat(held(bob, "boost_xp_hour")).as("the fifth's boost").isEqualTo(1);
        SeasonPassRepository.Premium again = passes.buyPremium(bob, now);
        assertThat(List.of(again.result(), again.gems())).as("once").containsExactly(SeasonPassRepository.Bought.ALREADY_BOUGHT, 100L);

        SeasonPassRepository.Earned e = earn(bob, 1_250);                  // tier 10
        assertThat(List.of(e.tier(), e.coins(), e.gems())).as("free 6 to 10, and premium 6 to 10")
                .containsExactly(10, 600L, 5 + 75);
        assertThat(e.items()).containsExactly("boost_coins_hour");
        assertThat(economy.wallet(bob).gems()).isEqualTo(180);
        assertThat(count("SELECT COUNT(*) FROM ledger WHERE idem_key = ? AND delta = -500 AND reason = ?",
                "pass:1:" + bob, EconomyRepository.REASON_PURCHASE)).isEqualTo(1);
        assertThat(count("SELECT premium_paid FROM season_pass WHERE player_id = ?", bob)).isEqualTo(10);
        assertThat(earn(bob, 1_250).items()).as("tier 15").containsExactly("boost_xp_hour");
        assertThat(held(bob, "boost_xp_hour")).as("a second of the same boost, added to the first").isEqualTo(2);

        long cyd = player("cyd");
        gems(cyd, 499);
        assertThat(passes.buyPremium(cyd, now).result()).isEqualTo(SeasonPassRepository.Bought.INSUFFICIENT_FUNDS);
        assertThat(List.of(economy.wallet(cyd).gems(), passes.of(cyd).premium())).containsExactly(499L, false);

        long dee = player("dee");
        gems(dee, 600);
        sql("UPDATE season SET ends_at = ? WHERE id = 1", Timestamp.from(now.minus(Duration.ofMinutes(1))));
        assertThat(passes.buyPremium(dee, now).result()).as("being closed").isEqualTo(SeasonPassRepository.Bought.SEASON_ENDED);
        assertThat(economy.wallet(dee).gems()).isEqualTo(600);
        assertThat(economy.reconcile(10).count()).isZero();
    }

    @Test
    @DisplayName("a pass is the season being played's: nothing before any points; a new season's starts at nothing, without premium; six seasons kept")
    void seasons() throws SQLException {
        long ada = player("ada");
        SeasonPassRepository.Pass none = passes.of(ada);
        assertThat(List.of(none.season(), none.points(), none.tier(), none.premium())).containsExactly(1, 0L, 0, false);
        assertThat(none.endsAt()).isEqualTo(new SeasonRepository(db.dataSource()).get(1).endsAt());
        gems(ada, 500);
        passes.buyPremium(ada, Instant.now());
        earn(ada, 300);

        Instant start = Instant.parse("2027-01-01T00:00:00Z");
        for (int s = 2; s <= 8; s++) {                    // as the season job's first step makes each next season
            sql("UPDATE season SET placed_at = NOW(3) WHERE id = ?", s - 1);
            sql("INSERT INTO season (id, starts_at, ends_at) VALUES (?, ?, ?)", s, Timestamp.from(start),
                    Timestamp.from(start.plus(Duration.ofDays(61))));
            if (s == 2) {
                SeasonPassRepository.Pass next = passes.of(ada);
                assertThat(List.of(next.season(), next.points(), next.premium())).as("a new season").containsExactly(2, 0L, false);
            }
            earn(ada, 10);
        }
        assertThat(count("SELECT COUNT(*) FROM season_pass WHERE player_id = ?", ada)).isEqualTo(8);
        assertThat(passes.purge(1)).as("one a batch").isEqualTo(2);
        assertThat(count("SELECT MIN(season_id) FROM season_pass WHERE player_id = ?", ada)).as("8 and the five before").isEqualTo(3);
        assertThat(passes.purge(100)).isZero();
    }
}
