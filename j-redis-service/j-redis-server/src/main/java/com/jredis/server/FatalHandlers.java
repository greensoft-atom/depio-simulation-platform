package com.jredis.server;

import com.jredis.server.core.FatalHandler;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

/** Fail-stop strategies. */
public final class FatalHandlers {

    private FatalHandlers() {
    }

    /**
     * Server mode: flush the logs and halt the JVM with the exit code, without running shutdown
     * hooks (which would try to talk to the stopped engine). systemd restarts the process and the
     * AOF brings back the last consistent state.
     */
    public static FatalHandler haltProcess() {
        return (reason, cause, exitCode) -> {
            try {
                ILoggerFactory f = LoggerFactory.getILoggerFactory();
                f.getClass().getMethod("stop").invoke(f);       // logback's LoggerContext.stop(), flushes async appenders
            } catch (Throwable ignored) {
                // not logback, or flushing failed (even with a linkage error): halting matters more
            }
            Runtime.getRuntime().halt(exitCode);
        };
    }
}
