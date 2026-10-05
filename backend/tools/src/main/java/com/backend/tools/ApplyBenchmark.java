package com.backend.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import com.backend.handoff.Ulid;
import com.backend.persistence.AccountRepository;
import com.backend.persistence.Database;
import com.backend.persistence.MatchResultRepository;
import com.backend.persistence.MatchResultRepository.MatchResult;
import com.backend.persistence.MatchResultRepository.PlayerResult;

/**
 * How fast results are applied, from one thread and from several at once (05 §1, plan item 26):
 * the worker's own transaction, a one-player result each, every thread with its own connection.
 * Commits made at once share their synchronous writes, so the answer is a rate a thread count.
 *
 * Usage: ApplyBenchmark jdbcUrl user password [results] [threads,...] [mysqld pid]
 *
 * It drops everything in the database and migrates it afresh, so it runs only on one whose name
 * ends in {@code _test}. The mysqld pid, where MySQL runs on this machine, adds its CPU a result.
 */
public final class ApplyBenchmark {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: ApplyBenchmark jdbcUrl user password [results] [threads,...] [mysqld pid]");
            System.exit(2);
        }
        String url = args[0];
        if (!isTestDatabase(url)) {
            System.err.println("refused: it drops everything in the database, and " + url
                    + " does not name one ending in _test");
            System.exit(2);
        }
        int results = args.length > 3 ? Integer.parseInt(args[3]) : 400;
        int[] threads = Arrays.stream((args.length > 4 ? args[4] : "1,2,4,8").split(","))
                .mapToInt(Integer::parseInt).toArray();
        long mysqld = args.length > 5 ? Long.parseLong(args[5]) : -1;
        int most = Arrays.stream(threads).max().orElse(1);

        try (Database db = new Database(url, args[1], args[2], most + 1)) {
            db.resetForTests();
            AccountRepository accounts = new AccountRepository(db.dataSource());
            int rounds = threads.length + 1;             // the first warms every connection
            long[] players = new long[results * rounds];
            for (int i = 0; i < players.length; i++) {
                players[i] = accounts.register("ab" + i, "ab" + i, "$argon2id$bench".getBytes(StandardCharsets.UTF_8));
            }
            MatchResultRepository repository = new MatchResultRepository(db.dataSource(), xp -> 1);
            for (int r = 0; r < rounds; r++) {
                int t = r == 0 ? most : threads[r - 1];
                long[] own0 = cpu(ProcessHandle.current().pid());
                long[] my0 = cpu(mysqld);
                long started = System.nanoTime();
                apply(repository, players, r * results, results, t);
                double seconds = (System.nanoTime() - started) / 1e9;
                long[] own1 = cpu(ProcessHandle.current().pid());
                long[] my1 = cpu(mysqld);
                if (r > 0) {
                    System.out.printf(Locale.ROOT, "threads %d: %d results in %.1f s, %.0f a second;"
                                    + " CPU a result, this process %.1f ms%s%n",
                            t, results, seconds, results / seconds,
                            (own1[0] + own1[1] - own0[0] - own0[1]) * 10.0 / results,
                            mysqld < 0 ? "" : String.format(Locale.ROOT, ", mysqld %.1f + %.1f ms (user + kernel)",
                                    (my1[0] - my0[0]) * 10.0 / results, (my1[1] - my0[1]) * 10.0 / results));
                }
            }
        }
    }

    /** A database named for tests: one, and ending in {@code _test}. */
    static boolean isTestDatabase(String url) {
        String rest = url.substring(url.indexOf("//") + 2);
        if (rest.substring(0, Math.max(0, rest.indexOf('/'))).contains(",")) {
            return false;                                // a primary and its replica: never a scratch database
        }
        int slash = rest.indexOf('/');
        int query = rest.indexOf('?');
        String name = slash < 0 ? "" : rest.substring(slash + 1, query < 0 ? rest.length() : query);
        return name.endsWith("_test");
    }

    private static void apply(MatchResultRepository repository, long[] players, int base, int results, int threads)
            throws InterruptedException {
        AtomicInteger next = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(threads);
        for (int k = 0; k < threads; k++) {
            Thread.ofPlatform().start(() -> {
                try {
                    int i;
                    while ((i = next.getAndIncrement()) < results) {
                        long now = System.currentTimeMillis();
                        repository.apply(new MatchResult(Ulid.generate(), 0, 0, "bench", now - 60_000, now,
                                List.of(new PlayerResult(players[base + i], 0, 0, 1, 1, 100, 10, 0, 10, false, 60))));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    done.countDown();
                }
            });
        }
        done.await();
    }

    /** User and kernel clock ticks, a hundred a second on Linux; zeros when there is no such process here. */
    private static long[] cpu(long pid) {
        try {
            String s = Files.readString(Path.of("/proc/" + pid + "/stat"));
            String[] f = s.substring(s.lastIndexOf(')') + 2).split(" ");
            return new long[] {Long.parseLong(f[11]), Long.parseLong(f[12])};
        } catch (Exception e) {
            return new long[] {0, 0};
        }
    }

    private ApplyBenchmark() {
    }
}
