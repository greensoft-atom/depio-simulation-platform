# 05 — Worker and event streams

Everything that happens *after* a match: results applied, rewards granted,
leaderboards updated, tournaments advanced, analytics recorded.

The `worker` process consumes durable event streams from j-redis and applies
them to MySQL. It is the only component where "exactly once" is a requirement
rather than a nicety
([NFR-7](../requirements/01-scope-and-nfrs.md#4-non-functional-requirements)).

**The result queue is a stream since 2026-09-29**, j-redis 2.1.0 having
streams (plan item 10, "The move onto streams" below): arenas add to
`s:match-result`, and workers read it as the group `rewards`
(`handoff/MatchResultStream`, `arena/MatchResultPublisher`,
`worker/MatchResultConsumer`). The list `q:match-result` stays as an inbox the
workers drain into the stream.

**A list queue came first** (`handoff/MatchResultQueue`,
`arena/MatchResultPublisher`), verified end to end as separate processes: an
arena, a j-redis, a worker and MySQL, with ten bots playing. Four matches ended,
four were applied, forty `match_player` rows were written, and the ledger
re-derived the coin balances exactly. What it lacked, and the stream gives:

| Stream property | The list | The stream |
|---|---|---|
| Several consumer groups | One consumer | One group; analytics and moderation can each read the same entries as a group of their own, once they exist |
| Replay from an offset | None: a reward bug would have to be repaired in SQL | Within the 24 hours kept; beyond that, from MySQL ([Q-9](../requirements/01-scope-and-nfrs.md#7-open-questions)) |
| Per-group pending list | One processing list per worker, `q:match-result:processing:{workerId}`: a retired worker's needed someone to know it was dead | Pending entries per consumer; any worker takes over those idle a minute |
| Retention by age | None; the queue drains | 24 hours, read or not |

The stream is `s:` and the lists stay `q:`, so the change of meaning shows in
the name.

### The move onto streams (designed 2026-09-29, plan item 10)

j-redis 2.1.0 has streams
([j-redis 15](../../j-redis-service/docs/15-streams.md)), so the result queue
moves onto one ([D-33](../architecture/03-decision-log.md#d-33--the-result-queue-is-a-stream-read-by-one-group-the-list-stays-an-inbox)):

| | Before | After |
|---|---|---|
| The queue | the list `q:match-result`, `LPUSH` | the stream `s:match-result`, `XADD * e <envelope>`, trimmed `MINID ~` to 24 hours ([Q-9](../requirements/01-scope-and-nfrs.md#7-open-questions)) |
| Who reads it | each worker claims onto its own processing list | the group `rewards`, one consumer per worker, named by its id |
| Done | `LREM` from the processing list, after the commit | `XACK`, after the commit |
| A worker's own unfinished entries | its processing list, re-driven every 30 s | its pending entries, read back as its history (`XREADGROUP … 0`), re-driven every 30 s |
| A retired worker's | stranded until a worker with its id starts | claimed by any worker once idle a minute (`XAUTOCLAIM`, 60 s) |
| Unreadable, refused | the list `q:match-result:dead` | the same list, then `XACK` |
| From a newer producer | the list `q:match-result:deferred` | the same list, then `XACK`; returned through the inbox when a worker starts |

**The list stays, as an inbox.** Every worker moves whatever `q:match-result`
holds into the stream, at start and at every re-drive, each entry claimed onto
its processing list first, added to the stream, then removed. So arenas of the
previous release, still pushing to the list during a rolling deploy, lose
nothing whichever of the two is upgraded first; a processing list left by the
list-reading worker is drained the same way; and the runbook's way of putting
dead entries back on the queue still works. An entry moved twice, after a
crash between the add and the remove, is applied once: the apply is
idempotent (§3).

**What a trim can take.** The stream is trimmed to 24 hours whether or not an
entry was read. An entry pending longer than that, whose worker has failed on
it for a day, is gone from the stream. The next re-drive's take-over
(`XAUTOCLAIM`) finds it gone, whatever its idle time, and the store drops it
from the pending list. That is a lost result, logged as an error with its ID
and counted (`backend_worker_trimmed_unapplied_total`), never silently dropped.

**Pushed, then acknowledged, as two commands**, as the list did (§3). Not in
one transaction: `EXEC` runs every command even when one is refused, so a push
the store refused would still have its `XACK` run, and the entry would be
nowhere. Two commands leave it pending when the push fails, and on the list and
pending after a crash between them; its redelivery sets it aside again, a
duplicate of the evidence and no loss.

**The group is made again when it is gone.** A store restored empty, or the
key deleted, takes the group with the stream, and every read would be refused
with `NOGROUP` for ever. So each re-drive makes the group if it is missing
(`XGROUP CREATE … 0 MKSTREAM`, a `BUSYGROUP` refusal ignored), and so does a
read refused with `NOGROUP`, at once. From `0`, the start: what was added
before the group existed is read, not skipped. (Found building it, [defect
D-27](../defects.md#3-data-and-the-result-pipeline): made once at start-up, a
lost group stopped the pipeline until a restart.)

**Measured as a group.** `backend_worker_queue_depth` becomes the group's lag,
entries not yet delivered (`XINFO GROUPS`), plus the inbox's length; a new
`backend_worker_pending` counts entries delivered and not yet acknowledged.

**Order of the upgrade.** The events instance runs j-redis 2.1.0 first: an
arena or worker of this release against a 2.0 store is refused at its first
stream command. Then arenas and workers in either order.

Parts: (a) the backend on j-redis 2.1.0; (b) the worker on the stream: the
group, reads, acknowledgements, re-drives, reclaiming, the inbox, the lists,
the metrics; (c) the arena publishing to the stream, trimmed; (d) operations,
the runbook and a live drill, a worker killed with entries in flight among
them. **(a) and (b) built 2026-09-29.** Tested besides the list's cases: a
retired worker's entry taken over once idle, and not before; the inbox and a
list-reading worker's processing list drained into the stream; an entry trimmed
while pending counted as lost once, its own or a retired worker's, idle or not;
the group lost with its stream and made again, by a re-drive and by a read.
Mutation-checked: 22 mutants, 21 caught. The one left is a guard for a trim
landing between a re-drive's take-over and its history read, which no test can
time: without it, that entry throws once, and the next pass counts it.
**(c) built the same day:** the arena adds each result with `MINID ~` a day
before its own clock, so every add trims what is older. Tested: an entry a day
and a minute old trimmed by the next add, one a minute short of a day kept, and
the spool's cases (the store down, a restart, a close, the order) read back
from the stream. Mutation-checked 5 of 5.

**(d), the drill** (`TAKEOVER=1 client/headless-drill.sh <scenario>`, from a
release). The worker is stopped before the scenario, so its results wait in
the stream; they are then read as that worker (`XREADGROUP … >` from the CLI),
which leaves them pending for it exactly as a crash mid-apply would, and it is
killed with `-9`. A second worker, named differently, as a replacement machine
would be, must take them over once idle a minute and apply each once: the
group's pending count back to 0, and every result in the stream in MySQL.
**Run 2026-09-29 from the release:** a stay and a duel pending on the killed
worker, taken over by the second in 66 s and applied once each (the duel's
players 50 coins and one ledger row apiece, `applied 2, duplicates 0`); again
with `play` alone, one result in 68 s. The store restore drill, run on that
store, found the stream and its group the same in the copy.

## 1. Why a stream and not a queue

A list queue (`LPUSH`/`BRPOPLPUSH`) delivers each entry to exactly one
consumer and then forgets it. That is fine for one consumer and fatal for
several: the moment analytics wants to see match results *and* the reward
pipeline does, one of them has to re-publish for the other, and a crash
between the two loses an event with no way to notice.

Streams give what is actually needed
([D-6](../architecture/03-decision-log.md#d-6--no-custom-message-broker-use-j-redis-streams)):

| Property | Why it matters here |
|---|---|
| Multiple consumer groups | Rewards, analytics and moderation read the same events independently |
| Per-group cursor plus a pending list | A crash mid-processing is recoverable, because unacknowledged entries are still claimable |
| Replay from an offset | A bug in reward calculation can be fixed and the affected range reprocessed |
| Retention by count or age | Bounded memory without a separate cleanup job |

The event rate is modest — ~170 match-end events/s at 50 000 CCU — so this is
about semantics, not throughput. **Measured on the development machine**
(2026-09-30, plan items 23 and 26): one worker applies about 10 results a
second, each waiting on its commit's synchronous writes, and more workers
multiply that, since commits made at once share their writes: draining a
backlog of 600, one worker managed 10 to 16 a second, two 18 to 27 and four 25
to 36, over two rounds as this machine's load moved. The transaction alone, from 1, 2,
4 and 8 threads at once, went 18, 27–34, 66–69 and 104 a second. So the rate is
a number of workers, not a ceiling: at this machine's rate 170 a second is
several processes, or threads in one. Q-3's machines, whose disks differ, set
the number, with `tools/ApplyBenchmark` ([backend README](../../backend/README.md#the-apply-benchmark)).
A worker spends about as long again outside its transaction, on the boosts, the
boards and the stream, as in it: where to look first if it must go faster.

## 2. Streams

| Stream | Producer | Consumers | Retention | Loss tolerance |
|---|---|---|---|---|
| `s:match-result` | arena | `rewards`, `analytics` | 7 days | **None.** Progression and currency depend on it. |
| `s:match-event` | arena | `analytics`, `moderation` | 24 hours | Tolerable. Kill feeds and captures. |
| `s:platform` | platform | `analytics` | 7 days | Low. Purchases, team changes, tournament transitions. |
| `s:audit` | platform | `audit` | 90 days | **None.** Admin actions and bans. |

**The seven days are not affordable** (defect [D-26](../defects.md#3-data-and-the-result-pipeline)):
j-redis keeps a stream in memory, and a week of `s:match-result` is about 54 GB at
the design's load. 24 hours is recommended, replay beyond it reading MySQL
([Q-9](../requirements/01-scope-and-nfrs.md#7-open-questions)).

Partitioning is by **player id** where ordering matters, so a single player's
events are processed in order even though the stream as a whole is not
globally ordered. Two matches ending simultaneously for different players may
be applied in either order; two events for the *same* player may not.

**As built, nothing is partitioned.** There is one group, `rewards`, and any
worker takes any entry, so two results of one player may be applied at once by two
workers, or in either order. What a result does to a player is written so that
the order does not matter: each result locks the player's row before it reads
anything it writes, ratings move from the values locked, `first_played_on` is the
earliest day whichever result arrives first, and totals, goal progress and pass
points are added by the statement that writes them, never read plainly and written
back ([06 §4](06-persistence-mysql.md#4-the-three-transactions-that-matter)).

### Envelope

Every entry carries the same envelope, so a consumer can route and dedupe
without parsing the payload:

```json
{
  "id":      "01JB2...",        // ULID, assigned by the producer
  "type":    "match.result",
  "v":       1,                 // payload schema version
  "ts":      1758585600123,
  "key":     "player:1001",     // ordering key
  "payload": { }
}
```

**As built, there is no `key`**: the interim queue has one consumer per entry and
nothing to partition, so the envelope is `id, type, v, ts, payload`. The key
belongs to the streams design (§5).

`id` is the idempotency key that reaches MySQL. `v` exists because a consumer
will one day meet an entry written by an older producer during a rolling
deploy, and "unknown version" must be a clear rejection rather than a
misparse.

## 3. The consumer loop

```
XREADGROUP GROUP rewards worker-1 COUNT 64 BLOCK 2000 STREAMS s:match-result >
  for each entry:
      apply in a MySQL transaction, idempotent on the natural key   (06 §4)
      XACK s:match-result rewards <entry-id>
```

**As built**, a worker reads one entry at a time (`COUNT 1`, `BLOCK 2000`),
applies it and acknowledges it; every 30 s, and at start-up
(`MatchResultConsumer.recoverAbandoned`), it drains the inbox, takes over
entries idle a minute (`XAUTOCLAIM`), and re-drives its own pending entries,
read back with `XREADGROUP … 0`. (Until 2026-09-29, a list: `BLMOVE` onto the
worker's own processing list, `LREM` when done.) The batch of 64 below is the
design's, not built.

These rules make this safe, and each exists because of a specific failure:

**Acknowledge after committing, never before.** A crash between commit and ack
redelivers the entry; the transaction is idempotent, so the redelivery is a
no-op. A crash between ack and commit would lose the event silently, which is
the failure nobody detects.

**Every entry ends in exactly one place:**

| What happened | Where the entry goes |
|---|---|
| Applied, or already applied | acknowledged |
| Unreadable, or a value no database could store | `q:match-result:dead`, kept as evidence, and acknowledged |
| Written by a newer producer than this build reads | `q:match-result:deferred`, and acknowledged; returned through the inbox whenever a worker starts |
| The database is failing | left pending for this worker, and re-driven |
| Trimmed away while pending, after a day of failing | counted as lost (`backend_worker_trimmed_unapplied_total`) and logged, by the next take-over, which drops it from the pending list |

The last row is the one that was broken. A failed transaction left the entry on
the processing list, and the only thing that ever read that list ran once, at
start-up — so a one-minute MySQL blip parked every result in it until somebody
restarted the worker. The list is now re-driven every 30 s and immediately after
the database recovers, and the worker **stops claiming new work while the
database is failing**: every claim would only move an entry from the queue to
its own list at the cost of a connection timeout. (True since 2026-09-26: until
then it claimed one more after every pause, and a long outage moved the whole
queue onto one worker's list, defect [D-20](../defects.md#3-data-and-the-result-pipeline).)

**Transient versus permanent.** A lost connection, a lock timeout or a deadlock
that outlasted its retries is retried. A value out of range, a broken foreign key
or a violated `CHECK` is dead-lettered, because retrying cannot change it. The
error code is checked as well as the SQLSTATE class: a `CHECK` violation is 3819
with SQLSTATE `HY000`, the generic "something went wrong", and a class-based rule
alone would retry it for ever. Anything unrecognised is treated as transient —
the conservative mistake is retrying something hopeless, not discarding
something recoverable.

**The loop does not die.** It used to call its handler unguarded, and an
acknowledgement that failed on a store hiccup threw out of `run()` and ended the
process. An unexpected failure now leaves that one entry for the next re-drive
and the worker carries on.

*Tested:* redelivery applied once; a crash mid-apply recovered; unreadable
entries dead-lettered; a database that fails and recovers caught up **by the same
running worker**; an unexpected failure on one entry neither stopping the worker
nor losing the entry; a starting worker not touching another's in-flight entry;
a newer version deferred; each failure class classified. Each of the new ones was
watched failing before the fix, and removing each fix fails exactly its test.

**Idempotency lives in MySQL, not in the consumer.** A plain insert into
`match_player (match_id, player_id)` is both the write and the duplicate check,
and only a duplicate-key error means "already applied"
([06 §4](06-persistence-mysql.md#4-the-three-transactions-that-matter)). A
consumer-side "have I seen this id" cache would be a second source of truth that
drifts on restart.

**Batch reads, single-entry transactions.** `COUNT 64` amortises the round
trip, but each entry commits on its own. Batching commits would mean one bad
entry poisoning 63 good ones.

**Claim abandoned work on start-up.** `XAUTOCLAIM` with a 60-second idle
threshold picks up entries a dead consumer never acknowledged. Without it, a
worker that dies mid-batch leaves its entries pending forever. As built, at
every re-drive, not only at start-up: a worker that is retired never starts
again.

## 4. Failure handling

```
delivery 1..3   retry with backoff 1 s, 5 s, 30 s
delivery 4      move to s:dlq:{stream} with the error and stack, then XACK
```

The distinction that matters:

| Failure | Response |
|---|---|
| Transient — MySQL unreachable, deadlock, lock timeout | Retry. Do not count toward the dead-letter limit; the entry is fine, the world is not. |
| Permanent — unknown schema version, malformed payload, referential violation | Dead-letter immediately. Retrying a malformed entry three times just delays the alert. |

**As built, in the interim queue**, a failure the worker did not expect (an
exception that is neither a database error nor a refusal it recognises) is not
dead-lettered after three deliveries: it is retried at every re-drive, every
30 s, for as long as it fails. An unexplained failure may be a bug the next
deploy fixes, and dead-lettering on a guess sets a payment aside. The price is
that such an entry never shows in the dead-letter depth, only as
`backend_worker_failed_total` rising steadily, so that is alerted on too
([operations/01](../operations/01-deploy.md#7-installing-a-machine)).

**A non-empty dead-letter stream is an alert, not a metric.** Each entry there
is a player whose reward did not arrive. The runbook entry is to fix the cause,
then replay the range — which is safe precisely because the transactions are
idempotent.

## 5. Ordering, and where it is not needed

Consumers run one thread per partition key hash, so events for one player are
serialised without a global lock. `worker` runs two instances in the same
consumer group; j-redis distributes entries between them.

As built, each worker is one consumer reading one entry at a time, and there is no
partition key (§2): a player's results are serialised by the lock on their row,
not by the order they are read in.

Most things do not need ordering at all, and pretending otherwise costs
throughput for nothing:

| Needs ordering | Does not |
|---|---|
| Currency changes for one player | Analytics rows |
| Tournament round transitions | Kill-feed events |
| Team membership changes | Leaderboard writes (a maximum is commutative) |

## 6. The result pipeline end to end

```
arena              match ends
                   XADD s:match-result * envelope{type=match.result, key=player:N}
                   always spooled to local disk first; pushed from there
                                 ──────────────────────────────────────────
worker/rewards     XREADGROUP → MySQL transaction (06 §4):
                       matches, match_player, player, player_stat, ledger,
                       daily_goal, season_pass, inventory_item, player_day,
                       match_team and team for a team match
                   → ZADD ... GT leaderboards in j-redis
                   → push "evt.rewards" via the gateway (best effort, 03 §7)
                   → XACK
                                 ──────────────────────────────────────────
worker/analytics   XREADGROUP → append to the analytics table → XACK
```

The `analytics` group was not made: activity is recorded in the rewards
transaction instead ([§11](#11-analytics-designed-2026-09-30-plan-item-25), D-47).

### What a match paid, pushed (designed 2026-10-01, plan item 43)

On [Q-39](../requirements/01-scope-and-nfrs.md#7-open-questions)'s
recommendation. After a result's transaction commits, and before its
acknowledgement, each player newly paid by it is pushed **`evt.rewards`**:

```json
{"matchUid": "…", "mode": "duel", "placement": 1, "coins": 50, "xp": 120, "ratingDelta": 0, "gems": 0,
 "achievements": [], "goals": [], "goalCoins": 0,
 "pass": {"points": 10, "tier": 0, "coins": 0, "gems": 0, "items": []}}
```

`gems` is what the result paid in level milestones and achievements, 0 in
nearly every result (04 §8, D-61, D-64); `achievements` names each achievement
it reached, for the client to show (since 2026-10-03, plan item 71 (b)); `goals`
each daily goal it met and `goalCoins` their coins, apart from the match's own
`coins`, the day's three together paying their gems into `gems` (since
2026-10-04, plan item 74, D-66). `pass` is the season pass's own: the points
the result earned, the tier after, and the coins, gems and items of the tiers it
reached, in neither `coins` nor `gems` (since 2026-10-04, plan item 75 (b),
D-69).

`mode` is the mode's key, `ffa` for a stay in the public arena or a checkpoint of
one; `ratingDelta` is what the transaction applied, 0 where nothing is rated.
The repository's answer carries, for each player it paid in this call, what it
paid: a redelivered result pays nobody new, so nobody is pushed twice, and a
worker that dies between the push and the acknowledgement pushes nothing on the
redelivery. Best effort, as every push (03 §7): a client that missed it reads
its inventory as before. A player in no lobby is not found, and that is all.

**Built 2026-10-01** (`MatchResultRepository.ApplyResult.paid`, one `Paid` a
player paid in the call; `MatchResultConsumer.telling`, wired to the lobby push
in `WorkerMain`). Clients get it through `LobbyClient.OnPush`.

**The arena spools to local disk when j-redis is unreachable.** A match result
is the one thing the arena produces that must not be lost, and the arena cannot
block a room thread waiting for a store to come back. The spool is drained by
the housekeeper thread on a timer.

Leaderboards are updated *after* the MySQL commit, not inside the transaction.
They live in j-redis and cannot participate in it. The board write is idempotent
(§7), so it does not need to be, and the entry is acknowledged whether or not it
lands: this pipeline pays players, and holding a payment because an index is
unwell would be the wrong way round.

## 7. The one place exactly-once nearly leaked

`ZINCRBY` is **not** idempotent. A retry after a successful increment but a
failed acknowledgement double-counts a score — and that retry is not an edge
case here, it is the designed behaviour of a queue that commits before it
acknowledges.

Four options were considered:

| Option | Verdict |
|---|---|
| Accept it | No. Leaderboards are the competitive core; a doubled score is visible and disputed. |
| A dedupe set per entry id | Rejected. It adds a key per event and a second thing to expire. |
| `ZADD` an absolute total read back from `player_stat` | Correct, and what this document specified first. Idempotent, but it costs a `SELECT` per player per match to find the total to write. |
| **`ZADD … GT` of the match's own score** | **Chosen and built.** The board's metric is a *best*, and a maximum is idempotent and commutative on its own. The store already holds the previous maximum, so nothing has to be read back. |

So the rule is: **after the MySQL transaction, `ZADD … GT` the score the match
itself reported.** The board is a projection rather than an accumulator, and
the property that makes it safe is arithmetic rather than bookkeeping — there
is no dedupe record that can disagree with its effect, because there is no
dedupe record.

One corollary, easy to get backwards: the board write runs on **every**
delivery, including ones MySQL recognises as duplicates. Gating it on "this
result is new" would make a crash between the commit and the board write lose
that ranking for good, since the redelivery would find the rows already there
and skip. The repeat is the recovery.

A totals board — lifetime kills, say — cannot use this trick and would need the
read-back row of the table above. None exists yet.

## 8. Rebuilding after a store loss

j-redis holds the live leaderboards and has no replica until Phase 4. Every
score on them came from a result MySQL committed first, so they are a
projection, and **built 2026-09-26** is the command that projects them again:
`worker`'s `LeaderboardRebuild`, run as `systemctl start
backend-leaderboard-rebuild` with the worker's settings.

- **All-time** from `player_stat.best_score`, which is a maximum kept in the
  same transaction as the result, read in pages by player id.
- **Day and week** from the matches that ended in each period, the best score per
  player, UTC as the boards count it. Only the periods whose board would still
  exist are written: a board lasts its TTL after its last score, and its expiry
  is set to exactly that.
- **Names** from `player.display_name`: the current name, as the live path
  writes it too, from the name MySQL holds when it applies a result (D-60).

**It is safe to run at any time.** Every write is `ZADD GT`, so a board that was
never lost only gains what it was missing, and nothing on it goes down; expiries
are set with `EXPIREAT … NX`, so a live board keeps its own. That makes it the
repair for results applied while the store could not be written to (the
worker's `unranked` count) as well as for a lost store. The design this section
first described, restoring `leaderboard_snapshot` rows and replaying the tail,
relied on a table that was never built, and it was an admin-only command because
it could overwrite good data with older data. This one cannot.

Tested against the live path itself: results through the real queue and
consumer into one store, a rebuild from MySQL alone into another, every board,
name and expiry equal; and on the live store, nothing lowered, no expiry moved,
the unranked result ranked. Mutation-checked 4 of 4. Rehearsed from the release:
the store's disk wiped, the shipped unit run, 16 of 16 players back.

## 9. Scheduled work

**As built, six timers, each started by `worker/WorkerMain` in every worker**, besides
the result loop's own re-drive every 30 s (§3):

| Job | Class | When | Across the fleet |
|---|---|---|---|
| Ledger reconciliation | `LedgerCheck` | an attempt 5 min after start, then every hour | once a day: the store's lock `job:ledger-check`, `SET NX EX` 23 hours |
| Retention | `Retention` | 1 min after start, then every 24 hours | in every worker, no lock |
| Tournament tick | `TournamentScheduler` | every 5 s | in every worker, no lock: every write conditional |
| Season job | `SeasonKeeper` | every minute | whoever takes `job:season`, `SET NX EX` 10 minutes |
| Replica heartbeat | `ReplicaWatch` | every second | in every worker |
| Replica lag | `ReplicaWatch` | every 5 s | in every worker |

The order of each, the batch sizes and the keys are in the
[worker README](../../backend/worker/README.md#scheduled-jobs), and the flows in
[diagrams/05](../diagrams/05-data-and-worker.md).

**Ledger reconciliation** (built 2026-09-26, `worker/LedgerCheck`) runs once a day
across the fleet: each worker tries hourly to take a 23-hour lock in the store
(`SET NX EX`), and whoever gets it compares every player's coins and gems with the
sums of their ledger rows in each currency. It reads a range of player ids at a
time, each range its own statement and so its own consistent snapshot: a balance
and its ledger row are written in one transaction, so a range read at once sees
both or neither, and no one statement has to read the whole ledger, which is kept
for good and would outgrow the pool's 30 s limit on a statement
([06 §7](06-persistence-mysql.md#7-connection-pooling)). The result goes to the
store, every worker's metrics read it back (`backend_worker_ledger_mismatches`,
`backend_worker_ledger_checked_timestamp_seconds`), and a check that fails hands the
lock back so the next hour retries. The lock needs no fencing token because the job
only reads. Gems are checked with coins since the system pays them and the shop
takes them (plan item 68): a player is a mismatch if either balance differs from its
ledger rows. Cost measured only on development data, as one statement (3 185
players, 698 ledger rows: 76 ms); time it again at production size.

**Retention** (`worker/Retention`), in every worker, a minute after start and then
daily, with no lock. Overlapping runs are harmless: the deletes are idempotent and
take their locks in the same order, which was cheaper to reason about than a lock.
It deletes rows in batches of 1 000 rather than dropping partitions; there are no
partitions ([06 §9](06-persistence-mysql.md#9-growth-and-retention)). Each of its
steps stands on its own: one that fails is logged, the next still runs, and the
run's time is reported either way (`backend_worker_retention_seconds`).

The **tournament tick** (built 2026-09-30, `worker/TournamentScheduler`) runs in
every worker every 5 s, also with no lock: each of its writes is conditional on what
the tick read, a tournament's state and version, a match's state, a prize's ledger
key ([D-41](../architecture/03-decision-log.md#d-41--every-worker-runs-the-tournament-clock-and-a-match-is-claimed-before-its-tickets-are-written),
04 §6). Making a match is the one step with effects outside MySQL: since
[T-37](../defects.md#4-concurrency) every seat's ticket is written first and the
match claimed after, and a worker whose claim another worker won gives its tickets
and its room back. D-41's name for it, a claim before the tickets, is the order it
replaced.

The **season job** (designed 2026-10-03, plan item 71 (a), `worker/SeasonKeeper`)
looks each minute in every worker; whoever takes the store's `job:season` lock
(`SET NX EX`, ten minutes, extended while a close runs) closes a season that has
ended, in three steps each recorded in the season's row and each safe to run again:
its places, its gems, its reset in batches
([D-63](../architecture/03-decision-log.md#d-63--a-season-ends-in-three-steps-each-safe-to-repeat-its-places-its-gems-then-its-reset)).
The lock keeps two workers from doing the same work at once, however long a close
takes; the row's progress, not the lock, is what makes it right, and a worker that
stops part-way leaves the rest to the next.

**The replica heartbeat and lag** (plan item 59, `worker/ReplicaWatch`,
[D-58](../architecture/03-decision-log.md#d-58--a-replica-is-measured-by-what-it-has-applied-a-heartbeat-for-mysql-the-primarys-own-account-for-the-stores)):
every worker stamps `ha_heartbeat` on the MySQL primary once a second, and every
5 s, on a thread of its own, reads each listed host's stamp; a replica's lag is the
primary's stamp less its own ([06 §10](06-persistence-mysql.md#10-backup-and-recovery)).
A replica that hangs delays neither the stamp nor a scrape.

There is no stream trim job (the producer trims the stream as it adds,
`MatchResultStream`) and no leaderboard snapshot (the boards are rebuilt from MySQL
instead, §8). The design, kept for what it decided; its retention by partitions was
never built, and could not have been
([06 §9](06-persistence-mysql.md#9-growth-and-retention)):

`worker` also runs the periodic jobs. Each is a singleton across the fleet,
guarded by a j-redis lock with a fencing token (`J.CAS`), so two instances
never run the same job:

| Job | Period | Purpose |
|---|---|---|
| Leaderboard snapshot | 5 min | The rebuild path above |
| Tournament tick | 5 s | Advance the state machine |
| Ledger reconciliation | nightly | `SUM(delta)` vs `player.coins`; alert on any mismatch |
| Retention | nightly | Drop `match_player` partitions older than 90 days |
| Stream trim | hourly | `XTRIM` to the retention in §2 |

The fencing token matters: a job that pauses for a long GC can wake up
believing it still holds the lock. Every write it makes carries the token, and
a stale token is refused.

## 10. What to measure

| Metric | Why | As built |
|---|---|---|
| Stream lag per consumer group (entries behind the tail) | The single best indicator that something is wrong downstream | `backend_worker_queue_depth`: the group's lag plus the inbox list |
| Pending-entry count and age | Rising age means entries are being read but not acknowledged | `backend_worker_pending`, the count; the age is not exported |
| Dead-letter depth | **Alert on any non-zero value** | `backend_worker_dead_letter_depth`; beside it `backend_worker_deferred_depth` |
| Apply-transaction duration p50/p99 | Feeds the MySQL metrics in [06 §12](06-persistence-mysql.md#12-what-to-measure) | not exported |
| Arena spool size and age | Non-zero means the arena cannot reach j-redis | the arena's `backend_arena_results_spooled_total` and `backend_arena_spool_failures_total`; size and age not exported |
| Redeliveries per 1 000 entries | Healthy is near zero; a rise means crashes or timeouts | `backend_worker_duplicates_total` against `backend_worker_applied_total` |

Every metric a worker exports is listed in the
[worker README](../../backend/worker/README.md#metrics).

## 11. Analytics (designed 2026-09-30, plan item 25)

What [Q-24](../requirements/01-scope-and-nfrs.md#7-open-questions) asks first:
whether players come back. By day, UTC:

| Figure | Counted as |
|---|---|
| active | players with a match or a stay that ended that day, long enough to be paid |
| new players | players whose first such day it was |
| day-1, day-7, day-30 | of that day's new players, those active again 1, 7 and 30 days later; empty until that later day has ended |

**Recorded in the result's transaction**
([D-47](../architecture/03-decision-log.md#d-47--activity-is-recorded-in-the-results-own-transaction-a-row-a-player-a-day-and-counted-when-it-is-read)).
For each player whose part is new (§3), `INSERT IGNORE INTO player_day (day,
player_id)`, the day the match ended; and the update the transaction already
makes to the player sets `first_played_on` to that day if it is earlier than
the one kept, or none is, so a result that arrives late from an earlier day
still makes that day the first. A redelivery changes nothing, as with the rest
of the result. A result too short to be paid, under `RewardRules`' minimum play,
writes no `player_day`: a stay of a few seconds is not a day of play. Nor does it
move stats, achievements, daily goals or pass points: it keeps its row and a rated
match's rating, nothing else (`MatchResultRepository.COUNTED_FROM_SECONDS`, defect
[D-45](../defects.md#3-data-and-the-result-pipeline)). `player_day` is kept 90 days, as match history is, and deleted
by the daily retention run.

**Counted when read**, by `platform`'s admin API (04 §10): `GET
/admin/stats?days=N`, N from 1 to 60, 14 if absent, newest day first, today's
figures so far included. Sixty, since a day's day-30 figure is known only once
thirty more days have ended: a day-30 figure is first seen 31 days back.

```json
{"days":[{"day":"2026-09-30","active":412,"newPlayers":37,"d1":null,"d7":null,"d30":null}, …]}
```

Active is a range of `player_day`'s key; new players an index range on
`player.first_played_on`; day-N the new players of that day found in
`player_day` N days on. A day-N figure is `null` while its day has not ended,
and 0 once it has and nobody came back.

V16 filled both from the match history there was, 90 days, so the figures were
right from the first read; the baseline that replaced the steps
([06 §8](06-persistence-mysql.md#8-migrations)) starts on an empty database, with
nothing to fill.

Not counted: a visit to the lobby without a match; anything by feature (the
next slice, the same counts split by what the players used).

**Built 2026-09-30** (`persistence/StatsRepository`, `MatchResultRepository`'s
`recordDay` and the progression update, V16, `worker/Retention`, `platform`'s
`AdminServer`), and drilled from the release (`stats`). The statement added to
every result's transaction costs less than this machine's noise: a result
applied alone took 35.7 to 55.7 ms before and 37.4 to 45.8 ms after, four runs
of each, alternated.

### By feature (designed 2026-09-30, plan item 27)

[Q-26](../requirements/01-scope-and-nfrs.md#7-open-questions): each day's new
players split by what they did **on that first day**, UTC, for six features;
for each, how many did, and how many of those came back 1, 7 and 30 days later,
to set against the day's figures for all of them (above).

| Feature | Done on the first day when |
|---|---|
| `queued` | a match of theirs in any mode but the public arena ended that day (`match_player`, `matches.mode` not 0) |
| `bought` | they bought something: a ledger row of reason 1 that day |
| `boosted` | a boost of theirs started that day (`boost.started_at`) |
| `tournament` | they registered for a tournament that day, alone (`tournament_entry`) or on a team's roster (`tournament_roster`, the team's `registered_at`) |
| `friend` | a friendship of theirs began that day (`friend.since`) |
| `team` | they joined a team that day (`team_member.joined_at`) |

Counted when read, as the rest (D-47): for each feature, the day's new players
(`first_played_on`, its index) for whom a row exists that day, and those of
them found in `player_day` N days on; four grouped queries a feature, whatever
the number of days. Each lookup goes by a key that starts with the player: for the
tournaments' two tables, `tournament_entry` and `tournament_roster`, the index
InnoDB made for each one's foreign key to `player`
([06 §3](06-persistence-mysql.md#3-schema)). Not counted: a
team left or a friend removed the same day, their rows being deleted; equipment
worn, whose time is not kept. Use is not cause (Q-26).

**Built 2026-09-30** (`StatsRepository.byFeature`, `GET /admin/stats/features`),
and drilled from the release (`stats`): two new guests who made friends on their
first day counted under `friend`.

### The funnel (designed 2026-10-04, plan item 76 (c))

With the revenue stream built (plan item 75), the question beside "do players
come back" is how far they go: of the accounts made on a day, how many played,
came back, progressed, bought, and paid. By the day the account was made, UTC,
each step counted **by now**, not by a deadline, and none required of the next:
a player may pay before reaching level 5.

| Step | Counted as |
|---|---|
| `registered` | accounts made that day, guests included |
| `guests` | of them, still guests, never upgraded |
| `played` | with a first day played (`player.first_played_on`) |
| `returned` | active again on another day within the 7 after their first (`player_day`) |
| `level5` | at level 5 or above |
| `rated` | with a rated match in any mode (the players' rated counts) |
| `bought` | with a purchase in the shop, coins or gems (a ledger row of reason 1) |
| `paid` | with an order paid for money (`payment_order` paid, or refunded since) |

Counted when read, by `platform`'s admin API: `GET /admin/stats/funnel?days=N`,
N from 1 to 60, 14 if absent, newest day first, today so far included. One
grouped query over the accounts made in the window, by an index on
`account.created_at` (V34), each step a lookup by the player's own key.

### Guests, measured (designed 2026-10-04, plan item 76 (c))

Q-22 kept guests never upgraded, and left their deletion until there was "a
measure of how many there are". `GET /admin/stats/guests` is that measure:

```json
{"players": 120000, "guests": 41000, "inactive": 9000}
```

`guests` are the accounts still guests; `inactive`, those of them made more than
90 days ago with no day played in the last 90 (`player_day`, kept that long)
and no login since. **Deleting them stays deferred**
([D-72](../architecture/03-decision-log.md#d-72--the-tables-growth-is-watched-against-measured-triggers-and-a-restore-not-the-purge-is-what-binds)'s
way, a trigger measured): a guest touches every table a player is in, and a
deleted one is a device whose player comes back to nothing. Trigger: `inactive`
past a million, or past a quarter of `players`.
