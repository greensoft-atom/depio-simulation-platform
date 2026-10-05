package com.backend.arena;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import com.backend.handoff.MatchOutcome;
import com.backend.handoff.MatchOutcome.PlayerOutcome;
import com.backend.handoff.MatchResultCodec;
import com.backend.handoff.MatchResultStream;
import com.backend.handoff.Ulid;

import com.jredis.client.JRedisClient;
import com.jredis.embedded.JRedisEmbedded;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * What happens to a finished match when the store is not there.
 *
 * The store is stopped for real rather than stubbed to throw, because the failure being
 * tested is the one that actually happens — j-redis restarting under a running arena — and a
 * stub proves only that the stub throws.
 */
class MatchResultPublisherTest {

    private static MatchOutcome outcome() {
        long now = System.currentTimeMillis();
        return new MatchOutcome(Ulid.generate(), MatchOutcome.KIND_OPEN, 0, "arena-1",
                now - 60_000, now,
                List.of(new PlayerOutcome(1L, "Ada", 0, 1, 3, 1, 250, 60)));
    }

    private static long spoolCount(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json")).count();
        }
    }

    /** The matches in the result stream, in the order they were added. */
    private static List<String> streamed(JRedisClient client) {
        return client.sync().xrange(MatchResultStream.KEY, "-", "+").stream()
                .map(e -> MatchResultCodec.decode(e.fields().get(MatchResultStream.FIELD)).matchUid())
                .toList();
    }

    /** Fails if the condition never comes; a silent give-up lets a test pass on nothing. */
    private static void waitFor(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (!condition.call()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 30 s");
            }
            Thread.sleep(50);
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a published result reaches the queue and leaves no file behind")
    void publishesAndClearsTheSpool(@TempDir Path spool) throws Exception {
        try (JRedisEmbedded server = JRedisEmbedded.start()) {
            JRedisClient client = server.newClient();
            MatchResultStream queue = new MatchResultStream(client);
            MatchResultPublisher publisher = new MatchResultPublisher(queue, spool);
            publisher.start();

            MatchOutcome match = outcome();
            publisher.publish(match);
            waitFor(() -> publisher.publishedCount() == 1);

            assertThat(streamed(client)).containsExactly(match.matchUid());
            assertThat(spoolCount(spool)).as("the file goes once the queue has it").isZero();
            publisher.close();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("with the store down the result waits on disk, and a restart sends it")
    void spoolSurvivesTheStoreBeingDown(@TempDir Path spool) throws Exception {
        MatchOutcome match = outcome();

        JRedisEmbedded firstServer = JRedisEmbedded.start();
        JRedisClient firstClient = firstServer.newClient();
        MatchResultPublisher publisher =
                new MatchResultPublisher(new MatchResultStream(firstClient), spool);
        publisher.start();

        firstServer.close();                       // the store goes away mid-match
        publisher.publish(match);

        // The push keeps failing, so the file stays: this is the only copy of the match.
        waitFor(() -> spoolCount(spool) == 1);
        assertThat(spoolCount(spool)).as("the result is on disk, not lost").isEqualTo(1);
        assertThat(publisher.publishedCount()).isZero();
        publisher.close();

        // A new process, a working store: start-up replays what the last one could not send.
        try (JRedisEmbedded secondServer = JRedisEmbedded.start()) {
            JRedisClient secondClient = secondServer.newClient();
            MatchResultStream queue = new MatchResultStream(secondClient);
            MatchResultPublisher restarted = new MatchResultPublisher(queue, spool);
            restarted.start();

            assertThat(restarted.replayedCount()).isEqualTo(1);
            assertThat(streamed(secondClient)).containsExactly(match.matchUid());
            assertThat(spoolCount(spool)).isZero();
            restarted.close();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a result the disk refuses is tried again every few seconds, not in a spin")
    void aRefusedSpoolIsRetriedCalmly(@TempDir Path spool) throws Exception {
        try (JRedisEmbedded server = JRedisEmbedded.start()) {
            MatchResultPublisher publisher = new MatchResultPublisher(
                    new MatchResultStream(server.newClient()), spool);
            publisher.start();
            MatchOutcome match = outcome();
            // Its temporary file's name taken by a directory, so every write fails, as on a
            // full disk; tests run as root here, which permissions do not stop.
            Path blocker = Files.createDirectory(spool.resolve(match.matchUid() + ".tmp"));
            publisher.publish(match);
            Thread.sleep(1_000);
            // Put back at the front of the line, it was found there at once by the wait for
            // work: the loop spun, logging an error and counting a failure on every pass.
            assertThat(publisher.spoolFailureCount()).isBetween(1L, 2L);

            Files.delete(blocker);
            waitFor(() -> publisher.publishedCount() == 1);     // kept, and sent once written
            publisher.close();
        }
    }

    /** A publisher whose store has already gone away, which is the case that matters. */
    private static MatchResultPublisher publisherWithTheStoreDown(Path spool) {
        JRedisEmbedded server = JRedisEmbedded.start();
        JRedisClient client = server.newClient();
        MatchResultPublisher publisher = new MatchResultPublisher(new MatchResultStream(client), spool);
        publisher.start();
        server.close();
        return publisher;
    }

    @Test
    @Timeout(60)
    @DisplayName("during a store outage the whole backlog is on disk, not only the first result")
    void theBacklogIsOnDiskDuringAnOutage(@TempDir Path spool) throws Exception {
        MatchResultPublisher publisher = publisherWithTheStoreDown(spool);
        try {
            for (int i = 0; i < 5; i++) {
                publisher.publish(outcome());
            }

            // The test above publishes ONE result, which is why it never saw this. The first
            // result was spooled, then the push for it retried for ever, and every result
            // after it waited in memory behind that push — during exactly the outage the
            // spool exists for. An arena killed at that point lost all of them.
            waitFor(() -> spoolCount(spool) == 5);
            assertThat(spoolCount(spool)).as("every result on disk, with the store still down")
                    .isEqualTo(5);
        } finally {
            publisher.close();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("closing drains what is queued to disk instead of abandoning it")
    void closeDrainsToDisk(@TempDir Path spool) throws Exception {
        MatchResultPublisher publisher = publisherWithTheStoreDown(spool);
        for (int i = 0; i < 5; i++) {
            publisher.publish(outcome());
        }

        // No waiting: a shutdown straight after the last result is the ordinary case, not
        // an unlucky one. close() used to set running=false and then interrupt, so the poll
        // threw and the thread returned before the drain its own loop condition promised.
        publisher.close();

        assertThat(spoolCount(spool)).as("nothing queued in memory is lost on shutdown")
                .isEqualTo(5);
    }

    @Test
    @Timeout(60)
    @DisplayName("closing with the store up delivers the backlog instead of leaving it on disk")
    void closeDeliversWhenItCan(@TempDir Path spool) throws Exception {
        try (JRedisEmbedded server = JRedisEmbedded.start()) {
            JRedisClient client = server.newClient();
            MatchResultStream queue = new MatchResultStream(client);
            MatchResultPublisher publisher = new MatchResultPublisher(queue, spool);
            publisher.start();
            for (int i = 0; i < 40; i++) {
                publisher.publish(outcome());
            }

            // What a deploy does: forty players' results published by the rooms, and close()
            // straight after. Measured in the drill before this was fixed: 40 spooled, 1
            // pushed, 39 left on disk — safe, but on the disk of an arena that might be being
            // retired, where nothing would ever send them.
            publisher.close();

            assertThat(streamed(client)).as("delivered, with the store up").hasSize(40);
            assertThat(spoolCount(spool)).as("so nothing is left behind on disk").isZero();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a result published after close is still written down")
    void lateResultIsStillSpooled(@TempDir Path spool) throws Exception {
        MatchResultPublisher publisher = publisherWithTheStoreDown(spool);
        publisher.close();

        // A room thread can finish a player's match a moment after the publisher has
        // stopped. Adding it to a queue nobody drains any more is losing it quietly.
        publisher.publish(outcome());

        assertThat(spoolCount(spool)).isEqualTo(1);
    }

    @Test
    @Timeout(60)
    @DisplayName("each result added trims what the stream has kept longer than a day, and only that")
    void theStreamKeepsADay(@TempDir Path spool) throws Exception {
        try (JRedisEmbedded server = JRedisEmbedded.start()) {
            JRedisClient client = server.newClient();
            long now = System.currentTimeMillis();
            long day = 24L * 60 * 60 * 1000;
            MatchOutcome old = outcome();
            MatchOutcome recent = outcome();
            client.sync().send("XADD", MatchResultStream.KEY, (now - day - 60_000) + "-0",
                    MatchResultStream.FIELD, MatchResultCodec.encode(old));
            client.sync().send("XADD", MatchResultStream.KEY, (now - day + 60_000) + "-0",
                    MatchResultStream.FIELD, MatchResultCodec.encode(recent));
            MatchResultPublisher publisher = new MatchResultPublisher(new MatchResultStream(client), spool);
            publisher.start();
            MatchOutcome match = outcome();
            publisher.publish(match);
            waitFor(() -> publisher.publishedCount() == 1);
            assertThat(streamed(client)).as("a day and a minute old: gone; a minute short of a day: kept")
                    .containsExactly(recent.matchUid(), match.matchUid());
            publisher.close();
        }
    }

    /** A real server over TCP; a replica lands its image in {@code dir}. */
    private static com.jredis.server.JRedisServer tcpServer(Path dir) throws Exception {
        com.jredis.server.config.ServerConfig c = new com.jredis.server.config.ServerConfig();
        c.port(0);
        c.bind(List.of("127.0.0.1"));
        c.appendonly(false);
        c.dir(dir.toString());
        return com.jredis.server.JRedisServer.start(c, com.jredis.server.core.Clock.system(), (reason, cause, code) -> { }, null);
    }

    @Test
    @Timeout(60)
    @DisplayName("with no replica configured, a result is let go at once and nothing is counted")
    void withoutAReplicaNothingWaits(@TempDir Path spool) throws Exception {
        try (JRedisEmbedded server = JRedisEmbedded.start()) {
            JRedisClient client = server.newClient();
            MatchResultPublisher publisher = new MatchResultPublisher(new MatchResultStream(client), spool, false);
            publisher.start();
            publisher.publish(outcome());
            waitFor(() -> publisher.publishedCount() == 1);
            assertThat(publisher.unreplicatedCount()).isZero();
            publisher.close();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a wait that fails after the result was added counts it as not confirmed")
    void aFailedWaitIsNotAConfirmation(@TempDir Path spool) throws Exception {
        try (JRedisEmbedded server = JRedisEmbedded.start(com.jredis.embedded.EmbeddedConfig.inMemory()
                .configure(c -> c.disabledCommands().add("WAIT")))) {    // WAIT refused, as by a store going
            MatchResultPublisher publisher = new MatchResultPublisher(new MatchResultStream(server.newClient()), spool, true);
            publisher.start();
            publisher.publish(outcome());
            waitFor(() -> publisher.unreplicatedCount() == 1);
            waitFor(() -> spoolCount(spool) == 0);
            publisher.close();
        }
    }

    @Test
    @Timeout(90)
    @DisplayName("with a replica, a result is let go once the replica holds it, and one it does not is counted and let go all the same")
    void aResultWaitsAMomentForTheReplica(@TempDir Path dir) throws Exception {
        Path spool = Files.createDirectory(dir.resolve("spool"));
        com.jredis.server.JRedisServer primary = tcpServer(Files.createDirectory(dir.resolve("p")));
        com.jredis.server.JRedisServer replica = tcpServer(Files.createDirectory(dir.resolve("r")));
        try (JRedisClient r = JRedisClient.builder().address("127.0.0.1", replica.port()).build().start();
             JRedisClient events = JRedisClient.builder().addresses("127.0.0.1:" + primary.port(),
                     "127.0.0.1:" + replica.port()).build().start()) {
            r.sync().send("REPLICAOF", "127.0.0.1", Integer.toString(primary.port()));
            waitFor(() -> r.sync().send("INFO", "replication").toString().contains("primary_link_status:up"));
            MatchResultPublisher publisher = new MatchResultPublisher(new MatchResultStream(events), spool, true);
            publisher.start();
            for (int i = 0; i < 3; i++) {
                publisher.publish(outcome());
            }
            // Counted as published before the replica is asked, and let go after (T-23): all three
            // let go, so the replica has answered for each before it stops.
            waitFor(() -> publisher.publishedCount() == 3 && spoolCount(spool) == 0);
            assertThat(publisher.unreplicatedCount()).as("the replica held each").isZero();
            replica.stop();                                     // the replica's machine goes
            publisher.publish(outcome());
            waitFor(() -> publisher.unreplicatedCount() == 1);  // not confirmed within the wait: counted
            waitFor(() -> spoolCount(spool) == 0);               // and let go all the same (Q-11)
            assertThat(publisher.publishedCount()).isEqualTo(4);
            publisher.close();
        } finally {
            primary.stop();
            replica.stop();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("replay sends older matches before newer ones")
    void replayIsInOrder(@TempDir Path spool) throws Exception {
        // ULIDs sort by the millisecond they were made, and the file names are ULIDs, so
        // the order on disk is the order the matches ended.
        MatchOutcome first = outcome();
        Thread.sleep(5);
        MatchOutcome second = outcome();
        Files.writeString(spool.resolve(first.matchUid() + ".json"),
                MatchResultCodec.encode(first));
        Files.writeString(spool.resolve(second.matchUid() + ".json"),
                MatchResultCodec.encode(second));

        try (JRedisEmbedded server = JRedisEmbedded.start()) {
            JRedisClient client = server.newClient();
            MatchResultStream queue = new MatchResultStream(client);
            MatchResultPublisher publisher = new MatchResultPublisher(queue, spool);
            publisher.start();

            assertThat(publisher.replayedCount()).isEqualTo(2);
            assertThat(streamed(client)).as("the match that ended first is applied first")
                    .containsExactly(first.matchUid(), second.matchUid());
            publisher.close();
        }
    }
}
