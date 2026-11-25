# worker

The `worker` process does everything that happens after a match
([05](../../docs/detailed-design/05-worker-and-events.md)). It reads the results that the arenas publish to the
stream `s:match-result`. Through `persistence`, it applies each result to MySQL exactly once per player. Then it
tells each player what the result paid (the lobby push `evt.rewards`) and puts their scores on the store's
leaderboards. Every worker also runs the scheduled jobs: retention, the daily ledger check, the tournaments'
clock, the season's close, and the MySQL replica's heartbeat. The balance rules that pay a result (rewards,
account levels and Elo ratings) live in this module and nowhere else, so changing them is one release of one
process.

This page covers the classes, the two entry points, the scheduled jobs, what the result pipeline accepts and
promises, the settings, the metrics, the tests and how to run the worker. "The store" is j-redis: the
*session store* holds sessions, tickets and leaderboards, and the *events store* holds the result stream (D-7).
Terms such as *re-drive*, *dead letter*, *deferred* and *result inbox* are defined in the
[glossary](../../docs/glossary.md#5-results-and-reward).

- [Classes](#classes)
- [Entry points](#entry-points)
- [Scheduled jobs](#scheduled-jobs)
- [The result pipeline](#the-result-pipeline)
- [Configuration](#configuration)
- [Metrics](#metrics)
- [Build and test](#build-and-test)
- [Operational notes](#operational-notes)

## Classes

All classes are in the package `com.backend.worker`.

| Class | What it does |
|---|---|
| `WorkerMain` | The process: it reads the settings, opens the store and database clients, runs the migrations, starts every job, serves the metrics, installs the shutdown hook, then runs the result loop |
| `MatchResultConsumer` | The result loop: claim an entry, decode it, apply it, tell the players, rank the scores, acknowledge it. It also sets entries aside (dead list, deferred list), re-drives them, and takes over entries left by other workers |
| `RewardRules` | What a player's part of a match is worth in xp and coins, boosts included |
| `AccountLevels` | The account level that a lifetime xp total is worth: `3 000 × (L − 1)^p`, with p ≈ 1.654, so level 100 is at 6 000 000 xp |
| `EloRating` | How a rated match moves ratings: each player against each other player by placement, with K = 32, then K = 16 after 30 rated matches |
| `Retention` | The daily purge of everything that is kept for a limited time |
| `IdleGuests` | Retention's last step: the guests nobody has played for 180 days, erased (D-87) |
| `LedgerCheck` | The fleet's daily check that every balance equals the sum of its ledger rows |
| `ReplicaWatch` | The MySQL heartbeat, each MySQL replica's lag, and the stores' replicas, for the metrics |
| `TournamentScheduler` | The tournaments' clock: seeding, starting, matches made and decided, rounds, prizes |
| `SeasonKeeper` | Closes a season that has ended: its places, its gems, its reset |
| `BackupWatch` | Turns the backup steps' latest rows into the backup metrics |
| `LeaderboardRebuild` | A separate entry point: rebuilds the store's leaderboards from MySQL |

## Entry points

**`WorkerMain [storeHost] [storePort] [workerId]`** runs the worker.

- `storeHost` and `storePort` default to `127.0.0.1` and `6379`. When `BACKEND_STORE_ADDRESSES` is set, it is
  used instead.
- `workerId` defaults to `worker-1`.
- The database settings come from the environment only. A command line in the old shape is refused with
  exit 2: more than three arguments, or a JDBC URL in third place. The worker refuses it rather than reads it,
  so that an old line's password cannot become a worker's id.

The id names this worker's consumer in the stream's consumer group. Keep it:

- **stable across restarts**, so that a restarted worker finds its own unfinished entries at once, instead of
  after the minute in which any worker may take them over;
- **unique among workers**, or two workers re-drive each other's entries.

The unit `backend-worker@.service` passes `${STORE_HOST} ${STORE_PORT} worker-%i`. It reads
`/etc/backend/worker.env` and then `/etc/backend/worker-%i.env`, whose settings win where both set one
([deploy/env](../deploy/env/worker.env.example)).

**Start-up**, in order:

1. The settings are read: the database's (`DatabaseSettings`), with a warning if the development password is
   in use, and `BACKEND_RESULT_RETENTION_HOURS`, which the worker logs. A value out of range is exit 2.
2. The session store is opened, then the events store: `BACKEND_EVENTS_STORE`, or the session store again
   when that is unset (D-7). A store that does not answer yet does not stop the start: its client connects in
   the background (D-94). A store that asks for a password the worker does not have is exit 2.
3. The connection pool opens: 8 connections unless `BACKEND_DB_POOL_SIZE` says otherwise. Its first
   connection is tried again for up to a minute
   ([D-94](../../docs/architecture/03-decision-log.md#d-94--a-start-waits-for-its-database-in-the-same-process-for-up-to-a-minute)).
   Then the migrations run (Flyway; a start that races another waits on Flyway's lock).
4. The jobs start: retention, the ledger check, the replica watch, the tournament tick, the season job.
5. The metrics are served, if `BACKEND_METRICS_ADDR` is set.
6. The shutdown hook is installed.
7. `recoverAbandoned()` runs. It returns the deferred list through the result inbox, and with it what the old
   release's shared processing list (`q:match-result:processing`) still holds. It drains the result inbox into
   the stream and re-drives (below), which finishes this worker's pending entries. Then the result loop runs,
   and it blocks until the process stops.

Exit 2 is a refused configuration, which a restart cannot fix, so the unit does not restart on it
(`RestartPreventExitStatus=2`). Exit 1 is any other failure, and the unit restarts the worker 2 s later.

**`LeaderboardRebuild [storeHost] [storePort]`** puts the store's leaderboards back from MySQL
([05 §8](../../docs/detailed-design/05-worker-and-events.md#8-rebuilding-after-a-store-loss)). Run it after the
store's disk is lost, or after an outage in which results were applied but not ranked
(`backend_worker_unranked_total` rose).

- The all-time board comes from `player_stat.best_score`, read 1 000 players a page.
- Each day board and week board that would still exist comes from the matches that ended in it. A day board
  lives 3 days after its last score, and a week board 10 days.
- Every write is `ZADD … GT`, which only raises a score, and a live board keeps its expiry. So the rebuild is
  safe on a healthy store.

Run it once, on one worker machine, as `systemctl start backend-leaderboard-rebuild`. The unit reads
`/etc/backend/worker.env`, so the rebuild has the worker's settings and secrets. Its pool is 2 connections
unless `BACKEND_DB_POOL_SIZE` says otherwise. It prints what it wrote, and exits 0, 1 or 2 with the same meanings
as the worker.

## Scheduled jobs

Every worker runs all of these jobs. None needs a worker of its own.

| Job | Class | When | Across the fleet |
|---|---|---|---|
| Result loop | `MatchResultConsumer` | all the time: a claim waits up to 2 s; a re-drive every 30 s | the group `rewards`, one consumer a worker |
| Retention | `Retention` | 1 minute after start, then 24 hours after each run | no lock: the deletes are idempotent and take their locks in one order |
| Ledger check | `LedgerCheck` | an attempt 5 minutes after start, then every hour | once a day: `SET job:ledger-check <workerId> NX EX 82800` (23 hours) |
| Tournament tick | `TournamentScheduler` | every 5 s | no lock: every write is conditional on what the tick read |
| Season job | `SeasonKeeper` | every minute | `SET job:season <workerId> NX EX 600`, extended while a close runs, deleted when done |
| Replica heartbeat | `ReplicaWatch` | every second | none |
| Replica lag | `ReplicaWatch` | every 5 s, on a thread of its own | none |

The locks are keys in the session store. When a job fails, the worker logs it, and counts it where there is a
counter. The next run carries on. No job failure ends the process.

### The result loop

**Each pass of the loop first re-drives, when 30 s have passed since the last re-drive.** A re-drive:

1. makes the stream's consumer group again if the stream was lost with it;
2. drains the result inbox into the stream;
3. takes over entries that have been idle for a minute on any consumer, a retired worker's among them
   (`XAUTOCLAIM`, 64 at a time);
4. applies this worker's own pending entries again, oldest first. It stops at the first one that fails because
   of the database.

Then the loop claims one new entry (`XREADGROUP … COUNT 1 BLOCK 2000`) and processes it.

**While the database is failing, the worker claims nothing new.** It pauses 5 s and re-drives its parked entry
as the test of whether the database is back. A long outage therefore parks one entry, not the whole queue.
When a store command fails outside an apply, the loop pauses 1 s. The loop never ends on a failure.

### Retention

**Once a day, retention deletes what has passed its time**, in this order, 1 000 rows a transaction
([06 §9](../../docs/detailed-design/06-persistence-mysql.md#9-growth-and-retention)):

1. Matches that ended more than 90 days ago, oldest first, with their `match_player` and `match_team` rows.
   A cutoff inside the 30 days in which a result may still arrive is refused.
2. Inbox items older than 30 days, read or not.
3. `player_day` rows older than 90 days.
4. Friend requests, team invitations and team applications that have lapsed.
5. Boosts that ended more than 31 days ago, and activation keys used more than 30 days ago.
6. Tournaments, finished or cancelled, whose start was more than 90 days ago, with their entries, rosters and
   bracket: one tournament a transaction.
7. Daily goals' progress more than 7 days old.
8. Payment orders pending for longer than a day: marked expired. The rows stay.
9. Season passes older than the season being played and the five before it.
10. `backup_run` rows older than 90 days, except each kind's newest success.
11. Guests that nobody has played for 180 days
    ([D-87](../../docs/architecture/03-decision-log.md#d-87--a-guest-nobody-plays-for-180-days-is-erased-by-retention-a-deletion-is-at-once)):
    made more than 180 days ago, with no login since then and no day played since then (in `player_day`, which
    keeps 90 days). They are erased the way a player's own deletion erases an account (D-84), one guest at a
    time:
    - first off the store's boards. The store goes first, so a store that fails stops the step with nothing
      erased, and the next day's run finds the guest still there;
    - then out of their team. A leader's team goes to its longest-serving vice leader, else its longest-serving
      member; a leader alone ends the team;
    - then `AccountRepository.erase`, one transaction a guest.

    The step reads 1 000 guests at a time until none is left.

Each step stands on its own. A step that fails is logged, and the next step still runs. The run's time is
recorded either way (`backend_worker_retention_seconds`). Player totals, balances and the ledger are never
touched.

### Ledger check

**Once a day, one worker checks that every balance matches its ledger.** The worker that takes the day's lock
compares every player's coins and gems with the sums of their ledger rows. It reads 10 000 player ids at a
time, each range in its own statement, and so its own consistent snapshot.

- It writes `<mismatches> <epochSeconds>` to the store key `ledger:check:last`. Every worker's metrics read it
  back, so they all report the same check.
- It logs the first 20 mismatches, by id.
- A check that fails deletes the lock, if this worker still holds it, so that the next hourly attempt tries
  again. The lock needs no fencing token, because the check only reads.

### Tournament tick

**Every 5 s, the tick moves each tournament that is not over one step on**, in order of id:

- At the registration deadline, the tournament is seeded from the entries it locks, or cancelled with fewer
  than two entries.
- At its start time, it starts running. Then it runs a round at a time.
- A pending match is made the way the matchmaker makes one. A room is promised on an arena, every seat's ticket
  is written, and only then is the match claimed in MySQL. A worker that lost the claim to another worker gives
  its room and tickets back. Each seat gets a grant and the push `evt.tournament.match`.
- A ready match is decided from what MySQL recorded for it: its winner. On a draw, a result cut short, or no
  result 270 s after its tickets (1 800 s when somebody came), the higher seed goes on; in a round robin, it is
  a draw.
- After the last round, the prizes are paid through the ledger. From four entries, places 1 to 3 are paid gems
  too: 30, 15 and 5. Each prize comes with an inbox item and the push `evt.inbox`.

A tournament whose step fails is counted (`backend_worker_tournament_failures_total`), and it holds up no other
tournament.

### Season job

**The worker that holds `job:season` closes a season whose end has passed**
([D-63](../../docs/architecture/03-decision-log.md#d-63--a-season-ends-in-three-steps-each-safe-to-repeat-its-places-its-gems-then-its-reset)),
in three steps:

1. Its places on every board are written, and the next season is made, in one transaction.
2. Each place is paid its gems through the ledger, 1 000 places a page, each with an inbox item and
   `evt.inbox`. The board of teams' places are paid to the members who played for the team (D-65). Then the
   season is stamped as paid.
3. Every player's ratings move halfway back to 1 200, and their rated counts go to 0, 1 000 players a
   transaction. Then the teams' ratings are reset the same way.

Each step is stamped in the season's row. So a second run, or a run after a crash, pays nobody twice and halves
nobody twice. The worker extends the lock after each page and each batch. A run that finds the lock held by
another worker stops, and the next run goes on from the steps recorded.

### Replica heartbeat and lag

**Every second, the worker stamps the heartbeat on the primary**: `UPDATE ha_heartbeat SET at = NOW(6)`,
through the pool. Every 5 s it reads each listed host's `@@global.read_only` and stamp, with the probe's 2 s
limits. A replica's lag is the primary's stamp minus its own. A host that could not be read has no lag (NaN).
With one host in `BACKEND_DB_URL`, there is nothing to measure.

## The result pipeline

This section says what a worker accepts and what it promises
([05 §3](../../docs/detailed-design/05-worker-and-events.md#3-the-consumer-loop),
[06 §4](../../docs/detailed-design/06-persistence-mysql.md#4-the-three-transactions-that-matter)).

**The input.** The stream `s:match-result` is on the events store. An entry has one field, `e`, which holds the
envelope `{id, type, v, ts, payload}`:

- `type` is `match.result`, and `v` is 1;
- `payload` is the arena's `MatchOutcome`: `matchUid`, `kind`, `mode`, `arena`, `startedAtMillis`,
  `endedAtMillis`, `cutShort`, and for each player `playerId`, `displayName`, `team`, `placement`, `kills`,
  `deaths`, `score`, `playtimeSeconds`, `assists`.

A field may be added, but never given a new meaning. A worker ignores a field it does not know. Every add to the
stream trims entries older than `BACKEND_RESULT_RETENTION_HOURS`, 24 hours unless set
([D-83](../../docs/architecture/03-decision-log.md#d-83--the-result-streams-retention-is-a-setting-and-the-events-store-is-sized-by-a-table)).
The list `q:match-result` is the result inbox: every worker drains it into the stream. It is there for an
arena of the previous release, and for an operator who puts dead entries back.

**Delivery** is at least once: the consumer group is `rewards`, and each worker is one consumer. A worker
acknowledges an entry (`XACK`) only after its transaction has committed. A crash in between therefore delivers
the entry again, and the apply recognises the redelivery by its `match_player` rows. Each entry ends in one
place:

| What happened | Where it goes |
|---|---|
| Applied, or applied before | acknowledged |
| Unreadable: malformed, another type, an older or absent version, no payload | `q:match-result:dead`, then acknowledged |
| Written by a newer producer (`v` above 1) | `q:match-result:deferred`, then acknowledged; returned through the result inbox when a worker starts |
| A value no column can hold, a result that ended before it began, or one that ended more than 30 days ago | the dead list |
| A database error no retry can change: a `CHECK`, a foreign key, a value out of range or too long (3819, 1452, 1264, 1690, 1406, 1366, SQLSTATE class 22 or 23) | the dead list |
| Any other database error | left pending; claiming pauses, and the re-drive tries it first |
| Anything unexpected | left pending, and re-driven every 30 s for as long as it fails (`backend_worker_failed_total` rises) |
| Trimmed from the stream while pending, after a day of failing | counted as lost, `backend_worker_trimmed_unapplied_total`, and logged with its id |

**What a result is worth.** `RewardRules` works this out before the transaction:

- xp is the score plus 5 for each kill;
- coins are one for each ten points of score, plus 50 for placing first or 10 for taking part;
- a boost that was running when the match ended raises each by its percent, rounded down;
- a player who played under 5 s is paid nothing, so that quitting never pays, and the result counts for
  nothing beyond its `match_player` row.

A timed result of a rated mode that was not cut short moves ratings by `EloRating`, from the ratings the
transaction has locked. A walkover moves none. The new level is `AccountLevels`' level for the locked xp plus the
xp gained.

**After the commit**, and before the acknowledgement:

- The worker tells each player whom the result newly paid, through the lobby push `evt.rewards`:
  `{matchUid, mode, placement, coins, xp, ratingDelta, gems, achievements[], goals[], goalCoins, pass: {points,
  tier, coins, gems, items[]}}` (05 §6). A redelivery pays nobody new, so it tells nobody again.
- Each existing player's score above 0 goes on the all-time board, and on the boards of the day and the week
  in which the match ended: `ZADD … GT`, under the name that MySQL holds. This happens on every delivery,
  a redelivery included. A maximum costs nothing to repeat, and the repeat repairs a board write that a crash
  lost. Each write waits 2 s at most. One that fails is counted (`backend_worker_unranked_total`), and the
  entry is acknowledged all the same.

## Configuration

On a server these are set in `/etc/backend/worker.env`, which every worker on the machine and the rebuild read,
and then in `/etc/backend/worker-<id>.env` for one worker's own settings (its metrics port). Both files are
`root:root 0600`, and the secrets are plain variables in `worker.env`
([D-102](../../docs/architecture/03-decision-log.md#d-102--a-processs-secrets-are-in-its-own-settings-file-as-plain-variables-the-units-carry-none)).

| Variable | Default | Meaning | Read by |
|---|---|---|---|
| `STORE_HOST`, `STORE_PORT` | none: set in `worker.env` | The session store's host and port, which the units pass as the first two arguments. They stay set even when `BACKEND_STORE_ADDRESSES` is used: an empty port stops the process (exit 2) | the units' `ExecStart` |
| `BACKEND_DB_URL` | `jdbc:mysql://127.0.0.1:3314/backend_dev?useSSL=false&allowPublicKeyRetrieval=true`, the development MySQL (O-40) | The database; two hosts name the primary and its replica | `persistence/DatabaseSettings` |
| `BACKEND_DB_USER` | `backend` | Its user | `DatabaseSettings` |
| `BACKEND_DB_PASSWORD_FILE`, `BACKEND_DB_PASSWORD` | the development password, with a warning | Its password. On a server, `BACKEND_DB_PASSWORD` in `worker.env`. The file wins where both are set, and a named file that cannot be read stops the start | `DatabaseSettings`, `common/Secrets` |
| `BACKEND_DB_POOL_SIZE` | 8 (the rebuild 2) | The pool, 1 to 100; any other value is exit 2 | `DatabaseSettings` |
| `BACKEND_STORE_ADDRESSES` | the command line's host and port | The session store, primary and replica, `host:port,host:port` | `handoff/StoreClients` |
| `BACKEND_STORE_PASSWORD_FILE`, `BACKEND_STORE_PASSWORD` | none: the worker warns and connects without a password, which only a development store accepts | The store's password | `StoreClients` |
| `BACKEND_EVENTS_STORE` | the session store | The events store, which holds the result stream; `host:port`, or the primary and its replica | `StoreClients` |
| `BACKEND_RESULT_RETENTION_HOURS` | 24 | Hours the result stream keeps an entry, 1 to 168, the same as every arena's (D-83). A worker adds to the stream what it moves from the result inbox, so it trims too. Any other value is exit 2 | `MatchResultStream` |
| `BACKEND_EVENTS_STORE_PASSWORD_FILE`, `BACKEND_EVENTS_STORE_PASSWORD` | the store's password | The events store's password | `StoreClients` |
| `BACKEND_METRICS_ADDR` | unset: no metrics server | Where `/metrics` is served: 9102 and 9112 for the two workers on a machine, in each `worker-<id>.env`. Two workers given one port: the second exits 2 | `common/MetricsServer` |

How each is set on a machine, and the secrets, are in
[operations/01 §2](../../docs/operations/01-deploy.md#2-conventions).

## Metrics

The worker serves its metrics at `/metrics`, in the Prometheus text format, when `BACKEND_METRICS_ADDR` is set.
The platform's watch reads them and raises the alerts
([D-86](../../docs/architecture/03-decision-log.md#d-86--monitoring-is-the-backends-own-a-watch-in-every-platform-the-runbooks-rules-in-code-alerts-in-the-store-sent-by-webhook),
[operations/08](../../docs/operations/08-monitoring.md)).

| Metric | Type | What it is |
|---|---|---|
| `backend_worker_applied_total` | counter | Results applied |
| `backend_worker_duplicates_total` | counter | Redelivered results recognised and skipped |
| `backend_worker_dead_lettered_total`, `backend_worker_dead_letter_depth` | counter, gauge | Results set aside, and how many wait on the dead list: **alert above 0** |
| `backend_worker_deferred_total`, `backend_worker_deferred_depth` | counter, gauge | Results from a newer producer set aside, and how many wait |
| `backend_worker_failed_total` | counter | Attempts that failed and will be retried: alert on a steady rise |
| `backend_worker_unranked_total` | counter | Applied results that the boards missed |
| `backend_worker_queue_depth` | gauge | Results not yet delivered: the group's lag, plus the result inbox |
| `backend_worker_pending` | gauge | Results delivered and not yet acknowledged |
| `backend_worker_trimmed_unapplied_total` | counter | Results trimmed before anyone applied them: lost, **alert above 0** |
| `backend_worker_pushes_unheard_total` | counter | Pushes to a player registered on a gateway whose channel nobody heard |
| `backend_worker_retention_deleted_total`, `backend_worker_retention_seconds` | counter, gauge | Matches deleted by retention; how long this worker's last run took, NaN before one (alert past 1 800) |
| `backend_worker_guests_erased_total` | counter | Guests nobody played for 180 days, erased by retention (D-87) |
| `backend_worker_tournament_failures_total` | counter | Tournament ticks, or one tournament's step, that failed and will be tried again in 5 s: alert on a steady rise |
| `backend_worker_ledger_mismatches`, `backend_worker_ledger_checked_timestamp_seconds` | gauge, gauge | The fleet's last ledger check, the same on every worker: alert above 0, or when it is older than two days |
| `backend_backup_succeeded_timestamp_seconds{kind}`, `backend_backup_failed{kind}`, `backend_backup_restore_seconds` | gauge, gauge, gauge | The backup steps (`dump`, `proof`, `offsite`): the last success (NaN for never), 1 when the last run failed, and the last proof's restore and replay time (alert past 7 200) |
| `backend_mysql_table_rows{table}` | gauge | InnoDB's estimate for `ledger`, `match_player`, `matches`, `player_day`, `inbox`, `player` (alert when `match_player` passes 500 million, D-72) |
| `backend_mysql_replica_lag_seconds{host}`, `backend_mysql_replica_up{host}` | gauge, gauge | Each MySQL replica's lag by the heartbeat, and 1 while it can be read |
| `backend_store_replicas`, `backend_store_replica_behind_bytes`, `backend_store_replica_ack_seconds` | gauge | The session store's replicas, as its primary reports them; the same under `backend_events_store_*` when the events store is a separate instance |
| `backend_store_timeouts_total`, `_failed_fast_total`, `_reconnects_total`, `_server_errors_total`, `_connected` | counter, gauge | The store client; the same under `backend_events_store_*` when the events store is a separate instance |
| `backend_jvm_heap_used_bytes`, `backend_jvm_threads`, `backend_jvm_gc_seconds_total`, `backend_process_uptime_seconds` | gauge, counter | The process |

The [runbook, §5](../../docs/operations/02-runbook.md#5-what-to-watch) says what to alert on and what to do.

## Build and test

From `backend/`, offline:

```bash
. ../dev-env.sh                                     # vendor/'s JDK and Maven, the bundle, work/ (D-80)
mvn -pl worker -am install                          # this module and what it needs
mvn -pl worker test -Dtest=MatchResultPipelineTest  # one class
```

The tests run against MySQL's `backend_test`, which they drop and migrate afresh (set up as for
[persistence](../persistence/README.md#tests)). They use an embedded j-redis that the test starts, so there is no
store to install.

| Class | What it holds |
|---|---|
| `MatchResultPipelineTest` | The loop end to end: rows, coins and xp; the ratings of each mode; boosts; `evt.rewards`; the boards and their repair; redelivery; a crash in the middle of an apply; every fate in the table above; the database failing and recovering; take-over, the result inbox, trims, a lost group; the retention run and its steps; a player who barely played, not paid |
| `TournamentSchedulerTest` | Seeding, rooms, claims lost to another worker, decisions with and without a result, results cut short, round robins, prizes and gems, a failing step |
| `SeasonKeeperTest` | A season closed, paid and reset once; the teams'; the lock; a run stopped part-way and finished by the next |
| `LedgerCheckTest` | Once a day across the fleet; a mismatch in coins or gems found and reported; a failed check gives the day back |
| `LeaderboardRebuildTest` | A rebuild equal to the live boards; nothing lowered on a live store; an erased player not rebuilt (D-84) |
| `IdleGuestsTest` | The idle guests erased as a deletion erases: off the boards, the team handed over, the key gone, recent guests and registered players kept; a batch at a time; the store first, so a store that fails erases nothing (D-87) |
| `ReplicaWatchTest` | The heartbeat stamped through the pool every second; a replica that cannot be read reported so; the stores' replicas counted (D-58) |
| `EloRatingTest`, `AccountLevelsTest`, `BackupWatchTest` | The rules, and the backup metrics, without a database |

## Operational notes

- **Two workers a machine**, `worker-c1` and `worker-c2`, sharing its cores, each with its own metrics port
  (9102 and 9112). Two given one port: the second exits 2.
  - In the three-machine design they run on C, each with a 1.5 GB heap (the unit's `-Xms1536m -Xmx1536m`).
  - At the one-machine launch
    ([D-93](../../docs/architecture/03-decision-log.md#d-93--the-site-launches-on-one-machine-for-up-to-about-5-000-players))
    both run on that machine. The install guide gives each a 1 GB heap by a drop-in, or 512 MB growing to 1 GB
    on a machine with less than 48 GB of memory
    ([operations/03 §8](../../docs/operations/03-install-guide.md#8-the-processes-settings)).
- **The first start of a release migrates the database.** Migrations are forward-only; a changed one stops the
  start ([06 §8](../../docs/detailed-design/06-persistence-mysql.md#8-migrations)).
- **A database that does not answer at the start** is tried for up to a minute (D-94). After that the start
  fails with exit 1, and the unit starts the worker again 2 s later.
- **A database that fails while the worker runs** parks one entry, and the worker claims no more. The queue
  waits in the stream. The same running worker catches up when MySQL is back, without a restart.
- **The dead list is evidence**: each entry is a result someone was not paid for. Fix the cause, then put the
  entries back on `q:match-result`, which every worker drains
  ([runbook §3](../../docs/operations/02-runbook.md#3-per-component-procedures)). The apply is idempotent, so a
  result applied before is skipped.
- **The deferred list** waits for a worker that can read a newer version. Upgrading the workers returns it when
  they start.
- **A result pending for a whole day is lost** when the stream trims it, and it is counted
  (`backend_worker_trimmed_unapplied_total`). A result more than 30 days late is refused.
- **Stopping.** The shutdown hook stops the loop and the jobs, and logs the counters. An apply in flight is
  rolled back with its connection and delivered again: to this worker, or after a minute to any other. The unit
  allows 30 s for the stop.
- **After a store loss**, or when `backend_worker_unranked_total` rose, run `backend-leaderboard-rebuild` once,
  on one worker machine.
