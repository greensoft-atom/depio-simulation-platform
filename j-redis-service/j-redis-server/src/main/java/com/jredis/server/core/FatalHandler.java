package com.jredis.server.core;

/** What to do when the engine must stop because continuing could serve corrupted data. */
public interface FatalHandler {
    void fatal(String reason, Throwable cause, int exitCode);
}
