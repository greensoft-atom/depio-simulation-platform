package com.backend.handoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.backend.common.RefusedConfiguration;
import com.jredis.client.JRedisClient;
import com.jredis.embedded.JRedisEmbedded;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The result stream's retention, a setting since D-83: read, refused, and trimmed by. */
class MatchResultStreamTest {

    private static final long HOUR = TimeUnit.HOURS.toMillis(1);

    @Test
    @DisplayName("BACKEND_RESULT_RETENTION_HOURS, 1 to 168, is the retention; unset or empty, a day (Q-9, D-83)")
    void retentionFromTheEnvironment() {
        assertThat(MatchResultStream.retentionFromEnvironment(Map.of())).isEqualTo(24 * HOUR);
        assertThat(MatchResultStream.retentionFromEnvironment(Map.of("BACKEND_RESULT_RETENTION_HOURS", "")))
                .isEqualTo(24 * HOUR);
        for (int hours : new int[] {1, 6, 168}) {
            assertThat(MatchResultStream.retentionFromEnvironment(
                    Map.of("BACKEND_RESULT_RETENTION_HOURS", Integer.toString(hours)))).isEqualTo(hours * HOUR);
        }
    }

    @Test
    @DisplayName("anything but a whole number of hours from 1 to 168 refuses the start")
    void aRetentionOutOfRangeIsRefused() {
        for (String value : List.of("0", "169", "-1", "1.5", "six", "24h", " 6", "0006000")) {
            assertThatThrownBy(() -> MatchResultStream.retentionFromEnvironment(
                    Map.of("BACKEND_RESULT_RETENTION_HOURS", value)))
                    .as(value).isInstanceOf(RefusedConfiguration.class)
                    .hasMessageContaining("BACKEND_RESULT_RETENTION_HOURS");
        }
    }

    @Test
    @DisplayName("an arena's add trims what is older than the retention set, and only that")
    void anAddTrimsByTheRetentionSet() {
        try (JRedisEmbedded server = JRedisEmbedded.start()) {
            JRedisClient client = server.newClient();
            long now = System.currentTimeMillis();
            client.sync().send("XADD", MatchResultStream.KEY, (now - 6 * HOUR - 60_000) + "-0", MatchResultStream.FIELD, "old");
            client.sync().send("XADD", MatchResultStream.KEY, (now - 6 * HOUR + 60_000) + "-0", MatchResultStream.FIELD, "recent");
            new MatchResultStream(client, 6 * HOUR).publish("new", now);
            assertThat(payloads(client)).as("six hours and a minute: gone; a minute short of six hours: kept")
                    .containsExactly("recent", "new");
        }
    }

    @Test
    @DisplayName("a worker's move from the inbox trims by the retention set too: it adds to the stream")
    void aWorkersMoveTrimsByTheRetentionSet() {
        try (JRedisEmbedded server = JRedisEmbedded.start()) {
            JRedisClient client = server.newClient();
            long now = System.currentTimeMillis();
            client.sync().send("XADD", MatchResultStream.KEY, (now - 2 * HOUR - 60_000) + "-0", MatchResultStream.FIELD, "old");
            client.sync().send("XADD", MatchResultStream.KEY, (now - 2 * HOUR + 60_000) + "-0", MatchResultStream.FIELD, "recent");
            client.sync().send("LPUSH", MatchResultQueue.KEY, "from the inbox");
            MatchResultStream worker = new MatchResultStream(client, "worker-a",
                    MatchResultStream.DEFAULT_CLAIM_IDLE_MILLIS, 2 * HOUR);
            assertThat(worker.drainInbox(false)).isEqualTo(1);
            assertThat(payloads(client)).containsExactly("recent", "from the inbox");
        }
    }

    private static List<String> payloads(JRedisClient client) {
        return client.sync().xrange(MatchResultStream.KEY, "-", "+").stream()
                .map(e -> e.fields().get(MatchResultStream.FIELD)).toList();
    }
}
