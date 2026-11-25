# Monitoring and alerts

This page is for operators. It says what watches the site, which alerts it raises, where the alerts go, and how to
read and silence them. The watching is the backend's own code
([D-86](../architecture/03-decision-log.md#d-86--monitoring-is-the-backends-own-a-watch-in-every-platform-the-runbooks-rules-in-code-alerts-in-the-store-sent-by-webhook)):
no third-party monitoring service is installed or bundled
([C-6](../requirements/01-scope-and-nfrs.md#3-constraints)). The design is
[04 §12](../detailed-design/04-platform-services.md#12-the-watch-monitoring-and-alerts-designed-2026-10-24-plan-item-100).
What each alert means, and what to do about it, is in the [runbook §5](02-runbook.md#5-what-to-watch).

## 1. What watches

**Every platform process runs a watch.** Every 15 s, one round of the watch reads:

- **this machine's processes' metrics**, on loopback, the platform's own included: each process serves them on the
  port its `BACKEND_METRICS_ADDR` gives ([05 §2](05-ports-and-firewall.md#2-every-port-in-production));
- **this machine itself**: the free space of each file system the release writes to, its memory (`MemAvailable`),
  its load and its number of cores;
- **the other machines' platforms**, by `GET /health` on their private address and port 8080. That port is
  already open to the other two machines, so no port is added, and a lost machine is seen by the two others.

Each read waits at most 2 s, and all are made at once, so a round takes about 2 s at most, however many targets are
down.

**The rules are the runbook's §5**, written in code (`platform/watch/Rules.java`): every signal there that has a
metric. Each rule has a threshold and a wait: the condition must hold for the wait before the alert fires.
[04 §12](../detailed-design/04-platform-services.md#12-the-watch-monitoring-and-alerts-designed-2026-10-24-plan-item-100)
has the table. A firing alert goes to:

1. the session store, where every machine's admin API lists it;
2. the platform's journal;
3. the webhook, when one is named (§4).

**There are three watches, one on each machine**, so losing a machine loses no watching. An alert that two watches
both raise, such as `PeerUnreachable`, is sent once.

## 2. Settings

The watch's settings are in `/etc/backend/platform.env`. The platform reads them when it starts. A setting it
cannot use makes the platform refuse to start (exit 2), before it serves anything.

| Setting | What | Example on A |
|---|---|---|
| `BACKEND_WATCH_TARGETS` | this machine's processes' metrics, as `job=host:port` pairs; `arena` and `worker` repeat, one pair each; unset: the watch is off, and the log says so | `platform=127.0.0.1:9101,gateway=127.0.0.1:9103,arena=127.0.0.1:9110,arena=127.0.0.1:9111` |
| `BACKEND_WATCH_PEERS` | the other machines' platforms, as `name=host:port` | `B=10.0.0.2:8080,C=10.0.0.3:8080` |
| `BACKEND_WATCH_DISKS` | the paths whose file systems are watched; default `/,/var/lib,/var/log,/opt/backend` | the default |
| `BACKEND_WATCH_MACHINE` | this machine's name on its alerts; default the host name | `A` |
| `BACKEND_ALERT_WEBHOOK_URL`, or `_FILE` | where alerts are sent (§4), an `http` or `https` URL; unset: nowhere but the store, the admin API and the journal | `https://chat.example.test/hooks/…` |
| `BACKEND_ALERT_REPEAT_HOURS` | how often a firing alert is sent again, 1 to 24; default 4 | `4` |

**Each machine's targets and peers**, as the examples set them (the metrics ports are in
[01 §7](01-deploy.md#7-installing-a-machine)):

| Machine | `BACKEND_WATCH_TARGETS` reads | `BACKEND_WATCH_PEERS` |
|---|---|---|
| A | the platform 9101, the gateway 9103, the arenas 9110 and 9111 | `B=10.0.0.2:8080,C=10.0.0.3:8080` |
| B | the platform, the gateway, the arenas 9110, 9111 and 9113 | `A=10.0.0.1:8080,C=10.0.0.3:8080` |
| C | the platform, the gateway, the arenas 9110 and 9111, the workers 9102 and 9112 | A and B |
| the launch's one machine | the platform, the gateway, the arenas 9110, 9111 and 9113, the workers 9102 and 9112 | none |

- **On the launch's one machine**
  ([D-93](../architecture/03-decision-log.md#d-93--the-site-launches-on-one-machine-for-up-to-about-5-000-players))
  every process is this machine's. The workers' metrics carry the backups' state too. The install guide's step 8
  sets these targets, and its check finds every metrics port listening and watched (O-59). **With no peer, nothing
  on the site says that the machine itself is down**: its watch goes down with it, and sends nothing.
- **On two machines, with arenas on B**
  ([D-97](../architecture/03-decision-log.md#d-97--arenas-on-the-standby-three-of-its-own-behind-as-nginx-watched-from-a)):
  A's targets also list B's arenas on B's address (`arena=10.0.0.2:9110`, `arena=10.0.0.2:9111` and
  `arena=10.0.0.2:9113`), so that losing B raises an alert on A. B's platform runs only after A is lost. Its
  targets are set in advance for that time: B's own platform and gateway on loopback, B's arenas, and the workers.
- **A process added to a machine, or a port moved**, is a change to these settings, and then a platform restart
  ([07](07-start-stop-restart.md)).

## 3. The alerts

**Every alert pages**: each one needs a person. The payload has no severity field (§4). The names come from
04 §12's table; what each one means and what to do are in the runbook:

| Alert | It means | Runbook |
|---|---|---|
| `TargetDown` | a process's metrics cannot be read: it is down, or its port is wrong | [§5](02-runbook.md#5-what-to-watch) |
| `PeerUnreachable` | another machine's platform cannot be reached: the machine, its platform or the private network | [§4](02-runbook.md#4-whole-machine-loss) |
| `StoreConnectionDown`, `EventsStoreConnectionDown`, `StoreWithoutReplica`, `EventsStoreWithoutReplica`, `StoreReplicaSilent`, `EventsStoreReplicaSilent` | a store lost, or its replica | [§2](02-runbook.md#2-stateful-primary-failure) |
| `MysqlReplicaLagging`, `MysqlReplicaDown` | the MySQL replica behind, or unreadable | [§2](02-runbook.md#2-stateful-primary-failure) |
| `GatewaySubscriptionDown`, `PushesUnheard` | pushes to a gateway's lobbies are lost | [§5](02-runbook.md#5-what-to-watch) |
| `ResultSetAside`, `ResultsBacklogGrowing`, `WorkerFailing`, `ResultTrimmedUnapplied`, `TournamentsStalling` | the result pipeline | [§3](02-runbook.md#3-per-component-procedures) |
| `ResultsUnreplicated` | results not confirmed by the `events` replica: each lives on one machine until the replica is back | [§2](02-runbook.md#2-stateful-primary-failure) |
| `LedgerMismatch`, `LedgerCheckStale` | the ledger's nightly check | [§3](02-runbook.md#3-per-component-procedures) |
| `HasherLineFull`, `TickSlow`, `ArenaCrashLoop` | the platform turning logins away; a room's tick too slow; an arena restarting again and again | [§5](02-runbook.md#5-what-to-watch) |
| `DiskFilling`, `DiskFillsWithinAWeek`, `MemoryLow` | the machine | [§5](02-runbook.md#5-what-to-watch) |
| `BackupDumpStale`, `BackupProofStale`, `BackupOffsiteStale`, `BackupFailed`, `RestoreTooSlow` | the backups | [§5](02-runbook.md#5-what-to-watch) |
| `TableTooBig`, `RetentionSlow` | the database's growth | [§5](02-runbook.md#5-what-to-watch) |
| `CertificateExpiring` | a certificate within 14 days of its expiry | [06 §10](06-certificates.md#10-when-something-goes-wrong) |

**An alert's labels say where it is**: `machine`, `job` and `instance` for a process; `mount` for a disk; `room`
for a tick; `peer` for a machine; `kind` for a backup step.

## 4. Where alerts go

**The webhook.** With `BACKEND_ALERT_WEBHOOK_URL` set, the platform sends a `POST` with a JSON body when an alert
fires or resolves, and sends it again every `BACKEND_ALERT_REPEAT_HOURS` while it still fires:

```json
{"status": "firing", "machine": "A", "alerts": [
  {"name": "TargetDown", "labels": {"instance": "127.0.0.1:9102", "job": "worker", "machine": "A"},
   "summary": "A process's metrics cannot be read: it is down, or its port is wrong",
   "runbook": "docs/operations/02-runbook.md#5-what-to-watch", "since": "2026-10-24T14:02:15Z"}]}
```

- **A receiver that does not answer** with a 2xx status within 10 s is tried once more. If it still fails, the
  alerts are sent again in the next round (15 s later), and so on until a receiver answers. The failures are
  counted in `backend_watch_notifications_total{outcome="failed"}`.
- **An alert is sent once for the site.** The three platforms share the session store, and the first platform to
  claim an alert there sends it.
- **Which receiver is the owner's choice** ([Q-60](../requirements/01-scope-and-nfrs.md#7-open-questions), held by
  the owner). Most chat tools and paging services accept such a webhook. Until the owner names one, alerts go
  nowhere but the store, the admin API and the journal, and the runbook's daily checks
  ([§6](02-runbook.md#6-routine)) stand.

**The journal**, on the machine that raised the alert. The platform writes a line at WARN when an alert fires, and
another when it resolves. Read them with `journalctl -u backend-platform`:

```
ALERT firing TargetDown {instance=…, job=worker, machine=A}: A process's metrics cannot be read … (docs/operations/02-runbook.md#5-what-to-watch)
ALERT resolved …
```

## 5. Reading and silencing alerts

You read and silence alerts through the admin API ([api/03](../api/03-admin-api.md)), on any machine. It must be
turned on first: `BACKEND_ADMIN_ADDR` (a loopback address, `127.0.0.1:9120` in the examples) and
`BACKEND_ADMIN_TOKEN` in `platform.env` ([runbook §3a](02-runbook.md#3a-the-admin-api)). The install guide leaves it
off.

1. Open an SSH tunnel from your own computer to the machine's admin port:

   ```bash
   ssh -L 9120:127.0.0.1:9120 10.0.0.1
   ```

2. On your computer, with the token in a file named `admin-token`, list the alerts:

   ```bash
   curl -s -H "Authorization: Bearer $(cat admin-token)" http://127.0.0.1:9120/admin/alerts
   ```

   ```json
   {"alerts": [{"name": "TargetDown", "labels": {"instance": "127.0.0.1:9102", "job": "worker", "machine": "C"},
     "summary": "…", "runbook": "docs/operations/02-runbook.md#5-what-to-watch",
     "since": "2026-10-24T14:02:15Z", "silenced": false}]}
   ```

**Every machine's alerts are listed, whichever platform answers**, since they are in the shared store. When a
platform stops, its alerts disappear from the list within a minute, and the two other machines raise
`PeerUnreachable` for it.

**What each watch last saw** is `GET /admin/watch`. It lists every machine's last round, newest first: each
target's and each peer's `up`, the disks' free space and size, the memory, the load and the cores, the round's time
and the alerts firing. It gives the whole site's state from one machine, with no metrics port to read
([api/03](../api/03-admin-api.md)). A machine whose watch has stopped drops out of the list within a minute.

```bash
curl -s -H "Authorization: Bearer $(cat admin-token)" http://127.0.0.1:9120/admin/watch
```

**A silence** is for a maintenance that you know will fire an alert: for example `MysqlReplicaDown` while you
restart MySQL at a quiet hour, or `TargetDown` while you upgrade a worker. A silenced alert stays listed, marked
`silenced`, and is not sent.

```bash
curl -s -X POST -H "Authorization: Bearer …" -H 'Content-Type: application/json' \
    -d '{"alertname":"MysqlReplicaDown","hours":1,"reason":"restarting MySQL on A"}' http://127.0.0.1:9120/admin/silences
curl -s -H "Authorization: Bearer …" http://127.0.0.1:9120/admin/silences
curl -s -X DELETE -H "Authorization: Bearer …" http://127.0.0.1:9120/admin/silences/MysqlReplicaDown
```

A silence names one alert, by its name. It lasts 1 to 168 hours, needs a reason, and ends by itself. Like every
admin call, it is audited (D-30). A name that is not one of §3's alerts is refused (400 `no_such_alert`).

## 6. What it costs, and when it fails

- **A round** is one HTTP request to each target on loopback, plus a few system calls. The targets and the peers
  are read at once, each on a virtual thread, so the 2 s timeout bounds a round, not the number of targets that are
  down. A round took 0.15 s with four targets on the drill's stack (§7). The watch keeps only the series its rules
  read, an hour of each. Its own metrics are `backend_watch_scrapes_total{outcome}`,
  `backend_watch_notifications_total{outcome}` (`sent`, `failed`, `duplicate`, `silenced`),
  `backend_watch_alerts_firing{machine}` and `backend_watch_round_seconds`.
- **The watch's platform stops**: its alerts leave the store within a minute. The other two machines raise
  `PeerUnreachable` for it, and it is sent once.
- **The session store is down**: the alerts are evaluated and logged, but not stored or sent, because the store is
  where a platform claims an alert before sending it. `StoreConnectionDown` fires on every machine's watch and is
  in each journal.
- **The webhook is down**: the alerts are tried each round until it answers. Meanwhile the admin API lists them.
- **A target's port is wrong** in `BACKEND_WATCH_TARGETS`: `TargetDown` fires for it two minutes after the start.
  Fix the setting and restart the platform.

## 7. Checked as written

On 2026-10-24 (plan item 100):

- **Every rule** fires at its threshold and after its wait, and not before, when fed rounds 15 s apart
  (`platform`'s `RulesTest`).
- **The watch end to end**, against scripted targets and a real j-redis (`WatchTest`): a round read; an alert
  stored, sent, resolved and sent again; a target down and a peer unreachable, raised by two machines and sent
  once; silences; the repeat; a dead webhook tried again.
- **The live drill**, `WATCH=1 client/headless-drill.sh play`, on the release's stack: the worker killed after the
  scenarios; `TargetDown` listed by the admin API 129 s later (two minutes and a round); one notification at the
  drill's receiver; the `ALERT` line in the platform's log; a round of 0.15 s with four targets; then the alert
  silenced through the admin API.
- **Under systemd**, by `check-units-el9.sh` in UBI 9.5: the same alert, listed after 134 s, in the platform's
  journal, and resolved within 15 s of the worker's start.
- **The view** (plan item 102): the drill's `GET /admin/watch` showed the platform, the gateway and the arena up and
  the worker down, with a round of 0.10 s. Four targets that each take a second to answer are read in about one
  second (`WatchTest`).
- **Not checked**: three machines' watches over a real private network, and a real webhook receiver. The rehearsal
  on the owner's machines checks them ([09 §3](09-rehearsal.md#3-the-watch-end-to-end)), once Q-60 names a
  receiver.
