package com.backend.handoff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import com.jredis.client.JRedisClient;
import com.jredis.embedded.JRedisEmbedded;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** A tournament match's grant, as worker keeps it and the platform reads it (04 §6). */
class TournamentGrantsTest {

    @Test
    @Timeout(30)
    @DisplayName("a grant is kept a player a tournament, for the ticket's life, and read back as written")
    void keptForTheTicketsLife() throws Exception {
        try (JRedisEmbedded server = JRedisEmbedded.start();
             JRedisClient store = server.newClient()) {
            TournamentGrants grants = new TournamentGrants(store);
            assertThat(grants.get(7, 4711).get(5, TimeUnit.SECONDS)).as("none yet").isNull();

            grants.put(7, 4711, "{\"ticketId\":\"t1\"}").get(5, TimeUnit.SECONDS);
            grants.put(8, 4711, "{\"ticketId\":\"t2\"}").get(5, TimeUnit.SECONDS);

            assertThat(grants.get(7, 4711).get(5, TimeUnit.SECONDS)).isEqualTo("{\"ticketId\":\"t1\"}");
            assertThat(grants.get(8, 4711).get(5, TimeUnit.SECONDS)).as("another tournament's")
                    .isEqualTo("{\"ticketId\":\"t2\"}");
            assertThat(grants.get(7, 4712).get(5, TimeUnit.SECONDS)).as("another player's").isNull();
            assertThat(store.sync().ttl(TournamentGrants.key(7, 4711)))
                    .as("no longer than the ticket it names").isBetween(1L, (long) TicketStore.TTL_SECONDS);
        }
    }
}
