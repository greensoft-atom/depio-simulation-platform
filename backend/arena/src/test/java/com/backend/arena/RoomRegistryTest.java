package com.backend.arena;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** How the registry places players in rooms, and names and drops the rooms. */
class RoomRegistryTest {

    @Test
    @Timeout(30)
    @DisplayName("a full two-player room is not chosen: a third player opens a second room")
    void smallRoomsOverflowIntoNewOnes() {
        RoomRegistry registry = new RoomRegistry(1_000f, 256, 2, 0, 2);
        try {
            assertThat(registry.allocate()).isNotNull();
            assertThat(registry.allocate()).isNotNull();
            // The join headroom rounds to nothing for a room this small, so a full room still
            // counted as under its ceiling, was chosen, refused the reservation, and the
            // player was sent away with rooms to spare.
            assertThat(registry.allocate()).as("a third player").isNotNull();
            assertThat(registry.roomCount()).isEqualTo(2);
        } finally {
            registry.close();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("a match's room is made by its first ticket, found by the next, takes nobody else, and needs a free room")
    void matchRooms() {
        RoomRegistry registry = new RoomRegistry(1_000f, 256, 4, 0, 2);
        try {
            com.backend.handoff.MatchMode duel = com.backend.handoff.MatchMode.DUEL;
            RoomThread made = registry.allocateMatch("01JCMATCH00000000000000001", duel);
            assertThat(made).isNotNull();
            assertThat(made.matchUid()).isEqualTo("01JCMATCH00000000000000001");
            assertThat(registry.allocateMatch("01JCMATCH00000000000000001", duel)).as("the second ticket")
                    .isSameAs(made);

            // No capacity arithmetic in a match's room: a room counts a player it admits before
            // it releases that player's place (T-4), so in a room of two the second player was
            // refused while the first was being let in. Found live; its timing is not one a test
            // can force, so what is pinned is that these places are not counted at all.
            for (int i = 0; i < 3; i++) {
                assertThat(made.reserveForMatch()).isTrue();
                made.releaseReservation();
            }
            assertThat(registry.allocateMatch("01JCMATCH00000000000000001", duel))
                    .as("the registry does not count them either: admission is the guard").isSameAs(made);
            made.releaseReservation();

            RoomThread open = registry.allocate();
            assertThat(open).as("an open join opens a room of its own").isNotSameAs(made);
            assertThat(registry.roomCount()).isEqualTo(2);
            assertThat(registry.allocateMatch("01JCMATCH00000000000000002", duel))
                    .as("no free room for another match").isNull();
            assertThat(registry.allocateMatch("01JCMATCH00000000000000003", com.backend.handoff.MatchMode.FFA))
                    .as("the public arena is not made").isNull();
            for (int i = 0; i < 3; i++) {
                assertThat(registry.allocate()).as("open joins fill the open room, never the made one")
                        .isSameAs(open);
            }
        } finally {
            registry.close();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("the arena announces its rooms and how many it may run, which the matcher needs to send it a match")
    void announcesItsRooms() throws Exception {
        RoomRegistry registry = new RoomRegistry(1_000f, 256, 4, 0, 3);
        try (com.jredis.embedded.JRedisEmbedded store = com.jredis.embedded.JRedisEmbedded.start();
             com.jredis.client.JRedisClient client = store.newClient()) {
            registry.startInitialRoom();
            registry.allocateMatch("01JCMATCH00000000000000009", com.backend.handoff.MatchMode.DUEL);
            com.backend.handoff.ArenaDirectory directory = new com.backend.handoff.ArenaDirectory(client);
            client.sync().send("ZADD", "rooms:promised:arena-9", Long.toString(System.currentTimeMillis() + 60_000),
                    "01JCMATCH00000000000000009");
            try (ArenaAnnouncer announcer = new ArenaAnnouncer(directory, "arena-9", "10.0.0.9", 9009, registry)) {
                announcer.start();
                assertThat(client.sync().zscore("rooms:promised:arena-9", "01JCMATCH00000000000000009"))
                        .as("its room announced, the promise dropped (D-42)").isNull();
                assertThat(directory.live()).singleElement().satisfies(e -> {
                    assertThat(e.rooms()).isEqualTo(2);
                    assertThat(e.maxRooms()).isEqualTo(3);
                });
                assertThat(directory.reserveForMatch("01JCMATCH00000000000000010").name()).isEqualTo("arena-9");
            }
        } finally {
            registry.close();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("a room made to replace a failed one does not take a living room's name")
    void roomNamesAreNeverReused() throws Exception {
        RoomRegistry registry = new RoomRegistry(1_000f, 256, 2, 0, 3);
        try {
            registry.allocate();
            registry.allocate();                    // room-1 full
            registry.allocate();                    // room-2
            RoomThread first = registry.rooms().get(0);
            first.tickHook = () -> {
                throw new IllegalStateException("injected");
            };
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (!first.hasFailed() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            registry.allocate();                    // drops room-1; room-2 full
            registry.allocate();                    // a new room
            // Named by how many rooms there were, the new one was "room-2" beside room-2: the
            // tick metric, keyed by name, showed one of them.
            assertThat(registry.rooms()).extracting(RoomThread::name).doesNotHaveDuplicates();
        } finally {
            registry.close();
        }
    }
}
