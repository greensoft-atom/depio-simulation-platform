package com.backend.worker;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.backend.persistence.MatchResultRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deletes match history past its retention, once a day (D-14; the rule and why it is safe
 * are on {@link MatchResultRepository#RESULT_ACCEPT_WINDOW_MILLIS}).
 *
 * Every worker runs it. Three workers purging the same rows is harmless — the deletes are
 * idempotent and take their locks in the same order — and cheaper to reason about than a
 * lock that decides which one may.
 */
final class Retention implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Retention.class);

    static final int BATCH = 1_000;

    private final MatchResultRepository matches;
    private final com.backend.persistence.InboxRepository inbox;
    private final com.backend.persistence.StatsRepository stats;
    private final com.backend.persistence.FriendRepository friends;
    private final com.backend.persistence.TeamRepository teams;
    private final com.backend.persistence.BoostRepository boosts;
    private final com.backend.persistence.TournamentRepository tournaments;
    private final com.backend.persistence.DailyGoalRepository goals;
    private final com.backend.persistence.PaymentRepository payments;
    private final com.backend.persistence.SeasonPassRepository passes;
    private final com.backend.persistence.BackupRunRepository backups;

    private volatile double lastRunSeconds = Double.NaN;

    /** Matches deleted by every run so far; read by the metrics scrape. */
    private final java.util.concurrent.atomic.AtomicLong deleted = new java.util.concurrent.atomic.AtomicLong();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "retention");
        t.setDaemon(true);
        return t;
    });

    Retention(MatchResultRepository matches, com.backend.persistence.InboxRepository inbox,
              com.backend.persistence.StatsRepository stats, com.backend.persistence.FriendRepository friends,
              com.backend.persistence.TeamRepository teams, com.backend.persistence.BoostRepository boosts,
              com.backend.persistence.TournamentRepository tournaments,
              com.backend.persistence.DailyGoalRepository goals, com.backend.persistence.PaymentRepository payments,
              com.backend.persistence.SeasonPassRepository passes, com.backend.persistence.BackupRunRepository backups) {
        this.matches = matches;
        this.inbox = inbox;
        this.stats = stats;
        this.friends = friends;
        this.teams = teams;
        this.boosts = boosts;
        this.tournaments = tournaments;
        this.goals = goals;
        this.payments = payments;
        this.passes = passes;
        this.backups = backups;
    }

    /** A minute after start, so start-up recovery goes first; then daily. */
    void start() {
        scheduler.scheduleWithFixedDelay(this::runOnce, 1, 24 * 60, TimeUnit.MINUTES);
    }

    /** A step of the day's purge: what it deletes, and how many it did. */
    private interface Step {
        int run() throws Exception;
    }

    /**
     * Runs one step, its failure its own: one table's trouble no longer skipped every step after it for a day (D-49).
     *
     * @return how many it deleted, or -1 if it failed; the next run tries it again
     */
    private static int step(String what, Step step) {
        try {
            int n = step.run();
            log.info("retention: {}: {}", what, n);
            return n;
        } catch (Exception e) {
            log.warn("retention: {} failed, the next run tries again: {}", what, e.toString());
            return -1;
        }
    }

    /** @return matches deleted, or -1 if that step failed; each step runs whatever another did. */
    int runOnce() {
        long started = System.nanoTime();
        java.time.Instant now = java.time.Instant.now();
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        try {
            int count = step("matches ended more than " + TimeUnit.MILLISECONDS.toDays(MatchResultRepository.MATCH_RETENTION_MILLIS)
                    + " days ago", () -> matches.purgeMatchesEndedBefore(
                    System.currentTimeMillis() - MatchResultRepository.MATCH_RETENTION_MILLIS, BATCH));
            if (count > 0) {
                deleted.addAndGet(count);
            }
            // The inbox's items too, read or not, past their 30 days (04 §9, Q-20).
            step("inbox items older than " + com.backend.persistence.InboxRepository.KEPT.toDays() + " days",
                    () -> inbox.purgeOlderThan(now.minus(com.backend.persistence.InboxRepository.KEPT), BATCH));
            // A player's days of activity past theirs (05 §11, Q-24).
            step("days of players' activity older than " + com.backend.persistence.StatsRepository.KEPT_DAYS + " days",
                    () -> stats.purgeActivityBefore(today.minusDays(com.backend.persistence.StatsRepository.KEPT_DAYS), BATCH));
            // Friend requests and team invitations past their seven days, which nothing else removes (D-40).
            step("lapsed friend requests", () -> friends.purgeLapsed(now, BATCH));
            step("lapsed team invitations", () -> teams.purgeLapsedInvites(now, BATCH));
            step("lapsed team applications", () -> teams.purgeLapsedApplications(now, BATCH));        // Q-49
            // Boosts ended and keys used past theirs, and tournaments past their 90 days (D-38, Q-47).
            step("ended boosts and used keys", () -> boosts.purge(now, BATCH));
            step("tournaments", () -> tournaments.purge(now, BATCH));
            step("daily goals' progress older than " + com.backend.persistence.DailyGoalRepository.KEPT_DAYS + " days (D-66)",
                    () -> goals.purgeBefore(today.minusDays(com.backend.persistence.DailyGoalRepository.KEPT_DAYS), BATCH));
            // Orders their provider never answered: expired, so a late word grants nothing (D-68).
            step("orders expired after pending a day (D-68)",
                    () -> payments.expirePending(now.minus(com.backend.persistence.PaymentRepository.PENDING_FOR), BATCH));
            step("season passes older than the " + com.backend.persistence.SeasonPassRepository.KEPT_SEASONS
                    + " seasons kept (D-69)", () -> passes.purge(BATCH));
            step("backup runs older than " + com.backend.persistence.BackupRunRepository.KEPT.toDays()
                    + " days, each kind's newest success kept (D-71, D-48)",
                    () -> backups.purgeBefore(now.minus(com.backend.persistence.BackupRunRepository.KEPT), BATCH));
            return count;
        } finally {
            // Timed whether or not a step failed: a slow run that failed is still slow (D-72's trigger).
            lastRunSeconds = (System.nanoTime() - started) / 1e9;
            log.info("retention: the run took {} ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }

    /** How long the last run took, NaN before one: a day's purge past 30 minutes is a trigger (D-72). */
    double lastRunSeconds() {
        return lastRunSeconds;
    }

    long deletedTotal() {
        return deleted.get();
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
