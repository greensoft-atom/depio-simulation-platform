package com.backend.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.jredis.client.JRedisClient;
import com.jredis.embedded.JRedisEmbedded;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Parties in the store (04 §4, "Parties"; D-25). */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PartiesTest {

    private static JRedisEmbedded server;
    private static JRedisClient store;

    private final AtomicInteger next = new AtomicInteger();
    private Parties parties;

    @BeforeAll
    static void start() throws Exception {
        server = JRedisEmbedded.start();
        store = server.newClient();
    }

    @AfterAll
    static void stop() {
        store.close();
        server.close();
    }

    @BeforeEach
    void fresh() {
        store.sync().send("FLUSHALL");
        parties = new Parties(store, () -> "p" + next.incrementAndGet());
    }

    /** {@code a} invites {@code b}, who accepts: the party's id. */
    private String together(long a, long b) {
        Parties.Invitation i = parties.invite(a, "n" + a, b);
        assertThat(i.result()).isEqualTo(Parties.Invited.INVITED);
        assertThat(parties.accept(b, "n" + b, i.partyId())).isEqualTo(Parties.Joined.JOINED);
        return i.partyId();
    }

    @Test
    @DisplayName("an invitation makes the inviter's party, led by them; accepting joins it, names and all")
    void inviteAndAccept() {
        String id = together(1, 2);
        Parties.Party p = parties.of(2);
        assertThat(p.id()).isEqualTo(id);
        assertThat(p.leader()).isEqualTo(1);
        assertThat(p.members()).containsExactly(1L, 2L);
        assertThat(p.names()).containsEntry(1L, "n1").containsEntry(2L, "n2");
        assertThat(parties.of(1)).isEqualTo(p);
        assertThat(parties.of(3)).as("in none").isNull();
        assertThat(store.sync().ttl(Parties.partyKey(id))).isBetween(1L, (long) Parties.TTL_SECONDS);
        assertThat(store.sync().ttl(Parties.memberKey(2))).isBetween(1L, (long) Parties.TTL_SECONDS);

        String next = parties.invite(1, "n1", 3).partyId();
        assertThat(store.sync().ttl(Parties.invitationKey(3, next))).as("an invitation lapses")
                .isBetween(1L, (long) Parties.INVITATION_SECONDS);
        assertThat(parties.accept(3, "n3", next)).isEqualTo(Parties.Joined.JOINED);
        parties.leave(2);                                        // 1 and 3 are still a party
        assertThat(parties.accept(2, "n2", id)).as("an invitation is spent once accepted")
                .isEqualTo(Parties.Joined.NO_INVITATION);
    }

    @Test
    @DisplayName("an invitation is refused to oneself, to a player in a party, from a member who does not lead, and to a full party")
    void invitationsAreRefused() {
        assertThat(parties.invite(1, "n1", 1).result()).isEqualTo(Parties.Invited.SELF);
        together(1, 2);
        together(5, 6);
        assertThat(parties.invite(1, "n1", 5).result()).as("in a party").isEqualTo(Parties.Invited.IN_PARTY);
        assertThat(parties.invite(2, "n2", 7).result()).as("a member").isEqualTo(Parties.Invited.NOT_LEADER);
        String id = parties.invite(1, "n1", 3).partyId();
        assertThat(parties.accept(3, "n3", id)).isEqualTo(Parties.Joined.JOINED);
        assertThat(parties.invite(1, "n1", 4).result()).as("three is full").isEqualTo(Parties.Invited.PARTY_FULL);
    }

    @Test
    @DisplayName("accepting is refused without an invitation, when already in a party, and when the party filled meanwhile")
    void acceptingIsRefused() {
        String id = parties.invite(1, "n1", 2).partyId();
        assertThat(parties.accept(9, "n9", id)).as("not invited").isEqualTo(Parties.Joined.NO_INVITATION);
        parties.invite(1, "n1", 3);
        parties.invite(1, "n1", 4);
        assertThat(parties.accept(2, "n2", id)).isEqualTo(Parties.Joined.JOINED);
        assertThat(parties.accept(3, "n3", id)).isEqualTo(Parties.Joined.JOINED);
        assertThat(parties.accept(4, "n4", id)).as("invited, but three are in").isEqualTo(Parties.Joined.PARTY_FULL);
        String other = parties.invite(5, "n5", 2).partyId();
        assertThat(other).as("2 is in a party, so is not invited").isNull();
        String third = parties.invite(6, "n6", 7).partyId();
        together(8, 7);
        assertThat(parties.accept(7, "n7", third)).as("in a party").isEqualTo(Parties.Joined.IN_PARTY);
    }

    @Test
    @DisplayName("a party asked about a match takes no newcomer, as a queued one takes none; a matched one may (T-32)")
    void anAskedPartyTakesNoOne() {
        String id = parties.invite(1, "n1", 2).partyId();
        assertThat(parties.accept(2, "n2", id)).isEqualTo(Parties.Joined.JOINED);
        parties.invite(1, "n1", 3);
        store.sync().hset(MatchQueue.playerKey(1), java.util.Map.of("state", "confirming"));
        assertThat(parties.accept(3, "n3", id)).isEqualTo(Parties.Joined.QUEUED);
        store.sync().hset(MatchQueue.playerKey(1), java.util.Map.of("state", "matched"));
        assertThat(parties.accept(3, "n3", id)).as("each member's match is their own").isEqualTo(Parties.Joined.JOINED);
    }

    @Test
    @DisplayName("a leader who leaves hands the party to the longest member; a party of one is no party")
    void leaving() {
        String id = together(1, 2);
        assertThat(parties.accept(3, "n3", parties.invite(1, "n1", 3).partyId())).isEqualTo(Parties.Joined.JOINED);
        Parties.Change c = parties.leave(1);
        assertThat(c.before().members()).containsExactly(1L, 2L, 3L);
        assertThat(c.after().leader()).as("the longest member").isEqualTo(2);
        assertThat(c.after().members()).containsExactly(2L, 3L);
        assertThat(parties.of(1)).isNull();
        assertThat(parties.of(3).leader()).isEqualTo(2);

        Parties.Change last = parties.leave(3);
        assertThat(last.after()).as("one left: no party").isNull();
        assertThat(parties.of(2)).isNull();
        assertThat(store.sync().exists(Parties.partyKey(id))).isZero();
        assertThat(parties.invite(9, "n9", 2).result()).as("the one left is in no party, so may be invited")
                .isEqualTo(Parties.Invited.INVITED);
        parties.leave(9);
        assertThat(parties.leave(2).before()).as("in none: nothing to leave").isNull();
    }

    @Test
    @DisplayName("only the leader removes a member, and only a member")
    void kicking() {
        together(1, 2);
        parties.accept(3, "n3", parties.invite(1, "n1", 3).partyId());
        assertThat(parties.kick(2, 3).before()).as("not the leader").isNull();
        assertThat(parties.kick(1, 9).before()).as("not a member").isNull();
        Parties.Change c = parties.kick(1, 3);
        assertThat(c.after().members()).containsExactly(1L, 2L);
        assertThat(parties.of(3)).isNull();
    }

    @Test
    @DisplayName("a party's version: 1 when made, one more on each change of its members or leader, none for an invitation (D-74)")
    void everyChangeRaisesTheVersion() {
        String id = parties.invite(1, "n1", 2).partyId();
        assertThat(parties.of(1).version()).as("made").isEqualTo(1);
        assertThat(parties.accept(2, "n2", id)).isEqualTo(Parties.Joined.JOINED);
        assertThat(parties.of(1).version()).as("joined").isEqualTo(2);
        parties.invite(1, "n1", 3);
        assertThat(parties.of(1).version()).as("an invitation changes no member").isEqualTo(2);
        assertThat(parties.accept(3, "n3", id)).isEqualTo(Parties.Joined.JOINED);
        assertThat(parties.of(2).version()).isEqualTo(3);

        Parties.Change kicked = parties.kick(1, 3);
        assertThat(kicked.after().version()).as("kicked").isEqualTo(4);
        assertThat(parties.of(1)).as("the state told is the one written").isEqualTo(kicked.after());
        assertThat(parties.accept(3, "n3", parties.invite(1, "n1", 3).partyId())).isEqualTo(Parties.Joined.JOINED);
        Parties.Change left = parties.leave(1);
        assertThat(left.after().version()).as("the leader left, the party handed on").isEqualTo(6);
        assertThat(parties.of(2)).isEqualTo(left.after());

        Parties.Change last = parties.leave(3);
        assertThat(last.after()).as("a party of one is no party").isNull();
        Parties.Party ended = Parties.ended(last.before());
        assertThat(ended.id()).as("what its members are told names it").isEqualTo(id);
        assertThat(ended.members()).isEmpty();
        assertThat(ended.version()).as("at the version of the change that ended it").isEqualTo(7);
    }

    @Test
    @DisplayName("a party made before versions reads as version 0, and is 1 at its next change (D-74)")
    void anUnversionedPartyIsVersionZero() {
        String id = together(1, 2);
        store.sync().hdel(Parties.partyKey(id), "v");
        assertThat(parties.of(1).version()).isZero();
        assertThat(parties.accept(3, "n3", parties.invite(1, "n1", 3).partyId())).isEqualTo(Parties.Joined.JOINED);
        assertThat(parties.of(1).version()).isEqualTo(1);
    }

    @Test
    @DisplayName("a change overtaken by another between its read and its write is made again, not lost")
    void anOvertakenChangeIsMadeAgain() {
        AtomicInteger made = new AtomicInteger();
        Parties racing = new Parties(store, () -> {
            if (made.incrementAndGet() == 1) {
                // Another change touches what the first attempt read, and leaves it as it was.
                store.sync().set(Parties.memberKey(1), "x");
                store.sync().del(Parties.memberKey(1));
            }
            return "p" + made.get();
        });
        Parties.Invitation i = racing.invite(1, "n1", 2);
        assertThat(i.result()).isEqualTo(Parties.Invited.INVITED);
        assertThat(i.partyId()).as("the second attempt's").isEqualTo("p2");
        assertThat(racing.of(1).id()).isEqualTo("p2");
    }

    @Test
    @DisplayName("two invited players accepting the last place at once: one is in, the other is told it is full")
    void theLastPlaceIsTakenOnce() throws Exception {
        String id = together(1, 2);
        parties.invite(1, "n1", 3);
        parties.invite(1, "n1", 4);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            Future<Parties.Joined> a = pool.submit(() -> { go.await(); return parties.accept(3, "n3", id); });
            Future<Parties.Joined> b = pool.submit(() -> { go.await(); return parties.accept(4, "n4", id); });
            go.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(Parties.Joined.JOINED, Parties.Joined.PARTY_FULL);
            assertThat(parties.of(1).members()).hasSize(3);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a party fits a sandbox: the arena's room for one holds a party's most (01 §8.10)")
    void aPartyFitsASandbox() {
        assertThat(com.backend.handoff.MatchMode.SANDBOX.places()).isEqualTo(Parties.MOST);
    }
}
