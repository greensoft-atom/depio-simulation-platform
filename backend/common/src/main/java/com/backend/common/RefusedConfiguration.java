package com.backend.common;

/**
 * The process was started with settings it cannot run with — a credential file that cannot be
 * read, a store that demands a password nobody gave. Every main exits 2 for this, which the
 * units list in {@code RestartPreventExitStatus}: restarting cannot fix it, and a restart loop
 * only buries the one log line that says what to fix.
 */
public final class RefusedConfiguration extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RefusedConfiguration(String message) {
        super(message);
    }

    public RefusedConfiguration(String message, Throwable cause) {
        super(message, cause);
    }
}
