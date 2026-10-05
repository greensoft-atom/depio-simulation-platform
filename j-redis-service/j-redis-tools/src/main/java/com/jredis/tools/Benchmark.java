package com.jredis.tools;

import com.jredis.client.JRedisClient;
import com.jredis.client.JRedisClosedException;
import com.jredis.client.JRedisConnectionException;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.HdrHistogram.ConcurrentHistogram;
import org.HdrHistogram.Histogram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Load generator in the spirit of redis-benchmark, built on j-redis-client.
 *
 * <p>Each of {@code -c} clients has its own connection and keeps {@code -P} requests in flight
 * (a window: a new request is sent as soon as one completes). Latency is measured per request
 * from send to reply and reported as percentiles.
 */
final class Benchmark {

    private static final String USAGE = String.join("\n",
            "usage: benchmark [options]",
            "  -h <host>       server host (127.0.0.1)",
            "  -p <port>       server port (6379)",
            "  -a <password>   password",
            "  -c <clients>    parallel connections (50)",
            "  -n <requests>   requests per test (100000)",
            "  -P <window>     requests in flight per connection (1 = no pipelining)",
            "  -d <bytes>      value size for SET/LPUSH/... (3)",
            "  -r <keyspace>   random keys/members from [0, keyspace); 0 = one fixed key (0)",
            "  -t <tests>      comma-separated tests (all): " + String.join(",", tests(3).keySet()),
            "  --threads <n>   client I/O threads (2)",
            "  -q              one line per test");

    private final String host;
    private final int port;
    private final String password;
    private final int clients;
    private final long requests;
    private final int window;
    private final int keyspace;
    private final int threads;
    private final boolean quiet;

    private Benchmark(String host, int port, String password, int clients, long requests, int window, int keyspace,
                      int threads, boolean quiet) {
        this.host = host;
        this.port = port;
        this.password = password;
        this.clients = clients;
        this.requests = requests;
        this.window = window;
        this.keyspace = keyspace;
        this.threads = threads;
        this.quiet = quiet;
    }

    static int run(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 6379;
        String password = null;
        int clients = 50;
        int requests = 100_000;
        int window = 1;
        int dataSize = 3;
        int keyspace = 0;
        int threads = 2;
        boolean quiet = false;
        String selected = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-h": host = Tools.value(args, ++i); break;
                case "-p": port = Tools.intValue(args, ++i); break;
                case "-a": password = Tools.value(args, ++i); break;
                case "-c": clients = Tools.intValue(args, ++i); break;
                case "-n": requests = Tools.intValue(args, ++i); break;
                case "-P": window = Tools.intValue(args, ++i); break;
                case "-d": dataSize = Tools.intValue(args, ++i); break;
                case "-r": keyspace = Tools.intValue(args, ++i); break;
                case "-t": selected = Tools.value(args, ++i).toLowerCase(Locale.ROOT); break;
                case "--threads": threads = Tools.intValue(args, ++i); break;
                case "-q": quiet = true; break;
                case "--help": System.out.println(USAGE); return 0;
                default: throw new Tools.UsageException("unknown option " + args[i] + "\n" + USAGE);
            }
        }
        if (clients < 1 || requests < 1 || window < 1 || dataSize < 0 || keyspace < 0 || threads < 1) {
            throw new Tools.UsageException("-c, -n, -P and --threads must be >= 1; -d and -r must be >= 0");
        }
        Map<String, Function<Benchmark, Object[]>> all = tests(dataSize);
        List<String> names = new ArrayList<>(all.keySet());
        if (selected != null) {
            names = Arrays.asList(selected.split(","));
            for (String name : names) {
                if (!all.containsKey(name)) {
                    throw new Tools.UsageException("unknown test '" + name + "'; known: " + String.join(",", all.keySet()));
                }
            }
        }
        Benchmark b = new Benchmark(host, port, password, clients, requests, window, keyspace, threads, quiet);
        return b.runAll(names, all);
    }

    /** Test name -> generator of one request's arguments. Tests run in this order. */
    private static Map<String, Function<Benchmark, Object[]>> tests(int dataSize) {
        byte[] data = new byte[dataSize];
        Arrays.fill(data, (byte) 'x');
        Map<String, Function<Benchmark, Object[]>> t = new LinkedHashMap<>();
        t.put("ping", b -> new Object[] {"PING"});
        t.put("set", b -> new Object[] {"SET", b.key("key:"), data});
        t.put("get", b -> new Object[] {"GET", b.key("key:")});
        t.put("incr", b -> new Object[] {"INCR", b.key("counter:")});
        t.put("lpush", b -> new Object[] {"LPUSH", "bench:list", data});
        t.put("rpush", b -> new Object[] {"RPUSH", "bench:list", data});
        t.put("lpop", b -> new Object[] {"LPOP", "bench:list"});
        t.put("rpop", b -> new Object[] {"RPOP", "bench:list"});
        t.put("sadd", b -> new Object[] {"SADD", "bench:set", b.key("member:")});
        t.put("hset", b -> new Object[] {"HSET", "bench:hash", b.key("field:"), data});
        t.put("zadd", b -> new Object[] {"ZADD", "bench:zset", b.score(), b.key("member:")});
        t.put("zincrby", b -> new Object[] {"ZINCRBY", "bench:zset", 1, b.key("member:")});
        t.put("zrevrank", b -> new Object[] {"ZREVRANK", "bench:zset", b.key("member:")});
        t.put("zrevrange10", b -> new Object[] {"ZREVRANGE", "bench:zset", 0, 9, "WITHSCORES"});
        t.put("zaround", b -> new Object[] {"J.ZAROUND", "bench:zset", b.key("member:"), 5, "REV", "WITHSCORES"});
        t.put("mset10", b -> {
            Object[] a = new Object[21];
            a[0] = "MSET";
            for (int i = 0; i < 10; i++) {
                a[1 + 2 * i] = b.key("key:");
                a[2 + 2 * i] = data;
            }
            return a;
        });
        return t;
    }

    private String key(String prefix) {
        if (keyspace == 0) {
            return prefix + "000000000000";
        }
        return prefix + String.format(Locale.ROOT, "%012d", ThreadLocalRandom.current().nextInt(keyspace));
    }

    private long score() {
        return ThreadLocalRandom.current().nextInt(1_000_000);
    }

    private int runAll(List<String> names, Map<String, Function<Benchmark, Object[]>> all) throws InterruptedException {
        EventLoopGroup group = new NioEventLoopGroup(threads, new DefaultThreadFactory("bench-io"));
        List<JRedisClient> conns = new ArrayList<>();
        try {
            for (int i = 0; i < clients; i++) {
                JRedisClient.Builder builder = JRedisClient.builder().address(host, port).eventLoopGroup(group)
                        .clientName("benchmark").commandTimeoutMillis(30_000);
                if (password != null) {
                    builder.password(password);
                }
                JRedisClient c = builder.build();
                conns.add(c);
                c.start();
                if (!c.awaitConnected(5_000)) {
                    System.err.println("cannot connect to " + host + ":" + port);
                    return 1;
                }
            }
            int failedTests = 0;
            for (String name : names) {
                if (!runTest(name.toUpperCase(Locale.ROOT), all.get(name), conns)) {
                    failedTests++;
                }
            }
            return failedTests == 0 ? 0 : 1;
        } finally {
            for (JRedisClient c : conns) {
                c.close();
            }
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    /** @return false if any request failed */
    private boolean runTest(String name, Function<Benchmark, Object[]> gen, List<JRedisClient> conns) throws InterruptedException {
        TestRun run = new TestRun(gen);
        long start = System.nanoTime();
        for (JRedisClient c : conns) {
            for (int w = 0; w < window; w++) {
                run.sendNext(c);
            }
        }
        run.done.await();
        double seconds = (System.nanoTime() - start) / 1e9;
        long completed = run.completed.get();
        double rps = completed / seconds;
        Histogram h = run.latency;
        if (quiet) {
            System.out.printf(Locale.ROOT, "%s: %.2f requests per second, p50=%.3f msec%n", name, rps,
                    h.getValueAtPercentile(50) / 1e6);
        } else {
            System.out.printf(Locale.ROOT, "====== %s ======%n", name);
            System.out.printf(Locale.ROOT, "  %d requests completed in %.2f seconds%n", completed, seconds);
            System.out.printf(Locale.ROOT, "  %d parallel clients, window %d%n", clients, window);
            System.out.printf(Locale.ROOT, "  %.2f requests per second%n", rps);
            System.out.printf(Locale.ROOT, "  latency (msec): avg %.3f  p50 %.3f  p95 %.3f  p99 %.3f  p99.9 %.3f  max %.3f%n%n",
                    h.getMean() / 1e6, h.getValueAtPercentile(50) / 1e6, h.getValueAtPercentile(95) / 1e6,
                    h.getValueAtPercentile(99) / 1e6, h.getValueAtPercentile(99.9) / 1e6, h.getMaxValue() / 1e6);
        }
        if (run.aborted) {
            System.out.printf(Locale.ROOT, "  ABORTED after %d requests: %s%n", completed, run.firstError.get());
            return false;
        }
        if (run.errors.get() > 0) {
            System.out.printf(Locale.ROOT, "  %d requests FAILED; first failure: %s%n", run.errors.get(), run.firstError.get());
            return false;
        }
        return true;
    }

    /**
     * State of one test; callbacks run on the client I/O threads. A lost connection aborts the test
     * (every further request would fail at once), which also keeps the send-on-completion loop from
     * recursing on fast failures.
     */
    private final class TestRun {
        final Function<Benchmark, Object[]> gen;
        final Histogram latency = new ConcurrentHistogram(TimeUnit.SECONDS.toNanos(60), 3);
        final AtomicLong remaining = new AtomicLong(requests);
        final AtomicLong inFlight = new AtomicLong();
        final AtomicLong completed = new AtomicLong();
        final AtomicLong errors = new AtomicLong();
        final AtomicReference<String> firstError = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        volatile boolean aborted;

        TestRun(Function<Benchmark, Object[]> gen) {
            this.gen = gen;
        }

        /**
         * Sends one more request if any are left. inFlight counts the requests being sent or
         * awaited; a completion starts its successor before releasing itself, so inFlight cannot
         * touch zero (which ends the test) while requests remain.
         */
        void sendNext(JRedisClient c) {
            inFlight.incrementAndGet();
            if (aborted || remaining.getAndDecrement() <= 0) {
                release();
                return;
            }
            long t0 = System.nanoTime();
            c.send(gen.apply(Benchmark.this)).whenComplete((reply, failure) -> {
                latency.recordValue(Math.min(System.nanoTime() - t0, latency.getHighestTrackableValue()));
                completed.incrementAndGet();
                if (failure != null) {
                    errors.incrementAndGet();
                    Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                    firstError.compareAndSet(null, cause.toString());
                    if (cause instanceof JRedisConnectionException || cause instanceof JRedisClosedException) {
                        aborted = true;
                    }
                }
                sendNext(c);
                release();
            });
        }

        private void release() {
            if (inFlight.decrementAndGet() == 0) {
                done.countDown();
            }
        }
    }
}
