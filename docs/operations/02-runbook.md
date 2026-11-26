# Runbook

This runbook is for the operators on call. It says what to do when something breaks, and what to watch so that you
find out before a player tells you. It covers:

- triage: what is actually broken (§1);
- a primary that holds state failing: a store or MySQL (§2);
- each component, one by one (§3), and the admin API (§3a);
- a whole machine lost: on three machines, on the launch's one machine, and on two machines (§4);
- what to watch (§5);
- the routine tasks, and the recoveries from the backups (§6);
- what is still open (§7).

Words such as "fence", "epoch", "drain" and "spool" are defined in the [glossary](../glossary.md). How to start, stop
and restart each service is [07](07-start-stop-restart.md); how the alerts reach you is [08](08-monitoring.md).

**Status: written for everything built** (2026-10-04). The failure model is settled
([architecture/02](../architecture/02-availability.md)), and j-redis has a tested runbook of its own. Every procedure
below has been rehearsed in a drill, unless it says otherwise. Still open: the alerts' thresholds, which wait on
production hardware (§7).

## 1. Triage: what is actually broken

Work down this list. It is ordered by how much each failure hurts, not by how likely it is.

| Symptom | First check | Likely cause |
|---|---|---|
| Nobody can log in | MySQL primary, then j-redis `session` | A stateful primary is down |
| Logins work, no new matches start | j-redis `session`, then `platform` | Matchmaking queues or the allocator |
| Matches start but end immediately | `arena` instances | An arena is crash-looping |
| Play is fine, rewards never arrive | `worker`, then j-redis `events` | Results backlog |
| One third of players dropped at once | Whole machine | See [§4](#4-whole-machine-loss) |
| Everything slow, nothing down | GC logs, then load average | Capacity, or a noisy neighbour |

On the launch's one machine every check is on that machine, and nothing fails over: see §4,
[On the launch's one machine](#on-the-launchs-one-machine-d-93).

The useful property of the design: **a match in progress survives the loss of `platform`, `worker`, MySQL and the
`events` store.** If players are still playing, the problem is not as urgent as it looks.

## 2. Stateful primary failure

Three roles need a person when their primary fails: MySQL, j-redis `session` and j-redis `events`. Promotion is
manual by design ([D-8](../architecture/03-decision-log.md#d-8--failover-is-scripted-not-automatic)).

The procedure has the same shape for all three:

1. **Confirm that the primary is really dead**, not merely slow. Promoting while the old primary is alive is the one
   way to lose data.
2. **Fence it**: stop the unit, and disable it, so that a reboot cannot bring it back as a primary.
3. **Promote the replica**, which raises the replication epoch: the number by which every process tells the newest
   primary.
4. **Repoint the clients**, and confirm that writes succeed.
5. **Build a new replica** from the new primary before you go back to bed. A promoted replica with no replica of its
   own is the next incident.

Step 1 is the one that gets skipped under pressure, and it is the only step that can cause permanent damage.

**On two machines**
([D-96](../architecture/03-decision-log.md#d-96--a-second-machine-is-the-first-ones-standby-the-replicas-and-a-front-ready-to-start)),
every primary is on A and every replica on B. Run each command below on B, with A as the old primary. A's processes
were given both machines' addresses
([10 §3](10-two-machines.md#3-on-a-mysql-and-the-stores-on-the-private-network)), so they follow the promotion by
themselves.

### A j-redis store (built 2026-09-29, D-34)

`session` has its primary on A and its replica on B. `events` has its primary on B and its replica on C
([01 §1](01-deploy.md#1-what-runs-where)). Run the script on the replica's machine, **as root**, because:

- it reads the password from the store's configuration file, `/etc/backend/store-<name>.conf` (`root:jredis`,
  mode 0640);
- after promoting, it rewrites that file, and keeps a copy beside it in `/etc/backend/`
  (`store-<name>.conf.before-promotion-<UTC time>`), where only root may write.

Run as any other user, the script stops at its first read. If it could read the file but not write it, it would
promote and leave `replicaof` in the file, and the next restart would make the new primary a replica again. The
script reaches this machine's server on `127.0.0.1` ([01 §3](01-deploy.md#3-j-redis-built-and-tested)):

```bash
# 1–2. The old primary's machine is gone, or its unit is stopped and disabled, or its port
#      is firewalled. Then:
sudo /opt/backend/scripts/promote-store.sh /etc/backend/store-session.conf <old primary>:6379 --old-is-down
# If the old primary is alive but must hand over (a planned move), it can be demoted instead:
#   sudo /opt/backend/scripts/promote-store.sh /etc/backend/store-session.conf <old>:6379 --demote-old <this machine>:6379
```

For `events`, run the same with its own configuration file and port 6380. The scripts use the release's client,
`/opt/backend/jredis/bin/j-redis-cli`, on `/opt/backend/runtime`; `JREDIS_CLI` names another client.

**What the script refuses** (D-95). The old primary is named as `host:port`.

- With `--old-is-down`, it refuses while the old primary answers as anything but a replica. That includes refusing
  the password (`NOAUTH`): a server that refuses is up.
- With `--demote-old`, it refuses an old primary it cannot reach, since one cut off from this machine may still serve
  the processes on its side. Stop it or cut it off, then use `--old-is-down`.

**What it does**: it promotes (`REPLICAOF NO ONE`: the epoch goes up and is written to disk), takes `replicaof` out of
the configuration file with a copy kept, and prints the new epoch.

**Step 4 is automatic.** Every process was given both addresses, and finds the new primary within a reconnect.
Confirm it:

- `backend_store_connected` (or `backend_events_store_connected`) is back at 1;
- each gateway's `backend_gateway_store_subscribed` is at 1. The gateways' subscriptions carry every push, and they
  follow the primary too: a demoted primary closes them, and one on a lost machine is noticed within about 11 s
  ([03 §5](../detailed-design/03-gateway.md#5-push-routing));
- `PUBSUB NUMSUB push:all` on the new primary counts every gateway. The metric says that the subscription is up; this
  says where it is;
- the store's `INFO replication` shows `role:primary`.

An old primary that comes back on its own is ignored by every process that saw the higher epoch.

**Step 5**, when the old machine is back: put `replicaof <new primary> 6379` (6380 for `events`) and `primaryauth` in
its configuration file **before** you start it. It then takes a full copy and the new epoch, and `ROLE` on it shows
`connected`.

**Rehearsed:**

- **The primary killed**, on 2026-09-29, from the release (`FAILOVER=1 client/headless-drill.sh play duel`): the
  store's primary killed with `-9` after a first run of the scenarios, and the replica promoted by the script. The
  same scenarios passed again against the same processes, each back on the new primary within about six seconds. The
  four results, two from each side of the failover, were applied.
- **A planned handover**, on 2026-10-01 (`FAILOVER=demote client/headless-drill.sh duel notice`): the old primary
  demoted by the script and left running, as step 3's `--demote-old` leaves it. Step 4's confirmation held as
  written above: every process back on the new primary, the gateway's `backend_gateway_store_subscribed` at 1, and
  `PUBSUB NUMSUB` on the new primary counting the gateway's two channels and the arena's. The scenarios passed again
  against the same processes. Before j-redis 2.2.1 the same handover left every subscription on the demoted server,
  and no push or notice arrived (O-6).
- **Under load**, on 2026-09-30 (`BOTS=600:120 FAILOVER=1 client/headless-drill.sh`,
  [architecture/02 §6](../architecture/02-availability.md#6-rehearsed-under-load-designed-2026-09-30-plan-item-23)):
  600 bots playing, the primary killed halfway through, the bots leaving while it was down, and the replica promoted
  20 s later. The procedure above was followed as written, step 4's confirmation included:
  `backend_store_connected` read 0 on `platform` and the arena ten seconds after the kill, and 1 again after the
  promotion. No bot noticed. The 600 results waited in the arena's spool, and were all applied once, the last 76 s
  after the promotion, at one worker's rate (§3).

### MySQL (built 2026-09-29, D-35)

The primary is on A, and its replica on C ([01 §1](01-deploy.md#1-what-runs-where)). Run the script on C, as root,
since the failover account's option file, `mysql-failover.cnf`, is in `/etc/backend/credentials/`
([01 §2](01-deploy.md#2-conventions), [§9](01-deploy.md#9-mysql)):

```bash
# 1–2. The old primary's machine is gone, or mysqld is stopped and disabled, or its port is
#      firewalled from C. Then:
sudo /opt/backend/scripts/promote-mysql.sh <old primary>:3306 --old-is-down
# If the old primary is up and must hand over (a planned move), the script fences it itself:
#   sudo /opt/backend/scripts/promote-mysql.sh <old primary>:3306 --demote-old
```

**First, the script says what a promotion would lose.** It prints the primary's last heartbeat that the replica
applied, and how long ago that was
([architecture/02 §7](../architecture/02-availability.md#7-a-replicas-health-measured-designed-2026-10-02-plan-item-59)).
A promotion would lose everything the primary wrote after that heartbeat.

- **If the heartbeat is older than five minutes, or cannot be read** (the account's grant missing, 01 §9), the script
  refuses, and touches nothing. Compare that time with when the primary was lost:
  - if they agree, the outage is simply that old: run the script again with `--stale-ok` as a third argument, and it
    promotes;
  - if the heartbeat is much older, the replica had stopped receiving or applying before the outage. That stretch is
    lost, unless the old primary's disk can be read (§3).

**With `--old-is-down`**, the script refuses while the old primary answers at all, even with an error that refuses
the account or its TLS: an error from it means the server is up (D-95). Once it promotes, it first waits, up to 60 s,
until the replica has applied everything it received.

**With `--demote-old`**, the script refuses an old primary that does not answer, since that primary may still serve
the processes that reach it. Stop it or cut it off, then use `--old-is-down`. An old primary that answers is fenced,
in this order:

1. `super_read_only`, which stops every write, persisted so that a restart keeps it;
2. a wait, up to 60 s, until this replica has applied every transaction the old primary executed;
3. `offline_mode`, which closes every connection but an administrator's, and refuses new ones.

Without `offline_mode`, each process kept its connections to the old primary, and failed every write on them (defect
[D-29](../defects.md)). Set before the wait, `offline_mode` also closed the replica's own link, and what that link
had not carried yet was lost (O-64). If the wait times out, the script refuses, and leaves the old primary read-only
(`SET PERSIST super_read_only = OFF` there gives writes back). Running it again once the replica has caught up is
safe.

**Then it promotes**: it stops replication, makes the replica writable persistently, raises the epoch, the backend's
and the account service's beside it (its database's own, which the account service follows: O-103), and prints the
epoch with the executed GTID set. Keep that line for the incident's record.

**If a run ends with an error after it has stopped replication**, before its last line, it may have left the epoch
where it was. Compare `SELECT epoch FROM backend.ha_epoch` on both servers. When they are equal, raise it on the new
primary: `UPDATE backend.ha_epoch SET epoch = epoch + 1 WHERE id = 1`.

**Step 4 is automatic.** Every process was given both hosts, and opens each new connection to the writable server
with the highest epoch. Its broken connections are replaced as they are next used. Requests in flight at that moment
fail, and are answered "try again". A primary whose machine vanished, or was cut off by the `DROP` rule, resets
nothing: each statement waiting on it is given up after 30 s, and a new connection's probe passes over it in 2 s
([06 §7](../detailed-design/06-persistence-mysql.md#7-connection-pooling)). Confirm with a login, and with the
worker's results being applied again (`backend_worker_applied_total` rising). What the replica had not received when
the primary died is lost: as much as the replication lag.

**Step 5: the old primary never comes back as it was.** It may hold transactions that the replica never received.
Started as it was, it would be a second primary with an older epoch. Every process that saw the promotion passes over
it, but a process started afterwards with only its address would not.

1. Before you start it again, put `super_read_only = ON` in its configuration, as C's is (01 §9), so that it starts
   read-only.
2. Make it a replica of the new primary exactly as [01 §9](01-deploy.md#the-replica-on-c-plan-item-13-d-35) makes C
   one, with the addresses the other way round. `CLONE INSTANCE` from the new primary replaces its data, accounts
   and GTID history, and its stray transactions with them. It then follows the new primary by GTIDs.
3. Check that `SHOW REPLICA STATUS` shows both threads running.

An old primary that was handed over (`--demote-old`) is still up and fenced: do the same, without the restart first.
It comes back read-only and open, which the probes need.

**The backups, the same night.** The copies were taken on C, which now holds the primary: they would be on the disk
they protect ([06 §10](../detailed-design/06-persistence-mysql.md#10-backup-and-recovery)). So:

1. Install the stream and the dump ([01 §10](01-deploy.md#10-copies-off-the-databases-machine)) on the machine that
   holds the replica: B until the old primary is rebuilt, and the rebuilt machine after. In their option files,
   `host` names the new primary. Use an empty copies directory, and set the old dumps aside. The new primary's
   binlogs are a series of their own, and a dump from the old series beside the new would name another series'
   files. (Since O-66, retention keeps the copies that any remaining dump starts from; but the proof takes the newest
   dump, and the restore takes the one it is given.)
2. Start `backend-mysql-backup` at once. That dump is the earliest point the new series restores to. A point before
   the promotion is restored from the old copies, where they are.
3. Move the proofs, their scratch server and the copy off the site with the stream and the dump (01 §10). Left on C,
   they would prove copies kept on the primary's own disk.

**Moving back**, if the primary belongs on A: once the rebuilt replica has caught up, run the handover the other way,
on A: `promote-mysql.sh <C>:3306 --demote-old`. Then rebuild C the same way, and move the backups with the replica
again. Each promotion raises the epoch.

**Rehearsed:**

- **Killed, and handed over**, on 2026-09-29, from the release, with two MySQL servers of the drill's own
  (`MYSQL_FAILOVER=1` and `MYSQL_FAILOVER=demote client/headless-drill.sh play duel`). The replica applied 30 s late,
  so that the promotion had to wait. Killed with `-9`, the old primary still answered at first, and the script
  refused; then it promoted. Handed over, the script fenced the old primary. Both times the new primary held every
  match the old one had, the scenarios passed again against the same processes, a second run of the script changed
  nothing, and the new primary restarted writable although its command line said read-only.
- **Step 5 and the move back**, on 2026-09-30: the old primary rebuilt by clone, over TLS, in 9 s at development
  size, read-only and open, with every match there; handed back to at epoch 2; and the scenarios passed a third time
  against the same processes.
- **The primary frozen**, on 2026-10-02 (`MYSQL_FAILOVER=freeze client/headless-drill.sh duel`): `SIGSTOP`, so that
  it accepted TCP and answered nothing, as a lost machine does. The script found that it did not answer, and
  promoted. `platform` answered again 11.3 s after the promotion, and the duel passed against the same processes.
  Before the limits of 06 §7 it never did: each new connection's probe waited on the frozen server.
- **Under load**, on 2026-09-30 (`BOTS=600:120 MYSQL_FAILOVER=1`, and `=demote`,
  [architecture/02 §6](../architecture/02-availability.md#6-rehearsed-under-load-designed-2026-09-30-plan-item-23)):
  600 bots playing, MySQL's primary killed halfway through, the script refusing while the old primary still answered,
  the bots leaving while it was down, and the replica promoted 20 s later. No bot noticed. The 600 results waited in
  the stream, and were all applied once, the last 44 s after the promotion. A signed-in request was refused from the
  kill until 4 s after the promotion. Handed over instead, it was never refused.

## 3. Per-component procedures

| Component | Impact | Action |
|---|---|---|
| One `arena` | Its rooms end; up to `MAX_ROOMS` × `MAX_PLAYERS` players (1 800 with the example settings: 12 rooms of 150) go back to the lobby | systemd restarts it, and rooms are made again on demand. Investigate only if it loops. |
| One `gateway` | Its connections drop | Clients reconnect through the front, whose nginx passes over the lost gateway. Restart it; there is no state to recover. |
| One `platform` | Lobby requests in flight fail | It is stateless: restart it. |
| One `worker` | Results back up | Restart it. What it had in flight stays pending for it in the stream: the same instance name takes it up again at once. If the machine is gone for good, any other worker takes it over once it has been idle a minute, so nothing is lost ([05](../detailed-design/05-worker-and-events.md#the-move-onto-streams-designed-2026-09-29-plan-item-10)). Confirm that `backend_worker_pending` and `backend_worker_queue_depth` drain. |
| j-redis `events` | Results stop draining; the arenas spool them to their local disk | Promote its replica ([§2](#2-stateful-primary-failure)). The arenas' spools drain into it, and the workers apply the backlog at their own rate: 600 results took one worker 76 s after a promotion on the development machine (rehearsed under load, [architecture/02 §6](../architecture/02-availability.md#6-rehearsed-under-load-designed-2026-09-30-plan-item-23)). Without a replica: restore the store, then watch the spool files drain, and check the disks on the arenas' machines. A store started empty has no result group: the workers make it again at their next read or re-drive ([defect D-27](../defects.md#3-data-and-the-result-pipeline)). |
| j-redis `session` primary lost | Logins, tickets, matchmaking and every request that needs a session fail until it is back; matches in progress play on (rehearsed under load, [architecture/02 §6](../architecture/02-availability.md#6-rehearsed-under-load-designed-2026-09-30-plan-item-23)) | Promote its replica ([§2](#2-stateful-primary-failure)): sessions, boards and queues are on it, up to the last moment the replica had |
| j-redis `session` lost with its disk, and no replica | Everyone is logged out, and every board is empty | Start it empty; players log in again. Then run `systemctl start backend-leaderboard-rebuild` on one worker machine: the boards come back from MySQL ([05 §8](../detailed-design/05-worker-and-events.md#8-rebuilding-after-a-store-loss)). Rehearsed on 2026-09-26 with the shipped unit: 16 of 16 players, every board and name as before the loss |
| Results set aside (`backend_worker_dead_letter_depth` above 0) | Each entry is a player's match whose pay and rating did not arrive. It lives only in the store: the `events` instance, where there is one ([D-31](../architecture/03-decision-log.md#d-31--the-store-is-not-copied-off-its-machine-on-a-timer-before-replication)) | **Copy the entries off the machine first** (below). Then find out why: the worker's log says `dead-lettering match …`, and the reason. Once a deploy can read them, put them back on the queue. Applying is idempotent, so an entry already applied does nothing ([05 §3](../detailed-design/05-worker-and-events.md#3-the-consumer-loop)) |
| The ledger check found balances it does not explain (`backend_worker_ledger_mismatches` above 0) | Some players' coins or gems moved without a ledger row, or a row was written without its balance. That is a bug in whatever path moved them, since every path goes through one method ([defect D-13](../defects.md#3-data-and-the-result-pipeline), not the decision of that name). The worker's log names the first twenty, each with both balances and both ledger totals (`ledger check: N players' balances differ`) | **Find the cause first**: the ledger rows and `updated_at` of those players say when it happened. Then set each balance to its ledger total, which is the audit trail ([06 §3](../detailed-design/06-persistence-mysql.md#3-schema)), and record what you did: `UPDATE player p JOIN (SELECT player_id, SUM(delta) s FROM ledger WHERE currency = 0 GROUP BY player_id) l ON l.player_id = p.id SET p.coins = l.s WHERE p.id IN (…)`, and for gems the same with `currency = 1` and `p.gems`. The join skips a player with no ledger rows of that currency: set that balance to 0 with a plain `UPDATE`. The next day's check confirms it |
| Tournaments stop advancing (`backend_worker_tournament_failures_total` rising steadily; `TournamentsStalling` fires at more than 5 failures in 30 minutes, for five minutes) | No match is made, decided or paid; the rounds wait | The worker's log names the tournament and the error (`tournament N: its step failed`). Most often MySQL or the store cannot be reached. Every worker tries again every 5 s by itself, and nothing is done twice (D-41): fix what the error names. A match whose worker died between claiming it and writing its tickets cannot be joined: 270 s after the claim, the higher seed advances ([04 §6](../detailed-design/04-platform-services.md#6-tournaments)) |
| Results lost to the trim (`backend_worker_trimmed_unapplied_total` above 0) | A result stayed pending, failing, for longer than the stream keeps one (`BACKEND_RESULT_RETENTION_HOURS`, 24 unless set: D-83), and the trim took it. Its players were not paid, and nothing holds it any more: the arena's spool let it go once the stream had it | The worker's log names it (`result … was trimmed … before anyone applied it`), and `backend_worker_failed_total` will have been rising all that day: find out why it failed. The arena's log of that time names the match, and its players can be made good by hand |
| Scores missing from the boards (`backend_worker_unranked_total` rose during a store outage) | The results are in MySQL, but their scores never reached a board | Run the same command (`systemctl start backend-leaderboard-rebuild`) once the store answers again. It is safe on a healthy store: it only raises scores, and leaves each board's expiry alone |
| MySQL primary lost | No logins, and no request that reads or writes it: purchases, progression, profiles, inventories. Results wait in the stream and are applied once it is back. Matches in progress play on (rehearsed under load, [architecture/02 §6](../architecture/02-availability.md#6-rehearsed-under-load-designed-2026-09-30-plan-item-23)) | Promote its replica ([§2](#2-stateful-primary-failure)): everything committed is on it, up to the replication lag. The processes follow by themselves. |
| MySQL slow | Purchases and progression writes time out | Check for a long transaction before you assume load. |
| The site's certificate close to expiry, or devices refusing the site after a change | At expiry every device that connects with TLS refuses the site: no login, purchase, lobby or match, since the front's nginx ends TLS for all of them. Only a client with no TLS at all, `client-simple-no-tls` through 80 (D-115), is not affected | `install-certificate.sh --check` on the front says what 443 and 9001 serve for each name, the days left, and why a device would refuse it. Issue a new certificate from the backend's root, and install it on every machine. nginx reloads without dropping anyone, and the arenas need nothing ([06 §6](06-certificates.md#6-renewing-every-year), [§10](06-certificates.md#10-when-something-goes-wrong)) |

**The dead list, by hand.** Run these on the store's machine. The password goes to the client on standard input, as
the scripts send it, never with `-a`, which `ps` shows. The function `store` below talks to the store on port 6379.
Where results have a store of their own, `events` on port 6380 (in every shipped layout), the dead list is there:
change the port, and the password file if `events` has its own (the function's comment, `split: events' port,
password`, says so). This was run against j-redis 2.0 on 2026-09-29, and again against 2.1 the same day with the
prompt fix ([O-2](../defects.md#6-operations)): the copy held each entry on its own line, an empty list copied
nothing, and the move put the entries back oldest first, behind what was queued.

```bash
store() { { echo "AUTH $(cat /etc/backend/credentials/store-password)"; cat; } \
    | JAVA_HOME=/opt/backend/runtime /opt/backend/jredis/bin/j-redis-cli --raw -p 6379 \
    | sed -E 's/^([^ ]+:[0-9]+> ?)+//' | sed '/^$/d' | tail -n +2; }   # split: events' port, password
echo "LRANGE q:match-result:dead 0 -1" | store > dead-$(date -u +%Y%m%dT%H%M%SZ).txt
# ...copy that file off the machine. After the fix is deployed:
n=$(echo "LLEN q:match-result:dead" | store)
yes "LMOVE q:match-result:dead q:match-result RIGHT LEFT" | head -n "$n" | store
```

The list the entries go back on, `q:match-result`, has been the inbox since results moved onto a stream (2026-09-29).
Every worker moves it into `s:match-result` at its next re-drive, within 30 s.

### 3a. The admin API

Run these on the machine that runs `platform`, as root, with the secret read from its file
([04 §10](../detailed-design/04-platform-services.md#the-first-slice-designed-2026-09-29-plan-item-7)).

- **It must be turned on.** The admin API is served only when `BACKEND_ADMIN_ADDR` names a loopback address, and
  needs its secret, `BACKEND_ADMIN_TOKEN`. Both are in `platform.env` ([01 §2](01-deploy.md#secrets)). The install
  guide leaves it off. `127.0.0.1:9120` below is the example's address, and the examples read the secret from
  `/etc/backend/credentials/admin-token`, where `platform.env`'s example makes it.
- **Every call needs `Authorization: Bearer <the secret>`**, or it is answered 401.
- **A body is JSON**, of at most 4 KiB.
- **Every call is audited** in `admin_audit`, refused ones too. The few that are not (an unknown path, a wrong
  method, and others) are listed in [api/03 §2](../api/03-admin-api.md#2-rules-for-every-call).

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
| `GET /admin/alerts` | | Every machine's firing alerts, each marked silenced or not ([08 §5](08-monitoring.md#5-reading-and-silencing-alerts)) |
| `GET /admin/watch` | | What each machine's watch last saw ([08 §5](08-monitoring.md#5-reading-and-silencing-alerts)) |
| `GET /admin/silences`, `POST /admin/silences` | `{"alertname", "hours", "reason"}` for a `POST` | The silences; a `POST` keeps one alert unsent for 1 to 168 hours |
| `DELETE /admin/silences/{alertname}` | | The silence lifted |

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

**A season's close** (04 §7, D-63) is the worker's job, within the minute of the season's end. `placedAt`, `paidAt`
and `resetAt` in `GET /admin/seasons` fill in, in that order. If one is still empty after a few minutes, every
worker's season job is failing: its log says why (`season job failed`). The next minute goes on from where it
stopped, paying nobody twice and halving nobody twice. End a season early only for a drill, or to bring a season
onto the calendar. The next season then runs to the next boundary (the first of an odd month, 00:00 UTC), however
near it is.

**A ban or a suspension** ends every session the player has, closes their lobby connection, and takes them out of
any match, at once. **Closing a room and kicking a player** are told to the arenas, and answered 202 with how many
heard: the arena acts on the room's next tick. Every call that changes something needs a reason. To see what was
done, and for which reason:

```sql
SELECT at, action, target, outcome, request FROM admin_audit ORDER BY id DESC LIMIT 20;
```

## 4. Whole-machine loss

On three machines, capacity drops to roughly 30 000 players (N−1: one machine fewer). What to do depends on which
machine was lost:

- **The front, whichever machine it is** (C in the examples,
  [D-82](../architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name)):
  the site's one public address leads nowhere, so nobody reaches the site.
  1. Have the provider forward the address's 80, 443 and 9001 to another machine. That machine's nginx,
     configuration, certificate and firewall are ready, since every machine is set up as the front.
  2. Check from outside:
     `openssl s_client -connect <address>:443 -verify_ip <address> -CAfile ca.pem </dev/null` ends with
     `Verify return code: 0 (ok)`; for the clients without TLS, `curl http://<address>/v1/health` answers 200
     ([11 §9](11-phone-to-backend-routes.md#9-checking-it)).
  3. Then handle the lost machine's own roles, as below.
- **Machine A**, both primaries: follow [§2](#2-stateful-primary-failure) twice, for MySQL and for j-redis `session`.
  This is the worst case. Both primaries sit together so that it is *one* procedure to rehearse. `session`'s replica
  is on B and MySQL's on C, and §2 promotes each. Only if a replica's machine is gone too:
  - `session` is started empty, and the boards are rebuilt ([§3](#3-per-component-procedures));
  - MySQL is brought back from the backup machine's copies, or from the copy off the site if C went too
    ([a last-resort restore](#a-last-resort-restore-with-no-source-left)).
- **Machine B**, the `events` primary, plus three arenas: promote `events`. The matches in B's arenas are lost, and
  their players return to the lobby.
- **Machine C**, the replicas and the workers: no promotion is needed. Whatever its workers had claimed and not
  acknowledged stays pending on the stream, and any other worker claims it once it has been idle a minute
  ([05, the move onto streams](../detailed-design/05-worker-and-events.md#the-move-onto-streams-designed-2026-09-29-plan-item-10)).
  In the examples every worker is on C, so start `backend-worker@c1` and `@c2` on another machine. Until a worker
  runs, results wait in the stream, which keeps them `BACKEND_RESULT_RETENTION_HOURS` (24 unless set). Rebuild the
  replicas when the machine returns.

**A replacement machine** is installed as the others are, and given the site's `ca.pem`, `fullchain.pem` and
`privkey.pem` ([06 §4](06-certificates.md#4-installing-it-on-every-machine)). Nothing is issued for it, since one
certificate serves every machine (D-82). If others could read the lost disk, the site's key went with it: issue a
new certificate ([06 §6](06-certificates.md#6-renewing-every-year)) and install it on every machine.

In all three cases the surviving machines keep serving. The thing to watch is whether the remaining arenas are
oversubscribed. If they are, cap matchmaking rather than let every room overfill.

### On the launch's one machine (D-93)

Nothing fails over: the machine is the site
([availability](../architecture/02-availability.md#on-one-machine-the-launch-d-93)). What to do depends on what was
lost:

- **The machine stopped** (power, a kernel panic, a reboot): start it. Every unit is enabled, and starts at boot.
  Check it as the [install guide's §11](03-install-guide.md#11-check-it) does. Nothing is lost: the stores reload
  their logs, and MySQL its own.
- **MySQL's disk**, with the copies' disk (`/var/lib/backend`) intact: the site refuses logins and everything that
  reads the database. Fit a new disk, then run the restore below. Nothing committed is lost, since the binlog stream
  holds every write to within milliseconds.
- **The machine, for good**: install another by the [install guide](03-install-guide.md) to the end of its §5, and
  move the copies' disk to it. Then run the restore below in place of the guide's §6 database, and go on with the
  guide from §6's backups. If the copies' disk is lost too, use the copy off the site
  ([a last resort](#a-last-resort-restore-with-no-source-left)), once Q-53 names a host. With neither, the database
  is gone.

**The restore**, as root, on the machine. It is checked as written: the install guide's check loses the database's
disk on purpose, and runs this block.

<!-- one-machine-restore -->
```bash
systemctl stop 'backend-arena@*' backend-gateway backend-platform backend-account 'backend-worker@*' backend-binlog-stream backend-mysql
# MySQL made again on the new, empty disk at /var/lib/backend-mysql, as the install guide's §6: its users,
# not the databases, which the restore makes.
install -d -o mysql -g mysql -m 0750 /var/lib/backend-mysql
/opt/backend/mysql/bin/mysqld --defaults-file=/etc/backend/mysql/backend.cnf --initialize-insecure --user=mysql
systemctl start backend-mysql
/opt/backend/mysql/bin/mysql -S /run/backend-mysql/mysqld.sock -u root <<SQL
ALTER USER root@localhost IDENTIFIED BY '$(cat /etc/backend/credentials/mysql-root)';
SQL
/opt/backend/mysql/bin/mysql --defaults-extra-file=/etc/backend/credentials/mysql-root.cnf <<SQL
    CREATE USER 'backend'@'127.0.0.1' IDENTIFIED BY '$(cat /etc/backend/credentials/db-password)';
    GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON backend.* TO 'backend'@'127.0.0.1';
    CREATE USER 'account'@'127.0.0.1' IDENTIFIED BY '$(cat /etc/backend/credentials/account-db-password)';
    GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON account.* TO 'account'@'127.0.0.1';
    CREATE USER 'backup'@'127.0.0.1' IDENTIFIED BY '$(cat /etc/backend/credentials/backup-password)';
    GRANT SELECT, SHOW VIEW, TRIGGER, EVENT, RELOAD, REPLICATION CLIENT ON *.* TO 'backup'@'127.0.0.1';
    CREATE USER 'binlog'@'127.0.0.1' IDENTIFIED BY '$(cat /etc/backend/credentials/binlog-password)';
    GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'binlog'@'127.0.0.1';
SQL
# Each database's newest dump, the backend's and the account service's (D-117), carried through the binlog copies
# to their end, under its production name
for db in backend account; do
    dump=$(ls /var/lib/backend/mysql-dumps/$db-*.sql.gz | sort | tail -1)
    NO_SOURCE=1 SCRATCH_CNF=/etc/backend/credentials/mysql-root.cnf \
        /opt/backend/scripts/restore-drill.sh "$dump" $db $db /var/lib/backend/binlog
done
/opt/backend/mysql/bin/mysql --defaults-extra-file=/etc/backend/credentials/mysql-root.cnf \
    -e "GRANT INSERT ON backend.backup_run TO 'backup'@'127.0.0.1'"
# The old server's copies and dumps set aside: the new server's binlogs are numbered from 000001 again, so the
# stream would write over copies of the same name, and the next dump would prune the new copies by an old dump's.
aside=/var/lib/backend/before-$(date -u +%Y%m%dT%H%M%S)
mkdir "$aside" && mv /var/lib/backend/binlog /var/lib/backend/mysql-dumps "$aside"/
systemctl start backend-account backend-platform backend-worker@c1 backend-worker@c2 backend-gateway \
    backend-arena@a1 backend-arena@a2 backend-arena@a3 backend-binlog-stream
systemctl start backend-mysql-backup          # the new server's first dump: the copies' new starting point
```
<!-- /one-machine-restore -->

`restore-drill.sh` ends by naming every table restored, the newest migration, and, for the backend's database, the
ledger reconciled ([06 §10](../detailed-design/06-persistence-mysql.md#10-backup-and-recovery)); the account service's
has no ledger, and is checked for what holds without one (10 §6.3). Keep the set-aside copies until the
next weekly proof has passed on the new ones.

### On two machines (D-96)

B is A's standby ([10](10-two-machines.md)): its replicas follow A's primaries, and the rest of it is a copy of A's
roles, stopped.

- **A's MySQL or a store lost, with A running**: follow [§2](#2-stateful-primary-failure) on B, with A as the old
  primary. A's processes follow, and A's site goes on.
- **A stopped, and coming back** (a reboot, a short outage): wait for it, as on one machine. A failover costs more
  than minutes of waiting: it ends A's time as the primary for good.
- **B lost, with A running**, and arenas on B ([10 §10](10-two-machines.md#10-arenas-on-b)): there is nothing to do
  at once. B's battles are lost. Their players are sent to A's arenas once B's arenas have left the directory, within
  10 s. A's watch raises `TargetDown` for B's arenas, beside the replicas' alerts. Without B there is no failover:
  bring B back, or make a new B (10 §10's last paragraph).
- **A lost**: B becomes the site, as below. **Fence A first**: have it powered off at the provider, and kept off. If
  it started again, it would be a second site: its stores and MySQL would take writes, and its arenas would announce
  themselves under the names that B's take. The epochs keep every process that saw a promotion off A's servers (§2),
  but not A's own processes.

**The steps on B**, as root, in a shell with the values of the install guide's §2 and of 10 §2. They are checked as
written: `check-two-machines-el9.sh` cuts A off and runs them.

<!-- two-machines-a-lost -->
```bash
# On B, A powered off: the three promotions, each one already made said so and left as it is
/opt/backend/scripts/promote-mysql.sh "$A_PRIVATE:$MYSQL_PORT" --old-is-down
/opt/backend/scripts/promote-store.sh /etc/backend/store-session.conf "$A_PRIVATE:6379" --old-is-down
/opt/backend/scripts/promote-store.sh /etc/backend/store-events.conf "$A_PRIVATE:6380" --old-is-down
```

Then, on B, follow the install guide's [§6, its backups](03-install-guide.md#its-backups-on-this-machine),
[§10](03-install-guide.md#10-start-everything) and [§11](03-install-guide.md#11-check-it), as written there: the
scratch server and the backups' files; every unit started and enabled; then the check, the first dump, and its proof
on B's own copies. The arenas started are those that B has settings for. When B has no arenas of its own, those are
A's. When it has its own (10 §10), they are B's alone, already running, and none of A's.

<!-- on B: the install guide's Its backups; 10.; 11. -->
<!-- /two-machines-a-lost -->

Then the site's address: have the provider forward 80, 443 and 9001 to B, and check from outside as for the front
above. A session made on A goes on, since the session store's replica held it, so players are not asked to log in
again. What is lost: the battles A was running; B's arenas' battles too, when it has arenas (their players came
through A's nginx); and A's arenas' results not yet in the replica (seconds of them).

**A, once repaired, never starts in its old roles.** It is installed again from nothing, as the standby:

1. the install guide to §5;
2. then [10](10-two-machines.md)'s §4 and §5, with the two addresses swapped (`A_PRIVATE` is B's, the primary's,
   and `B_PRIVATE` is A's);
3. a certificate issued again for A's address by MySQL's CA (10 §3's lines for one address), copied with the rest;
4. and B's firewall opened to it (10 §8).

If the primaries belong on A, hand them over at a quiet hour (§2's `--demote-old`, for each of the three), and make B
the standby again the same way. This has not yet been run as written.

## 5. What to watch

Alert on the first group; look at the second when you investigate. Each signal's metric is in
[01 §7](01-deploy.md#7-installing-a-machine). The signals of the first group are the watch's rules
([08](08-monitoring.md), D-86), each with its threshold and its wait, and each row names its alert. Every platform
evaluates the rules every 15 s over its machine's metrics. It lists what fires on the admin API
(`GET /admin/alerts`), writes it to its journal, and sends it to the webhook once the owner names one (Q-60). Until
then, the alerts are read on a machine, and the daily checks in §6 stand.

**Alert:**

| Signal | Why |
|---|---|
| A process's metrics not readable for two minutes (`TargetDown`) | The process is down, or its port in `BACKEND_WATCH_TARGETS` is wrong; the alert's `job` and `instance` labels name it ([08 §6](08-monitoring.md#6-what-it-costs-and-when-it-fails)) |
| Another machine's platform not reachable for two minutes (`PeerUnreachable`) | That machine, its platform, or the private network is down ([§4](#4-whole-machine-loss)) |
| Stateful primary unreachable, or a process's store connection down (`backend_store_connected` 0) for a minute (`StoreConnectionDown`, `EventsStoreConnectionDown`) | The only failures needing a human |
| A gateway's subscription down (`backend_gateway_store_subscribed` 0) for a minute (`GatewaySubscriptionDown`), or pushes that no gateway heard rising (`backend_platform_pushes_unheard_total`, `PushesUnheard`) | Every push to that gateway's lobbies is lost: a match's ask, a tournament's call, a notice ([03 §5](../detailed-design/03-gateway.md#5-push-routing)) |
| Results backlog growing steadily: above 100, and higher than ten minutes before, for ten minutes (`ResultsBacklogGrowing`) | Rewards silently not arriving |
| A result set aside: the dead list above 0 (`ResultSetAside`) | A player's pay and rating did not arrive; copy the entry out first ([§3](#3-per-component-procedures)) |
| Worker failures rising for five minutes (`WorkerFailing`) | An entry the worker cannot apply and does not recognise is retried, not set aside ([05 §4](../detailed-design/05-worker-and-events.md#4-failure-handling)) |
| A result lost to the stream's trim: above 0 (`ResultTrimmedUnapplied`) | It failed for a whole day, and nothing holds it any more ([§3](#3-per-component-procedures)) |
| Results not confirmed by the `events` replica, rising for ten minutes (`ResultsUnreplicated`) | The replica is down or behind: until it is back, a result lives on one machine ([Q-11](../requirements/01-scope-and-nfrs.md#7-open-questions)) |
| A MySQL replica more than 30 s behind for two minutes (`backend_mysql_replica_lag_seconds`, `MysqlReplicaLagging`), or not readable for two minutes (`backend_mysql_replica_up` 0, `MysqlReplicaDown`) | A promotion would lose that much; a replica that stopped long ago is a failover that loses days ([architecture/02 §7](../architecture/02-availability.md#7-a-replicas-health-measured-designed-2026-10-02-plan-item-59)) |
| A store with no replica connected for a minute (`backend_store_replicas`, `backend_events_store_replicas` 0: `StoreWithoutReplica`, `EventsStoreWithoutReplica`), or one silent for 30 s (`…_replica_ack_seconds`: `StoreReplicaSilent`, `EventsStoreReplicaSilent`) | Losing that store's primary would lose what it has held since: sessions and tickets, or results not yet applied |
| The ledger check found a mismatch (`LedgerMismatch`), or has not run for two days (`LedgerCheckStale`) | Coins or gems moved without a ledger row ([§3](#3-per-component-procedures)) |
| The account service's password hasher's line full (168) for a minute (`HasherLineFull`, D-117) | Logins are being turned away as busy |
| A machine's disk above 80 % full for five minutes, or filling at a rate that fills it within a week (`DiskFilling`, `DiskFillsWithinAWeek`); and memory under 10 % free for ten minutes (`MemoryLow`) | A full disk stops MySQL and loses nginx's lines; the front's `access.log` alone grows about 13 GB a day at 10 000 players before its nightly rotation (D-85). The watch reads each machine's file systems and memory itself ([08](08-monitoring.md)) |
| Arena restart rate above baseline: two restarts within an hour (`ArenaCrashLoop`) | A crash loop hiding behind automatic restarts |
| Tick p99 above 15 ms for five minutes (`TickSlow`; NFR-1b; [01 §7](01-deploy.md#7-installing-a-machine)) | The room is falling behind before players complain; at 40 ms, the deadline, ticks overrun |
| No dump for 26 hours (`BackupDumpStale`), no proof for 8 days (`BackupProofStale`), or nothing copied off the site for 26 hours once a host is named (`BackupOffsiteStale`), from `backend_backup_succeeded_timestamp_seconds{kind}`, NaN for a step that never succeeded; or the last run of any step failed, for an hour (`backend_backup_failed{kind}` 1, `BackupFailed`) | A backup that stopped is found when it is needed; a proof that failed is a backup that does not restore (06 §10, D-71). Read why in `backup_run.detail`, then in the unit's journal. The weekly proof and the monthly one from off the site are both `proof` |
| The binlog stream stopped (`systemctl is-active backend-binlog-stream` on the backup machine); no alert | It has no metric of its own. Otherwise it shows only as the next copy off the site or the next weekly proof failing, up to a week later. Meanwhile a restore to a moment reaches only as far as the copies got, or to a dump's own moment ([01 §10](01-deploy.md#10-copies-off-the-databases-machine)) |
| The last proof's restore past 2 hours (`backend_backup_restore_seconds` above 7 200, `RestoreTooSlow`) | Half the 4-hour objective for a last-resort restore: physical backups are due, as designed (06 §9, §10, D-72) |
| `match_player` past 500 million rows (`backend_mysql_table_rows`, `TableTooBig`), or a retention run past 30 minutes (`backend_worker_retention_seconds` above 1 800, `RetentionSlow`) | The purge, measured at 11 600 rows a second, no longer keeps up with a day; measure again with `tools/PurgeBenchmark` on these disks before anything else (D-72) |
| Disk on any machine | Spool files and GC logs grow quietly |
| A certificate within 14 days of expiry, nginx's or an arena's (`CertificateExpiring`) | Renewal has been failing for two weeks; at expiry every device that connects with TLS refuses logins, purchases or matches ([01 §11](01-deploy.md#11-the-certificate)) |

**Investigate with:**

- Tick histograms per room, and players per room.
- Bytes a second by traffic profile. The NFR-2 budget is the figure most likely to drift as content is added:
  `rate(backend_arena_snapshot_bytes_total[5m])` over `backend_arena_clients`, per profile. A rising share of clients
  at `saver`, or `profile_steps_total{direction="down"}` climbing, means that players' networks are struggling, or
  that the stream has grown past what they carry.
- The GC pause histogram per process. With ZGC, anything above a millisecond is worth understanding.
- j-redis `INFO`: operations a second, and `used_memory` against `maxmemory`. When the `events` store is above 80 %,
  give it a bigger heap and `maxmemory`, or keep fewer hours, by
  [04 §3.4](04-scaling-and-performance.md#34-j-redis)'s table.
- The result stream: `XLEN s:match-result`, and the group's lag and pending entries
  (`XINFO GROUPS s:match-result`); and the lists beside it (`LLEN q:match-result`, the inbox, `…:dead` and
  `…:deferred`).
- MySQL's slow query log.

## 6. Routine

| When | Task |
|---|---|
| Daily | Until the owner names where alerts go (Q-60): `GET /admin/alerts` on a machine ([08 §5](08-monitoring.md#5-reading-and-silencing-alerts)), for what the watches are raising, disks included |
| Daily | On the backup machine, `systemctl status backend-binlog-stream`: the stream records nothing, so nothing else says that it stopped (§5) |
| Daily | If the watch is off, read the backups' state by hand (the watch's backup rules read it, §5): each step's last success and last outcome, with `SELECT kind, MAX(finished_at), MAX(IF(ok, finished_at, NULL)) FROM backup_run GROUP BY kind` on the primary (kind 1 is the dump, 2 a proof, 3 the copy off the site), or the worker's `backend_backup_*` (01 §7) |
| Daily | Log rotation and disk space. The store is not copied on a timer ([D-31](../architecture/03-decision-log.md#d-31--the-store-is-not-copied-off-its-machine-on-a-timer-before-replication)); `backup-store.sh` is for a copy by hand, before an upgrade |
| Weekly | Restart the arenas one at a time, when traffic is low; while one drains, the others take every new match. A few minutes before, tell the players: `POST /admin/notice {"text", "reason"}` reaches everyone in the lobby ([04 §10](../detailed-design/04-platform-services.md#the-third-slice-a-notice-to-the-lobby-designed-2026-10-01-plan-item-44)). A stop drains ([01 §8.6](../detailed-design/01-arena.md#86-draining-an-arena-designed-2026-09-29-plan-item-7)): public players go back to the lobby at once with their results, and made matches play out, so `systemctl stop` may take up to eleven minutes. The journal says `drained in N s`, or `the drain ran out` if a match was cut short (paid, not rated) |
| Weekly | Review the `WARN` counts; run `install-certificate.sh --check` on each machine |
| Per release | Rolling deployment ([01-deploy §5](01-deploy.md#5-rolling-deployment)) |
| Weekly, by itself | **The restore proof** (`backend-restore-proof.timer`, Sundays 04:00 UTC): each database's newest dump and the copies restored onto the scratch server, into `backend_proof` and `account_proof`, and checked. Its outcome is recorded in `backup_run`, whatever it is. Its alert, or the daily check above, is the check. |
| Monthly | **The restore drill** (below), by hand, with its exact counts, at a quiet hour; from the copy off the site once there is one (`fetch-offsite.sh`). The store's drill, on each store's primary. The proof from off the site also runs by itself, on the first Sunday at 05:00 UTC (`backend-restore-proof-offsite.timer`), into `backend_proof_offsite` and `account_proof_offsite`, never at the same time as the weekly one. |
| Monthly, by itself too | **Security fixes for what the release carries** (D-77): the operating system's updates no longer reach the Java runtime, MySQL, nginx or the OpenSSL compiled into nginx. Read the advisories for Temurin 21, MySQL 8.4, nginx and OpenSSL 3.5 (the versions are in the release's `VERSIONS.md`). A fix is a new version in `vendor/` (`vendor/VERSIONS.md`, "Moving a version") and a new release, rolled out as §5 of the deploy document says. |
| Yearly, a month before its end | **The site's certificate**: issued again from the backend's root, and installed on every machine ([06 §6](06-certificates.md#6-renewing-every-year)). `install-certificate.sh --check` says the days left, and so does the gateway's `backend_gateway_edge_certificate_expiry_timestamp_seconds`; the watch alerts under 14 days. |
| Quarterly | **Rehearse a failover.** A promotion procedure that has never been run is a hypothesis. |

### Recovering to a moment

A mistake made at a known minute, such as an operator's `UPDATE` without its `WHERE`, is undone from the copies, not
by a promotion: the replica has replayed the mistake too.

1. Find the moment, in UTC, just before it: the statement's time in the general or slow log, the admin audit, or the
   first error it caused.
2. On the backup machine, as root (the dumps and the copies are `backend-backup`'s, mode 0600), restore the newest
   dump taken before the moment, carried forward to that moment, onto the scratch server. `SOURCE_CNF` is the backup
   account's option file, and `SCRATCH_CNF` the scratch server's
   ([01 §10](01-deploy.md#10-copies-off-the-databases-machine)):
   ```
   SOURCE_CNF=... SCRATCH_CNF=... STOP_AT='2026-10-04 14:02:59' \
       restore-drill.sh /var/lib/backend/mysql-dumps/<dump>.sql.gz backend backend_before /var/lib/backend/binlog
   ```
   It checks that nothing at or after the moment is in the copy, and that the ledger reconciles. A mistake in the
   account service's database is undone the same way, from its own dump (`account-<time>.sql.gz`, `account`
   `account_before`): its check is that no account made and no event written at or after the moment is in the copy.
3. Take from the copy what the mistake changed, and write it back to the primary as ordinary statements, logged and
   replicated. If the mistake was wide, a whole table perhaps: the scale is the operator's decision. The platform's
   writes since the moment are in the primary only.

### A last-resort restore, with no source left

This is for when no server holding the database is left: A and C both lost, and the backup machine's copies with C.
The copy off the site is what remains, and there is no source to read a position or a row count from. On a new MySQL
server set up as [01 §9](01-deploy.md#9-mysql) says, as root, with the key from where it is kept off the machines
(01 §10):

```
OFFSITE_TARGET=<user@host:dir> OFFSITE_KEY=<the key file> OFFSITE_SSH="ssh -i <its key>" \
    fetch-offsite.sh /var/tmp/restore
for db in backend account; do                      # the backend's database and the account service's (D-117)
    NO_SOURCE=1 SCRATCH_CNF=<option file for the new server> \
        restore-drill.sh "$(ls /var/tmp/restore/dumps/$db-*.sql.gz | sort | tail -1)" $db $db /var/tmp/restore/binlog
done
```

- **`NO_SOURCE=1` reads nothing from a source.** The copies are replayed to the end of the newest one, and only the
  checks that need no source are made, the ledger's reconciliation among them.
- **The databases must not exist yet** on the new server (`backend` and `account`, the production names). With no source to
  compare against, nothing is dropped, and a name that exists is refused (O-19).
- **What comes back is as old as the copy off the site's last hourly run.**
- **The restore and the replay are the recovery time** that the 4-hour objective watches (§5, D-72).

### If the proof fails

A proof records every outcome. A failure's `backup_run.detail` quotes the lines of its log that say why (up to
three), and the unit's journal has the rest. To see the newest proofs:
`SELECT finished_at, ok, detail FROM backup_run WHERE kind = 2 ORDER BY id DESC LIMIT 2`. A proof from off the site
starts `from off the site:`.

- **A gap in the copies, or copies that never reached the primary's position**, is the stream's fault: check
  `backend-binlog-stream`, and start a dump at once; it is the new starting point.
- **A count or a reconciliation that differs** is the backup's own fault: keep the failed copy on the scratch server
  (`backend_proof` and `account_proof`, or `…_proof_offsite` for the monthly proof), and run the drill by hand from the dump before,
  to learn which one is wrong.
- **From off the site, a fetch that failed** (ssh or rsync refused, or `bad decrypt` from a wrong key) is quoted the
  same way, since the fetch's errors go into the proof's log (O-15). A wrong key is the one that matters most: the
  copy off the site cannot be read without the right key, which is kept off the three machines (01 §10).

### The restore drill

**Run it the way a real recovery would go**: from the backup machine's copies, onto a server that is not the
database's ([01 §10](01-deploy.md#10-copies-off-the-databases-machine)).

```
SOURCE_CNF=<option file for the primary>  SCRATCH_CNF=<option file for a scratch server> \
    restore-drill.sh /var/lib/backend/mysql-dumps/<newest>.sql.gz backend backend_drill /var/lib/backend/binlog
```

- **The primary is read only for its position and its row counts.** The dump is loaded onto the scratch server, and
  carried forward through the **copied** binlogs, never the primary's own.
- **It waits for the copies to reach the primary's position**, and reports how long that took. That time is the
  recovery point: 0.0 s when rehearsed.
- **Before loading anything, it refuses copies that do not reach back to the dump's file, or that have a gap.** In a
  real recovery there is no primary left to compare with, and a missing file would otherwise be skipped without a
  word.

Rehearsed on 2026-09-26, with the full stack writing to the primary: 40 matches, 20 of them after the dump, restored
onto an empty second server, with every table equal and the ledger reconciled.

**If the stream reports that the server no longer has its file**, the copies have a gap that no restart fills: the
stream was down longer than the server keeps binlogs.

1. Move the copies aside.
2. Restart the stream. With no copies, it starts from the oldest binlog the server has.
3. Start `backend-mysql-backup` at once. That dump is the new earliest point a restore can reach.

**The drill as first written** runs without `SOURCE_CNF`, `SCRATCH_CNF` and a copies directory: a scratch database on
the primary's own server, replayed from that server's own binlog. It still works, and it is what the account below
describes.

- `backend/scripts/backup-mysql.sh` takes the nightly dump, as the `backup` user with the grants in
  [01 §9](01-deploy.md#9-mysql), into files that only its own user, `backend-backup`, can read (0600 in 0700
  directories). It uses `--single-transaction` (one snapshot, no table locks), records the binlog position it was
  taken at, and writes no `USE` statement, so it restores under any name.
- `/opt/backend/scripts/restore-drill.sh <dump> <source> <scratch>` restores it into a scratch database on the same
  server. It then replays the binlog from the dump's position to the moment the drill began, and checks the result:
  - every table's row count must equal the source's, which is exact only if nothing writes meanwhile;
  - the ledger must reconcile with every balance.
- The scratch database is left for inspection. Drop it afterwards, with `SET sql_log_bin = 0` in the same session.

**Run for real on 2026-09-26**: a dump of 511 matches, 30 more results written after it, and the restore came back
with **541**, the 30 through the binlog. Every count matched, with 0 reconciliation mismatches. A platform started
against the restored copy passed Flyway's validation of all 3 migrations, and served a real login. The dump took
0.3 s, the restore 1.0 s, the replay 0.4 s, at development size. **Time them again at production size**: the restore
time is the recovery time.

**The first three attempts failed, each without a word, and each would have failed in production:**

- **The replay replayed nothing.** `mysqlbinlog` applies `--rewrite-db` *first*, and then filters `--database` by the
  *new* name. Filtering on the source's name dropped every event, without an error. The drill caught it: 481 matches
  restored against 511.
- **The restore wrote itself into the binlog.** Loading a dump on a server with binary logging on logs every
  statement. A later replay across that stretch re-ran the load, including its `DROP TABLE`, which wiped the rows
  replayed a moment before, again without an error. On a primary it would also have **replicated a whole scratch
  database to every replica**. Every drill session now runs with `sql_log_bin = 0`, and the replay stops at the
  position captured before the drill began.
- **The script could not read the dump's position.** MySQL 8.0 writes `MASTER_LOG_FILE`; 8.2 and later write
  `SOURCE_LOG_FILE`. Both are read now. The source's own position is read with `SHOW BINARY LOG STATUS` where a
  server no longer has `SHOW MASTER STATUS` (8.4).

**The store** matters less than it did. The boards come back from MySQL (`backend-leaderboard-rebuild`). What the
store's disk alone holds is the results published and not yet consumed, and the dead list, which is copied out when
it alerts (§3). No copy goes off its machine on a timer
([D-31](../architecture/03-decision-log.md#d-31--the-store-is-not-copied-off-its-machine-on-a-timer-before-replication)):
each store's replica, on another machine, holds what such a copy would ([01 §6](01-deploy.md#6-still-to-be-written)).

- **Both scripts run on the store's own machine**, as root (they read its data directory and the password file), and
  reach it on `127.0.0.1` ([01 §3](01-deploy.md#3-j-redis-built-and-tested)). Both read the store's password from
  `BACKEND_STORE_PASSWORD_FILE`, which either store needs:
  `BACKEND_STORE_PASSWORD_FILE=/etc/backend/credentials/store-password /opt/backend/scripts/backup-store.sh /var/lib/backend-store/session <out> 6379`.
  For `events`, use port 6380, and its own password file if it has one.
- **`/opt/backend/scripts/backup-store.sh <data-dir> <out> [port]`** follows j-redis's own procedure
  (j-redis-service `docs/08` §12): stop automatic rewrites, compact, copy the manifest first and then every file it
  names, and put the setting back even if the copy fails. That copy is consistent, because files are deleted only
  when a rewrite commits.
- **`/opt/backend/scripts/store-restore-drill.sh <backup> <source-port> <scratch-port> session|events`** starts a
  second server on a copy, and compares what matters: each instance's own keys, named by the fourth argument. On
  `session`: the key count, the all-time board member by member with scores, and the names. On `events`: the key
  count, the result stream and its group's pending entries, the inbox, the dead list and the deferred list (O-20).
  Run it on each store's primary, with that store's backup.

**Run for real on 2026-09-26**, with the backup taken while the store was serving: 64 keys, 20 board members with
every score identical, 20 names, and empty queues on both sides. Loaded in 1.6 s. Two details that a naive script gets
wrong are both handled:

- The scratch copy holds **live session tokens**, so it gets a random password in a `0600` configuration file, not on
  a command line.
- The source's password goes to the client on standard input, not with `-a`, which every local user can read in
  `ps`.

## 7. Still to be written

| Item | Blocked by |
|---|---|
| Exact promotion commands for j-redis | Written 2026-09-29 (§2), rehearsed in the failover drill |
| MySQL promotion script and fencing | Written 2026-09-29 (§2), rehearsed killed and handed over; rebuilding the old primary and moving back, 2026-09-30 |
| Arena drain command and expected timings | Built 2026-09-29: the drain is `systemctl stop`, and its timing is the weekly row above |
| Admin API procedures: reload config | Ban, suspend, unban, kick, the arenas, their rooms and closing one are built (§3a); reloading is a restart |
| Alert thresholds with real numbers | Q-3 — nothing is measured on production hardware yet |
