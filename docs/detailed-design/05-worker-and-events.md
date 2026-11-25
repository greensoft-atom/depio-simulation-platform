# 05 — Worker and event streams

This page covers everything that happens *after* a match: results applied, rewards granted, leaderboards updated,
tournaments advanced, and activity recorded for analytics. It is for engineers who work on the `worker` process,
on the result stream between the arenas and the workers, or on the jobs the workers run. It covers the move from a
list to a stream (first), why a stream (§1), the streams themselves (§2), the consumer loop (§3), failures (§4),
ordering (§5), the whole result pipeline (§6), the leaderboards (§7, §8), the scheduled jobs (§9), the metrics
(§10) and the analytics (§11). Where the code differs from the design, the text says **as built**.

The `worker` process reads durable event streams from j-redis (the backend's own data store) and applies them to
MySQL. It is the only component where "exactly once" is a requirement rather than a nicety
([NFR-7](../requirements/01-scope-and-nfrs.md#4-non-functional-requirements)). Terms such as *stay*, *spool*,
*dead letter*, *deferred*, *re-drive*, *pending*, *take over* and *result inbox* are defined in the
[glossary](../glossary.md#5-results-and-reward).

**The result queue has been a stream since 2026-09-29** (plan item 10, "The move onto streams" below), once j-redis
2.1.0 had streams:

- arenas add each result to the stream `s:match-result`;
- workers read it as the consumer group `rewards`;
- the code is `handoff/MatchResultStream`, `arena/MatchResultPublisher` and `worker/MatchResultConsumer`;
- the list `q:match-result` stays, as an inbox that the workers drain into the stream.

**A list queue came first** (`handoff/MatchResultQueue`, `arena/MatchResultPublisher`). It was verified end to end
as separate processes: an arena, a j-redis, a worker and MySQL, with ten bots playing. Four matches ended and four
were applied, forty `match_player` rows were written, and the ledger re-derived the coin balances exactly. What the
list lacked, and the stream gives:

| Stream property | The list | The stream |
|---|---|---|
| Several consumer groups | One consumer | One group; analytics and moderation can each read the same entries as a group of their own, once they exist |
| Replay from an offset | None: a reward bug would have to be repaired in SQL | Within the 24 hours kept; beyond that, from MySQL ([Q-9](../requirements/01-scope-and-nfrs.md#7-open-questions)) |
| A pending list per group | One processing list per worker, `q:match-result:processing:{workerId}`. A retired worker's list needed someone to know that the worker was dead. | Pending entries per consumer; any worker takes over those idle for a minute |
| Retention by age | None; the queue drains | 24 hours, read or not, unless set (`BACKEND_RESULT_RETENTION_HOURS`, §2) |

The stream's key starts with `s:` and the lists' keys keep `q:`, so the change of meaning shows in the name.

### The move onto streams (designed 2026-09-29, plan item 10)

j-redis 2.1.0 has streams ([j-redis 15](../../j-redis-service/docs/15-streams.md)), so the result queue moved onto
one ([D-33](../architecture/03-decision-log.md#d-33--the-result-queue-is-a-stream-read-by-one-group-the-list-stays-an-inbox)):

| | Before | After |
|---|---|---|
| The queue | the list `q:match-result`, `LPUSH` | the stream `s:match-result`, `XADD * e <envelope>`, trimmed `MINID ~` to 24 hours ([Q-9](../requirements/01-scope-and-nfrs.md#7-open-questions)) |
| Who reads it | each worker claims entries onto its own processing list | the group `rewards`, one consumer per worker, named by the worker's id |
| Done | `LREM` from the processing list, after the commit | `XACK`, after the commit |
| A worker's own unfinished entries | its processing list, re-driven every 30 s | its pending entries, read back as its history (`XREADGROUP … 0`), re-driven every 30 s |
| A retired worker's entries | stranded until a worker with its id starts | claimed by any worker once idle for a minute (`XAUTOCLAIM`, 60 s) |
| Unreadable, refused | the list `q:match-result:dead` | the same list, then `XACK` |
| From a newer producer | the list `q:match-result:deferred` | the same list, then `XACK`; returned through the inbox when a worker starts |

**The list stays, as an inbox.** At start and at every re-drive, every worker moves whatever `q:match-result` holds
into the stream. Each entry is first claimed onto the worker's processing list, then added to the stream, then
removed from the list. This keeps three things working:

- During a rolling deploy, arenas of the previous release still push to the list. They lose nothing, whichever of
  the two is upgraded first.
- A processing list left by the list-reading worker is drained the same way.
- The runbook's way of putting dead entries back on the queue still works.

An entry moved twice, after a crash between the add and the remove, is applied once, because the apply is
idempotent (§3).

**What a trim can take.** The stream is trimmed to its retention, 24 hours unless set (§2), whether or not an entry
was read. So an entry that stays pending longer than that, because its worker has failed on it for a day, is gone
from the stream. The next re-drive's take-over (`XAUTOCLAIM`) finds it gone, whatever its idle time, and the store
drops it from the pending list. That is a lost result. It is logged as an error with its ID and counted
(`backend_worker_trimmed_unapplied_total`); it is never dropped silently.

**Pushed, then acknowledged, as two commands**, as the list did it (§3). They are not in one transaction: `EXEC`
runs every command even when one is refused, so if the store refused the push, the `XACK` would still run, and the
entry would be nowhere. With two commands, a failed push leaves the entry pending. A crash between the two commands
leaves it both on the list and pending; when it is delivered again, it is set aside again, which duplicates the
evidence and loses nothing.

**The group is made again when it is gone.** A store restored empty, or the key deleted, takes the group with the
stream, and every read would then be refused with `NOGROUP` for ever. So each re-drive makes the group if it is
missing (`XGROUP CREATE … 0 MKSTREAM`, ignoring a `BUSYGROUP` refusal), and so does a read refused with `NOGROUP`,
at once. The group starts from `0`, the start of the stream: what was added before the group existed is read, not
skipped. (This was found while building it, [defect D-27](../defects.md#3-data-and-the-result-pipeline): when the
group was made once at start-up, a lost group stopped the pipeline until a restart.)

**Measured as a group.** `backend_worker_queue_depth` is now the group's lag (entries not yet delivered, from
`XINFO GROUPS`) plus the inbox's length. A new gauge, `backend_worker_pending`, counts entries delivered and not
yet acknowledged.

**The order of the upgrade.** The events store runs j-redis 2.1.0 first: an arena or worker of this release is
refused at its first stream command by a 2.0 store. Then arenas and workers are upgraded in either order.

**The parts, and how each was checked:**

| Part | What | Built, and checked |
|---|---|---|
| (a) | the backend on j-redis 2.1.0 | built 2026-09-29 |
| (b) | the worker on the stream: the group, reads, acknowledgements, re-drives, reclaiming, the inbox, the lists, the metrics | built 2026-09-29; 22 mutants, 21 caught (below) |
| (c) | the arena publishing to the stream, trimmed | built 2026-09-29; mutation-checked 5 of 5 |
| (d) | operations: the runbook and a live drill, with a worker killed while entries are in flight | drilled 2026-09-29 (below) |

**(b)'s tests**, besides the list's cases: a retired worker's entry taken over once idle, and not before; the inbox
and a list-reading worker's processing list drained into the stream; an entry trimmed while pending counted as lost
once, whether its own or a retired worker's, idle or not; and the group lost with its stream and made again, both
by a re-drive and by a read. Of 22 mutants, 21 were caught. The one left is a guard for a trim that lands between a
re-drive's take-over and its history read, which no test can time. Without the guard, that entry throws once, and
the next pass counts it.

**(c)'s design and tests:** the arena adds each result with `MINID ~` set to a day before its own clock, so every
add trims what is older. Tested: an entry a day and a minute old is trimmed by the next add; one a minute short of
a day is kept; and the spool's cases (the store down, a restart, a close, the order) are read back from the stream.

**(d), the drill** (`TAKEOVER=1 client/headless-drill.sh <scenario>`, from a release):

1. The worker is stopped before the scenario, so its results wait in the stream.
2. The results are read as that worker (`XREADGROUP … >` from the command-line client). This leaves them pending
   for it, exactly as a crash in the middle of an apply would.
3. That worker is killed with `-9`.
4. A second worker with a different name, as a replacement machine would have, must take the results over once
   they are idle for a minute, and apply each once: the group's pending count back to 0, and every result in the
   stream in MySQL.

**Run 2026-09-29 from the release:** a stay and a duel pending on the killed worker were taken over by the second
worker in 66 s and applied once each (each duel player got 50 coins and one ledger row; `applied 2, duplicates 0`).
Again with `play` alone: one result, in 68 s. The store's restore drill, run on that store, found the stream and its
group the same in the copy.

## 1. Why a stream and not a queue

**A list queue (`LPUSH`/`BRPOPLPUSH`) delivers each entry to exactly one consumer and then forgets it.** That is
fine for one consumer and fatal for several. As soon as analytics wants to see match results *and* the reward
pipeline does, one of them has to re-publish for the other. A crash between the two then loses an event, with no
way to notice.

Streams give what is actually needed
([D-6](../architecture/03-decision-log.md#d-6--no-custom-message-broker-use-j-redis-streams)):

| Property | Why it matters here |
|---|---|
| Multiple consumer groups | Rewards, analytics and moderation read the same events independently |
| A cursor per group, plus a pending list | A crash in the middle of processing is recoverable, because unacknowledged entries can still be claimed |
| Replay from an offset | A bug in the reward calculation can be fixed and the affected range processed again |
| Retention by count or age | Bounded memory without a separate cleanup job |

**The event rate is modest**, about 170 match-end events a second at 50 000 concurrent players. So the choice is
about semantics, not throughput.

**Measured on the development machine** (2026-09-30, plan items 23 and 26):

- One worker applies about 10 results a second. Each result waits for its commit's synchronous writes.
- More workers multiply that, because commits made at the same time share their writes. Draining a backlog of 600
  results, over two rounds while this machine's load moved: one worker managed 10 to 16 a second, two 18 to 27,
  and four 25 to 36.
- The transaction alone, from 1, 2, 4 and 8 threads at once: 18, 27–34, 66–69 and 104 a second.

So the rate is a matter of how many workers run, not a ceiling. At this machine's rate, 170 a second takes several
processes, or several threads in one. Q-3's machines, whose disks differ, set the number, measured with
`tools/ApplyBenchmark` ([backend README](../../backend/README.md#the-apply-benchmark)). A worker spends about as
long again outside its transaction, on the boosts, the boards and the stream, as inside it. That is where to look
first if it must go faster.

## 2. Streams

| Stream | Producer | Consumers | Retention | Loss tolerance |
|---|---|---|---|---|
| `s:match-result` | arena, and a worker moving the inbox | `rewards`, `analytics` | 24 hours unless set, below (a week would not fit in memory: [D-26](../defects.md#3-data-and-the-result-pipeline)) | **None.** Progression and currency depend on it. |
| `s:match-event` | arena | `analytics`, `moderation` | 24 hours | Tolerable. Kill feeds and captures. |
| `s:platform` | platform | `analytics` | 7 days | Low. Purchases, team changes, tournament transitions. |
| `s:audit` | platform | `audit` | 90 days | **None.** Admin actions and bans. |

**As built, only `s:match-result` exists, and only the group `rewards` reads it.**

- The `analytics` group was not made (§6, D-47).
- `s:match-event` and `s:platform` are not built.
- `s:audit` will not be built (D-50): admin actions are recorded in MySQL's `admin_audit`, in the change's own
  transaction ([06 §3](06-persistence-mysql.md)).

**The seven days are not affordable** (defect [D-26](../defects.md#3-data-and-the-result-pipeline)). j-redis keeps
a stream in memory, and a week of `s:match-result` is about 54 GB at the design's load. 24 hours is recommended;
replay beyond that reads MySQL ([Q-9](../requirements/01-scope-and-nfrs.md#7-open-questions)).

**The hours are a setting**
([D-83](../architecture/03-decision-log.md#d-83--the-result-streams-retention-is-a-setting-and-the-events-store-is-sized-by-a-table),
designed 2026-10-23, plan item 92):

| Question | Answer |
|---|---|
| Setting | `BACKEND_RESULT_RETENTION_HOURS`: whole hours, 1 to 168, default 24 |
| Read by | every process that adds to `s:match-result`: the arenas and the workers. Each add trims everything older, so the shortest value wins. |
| What it bounds | the store's memory, about 160 MB a day for every 1 000 players; and how long the workers or MySQL may be down before a result is trimmed unapplied (`backend_worker_trimmed_unapplied_total`) |
| A value outside the range | refuses the start (exit 2) |
| Logged | each process logs the value it uses at start |

**The design partitions by player id** where ordering matters. Then a single player's events are processed in
order, even though the stream as a whole is not globally ordered. Two matches that end at the same time for
different players may be applied in either order; two events for the *same* player may not.

**As built, nothing is partitioned.** There is one group, `rewards`, and any worker takes any entry. So two results
of one player may be applied at the same time by two workers, or in either order. What a result does to a player is
written so that the order does not matter:

- each result locks the player's row before it reads anything it writes;
- ratings move from the values read under that lock;
- `first_played_on` is the earliest day, whichever result arrives first;
- totals, goal progress and pass points are added by the statement that writes them. They are never read with a
  plain read and written back ([06 §4](06-persistence-mysql.md#4-the-three-transactions-that-matter)).

### Envelope

Every entry carries the same envelope, so a consumer can route and deduplicate an entry without parsing the
payload:

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

**As built, there is no `key`**: one group reads the stream and nothing is partitioned (§2, §5). So the envelope is
`id, type, v, ts, payload` (`handoff/MatchResultCodec`). The key belongs to the design of several streams (§5). The
`id` is the match's own ULID, the arena's `matchUid`.

`id` is the idempotency key that reaches MySQL. `v` exists because, during a rolling deploy, a consumer will one day
meet an entry written by a producer of another release. An unknown version must be a clear rejection, not a
misparse. As built, a newer version is deferred for a newer worker; an older or missing version, a wrong `type`, or
an entry without a result is unreadable, and is dead-lettered as evidence (§3).

## 3. The consumer loop

The design:

```
XREADGROUP GROUP rewards worker-1 COUNT 64 BLOCK 2000 STREAMS s:match-result >
  for each entry:
      apply in a MySQL transaction, idempotent on the natural key   (06 §4)
      XACK s:match-result rewards <entry-id>
```

**As built**, a worker reads one entry at a time (`COUNT 1`, `BLOCK 2000`), applies it and acknowledges it. Its
consumer name is `worker-<id>`, from its unit (`worker-c1`). Every 30 s, and at start-up
(`MatchResultConsumer.recoverAbandoned`), it does three things:

1. drains the inbox into the stream;
2. takes over entries idle for a minute (`XAUTOCLAIM`, 64 at a time);
3. re-drives its own pending entries, read back with `XREADGROUP … 0`.

(Until 2026-09-29 this was a list: `BLMOVE` onto the worker's own processing list, `LREM` when done.) The batch of
64 in the design is not built.

These rules make the loop safe, and each one exists because of a specific failure:

**Acknowledge after committing, never before.** A crash between the commit and the acknowledgement delivers the
entry again. The transaction is idempotent, so the redelivery changes nothing. A crash between an acknowledgement
and a commit would lose the event silently, which is the failure nobody detects.

**Every entry ends in exactly one place:**

| What happened | Where the entry goes |
|---|---|
| Applied, or already applied | acknowledged |
| Unreadable, or a value no database could store | `q:match-result:dead`, kept as evidence, and acknowledged |
| Written by a newer producer than this build reads | `q:match-result:deferred`, and acknowledged; returned through the inbox whenever a worker starts |
| The database is failing | left pending for this worker, and re-driven |
| Trimmed away while pending, after a day of failing | counted as lost (`backend_worker_trimmed_unapplied_total`) and logged by the next take-over, which drops it from the pending list |

**The "database is failing" row is the one that was broken.** A failed transaction left the entry on the processing
list, and the only thing that ever read that list ran once, at start-up. So a one-minute MySQL blip parked every
result there until somebody restarted the worker. Now the pending entries are re-driven every 30 s, and at once
after the database recovers. And the worker **stops claiming new work while the database is failing**: every claim
would only move an entry from the queue to its own pending list, at the cost of a connection timeout. While the
database fails, the worker re-drives its pending entries every 5 s: the entry already parked is the probe, and new
entries wait in the stream until it goes through. (This is true since 2026-09-26. Until then the
worker claimed one more entry after every pause, and a long outage moved the whole queue onto one worker's list:
defect [D-20](../defects.md#3-data-and-the-result-pipeline).)

**Transient versus permanent.**

- A lost connection, a lock timeout, or a deadlock that outlasted its retries is *transient*: it is retried.
- A value out of range, a broken foreign key or a violated `CHECK` is *permanent*: it is dead-lettered, because
  retrying cannot change it.
- The worker checks MySQL's error code as well as the SQLSTATE class. A `CHECK` violation is error 3819 with
  SQLSTATE `HY000`, the generic "something went wrong", and a rule based on the class alone would retry it for ever.
- Anything not recognised is treated as transient. The safe mistake is to retry something hopeless, not to discard
  something recoverable.

**The loop does not die.** It used to call its handler unguarded. An acknowledgement that failed on a store hiccup
then threw out of `run()` and ended the process. Now an unexpected failure leaves that one entry for the next
re-drive, and the worker carries on.

*Tested:* a redelivery applied once; a crash in the middle of an apply recovered; unreadable entries dead-lettered;
a database that fails and recovers caught up **by the same running worker**; an unexpected failure on one entry
neither stopping the worker nor losing the entry; a starting worker not touching another worker's entry in flight;
a newer version deferred; each failure class classified. Each new test was watched failing before its fix, and
removing each fix fails exactly its test.

**Idempotency lives in MySQL, not in the consumer.** A plain insert into `match_player (match_id, player_id)` is
both the write and the duplicate check, and only a duplicate-key error means "already applied"
([06 §4](06-persistence-mysql.md#4-the-three-transactions-that-matter)). A "have I seen this id" cache in the
consumer would be a second source of truth, which drifts on restart.

**Batch reads, single-entry transactions** (the design). `COUNT 64` spreads the cost of a round trip, but each
entry commits on its own. Batching commits would let one bad entry spoil 63 good ones.

**Claim abandoned work on start-up.** `XAUTOCLAIM` with a 60-second idle threshold picks up entries that a dead
consumer never acknowledged. Without it, a worker that dies in the middle of a batch leaves its entries pending for
ever. As built, this runs at every re-drive, not only at start-up, because a retired worker never starts again.

## 4. Failure handling

The design:

```
delivery 1..3   retry with backoff 1 s, 5 s, 30 s
delivery 4      move to s:dlq:{stream} with the error and stack, then XACK
```

(As built, the dead letters go to the list `q:match-result:dead`, §3.)

The distinction that matters:

| Failure | Response |
|---|---|
| Transient — MySQL unreachable, deadlock, lock timeout | Retry. Do not count it toward the dead-letter limit: the entry is fine, the world is not. |
| Permanent — unknown schema version, malformed payload, referential violation | Dead-letter at once. Retrying a malformed entry three times only delays the alert. |

**As built, an unexpected failure is retried for as long as it fails.** An unexpected failure is an exception that
is neither a database error nor a refusal the worker recognises. It is not dead-lettered after three deliveries:
it is retried at every re-drive, every 30 s. The reason: an unexplained failure may be a bug that the next deploy
fixes, and dead-lettering on a guess sets a payment aside. The price is that such an entry never shows in the
dead-letter depth. It shows only as `backend_worker_failed_total` rising steadily, so that is alerted on too
([operations/01](../operations/01-deploy.md#7-installing-a-machine)).

**A non-empty dead-letter list is an alert, not a metric.** Each entry there is a player whose reward did not
arrive. The runbook's entry is: fix the cause, then replay the range. That is safe precisely because the
transactions are idempotent.

## 5. Ordering, and where it is not needed

**The design:** consumers run one thread per hash of the partition key, so events for one player are serialised
without a global lock. `worker` runs two instances in the same consumer group, and j-redis distributes entries
between them.

**As built**, each worker is one consumer that reads one entry at a time, and there is no partition key (§2). A
player's results are serialised by the lock on the player's row, not by the order they are read in. Two workers run
on the site (`c1` and `c2`, on machine C, or on the launch's one machine, D-93).

Most things do not need ordering at all, and pretending otherwise costs throughput for nothing:

| Needs ordering | Does not |
|---|---|
| Currency changes for one player | Analytics rows |
| Tournament round transitions | Kill-feed events |
| Team membership changes | Leaderboard writes (a maximum is commutative) |

## 6. The result pipeline end to end

The design (as built, there is no `key` in the envelope and no `analytics` group):

```
arena              match ends
                   XADD s:match-result * envelope{type=match.result, key=player:N}
                   always spooled to local disk first; pushed from there
                                 ──────────────────────────────────────────
worker/rewards     XREADGROUP → MySQL transaction (06 §4):
                       matches, match_player, player, player_stat, ledger,
                       daily_goal, season_pass, inventory_item, player_day,
                       match_team and team for a team match
                   → push "evt.rewards" via the gateway to each player newly paid (best effort, 03 §7)
                   → ZADD ... GT leaderboards in j-redis, every delivery
                   → XACK
                                 ──────────────────────────────────────────
worker/analytics   XREADGROUP → append to the analytics table → XACK
```

The `analytics` group was not made: activity is recorded in the rewards transaction instead
([§11](#11-analytics-designed-2026-09-30-plan-item-25), D-47).

### What a match paid, pushed (designed 2026-10-01, plan item 43)

This follows [Q-39](../requirements/01-scope-and-nfrs.md#7-open-questions)'s recommendation. After a result's
transaction commits, and before its acknowledgement, each player newly paid by the result is pushed
**`evt.rewards`**:

```json
{"matchUid": "…", "mode": "duel", "placement": 1, "coins": 50, "xp": 120, "ratingDelta": 0, "gems": 0,
 "achievements": [], "goals": [], "goalCoins": 0,
 "pass": {"points": 10, "tier": 0, "coins": 0, "gems": 0, "items": []}}
```

| Field | What it holds | Since |
|---|---|---|
| `mode` | the mode's key; `ffa` for a stay in the public arena, or a checkpoint of one | |
| `coins` | the match's own coins | |
| `ratingDelta` | what the transaction applied; 0 where nothing is rated | |
| `gems` | what the result paid in level milestones and achievements, 0 in nearly every result (04 §8, D-61, D-64); plus the gems of the day's three goals together | |
| `achievements` | each achievement the result reached, for the client to show | 2026-10-03, plan item 71 (b) |
| `goals`, `goalCoins` | each daily goal the result met, and their coins, apart from the match's own `coins` | 2026-10-04, plan item 74, D-66 |
| `pass` | the season pass's own: the points the result earned, the tier after, and the coins, gems and items of the tiers it reached. These are in neither `coins` nor `gems`. | 2026-10-04, plan item 75 (b), D-69 |

**Nobody is pushed twice.** The repository's answer carries, for each player it paid in this call, what it paid.
A redelivered result pays nobody new, so nobody is pushed twice. A worker that dies between the push and the
acknowledgement pushes nothing on the redelivery. The push is best effort, as every push is (03 §7): a client that
missed it reads its inventory as before. A player in no lobby is not found, and that is all.

**Built 2026-10-01** (`MatchResultRepository.ApplyResult.paid`, one `Paid` for each player paid in the call;
`MatchResultConsumer.telling`, wired to the lobby push in `WorkerMain`). Clients get it through `LobbyClient.OnPush`.

**The arena spools every result to local disk first.** A match result is the one thing the arena produces that
must not be lost, and a room thread cannot wait for a store. So:

1. The room hands the outcome to `MatchResultPublisher`, whose own thread is `match-result-publisher`.
2. That thread writes everything waiting to the spool directory before it touches the network
   (`/var/lib/backend/arena-<name>/spool` on the site).
3. It pushes each result from there, and deletes each file once the stream has the result.
4. A failing push is tried again after 2 s. New results still reach the disk in the meantime.

When the events store has a replica (two addresses named), the publisher waits up to 100 ms for the replica to have
each result before it deletes the file; one not replicated in time is counted
(`backend_arena_results_unreplicated_total`) and the file goes anyway (Q-11).

**Leaderboards are updated *after* the MySQL commit, not inside the transaction.** They live in j-redis and cannot
take part in it. The board write is idempotent (§7), so it does not need to. The entry is acknowledged whether or
not the board write lands: this pipeline pays players, and holding a payment because an index is unwell would be
the wrong way round.

## 7. The one place exactly-once nearly leaked

**`ZINCRBY` is not idempotent.** A retry after a successful increment but a failed acknowledgement counts a score
twice. And that retry is not an edge case here: it is the designed behaviour of a queue that commits before it
acknowledges.

Four options were considered:

| Option | Verdict |
|---|---|
| Accept it | No. Leaderboards are the competitive core; a doubled score is visible and disputed. |
| A dedupe set per entry id | Rejected. It adds a key per event, and a second thing to expire. |
| `ZADD` an absolute total read back from `player_stat` | Correct, and what this document specified first. Idempotent, but it costs a `SELECT` per player per match to find the total to write. |
| **`ZADD … GT` of the match's own score** | **Chosen and built.** The board's metric is a *best*, and a maximum is idempotent and commutative on its own. The store already holds the previous maximum, so nothing has to be read back. |

So the rule is: **after the MySQL transaction, `ZADD … GT` the score the match itself reported.** The board is a
projection, not an accumulator. What makes it safe is arithmetic, not bookkeeping: no dedupe record can disagree
with its effect, because there is no dedupe record.

**One consequence is easy to get backwards: the board write runs on every delivery**, including deliveries that
MySQL recognises as duplicates. If it ran only when "this result is new", a crash between the commit and the board
write would lose that ranking for good: the redelivery would find the rows already there and skip it. The repeat
is the recovery.

A totals board, such as lifetime kills, cannot use this trick. It would need the read-back row of the table above.
None exists yet.

## 8. Rebuilding after a store loss

**The live leaderboards are on the session store, and MySQL can project them again.** The store's replica
(j-redis 2.2, promoted by a script) covers a lost machine. It does not cover a store restored empty or a key
deleted. Every score on the boards came from a result that MySQL committed first, so the boards are a projection.
**Built 2026-09-26**: `worker`'s `LeaderboardRebuild` projects them again. It runs as
`systemctl start backend-leaderboard-rebuild`, with the worker's settings.

| Board | Rebuilt from |
|---|---|
| All-time | `player_stat.best_score`, a maximum kept in the same transaction as the result, read in pages by player id |
| Day and week | the matches that ended in each period, the best score per player, in UTC as the boards count it. Only the periods whose board would still exist are written: a board lasts its TTL after its last score, and its expiry is set to exactly that. |
| Names | `player.display_name`: the current name, as the live path also writes it, from the name MySQL holds when it applies a result (D-60) |

**It is safe to run at any time.** Every write is `ZADD GT`, so a board that was never lost only gains what it was
missing, and nothing on it goes down. Expiries are set with `EXPIREAT … NX`, so a live board keeps its own. That
makes the rebuild the repair for results applied while the store could not be written to (the worker's `unranked`
count, `backend_worker_unranked_total`), as well as for a lost store.

The design this section first described restored `leaderboard_snapshot` rows and replayed the tail. It relied on a
table that was never built, and it was an admin-only command, because it could overwrite good data with older
data. The rebuild cannot.

*Tested* against the live path itself: results through the real queue and consumer into one store, and a rebuild
from MySQL alone into another, gave every board, name and expiry equal. On the live store: nothing lowered, no
expiry moved, and the unranked result ranked. Mutation-checked 4 of 4. Rehearsed from the release: the store's
disk wiped, the shipped unit run, and 16 of 16 players back.

## 9. Scheduled work

**As built, every worker starts six timers in `worker/WorkerMain`**, besides the result loop's own re-drive every
30 s (§3):

| Job | Class | When | Across the fleet |
|---|---|---|---|
| Ledger reconciliation | `LedgerCheck` | an attempt 5 min after start, then every hour | once a day: the store's lock `job:ledger-check`, `SET NX EX` 23 hours |
| Retention | `Retention` | 1 min after start, then every 24 hours | in every worker, no lock |
| Tournament tick | `TournamentScheduler` | every 5 s | in every worker, no lock: every write is conditional |
| Season job | `SeasonKeeper` | every minute | whoever takes `job:season`, `SET NX EX` 10 minutes |
| Replica heartbeat | `ReplicaWatch` | every second | in every worker |
| Replica lag | `ReplicaWatch` | every 5 s | in every worker |

The order of each job's steps, the batch sizes and the keys are in the
[worker README](../../backend/worker/README.md#scheduled-jobs), and the flows are drawn in
[diagrams/05](../diagrams/05-data-and-worker.md).

**Ledger reconciliation** (built 2026-09-26, `worker/LedgerCheck`) runs once a day across the fleet.

- Each worker tries every hour to take a 23-hour lock in the store (`SET NX EX`). Whoever gets it compares every
  player's coins and gems with the sums of their ledger rows in each currency.
- It reads a range of player ids at a time, and each range is its own statement, with its own consistent snapshot.
  A balance and its ledger row are written in one transaction, so a range read at once sees both or neither. And no
  single statement has to read the whole ledger, which is kept for good and would outgrow the pool's 30 s limit on
  a statement ([06 §7](06-persistence-mysql.md#7-connection-pooling)).
- The result goes to the store, and every worker's metrics read it back (`backend_worker_ledger_mismatches`,
  `backend_worker_ledger_checked_timestamp_seconds`). A check that fails hands the lock back, so the next hour
  retries.
- The lock needs no fencing token, because the job only reads.
- Gems are checked with coins, since the system pays them and the shop takes them (plan item 68). A player is a
  mismatch if either balance differs from its ledger rows.
- The cost was measured only on development data, as one statement: 3 185 players and 698 ledger rows took 76 ms.
  Time it again at production size.

**Retention** (`worker/Retention`) runs in every worker, a minute after start and then daily, with no lock.
Overlapping runs are harmless: the deletes are idempotent and take their locks in the same order, which was easier
to reason about than a lock. It deletes rows in batches of 1 000 rather than dropping partitions; there are no
partitions ([06 §9](06-persistence-mysql.md#9-growth-and-retention)). Each step stands on its own: a step that
fails is logged, the next one still runs, and the run's time is reported either way
(`backend_worker_retention_seconds`). Its last step erases guests idle for 180 days (§11, D-87).

**The tournament tick** (built 2026-09-30, `worker/TournamentScheduler`) runs in every worker every 5 s, also with
no lock. Each of its writes is conditional on what the tick read: a tournament's state and version, a match's
state, a prize's ledger key
([D-41](../architecture/03-decision-log.md#d-41--every-worker-runs-the-tournament-clock-and-a-match-is-claimed-before-its-tickets-are-written),
04 §6). Making a match is the one step with effects outside MySQL. Since [T-37](../defects.md#4-concurrency), every
seat's ticket is written first, and the match is claimed after. A worker whose claim another worker won gives its
tickets and its room back. D-41's title, "a match is claimed before its tickets are written", describes the order
that T-37 replaced.

**The season job** (designed 2026-10-03, plan item 71 (a), `worker/SeasonKeeper`) looks each minute in every worker.
Whoever takes the store's `job:season` lock (`SET NX EX`, ten minutes, extended while a close runs) closes a season
that has ended. The close has three steps, each recorded in the season's row and each safe to run again: its places,
its gems, and its reset in batches
([D-63](../architecture/03-decision-log.md#d-63--a-season-ends-in-three-steps-each-safe-to-repeat-its-places-its-gems-then-its-reset)).
The lock keeps two workers from doing the same work at once, however long a close takes. The row's progress, not the
lock, is what makes the close correct: a worker that stops part-way leaves the rest to the next.

**The replica heartbeat and lag** (plan item 59, `worker/ReplicaWatch`,
[D-58](../architecture/03-decision-log.md#d-58--a-replica-is-measured-by-what-it-has-applied-a-heartbeat-for-mysql-the-primarys-own-account-for-the-stores)):
every worker stamps `ha_heartbeat` on the MySQL primary once a second. Every 5 s, on a thread of its own, it reads
each listed host's stamp. A replica's lag is the primary's stamp minus the replica's own
([06 §10](06-persistence-mysql.md#10-backup-and-recovery)). A replica that hangs delays neither the stamp nor a
scrape.

**What is not built:** there is no stream trim job (the producer trims the stream as it adds, in
`MatchResultStream`), and no leaderboard snapshot (the boards are rebuilt from MySQL instead, §8).

**The design**, kept for what it decided. Its retention by partitions was never built, and could not have been
([06 §9](06-persistence-mysql.md#9-growth-and-retention)). In it, `worker` runs the periodic jobs, each a singleton
across the fleet, guarded by a j-redis lock with a fencing token (`J.CAS`), so two instances never run the same job:

| Job | Period | Purpose |
|---|---|---|
| Leaderboard snapshot | 5 min | The rebuild path above |
| Tournament tick | 5 s | Advance the state machine |
| Ledger reconciliation | nightly | `SUM(delta)` against `player.coins`; alert on any mismatch |
| Retention | nightly | Drop `match_player` partitions older than 90 days |
| Stream trim | hourly | `XTRIM` to the retention in §2 |

The fencing token matters in that design: a job that pauses for a long GC can wake up believing it still holds the
lock. Every write it makes carries the token, and a stale token is refused.

## 10. What to measure

| Metric | Why | As built |
|---|---|---|
| Stream lag per consumer group (entries behind the tail) | The single best sign that something is wrong downstream | `backend_worker_queue_depth`: the group's lag plus the inbox list |
| Pending-entry count and age | A rising age means entries are read but not acknowledged | `backend_worker_pending`, the count; the age is not exported |
| Dead-letter depth | **Alert on any non-zero value** | `backend_worker_dead_letter_depth`; beside it `backend_worker_deferred_depth` |
| Apply-transaction duration p50/p99 | Feeds the MySQL metrics in [06 §12](06-persistence-mysql.md#12-what-to-measure) | not exported |
| Arena spool size and age | Non-zero means the arena cannot reach j-redis | the arena's `backend_arena_results_spooled_total` and `backend_arena_spool_failures_total`; size and age not exported |
| Redeliveries per 1 000 entries | Healthy is near zero; a rise means crashes or timeouts | `backend_worker_duplicates_total` against `backend_worker_applied_total` |
| Results lost to a trim | Each one is a player not paid | `backend_worker_trimmed_unapplied_total` |
| Unexpected failures, retried | A steady rise is an entry that keeps failing (§4) | `backend_worker_failed_total` |

Every metric that a worker exports is listed in the [worker README](../../backend/worker/README.md#metrics).

## 11. Analytics (designed 2026-09-30, plan item 25)

**The first question [Q-24](../requirements/01-scope-and-nfrs.md#7-open-questions) asks is whether players come
back.** By day, in UTC:

| Figure | Counted as |
|---|---|
| active | players with a match or a stay that ended that day, long enough to be paid |
| new players | players for whom it was the first such day |
| day-1, day-7, day-30 | of that day's new players, those active again 1, 7 and 30 days later; empty until that later day has ended |

**Recorded in the result's transaction**
([D-47](../architecture/03-decision-log.md#d-47--activity-is-recorded-in-the-results-own-transaction-a-row-a-player-a-day-and-counted-when-it-is-read)):

- For each player whose part is new (§3): `INSERT IGNORE INTO player_day (day, player_id)`, with the day the match
  ended.
- The update that the transaction already makes to the player sets `first_played_on` to that day, if it is earlier
  than the day kept, or if none is kept. So a result that arrives late from an earlier day still makes that day the
  first.
- A redelivery changes nothing, as with the rest of the result.
- A result too short to be paid writes no `player_day`. "Too short" is under `RewardRules`' minimum play, 5
  seconds (`MatchResultRepository.COUNTED_FROM_SECONDS`): a stay of a few seconds is not a day of play. Such a result
  also moves no stats, achievements, daily goals or pass points. It keeps its row and a rated match's rating,
  nothing else (defect [D-45](../defects.md#3-data-and-the-result-pipeline)).
- `player_day` is kept 90 days, as match history is, and the daily retention run deletes older rows.

**Counted when read**, by the platform's admin API (04 §10): `GET /admin/stats?days=N`. N is 1 to 60, 14 if absent.
The newest day comes first, and today's figures so far are included. The limit is sixty because a day's day-30
figure is known only once thirty more days have ended: a day-30 figure is first seen 31 days back.

```json
{"days":[{"day":"2026-09-30","active":412,"newPlayers":37,"d1":null,"d7":null,"d30":null}, …]}
```

How each figure is read:

- *active* is a range of `player_day`'s key;
- *new players* is an index range on `player.first_played_on`;
- *day-N* is the new players of that day, found in `player_day` N days on. A day-N figure is `null` while its day
  has not ended, and 0 once it has ended and nobody came back.

V16 filled both columns from the match history there was (90 days), so the figures were right from the first read.
The baseline that replaced the migration steps ([06 §8](06-persistence-mysql.md#8-migrations)) starts on an empty
database, with nothing to fill.

Not counted: a visit to the lobby without a match; anything by feature (the next slice below: the same counts split
by what the players used).

**Built 2026-09-30** (`persistence/StatsRepository`, `MatchResultRepository`'s `recordDay` and the progression
update, V16, `worker/Retention`, and the platform's `AdminServer`), and drilled from the release (`stats`). The
statement added to every result's transaction costs less than this machine's noise. A result applied alone took
35.7 to 55.7 ms before and 37.4 to 45.8 ms after, four runs of each, alternated.

### By feature (designed 2026-09-30, plan item 27)

[Q-26](../requirements/01-scope-and-nfrs.md#7-open-questions) asks for each day's new players split by what they
did **on that first day** (UTC), for six features. For each feature: how many did it, and how many of those came
back 1, 7 and 30 days later, to set against the day's figures for all new players (above).

| Feature | Done on the first day when |
|---|---|
| `queued` | a match of theirs in any mode but the public arena ended that day (`match_player`, `matches.mode` not 0) |
| `bought` | they bought something: a ledger row of reason 1 that day |
| `boosted` | a boost of theirs started that day (`boost.started_at`) |
| `tournament` | they registered for a tournament that day, alone (`tournament_entry`) or on a team's roster (`tournament_roster`, the team's `registered_at`) |
| `friend` | a friendship of theirs began that day (`friend.since`) |
| `team` | they joined a team that day (`team_member.joined_at`) |

**Counted when read**, as the rest is (D-47). For each feature: the day's new players (`first_played_on`, by its
index) for whom a row exists that day, and those of them found in `player_day` N days on. That is four grouped
queries a feature, whatever the number of days. Each lookup goes by a key that starts with the player. For the
tournaments' two tables, `tournament_entry` and `tournament_roster`, it is the index that InnoDB made for each one's
foreign key to `player` ([06 §3](06-persistence-mysql.md#3-schema)).

Not counted: a team left or a friend removed on the same day, because their rows are deleted; equipment worn,
because its time is not kept. Use is not cause (Q-26).

**Built 2026-09-30** (`StatsRepository.byFeature`, `GET /admin/stats/features`), and drilled from the release
(`stats`): two new guests who made friends on their first day were counted under `friend`.

### The funnel (designed 2026-10-04, plan item 76 (c))

With the revenue stream built (plan item 75), the question beside "do players come back" is how far they go. Of the
accounts made on a day: how many played, came back, progressed, bought, and paid. The funnel is grouped by the day
the account was made (UTC). Each step is counted **by now**, not by a deadline, and no step requires the one before:
a player may pay before reaching level 5.

| Step | Counted as |
|---|---|
| `registered` | accounts made that day, guests included |
| `guests` | of them, those still guests, never upgraded |
| `played` | those with a first day played (`player.first_played_on`) |
| `returned` | those active again on another day within the 7 after their first (`player_day`) |
| `level5` | those at level 5 or above |
| `rated` | those with a rated match in any mode (the players' rated counts) |
| `bought` | those with a purchase in the shop, in coins or gems (a ledger row of reason 1) |
| `paid` | those with an order paid for money (`payment_order` paid, or refunded since) |

**Counted when read**, by the platform's admin API: `GET /admin/stats/funnel?days=N`. N is 1 to 60, 14 if absent;
the newest day comes first, and today so far is included. It is one grouped query over the accounts made in the
window, by an index on `account.created_at` (V34), and each step is a lookup by the player's own key.

### Guests, measured (designed 2026-10-04, plan item 76 (c))

Q-22 kept guests who never upgrade, and left their deletion until there was "a measure of how many there are".
`GET /admin/stats/guests` is that measure:

```json
{"players": 120000, "guests": 41000, "inactive": 9000}
```

- `guests` are the accounts that are still guests.
- `inactive` are those guests made more than 90 days ago, with no day played in the last 90 (`player_day`, which is
  kept that long) and no login since.

**Guests are erased at 180 days** (decided 2026-10-26,
[D-87](../architecture/03-decision-log.md#d-87--a-guest-nobody-plays-for-180-days-is-erased-by-retention-a-deletion-is-at-once)).
Retention's last step (`worker/IdleGuests`) erases every guest made more than 180 days ago, with no login since and
no day played in the days kept. It erases them as a player's own deletion does: first off the boards and out of
their team (a leader's team is handed to its longest-serving vice leader, or else member), then the account. Each
erasure is counted (`backend_worker_guests_erased_total`).

Until D-87, the deletion was deferred, in
[D-72](../architecture/03-decision-log.md#d-72--the-tables-growth-is-watched-against-measured-triggers-and-a-restore-not-the-purge-is-what-binds)'s
way, to a trigger: `inactive` past a million, or past a quarter of `players`. The reason was that deleting a guest
touched every table a player is in. D-84's erasure in place made that cost small. `inactive`, at 90 days, now
measures what the erasure will take 90 days later.
