package com.jredis.examples;

import com.jredis.client.JRedisClient;
import com.jredis.embedded.EmbeddedConfig;
import com.jredis.embedded.JRedisEmbedded;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Runs the examples.
 *
 * <pre>
 * java -jar j-redis-examples.jar                     all examples, against an in-process server
 * java -jar j-redis-examples.jar cache lock          only these
 * java -jar j-redis-examples.jar -p 6379 [-h host] [-a password] [names...]   against a running server
 * java -jar j-redis-examples.jar --list
 * </pre>
 */
public final class Examples {

    /** Every example, in a sensible reading order. */
    public static final List<Example> ALL = Arrays.asList(
            new QuickStart(),
            new StringsAndCounters(),
            new CacheAside(),
            new SessionStore(),
            new Leaderboard(),
            new RateLimiter(),
            new DistributedLock(),
            new OptimisticLocking(),
            new OneTimeToken(),
            new WorkQueue(),
            new PubSubNotifications(),
            new ServiceRegistry(),
            new AsyncPipelining(),
            new ScanKeys(),
            new ErrorHandling());

    private Examples() {
    }

    public static void main(String[] args) throws Exception {
        boolean remote = false;
        String host = "127.0.0.1";
        int port = 6379;
        String password = null;
        List<String> names = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-h": host = args[++i]; remote = true; break;
                case "-p": port = Integer.parseInt(args[++i]); remote = true; break;
                case "-a": password = args[++i]; break;
                case "--list":
                    for (Example e : ALL) {
                        System.out.printf("  %-10s %s%n", e.name(), e.summary());
                    }
                    return;
                default: names.add(args[i]);
            }
        }
        List<Example> selected = new ArrayList<>();
        for (Example e : ALL) {
            if (names.isEmpty() || names.contains(e.name())) {
                selected.add(e);
            }
        }
        if (selected.isEmpty()) {
            System.err.println("unknown example(s) " + names + "; use --list");
            System.exit(2);
        }
        PrintStream out = System.out;
        JRedisEmbedded embedded = null;
        JRedisClient client;
        if (!remote) {
            out.println("(no -h/-p given: running against an in-process server)");
            embedded = JRedisEmbedded.start(EmbeddedConfig.inMemory());
            client = embedded.newClient();
        } else {
            client = JRedisClient.builder().address(host, port).password(password).clientName("j-redis-examples")
                    .build().start();
        }
        try {
            for (Example e : selected) {
                out.println();
                out.println("=== " + e.name() + ": " + e.summary() + " ===");
                e.run(client, out);
            }
        } finally {
            client.close();
            if (embedded != null) {
                embedded.close();
            }
        }
    }
}
