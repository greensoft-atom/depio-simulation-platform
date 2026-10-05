package com.jredis.server.core;

import com.jredis.server.config.ServerConfig;
import com.jredis.server.persist.AofPersistence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A fail-stop keeps the effects of the commands that completed and drops those of the one that
 * failed half-way, which never finished changing the data (docs/02-architecture.md §11).
 */
class FailStopEffectsTest {

    private static byte[][] cmd(String... words) {
        byte[][] out = new byte[words.length][];
        for (int i = 0; i < words.length; i++) {
            out[i] = words[i].getBytes(StandardCharsets.US_ASCII);
        }
        return out;
    }

    @Test
    void theFailedCommandsEffectsAreDropped(@TempDir Path dir) throws Exception {
        ServerConfig config = new ServerConfig();
        config.appendonly(true);
        config.dir(dir.toString());
        AtomicReference<String> stopped = new AtomicReference<>();
        Engine engine = new Engine(config, Clock.system(), (reason, cause, code) -> stopped.set(reason));
        engine.persistence(AofPersistence.open(engine, config));
        try {
            engine.effects().markCommandStart();
            engine.propagate(cmd("SET", "completed", "1"));
            engine.effects().markCommandStart();              // the next command starts
            engine.propagate(cmd("SET", "half-done", "1"));
            assertThatThrownBy(() -> engine.fatal("a test", new IllegalStateException("boom")))
                    .isInstanceOf(EngineStoppedException.class);
            assertThat(stopped.get()).isEqualTo("a test");
        } finally {
            engine.persistence().shutdown();
        }
        String aof = Files.readString(dir.resolve("incr.1.aof"), StandardCharsets.US_ASCII);
        assertThat(aof).contains("completed").doesNotContain("half-done");
    }
}
