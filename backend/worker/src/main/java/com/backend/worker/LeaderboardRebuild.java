package com.backend.worker;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.backend.common.Arguments;
import com.backend.common.Logs;
import com.backend.common.RefusedConfiguration;
import com.backend.handoff.LeaderboardStore;
import com.backend.handoff.LeaderboardStore.Board;
import com.backend.handoff.StoreClients;
import com.backend.persistence.Database;
import com.backend.persistence.DatabaseSettings;
import com.backend.persistence.LeaderboardSource;
import com.jredis.client.JRedisClient;

/**
 * Puts the leaderboards back from MySQL (05 §8): after the store's disk is lost, or to rank
 * results that were applied while the store could not be written to.
 *
 * Safe to run at any time, against any store. Every write is {@code ZADD GT}, so a board that
 * was never lost only gains what it was missing, and nothing on it goes down; a live board's
 * expiry is left as it is. The all-time board comes from {@code player_stat.best_score}, the
 * day and week boards from the matches that ended in them, and only those boards are written
 * that would still exist had the store never been lost.
 *
 * Usage: LeaderboardRebuild [storeHost] [storePort], the database from the environment as for
 * WorkerMain; or {@code systemctl start backend-leaderboard-rebuild}, which uses the worker's
 * settings and credentials.
 */
public final class LeaderboardRebuild {

    private static final int PAGE = 1_000;
    private static final long WRITE_TIMEOUT_SECONDS = 60;

    /** What was written: how many players on the all-time board, and each day or week board. */
    record Report(int allTime, List<String> windows) { }

    private final LeaderboardSource source;
    private final LeaderboardStore boards;

    LeaderboardRebuild(LeaderboardSource source, LeaderboardStore boards) {
        this.source = source;
        this.boards = boards;
    }

    Report run(Instant now) throws Exception {
        int allTime = 0;
        long after = 0;
        while (true) {
            List<LeaderboardSource.Best> page = source.allTime(after, PAGE);
            if (page.isEmpty()) {
                break;
            }
            boards.raise(Board.ALLTIME, now, scores(page), null).get(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            allTime += page.size();
            after = page.get(page.size() - 1).playerId();
        }

        List<String> windows = new ArrayList<>();
        // Back from the current period while one could still be alive: a board lasts its TTL
        // after its last score, and the last score is before the period ends.
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        for (LocalDate day = today; stillAlive(day.plusDays(1), Board.DAILY, now); day = day.minusDays(1)) {
            window(Board.DAILY, day, day.plusDays(1), now, windows);
        }
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        for (LocalDate week = monday; stillAlive(week.plusWeeks(1), Board.WEEKLY, now); week = week.minusWeeks(1)) {
            window(Board.WEEKLY, week, week.plusWeeks(1), now, windows);
        }
        return new Report(allTime, windows);
    }

    /** Whether a board whose period ended at {@code end} could still be alive at {@code now}. */
    private static boolean stillAlive(LocalDate end, Board board, Instant now) {
        return start(end).plus(Duration.ofSeconds(board.ttlSeconds())).isAfter(now);
    }

    private void window(Board board, LocalDate from, LocalDate to, Instant now, List<String> written)
            throws Exception {
        LeaderboardSource.Window w = source.window(start(from), start(to));
        if (w.lastScoreAt() == null) {
            return;
        }
        Instant expires = w.lastScoreAt().plusSeconds(board.ttlSeconds());
        if (!expires.isAfter(now)) {
            return;                                 // the live board would be gone by now
        }
        boards.raise(board, start(from), scores(w.bests()), expires)
                .get(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        written.add(board.key(start(from)) + " (" + w.bests().size() + ")");
    }

    private static Instant start(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static List<LeaderboardStore.Score> scores(List<LeaderboardSource.Best> bests) {
        return bests.stream()
                .map(b -> new LeaderboardStore.Score(b.playerId(), b.displayName(), b.score()))
                .toList();
    }

    public static void main(String[] args) {
        try {
            String storeHost = args.length > 0 ? args[0] : "127.0.0.1";
            int storePort = Arguments.integer(args, 1, "store port", 6379);
            DatabaseSettings settings = DatabaseSettings.fromEnvironment();
            JRedisClient store = StoreClients.open(storeHost, storePort, "leaderboard-rebuild");
            try (Database db = new Database(settings.url(), settings.user(), settings.password(), settings.poolSizeOr(2))) {
                Report report = new LeaderboardRebuild(new LeaderboardSource(db.dataSource()),
                        new LeaderboardStore(store)).run(Instant.now());
                System.out.println("all-time board: " + report.allTime() + " players");
                report.windows().forEach(w -> System.out.println("rebuilt " + w));
            } finally {
                store.close();
            }
            Logs.flush();
            System.exit(0);
        } catch (RefusedConfiguration refused) {
            Logs.flush();
            System.err.println("refusing to start: " + refused.getMessage());
            System.exit(2);
        } catch (Throwable failed) {
            // Exit rather than end main by throwing: the store client's threads are not daemons.
            Logs.flush();
            failed.printStackTrace();
            System.exit(1);
        }
    }
}
