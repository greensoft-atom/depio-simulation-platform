# Monitoring and alerts

What watches the site, what it alerts on, where the alerts go, and how to read and silence them. The
watching is the backend's own ([D-86](../architecture/03-decision-log.md#d-86--monitoring-is-the-backends-own-a-watch-in-every-platform-the-runbooks-rules-in-code-alerts-in-the-store-sent-by-webhook)):
no third-party service, installed or bundled ([C-6](../requirements/01-scope-and-nfrs.md#3-constraints)).
The design is [04 §12](../detailed-design/04-platform-services.md#12-the-watch-monitoring-and-alerts-designed-2026-10-24-plan-item-100);
what each alert means and what to do is the [runbook](02-runbook.md#5-what-to-watch).

## 1. What watches

**A watch in every platform process.** Every 15 s it reads:
- **this machine's processes' metrics** on loopback, the platform's own included: the ports
  `BACKEND_METRICS_ADDR` gives each ([05 §2](05-ports-and-firewall.md#2-every-port-in-production));
- **this machine**: the free space of each file system the release writes to, its memory
  (`MemAvailable`), its load and cores;
- **the other machines' platforms**, by `GET /health` on their private address and 8080, a port already
  open to the other two machines: a machine lost is seen by the two others, and no port is added.

The runbook's §5 is the rules, one each with its threshold and its wait (04 §12 has the table). What
fires is written to the session store, where every machine's admin API lists it, to the platform's
journal, and to the webhook when one is named (§4). Three watches, so losing a machine loses no
watching; an alert the two others both raise (`PeerUnreachable`) is sent once.

## 2. Settings

In `/etc/backend/platform.env`, read at the platform's start; a setting it cannot use refuses the start
(exit 2) before the platform serves:

| Setting | What | Example on A |
|---|---|---|
| `BACKEND_WATCH_TARGETS` | this machine's processes' metrics, `job=host:port` pairs; `arena` and `worker` repeat; unset: the watch is off, and the log says so | `platform=127.0.0.1:9101,gateway=127.0.0.1:9103,arena=127.0.0.1:9110,arena=127.0.0.1:9111` |
| `BACKEND_WATCH_PEERS` | the other machines' platforms, `name=host:port` | `B=10.0.0.2:8080,C=10.0.0.3:8080` |
| `BACKEND_WATCH_DISKS` | the paths whose file systems are watched; default `/,/var/lib,/var/log,/opt/backend` | the default |
| `BACKEND_WATCH_MACHINE` | this machine's name on its alerts; default the hostname | `A` |
| `BACKEND_ALERT_WEBHOOK_URL`, or `_FILE` | where alerts are sent (§4); unset: nowhere but the store, the admin API and the journal | `https://chat.example.test/hooks/…` |
| `BACKEND_ALERT_REPEAT_HOURS` | how often a firing alert is sent again, 1 to 24; default 4 | `4` |

On B the arenas are `9110`, `9111` and `9113`, the peers `A=10.0.0.1:8080,C=10.0.0.3:8080`; on C the arenas
`9110` and `9111`, the workers `worker=127.0.0.1:9102,worker=127.0.0.1:9112`, the peers A and B. A process
added to a machine, or a port moved, is a change here and a platform restart
([07](07-start-stop-restart.md)).

## 3. The alerts

Every alert is `severity: page`: each needs a person. The names, from 04 §12's table; the meaning and
the action are the runbook's:

| Alert | It means | Runbook |
|---|---|---|
| `TargetDown` | a process's metrics cannot be read: it is down, or its port is wrong | [§5](02-runbook.md#5-what-to-watch) |
| `PeerUnreachable` | another machine's platform cannot be reached: the machine, its platform or the private network | [§1](02-runbook.md#4-whole-machine-loss) |
| `StoreConnectionDown`, `EventsStoreConnectionDown`, `StoreWithoutReplica`, `EventsStoreWithoutReplica`, `StoreReplicaSilent`, `EventsStoreReplicaSilent` | a store lost, or its replica | [§2](02-runbook.md#2-stateful-primary-failure) |
| `MysqlReplicaLagging`, `MysqlReplicaDown` | the MySQL replica behind, or unreadable | [§2](02-runbook.md#2-stateful-primary-failure) |
| `GatewaySubscriptionDown`, `PushesUnheard` | pushes to a gateway's lobbies are lost | [§5](02-runbook.md#5-what-to-watch) |
| `ResultSetAside`, `ResultsBacklogGrowing`, `WorkerFailing`, `ResultTrimmedUnapplied`, `ResultsUnreplicated`, `TournamentsStalling` | the result pipeline | [§3](02-runbook.md#3-per-component-procedures) |
| `LedgerMismatch`, `LedgerCheckStale` | the ledger's nightly check | [§3](02-runbook.md#3-per-component-procedures) |
| `HasherLineFull`, `TickSlow`, `ArenaCrashLoop` | the platform's logins turned away; a room's tick; an arena restarting | [§5](02-runbook.md#5-what-to-watch) |
| `DiskFilling`, `DiskFillsWithinAWeek`, `MemoryLow` | the machine | [§5](02-runbook.md#5-what-to-watch) |
| `BackupDumpStale`, `BackupProofStale`, `BackupOffsiteStale`, `BackupFailed`, `RestoreTooSlow` | the backups | [§5](02-runbook.md#5-what-to-watch) |
| `TableTooBig`, `RetentionSlow` | growth | [§5](02-runbook.md#5-what-to-watch) |
| `CertificateExpiring` | a certificate within 14 days of expiry | [06 §10](06-certificates.md#10-when-something-goes-wrong) |

An alert's labels say where: `machine`, `job` and `instance` for a process's, `mount` for a disk's, `room`
for a tick's, `peer` for a machine's, `kind` for a backup step's.

## 4. Where alerts go

**The webhook.** With `BACKEND_ALERT_WEBHOOK_URL` set, the platform `POST`s JSON when an alert fires or
resolves, and again every `BACKEND_ALERT_REPEAT_HOURS` while it fires:

```json
{"status": "firing", "machine": "A", "alerts": [
  {"name": "TargetDown", "labels": {"instance": "127.0.0.1:9102", "job": "worker", "machine": "A"},
   "summary": "A process's metrics cannot be read: it is down, or its port is wrong",
   "runbook": "docs/operations/02-runbook.md#5-what-to-watch", "since": "2026-10-24T14:02:15Z"}]}
```

A receiver that does not answer 2xx within 10 s is tried once more, and the alerts are sent again next
round (15 s) until one answers; the failures are counted (`backend_watch_notifications_total{outcome="failed"}`).
**An alert is sent once for the site**: the three platforms share the store, and the first to claim an
alert sends it. Most chat tools and paging services take such a webhook; **which one is the owner's
([Q-60](../requirements/01-scope-and-nfrs.md#7-open-questions))**, and until it is named alerts go
nowhere but the store, the admin API and the journal, and the runbook's daily checks stand.

**The journal**, on the machine that raised it: `ALERT firing TargetDown {instance=…, job=worker,
machine=A}: A process's metrics cannot be read … (docs/operations/02-runbook.md#5-what-to-watch)` and
`ALERT resolved …`, at WARN, in `journalctl -u backend-platform`.

## 5. Reading and silencing alerts

Through the admin API ([api/03](../api/03-admin-api.md)), on any machine, over an SSH tunnel:

```bash
ssh -L 9120:127.0.0.1:9120 10.0.0.1
curl -s -H "Authorization: Bearer $(cat admin-token)" http://127.0.0.1:9120/admin/alerts
```

```json
{"alerts": [{"name": "TargetDown", "labels": {"instance": "127.0.0.1:9102", "job": "worker", "machine": "C"},
  "summary": "…", "runbook": "docs/operations/02-runbook.md#5-what-to-watch",
  "since": "2026-10-24T14:02:15Z", "silenced": false}]}
```

Every machine's alerts, whichever platform answers: they are in the shared store. An alert whose
platform has stopped disappears within a minute, while the two other machines raise `PeerUnreachable`
for it.

**What each watch last saw**, `GET /admin/watch`: every machine's last round, newest first, with each
target's and peer's `up`, the disks' free space and size, the memory, the load and cores, the round's
time and the alerts firing; the site's state from one machine, with no metrics port to read
([api/03](../api/03-admin-api.md)). A machine whose watch has stopped drops out of the list within a
minute.

```bash
curl -s -H "Authorization: Bearer $(cat admin-token)" http://127.0.0.1:9120/admin/watch
```

**A silence**, for a maintenance you know will fire an alert (MySQL restarted at a quiet hour,
`MysqlReplicaDown`; a worker upgraded, `TargetDown`): the alert stays listed, marked `silenced`, and is
not sent.

```bash
curl -s -X POST -H "Authorization: Bearer …" -H 'Content-Type: application/json' \
    -d '{"alertname":"MysqlReplicaDown","hours":1,"reason":"restarting MySQL on A"}' http://127.0.0.1:9120/admin/silences
curl -s -H "Authorization: Bearer …" http://127.0.0.1:9120/admin/silences
curl -s -X DELETE -H "Authorization: Bearer …" http://127.0.0.1:9120/admin/silences/MysqlReplicaDown
```

A silence is by the alert's name, for 1 to 168 hours, with a reason, and is audited as every admin call
is (D-30); it lapses by itself.

## 6. What it costs, and when it fails

- **A round** is one HTTP request a target on loopback, the targets and the peers read at once on virtual
  threads so that a round is bounded by the 2 s timeout and not by how many are down, and a few system
  calls: 0.15 s with four targets on the drill's stack (§7); the series kept are the rules' alone, an
  hour of each. Its own metrics: `backend_watch_scrapes_total{outcome}`,
  `backend_watch_notifications_total{outcome}` (`sent`, `failed`, `duplicate`, `silenced`),
  `backend_watch_alerts_firing{machine}`, `backend_watch_round_seconds`.
- **The watch's platform stops**: its alerts leave the store within a minute; the other two machines
  raise `PeerUnreachable` for it, and send it once.
- **The session store is down**: alerts are evaluated and logged, not stored or sent (the store is the
  claim); `StoreConnectionDown` fires on every machine's watch and is in each journal.
- **The webhook is down**: tried each round until it answers; the alerts are listed meanwhile.
- **A target's port is wrong** in `BACKEND_WATCH_TARGETS`: `TargetDown` for it, two minutes after the
  start; the fix is the setting and a platform restart.

## 7. Checked as written

On 2026-10-24 (plan item 100):
- **Every rule** fires at its threshold and after its wait, and not before, fed rounds 15 s apart
  (`platform`'s `RulesTest`); **the watch end to end** against scripted targets and a real j-redis: a
  round read, an alert stored, sent, resolved and sent again; a target down and a peer unreachable
  raised by two machines and sent once; silences, the repeat, a dead webhook tried again (`WatchTest`).
- **The live drill**, `WATCH=1 client/headless-drill.sh play`, on the release's stack: the worker
  killed after the scenarios, `TargetDown` listed by the admin API 129 s later (two minutes and a
  round), one notification at the drill's receiver, the `ALERT` line in the platform's log, a round of
  0.15 s with four targets, then silenced through the admin API.
- **Under systemd**, `check-units-el9.sh` in UBI 9.5: the same, listed after 134 s, in the platform's
  journal, and resolved within 15 s of the worker's start.
- **The view** (item 102): the drill's `GET /admin/watch` showed the platform, gateway and arena up and the
  worker down, a round of 0.10 s; four targets that each take a second are read in about one (`WatchTest`).
- **Not checked**: three machines' watches over a real private network, and a real webhook receiver:
  the rehearsal on the owner's machines, once Q-60 names one.
