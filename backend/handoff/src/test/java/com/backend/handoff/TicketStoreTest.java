package com.backend.handoff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.jredis.client.JRedisClient;
import com.jredis.embedded.JRedisEmbedded;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runs against a real j-redis, embedded in this JVM.
 *
 * The single-use guarantee is a property of the store's command ordering, so a fake store
 * would only prove that the fake is single-use.
 */
class TicketStoreTest {

    private static JRedisEmbedded server;
    private static JRedisClient client;
    private static TicketStore store;

    @BeforeAll
    static void setUp() {
        server = JRedisEmbedded.start();
        client = server.newClient();
        store = new TicketStore(client);
    }

    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @BeforeEach
    void clean() {
        client.sync().send("FLUSHALL");
    }

    @Test
    @DisplayName("an issued ticket is claimed once, with the fields it was issued with")
    void roundTrip() throws Exception {
        Ticket issued = Ticket.forPlayer(4711, "Ada", 1);
        store.issue(issued).get(5, TimeUnit.SECONDS);

        Ticket claimed = store.claim(issued.id()).get(5, TimeUnit.SECONDS);

        assertThat(claimed).isEqualTo(issued);
        assertThat(client.sync().exists("ticket:" + issued.id()))
                .as("the claim consumed the key").isZero();
    }

    @Test
    @DisplayName("a ticket for a made match carries the match and its mode; an open one carries neither")
    void matchTickets() throws Exception {
        String uid = Ulid.generate();
        Ticket issued = Ticket.forMatch(4711, "Ada", 1, uid, MatchMode.DUEL.id);
        store.issue(issued).get(5, TimeUnit.SECONDS);
        Ticket claimed = store.claim(issued.id()).get(5, TimeUnit.SECONDS);
        assertThat(claimed).isEqualTo(issued);
        assertThat(claimed.isMatch()).isTrue();
        assertThat(claimed.mode()).isEqualTo(MatchMode.DUEL.id);

        Ticket open = Ticket.forPlayer(4712, "Bob", 0);
        store.issue(open).get(5, TimeUnit.SECONDS);
        assertThat(client.sync().hexists("ticket:" + open.id(), "match"))
                .as("an open ticket is written as it always was").isFalse();
        assertThat(store.claim(open.id()).get(5, TimeUnit.SECONDS).isMatch()).isFalse();

        // Half a match is no ticket: refused like a malformed one.
        client.sync().send("HSET", "ticket:half", "playerId", "1", "name", "Ada", "team", "0", "match", uid);
        assertThat(store.claim("half").get(5, TimeUnit.SECONDS)).isNull();
    }

    @Test
    @DisplayName("equipment's bonus rides in the ticket as stat:percent pairs, when there is one; a bad one refuses it")
    void bonusTravels() throws Exception {
        byte[] percents = new byte[Ticket.BONUS_STATS];
        percents[5] = 8;
        percents[6] = 25;
        assertThat(Ticket.bonusOf(percents)).isEqualTo("5:8,6:25");
        assertThat(Ticket.bonusOf(new byte[Ticket.BONUS_STATS])).isEmpty();

        Ticket issued = Ticket.forPlayer(4711, "Ada", 0, "5:8,6:25");
        store.issue(issued).get(5, TimeUnit.SECONDS);
        assertThat(client.sync().hget("ticket:" + issued.id(), "bonus")).isEqualTo("5:8,6:25");
        Ticket claimed = store.claim(issued.id()).get(5, TimeUnit.SECONDS);
        assertThat(claimed).isEqualTo(issued);
        assertThat(claimed.bonusPercents()).isEqualTo(percents);

        Ticket plain = Ticket.forMatch(4712, "Bob", 1, Ulid.generate(), MatchMode.DUEL.id, "");
        store.issue(plain).get(5, TimeUnit.SECONDS);
        assertThat(client.sync().hexists("ticket:" + plain.id(), "bonus")).as("none: no field").isFalse();
        assertThat(store.claim(plain.id()).get(5, TimeUnit.SECONDS).bonusPercents())
                .isEqualTo(new byte[Ticket.BONUS_STATS]);

        for (String bad : java.util.List.of("5:26", "5:0", "8:10", "-1:5", "5:8,5:9", "5", "x:1", "5:8,")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> Ticket.forPlayer(1, "Ada", 0, bad)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
            client.sync().send("HSET", "ticket:bad", "playerId", "1", "name", "Ada", "team", "0", "bonus", bad);
            assertThat(store.claim("bad").get(5, TimeUnit.SECONDS)).as(bad).isNull();
        }
    }

    @Test
    @DisplayName("a skin rides in the ticket as a field of its own, 1 to 255, when there is one; a bad one refuses it (D-70)")
    void skinTravels() throws Exception {
        Ticket issued = Ticket.forPlayer(4713, "Cy", 0, "5:8", 3);
        store.issue(issued).get(5, TimeUnit.SECONDS);
        assertThat(client.sync().hget("ticket:" + issued.id(), "skin")).isEqualTo("3");
        Ticket claimed = store.claim(issued.id()).get(5, TimeUnit.SECONDS);
        assertThat(claimed).isEqualTo(issued);
        assertThat(java.util.List.of(claimed.skin(), claimed.bonus())).containsExactly(3, "5:8");
        Ticket inMatch = Ticket.forMatch(4714, "Di", 1, Ulid.generate(), MatchMode.DUEL.id, "", 255);
        store.issue(inMatch).get(5, TimeUnit.SECONDS);
        assertThat(store.claim(inMatch.id()).get(5, TimeUnit.SECONDS).skin()).isEqualTo(255);

        Ticket plain = Ticket.forPlayer(4715, "Ed", 0, "");
        store.issue(plain).get(5, TimeUnit.SECONDS);
        assertThat(client.sync().hexists("ticket:" + plain.id(), "skin")).as("none: no field").isFalse();
        assertThat(store.claim(plain.id()).get(5, TimeUnit.SECONDS).skin()).isZero();

        for (int bad : new int[] {-1, 256}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> Ticket.forPlayer(1, "Ada", 0, "", bad)).as("" + bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String bad : java.util.List.of("0", "256", "x", "-1")) {
            client.sync().send("HSET", "ticket:badskin", "playerId", "1", "name", "Ada", "team", "0", "skin", bad);
            assertThat(store.claim("badskin").get(5, TimeUnit.SECONDS)).as(bad).isNull();
        }
    }

    @Test
    @DisplayName("a second claim of the same ticket finds nothing")
    void ticketIsSingleUse() throws Exception {
        Ticket issued = Ticket.forPlayer(1, "Ada", 0);
        store.issue(issued).get(5, TimeUnit.SECONDS);

        assertThat(store.claim(issued.id()).get(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(store.claim(issued.id()).get(5, TimeUnit.SECONDS)).isNull();
    }

    @Test
    @DisplayName("an unknown ticket id is refused, not an error")
    void unknownTicket() throws Exception {
        assertThat(store.claim("not-a-real-ticket").get(5, TimeUnit.SECONDS)).isNull();
        assertThat(store.claim("").get(5, TimeUnit.SECONDS)).isNull();
        assertThat(store.claim(null).get(5, TimeUnit.SECONDS)).isNull();
    }

    @Test
    @DisplayName("a ticket expires on its own if nobody claims it")
    void ticketHasExpiry() throws Exception {
        Ticket issued = Ticket.forPlayer(1, "Ada", 0);
        store.issue(issued).get(5, TimeUnit.SECONDS);

        // An unbounded ticket is a credential that never dies, so the TTL is asserted
        // rather than assumed: a lost EXPIRE is invisible until a leaked ticket is used.
        long ttl = client.sync().ttl("ticket:" + issued.id());
        assertThat(ttl).isBetween(1L, (long) TicketStore.TTL_SECONDS);
    }

    @Test
    @DisplayName("a half-written ticket is refused rather than joined with junk")
    void malformedTicketIsRefused() throws Exception {
        String id = Ticket.newId();
        client.sync().hset("ticket:" + id, "name", "Ada");     // no playerId, no team

        assertThat(store.claim(id).get(5, TimeUnit.SECONDS)).isNull();
        assertThat(client.sync().exists("ticket:" + id))
                .as("a refused ticket is still consumed, so it cannot be retried").isZero();
    }

    @Test
    @DisplayName("sixteen connections replaying one ticket at once: exactly one wins")
    void concurrentClaimsYieldOneWinner() throws Exception {
        Ticket issued = Ticket.forPlayer(99, "Ada", 0);
        store.issue(issued).get(5, TimeUnit.SECONDS);

        // Every claim is submitted before any of them is awaited, so all sixteen reads are
        // in flight together. Threads would not do this reliably: started one at a time on
        // a loaded machine, the first claim usually finishes before the last one starts, and
        // the test then passes against a plainly racy HGETALL-then-DEL. It was written that
        // way first, and the racy version passed.
        List<CompletableFuture<Ticket>> claims = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            claims.add(store.claim(issued.id()));
        }

        List<Ticket> winners = new ArrayList<>();
        for (CompletableFuture<Ticket> claim : claims) {
            Ticket t = claim.get(5, TimeUnit.SECONDS);
            if (t != null) {
                winners.add(t);
            }
        }
        assertThat(winners).as("HGETALL and DEL were indivisible").containsExactly(issued);
    }
}
