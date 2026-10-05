# worker

Everything that happens after a match
([05](../../docs/detailed-design/05-worker-and-events.md)). A worker reads the
results the arenas publish to the stream `s:match-result`, applies each to MySQL
exactly once per player through `persistence`, tells each player what it paid
(`evt.rewards`) and puts their scores on the store's leaderboards. Every worker also
runs the scheduled jobs: retention, the daily ledger check, the tournaments' clock,
the season's close, and the MySQL replica's heartbeat. The balance rules a result is
paid by, rewards, account levels and Elo, live here and nowhere else, so changing
them is one release of one process.

- [Classes](#classes)
- [Entry points](#entry-points)
- [Scheduled jobs](#scheduled-jobs)
- [The result pipeline](#the-result-pipeline)
- [Configuration](#configuration)
- [Metrics](#metrics)
- [Build and test](#build-and-test)
- [Operational notes](#operational-notes)

## Classes

Package `com.backend.worker`.

| Class | What it does |
|---|---|
| `WorkerMain` | The process: settings, the store and database clients, migrations, every job started, the metrics, the shutdown hook, then the result loop |
| `MatchResultConsumer` | The result loop: claim, decode, apply, tell, rank, acknowledge; dead-letters, defers, re-drives, takes over |
| `RewardRules` | What a player's part of a match is worth in xp and coins, boosts included |
| `AccountLevels` | The account level a lifetime xp total is worth: `3 000 × (L − 1)^p`, p ≈ 1.654, level 100 at 6 000 000 |
| `EloRating` | How a rated match moves ratings: each player against each other by placement, K 32, then 16 after 30 rated matches |
| `Retention` | The daily purge of everything kept for a while |
| `LedgerCheck` | The fleet's daily check that every balance is its ledger's sum |
| `ReplicaWatch` | The MySQL heartbeat, each replica's lag, and the stores' replicas, for the metrics |
| `TournamentScheduler` | The tournaments' clock: seeding, starting, matches made and decided, rounds, prizes |
| `SeasonKeeper` | Closes a season that has ended: its places, its gems, its reset |
| `BackupWatch` | Turns the backup steps' latest rows into the backup metrics |
| `LeaderboardRebuild` | A separate entry point: the store's leaderboards rebuilt from MySQL |

## Entry points

**`WorkerMain [storeHost] [storePort] [workerId]`**: the worker. The store's host and
port default to `127.0.0.1` and `6379`, replaced by `BACKEND_STORE_ADDRESSES` when it
is set; `workerId` defaults to `worker-1`. The database comes from the environment
only: a command line in the old shape, a JDBC URL in third place, is refused with
exit 2 rather than read, so an old password cannot become a worker's id.

The id names this worker's consumer in the stream's group. Keep it **stable across
restarts**, so a restarted worker finds its own unfinished entries at once rather than
after the minute in which any worker may take them, and **unique among workers**, or
two re-drive each other's entries. The unit `backend-worker@.service` passes
`${STORE_HOST} ${STORE_PORT} worker-%i`, reading `/etc/backend/worker.env` and then
`/etc/backend/worker-%i.env`
([deploy/env](../deploy/env/worker.env.example)).

Start-up, in order:

1. The settings read (`DatabaseSettings`); a warning if the development password is
   in use.
2. The session store opened, and the events store, `BACKEND_EVENTS_STORE`, or the
   session store again when that is unset (D-7).
3. The pool opened, 8 connections unless `BACKEND_DB_POOL_SIZE` says otherwise, and
   the migrations run (Flyway; a racing start waits on its lock).
4. The jobs started: retention, the ledger check, the replica watch, the tournament
   tick, the season job.
5. The metrics served if `BACKEND_METRICS_ADDR` is set.
6. The shutdown hook installed.
7. `recoverAbandoned()`: the deferred list returned through the inbox, the inbox
   drained into the stream, this worker's pending entries re-driven; then the result
   loop, which blocks until the process stops.

Exit 2 is a refused configuration, which a restart cannot fix, and the unit does not
restart on it (`RestartPreventExitStatus=2 243`, 243 a missing credential); exit 1 is
any other failure, and the unit restarts it.

**`LeaderboardRebuild [storeHost] [storePort]`**: puts the store's leaderboards back
from MySQL ([05 §8](../../docs/detailed-design/05-worker-and-events.md#8-rebuilding-after-a-store-loss)),
after the store's disk is lost or after an outage in which results were applied but
not ranked (`backend_worker_unranked_total` rose). The all-time board from
`player_stat.best_score`, read 1 000 players a page; each day and week board still
alive (3 and 10 days after its last score) from the matches that ended in it. Every
write is `ZADD … GT` and a live board keeps its expiry, so it is safe on a healthy
store. Run it as `systemctl start backend-leaderboard-rebuild` on one worker machine:
the worker's settings and credentials, a pool of 2. It prints what it wrote and exits
0, 1 or 2 as the worker does.

## Scheduled jobs

Every worker runs all of them. None needs a worker of its own.

| Job | Class | When | Across the fleet |
|---|---|---|---|
| Result loop | `MatchResultConsumer` | continuously: a claim waits up to 2 s; a re-drive every 30 s | the group `rewards`, one consumer a worker |
| Retention | `Retention` | 1 minute after start, then every 24 hours | no lock: the deletes are idempotent and take their locks in one order |
| Ledger check | `LedgerCheck` | an attempt 5 minutes after start, then every hour | once a day: `SET job:ledger-check <workerId> NX EX 82800` (23 hours) |
| Tournament tick | `TournamentScheduler` | every 5 s | no lock: every write conditional on what the tick read |
| Season job | `SeasonKeeper` | every minute | `SET job:season <workerId> NX EX 600`, extended while a close runs, deleted when done |
| Replica heartbeat | `ReplicaWatch` | every second | none |
| Replica lag | `ReplicaWatch` | every 5 s, on a thread of its own | none |

The locks are in the session store. A job's failure is logged and counted where
there is a counter, and the next run carries on; none ends the process.

### The result loop

Each pass of the loop first re-drives when 30 s have passed: the inbox drained into
the stream, entries idle a minute on any consumer taken over (`XAUTOCLAIM`, 64 at a
time, a retired worker's among them), and this worker's own pending entries applied
again, oldest first, stopping at the first that fails for the database's sake. Then
it claims one new entry (`XREADGROUP … COUNT 1 BLOCK 2000`) and processes it.

While the database is failing, the worker **claims nothing new**: it pauses 5 s and
re-drives its parked entry as the probe, so a long outage parks one entry, not the
queue. A failed store command outside an apply pauses it 1 s; the loop never ends on
one.

### Retention

Daily, in this order, each step 1 000 rows a transaction
([06 §9](../../docs/detailed-design/06-persistence-mysql.md#9-growth-and-retention)):

1. Matches that ended more than 90 days ago, oldest first, with their `match_player`
   and `match_team` rows. A cutoff inside the 30 days a result may still arrive is
   refused.
2. Inbox items older than 30 days, read or not.
3. `player_day` rows older than 90 days.
4. Friend requests, team invitations and team applications that have lapsed.
5. Boosts that ended more than 31 days ago, and activation keys used more than 30
   days ago.
6. Tournaments finished or cancelled whose start was more than 90 days ago, one a
   transaction, with their entries, rosters and bracket.
7. Daily goals' progress more than 7 days old.
8. Payment orders pending longer than a day, marked expired; the rows stay.
9. Season passes older than the season being played and the five before it.
10. `backup_run` rows older than 90 days, except each kind's newest success.

Each step stands on its own: one that fails is logged and the next still runs, and
the run's time is recorded either way (`backend_worker_retention_seconds`). Player
totals, balances and the ledger are never touched.

### Ledger check

Whoever takes the day's lock compares every player's coins and gems with the sums of
their ledger rows, a range of player ids at a time, each range its own statement and
so its own consistent snapshot. It writes `<mismatches> <epochSeconds>` to
`ledger:check:last`, which every worker's metrics read back, and logs the first 20
mismatches by id. A check that fails deletes the lock, if it still holds it, so the
next hour tries again; the lock needs no fencing token, since the check only reads.

### Tournament tick

Every tournament not over, by id: at the registration deadline, seeded from the
entries it locks, or cancelled with fewer than two; at its start, running; then a
round at a time. A pending match is made as the matchmaker makes one: a room promised
on an arena, every seat's ticket written, and only then the match claimed in MySQL,
so a worker whose claim another worker won gives its room and tickets back. Each seat
gets a grant and the push `evt.tournament.match`. A ready match is decided from what
MySQL recorded for it: its winner; for a draw, a result cut short, or no result 270 s
after its tickets (1 800 s when somebody came), the higher seed, or in a round robin
a draw. After the last round the prizes are paid through the ledger, from four entries
with gems too (30, 15 and 5), each with an inbox item and the push `evt.inbox`. A
tournament whose step fails is counted (`backend_worker_tournament_failures_total`)
and holds up no other.

### Season job

Whoever holds `job:season` closes a season whose end has passed
([D-63](../../docs/architecture/03-decision-log.md#d-63--a-season-ends-in-three-steps-each-safe-to-repeat-its-places-its-gems-then-its-reset)):
its places on every board written with the next season in one transaction; then each
place paid its gems through the ledger, 1 000 places a page, each with an inbox item
and `evt.inbox`, and the season stamped paid; then every player's ratings moved
halfway back to 1 200 and their rated counts set to 0, 1 000 players a transaction;
then the teams'. Each step is stamped in the season's row, so a second run, or one
after a crash, pays nobody twice and halves nobody twice.

### Replica heartbeat and lag

`UPDATE ha_heartbeat SET at = NOW(6)` on the primary every second, through the pool;
every 5 s each listed host's `@@global.read_only` and stamp read with the probe's
2 s limits. A replica's lag is the primary's stamp less its own; a host not read has
none (NaN). With one host in `BACKEND_DB_URL` there is nothing to measure.

## The result pipeline

What a worker accepts and promises
([05 §3](../../docs/detailed-design/05-worker-and-events.md#3-the-consumer-loop),
[06 §4](../../docs/detailed-design/06-persistence-mysql.md#4-the-three-transactions-that-matter)).

**The input.** The stream `s:match-result` on the events store, an entry's one field
`e` holding the envelope `{id, type, v, ts, payload}`: `type` is `match.result`, `v`
is 1, and `payload` the arena's `MatchOutcome`: `matchUid`, `kind`, `mode`, `arena`,
`startedAtMillis`, `endedAtMillis`, `cutShort`, and for each player `playerId`,
`displayName`, `team`, `placement`, `kills`, `deaths`, `score`, `playtimeSeconds`,
`assists`. A field may be added, never repurposed; an unknown one is ignored. The
producer trims the stream to 24 hours. The list `q:match-result` is an inbox every
worker drains into the stream, for an arena of the previous release and for an
operator putting dead entries back.

**Delivery.** At least once: the group `rewards`, each worker a consumer. An entry is
acknowledged (`XACK`) only after its transaction has committed, so a crash in between
redelivers it, and the apply recognises a redelivery by its `match_player` rows. Where
an entry ends:

| What happened | Where it goes |
|---|---|
| Applied, or applied before | acknowledged |
| Unreadable: malformed, another type, an older or absent version, no payload | `q:match-result:dead`, then acknowledged |
| Written by a newer producer (`v` above 1) | `q:match-result:deferred`, then acknowledged; returned through the inbox when a worker starts |
| A value no column can hold, a result that ended before it began or more than 30 days ago | the dead list |
| A database error no retry can change: a `CHECK`, a foreign key, a value out of range or too long (3819, 1452, 1264, 1690, 1406, 1366, SQLSTATE 22 or 23) | the dead list |
| Any other database error | left pending; claiming pauses, and the re-drive tries it first |
| Anything unexpected | left pending, re-driven every 30 s for as long as it fails (`backend_worker_failed_total` rises) |
| Trimmed from the stream while pending, after a day of failing | counted as lost, `backend_worker_trimmed_unapplied_total`, and logged with its id |

**What a result is worth** (`RewardRules`, worked out before the transaction): xp is
the score plus 5 a kill; coins are one a ten of score, plus 50 for placing first or 10
for taking part; a boost running when the match ended raises each by its percent,
rounded down. A player who played under 5 s is paid nothing, so that quitting is never
profitable, and the result counts for nothing beyond its `match_player` row. A rated
mode's timed result, not cut short, moves ratings by `EloRating` from the ratings the
transaction has locked; a walkover moves none. The level is `AccountLevels`' for the
xp locked plus the xp gained.

**After the commit**, before the acknowledgement:

- Each player the result newly paid is told, through the lobby push, `evt.rewards`:
  `{matchUid, mode, placement, coins, xp, ratingDelta, gems, achievements[], goals[],
  goalCoins, pass: {points, tier, coins, gems, items[]}}` (05 §6). A redelivery pays
  nobody new, so tells nobody again.
- Each existing player's score above 0 goes on the all-time board and those of the
  day and week the match ended in, `ZADD … GT` under the name MySQL holds, on every
  delivery, a redelivery included:
  a maximum costs nothing to repeat, and the repeat is the recovery for a board write
  a crash lost. Each write waits 2 s at most; one that fails is counted
  (`backend_worker_unranked_total`) and the entry is acknowledged all the same.

## Configuration

| Variable | Default | Meaning | Read by |
|---|---|---|---|
| `BACKEND_DB_URL` | `jdbc:mysql://127.0.0.1:3306/backend_dev?useSSL=false&allowPublicKeyRetrieval=true` | The database; two hosts for the primary and its replica | `persistence/DatabaseSettings` |
| `BACKEND_DB_USER` | `backend` | Its user | `DatabaseSettings` |
| `BACKEND_DB_PASSWORD_FILE`, `BACKEND_DB_PASSWORD` | the development password, with a warning | Its password; the file preferred, and a named file that cannot be read stops the start | `DatabaseSettings`, `common/Secrets` |
| `BACKEND_DB_POOL_SIZE` | 8 (the rebuild 2) | The pool, 1 to 100 | `DatabaseSettings` |
| `BACKEND_STORE_ADDRESSES` | the command line's host and port | The session store, primary and replica, `host:port,host:port` | `handoff/StoreClients` |
| `BACKEND_STORE_PASSWORD_FILE`, `BACKEND_STORE_PASSWORD` | none | The store's password | `StoreClients` |
| `BACKEND_EVENTS_STORE` | the session store | The events store, which holds the result stream | `StoreClients` |
| `BACKEND_EVENTS_STORE_PASSWORD_FILE`, `BACKEND_EVENTS_STORE_PASSWORD` | the store's password | The events store's password | `StoreClients` |
| `BACKEND_METRICS_ADDR` | unset: no metrics server | Where `/metrics` is served; 9102 and 9112 for the two workers on a machine | `common/MetricsServer` |

How each is set on a machine, and the secrets' files, are in
[operations/01 §2](../../docs/operations/01-deploy.md#2-conventions).

## Metrics

Served in the Prometheus text format at `/metrics`.

| Metric | What it is |
|---|---|
| `backend_worker_applied_total` | Results applied |
| `backend_worker_duplicates_total` | Redelivered results recognised and skipped |
| `backend_worker_dead_lettered_total`, `backend_worker_dead_letter_depth` | Results set aside, and how many wait on the dead list: **alert above 0** |
| `backend_worker_deferred_total`, `backend_worker_deferred_depth` | Results from a newer producer set aside, and how many wait |
| `backend_worker_failed_total` | Attempts that failed and will be retried: alert on a steady rise |
| `backend_worker_unranked_total` | Applied results the boards missed |
| `backend_worker_queue_depth` | Results not yet delivered: the group's lag, plus the inbox list |
| `backend_worker_pending` | Results delivered and not yet acknowledged |
| `backend_worker_trimmed_unapplied_total` | Results trimmed before anyone applied them: lost, **alert above 0** |
| `backend_worker_pushes_unheard_total` | Pushes to a player registered on a gateway whose channel nobody heard |
| `backend_worker_retention_deleted_total`, `backend_worker_retention_seconds` | Matches deleted by retention; how long the last run took (alert past 1 800) |
| `backend_worker_tournament_failures_total` | Tournament ticks, or one tournament's step, that failed |
| `backend_worker_ledger_mismatches`, `backend_worker_ledger_checked_timestamp_seconds` | The fleet's last ledger check, the same on every worker: alert above 0, or older than two days |
| `backend_backup_succeeded_timestamp_seconds{kind}`, `backend_backup_failed{kind}`, `backend_backup_restore_seconds` | The backup steps (`dump`, `proof`, `offsite`): last success, last run failed, the last proof's restore and replay |
| `backend_mysql_table_rows{table}` | InnoDB's estimate for `ledger`, `match_player`, `matches`, `player_day`, `inbox`, `player` |
| `backend_mysql_replica_lag_seconds{host}`, `backend_mysql_replica_up{host}` | Each MySQL replica's lag by the heartbeat, and whether it was read |
| `backend_store_replicas`, `backend_store_replica_behind_bytes`, `backend_store_replica_ack_seconds` | The session store's replicas, as its primary tells them; the same as `backend_events_store_*` |
| `backend_store_timeouts_total`, `_failed_fast_total`, `_reconnects_total`, `_server_errors_total`, `_connected` | The store client; the same as `backend_events_store_*` when the events store is apart |
| `backend_jvm_heap_used_bytes`, `backend_jvm_threads`, `backend_jvm_gc_seconds_total`, `backend_process_uptime_seconds` | The process |

What to alert on, and what to do, is in the
[runbook, §5](../../docs/operations/02-runbook.md#5-what-to-watch).

## Build and test

From `backend/`, offline:

```bash
export JAVA_HOME=/opt/jdk21 PATH=/opt/jdk21/bin:$PATH
/opt/maven/bin/mvn -o -pl worker -am install                          # this module and what it needs
/opt/maven/bin/mvn -o -pl worker test -Dtest=MatchResultPipelineTest  # one class
```

The tests run against MySQL's `backend_test`, which they drop and migrate afresh
(set as for [persistence](../persistence/README.md#tests)), and an embedded j-redis
started in the test: no store to install.

| Class | What it holds |
|---|---|
| `MatchResultPipelineTest` | The loop end to end: rows, coins and xp; ratings of each mode; boosts; `evt.rewards`; boards and their repair; redelivery; a crash mid-apply; every fate in the table above; the database failing and recovering; take-over, the inbox, trims, a lost group |
| `TournamentSchedulerTest` | Seeding, rooms, claims lost to another worker, decisions with and without a result, cut short, round robins, prizes and gems, a failing step |
| `SeasonKeeperTest` | A season closed, paid and reset once; the teams'; the lock; a run stopped part-way finished by the next |
| `LedgerCheckTest` | Once a day across the fleet; a mismatch in coins or gems found and reported; a failed check gives the day back |
| `LeaderboardRebuildTest` | A rebuild equal to the live boards; nothing lowered on a live store |
| `ReplicaWatchTest` | The heartbeat stamped through the pool |
| `EloRatingTest`, `AccountLevelsTest`, `BackupWatchTest` | The rules, and the backup metrics, without a database |

## Operational notes

- **Two workers a machine**, `worker-c1` and `worker-c2` on C, sharing its cores, each
  with a 1.5 GB heap and its own metrics port. Two given one port: the second exits 2.
- **The first start of a release migrates the database.** Migrations are
  forward-only; a changed one stops the start
  ([06 §8](../../docs/detailed-design/06-persistence-mysql.md#8-migrations)).
- **A database down at start** parks one entry and claims no more; the queue waits in
  the stream, and the same running worker catches up when MySQL is back, without a
  restart.
- **The dead list is evidence**: each entry a result someone was not paid. Fix the
  cause, then put the entries back on `q:match-result`, which every worker drains
  ([runbook §3](../../docs/operations/02-runbook.md#3-per-component-procedures)); the
  apply is idempotent, so a result applied before is skipped.
- **The deferred list** waits for a worker that can read a newer version: upgrading
  the workers returns it at their start.
- **A result pending a whole day is lost** when the stream trims it, and counted
  (`backend_worker_trimmed_unapplied_total`); one more than 30 days late is refused.
- **Stopping**: the hook stops the loop and the jobs and logs the counters; an apply
  in flight is rolled back with its connection and delivered again, to this worker or,
  after a minute, any other. The unit allows 30 s.
- **After a store loss**, or when `backend_worker_unranked_total` rose, run
  `backend-leaderboard-rebuild` once, on one worker machine.
