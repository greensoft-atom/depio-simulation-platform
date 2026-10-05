package com.backend.worker;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.backend.handoff.LeaderboardStore;
import com.backend.handoff.MatchOutcome;
import com.backend.handoff.MatchResultCodec;
import com.backend.handoff.MatchResultStream;
import com.backend.handoff.MatchResultStream.Entry;
import com.backend.persistence.MatchResultRepository;
import com.backend.persistence.MatchResultRepository.ApplyResult;
import com.backend.persistence.MatchResultRepository.MatchResult;
import com.backend.persistence.MatchResultRepository.PlayerResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies finished matches to MySQL (docs detailed-design/05-worker-and-events.md §3).
 *
 * <h2>Commit, then acknowledge</h2>
 *
 * Never the other way round. A crash between the commit and the acknowledgement redelivers
 * the entry, and the apply is idempotent, so the redelivery changes nothing. A crash
 * between an acknowledgement and the commit would lose the match silently — and silent loss
 * of currency is the one failure nobody ever notices in time.
 *
 * <h2>Every entry ends in exactly one place</h2>
 *
 * <table>
 * <tr><td>applied, or already applied</td><td>acknowledged</td></tr>
 * <tr><td>unreadable, or a value no database could store</td><td>the dead list, kept as evidence</td></tr>
 * <tr><td>written by a newer producer</td><td>the deferred list, for a newer worker</td></tr>
 * <tr><td>the database is failing</td><td>left pending for this worker, re-driven</td></tr>
 * </table>
 *
 * The last row is the one that used to be broken. A failed transaction left the entry in
 * flight, and the only thing that ever looked there ran once, at start-up — so a one-minute
 * MySQL blip parked every result until somebody restarted the worker. What is pending for
 * this worker is now re-driven on a timer, and immediately after the database recovers.
 *
 * <h2>Transient versus permanent</h2>
 *
 * A failure is only worth retrying if retrying can change it. A lost connection, a lock
 * timeout or a deadlock that outlasted its retries can; a value out of range, a broken
 * foreign key or a violated {@code CHECK} cannot, and retrying one of those for ever blocks
 * nothing but fills the logs and hides the real problem. Unknown failures are treated as
 * transient: the conservative mistake is to retry something hopeless, not to discard
 * something recoverable.
 *
 * <h2>The loop does not die</h2>
 *
 * It used to call {@code handle} unguarded, and an acknowledgement that failed on a store
 * hiccup threw straight out of {@code run()} and ended the process — after the commit, so
 * nothing was lost, but nothing was applied again until a human noticed.
 */
public final class MatchResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(MatchResultConsumer.class);

    private static final double POLL_SECONDS = 2.0;

    /** Six commands against a local store. Longer than this and the store is not answering. */
    private static final long LEADERBOARD_TIMEOUT_SECONDS = 2;

    /** How often this worker re-drives what is pending for it while healthy. */
    public static final long DEFAULT_REDRIVE_MILLIS = 30_000;

    /**
     * How long to stop claiming after a transient failure.
     *
     * While the database is failing, claiming more work only makes more entries pending for
     * this worker, each at the cost of a connection timeout. Better to leave them in the
     * stream, wait, and then try the parked ones first.
     */
    private static final long UNHEALTHY_PAUSE_MILLIS = 5_000;

    // MySQL errors no retry will ever fix. Named because the SQLSTATE class is not enough:
    // a CHECK violation is 3819 with SQLSTATE HY000, the generic "something went wrong".
    private static final int ER_CHECK_CONSTRAINT_VIOLATED = 3819;
    private static final int ER_NO_REFERENCED_ROW = 1452;
    private static final int ER_WARN_DATA_OUT_OF_RANGE = 1264;
    private static final int ER_DATA_OUT_OF_RANGE = 1690;
    private static final int ER_DATA_TOO_LONG = 1406;
    private static final int ER_TRUNCATED_WRONG_VALUE = 1366;

    /** How a result reaches the database. The repository in production; a test can fail it. */
    @FunctionalInterface
    public interface Applier {
        ApplyResult apply(MatchResult result) throws SQLException;
    }

    /** A match's boosts at its end: a percent a kind, by player; a player with none absent (D-38). */
    public interface Boosts {
        java.util.Map<Long, int[]> percentsAt(java.util.Collection<Long> players, java.time.Instant at)
                throws SQLException;
    }

    /** Who is told what a match paid them (05 §6): the lobby's push in production. */
    @FunctionalInterface
    public interface Teller {
        void tell(long playerId, com.fasterxml.jackson.databind.node.ObjectNode rewards);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** Nobody is told: tests that are not about it. */
    private volatile Teller teller = (player, rewards) -> { };

    /** Tells each player what a result paid them, after its commit: {@code evt.rewards} (05 §6). */
    public MatchResultConsumer telling(Teller teller) {
        this.teller = teller;
        return this;
    }

    /** No player has a boost: tests that are not about them. */
    private static final Boosts NO_BOOSTS = (players, at) -> java.util.Map.of();
    private static final int[] NOT_BOOSTED = new int[2];

    /** What processing one entry did to it. */
    enum Fate {
        /** No longer pending: applied, a duplicate, dead-lettered or deferred. */
        DONE,
        /** Left for a re-drive because the database is failing. Others will fail too. */
        RETRY_DATABASE,
        /** Left for a re-drive because this entry hit something unexpected. Others may not. */
        RETRY_FAULT
    }

    private final MatchResultStream stream;
    private final Applier applier;
    private final Boosts boosts;
    private final LeaderboardStore leaderboard;
    private final long redriveMillis;

    private final AtomicLong applied = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong deadLettered = new AtomicLong();
    private final AtomicLong deferred = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong leaderboardFailures = new AtomicLong();
    private final AtomicLong unapplicablePlayers = new AtomicLong();
    private final AtomicLong trimmedUnapplied = new AtomicLong();

    private volatile boolean running = true;

    /** The last re-drive stopped on a database failure: the database is still down. */
    private boolean databaseFailing;

    public MatchResultConsumer(MatchResultStream stream, MatchResultRepository repository,
            LeaderboardStore leaderboard) {
        this(stream, repository, leaderboard, NO_BOOSTS);
    }

    public MatchResultConsumer(MatchResultStream stream, MatchResultRepository repository,
            LeaderboardStore leaderboard, Boosts boosts) {
        this(stream, repository::apply, boosts, leaderboard, DEFAULT_REDRIVE_MILLIS);
    }

    public MatchResultConsumer(MatchResultStream stream, Applier applier,
            LeaderboardStore leaderboard, long redriveMillis) {
        this(stream, applier, NO_BOOSTS, leaderboard, redriveMillis);
    }

    private MatchResultConsumer(MatchResultStream stream, Applier applier, Boosts boosts,
            LeaderboardStore leaderboard, long redriveMillis) {
        this.stream = stream;
        this.applier = applier;
        this.boosts = boosts;
        this.leaderboard = leaderboard;
        this.redriveMillis = redriveMillis;
    }

    public long appliedCount() {
        return applied.get();
    }

    public long duplicateCount() {
        return duplicates.get();
    }

    public long deadLetteredCount() {
        return deadLettered.get();
    }

    public long deferredCount() {
        return deferred.get();
    }

    public long failedCount() {
        return failed.get();
    }

    /** Results the stream trimmed away while pending, before anyone applied them: lost. */
    public long trimmedUnappliedCount() {
        return trimmedUnapplied.get();
    }

    /** Players named in a result who do not exist, so whose part of it cannot be paid. */
    public long unapplicablePlayerCount() {
        return unapplicablePlayers.get();
    }

    /** Matches whose rows were written but whose board update did not land. */
    public long leaderboardFailureCount() {
        return leaderboardFailures.get();
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Start-up: make the group if it is not there, return deferred entries through the inbox in
     * case this build is the newer one they were waiting for, then finish whatever this worker
     * had in flight when it last stopped.
     *
     * @return how many of this worker's own parked entries were settled.
     */
    public int recoverAbandoned() {
        int moved = stream.drainInbox(true);
        if (moved > 0) {
            log.info("moved {} entries from the inbox and the old lists into the stream", moved);
        }
        return redrive();
    }

    /** A result the stream trimmed away while it was pending: nothing is left to apply. */
    private void lost(String id) {
        trimmedUnapplied.incrementAndGet();
        log.error("result {} was trimmed from {} before anyone applied it: lost", id, MatchResultStream.KEY);
    }

    /**
     * Retries everything pending for this worker, after draining the inbox and taking over
     * what has been idle past the claim time on any consumer.
     *
     * Stops at the first database failure: if the database is down for one entry it is down
     * for the rest, and trying each would cost a connection timeout apiece. An entry that
     * failed for its own reasons does not stop the others behind it.
     */
    int redrive() {
        databaseFailing = false;
        stream.ensureGroup();                           // gone if the stream was lost with it
        int moved = stream.drainInbox(false);
        if (moved > 0) {
            log.info("moved {} entries from the inbox into the stream", moved);
        }
        // Idle a while on any consumer, a retired worker's among them: this worker's now (D-33).
        for (String id : stream.claimIdle()) {
            lost(id);
        }
        List<Entry> parked = stream.abandoned();
        if (parked.isEmpty()) {
            return 0;
        }
        log.info("re-driving {} parked entries", parked.size());
        int settled = 0;
        for (Entry entry : parked) {
            Fate fate = process(entry);
            if (fate == Fate.DONE) {
                settled++;
            } else if (fate == Fate.RETRY_DATABASE) {
                databaseFailing = true;
                break;
            }
        }
        return settled;
    }

    /** Blocks until stopped. */
    public void run() {
        log.info("consuming {} as {}/{}", MatchResultStream.KEY, MatchResultStream.GROUP, stream.consumer());
        // Straight to the probe when the start-up redrive found the database down: claiming
        // first parked a second entry before the first had gone through (05 §3).
        long nextRedrive = databaseFailing ? 0 : System.currentTimeMillis() + redriveMillis;
        while (running) {
            try {
                if (System.currentTimeMillis() >= nextRedrive) {
                    redrive();
                    nextRedrive = System.currentTimeMillis() + redriveMillis;
                    if (databaseFailing) {
                        // Still down. Claiming now only made one more entry a cycle pending for
                        // this worker: during a long outage, all of them. The parked entry is the
                        // probe; the queue waits until it goes through.
                        pause(Math.min(UNHEALTHY_PAUSE_MILLIS, redriveMillis));
                        nextRedrive = 0;
                        continue;
                    }
                }
                Entry entry = stream.claim(POLL_SECONDS);
                if (entry == null) {
                    continue;
                }
                if (process(entry) == Fate.RETRY_DATABASE) {
                    pause(Math.min(UNHEALTHY_PAUSE_MILLIS, redriveMillis));
                    nextRedrive = 0;            // the parked ones first, as soon as we resume
                }
            } catch (RuntimeException e) {
                // A store failure outside an apply: claiming, acknowledging, dead-lettering.
                // Whatever was in flight is still pending for this worker, which is exactly
                // where the next re-drive looks.
                log.warn("store operation failed, continuing: {}", e.toString());
                pause(1_000);
            }
        }
        log.info("stopped after applying {} matches", applied.get());
    }

    public void stop() {
        running = false;
    }

    /** @return true if the entry is no longer pending. */
    boolean handle(Entry entry) {
        return process(entry) == Fate.DONE;
    }

    Fate process(Entry entry) {
        MatchOutcome outcome;
        try {
            outcome = MatchResultCodec.decode(entry.payload());
        } catch (MatchResultCodec.FutureEntry e) {
            log.warn("deferring an entry for a newer worker: {}", e.getMessage());
            stream.defer(entry);
            deferred.incrementAndGet();
            return Fate.DONE;
        } catch (MatchResultCodec.UnreadableEntry e) {
            log.error("dead-lettering an unreadable entry: {}", e.getMessage());
            stream.deadLetter(entry);
            deadLettered.incrementAndGet();
            return Fate.DONE;
        }

        ApplyResult result;
        try {
            result = applier.apply(toResult(outcome));
        } catch (MatchResultRepository.InvalidResult e) {
            log.error("dead-lettering match {}: {}", outcome.matchUid(), e.getMessage());
            stream.deadLetter(entry);
            deadLettered.incrementAndGet();
            return Fate.DONE;
        } catch (SQLException e) {
            if (isPermanent(e)) {
                log.error("dead-lettering match {}: {} (no retry can fix this)",
                        outcome.matchUid(), e.toString());
                stream.deadLetter(entry);
                deadLettered.incrementAndGet();
                return Fate.DONE;
            }
            failed.incrementAndGet();
            log.error("could not apply match {}, will retry: {}", outcome.matchUid(), e.toString());
            return Fate.RETRY_DATABASE;
        } catch (RuntimeException e) {
            failed.incrementAndGet();
            log.error("unexpected failure applying match {}, will retry", outcome.matchUid(), e);
            return Fate.RETRY_FAULT;
        }

        // Counted at the commit, not after the acknowledgement: an acknowledgement that failed
        // left a committed match uncounted, and its redelivery then counted it a duplicate.
        if (!result.missingPlayers().isEmpty()) {
            unapplicablePlayers.addAndGet(result.missingPlayers().size());
            // Warn, not debug: this is a result that can never be paid, and the old code hid
            // exactly this case by calling it a duplicate.
            log.warn("match {}: players {} do not exist, so their part cannot be applied",
                    outcome.matchUid(), result.missingPlayers());
        }
        if (result.anythingNew()) {
            applied.incrementAndGet();
            log.info("applied match {} for {} players", outcome.matchUid(), result.newPlayers());
            // Before the acknowledgement: a redelivery pays nobody new, so tells nobody again.
            com.backend.handoff.MatchMode mode = com.backend.handoff.MatchMode.ofId(outcome.mode());
            for (MatchResultRepository.Paid p : result.paid()) {
                var told = JSON.createObjectNode().put("matchUid", outcome.matchUid())
                        .put("mode", mode == null ? null : mode.key).put("placement", p.placement())
                        .put("coins", p.coins()).put("xp", p.xp()).put("ratingDelta", p.ratingDelta())
                        .put("gems", p.gems());
                // Each achievement reached, for the client to show; its gems are in "gems" (D-64).
                p.achievements().forEach(told.putArray("achievements")::add);
                // Each daily goal met, and their coins, apart from the match's own (D-66).
                p.goals().forEach(told.putArray("goals")::add);
                told.put("goalCoins", p.goalCoins());
                // The season pass's points, the tier after, and what the tiers reached paid, its own (D-69).
                var pass = told.putObject("pass").put("points", p.pass().points()).put("tier", p.pass().tier())
                        .put("coins", p.pass().coins()).put("gems", p.pass().gems());
                p.pass().items().forEach(pass.putArray("items")::add);
                teller.tell(p.playerId(), told);
            }
        } else if (result.missingPlayers().isEmpty()) {
            duplicates.incrementAndGet();
            log.debug("match {} was already applied", outcome.matchUid());
        }

        updateLeaderboards(outcome, result);
        stream.ack(entry);                          // only after the transaction committed
        return Fate.DONE;
    }

    /** True for a failure that retrying cannot change. */
    static boolean isPermanent(SQLException e) {
        switch (e.getErrorCode()) {
            case ER_CHECK_CONSTRAINT_VIOLATED, ER_NO_REFERENCED_ROW, ER_WARN_DATA_OUT_OF_RANGE,
                    ER_DATA_OUT_OF_RANGE, ER_DATA_TOO_LONG, ER_TRUNCATED_WRONG_VALUE -> {
                return true;
            }
            default -> {
                // 22: data exception. 23: integrity constraint. Neither is a matter of timing.
                String state = e.getSQLState();
                return state != null && (state.startsWith("22") || state.startsWith("23"));
            }
        }
    }

    /**
     * Projects the match onto the boards, after the commit and before the acknowledgement.
     *
     * <h2>Run on every delivery, including redeliveries</h2>
     *
     * Not gated on the result being new. Gating it would mean that a worker dying between
     * the MySQL commit and this write loses the board update <em>permanently</em>: the
     * redelivery finds the rows already there, decides it has nothing to do, and the score
     * is never ranked. Because the write is a maximum it costs nothing to repeat, so the
     * repeat is the recovery.
     *
     * <h2>A failure here does not hold up the queue</h2>
     *
     * The entry is acknowledged either way. This pipeline pays players; the board is a
     * projection of what it already wrote, and the next match the player finishes will carry
     * their best score onto the board again. Holding a payment because an index is unwell
     * would be the wrong way round.
     */
    private void updateLeaderboards(MatchOutcome outcome, ApplyResult applied) {
        for (MatchOutcome.PlayerOutcome p : outcome.players()) {
            if (applied.missingPlayers().contains(p.playerId())) {
                continue;                   // nothing was committed for them to project
            }
            try {
                // The name the database holds now, not the one the match began with (D-60).
                leaderboard.record(p.playerId(), applied.names().get(p.playerId()), p.score(),
                                outcome.endedAtMillis())
                        .get(LEADERBOARD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
                return;
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                leaderboardFailures.incrementAndGet();
                log.warn("match {} applied but not ranked for player {}: {}",
                        outcome.matchUid(), p.playerId(), e.toString());
            }
        }
    }

    /** The result to apply; a player's boosts read as they ran when the match ended (D-38). */
    private MatchResult toResult(MatchOutcome outcome) throws SQLException {
        java.util.Map<Long, int[]> boosted = boosts.percentsAt(
                outcome.players().stream().map(MatchOutcome.PlayerOutcome::playerId).toList(),
                java.time.Instant.ofEpochMilli(outcome.endedAtMillis()));
        List<PlayerResult> players = new ArrayList<>(outcome.players().size());
        for (MatchOutcome.PlayerOutcome p : outcome.players()) {
            RewardRules.Reward reward = RewardRules.forPlayer(outcome, p,
                    boosted.getOrDefault(p.playerId(), NOT_BOOSTED));
            players.add(new PlayerResult(p.playerId(), p.team(), p.placement(), p.kills(),
                    p.deaths(), p.score(), reward.xp(), reward.ratingDelta(), reward.coins(),
                    outcome.won(p), p.playtimeSeconds(), p.assists()));
        }
        // A rated mode's timed result moves ratings, worked out under the players' locks (04 §4).
        com.backend.handoff.MatchMode mode = com.backend.handoff.MatchMode.ofId(outcome.mode());
        // Not one a stop cut short (D-29): its placements where it stood are not a finish.
        boolean rated = mode != null && mode.rated && outcome.kind() == MatchOutcome.KIND_TIMED && !outcome.cutShort();
        return new MatchResult(outcome.matchUid(), outcome.kind(), outcome.mode(),
                outcome.arena(), outcome.startedAtMillis(), outcome.endedAtMillis(), players, rated,
                outcome.cutShort());
    }

    private void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
