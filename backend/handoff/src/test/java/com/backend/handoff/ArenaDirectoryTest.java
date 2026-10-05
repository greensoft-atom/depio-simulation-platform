package com.backend.handoff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import com.jredis.client.JRedisClient;
import com.jredis.embedded.JRedisEmbedded;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ArenaDirectoryTest {

    private static JRedisEmbedded server;
    private static JRedisClient client;
    private static ArenaDirectory directory;

    @BeforeAll
    static void setUp() {
        server = JRedisEmbedded.start();
        client = server.newClient();
        directory = new ArenaDirectory(client);
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

    private static void announce(String name, int port, int players, int max) throws Exception {
        directory.announce(new ArenaDirectory.Endpoint(name, "127.0.0.1", port, players, max))
                .get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("a match goes to the arena with the most free rooms, and never to one with none or too old to say")
    void pickForAMatch() throws Exception {
        directory.announce(new ArenaDirectory.Endpoint("full", "10.0.0.1", 9001, 10, 600, false, 4, 4))
                .get(5, TimeUnit.SECONDS);
        announce("old", 9002, 0, 600);                       // announces no rooms: an older arena
        assertThat(directory.reserveForMatch("m0")).isNull();

        directory.announce(new ArenaDirectory.Endpoint("one", "10.0.0.3", 9003, 500, 600, false, 3, 4))
                .get(5, TimeUnit.SECONDS);
        directory.announce(new ArenaDirectory.Endpoint("two", "10.0.0.4", 9004, 590, 600, false, 2, 4))
                .get(5, TimeUnit.SECONDS);
        assertThat(directory.reserveForMatch("m1").name()).isEqualTo("two");
        directory.release("two", "m1");
        directory.announce(new ArenaDirectory.Endpoint("two-b", "10.0.0.5", 9005, 100, 600, false, 2, 4))
                .get(5, TimeUnit.SECONDS);
        assertThat(directory.reserveForMatch("m2").name()).as("between equals, the emptier").isEqualTo("two-b");
        assertThat(directory.live()).filteredOn(e -> e.name().equals("two-b")).singleElement()
                .satisfies(e -> {
                    assertThat(e.rooms()).isEqualTo(2);
                    assertThat(e.maxRooms()).isEqualTo(4);
                });
    }

    @Test
    @DisplayName("a room chosen for a match is promised to it until the arena announces the match's room (D-42)")
    void roomsArePromised() throws Exception {
        rooms("one", 1, 2);
        assertThat(directory.reserveForMatch("m1").name()).isEqualTo("one");
        assertThat(directory.reserveForMatch("m2")).as("its one free room is m1's").isNull();

        rooms("one", 2, 2, "m1");
        assertThat(client.sync().zscore("rooms:promised:one", "m1")).as("dropped as the room is announced").isNull();
        assertThat(directory.reserveForMatch("m2")).as("m1's room made, counted once: full").isNull();
        rooms("one", 1, 2);                                   // m1's match over, its room gone
        assertThat(directory.reserveForMatch("m2").name()).as("free again").isEqualTo("one");

        directory.release("one", "m2");
        assertThat(directory.reserveForMatch("m3").name()).as("a promise given back").isEqualTo("one");
        directory.release("one", "m3");
        client.sync().send("ZADD", "rooms:promised:one", "1", "lapsed");
        assertThat(directory.reserveForMatch("m4").name()).as("a lapsed promise holds nothing").isEqualTo("one");
        assertThat(client.sync().zscore("rooms:promised:one", "lapsed")).as("and is dropped").isNull();
        assertThat(client.sync().pttl("rooms:promised:one")).as("the promises lapse with their ticket")
                .isBetween(1L, TimeUnit.SECONDS.toMillis(TicketStore.TTL_SECONDS));

        client.sync().send("FLUSHALL");
        rooms("busy", 0, 2);
        rooms("calm", 1, 2);
        String later = Long.toString(System.currentTimeMillis() + 30_000);
        client.sync().send("ZADD", "rooms:promised:busy", later, "p1", later, "p2");
        assertThat(directory.reserveForMatch("m5").name()).as("the most free rooms once promises are counted")
                .isEqualTo("calm");
    }

    @Test
    @DisplayName("choosers racing for one last room: never two of them given it, and never none (the gateway review)")
    void racingChoosers() throws Exception {
        for (int round = 0; round < 20; round++) {
            client.sync().send("FLUSHALL");
            rooms("one", 1, 2);
            java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(8);
            java.util.concurrent.atomic.AtomicInteger given = new java.util.concurrent.atomic.AtomicInteger();
            java.util.List<Thread> choosers = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String match = "m" + round + "-" + i;
                choosers.add(Thread.ofPlatform().start(() -> {
                    try (JRedisClient own = server.newClient()) {           // its own, as each process has
                        ArenaDirectory mine = new ArenaDirectory(own);
                        start.await(5, TimeUnit.SECONDS);
                        if (mine.reserveForMatch(match) != null) {
                            given.incrementAndGet();
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            for (Thread t : choosers) {
                t.join(10_000);
            }
            assertThat(given.get()).as("round %d: exactly one keeps it, not all giving it back", round).isEqualTo(1);
        }
    }

    /** Announced as an arena does, with the matches whose rooms it has open. */
    private static void rooms(String name, int rooms, int maxRooms, String... openMatches) throws Exception {
        directory.announce(new ArenaDirectory.Endpoint(name, "10.0.0.1", 9001, 0, 600, false, rooms, maxRooms), "[]",
                java.util.List.of(openMatches)).get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("an announced arena is listed, with an expiry")
    void announceThenFind() throws Exception {
        announce("arena-1", 9001, 12, 150);

        assertThat(directory.live()).singleElement().satisfies(e -> {
            assertThat(e.name()).isEqualTo("arena-1");
            assertThat(e.host()).isEqualTo("127.0.0.1");
            assertThat(e.port()).isEqualTo(9001);
            assertThat(e.players()).isEqualTo(12);
            assertThat(e.free()).isEqualTo(138);
        });
        // Without the expiry an arena that dies is offered players for ever.
        assertThat(client.sync().ttl("arena:arena-1"))
                .isBetween(1L, (long) ArenaDirectory.TTL_SECONDS);
    }

    @Test
    @DisplayName("the arena with the most free capacity is picked")
    void pickTheEmptiest() throws Exception {
        announce("arena-1", 9001, 140, 150);
        announce("arena-2", 9002, 20, 150);
        announce("arena-3", 9003, 90, 150);

        assertThat(directory.pick().name()).isEqualTo("arena-2");
    }

    @Test
    @DisplayName("a full arena is not offered, and neither is a set of full arenas")
    void fullArenasAreSkipped() throws Exception {
        announce("arena-1", 9001, 150, 150);
        announce("arena-2", 9002, 151, 150);          // over capacity, however that happened

        assertThat(directory.pick()).isNull();
        assertThat(directory.live()).as("still listed, just not offered").hasSize(2);
    }

    @Test
    @DisplayName("an expired arena leaves nothing behind in the index")
    void expiredEntryIsTidiedUp() throws Exception {
        announce("arena-1", 9001, 0, 150);
        // Exactly what a SIGKILLed arena leaves: the entry expires, the index does not.
        client.sync().del("arena:arena-1");

        assertThat(directory.live()).as("a name with no entry is not an arena").isEmpty();
        assertThat(directory.pick()).isNull();
        assertThat(client.sync().smembers("arenas"))
                .as("and the index is repaired, so it cannot grow for ever").isEmpty();
    }

    @Test
    @DisplayName("a malformed entry is ignored rather than crashing the lookup")
    void malformedEntryIsIgnored() throws Exception {
        announce("arena-1", 9001, 0, 150);
        client.sync().hset("arena:broken", "host", "127.0.0.1");   // no port, no capacity
        client.sync().sadd("arenas", "broken");

        // One bad row must not stop every other arena being found.
        assertThat(directory.live()).singleElement()
                .satisfies(e -> assertThat(e.name()).isEqualTo("arena-1"));
        assertThat(directory.pick().name()).isEqualTo("arena-1");
    }

    @Test
    @DisplayName("withdrawing removes an arena at once")
    void withdrawIsImmediate() throws Exception {
        announce("arena-1", 9001, 0, 150);
        assertThat(directory.pick()).isNotNull();

        directory.withdraw("arena-1").get(5, TimeUnit.SECONDS);

        assertThat(directory.live()).isEmpty();
        assertThat(client.sync().smembers("arenas")).isEmpty();
    }

    @Test
    @DisplayName("re-announcing an arena updates it instead of duplicating it")
    void reannounceUpdates() throws Exception {
        announce("arena-1", 9001, 10, 150);
        announce("arena-1", 9001, 40, 150);

        assertThat(directory.live()).singleElement()
                .satisfies(e -> assertThat(e.players()).isEqualTo(40));
    }

    @Test
    @DisplayName("whether an arena wants TLS is announced, and an entry that does not say means no")
    void tlsIsAnnounced() throws Exception {
        directory.announce(new ArenaDirectory.Endpoint("arena-1", "10.0.0.7", 9001, 0, 150, true))
                .get(5, TimeUnit.SECONDS);
        announce("arena-2", 9002, 0, 150);

        assertThat(directory.live()).extracting(ArenaDirectory.Endpoint::name, ArenaDirectory.Endpoint::tls)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("arena-1", true),
                        org.assertj.core.groups.Tuple.tuple("arena-2", false));

        // Restarted without its keystore: the field is written every time, so the directory
        // stops telling clients to use TLS at the next announcement rather than never.
        announce("arena-1", 9001, 0, 150);
        assertThat(directory.live()).filteredOn(e -> e.name().equals("arena-1"))
                .singleElement().satisfies(e -> assertThat(e.tls()).isFalse());

        // What an arena older than the field left behind.
        client.sync().hdel("arena:arena-2", "tls");
        assertThat(directory.live()).filteredOn(e -> e.name().equals("arena-2"))
                .singleElement().satisfies(e -> assertThat(e.tls()).isFalse());
    }
}
