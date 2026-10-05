package com.backend.worker;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.backend.handoff.LobbyPush;
import com.backend.persistence.EconomyRepository;
import com.backend.persistence.InboxRepository;
import com.backend.persistence.SeasonRepository;
import com.jredis.client.JRedisClient;
import com.jredis.client.SetArgs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Closes a season that has ended (docs 04 §7, 05 §9, D-63): its places, its gems, its reset.
 *
 * <h2>Right whoever runs it</h2>
 *
 * Every worker looks each minute; whoever takes the store's lock does the work, and hands the lock
 * back when done. The lock only keeps two workers from doing the same work at once: what makes it
 * right is that each step says in the season's row that it is done, the places and each reset
 * batch in the transaction that does them, and that a place's gems are paid by the ledger's key,
 * so a second run, or a run after a crash, pays nobody twice and halves nobody twice.
 */
final class SeasonKeeper implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SeasonKeeper.class);

    static final String LOCK_KEY = "job:season";
    static final long LOCK_SECONDS = TimeUnit.MINUTES.toSeconds(10);

    /** Another worker has the job. */
    static final int NOT_RUN = -1;
    /** The work could not be done; the next minute goes on from where it stopped. */
    static final int FAILED = -2;

    private final SeasonRepository seasons;
    private final EconomyRepository economy;
    private final InboxRepository inbox;
    private final LobbyPush push;
    private final JRedisClient store;
    private final String workerId;
    private final int page;
    private final int batch;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "season-keeper");
        t.setDaemon(true);
        return t;
    });

    SeasonKeeper(SeasonRepository seasons, EconomyRepository economy, InboxRepository inbox, LobbyPush push,
                 JRedisClient store, String workerId) {
        this(seasons, economy, inbox, push, store, workerId, 1_000, 1_000);
    }

    /** @param page places paid a read; @param batch players reset a transaction */
    SeasonKeeper(SeasonRepository seasons, EconomyRepository economy, InboxRepository inbox, LobbyPush push,
                 JRedisClient store, String workerId, int page, int batch) {
        this.seasons = seasons;
        this.economy = economy;
        this.inbox = inbox;
        this.push = push;
        this.store = store;
        this.workerId = workerId;
        this.page = page;
        this.batch = batch;
    }

    /** A minute after start, then every minute. */
    void start() {
        scheduler.scheduleWithFixedDelay(() -> runIfDue(Instant.now()), 1, 1, TimeUnit.MINUTES);
    }

    /** @return the seasons finished in this run, 0 for nothing to do, or {@link #NOT_RUN}, {@link #FAILED} */
    int runIfDue(Instant now) {
        try {
            if (!store.sync().set(LOCK_KEY, workerId, SetArgs.nx().andEx(LOCK_SECONDS))) {
                return NOT_RUN;
            }
        } catch (RuntimeException storeDown) {
            log.warn("season job: cannot reach the store, will try again: {}", storeDown.toString());
            return FAILED;
        }
        try {
            SeasonRepository.Season open = seasons.current();
            if (open != null && !now.isBefore(open.endsAt())
                    && seasons.place(open.id(), SeasonRepository.endAfter(open.endsAt()), now)) {
                log.info("season {} ended: its places written, season {} begun", open.id(), open.id() + 1);
            }
            List<SeasonRepository.Season> unfinished = seasons.unfinished();
            for (SeasonRepository.Season s : unfinished) {
                // Each step is stamped when it is done, for the operator's list: at scale, minutes after the run began.
                if (s.paidAt() == null) {
                    int paid = pay(s.id(), now) + payTeams(s.id(), now);
                    seasons.markPaid(s.id(), Instant.now());
                    log.info("season {}: {} places paid", s.id(), paid);
                }
                if (s.resetAt() == null) {
                    while (seasons.resetBatch(s.id(), batch, Instant.now())) {
                        stillHeld();                // a batch a transaction, its progress with it
                    }
                    log.info("season {}: every rating reset", s.id());
                }
                if (s.teamResetAt() == null && seasons.resetTeams(s.id(), Instant.now())) {
                    log.info("season {}: every team's rating reset", s.id());
                }
            }
            return unfinished.size();
        } catch (LockLost lost) {
            log.info("season job: {}; it goes on from the steps recorded", lost.getMessage());
            return NOT_RUN;
        } catch (Exception e) {
            log.warn("season job failed, the next minute goes on from here: {}", e.toString());
            return FAILED;
        } finally {
            try {
                if (workerId.equals(store.sync().get(LOCK_KEY))) {
                    store.sync().del(LOCK_KEY);
                }
            } catch (RuntimeException storeDown) {
                // It expires on its own.
            }
        }
    }

    /**
     * Extends the lock while this worker holds it, after each page of places and each reset batch: a close at full
     * size takes longer than {@link #LOCK_SECONDS}, and a lock that lapsed mid-run let another worker start the same
     * close over, each paying the same pages (D-47). False when another worker holds it now: this run stops, and the
     * steps' own records say where the next goes on.
     */
    boolean holdLock() {
        if (!workerId.equals(store.sync().get(LOCK_KEY))) {
            return false;
        }
        store.sync().send("EXPIRE", LOCK_KEY, Long.toString(LOCK_SECONDS));
        return true;
    }

    /** This run's lock has passed to another worker: it stops where it is. */
    private static final class LockLost extends Exception {
        LockLost() {
            super("the season job's lock passed to another worker", null, false, false);
        }
    }

    private void stillHeld() throws LockLost {
        if (!holdLock()) {
            throw new LockLost();
        }
    }

    /** Every place's gems, by the ledger's key, its inbox item and a push to look: a repeat does nothing. */
    private int pay(int season, Instant now) throws Exception {
        int paid = 0;
        int board = 0;
        long place = 0;
        List<SeasonRepository.Place> places;
        while (!(places = seasons.places(season, board, place, page)).isEmpty()) {
            for (SeasonRepository.Place p : places) {
                String ref = "season:" + season + ":" + p.board();
                economy.credit(p.playerId(), p.gems(), EconomyRepository.REASON_SEASON, ref,
                        ref + ":" + p.playerId(), EconomyRepository.CURRENCY_GEMS);
                if (inbox.add(p.playerId(), InboxRepository.SEASON_REWARD, (long) season * 10 + p.board(), now)) {
                    push.send(p.playerId(), "evt.inbox", null);
                }
                paid++;
            }
            SeasonRepository.Place last = places.get(places.size() - 1);
            board = last.board();
            place = last.place();
            stillHeld();
        }
        return paid;
    }

    /** Each team place's payees (D-65), as {@link #pay}: board 5, the team matches' mode. */
    private int payTeams(int season, Instant now) throws Exception {
        int paid = 0;
        long after = 0;
        List<SeasonRepository.TeamPayee> payees;
        while (!(payees = seasons.teamPayees(season, after, page)).isEmpty()) {
            for (SeasonRepository.TeamPayee p : payees) {
                String ref = "season:" + season + ":" + TEAMS_BOARD;
                economy.credit(p.playerId(), p.gems(), EconomyRepository.REASON_SEASON, ref,
                        ref + ":" + p.playerId(), EconomyRepository.CURRENCY_GEMS);
                if (inbox.add(p.playerId(), InboxRepository.SEASON_REWARD, (long) season * 10 + TEAMS_BOARD, now)) {
                    push.send(p.playerId(), "evt.inbox", null);
                }
                paid++;
            }
            after = payees.get(payees.size() - 1).playerId();
            stillHeld();
        }
        return paid;
    }

    /** The board of teams' number in a season's keys and inbox items: the team matches' mode. */
    private static final int TEAMS_BOARD = 5;

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
