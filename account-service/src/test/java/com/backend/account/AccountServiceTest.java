package com.backend.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.backend.account.db.Accounts;
import com.backend.account.db.Database;
import com.backend.account.db.Outbox;
import com.backend.account.db.TestClock;
import com.backend.account.db.TestDatabase;
import com.backend.account.db.Throttle;
import com.backend.account.token.BearerCheck;
import com.backend.account.token.SigningKeys;
import com.backend.account.token.TestKeys;
import com.backend.account.token.TokenIssuer;
import com.nimbusds.jwt.SignedJWT;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccountServiceTest {

    private static final String ISSUER = "https://203.0.113.7/account";
    private static final char[] PW = "hunter2-hunter2".toCharArray();
    private static Database db;
    private final SigningKeys keys = SigningKeys.from(TestKeys.newPair().privateKey(), null);
    private final TokenIssuer issuer = new TokenIssuer(keys, ISSUER, "backend");
    private final TestClock clock = new TestClock(Instant.now());
    private final AtomicInteger woken = new AtomicInteger();
    private PasswordHasher hasher;
    private AccountService service;

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
        hasher = new PasswordHasher(1024, 1, 1);          // cheap: the policy's own cost is PasswordHasherTest's
        service = service(hasher);
    }

    private AccountService service(PasswordHasher h) {
        return new AccountService(db.dataSource(), h, new Throttle(db.dataSource(), clock), issuer,
                new BearerCheck(keys, ISSUER), new Outbox(db.dataSource()), woken::incrementAndGet, clock);
    }

    @Test
    @DisplayName("a sign-up answers as a login does: an access token for its subject, and a refresh token")
    void signUp() throws Exception {
        AccountService.Result r = service.signUp("198.51.100.7", "Ada", PW);
        assertThat(r.outcome()).isEqualTo(AccountService.Outcome.OK);
        assertThat(r.tokens().expiresIn()).isEqualTo(900);
        assertThat(r.tokens().refreshToken()).hasSize(43);
        SignedJWT jwt = SignedJWT.parse(r.tokens().accessToken());
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(r.tokens().accountId()).hasSize(22);
        assertThat(jwt.getJWTClaimsSet().getStringClaim("preferred_username")).isEqualTo("Ada");
        assertThat(service.signUp("198.51.100.7", "ADA", PW).outcome()).isEqualTo(AccountService.Outcome.USERNAME_TAKEN);
        assertThat(service.signUp("198.51.100.7", "a b", PW).outcome()).isEqualTo(AccountService.Outcome.INVALID_USERNAME);
        assertThat(service.signUp("198.51.100.7", "bob", "short".toCharArray()).outcome())
                .isEqualTo(AccountService.Outcome.INVALID_PASSWORD);
    }

    @Test
    @DisplayName("a login by the name in any case; a wrong password and an unknown name are the same refusal")
    void logIn() throws Exception {
        String subject = service.signUp("198.51.100.7", "ada", PW).tokens().accountId();
        AccountService.Result r = service.logIn("198.51.100.7", "ADA", PW);
        assertThat(r.outcome()).isEqualTo(AccountService.Outcome.OK);
        assertThat(r.tokens().accountId()).isEqualTo(subject);
        assertThat(service.logIn("198.51.100.7", "ada", "wrong-password".toCharArray()).outcome())
                .isEqualTo(AccountService.Outcome.INVALID_CREDENTIALS);
        assertThat(service.logIn("198.51.100.7", "nobody", PW).outcome()).isEqualTo(AccountService.Outcome.INVALID_CREDENTIALS);
        assertThat(service.logIn("198.51.100.7", "áda", PW).outcome()).isEqualTo(AccountService.Outcome.INVALID_CREDENTIALS);
    }

    @Test
    @DisplayName("a password hashed under an older cost is hashed again at the login that proves it")
    void rehashed() throws Exception {
        PasswordHasher older = new PasswordHasher(512, 1, 1);
        service(older).signUp("198.51.100.7", "ada", PW);
        assertThat(service.logIn("198.51.100.7", "ada", PW).outcome()).isEqualTo(AccountService.Outcome.OK);
        try (Connection c = db.dataSource().getConnection()) {
            assertThat(hasher.needsRehash(new Accounts().byUsername(c, "ada").passwordHash())).isFalse();
        }
        assertThat(service.logIn("198.51.100.7", "ada", PW).outcome()).isEqualTo(AccountService.Outcome.OK);
    }

    @Test
    @DisplayName("past the address's limit a sign-up and a login are refused, with how long to wait")
    void limited() throws Exception {
        for (int i = 0; i < 30; i++) {
            service.logIn("198.51.100.7", "nobody" + i, PW);
        }
        AccountService.Result r = service.logIn("198.51.100.7", "ada", PW);
        assertThat(r.outcome()).isEqualTo(AccountService.Outcome.TOO_MANY_ATTEMPTS);
        assertThat(r.retryAfterSeconds()).isBetween(1, 60);
        assertThat(service.signUp("198.51.100.7", "bob", PW).outcome()).isEqualTo(AccountService.Outcome.TOO_MANY_ATTEMPTS);
        assertThat(service.signUp("198.51.100.8", "bob", PW).outcome()).isEqualTo(AccountService.Outcome.OK);
    }

    @Test
    @DisplayName("an unknown username costs a hash as a wrong password does, so timing does not reveal it")
    void anUnknownNameCostsAHash() throws Exception {
        AccountService real = service(new PasswordHasher());       // the policy's own cost
        real.signUp("198.51.100.7", "ada", PW);
        real.logIn("198.51.100.7", "ada", "warm-up-the-jit".toCharArray());
        long wrong = time(() -> real.logIn("198.51.100.8", "ada", "not-the-password".toCharArray()));
        long unknown = time(() -> real.logIn("198.51.100.9", "nobody-at-all", "not-the-password".toCharArray()));
        // Loose, as the platform's (04 §1): the machine is shared, and the decoy gone is a thousandfold, not twofold.
        System.out.printf("login: wrong password %d ms, unknown user %d ms%n", wrong, unknown);
        assertThat(unknown).as("an unknown user must not short-circuit").isGreaterThan(wrong / 4);
    }

    @Test
    @DisplayName("the hasher's line full: 503 busy in one to five seconds, counted against nobody")
    void aFullLineIsBusy() throws Exception {
        service.signUp("198.51.100.7", "ada", PW);
        java.util.List<PasswordHasher.Admission> held = new java.util.ArrayList<>();
        for (PasswordHasher.Admission a = hasher.admit(); a != null; a = hasher.admit()) {
            held.add(a);
        }
        for (int i = 0; i < 40; i++) {
            AccountService.Result r = service.logIn("198.51.100.7", "ada", PW);
            assertThat(r.outcome()).isEqualTo(AccountService.Outcome.BUSY);
            assertThat(r.retryAfterSeconds()).isBetween(1, 5);
        }
        assertThat(service.signUp("198.51.100.7", "bob", PW).outcome()).isEqualTo(AccountService.Outcome.BUSY);
        held.forEach(PasswordHasher.Admission::close);
        assertThat(service.logIn("198.51.100.7", "ada", PW).outcome())
                .as("forty turned away for load, and neither the address nor the account counted them").isEqualTo(AccountService.Outcome.OK);
    }

    private interface Action {
        void run() throws Exception;
    }

    private static long time(Action action) throws Exception {
        long start = System.nanoTime();
        action.run();
        return (System.nanoTime() - start) / 1_000_000;
    }

    @Test
    @DisplayName("a refresh gives a new pair, the login's auth_time kept; a logout spends the family")
    void refreshAndLogOut() throws Exception {
        AccountService.Result in = service.signUp("198.51.100.7", "ada", PW);
        long authTime = SignedJWT.parse(in.tokens().accessToken()).getJWTClaimsSet().getLongClaim("auth_time");
        clock.advance(java.time.Duration.ofMinutes(20));
        AccountService.Result r = service.refresh(in.tokens().refreshToken());
        assertThat(r.outcome()).isEqualTo(AccountService.Outcome.OK);
        assertThat(r.tokens().accountId()).isEqualTo(in.tokens().accountId());
        assertThat(SignedJWT.parse(r.tokens().accessToken()).getJWTClaimsSet().getLongClaim("auth_time")).isEqualTo(authTime);
        service.logOut(r.tokens().refreshToken());
        assertThat(service.refresh(r.tokens().refreshToken()).outcome()).isEqualTo(AccountService.Outcome.INVALID_GRANT);
        assertThat(service.refresh("garbage").outcome()).isEqualTo(AccountService.Outcome.INVALID_GRANT);
        service.logOut("garbage");
    }

    @Test
    @DisplayName("a password's change: every family revoked, its event written, a new pair given, the new password logs in")
    void passwordChanged() throws Exception {
        AccountService.Result phone = service.signUp("198.51.100.7", "ada", PW);
        AccountService.Result tablet = service.logIn("198.51.100.7", "ada", PW);
        String bearer = phone.tokens().accessToken();
        assertThat(service.changePassword("198.51.100.7", bearer, "not-it-at-all".toCharArray(), "a-new-password".toCharArray())
                .outcome()).isEqualTo(AccountService.Outcome.INVALID_CREDENTIALS);
        assertThat(service.changePassword("198.51.100.7", bearer, PW, "short".toCharArray()).outcome())
                .isEqualTo(AccountService.Outcome.INVALID_PASSWORD);
        assertThat(events()).isEmpty();
        AccountService.Result r = service.changePassword("198.51.100.7", bearer, PW, "a-new-password".toCharArray());
        assertThat(r.outcome()).isEqualTo(AccountService.Outcome.OK);
        assertThat(service.refresh(tablet.tokens().refreshToken()).outcome()).isEqualTo(AccountService.Outcome.INVALID_GRANT);
        assertThat(service.refresh(phone.tokens().refreshToken()).outcome()).isEqualTo(AccountService.Outcome.INVALID_GRANT);
        assertThat(service.refresh(r.tokens().refreshToken()).outcome()).isEqualTo(AccountService.Outcome.OK);
        assertThat(service.logIn("198.51.100.7", "ada", PW).outcome()).isEqualTo(AccountService.Outcome.INVALID_CREDENTIALS);
        assertThat(service.logIn("198.51.100.7", "ada", "a-new-password".toCharArray()).outcome()).isEqualTo(AccountService.Outcome.OK);
        List<String[]> events = events();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)[0]).isEqualTo("sessions-revoked");
        assertThat(SignedJWT.parse(events.get(0)[1]).getJWTClaimsSet().getSubject()).isEqualTo(phone.tokens().accountId());
        assertThat(woken.get()).as("the delivery told at once").isEqualTo(1);
        assertThat(service.changePassword("198.51.100.7", "not-a-token", PW, PW).outcome())
                .isEqualTo(AccountService.Outcome.INVALID_TOKEN);
    }

    @Test
    @DisplayName("a deletion: the password asked, every family revoked, its event written; the name free, again changes nothing")
    void deleted() throws Exception {
        AccountService.Result in = service.signUp("198.51.100.7", "ada", PW);
        String bearer = in.tokens().accessToken();
        assertThat(service.delete("198.51.100.7", bearer, "not-it-at-all".toCharArray()).outcome())
                .isEqualTo(AccountService.Outcome.INVALID_CREDENTIALS);
        assertThat(service.delete("198.51.100.7", bearer, PW).outcome()).isEqualTo(AccountService.Outcome.OK);
        assertThat(service.refresh(in.tokens().refreshToken()).outcome()).isEqualTo(AccountService.Outcome.INVALID_GRANT);
        assertThat(service.logIn("198.51.100.7", "ada", PW).outcome()).isEqualTo(AccountService.Outcome.INVALID_CREDENTIALS);
        assertThat(service.delete("198.51.100.7", bearer, null).outcome()).as("already deleted").isEqualTo(AccountService.Outcome.OK);
        assertThat(events()).extracting(e -> e[0]).containsExactly("deleted");
        AccountService.Result again = service.signUp("198.51.100.7", "ada", PW);
        assertThat(again.outcome()).isEqualTo(AccountService.Outcome.OK);
        assertThat(again.tokens().accountId()).isNotEqualTo(in.tokens().accountId());
    }

    private static List<String[]> events() throws Exception {
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT kind, token FROM event_outbox ORDER BY id");
             ResultSet r = ps.executeQuery()) {
            List<String[]> out = new java.util.ArrayList<>();
            while (r.next()) {
                out.add(new String[] {r.getString(1), r.getString(2)});
            }
            return out;
        }
    }
}
