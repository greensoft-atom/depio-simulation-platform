# Availability and failure model

What breaks when something fails, what players see, and how it recovers. The
targets this satisfies are NFR-4, NFR-5, NFR-8 and NFR-9 in
[requirements](../requirements/01-scope-and-nfrs.md#4-non-functional-requirements).

The honest summary: **three machines carrying 50 000 players have no spare
machine.** Losing one degrades capacity to roughly 30 000. Everything below is
about making that degradation orderly rather than total.

## 1. Failure table

| Component | Impact of failure | Players see | Recovery |
|---|---|---|---|
| One `arena` process | its rooms end (~7 000 players at design capacity) | "match ended", back to lobby | systemd restart ~5 s; rooms recreated on demand |
| One `gateway` | its lobby connections drop | reconnect to another endpoint | client-side, seconds |
| One `platform` | in-flight lobby requests fail | retry | stateless; restart |
| One `worker` | results queue backs up | nothing immediately | restart it; what it had in flight stays pending for it in the result stream, and if it is gone for good any other worker takes that over once idle a minute ([05](../detailed-design/05-worker-and-events.md#the-move-onto-streams-designed-2026-09-29-plan-item-10)) |
| j-redis `session` primary | no logins, no new matches, no request that needs a session | running matches continue; everything else refused | promote replica (scripted, manual trigger) |
| j-redis `events` primary | results stop draining; arenas spool to disk | nothing immediately | promote replica |
| MySQL primary | no logins, and no request that reads or writes it: purchases, progression, profiles, inventories; results wait in the stream | running matches continue; everything else refused | promote replica (scripted, manual trigger) |
| nginx on one machine | that machine's lobby endpoint is gone | client retries the next endpoint in its list | client-side, seconds |
| One whole machine | all of the above for that machine | ~1/3 of battles end | N−1 capacity ≈ 30 000 players |

Note the asymmetry that shapes the design: **a stateless process failing is a
non-event; a stateful primary failing is an incident.** There are exactly three
stateful roles — MySQL, j-redis `session`, j-redis `events` — and they are the
only things that need a promotion procedure.

## 2. Degradation is graded, not binary

Not everything deserves the same effort, and saying so explicitly is what keeps
the failover work small:

| Tier | Components | If it is down |
|---|---|---|
| **Must not break** | MySQL, j-redis `session` | Nobody can log in or spend anything. Minutes matter. |
| **Degrades play** | `arena`, `gateway` | Some matches end, some players reconnect. Annoying, not an outage. |
| **Invisible for a while** | `worker`, j-redis `events` | Results queue up and drain later. Tolerable for tens of minutes because the streams are durable and arenas spool to local disk. |

A match already in progress survives the loss of `platform`, `worker`, MySQL and
the `events` store. It only ends if its own `arena` dies. That is deliberate:
the thing players are actively doing has the fewest dependencies.

## 3. Failover is scripted and human-triggered

**Not automatic.** Automatic failover needs consensus, and a correct Raft
implementation is a larger project than everything else in this system put
together ([D-8](03-decision-log.md#d-8--failover-is-scripted-not-automatic)).

Instead, every service is built so that a promotion is *safe* to perform by
hand at 3 a.m. Of the three safeguards below, idempotency keys and epochs exist;
fencing tokens do not, the jobs that hold leases being made safe to repeat
instead (§5):

- **Fencing tokens on locks**, so a demoted primary that wakes up cannot act on
  a lock it still believes it holds.
- **Idempotency keys on every write**, so replaying a partly-applied batch after
  a promotion produces the same result rather than double-crediting.
- **Epoch numbers on replication**, so a promoted replica rejects writes from
  the old primary. Built for the stores (j-redis 2.2, j-redis's own D-37): a promoted replica
  refuses to follow the old primary again, and the backend's processes never
  use a primary whose epoch is lower than one they have seen. Built for MySQL
  too (D-35): a one-row table only a promotion raises, and the same rule in
  the processes; the old primary is fenced, and returns only as a new replica.

Without those three, automatic failover would be dangerous and manual failover
would be merely slow. With them, manual failover is safe, and automating it
later becomes a scheduling problem rather than a correctness one.

## 4. What is not covered

- **Two machines failing.** Out of scope; the system is not sized for it.
- **A room migrating between processes.** A crashed room's match is lost
  (NFR-9 requires only that players are returned cleanly, not that the match
  survives).
- **Split brain.** With manual promotion the operator is the arbiter. This is
  the main reason promotion is not automatic.
- **Region loss.** Single-region by constraint.

## 5. Prerequisites still missing

These are real gaps between this document and reality:

| Gap | Consequence today | Tracked in |
|---|---|---|
| No fencing tokens | No lease carries one, and none needs one yet: every singleton job is made safe to repeat instead. The ledger check (`job:ledger-check`) only reads. The matcher (`mm:leader`) can run twice for a moment when a slow round outlives its lease, and every move it makes is a watched transaction on the players' own records, so a player is never in two matches (D-55). A season's close (`job:season`) writes, and records each step in MySQL in the transaction that does it, so a second run, or one after a crash, pays and halves nobody twice (D-63). The tournament clock runs in every worker and claims a match in MySQL before writing its tickets (D-41). A singleton job that writes and cannot be made safe to repeat would need a fencing token first. | [05 §9](../detailed-design/05-worker-and-events.md#9-scheduled-work) |
| N−1 capacity unverified | 30 000 is derived from the core budget, not measured, and the load run (2026-09-30) found socket I/O missing from that budget and larger than the rooms' own cost ([01 §3](01-system-topology.md#3-capacity-model)). | Q-3 in [requirements](../requirements/01-scope-and-nfrs.md#7-open-questions) |

The three stateful roles now run as described: each with a replica on another
machine that an operator promotes with a script, and every process following the
promotion by itself (the stores D-34, MySQL D-35; both drilled 2026-09-29, from
the release, the primary killed and, for MySQL, handed over alive as well).

## 6. Rehearsed under load (designed 2026-09-30, plan item 23)

The drills of 2026-09-29 promoted each replica between scenarios, with nobody
playing. §2's claim is about players in a match, and the path it rests on, a
result ending while its primary is down, was never taken. The rehearsal runs the
load run's bots (`BOTS=<n>:<s>`, 07 §4) and fails a primary under them, on this
machine, from the release:

| When | What |
|---|---|
| every bot in | the window opens; a probe starts: `GET /v1/inventory` with a session of its own every half second, which needs the session store and MySQL both, each answer's time and status kept |
| half the window | the primary is killed (`kill -9`); the store's or MySQL's, by `FAILOVER=1` or `MYSQL_FAILOVER=1`. With `MYSQL_FAILOVER=demote`, the planned handover runs here instead, the old primary alive |
| ten seconds on | `backend_store_connected` read from `platform` and the arena, for the store: the alert §5 says fires |
| the window's end | the bots leave, each a result: for the store, spooled to the arena's disk; for MySQL, into the stream, which `worker` cannot apply |
| 20 s after the bots have left | the replica promoted, by `promote-store.sh` or `promote-mysql.sh --old-is-down`: the operator's minutes, shortened |
| then | the drill waits for every bot's stay in MySQL, up to 3 minutes |

**Passes when:** the bots report no disconnect and no kick; every bot's stay is
in `match_player` exactly once; the probe's refusals begin after the kill and end
after the promotion. **Reported:** how long the probe was refused, from its first
refusal to its first answer after the promotion; how long after the promotion
the last stay was paid; the tick p99 and overruns over the window; what the
alert read. The scenarios then run against the promoted replica, as in the
drills without bots, and for MySQL the old primary is rebuilt and handed back
as before.

Not rehearsed here: the soak, target load and N−1 capacity (Q-3's machines);
two stores on two machines (the drill's one store holds `session` and `events`
both, so its loss is both at once, the worse case).

**Rehearsed 2026-09-30**, 600 bots in four full rooms and one spare, a window
of 120 s, from the release, on the shared development machine (load average 6
to 8 that evening):

| | The store killed | MySQL killed | MySQL handed over | Control, no failure |
|---|---|---|---|---|
| Bots dropped or kicked | none | none | none | none |
| The probe refused | from 0.6 s after the kill to 0.5 s after the promotion (3.6 s in a second run) | from 0.7 s after the kill to 3.7 s after the promotion (2.2 s) | never | — |
| Every stay paid once | 600 of 600, the last 75.6 s after the promotion (78.8 s) | 600 of 600, 43.5 s after (39.1 s) | 600 of 600, 47 s after the bots left | — |
| The alert | `backend_store_connected` 0 on `platform` and the arena 10 s on; 1 again after | — | — | — |
| Whole-tick p99, full rooms | 74–79 ms | 52–68 ms | 59–71 ms | 78–108 ms |
| Overruns, all rooms | 10 | 5 | 2 | 9 |

**§2's claim holds under load**: no player in a match noticed either primary
go, and no result was lost or paid twice. A result that ended while the store
was down waited on the arena's disk, and one that ended while MySQL was down
waited in the stream; both were applied once the replica was promoted, with
nothing done by hand. The processes were back on a promoted store within about
7 s, and a signed-in request was answered within 4 s of either promotion.

**The ticks were the machine's, not the failover's**: the control's tails were
as long as the failures', and all four far above item 14's 16 to 25 ms on the
same machine earlier that day. The simulation of item 14's build and this one,
run alternately at the rooms' density, cost the same (p50 2.3 ms both), and
nothing that encodes or writes changed between them.

**What the catch-up measured: a worker's rate.** Paying the backlog took one
`worker` 40 to 80 s: about 13 results a second, each costing 36 to 50 ms of the
worker's CPU and 41 to 54 ms of MySQL's over the catch-up. Applied alone, a
one-player result takes 34.7 ms here, 12.3 ms of it the commit: two synchronous
writes, the binary log's and the redo log's, at the 6 ms this machine's disk
takes for one, and most of MySQL's CPU a result is the kernel's (17.3 ms of
26.9). [05 §1](../detailed-design/05-worker-and-events.md#1-why-a-stream-and-not-a-queue)
expects ~170 results a second at 50 000 players and calls that "about
semantics, not throughput"; at this machine's rate it would take six to
thirteen workers. On Q-3's machines, with their own disks, it is to be measured
before it is believed. More workers multiply it (plan item 26): commits made at
once share their synchronous writes. Applying several results in one transaction would divide
the commit's cost among them; not built, since the number that would decide it
is not this machine's.

## 7. A replica's health, measured (designed 2026-10-02, plan item 59)

The second audit found nothing reading a replica ([O-10](../defects.md#6-operations)):
no metric and no alert names the MySQL replica or either store's, and
`promote-mysql.sh --old-is-down` waits for the replica to apply what it
*received*, so one whose receiver stopped days ago would be promoted without a
word, and the days lost. [D-35](03-decision-log.md#d-35--mysql-fails-over-as-the-stores-do-by-an-epoch-the-clients-follow)
priced a promotion at "the replication lag, usually well under a second",
which nothing measured
([D-58](03-decision-log.md#d-58--a-replica-is-measured-by-what-it-has-applied-a-heartbeat-for-mysql-the-primarys-own-account-for-the-stores)).

**MySQL, by a heartbeat.** V21 adds `ha_heartbeat`, one row, `at DATETIME(6)`.
Every `worker` writes `at = NOW(6)` on the primary once a second; replication
carries it as any other write. Every five seconds, on a thread of its own, a
worker reads `at` on every host of `BACKEND_DB_URL`, with the probe's 2 s limits: the writable
host's is the primary's now, a read-only host's is as far as that replica has
applied. **Lag is the primary's `at` less the replica's**: both were written by
the primary's clock, so no skew between machines enters it. A replica whose
receiver or applier has stopped keeps its `at`, and its lag grows a second a
second. `backend_mysql_replica_lag_seconds{host}`, and
`backend_mysql_replica_up{host}`, 0 when the replica cannot be read (and then no
lag); a single host, as in development, reports neither. MySQL's own
`Seconds_Behind_Source` is not used: it is empty while the applier is stopped,
counts only what was received, so a receiver that stopped reads as caught up,
and needs a privilege the backend's account does not have.

**The stores, by the primary's own account.** A j-redis primary's `INFO
replication` lists each replica connected, the offset it has acknowledged and
the seconds since it last did. A worker holds both stores, and at each scrape
reads both primaries: `backend_store_replicas` (connected),
`backend_store_replica_behind_bytes` (the primary's offset less the slowest
replica's acknowledged one) and `backend_store_replica_ack_seconds` (the longest
since an acknowledgement), and the same under `backend_events_store_` for the
events instance, as the stores' other metrics are named.

**Alerts** (runbook §5; first cuts, since real numbers are Q-3's): a MySQL replica
more than 30 s behind for two minutes, or not readable for two minutes; a store
with no replica connected for a minute, or one silent for 30 s.

**A promotion says what it would lose.** Once the replica has applied what it
received, `promote-mysql.sh` reads its heartbeat and prints when the primary
wrote it and how long ago. Older than five minutes, or not readable at all
(no grant, or a database before V21), it refuses, before it fences anything,
unless given `--stale-ok`: the operator compares that time with when the
primary was lost, and promoting well after an outage is what the override is
for. The first version went ahead when it could not read the heartbeat, which
the drill found: its replica, held 30 s behind, had not yet applied the grant,
and the old primary was fenced and the replica promoted past a limit it was to
refuse. A replica that
stopped receiving days earlier is then never promoted without a word, and the
alert above should have named it days before.
