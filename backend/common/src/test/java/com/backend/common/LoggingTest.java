package com.backend.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.util.LogbackMDCAdapter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The shipped logging configuration, as every process loads it. Standard output goes to the
 * journal, and a journal that stops reading blocks whoever writes to it: with the writing done
 * on the thread that logs, that was a room thread in the middle of a tick, or a Netty event
 * loop, for as long as journald was stuck.
 */
class LoggingTest {

    private final PrintStream realOut = System.out;
    private LoggerContext context;

    /** After {@code System.setOut}: the console appender takes standard output when it starts. */
    private void configure() throws Exception {
        context = new LoggerContext();
        // What SLF4J's provider sets on the context it makes; without it every append fails
        // on the MDC, which logback reports only in its own status, and nothing is written.
        context.setMDCAdapter(new LogbackMDCAdapter());
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        URL shipped = LoggingTest.class.getResource("/logback.xml");
        assertThat(shipped).as("the shipped configuration").isNotNull();
        configurator.doConfigure(shipped);
    }

    @AfterEach
    void restore() {
        System.setOut(realOut);
        if (context != null) {
            context.stop();
        }
    }

    @Test
    @DisplayName("a journal that stops reading does not stop the thread that logs, errors included")
    void aStalledSinkDoesNotStallTheCaller() throws Exception {
        CountDownLatch released = new CountDownLatch(1);
        System.setOut(new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
                await(released);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                await(released);
            }
        }, true));
        configure();
        Logger log = context.getLogger("room-1");
        CountDownLatch done = new CountDownLatch(1);
        Thread room = new Thread(() -> {
            // More than any queue holds, and at the levels a queue keeps longest.
            for (int i = 0; i < 20_000; i++) {
                log.error("tick {} failed", i);
                log.warn("room has not ticked for {} ms", i);
                log.info("player {} joined", i);
            }
            done.countDown();
        }, "room-1");
        room.start();
        try {
            assertThat(done.await(10, TimeUnit.SECONDS)).as("the logging thread finished").isTrue();
        } finally {
            released.countDown();
            room.join(10_000);
        }
    }

    @Test
    @DisplayName("stopping the logging writes out what was still queued")
    void stoppingFlushes() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        configure();
        Logger log = context.getLogger("arena");
        for (int i = 0; i < 100; i++) {
            log.info("line {}", i);
        }
        log.error("LOST match m-1: could not be spooled during shutdown");

        context.stop();

        String written = out.toString(StandardCharsets.UTF_8);
        assertThat(written).contains("line 0", "line 99", "LOST match m-1");
    }

    @Test
    @DisplayName("Logs.flush writes out the process's queue before it halts")
    void flushWritesOutTheProcessQueue() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        Logger log = LoggerFactory.getLogger("arena");
        for (int i = 0; i < 5_000; i++) {
            log.info("result {} spooled", i);
        }

        Logs.flush();

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("result 0 spooled", "result 4999 spooled");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
