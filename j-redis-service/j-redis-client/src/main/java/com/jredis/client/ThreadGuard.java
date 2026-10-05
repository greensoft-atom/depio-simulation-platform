package com.jredis.client;

/**
 * Marks threads that must never block (latency-critical worker or event-loop threads). The sync facade refuses to run
 * on them, turning a latency bug into an immediate, obvious exception.
 */
public final class ThreadGuard {

    private static final ThreadLocal<Boolean> NON_BLOCKING = new ThreadLocal<>();

    private ThreadGuard() {
    }

    public static void markNonBlocking() {
        NON_BLOCKING.set(Boolean.TRUE);
    }

    public static void unmark() {
        NON_BLOCKING.remove();
    }

    public static boolean isNonBlocking() {
        return Boolean.TRUE.equals(NON_BLOCKING.get());
    }
}
