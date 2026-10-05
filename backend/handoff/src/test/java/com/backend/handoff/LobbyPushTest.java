package com.backend.handoff;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jredis.client.JRedisClient;
import com.jredis.embedded.JRedisEmbedded;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The push route's two ends, around a real store (03 §5). */
class LobbyPushTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("what is published parses back to whom it is for and the lobby message itself")
    void envelopeRoundTrips() throws Exception {
        String env = LobbyPush.envelope(4711, "evt.match.found",
                JSON.createObjectNode().put("arenaPort", 9001).put("name", "Zoë"));
        LobbyPush.Delivery d = LobbyPush.Delivery.parse(env.getBytes(StandardCharsets.UTF_8));
        assertThat(d.type()).as("its type, for the gateway to act on").isNotBlank();
        assertThat(d.to()).isEqualTo(4711);
        assertThat(JSON.readTree(d.message()).get("t").asText()).isEqualTo("evt.match.found");
        assertThat(JSON.readTree(d.message()).path("d").get("name").asText()).isEqualTo("Zoë");
        assertThat(JSON.readTree(d.message()).has("to")).as("the routing stays behind").isFalse();
    }

    @Test
    @DisplayName("anything else on the channel is refused, not delivered")
    void malformedIsNull() {
        for (String bad : new String[] {"", "not json", "[]", "{\"to\":1}", "{\"msg\":{\"t\":\"x\"}}",
                "{\"to\":\"ada\",\"msg\":{\"t\":\"x\"}}", "{\"to\":1,\"msg\":\"x\"}", "{\"to\":1,\"msg\":{}}"}) {
            assertThat(LobbyPush.Delivery.parse(bad.getBytes(StandardCharsets.UTF_8))).as(bad).isNull();
        }
    }

    @Test
    @DisplayName("a registration routes by the gateway before the #")
    void gatewayOfARegistration() {
        assertThat(LobbyPush.gatewayOf("gw-2#k3v9q-812")).isEqualTo("gw-2");
        assertThat(LobbyPush.gatewayOf("gw-2")).isEqualTo("gw-2");
        assertThat(LobbyPush.channelOf("gw-2")).isEqualTo("push:gw-2");
    }

    @Test
    @Timeout(30)
    @DisplayName("a push to a player registered on a gateway nobody hears is counted unheard: a subscription gone astray (O-6)")
    void unheardIsCounted() throws Exception {
        try (JRedisEmbedded store = JRedisEmbedded.start();
             JRedisClient sender = store.newClient();
             JRedisClient gateway = store.newClient()) {
            sender.sync().set("conn:4711", "gw-2#a-1");
            sender.sync().set("conn:4712", "gw-3#b-1");
            gateway.pubSub().subscribe("push:gw-2", (ch, msg) -> { }).get(5, TimeUnit.SECONDS);
            LobbyPush push = new LobbyPush(sender);
            assertThat(push.send(4711, "evt.x", null).get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(push.send(4713, "evt.x", null).get(5, TimeUnit.SECONDS)).as("not connected anywhere").isFalse();
            assertThat(push.unheard()).as("neither is unheard").isZero();
            assertThat(push.send(4712, "evt.x", null).get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(push.unheard()).isEqualTo(1);
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("a push goes to the channel of the gateway holding the player, and nowhere for a player not held")
    void sendRoutesByTheRegistration() throws Exception {
        try (JRedisEmbedded store = JRedisEmbedded.start();
             JRedisClient sender = store.newClient();
             JRedisClient gateway = store.newClient()) {
            CompletableFuture<byte[]> heard = new CompletableFuture<>();
            gateway.pubSub().subscribe("push:gw-2", (ch, msg) -> heard.complete(msg)).get(5, TimeUnit.SECONDS);
            sender.sync().set("conn:4711", "gw-2#k3v9q-812");
            LobbyPush push = new LobbyPush(sender);

            assertThat(push.send(4711, "evt.x", null).get(5, TimeUnit.SECONDS)).isTrue();
            LobbyPush.Delivery d = LobbyPush.Delivery.parse(heard.get(5, TimeUnit.SECONDS));
            assertThat(d.to()).isEqualTo(4711);
            assertThat(JSON.readTree(d.message()).get("d").isObject()).as("no data is an empty object").isTrue();

            assertThat(push.send(4712, "evt.x", null).get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(push.connected(4711).get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(push.connected(4712).get(5, TimeUnit.SECONDS)).as("no registration").isFalse();
            sender.sync().set("conn:4713", "gw-9#x-1");
            assertThat(push.connected(java.util.List.of(4711L, 4712L, 4713L)).get(5, TimeUnit.SECONDS))
                    .as("many in one read, in order (S-15)").containsExactly(true, false, true);
            assertThat(push.connected(java.util.List.of()).get(5, TimeUnit.SECONDS)).isEmpty();
            assertThat(push.send(4713, "evt.x", null).get(5, TimeUnit.SECONDS))
                    .as("a gateway nobody is listening for").isFalse();
        }
    }
}
