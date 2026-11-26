# Availability and failure model

This page says what breaks when a part of the backend fails, what players see, and how the backend
recovers. It is for engineers and operators who need to know what a failure costs before it
happens. It meets the targets NFR-4, NFR-5, NFR-8 and NFR-9 of the
[requirements](../requirements/01-scope-and-nfrs.md#4-non-functional-requirements). The steps an
operator takes are in the [runbook](../operations/02-runbook.md); the layout of the machines is in
the [topology](01-system-topology.md).

The honest summary: **three machines have no spare machine.**

- As set, seven arenas of 1 800 places carry 12 600 players in battle.
- Losing one machine leaves 7 200 (when B is lost, with its three arenas) to 9 000 (when A or C is
  lost, with two).
- The design's 50 000, and the 30 000 left after a loss, are unproven
  ([01 §3](01-system-topology.md#3-capacity-model)).
- Since [D-82](03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name)
  the front's nginx is also the one way in.

Everything below is about making that degradation orderly rather than total. Terms such as
*primary*, *replica*, *promotion*, *epoch* and *fence* are defined in the
[glossary](../glossary.md#1-processes-and-places).

## On one machine (the launch, D-93)

**At launch the site is one machine**
([D-93](03-decision-log.md#d-93--the-site-launches-on-one-machine-for-up-to-about-5-000-players)),
and NFR-4 is not met: nothing stands beside the machine, so nothing fails over. The sections after
this one describe three machines. On one machine:

| What fails | Players see | Recovery | Lost |
|---|---|---|---|
| A process (an arena, a gateway, a platform, a worker, nginx, a store, MySQL) | as in §1's row for it: an arena's battles end; a store's or MySQL's requests are refused | systemd restarts it, in about 5 s for a Java process. A store or MySQL reloads its data from its disk | nothing but an arena's battles; and a store's last second of writes if the machine stopped too (a store writes its log each second) |
| The machine, stopped or rebooted | nothing works, and every battle ends | the machine starts again. Every unit is enabled and starts at boot, MySQL before the platform and the workers. This is checked: the install guide's check restarts its machine | nothing: the stores reload their logs, so a session survives and its player is not asked to log in again |
| MySQL's disk | logins, and everything else that reads or writes MySQL, are refused; matches go on, and results wait | a new disk, then a restore onto it from the dump and the binlog copies ([runbook](../operations/02-runbook.md#the-restore-drill)): an hour or more | nothing, if the copies are on a disk of their own; everything since the last copy kept elsewhere, if they are not |
| The machine, for good | nothing works until another machine is installed | another machine, the install guide, then the restore: hours | as the row above, and the stores' state: sessions, queues and parties (players log in again) and results in flight (seconds of them). The leaderboards come back from MySQL (D-31). With no copy off the site (Q-53), the database is lost too |

So on one machine the copies decide what a loss costs. `/var/lib/backend`, where the copies go,
must be on a disk of its own. A copy off the site is made once the owner names a host for it.

**On two machines** ([D-96](03-decision-log.md#d-96--a-second-machine-is-the-first-ones-standby-the-replicas-and-a-front-ready-to-start),
[operations/10](../operations/10-two-machines.md)) the second machine, B, holds the replicas. The
rest of A is installed on B and stopped.

- **A's MySQL or one of A's stores fails.** This is §1's row for it: the operator promotes the
  replica on B, and A's processes follow by themselves.
- **A fails.** The site is down until an operator starts B in A's place and the provider forwards
  the site's address to B. That takes minutes, by hand
  ([runbook §4](../operations/02-runbook.md#on-two-machines-d-96)). The sessions are kept; A's
  battles and a few seconds of A's results are lost.
- **With B's own arenas**
  ([D-97](03-decision-log.md#d-97--arenas-on-the-standby-three-of-its-own-behind-as-nginx-watched-from-a),
  [10 §10](../operations/10-two-machines.md#10-arenas-on-b)), A failing also ends the battles on
  B's arenas, because their players reach them through A's nginx. After that the site is one
  machine's.
- **B fails while A runs.** B's battles are lost. Their players are sent to A's arenas once B's
  arenas have left the arena directory, within its 10 s. A's site goes on.

## 1. Failure table

On three machines, one component at a time:

| Component | Impact of failure | Players see | Recovery |
|---|---|---|---|
| One `arena` process | its rooms end: up to 1 800 players as set (12 rooms of 150) | "match ended", and back to the lobby | systemd restarts it in ~5 s; rooms are made again on demand |
| One `gateway` | its lobby connections drop | they reconnect through the front, whose nginx passes over the lost gateway | on the client's side, in seconds |
| One `platform` | lobby requests in progress fail | the client retries | it holds no state; restart it |
| One account service (D-117) | its sign-ups, logins and refreshes in progress fail; its events not yet delivered wait in its outbox, in MySQL | a player with a session plays on, a guest too; a login is retried, nginx passing over the lost one; on one machine, no account can sign up or log in until it is back | systemd restarts it; it holds no state but its database. The platforms keep the key set they last fetched for an hour, so the tokens it issued are still taken ([10 §4.2](../detailed-design/10-accounts.md#42-checking-a-token)) |
| One `worker` | the results back up | nothing at once | restart it. What it had in progress stays pending for it in the result stream. If it is gone for good, any other worker takes that over once it has been idle a minute ([05](../detailed-design/05-worker-and-events.md#the-move-onto-streams-designed-2026-09-29-plan-item-10)) |
| j-redis `session` primary | no logins, no new matches, and no request that needs a session | running matches continue; everything else is refused | promote the replica (a script, started by hand) |
| j-redis `events` primary | results stop draining; arenas spool them to disk | nothing at once | promote the replica |
| MySQL primary | no logins, and no request that reads or writes MySQL: purchases, progression, profiles, inventories. Results wait in the stream | running matches continue; everything else is refused | promote the replica (a script, started by hand) |
| nginx on the front | the site's only way in: the API, the lobby and every match connection pass it | nothing works; matches in progress end | the provider forwards the address's 80, 443 and 9001 to another machine, set up the same ([runbook §4](../operations/02-runbook.md#4-whole-machine-loss)); minutes, by hand |
| nginx on another machine | nothing, until that machine becomes the front | nothing | restart it |
| One whole machine | all of the above for that machine; if it is the front, everything, until the forwarding moves | its arenas' battles end (B: 3 of the 7 arenas; A or C: 2 of 7); every battle, if it is the front | as set, 7 200 to 9 000 places in battle are left |

Note the asymmetry that shapes the design: **a stateless process failing is a non-event; a
stateful primary failing is an incident.** There are exactly three stateful roles: MySQL, j-redis
`session` and j-redis `events`. They are the only things that need a promotion procedure. Losing
the front also needs a person, but not for a promotion: the provider must forward the public address
to another machine (D-82).

## 2. Degradation is graded, not binary

Not every component deserves the same effort. Saying so explicitly is what keeps the failover work
small:

| Tier | Components | If it is down |
|---|---|---|
| **Must not break** | MySQL, j-redis `session`, the front's nginx | Nobody can log in or spend anything. Without the front, nobody reaches the site at all. Minutes matter. |
| **Degrades play** | `arena`, `gateway`, the account service | Some matches end, and some players reconnect; without the account service, an account with no session cannot log in until it is back. Annoying, not an outage. |
| **Invisible for a while** | `worker`, j-redis `events` | Results queue up and drain later. This is tolerable for tens of minutes, because the streams are durable and the arenas spool to their local disk. |

A match already in progress survives the loss of `platform`, `worker`, MySQL and the `events`
store. It ends only if its own `arena` dies, or the front's nginx, which carries every match
connection since D-82. That is deliberate: the thing players are actively doing has the fewest
dependencies.

## 3. Failover is scripted and human-triggered

**Failover is not automatic.** Automatic failover needs consensus, and a correct Raft
implementation is a larger project than everything else in this system put together
([D-8](03-decision-log.md#d-8--failover-is-scripted-not-automatic)).

Instead, every service is built so that a promotion is *safe* to perform by hand at 3 a.m. The
design names three safeguards. Idempotency keys and epochs exist. Fencing tokens do not: the jobs
that hold leases are made safe to repeat instead (§5).

- **Fencing tokens on locks**, so that a demoted primary that wakes up cannot act on a lock it
  still believes it holds.
- **Idempotency keys on every write**, so that replaying a partly applied batch after a promotion
  gives the same result, rather than crediting twice.
- **Epoch numbers on replication**, so that a promoted replica rejects writes from the old primary.
  - For the stores this is built in j-redis 2.2 (j-redis's own D-37): a promoted replica refuses to
    follow the old primary again, and the backend's processes never use a primary whose epoch is
    lower than one they have seen.
  - For MySQL it is built too (D-35): a one-row table that only a promotion raises, and the same
    rule in the processes. The old primary is fenced, and it returns only as a new replica.

Without those three, automatic failover would be dangerous and manual failover would be merely
slow. With them, manual failover is safe, and automating it later becomes a scheduling problem
rather than a correctness problem.

The promotion scripts also refuse to make two primaries
([D-95](03-decision-log.md#d-95--a-promotion-refuses-an-old-primary-it-can-neither-rule-out-nor-fence-and-a-handover-closes-mysqls-last)).
They treat an old primary as down only when nothing answers. One that answers but refuses the
script's account is up, and the script refuses to promote past it. For a planned MySQL handover the
script makes the old primary read-only first, waits until the replica has applied everything the
old primary executed, and only then closes the old primary to everyone but administrators.

## 4. What is not covered

- **Two machines failing.** Out of scope: the system is not sized for it.
- **A room moving between processes.** A crashed room's match is lost. NFR-9 requires only that its
  players are returned cleanly, not that the match survives.
- **Split brain.** With manual promotion the operator decides which server is the primary. This is
  the main reason promotion is not automatic.
- **Region loss.** The site is in one region, by constraint.

## 5. Prerequisites still missing

These are real gaps between this document and the system as built:

| Gap | Consequence today | Tracked in |
|---|---|---|
| No fencing tokens | No lease carries one, and none needs one yet: every singleton job is made safe to repeat instead. **The ledger check** (`job:ledger-check`) only reads. **The matcher** (`mm:leader`) can run twice for a moment, when a slow round outlives its lease. Every move it makes is a watched transaction on the players' own records, so a player is never in two matches (D-55). **A season's close** (`job:season`) writes, and records each step in MySQL in the transaction that does the step. A second run, or a run after a crash, therefore pays nobody twice and halves no rating twice (D-63). **The tournament clock** runs in every worker, and claims a match in MySQL before it writes the match's tickets (D-41). A singleton job that writes and cannot be made safe to repeat would need a fencing token first. | [05 §9](../detailed-design/05-worker-and-events.md#9-scheduled-work) |
| Capacity after a machine's loss (N−1) not verified | The 30 000 is derived from the core budget, not measured. As set since D-82 the arenas carry 12 600, and 7 200 to 9 000 after a loss. The load run of 2026-09-30 found that socket I/O is missing from that budget, and costs more than the rooms themselves ([01 §3](01-system-topology.md#3-capacity-model)). | Q-3 in the [requirements](../requirements/01-scope-and-nfrs.md#7-open-questions) |

The three stateful roles run as described on two or three machines: each has a replica on another
machine, which an operator promotes with a script, and every process follows the promotion by
itself (the stores D-34, MySQL D-35). Both were drilled on 2026-09-29 from the release, with the
primary killed; MySQL was also handed over while still running. On the launch's one machine there
is no replica (D-93).

## 6. Rehearsed under load (designed 2026-09-30, plan item 23)

The drills of 2026-09-29 promoted each replica between scenarios, with nobody playing. The claim in
§2 is about players in a match, and it rests on one path that had never run: a match result that
ends while its primary is down. The rehearsal runs the load run's bots (`BOTS=<n>:<s>`,
[07 §4](../detailed-design/07-threading-and-performance.md#4-tick-budget-and-what-to-measure)) and
fails a primary under them, on this machine, from the release:

| When | What happens |
|---|---|
| every bot is in | the window opens. A probe starts: `GET /v1/inventory` with a session of its own every half second. It needs both the session store and MySQL, and the drill keeps each answer's time and status |
| half-way through the window | the primary is killed (`kill -9`): the store's with `FAILOVER=1`, or MySQL's with `MYSQL_FAILOVER=1`. With `MYSQL_FAILOVER=demote`, the planned handover runs here instead, with the old primary still alive |
| ten seconds later | the drill reads `backend_store_connected` from `platform` and from the arena, for the store: the alert named in the runbook's [§5](../operations/02-runbook.md#5-what-to-watch) should fire on it |
| the end of the window | the bots leave, and each one's stay becomes a result. For the store, the results are spooled to the arena's disk. For MySQL, they go into the stream, which `worker` cannot apply |
| 20 s after the bots have left | the replica is promoted, by `promote-store.sh` or `promote-mysql.sh --old-is-down`. In production the operator takes minutes; the drill shortens that |
| then | the drill waits up to 3 minutes for every bot's stay to reach MySQL |

**It passes when** the bots report no disconnect and no kick; every bot's stay is in `match_player`
exactly once; and the probe's refusals begin after the kill and end after the promotion.

**It reports** how long the probe was refused, from its first refusal to its first answer after
the promotion; how long after the promotion the last stay was paid; the tick p99 and the overruns
over the window; and what the alert read. The scenarios then run against the promoted replica, as
in the drills without bots. For MySQL the old primary is rebuilt and handed back, as before.

Not rehearsed here: the soak, the target load and the capacity after a machine's loss (these need
Q-3's machines); and two stores on two machines. The drill has one store holding both `session` and
`events`, so its loss is both at once, the worse case.

**Rehearsed on 2026-09-30** with 600 bots in four full rooms and one spare, a window of 120 s, from
the release, on the shared development machine (load average 6 to 8 that evening). Figures in
brackets are from a second run:

| | The store killed | MySQL killed | MySQL handed over | Control, no failure |
|---|---|---|---|---|
| Bots dropped or kicked | none | none | none | none |
| The probe refused | from 0.6 s after the kill to 0.5 s after the promotion (3.6 s in a second run) | from 0.7 s after the kill to 3.7 s after the promotion (2.2 s) | never | — |
| Every stay paid once | 600 of 600, the last 75.6 s after the promotion (78.8 s) | 600 of 600, 43.5 s after (39.1 s) | 600 of 600, 47 s after the bots left | — |
| The alert | `backend_store_connected` was 0 on `platform` and the arena 10 s after the kill, and 1 again afterwards | — | — | — |
| Whole-tick p99, full rooms | 74–79 ms | 52–68 ms | 59–71 ms | 78–108 ms |
| Overruns, all rooms | 10 | 5 | 2 | 9 |

**§2's claim holds under load.** No player in a match noticed either primary go, and no result was
lost or paid twice. A result that ended while the store was down waited on the arena's disk. One
that ended while MySQL was down waited in the stream. Both were applied once the replica was
promoted, with nothing done by hand. The processes were back on a promoted store within about 7 s,
and a signed-in request was answered within 4 s of either promotion.

**The long ticks came from the machine, not from the failover.** The control's tails were as long as
the failures' tails. All four were far above the 16 to 25 ms that plan item 14 measured on the same
machine earlier that day. The simulation of item 14's build and of this one, run in turn at the
rooms' density, cost the same (p50 2.3 ms for both), and nothing that encodes or writes had changed
between them.

**What the catch-up measured: the rate of one worker.** Paying the backlog took one `worker` 40 to
80 s: about 13 results a second.

- Each result cost 36 to 50 ms of the worker's CPU and 41 to 54 ms of MySQL's over the catch-up.
- Applied alone, a result for one player takes 34.7 ms here. 12.3 ms of it is the commit: two
  synchronous writes, the binary log's and the redo log's, at the 6 ms this machine's disk takes for
  one.
- Most of MySQL's CPU for a result is the kernel's (17.3 ms of 26.9).

[05 §1](../detailed-design/05-worker-and-events.md#1-why-a-stream-and-not-a-queue) expects ~170
results a second at 50 000 players, and calls that "about semantics, not throughput". At this
machine's rate that would take six to thirteen workers. On Q-3's machines, with their own disks, it
must be measured before it is believed. More workers multiply the rate (plan item 26), because
commits made at the same time share their synchronous writes. Applying several results in one
transaction would divide the commit's cost among them. That is not built, because the number that
would decide it is not this machine's.

## 7. A replica's health, measured (designed 2026-10-02, plan item 59)

The second audit found that nothing read a replica ([O-10](../defects.md#6-operations)):

- No metric and no alert named the MySQL replica or either store's replica.
- `promote-mysql.sh --old-is-down` waited for the replica to apply what it had *received*. A replica
  whose receiver had stopped days earlier would have been promoted without a word, and those days
  lost.
- [D-35](03-decision-log.md#d-35--mysql-fails-over-as-the-stores-do-by-an-epoch-the-clients-follow)
  priced a promotion at "the replication lag, usually well under a second", which nothing measured
  ([D-58](03-decision-log.md#d-58--a-replica-is-measured-by-what-it-has-applied-a-heartbeat-for-mysql-the-primarys-own-account-for-the-stores)).

**MySQL, by a heartbeat.** The table `ha_heartbeat` has one row, `at DATETIME(6)`. It was added by
V21 and has been part of the baseline schema, V1, since the migrations were squashed
([D-75](03-decision-log.md#d-75--the-migrations-are-squashed-into-one-baseline-before-the-first-launch)).

- Every `worker` writes `at = NOW(6)` on the primary once a second. Replication carries it as it
  carries any other write.
- Every five seconds, on a thread of its own, a worker reads `at` on every host in `BACKEND_DB_URL`,
  with the probe's 2 s limits. The writable host's `at` is the primary's now. A read-only host's
  `at` shows how far that replica has applied.
- **The lag is the primary's `at` less the replica's.** The primary's clock wrote both, so no clock
  skew between machines enters it. A replica whose receiver or applier has stopped keeps its old
  `at`, and its lag grows by a second every second.
- The metrics are `backend_mysql_replica_lag_seconds{host}`, and `backend_mysql_replica_up{host}`,
  which is 0 when the replica cannot be read (and then no lag is reported). A single host, as in
  development, reports neither.
- MySQL's own `Seconds_Behind_Source` is not used. It is empty while the applier is stopped; it
  counts only what was received, so a receiver that stopped reads as caught up; and it needs a
  privilege the backend's account does not have.

**The stores, by the primary's own account.** A j-redis primary's `INFO replication` lists each
replica connected, the offset it has acknowledged, and the seconds since it last did. A worker
holds both stores, and at each scrape it reads both primaries:

- `backend_store_replicas`: the replicas connected;
- `backend_store_replica_behind_bytes`: the primary's offset less the slowest replica's
  acknowledged one;
- `backend_store_replica_ack_seconds`: the longest time since an acknowledgement;
- and the same three under `backend_events_store_` for the `events` instance, as the stores' other
  metrics are named.

**Alerts** ([runbook §5](../operations/02-runbook.md#5-what-to-watch); first cuts, since the real
numbers are Q-3's):

- a MySQL replica more than 30 s behind for two minutes, or not readable for two minutes;
- a store with no replica connected for a minute, or a replica silent for 30 s.

**A promotion says what it would lose.** Before it touches anything, `promote-mysql.sh` reads the
last heartbeat the replica applied, and prints when the primary wrote it and how long ago.

- If the heartbeat is older than five minutes, or cannot be read at all (the failover account lacks
  the grant, or there is no heartbeat row), the script refuses, before it fences anything.
- `--stale-ok` overrides the refusal. The operator compares the time printed with when the primary
  was lost: promoting well after an outage is what the override is for.
- The first version went ahead when it could not read the heartbeat. The drill found this: its
  replica, held 30 s behind, had not yet applied the grant, so the old primary was fenced and the
  replica promoted past a limit it should have refused.

A replica that stopped receiving days earlier is therefore never promoted without a word, and the
alert above should have named it days before.
