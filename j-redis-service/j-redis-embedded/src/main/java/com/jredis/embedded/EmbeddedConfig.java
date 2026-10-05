package com.jredis.embedded;

import com.jredis.server.config.ServerConfig;
import com.jredis.server.core.Clock;

import java.nio.file.Path;
import java.util.function.Consumer;

/** Settings for an embedded server. In-memory by default. */
public final class EmbeddedConfig {

    final ServerConfig server = new ServerConfig();
    Clock clock = Clock.system();              // one per server: the cached instant belongs to its batch

    private EmbeddedConfig() {
    }

    /** No persistence: the fastest option for unit tests. */
    public static EmbeddedConfig inMemory() {
        EmbeddedConfig c = new EmbeddedConfig();
        c.server.appendonly(false);
        c.server.ioThreads(1);
        return c;
    }

    /** Full persistence in the given directory, exactly as the standalone server. */
    public static EmbeddedConfig persistentAt(Path dir) {
        EmbeddedConfig c = new EmbeddedConfig();
        c.server.appendonly(true);
        c.server.dir(dir.toString());
        c.server.ioThreads(1);
        return c;
    }

    /** Use a controllable clock (e.g. {@link com.jredis.server.core.ManualClock}) for TTL tests. */
    public EmbeddedConfig clock(Clock clock) {
        this.clock = clock;
        return this;
    }

    /** Adjust any server setting. */
    public EmbeddedConfig configure(Consumer<ServerConfig> change) {
        change.accept(server);
        return this;
    }
}
