package com.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Gems for money (docs 04 §8, revenue, D-68): an order its provider confirms, against a real MySQL. */
class PaymentRepositoryTest {

    private static final String URL = System.getenv().getOrDefault("JDBC_URL",
            "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "backend");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD",
            "backend-dev-password");
    private static final Instant T = Instant.parse("2026-10-04T12:00:00Z");

    private static Database db;
    private static AccountRepository accounts;
    private static EconomyRepository economy;
    private static PaymentRepository payments;
    private static AdminRepository admin;

    @BeforeAll
    static void setUp() {
        db = new Database(URL, USER, PASSWORD, 4);
        accounts = new AccountRepository(db.dataSource());
        economy = new EconomyRepository(db.dataSource());
        payments = new PaymentRepository(db.dataSource());
        admin = new AdminRepository(db.dataSource());
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

    private static PaymentRepository.Order order(long player, String key, int gems, Instant at) throws SQLException {
        PaymentRepository.Placed placed = payments.place(player, key, "gems_" + gems, gems, 99, "USD", "simulated", at);
        assertThat(placed.inDebt()).isFalse();
        return placed.order();
    }

    /** Gems spent through the ledger's one path, as the shop spends them. */
    private static void spend(long player, int gems) throws SQLException {
        try (Connection c = db.dataSource().getConnection()) {
            c.setAutoCommit(false);
            EconomyRepository.move(c, player, EconomyRepository.CURRENCY_GEMS, -gems, EconomyRepository.REASON_PURCHASE,
                    "boost", "spend:" + player + ":" + gems);
            c.commit();
        }
    }

    @Test
    @DisplayName("an order's time is the same when placed as in every read after: cut to the millisecond before MySQL rounds it (P-55)")
    void anOrdersTimeIsAsStored() throws SQLException {
        long ada = player("ada");
        Instant at = Instant.parse("2026-10-05T22:29:54.500600Z");          // MySQL's DATETIME(3) rounds it to .501
        PaymentRepository.Order placed = order(ada, "00000000-0000-0000-0000-0000000000b5", 80, at);
        assertThat(payments.get(placed.id()).createdAt()).isEqualTo(placed.createdAt())
                .isEqualTo(Instant.parse("2026-10-05T22:29:54.500Z"));
    }

    @Test
    @DisplayName("an order: pending, the same order for a retried ask; paid once, its gems through the ledger, the first order's twice; declined grants nothing")
    void ordersPaidOnce() throws SQLException {
        long ada = player("ada");
        PaymentRepository.Order first = order(ada, "00000000-0000-0000-0000-00000000000a", 80, T);
        assertThat(first.state()).isEqualTo(PaymentRepository.PENDING);
        assertThat(order(ada, "00000000-0000-0000-0000-00000000000a", 80, T).id()).as("a retried ask").isEqualTo(first.id());

        PaymentRepository.Confirmed paid = payments.confirm(first.id(), true, T);
        assertThat(List.of(paid.now(), paid.order().state(), paid.order().bonus(), paid.gemsBalance()))
                .containsExactly(true, PaymentRepository.PAID, 80, 160L);
        PaymentRepository.Confirmed again = payments.confirm(first.id(), false, T);
        assertThat(List.of(again.now(), again.order().state(), again.gemsBalance())).as("final: the first confirm's answer")
                .containsExactly(false, PaymentRepository.PAID, 160L);

        PaymentRepository.Order second = order(ada, "00000000-0000-0000-0000-00000000000b", 500, T);
        assertThat(payments.confirm(second.id(), true, T).order().bonus()).as("not the first").isZero();
        PaymentRepository.Order third = order(ada, "00000000-0000-0000-0000-00000000000c", 80, T);
        assertThat(payments.confirm(third.id(), false, T).order().state()).isEqualTo(PaymentRepository.DECLINED);
        assertThat(payments.confirm(third.id(), true, T).order().state()).as("declined is final").isEqualTo(PaymentRepository.DECLINED);
        assertThat(economy.wallet(ada).gems()).isEqualTo(660);
        assertThat(count("SELECT COUNT(*) FROM ledger WHERE player_id = ? AND reason = ?", ada,
                EconomyRepository.REASON_PAYMENT)).as("two orders and one bonus").isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM ledger WHERE idem_key = ?", "payment:" + first.id())).isEqualTo(1);
        assertThat(payments.get(second.id()).gems()).isEqualTo(500);
        assertThat(payments.get("no-such-order")).isNull();
    }

    @Test
    @DisplayName("a refund takes the order's gems and bonus back once; what was spent is a debt that blocks ordering until it is cleared; each audited in its transaction (D-30)")
    void refundsAndDebt() throws SQLException {
        long bob = player("bob");
        PaymentRepository.Order a = order(bob, "00000000-0000-0000-0000-0000000000b1", 80, T);
        payments.confirm(a.id(), true, T);                                                // 80 and the first's 80
        spend(bob, 150);                                                                 // 10 left
        PaymentRepository.Refunded r = admin.refund(a.id(), T, "{\"reason\":\"chargeback\"}");
        assertThat(List.of(r.now(), r.taken(), r.debt(), r.order().state())).containsExactly(true, 10, 150, PaymentRepository.REFUNDED);
        assertThat(admin.refund(a.id(), T, "{}").now()).as("once").isFalse();
        assertThat(economy.wallet(bob).gems()).isZero();
        assertThat(payments.debtOf(bob)).isEqualTo(150);
        assertThat(payments.place(bob, "00000000-0000-0000-0000-0000000000b2", "gems_80", 80, 99, "USD", "simulated", T).inDebt())
                .as("no new order in debt").isTrue();
        assertThat(admin.clearDebt(bob, "{}")).isEqualTo(150);
        PaymentRepository.Order b = order(bob, "00000000-0000-0000-0000-0000000000b3", 80, T);
        assertThat(payments.confirm(b.id(), true, T).order().bonus()).as("a second first is not").isZero();
        assertThat(count("SELECT COUNT(*) FROM ledger WHERE player_id = ? AND reason = ?", bob,
                EconomyRepository.REASON_PAYMENT_REFUND)).isEqualTo(1);
        assertThat(admin.refund(PaymentRepository.class.getName(), T, "{}")).as("no such order").isNull();
        PaymentRepository.Order pending = order(bob, "00000000-0000-0000-0000-0000000000b4", 80, T);
        assertThat(admin.refund(pending.id(), T, "{}").now()).as("only a paid order").isFalse();
        assertThat(count("SELECT COUNT(*) FROM admin_audit WHERE action = 'refund' AND target = ? AND outcome = 'refunded'", a.id()))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM admin_audit WHERE action = 'refund' AND outcome = 'refused_not_paid'")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM admin_audit WHERE action = 'refund' AND outcome = 'refused_no_such_order'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM admin_audit WHERE action = 'refund-debt' AND target = ?", Long.toString(bob))).isEqualTo(1);
        assertThat(economy.reconcile(10).count()).as("every balance its ledger's").isZero();
    }

    @Test
    @DisplayName("orders left pending longer than a day expire, a batch at a time; a day exactly, or paid, they stay; an expired order is not paid")
    void pendingOrdersExpire() throws SQLException {
        long cyd = player("cyd");
        PaymentRepository.Order old = order(cyd, "00000000-0000-0000-0000-0000000000c1", 80, T.minus(Duration.ofHours(25)));
        PaymentRepository.Order older = order(cyd, "00000000-0000-0000-0000-0000000000c5", 80, T.minus(Duration.ofHours(26)));
        PaymentRepository.Order fresh = order(cyd, "00000000-0000-0000-0000-0000000000c2", 80, T);
        PaymentRepository.Order aDay = order(cyd, "00000000-0000-0000-0000-0000000000c3", 80, T.minus(PaymentRepository.PENDING_FOR));
        PaymentRepository.Order paid = order(cyd, "00000000-0000-0000-0000-0000000000c4", 80, T.minus(Duration.ofHours(30)));
        payments.confirm(paid.id(), true, T.minus(Duration.ofHours(30)));
        assertThat(payments.expirePending(T.minus(PaymentRepository.PENDING_FOR), 1)).as("two, one a batch").isEqualTo(2);
        assertThat(payments.get(old.id()).state()).isEqualTo(PaymentRepository.EXPIRED);
        assertThat(payments.get(older.id()).state()).isEqualTo(PaymentRepository.EXPIRED);
        assertThat(payments.get(fresh.id()).state()).isEqualTo(PaymentRepository.PENDING);
        assertThat(payments.get(aDay.id()).state()).as("a day exactly").isEqualTo(PaymentRepository.PENDING);
        assertThat(payments.get(paid.id()).state()).as("paid").isEqualTo(PaymentRepository.PAID);
        assertThat(payments.confirm(old.id(), true, T).order().state()).isEqualTo(PaymentRepository.EXPIRED);
        assertThat(economy.wallet(cyd).gems()).as("the paid order's 80 and its bonus, and no more").isEqualTo(160);
    }
}
