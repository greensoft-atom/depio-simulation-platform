package com.backend.common;

import ch.qos.logback.classic.LoggerContext;

import org.slf4j.LoggerFactory;

/** The end of a process's logging. */
public final class Logs {

    /**
     * Writes out every line still queued, waiting at most a second, and stops logging. Lines
     * are queued so that a stuck journal cannot stall the thread that logs (logback.xml), and
     * a queue still holding the last lines when the JVM halts loses them: the ones that say
     * what a stop left undone. So call it last, at the end of a shutdown, and before
     * {@code System.exit}; logback's own shutdown hook would race the process's.
     */
    public static void flush() {
        try {
            if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
                context.stop();
            }
        } catch (LinkageError noLogback) {
            // A process without logback queued nothing.
        }
    }

    private Logs() {
    }
}
