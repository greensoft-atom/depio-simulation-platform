package com.jredis.server.core;

/** Unwinds the command loop after a fail-stop in embedded mode (in server mode the JVM halts). */
public final class EngineStoppedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public EngineStoppedException(String message) {
        super(message);
    }
}
