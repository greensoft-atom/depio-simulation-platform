package com.jredis.server.persist;

/**
 * The engine's view of persistence. All methods are called on the command thread; the
 * implementation hands bytes to its own writer threads.
 */
public interface Persistence {

    boolean enabled();

    /** One batch's effects, encoded ({@link com.jredis.server.core.Effects}), for the AOF. */
    void append(byte[] chunk);

    /** End of a command batch, after its effects were appended: wait if the writer is far behind. */
    void endOfBatch();

    /** False while the AOF cannot be written; write commands then fail with MISCONF. */
    boolean writable();

    /** Background slice: snapshot progress and automatic rewrite triggers. */
    void background(long deadlineNanos);

    /** True while a rewrite is running (the background loop keeps going without idling). */
    boolean busy();

    /** BGSAVE SCHEDULE while a rewrite runs: start another when it ends. */
    default void scheduleRewrite() {
    }

    /** True if {@link #background} could do useful work right now (not merely waiting for the writer). */
    default boolean canProgress() {
        return busy();
    }

    boolean rewriteInProgress();

    /** Starts a background rewrite. @return null if started, otherwise the reason it was not */
    String startRewrite();

    void abortRewrite(String reason);

    /**
     * A replica's full sync: makes the image received from the primary this server's base, as a
     * rewrite's commit would, before anything is loaded from it (docs/16-replication.md §5.3).
     * Blocking. Without persistence there is nothing to install: the image is loaded where it is.
     *
     * @return where the image is now
     */
    default java.nio.file.Path installBase(java.nio.file.Path received) throws java.io.IOException {
        return received;
    }

    /** Synchronous rewrite (SAVE, SHUTDOWN SAVE): blocks the command thread until done. */
    void saveBlocking() throws Exception;

    long lastSaveSeconds();

    /** Clean shutdown: write and fsync everything, stop writer threads. Blocking. */
    void shutdown();

    /** Fail-stop: write and fsync effects of completed commands, as far as possible. */
    void emergencyFlush();

    void appendInfo(StringBuilder sb);

    /** DEBUG RELOAD: rewrite, then rebuild the dataset from the files (a persistence self-test). */
    void debugReload() throws Exception;
}
