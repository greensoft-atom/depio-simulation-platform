package com.jredis.server.repl;

import com.jredis.server.config.ServerConfig;
import com.jredis.server.core.Client;
import com.jredis.server.core.ClientOutput;
import com.jredis.server.core.Clock;
import com.jredis.server.core.Engine;
import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A full sync's instant falls between two chunks of effects (docs/16-replication.md §5.2): what a
 * batch did before the {@code PSYNC} is in the image, and must not follow it again in the stream.
 */
class FullSyncInstantTest {

    /** A connection that keeps everything written to it. */
    private static final class Recording implements ClientOutput {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Client client;

        @Override
        public synchronized void write(ByteBuf b) {
            byte[] a = new byte[b.readableBytes()];
            b.readBytes(a);
            b.release();
            bytes.write(a, 0, a.length);
        }

        synchronized String text() {
            return bytes.toString(StandardCharsets.ISO_8859_1);
        }

        @Override public void close() { }
        @Override public void abort() { }
        @Override public long pendingBytes() { return 0; }
        @Override public void commandDone(long n) { }
        @Override public String remoteAddress() { return "127.0.0.1:1"; }
        @Override public String localAddress() { return "127.0.0.1:2"; }
        @Override public Client client() { return client; }
        @Override public void client(Client c) { client = c; }
    }

    private static byte[][] cmd(String... w) {
        byte[][] out = new byte[w.length][];
        for (int i = 0; i < w.length; i++) {
            out[i] = w[i].getBytes(StandardCharsets.US_ASCII);
        }
        return out;
    }

    @Test
    void whatTheBatchDidBeforeThePsyncIsNotSentAgainAndRepliesKeepTheirPlace() throws Exception {
        ServerConfig config = new ServerConfig();
        config.appendonly(false);
        Engine engine = new Engine(config, Clock.system(), (reason, cause, code) -> { });
        engine.start("test-cmd");
        try {
            Recording out = new Recording();
            AtomicReference<Client> held = new AtomicReference<>();
            CompletableFuture<Void> ran = new CompletableFuture<>();
            engine.submit((Runnable) () -> {
                engine.startBacklog(1 << 20);                  // a replica came before: the backlog exists
                Client writer = engine.newLoadingClient();
                engine.executeLoading(writer, cmd("SET", "before", "1"));
                engine.executeLoading(writer, cmd("INCR", "counted-once"));   // in this batch, not yet flushed
                Client replica = new Client(99, out, engine.clock().nowMillis());
                out.client(replica);
                replica.authenticated = true;
                held.set(replica);
                engine.replyBuffer(replica).writeBytes("+OK\r\n".getBytes(StandardCharsets.US_ASCII));   // its own earlier command
                engine.replication().psync(replica, "?", -1);          // no history: a full sync
                ran.complete(null);
            });
            ran.get(10, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + 10_000_000_000L;
            String text;
            int mark;
            do {
                Thread.sleep(20);
                text = out.text();
                int eof = text.indexOf("$EOF:");
                mark = eof < 0 ? -1 : text.indexOf(text.substring(eof + 5, eof + 45), eof + 47);
            } while (mark < 0 && System.nanoTime() < deadline);
            assertThat(mark).as("the image was sent").isPositive();
            engine.submit((Runnable) () -> engine.writeError(held.get(), "ERR a reply no replica should see"));
            Thread.sleep(300);
            String all = out.text();
            assertThat(all).as("what came before, then the image").startsWith("+OK\r\n+FULLRESYNC ");
            assertThat(all.substring(mark + 40)).as("the stream after the image").doesNotContain("counted-once");
            assertThat(all).as("nothing but the stream once a replica").doesNotContain("no replica should see");
        } finally {
            engine.requestShutdown(false, null);
        }
    }
}
