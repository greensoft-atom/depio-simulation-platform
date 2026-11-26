package com.backend.account.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;

import com.backend.account.token.TokenIssuer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccountsTest {

    private static Database db;
    private final Accounts accounts = new Accounts();
    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");
    private static final byte[] HASH = "$argon2id$v=19$m=1,t=1,p=1$c2FsdA$aGFzaA".getBytes(StandardCharsets.UTF_8);

    @BeforeAll
    static void setUp() {
        db = TestDatabase.open();
    }

    @AfterAll
    static void tearDown() {
        db.close();
    }

    @BeforeEach
    void fresh() throws Exception {
        TestDatabase.empty(db);
    }

    @Test
    @DisplayName("an account is made under its name as typed, with a subject of its own; the name whatever its case is taken")
    void made() throws Exception {
        try (Connection c = db.dataSource().getConnection()) {
            Accounts.Account ada = accounts.create(c, "Ada", HASH, TokenIssuer.randomId(), NOW);
            assertThat(ada.username()).isEqualTo("Ada");
            assertThat(ada.subject()).hasSize(22);
            assertThat(ada.status()).isEqualTo(Accounts.Status.ACTIVE);
            assertThat(accounts.create(c, "ada", HASH, TokenIssuer.randomId(), NOW)).as("taken, whatever its case").isNull();
            assertThat(accounts.byUsername(c, "ADA").id()).isEqualTo(ada.id());
            assertThat(accounts.byUsername(c, "ADA").passwordHash()).isEqualTo(HASH);
            assertThat(accounts.bySubject(c, ada.subject()).id()).isEqualTo(ada.id());
            assertThat(accounts.byUsername(c, "bob")).isNull();
            assertThat(accounts.lock(c, ada.id()).username()).isEqualTo("Ada");
        }
    }

    @Test
    @DisplayName("a login is recorded; a rehash replaces the hash only if it is still the one checked; a change records when")
    void changes() throws Exception {
        try (Connection c = db.dataSource().getConnection()) {
            Accounts.Account ada = accounts.create(c, "ada", HASH, TokenIssuer.randomId(), NOW);
            accounts.touchLogin(c, ada.id(), NOW.plusSeconds(5));
            assertThat(column(c, ada.id(), "last_login_at")).isEqualTo("2026-11-26 10:00:05.000");
            byte[] other = "other".getBytes(StandardCharsets.UTF_8);
            assertThat(accounts.replaceHash(c, ada.id(), other, "x".getBytes(StandardCharsets.UTF_8)))
                    .as("not the hash checked: a change came in between").isFalse();
            assertThat(accounts.replaceHash(c, ada.id(), HASH, other)).isTrue();
            assertThat(accounts.byUsername(c, "ada").passwordHash()).isEqualTo(other);
            accounts.changePassword(c, ada.id(), HASH, NOW.plusSeconds(9));
            assertThat(accounts.byUsername(c, "ada").passwordHash()).isEqualTo(HASH);
            assertThat(column(c, ada.id(), "password_changed_at")).isEqualTo("2026-11-26 10:00:09.000");
        }
    }

    @Test
    @DisplayName("a deletion clears the name and the hash and keeps the subject: the name is free, the subject never given again")
    void deleted() throws Exception {
        try (Connection c = db.dataSource().getConnection()) {
            Accounts.Account ada = accounts.create(c, "ada", HASH, TokenIssuer.randomId(), NOW);
            accounts.delete(c, ada.id(), NOW.plusSeconds(1));
            assertThat(accounts.byUsername(c, "ada")).isNull();
            Accounts.Account gone = accounts.bySubject(c, ada.subject());
            assertThat(gone.status()).isEqualTo(Accounts.Status.DELETED);
            assertThat(gone.username()).isNull();
            assertThat(gone.passwordHash()).isNull();
            assertThat(column(c, ada.id(), "deleted_at")).isEqualTo("2026-11-26 10:00:01.000");
            Accounts.Account again = accounts.create(c, "ada", HASH, TokenIssuer.randomId(), NOW);
            assertThat(again).as("the name free again").isNotNull();
            assertThat(again.subject()).isNotEqualTo(ada.subject());
        }
    }

    private static String column(Connection c, long id, String name) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT DATE_FORMAT(" + name + ", '%Y-%m-%d %H:%i:%s.%f') FROM account WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet r = ps.executeQuery()) {
                r.next();
                String s = r.getString(1);
                return s == null ? null : s.substring(0, s.length() - 3);
            }
        }
    }
}
