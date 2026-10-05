package com.jredis.examples;

import com.jredis.client.JRedisClient;

import java.io.PrintStream;

/**
 * One self-contained example. Every example uses only keys under its own prefix and deletes them
 * first, so it can be run again, and against a shared server.
 */
public interface Example {

    /** Short name used on the command line, e.g. {@code cache}. */
    String name();

    /** One line describing what the example shows. */
    String summary();

    void run(JRedisClient client, PrintStream out) throws Exception;
}
