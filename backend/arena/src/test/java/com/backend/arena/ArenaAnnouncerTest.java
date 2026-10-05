package com.backend.arena;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import com.backend.handoff.ArenaDirectory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jredis.client.JRedisClient;
import com.jredis.embedded.JRedisEmbedded;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The arena's presence in the store: its rooms announced, and an operator's commands heard (04 §10). */
@Timeout(60)
class ArenaAnnouncerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("each announcement carries the rooms; a close or a kick published on the arena's channel reaches its rooms")
    void roomsAndCommands() throws Exception {
        JRedisEmbedded store = JRedisEmbedded.start();
        JRedisClient client = store.newClient();
        RoomRegistry registry = new RoomRegistry(1_000f, 2_048, 10, 10, 2);
        registry.startInitialRoom();
        ArenaDirectory directory = new ArenaDirectory(client);
        try (ArenaAnnouncer announcer = new ArenaAnnouncer(directory, "arena-t", "127.0.0.1", 9001, registry)) {
            announcer.start();
            JsonNode rooms = JSON.readTree(directory.roomList("arena-t"));
            assertThat(rooms).hasSize(1);
            String room = rooms.get(0).get("room").asText();
            assertThat(room).startsWith("room-");
            assertThat(rooms.get(0).get("players").asInt()).isZero();
            assertThat(rooms.get(0).get("stage").asText()).isEqualTo("open");

            assertThat(directory.command("arena-t", JSON.createObjectNode().put("cmd", "close").put("room", room))
                    .get(5, TimeUnit.SECONDS)).as("heard").isEqualTo(1L);
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (registry.views().stream().anyMatch(v -> v.room().equals(room)) && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(registry.views()).as("closed").noneMatch(v -> v.room().equals(room));
            // Not ours, or not a command: heard, and ignored.
            client.publish(ArenaDirectory.commandChannel("arena-t"), "not json").get(5, TimeUnit.SECONDS);
            client.publish(ArenaDirectory.commandChannel("arena-t"), "{\"cmd\":\"dance\"}").get(5, TimeUnit.SECONDS);
            Thread.sleep(200);
            assertThat(registry.views()).as("nothing else happened").isEmpty();

            // A ban's kick is remembered, its player's tickets refused for their minute; a kick's is not (T-35).
            directory.command("arena-t", JSON.createObjectNode().put("cmd", "kick").put("player", 9911))
                    .get(5, TimeUnit.SECONDS);
            directory.command("arena-t", JSON.createObjectNode().put("cmd", "kick").put("player", 9910).put("ban", true))
                    .get(5, TimeUnit.SECONDS);
            long until = System.nanoTime() + 5_000_000_000L;
            while (!registry.removed(9910) && System.nanoTime() < until) {
                Thread.sleep(50);
            }
            assertThat(registry.removed(9910)).as("banned").isTrue();
            assertThat(registry.removed(9911)).as("kicked").isFalse();
        } finally {
            registry.close();
            client.close();
            store.close();
        }
    }
}
