# Runbook

What to do when something breaks, and what to watch so you find out before a
player tells you.

**Status: written for everything built** (2026-10-04). The failure model is
settled ([architecture/02](../architecture/02-availability.md)), and j-redis has
a tested runbook of its own. Every procedure below has been rehearsed in a
drill; what is still open, the alerts' thresholds, waits on production
hardware (§7).

## 1. Triage: what is actually broken

Work down this list. It is ordered by how much it hurts, not by how likely it is.

| Symptom | First check | Likely cause |
|---|---|---|
| Nobody can log in | MySQL primary, then j-redis `session` | A stateful primary is down |
| Logins work, no new matches start | j-redis `session`, then `platform` | Matchmaking queues or the allocator |
| Matches start but end immediately | `arena` instances | An arena is crash-looping |
| Play is fine, rewards never arrive | `worker`, then j-redis `events` | Results backlog |
| One third of players dropped at once | Whole machine | See [§4](#4-whole-machine-loss) |
| Everything slow, nothing down | GC logs, then load average | Capacity, or a noisy neighbour |

The useful property of the design: **a match in progress survives the loss of
`platform`, `worker`, MySQL and the `events` store.** If players are still playing,
the problem is not urgent in the way it looks.

## 2. Stateful primary failure

The three roles that need a human: MySQL, j-redis `session`, j-redis `events`.
Promotion is manual by design
([D-8](../architecture/03-decision-log.md#d-8--failover-is-scripted-not-automatic)).

The procedure is the same shape for all three:

1. **Confirm the primary is really dead**, not merely slow. Promoting while the
   old primary is alive is the one way to lose data.
2. **Fence it** — stop the unit, and disable it so a reboot cannot bring it
   back as a primary.
3. **Promote the replica** and bump the replication epoch.
4. **Repoint the clients** and confirm writes succeed.
5. **Rebuild a new replica** from the new primary before going back to bed. A
   promoted replica with no replica of its own is the next incident.

Step 1 is the one that gets skipped under pressure, and it is the only step
that can cause permanent damage.

### A j-redis store (built 2026-09-29, D-34)

`session` has its primary on A and its replica on B; `events` its primary on B
and its replica on C (01 §1). On the replica's machine, **as root**: the script
reads the password from the store's configuration file
(`/etc/backend/store-<name>.conf`, `root:jredis`, mode 0640), and after promoting
it rewrites that file and keeps a copy beside it in `/etc/backend/`, which only
root may write. Run
as any other user it stops at its first read; and if it could read but not
write, it would promote and leave `replicaof` in the file, so that the next
restart made the new primary a replica again. It reaches this machine's server
on `127.0.0.1` (01 §3):

```bash
# 1–2. The old primary's machine is gone, or its unit is stopped and disabled, or its port
#      is firewalled. Then:
sudo /opt/backend/scripts/promote-store.sh /etc/backend/store-session.conf <old primary>:6379 --old-is-down
# If the old primary is alive but must hand over (a planned move), it can be demoted instead:
#   sudo /opt/backend/scripts/promote-store.sh /etc/backend/store-session.conf <old>:6379 --demote-old <this machine>:6379
```

For `events`, the same with its own configuration file and port 6380.
The scripts use the release's client, `/opt/backend/jredis/bin/j-redis-cli` on
`/opt/backend/runtime`; `JREDIS_CLI` names another.

It refuses while the old primary still answers as one, promotes (`REPLICAOF NO
ONE`: the epoch goes up and is written to disk), takes `replicaof` out of the
configuration file with a copy kept, and prints the new epoch. **Step 4 is
automatic**: every process was given both addresses and finds the new primary
within a reconnect; confirm with `backend_store_connected` (or
`backend_events_store_connected`) back at 1, each gateway's
`backend_gateway_store_subscribed` at 1 (its subscriptions, which carry every
push, follow the primary too: a demoted one closes them, and one on a lost
machine is noticed within about 11 s, [03 §5](../detailed-design/03-gateway.md#5-push-routing)),
`PUBSUB NUMSUB push:all` on the new primary counting every gateway (the metric
says the subscription is up, this says where), and the store's
`INFO replication` showing `role:primary`. An old primary that comes back on its
own is ignored by every process that saw the higher epoch.

**Step 5**, when the old machine is back: put `replicaof <new primary> 6379` and
`primaryauth` in its configuration **before** starting it. It takes a full copy
and the new epoch; `ROLE` on it shows `connected`.

Rehearsed on 2026-09-29 from the release (`FAILOVER=1 client/headless-drill.sh
play duel`): the store's primary killed with `-9` after a first run of the
scenarios, the replica promoted by the script, and the same scenarios passed
again against the same processes, each back on the new primary within about six
seconds; the four results, two from each side of the failover, applied.

**Rehearsed as a planned handover** on 2026-10-01 (`FAILOVER=demote
client/headless-drill.sh duel notice`): the old primary demoted by the script
and left running, as step 3's `--demote-old` leaves it. Step 4's confirmation
as written above: every process back on the new primary, the gateway's
`backend_gateway_store_subscribed` at 1, and `PUBSUB NUMSUB` on the new primary
counting the gateway's two channels and the arena's. The scenarios passed
again against the same processes. Before j-redis 2.2.1 the same handover left
every subscription on the demoted server, and no push or notice arrived (O-6).

**Rehearsed under load** on 2026-09-30 (`BOTS=600:120 FAILOVER=1
client/headless-drill.sh`, [architecture/02 §6](../architecture/02-availability.md#6-rehearsed-under-load-designed-2026-09-30-plan-item-23)):
600 bots playing, the primary killed halfway through, the bots leaving while it
was down, the replica promoted 20 s after. The procedure above was followed as
written, step 4's confirmation included: `backend_store_connected` read 0 on
`platform` and the arena ten seconds after the kill, and 1 again after the
promotion. No bot noticed; the 600 results waited in the arena's spool and were
all applied once, the last 76 s after the promotion, at one worker's rate (§3).

### MySQL (built 2026-09-29, D-35)

The primary is on A, its replica on C (01 §1). On C, as root, since the
account's file is in `/etc/backend/credentials/` (01 §2):

```bash
# 1–2. The old primary's machine is gone, or mysqld is stopped and disabled, or its port is
#      firewalled from C. Then:
sudo /opt/backend/scripts/promote-mysql.sh <old primary>:3306 --old-is-down
# If the old primary is up and must hand over (a planned move), the script fences it itself:
#   sudo /opt/backend/scripts/promote-mysql.sh <old primary>:3306 --demote-old
```

First it prints the primary's last heartbeat the replica applied and how long
ago that was (architecture/02 §7): what a promotion would lose is everything the
primary wrote after it. **Older than five minutes, or not readable (the account's
grant missing, 01 §9), it refuses**, touching nothing. Compare that time with when the primary was lost: if they agree, the
outage is simply that old, and running it again with `--stale-ok` as a third
argument promotes; if the heartbeat is much older, the replica had stopped
receiving or applying before the outage, and that stretch is lost unless the
old primary's disk can be read (§3). With `--old-is-down` it refuses while the
old primary answers at all. With
`--demote-old` it fences it first: `super_read_only`, which stops every write,
persisted so a restart keeps it, and `offline_mode`, which closes every
connection but an administrator's and refuses new ones. Without the second,
each process kept its connections to the old primary and failed every write on
them (defect [D-29](../defects.md)). Then it waits, up to 60 s, until the replica has
applied everything it received; if that times out it refuses, and running it
again once the replica has caught up is safe. It stops replication, makes the
replica writable persistently, raises the epoch, and prints it with the executed
GTID set: keep that line for the incident's record.

**Step 4 is automatic**: every process was given both hosts and opens each new
connection to the writable one with the highest epoch; its broken connections
are replaced as they are next used. Requests in flight at the moment fail and
are answered "try again". A primary whose machine vanished, or was cut off by
the `DROP` rule, resets nothing: each statement waiting on it is given up after
30 s, and a new connection's probe passes over it in 2 s
([06 §7](../detailed-design/06-persistence-mysql.md#7-connection-pooling)). Confirm with a login, and with `worker`'s results
being applied again (`backend_worker_applied_total` rising). What the
replica had not received when the primary died is lost, at the replication lag.

**Step 5: the old primary never comes back as it was.** It may hold
transactions the replica never received, and started as it was it would be a
second primary with an older epoch, which every process that saw the promotion
passes over but a process started afterwards with only its address would not.
Before starting it again, put `super_read_only = ON` in its configuration, as
C's is (01 §9), so that it starts read-only. Then make it a replica of the new
primary exactly as 01 §9 makes C one, with the addresses the other way round:
`CLONE INSTANCE` from the new primary replaces its data, accounts and GTID
history, its stray transactions with them, and it then follows the new primary
by GTIDs. An old primary that was handed over is still up and fenced: the same,
without the restart first. It comes back read-only and open, which the probes
need; check `SHOW REPLICA STATUS` shows both threads running.

**The backups, the same night.** The copies were taken on C, which now holds
the primary: they would be on the disk they protect (06 §10). Install the
stream and the dump (01 §10) on the machine that holds the replica, B until the
old primary is rebuilt and that machine after, with `host` in their option
files naming the new primary, into an empty copies directory: the new
primary's binlogs are a series of their own. Start `backend-mysql-backup` at
once; that dump is the earliest point the new series restores to. A point
before the promotion restores from the old copies, where they are. The proofs,
their scratch server and the copy off the site go with the stream and the dump
(01 §10): on C they would prove copies kept on the primary's own disk.

**Moving back**, if the primary belongs on A: once the rebuilt replica has
caught up, the handover the other way, on A, `promote-mysql.sh <C>:3306
--demote-old`; then C is rebuilt the same way, and the backups move with the
replica again. Each promotion raises the epoch.

Rehearsed on 2026-09-29 from the release, with two MySQL servers of the drill's
own (`MYSQL_FAILOVER=1` and `MYSQL_FAILOVER=demote client/headless-drill.sh play
duel`), the replica applying 30 s late so that the promotion had to wait: killed
with `-9`, the script refused while the old primary still answered, then
promoted; handed over, it fenced the old primary. Both times the new primary
held every match the old one had, the scenarios passed again against the same
processes, a second run of the script changed nothing, and the new primary
restarted writable although its command line said read-only. Then step 5 and
the move back (2026-09-30): the old primary rebuilt by clone, over TLS, in 9 s
at development size, read-only and open, every match there; handed back to at
epoch 2; and the scenarios a third time against the same processes.

**Rehearsed with the primary frozen** on 2026-10-02 (`MYSQL_FAILOVER=freeze
client/headless-drill.sh duel`): `SIGSTOP`, so that it accepted TCP and
answered nothing, as a machine gone; the script, finding it did not answer,
promoted. `platform` answered again 11.3 s after the promotion and the duel
passed against the same processes. Before the limits of 06 §7 it never did:
each new connection's probe waited on the frozen server.

**Rehearsed under load** on 2026-09-30 (`BOTS=600:120 MYSQL_FAILOVER=1`, and
`=demote`, [architecture/02 §6](../architecture/02-availability.md#6-rehearsed-under-load-designed-2026-09-30-plan-item-23)):
600 bots playing, MySQL's primary killed halfway through, the script refusing
while it still answered, the bots leaving while it was down, the replica
promoted 20 s after. No bot noticed; the 600 results waited in the stream and
were all applied once, the last 44 s after the promotion. A signed-in request
was refused from the kill until 4 s after the promotion; handed over instead,
it was never refused.

## 3. Per-component procedures

| Component | Impact | Action |
|---|---|---|
| One `arena` | Its rooms end; up to `MAX_ROOMS` × `MAX_PLAYERS` players (600 with the example settings) returned to the lobby | systemd restarts it; rooms are recreated on demand. Investigate only if it loops. |
| One `gateway` | Its connections drop | Clients retry another endpoint. Restart; no state to recover. |
| One `platform` | In-flight lobby requests fail | Stateless; restart. |
| One `worker` | Results back up | Restart it. What it had in flight stays pending for it in the stream: the same instance name re-drives it at once, and if the machine is gone for good any other worker takes it over once idle a minute, so nothing is lost ([05](../detailed-design/05-worker-and-events.md#the-move-onto-streams-designed-2026-09-29-plan-item-10)). Confirm `backend_worker_pending` and `backend_worker_queue_depth` drain. |
| j-redis `events` | Results stop draining; arenas spool to local disk | Promote its replica ([§2](#2-stateful-primary-failure)); the arenas' spools drain into it, and the workers apply the backlog at their own rate: 600 results took one worker 76 s after a promotion on the development machine (rehearsed under load, [architecture/02 §6](../architecture/02-availability.md#6-rehearsed-under-load-designed-2026-09-30-plan-item-23)). Without a replica: restore the store, then watch spool files drain, checking disk on the arena machines. Started empty, the store has no result group: the workers make it again at their next read or re-drive ([defect D-27](../defects.md#3-data-and-the-result-pipeline)). |
| j-redis `session` primary lost | Logins, tickets, matchmaking and every request that needs a session fail until it is back; matches in progress play on (rehearsed under load, [architecture/02 §6](../architecture/02-availability.md#6-rehearsed-under-load-designed-2026-09-30-plan-item-23)) | Promote its replica ([§2](#2-stateful-primary-failure)): sessions, boards and queues are on it, up to the last moment the replica had |
| j-redis `session` lost with its disk, and no replica | Everyone is logged out and every board is empty | Start it empty; players log in again. Then `systemctl start backend-leaderboard-rebuild` on one worker machine: the boards come back from MySQL (05 §8). Rehearsed on 2026-09-26 with the shipped unit: 16 of 16 players, every board and name as before the loss |
| Results set aside (`backend_worker_dead_letter_depth` above 0) | Each entry is a player's match whose pay and rating did not arrive, and it lives only in the store, the `events` instance where there is one ([D-31](../architecture/03-decision-log.md#d-31--the-store-is-not-copied-off-its-machine-on-a-timer-before-replication)) | **Copy the entries off the machine first** (below). Then find why: the worker's log says `dead-lettering match …` and the reason. Once a deploy reads them, put them back on the queue: applying is idempotent, so an entry already applied is a no-op ([05 §3](../detailed-design/05-worker-and-events.md#3-the-consumer-loop)) |
| The ledger check found balances it does not explain (`backend_worker_ledger_mismatches` above 0) | Some players' coins or gems moved without a ledger row, or a row without its balance: a bug in whatever path moved them, since every path goes through one method ([defect D-13](../defects.md#3-data-and-the-result-pipeline), not the decision of that name). The worker's log names the first twenty, each with both balances and both ledger totals (`ledger check: N players' balances differ`) | **Find the cause first**: the ledger rows and `updated_at` of those players say when. Then set each balance to its ledger total, which is the audit trail (06 §3), and record it: `UPDATE player p JOIN (SELECT player_id, SUM(delta) s FROM ledger WHERE currency = 0 GROUP BY player_id) l ON l.player_id = p.id SET p.coins = l.s WHERE p.id IN (…)`, and for gems the same with `currency = 1` and `p.gems`. The join skips a player with no ledger rows of that currency; set that balance to 0 with a plain `UPDATE`. The next day's check confirms it |
| Tournaments stop advancing (`backend_worker_tournament_failures_total` rising steadily) | No match is made, decided or paid; the rounds wait | The worker's log names the tournament and the error (`tournament N: its step failed`), most often MySQL or the store unreachable. Every worker tries again every 5 s by itself, and nothing is done twice (D-41); fix what the error names. A match whose worker died between claiming it and writing its tickets cannot be joined: 270 s after the claim, the higher seed advances (04 §6) |
| Results lost to the trim (`backend_worker_trimmed_unapplied_total` above 0) | A result was pending a whole day, failing, and the stream's 24 hours trimmed it: its players were not paid, and nothing holds it any more (the arena's spool let it go once the stream had it) | The worker's log names it (`result … was trimmed … before anyone applied it`), and `backend_worker_failed_total` will have been rising all that day: find why it failed. The arena's log of the time names the match, and its players can be made good by hand |
| Scores missing from the boards (`backend_worker_unranked_total` rose during a store outage) | The results are in MySQL; their scores never reached a board | The same command, once the store answers again. It is safe on a healthy store: it only raises scores, and leaves each board's expiry alone |
| MySQL primary lost | No logins, and no request that reads or writes it: purchases, progression, profiles, inventories; results wait in the stream and are applied once it is back; matches in progress play on (rehearsed under load, [architecture/02 §6](../architecture/02-availability.md#6-rehearsed-under-load-designed-2026-09-30-plan-item-23)) | Promote its replica ([§2](#2-stateful-primary-failure)): everything committed up to the replication lag is on it. The processes follow by themselves. |
| MySQL slow | Purchases and progression writes time out | Check for a long transaction before assuming load. |
| A certificate close to expiry, or `connections_dropped_total{reason="tls_handshake"}` jumping after a change | At expiry, every device refuses that machine's logins and purchases (nginx) or matches (an arena) | `install-certificate.sh --check` on the machine says which port serves what, and why a device would refuse it. `certbot renew --dry-run` says why renewal fails; once renewed, the hook has installed it everywhere and only arenas still need a restart, one at a time ([01 §11](01-deploy.md#11-the-certificate)) |

**The dead list, by hand**, on the store's machine, the password sent on
standard input as the scripts send it (never `-a`, which `ps` shows). Run
against j-redis 2.0 on 2026-09-29, and again against 2.1 the same day with the
prompt fix ([O-2](../defects.md#6-operations)): the copy held each entry on its
own line, an empty list copied nothing, and the move put them back oldest
first, behind what was queued.

```bash
store() { { echo "AUTH $(cat /etc/backend/credentials/store-password)"; cat; } \
    | JAVA_HOME=/opt/backend/runtime /opt/backend/jredis/bin/j-redis-cli --raw -p 6379 \
    | sed -E 's/^([^ ]+:[0-9]+> ?)+//' | sed '/^$/d' | tail -n +2; }   # split: events' port, password
echo "LRANGE q:match-result:dead 0 -1" | store > dead-$(date -u +%Y%m%dT%H%M%SZ).txt
# ...copy that file off the machine. After the fix is deployed:
n=$(echo "LLEN q:match-result:dead" | store)
yes "LMOVE q:match-result:dead q:match-result RIGHT LEFT" | head -n "$n" | store
```

The list they go back on, `q:match-result`, is the inbox since results moved
onto a stream (2026-09-29): every worker moves it into `s:match-result` at its
next re-drive, within 30 s.

### 3a. The admin API

On the machine running `platform`, as root, the secret read from its file
([04 §10](../detailed-design/04-platform-services.md#the-first-slice-designed-2026-09-29-plan-item-7)).
It is served only when `BACKEND_ADMIN_ADDR` names a loopback address, with the
secret from the drop-in `deploy/systemd/platform-admin.conf.example`
([01 §2](01-deploy.md#secrets)); `127.0.0.1:9120` below is the example's.
Every call needs `Authorization: Bearer <the secret>`, else 401; a body is JSON
of at most 4 KiB; every call is audited, refused ones too.

| Call | Body | What it does |
|---|---|---|
| `GET /admin/arenas` | | Each arena, its players and rooms |
| `GET /admin/rooms` | | Each arena's rooms, as last announced |
| `POST /admin/rooms/{arena}/{room}/close` | `{"reason"}` | Told to the arena: 202 with how many heard. A made match closed is cut short, and not rated |
| `POST /admin/players/{id}/ban` | `{"reason"}`, or with `"until"` (an ISO-8601 instant) for a suspension | Ends every session, closes the lobby connection, takes the player out of any match; 404 `no_such_player`, 400 `bad_until` |
| `POST /admin/players/{id}/unban` | `{"reason"}` | The account active again |
| `POST /admin/players/{id}/kick` | `{"reason"}` | Out of any match, told to every arena: 202 with how many heard; the player may come back |
| `POST /admin/players/{id}/refund-debt` | `{"reason"}` | Clears what a refund left the player owing (04 §8, D-68); answers how many gems were cleared |
| `POST /admin/payments/{orderId}/refund` | `{"reason"}` | A paid order refunded, once: its gems taken back as far as the balance allows, the rest the player's debt; 404 `no_such_order`, 409 `not_paid` |
| `POST /admin/notice` | `{"text", "reason"}` | Pushed as `evt.notice` to every player in the lobby, on every gateway: 202 with how many gateways heard. 1 to 200 characters, none a control or direction character |
| `POST /admin/tournaments` | `{"reason", "name", "maxEntries", "registrationEnds", "startsAt", "roundMinutes", "prizes", "mode", "format"}` | A tournament (04 §6): a name of 1 to 64 characters, 2 to 32 entries (2 to 8 for a round robin), registration ending ahead and the start no earlier, rounds of 1 to 60 minutes, three whole prizes in coins; `mode` `duel` (default) or `teams`, `format` `elimination` (default) or `round_robin`. Answers its `id`; 400 `invalid_tournament` says which rule failed |
| `GET /admin/seasons` | | Every season, newest first, and how far its close has gone |
| `POST /admin/seasons/end` | `{"reason"}` | The current season ends now; 409 `already_ended` while the worker closes it |
| `GET /admin/stats?days=N` | | Whether players come back, by day (05 §11); `N` 1 to 60, default 14 |
| `GET /admin/stats/features?days=N` | | Each day's new players by what they did that day |
| `GET /admin/stats/funnel?days=N` | | Of the accounts made each day, how far they have gone by now |
| `GET /admin/stats/guests` | | The guests never upgraded, and those idle 90 days |

```bash
A=http://127.0.0.1:9120; T="Authorization: Bearer $(cat /etc/backend/credentials/admin-token)"
curl -s -H "$T" $A/admin/arenas                                   # each arena, its players and rooms
curl -s -H "$T" -d '{"reason":"cheating, ticket 123"}' $A/admin/players/<id>/ban
curl -s -H "$T" -d '{"reason":"abuse","until":"2026-10-08T00:00:00Z"}' $A/admin/players/<id>/ban
curl -s -H "$T" -d '{"reason":"appeal upheld"}' $A/admin/players/<id>/unban
curl -s -H "$T" $A/admin/rooms                                    # each arena's rooms, as last announced
curl -s -H "$T" -d '{"reason":"stuck"}' $A/admin/rooms/<arena>/<room>/close
curl -s -H "$T" -d '{"reason":"afk"}' $A/admin/players/<id>/kick     # out of any match; may come back
curl -s -H "$T" -d '{"reason":"chargeback"}' $A/admin/payments/<orderId>/refund
curl -s -H "$T" -d '{"reason":"settled by support"}' $A/admin/players/<id>/refund-debt
curl -s -H "$T" -d '{"text":"Restarting in 5 minutes","reason":"weekly restart"}' $A/admin/notice
curl -s -H "$T" $A/admin/seasons                                  # each season, and how far its close has gone
curl -s -H "$T" -d '{"reason":"align the calendar"}' $A/admin/seasons/end   # the current season ends now
curl -s -H "$T" "$A/admin/stats?days=14"                          # and /features, /funnel; /guests takes no days
```

**A season's close** (04 §7, D-63) is the worker's, within the minute of its
end: `placedAt`, `paidAt` and `resetAt` in `GET /admin/seasons` fill in that
order. One still empty after a few minutes means every worker's season job
is failing: its log says why (`season job failed`), and the next minute goes
on from where it stopped, paying nobody twice and halving nobody twice. Ending a
season early is for a drill, or to bring a season onto the calendar; the next
then runs to the next boundary (the first of an odd month, 00:00 UTC), however
near.

A ban or suspension ends every session the player has, closes their lobby
connection and takes them out of any match, at once. Closing a room and kicking
a player are told to the arenas, and answered 202 with how many heard: the
arena acts on the room's next tick. A reason is required for every call that
changes something. What was done, and by which reason:

```sql
SELECT at, action, target, outcome, request FROM admin_audit ORDER BY id DESC LIMIT 20;
```

## 4. Whole-machine loss

Capacity drops to roughly 30 000 players (N−1). What to do depends on which
machine:

- **Machine A** — both primaries. Follow [§2](#2-stateful-primary-failure)
  twice: MySQL and j-redis `session`. This is the worst case, and the reason
  both primaries sit together is that it is *one* procedure to rehearse.
  `session`'s replica is on B and MySQL's on C, and §2 promotes each; only with
  a replica's machine gone too is `session` started empty and the boards
  rebuilt ([§3](#3-per-component-procedures)), or MySQL brought back from the
  backup machine's copies, or from the copy off the site if C went too
  ([a last-resort restore](#a-last-resort-restore-with-no-source-left)).
- **Machine B** — the `events` primary, plus three arenas. Promote `events`;
  the arenas' matches are lost and players return to the lobby.
- **Machine C** — replicas and workers. No promotion needed. Whatever its
  workers had claimed and not acknowledged stays pending on the stream, and the
  surviving workers claim it once it has been idle a minute
  ([05, the move onto streams](../detailed-design/05-worker-and-events.md#the-move-onto-streams-designed-2026-09-29-plan-item-10));
  start `backend-worker@c1` and `@c2` elsewhere only for the capacity. Rebuild
  replicas when the machine returns.

A replacement machine takes the lost one's name and gets its own certificate
once the name points at it ([01 §11](01-deploy.md#11-the-certificate)): the
old one's key went with its disk, and nothing needs it.

In all three cases the surviving machines keep serving. The thing to watch is
whether the remaining arena capacity is being oversubscribed; if so, cap
matchmaking rather than letting every room overfill.

## 5. What to watch

Alert on the first group; look at the second when investigating. Each alert's
metric and threshold are in [01 §7](01-deploy.md#7-installing-a-machine).
These are what alert rules should say: none ship with the release, which
serves the metrics on each machine's loopback and no more (01 §7). Until
something scrapes and alerts, the daily checks in §6 are by hand.

**Alert:**

| Signal | Why |
|---|---|
| Stateful primary unreachable, or a process's store connection down (`backend_store_connected` 0) | The only failures needing a human |
| A gateway's subscription down (`backend_gateway_store_subscribed` 0), or pushes no gateway heard rising (`backend_platform_pushes_unheard_total`) | Every push to that gateway's lobbies is lost: a match's ask, a tournament's call, a notice ([03 §5](../detailed-design/03-gateway.md#5-push-routing)) |
| Results backlog growing steadily | Rewards silently not arriving |
| A result set aside (dead list above 0) | A player's pay and rating did not arrive; copy it out first ([§3](#3-per-component-procedures)) |
| Worker failures rising for minutes | An entry the worker cannot apply and does not recognise is retried, not set aside ([05 §4](../detailed-design/05-worker-and-events.md#4-failure-handling)) |
| A result lost to the stream's trim (above 0) | It failed for a whole day and nothing holds it any more ([§3](#3-per-component-procedures)) |
| Results not confirmed by the `events` replica, rising | The replica is down or behind: until it is back, a result lives on one machine ([Q-11](../requirements/01-scope-and-nfrs.md#7-open-questions)) |
| A MySQL replica more than 30 s behind for two minutes (`backend_mysql_replica_lag_seconds`), or not readable for two minutes (`backend_mysql_replica_up` 0) | A promotion would lose that much; a replica that stopped long ago is a failover that loses days ([architecture/02 §7](../architecture/02-availability.md#7-a-replicas-health-measured-designed-2026-10-02-plan-item-59)) |
| A store with no replica connected for a minute (`backend_store_replicas`, `backend_events_store_replicas` 0), or one silent for 30 s (`…_replica_ack_seconds`) | Losing that store's primary would lose what it holds since: sessions and tickets, or results not yet applied |
| The ledger check found a mismatch, or has not run for two days | Coins or gems moved without a ledger row ([§3](#3-per-component-procedures)) |
| The password hasher's line full (168) | Logins are being turned away as busy |
| Arena restart rate above baseline | A crash loop hiding behind automatic restarts |
| Tick p99 above 15 ms for five minutes (NFR-1b; [01 §7](01-deploy.md#7-installing-a-machine)) | The room is falling behind before players complain; at 40 ms, the deadline, ticks overrun |
| No dump for 26 hours, no proof for 8 days, or nothing copied off the site for 26 hours once a host is named (`backend_backup_succeeded_timestamp_seconds{kind}`, NaN for a step that never succeeded); or the last run of any failed (`backend_backup_failed{kind}` 1) | A backup that stopped is found when it is needed; a proof that failed is a backup that does not restore (06 §10, D-71). Read why in `backup_run.detail`, then the unit's journal. The weekly proof and the monthly one from off the site are both `proof` |
| The binlog stream stopped (`systemctl is-active backend-binlog-stream` on the backup machine) | It has no metric of its own: otherwise it shows only as the next copy off the site or the next weekly proof failing, up to a week later; meanwhile a restore to a moment reaches only as far as the copies got, or to a dump's own moment ([01 §10](01-deploy.md#10-copies-off-the-databases-machine)) |
| The last proof's restore past 2 hours (`backend_backup_restore_seconds` above 7 200) | Half the 4-hour objective for a last-resort restore: physical backups are due, as designed (06 §9, §10, D-72) |
| `match_player` past 500 million rows (`backend_mysql_table_rows`), or a retention run past 30 minutes (`backend_worker_retention_seconds` above 1 800) | The purge, measured at 11 600 rows a second, no longer keeps up with a day; re-measure with `tools/PurgeBenchmark` on these disks before anything else (D-72) |
| Disk on any machine | Spool files and GC logs grow quietly |
| A certificate within 14 days of expiry (nginx's or an arena's) | Renewal has been failing for two weeks; at expiry every device refuses logins, purchases or matches ([01 §11](01-deploy.md#11-the-certificate)) |

**Investigate with:**

- Tick histograms per room, and players per room.
- Bytes/s by traffic profile — the NFR-2 budget is the thing most likely to
  drift as content is added: `rate(backend_arena_snapshot_bytes_total[5m])`
  over `backend_arena_clients`, per profile. A rising share of clients at
  `saver`, or `profile_steps_total{direction="down"}` climbing, is players'
  networks struggling, or the stream having grown past what they carry.
- GC pause histogram per process. With ZGC anything above a millisecond is
  worth understanding.
- j-redis `INFO` (ops/s, `used_memory` against `maxmemory`); the result stream, `XLEN s:match-result` and the group's lag and pending (`XINFO GROUPS s:match-result`); and the lists beside it (`LLEN q:match-result`, the inbox, `…:dead`, `…:deferred`).
- MySQL slow query log.

## 6. Routine

| When | Task |
|---|---|
| Daily | On the backup machine, `systemctl status backend-binlog-stream`: the stream records nothing, so nothing else says it stopped (§5). Until alert rules read the backups' metrics (§5), read them by hand: each step's last success and last outcome, `SELECT kind, MAX(finished_at), MAX(IF(ok, finished_at, NULL)) FROM backup_run GROUP BY kind` on the primary (kind 1 the dump, 2 a proof, 3 the copy off the site), or the worker's `backend_backup_*` (01 §7). The store is not copied on a timer ([D-31](../architecture/03-decision-log.md#d-31--the-store-is-not-copied-off-its-machine-on-a-timer-before-replication)); `backup-store.sh` is for a copy by hand, before an upgrade. Log rotation, disk check |
| Weekly | Restart arenas one at a time during low traffic, which is also when they pick up a renewed certificate. A few minutes before, tell the players: `POST /admin/notice {"text", "reason"}` reaches everyone in the lobby ([04 §10](../detailed-design/04-platform-services.md#the-third-slice-a-notice-to-the-lobby-designed-2026-10-01-plan-item-44)). A stop drains ([01 §8.6](../detailed-design/01-arena.md#86-draining-an-arena-designed-2026-09-29-plan-item-7)): public players go back to the lobby at once with their results, made matches play out, so `systemctl stop` may take up to eleven minutes; the journal says `drained in N s`, or `the drain ran out` if a match was cut short (paid, not rated). Review `WARN` counts; `install-certificate.sh --check` on each machine |
| Per release | Rolling deployment ([01-deploy §5](01-deploy.md#5-rolling-deployment)) |
| Weekly, by itself | **The restore proof** (`backend-restore-proof.timer`, Sundays 04:00 UTC): the newest dump and the copies restored onto the scratch server, into `backend_proof`, and checked; its outcome recorded in `backup_run` whichever it is. Its alert, or the daily check above, is the check. |
| Monthly | **The restore drill** (below), by hand, with its exact counts, at a quiet hour; from the copy off the site once there is one (`fetch-offsite.sh`). The store's drill on each store's primary. The proof from off the site also runs by itself, the first Sunday at 05:00 UTC (`backend-restore-proof-offsite.timer`), into `backend_proof_offsite`, never at the same time as the weekly one. |
| Monthly, by itself too | **Security fixes for what the release carries** (D-77): the operating system's updates no longer reach the Java runtime, MySQL, nginx or the OpenSSL compiled into it. Read the advisories for Temurin 21, MySQL 8.4, nginx and OpenSSL 3.5 (the versions are the release's `VERSIONS.md`); a fix is a new version in `vendor/` (`vendor/VERSIONS.md`, "Moving a version") and a release, rolled out as §5 of the deploy document says. |
| Quarterly | **Rehearse a failover.** A promotion procedure that has never been run is a hypothesis. |

### Recovering to a moment

A mistake made at a known minute, an operator's `UPDATE` without its `WHERE`,
is undone from the copies, not by a promotion: the replica has replayed it too.

1. Find the moment, in UTC, just before it: the statement's time in the
   general or slow log, the admin audit, or the first error it caused.
2. On the backup machine, as root (the dumps and copies are `backend-backup`'s,
   0600), restore the newest dump taken before it, carried to that moment, onto
   the scratch server, with `SOURCE_CNF` the backup account's option file and
   `SCRATCH_CNF` the scratch server's (01 §10):
   ```
   SOURCE_CNF=... SCRATCH_CNF=... STOP_AT='2026-10-04 14:02:59' \
       restore-drill.sh /var/lib/backend/mysql-dumps/<dump>.sql.gz backend backend_before /var/lib/backend/binlog
   ```
   It checks that nothing at or after the moment is in the copy, and that the
   ledger reconciles.
3. Take from the copy what the mistake changed, and write it back to the
   primary as ordinary statements, logged and replicated. If the mistake was
   wide, a whole table: the scale is the operator's decision, and the platform's
   writes since the moment are in the primary only.

### A last-resort restore, with no source left

When no server holding the database is left (A and C both lost, and the
backup machine's copies with C), the copy off the site is what remains, and
there is no source to read a position or a row count from. On a new MySQL
server set up as [01 §9](01-deploy.md#9-mysql) says, as root, with the key
from where it is kept off the machines (01 §10):

```
OFFSITE_TARGET=<user@host:dir> OFFSITE_KEY=<the key file> OFFSITE_SSH="ssh -i <its key>" \
    fetch-offsite.sh /var/tmp/restore
NO_SOURCE=1 SCRATCH_CNF=<option file for the new server> \
    restore-drill.sh /var/tmp/restore/dumps/<newest>.sql.gz backend <database> /var/tmp/restore/binlog
```

`NO_SOURCE=1` reads nothing from a source: the copies are replayed to the end
of the newest one, and only the checks that need no source are made, the
ledger's reconciliation among them. `<database>` must not exist yet on the new
server (`backend`, for the production name): with no source to compare
against, nothing is dropped, and a name that exists is refused (O-19). What comes back is as old as the copy off
the site's last hourly run. The restore and the replay are the recovery time
that the 4-hour objective watches (§5, D-72).

### If the proof fails

A proof records every outcome: a failure's `backup_run.detail` quotes the lines
of its log that say why (up to three), and the unit's journal has the rest.
The newest proofs: `SELECT finished_at, ok, detail FROM backup_run WHERE kind =
2 ORDER BY id DESC LIMIT 2`. One from off the site starts `from off the site:`.

- **A gap in the copies, or copies that never reached the primary's
  position**, is the stream's: check `backend-binlog-stream` and start a dump
  at once; it is the new starting point.
- **A count or reconciliation that differs** is the backup's own: keep the
  failed copy on the scratch server (`backend_proof`, or
  `backend_proof_offsite` for the monthly one), and run the drill by hand from
  the dump before, to learn which one is wrong.
- **From off the site, a fetch that failed** (ssh or rsync refused, `bad
  decrypt` from a wrong key) is quoted the same way, since the fetch's errors go
  into the proof's log (O-15). A wrong key is the one that matters most: the
  copy off the site cannot be read without the right one, which is kept off the
  three machines (01 §10).

### The restore drill

**Run it the way a real recovery would go**: from the backup machine's copies,
onto a server that is not the database's
([01 §10](01-deploy.md#10-copies-off-the-databases-machine)).

```
SOURCE_CNF=<option file for the primary>  SCRATCH_CNF=<option file for a scratch server> \
    restore-drill.sh /var/lib/backend/mysql-dumps/<newest>.sql.gz backend backend_drill /var/lib/backend/binlog
```

The primary is read only for its position and its row counts. The dump is
loaded onto the scratch server and carried forward through the **copied**
binlogs, never the primary's own. It waits for the copies to reach the
primary's position and reports how long that took, which is the recovery
point: 0.0 s when rehearsed. It refuses, before loading anything, copies that
do not reach back to the dump's file or that have a gap: in a real recovery
there is no primary left to compare with, and a missing file would otherwise be
skipped without a word. Rehearsed on 2026-09-26 with the full stack writing to
the primary: 40 matches, 20 of them after the dump, restored onto an empty
second server, every table equal and the ledger reconciled.

**If the stream reports that the server no longer has its file**, the copies
have a gap no restart fills: the stream was down longer than the server keeps
binlogs. Move the copies aside, restart the stream (with no copies it starts
from the oldest binlog the server has), and start `backend-mysql-backup` at
once: that dump is the new earliest point a restore can reach.

The form without `SOURCE_CNF`, `SCRATCH_CNF` and a copies directory is the
drill as first written: a scratch database on the primary's own server, replayed
from its own binlog. It still works, and it is what the account below describes.

`backend/scripts/backup-mysql.sh` takes the nightly dump, as the `backup` user
with the grants in [01 §9](01-deploy.md#9-mysql), into files only its own user,
`backend-backup`, can read (0600 in 0700 directories):
`--single-transaction` (one snapshot, no table locks), with the binlog position
it was taken at, and with no `USE` statement, so it restores under any name.
`backend/scripts/restore-drill.sh <dump> <source> <scratch>` restores it into a
scratch database on the same server. It then replays the binlog from the
dump's position to the moment the drill began, and checks the result. Every
table's row count must equal the source's, which is exact only if nothing
writes meanwhile. The ledger must reconcile with every balance. The scratch
database is left for inspection; drop it afterwards with
`SET sql_log_bin = 0` in the same session.

Run for real on 2026-09-26. A dump of 511 matches, 30 more results written after
it, and the restore came back with **541**: the 30 through the binlog. Every
count matched, 0 reconciliation mismatches. A platform started against the
restored copy passed Flyway's validation of all 3 migrations and served a real
login. Dump 0.3 s, restore 1.0 s, replay 0.4 s, at development size. **Time
them again at production size**; the restore time is the recovery time.

**The first three attempts failed, each silently, and each would in production:**

- **The replay replayed nothing.** `mysqlbinlog` applies `--rewrite-db`
  *first*, then filters `--database` by the *new* name. Filtering on the
  source's name dropped every event without an error. The drill caught it:
  481 matches restored against 511.
- **The restore wrote itself into the binlog.** Loading a dump on a server
  with binary logging on logs every statement. A later replay across that
  stretch re-ran the load, including its `DROP TABLE`, which wiped the rows
  replayed a moment before, again without an error. On a primary, it would
  also have **replicated a whole scratch database to every replica**. Every
  drill session now runs with `sql_log_bin = 0`, and the replay stops at the
  position captured before the drill began.
- **The script could not read the dump's position.** MySQL 8.0 writes
  `MASTER_LOG_FILE`, 8.2 and later `SOURCE_LOG_FILE`. Both are read now; and
  the source's own position is read with `SHOW BINARY LOG STATUS` where a
  server no longer has `SHOW MASTER STATUS` (8.4).

**The store** matters less than it did: the boards come back from MySQL
(`backend-leaderboard-rebuild`), and what its disk alone holds is the results
published and not yet consumed, and the dead list (copied out when it alerts,
§3). No copy goes off its machine on a timer ([D-31](../architecture/03-decision-log.md#d-31--the-store-is-not-copied-off-its-machine-on-a-timer-before-replication));
each store's replica, on another machine, holds what one would
([01 §6](01-deploy.md#6-still-to-be-written)). Both scripts run on the store's
own machine, as root (they read its data directory and the password file), and
reach it on `127.0.0.1` (01 §3). For `events`, give its port, and its password
file in `BACKEND_STORE_PASSWORD_FILE` if it has its own.
`backend/scripts/backup-store.sh <data-dir> <out>
[port]` follows j-redis's own procedure (j-redis-service `docs/08` §12): stop
automatic rewrites, compact, copy the manifest first and then every file it
names, and put the setting back even if the copy fails. That is consistent
because files are deleted only when a rewrite commits.
`backend/scripts/store-restore-drill.sh <backup> <source-port> <scratch-port> session|events`
starts a second server on a copy and compares what matters, each instance's
own keys, named by the fourth argument: on `session`, the key count, the
all-time board member by member with scores, and the names; on `events`, the
key count, the result stream and its group's pending entries, the inbox, the
dead list and the deferred list (O-20). Run it on each store's primary, with
that store's backup.

Run for real on 2026-09-26, the backup taken while the store was serving: 64
keys, 20 board members with every score identical, 20 names, empty queues on
both sides. Loaded in 1.6 s. Two details a naive script gets wrong, both
handled. The scratch copy holds **live session tokens**, so it gets a random
password in a `0600` config file, not on a command line. And the source's
password goes to the client on standard input, not `-a`, which every local
user can read in `ps`.

## 7. Still to be written

| Item | Blocked by |
|---|---|
| Exact promotion commands for j-redis | Written 2026-09-29 (§2), rehearsed in the failover drill |
| MySQL promotion script and fencing | Written 2026-09-29 (§2), rehearsed killed and handed over; rebuilding the old primary and moving back, 2026-09-30 |
| Arena drain command and expected timings | Built 2026-09-29: the drain is `systemctl stop`, and its timing is the weekly row above |
| Admin API procedures: reload config | Ban, suspend, unban, kick, the arenas, their rooms and closing one are built (§3a); reloading is a restart |
| Alert thresholds with real numbers | Q-3 — nothing is measured on production hardware yet |
