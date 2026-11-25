# The rehearsal on the three machines, or the launch's one

This page is the checklist for the rehearsal, the first step of the readiness review on the owner's own hardware
([plan item 100's list](../plan.md)). It is for the operators who run it. It lists, in order, every check that this
repository could not run on a development machine, each with its expected outcome and what to record. It adds no
new procedure: each step names the guide that holds the procedure.

Plan a day for it, and keep a quiet hour for the load test. The second half (§6 onwards) can be done on another day.

**Bring:**

- the release `.tar.gz`, built by `./build-offline.sh` ([03 §3](03-install-guide.md#3-build));
- the root's `ca.pem` and, kept apart from the servers, the root's key
  ([06 §2](06-certificates.md#2-making-the-root-once));
- a fourth machine for the bots, which reaches the site from outside, as a player does;
- the webhook's address, if the owner has named one ([Q-60](../requirements/01-scope-and-nfrs.md#7-open-questions));
- this page printed, with §8's table to fill in.

The addresses are the examples': the site's public address `203.0.113.10`, and the machines' private addresses
`10.0.0.1` (A), `10.0.0.2` (B) and `10.0.0.3` (C).

## On the launch's one machine (D-93)

The site launches on one machine
([D-93](../architecture/03-decision-log.md#d-93--the-site-launches-on-one-machine-for-up-to-about-5-000-players)),
and the rehearsal is run there: the sections below, on that machine, with the differences in this table. A second
machine is still needed, for the bots.

| § | On one machine |
|---|---|
| 1 | Install by the install guide's one machine, §1 to §12. There are no other machines, so there is no `curl` to them. The platform's journal says `the watch reads 7 target(s), 0 peer(s)`. `ss -ltnp` shows 80, 443, 9001 and sshd's 22 on every address, and the rest on `127.0.0.1` alone. `df -h /var/lib/backend /var/lib/backend-mysql` shows two disks, not one |
| 2 | As written |
| 3 | As written, on the one machine. Skip the `PeerUnreachable` row: there is no peer |
| 4 | **No failover.** Instead, run the two recoveries the runbook has for one machine ([02 §4](02-runbook.md#on-the-launchs-one-machine-d-93)), before the first player, when nothing can be lost. First, `reboot`, then the install guide's §11: every unit comes back by itself, and a session made before the reboot is still served. Record the time from the reboot to the first login. Second, lose the database's disk: `systemctl stop backend-mysql`, move its directory aside (`mv /var/lib/backend-mysql /var/lib/backend-mysql.before`), and run the runbook's restore. Record the time it took, and the restore's own lines. These two times are NFR-4's real figures on one machine |
| 5 | As written, on the one machine |
| 6 | The bots run on the second machine, at 1 000, 2 500 and 5 000: they need six cores there. Here the platform and the gateway listen on `127.0.0.1` alone. For the run alone: give them the machine's private address too (`PLATFORM_BIND`, `GATEWAY_BIND`, and `PLATFORM_URL` to match); put the bots' machine's address in a zone with 8080 and 8090 and in `BACKEND_TRUSTED_PROXIES`; and restart both. Afterwards, put all of it back and restart them again |
| 7 | `systemctl restart backend-arena@a1` as written. A restart of the platform or the gateway is a few seconds of refused requests here, since there is no other to pass to: record how long |

## 1. Install the three machines

Install each machine by the [install guide](03-install-guide.md), §1 to §12, with the differences its
[three machines](03-install-guide.md#three-machines) table gives. Then, on each machine:

| Check | Expected | Record |
|---|---|---|
| `cat /opt/backend/VERSIONS.md` | the release's commit, its runtime, MySQL, nginx and j-redis | the commit |
| `systemctl status 'backend-*'` | every unit of that machine active ([07 §1](07-start-stop-restart.md#1-on-a-server)'s table); its one-shot services inactive between their timers' runs, and the timers active | |
| `ss -ltnp` | what [05 §2](05-ports-and-firewall.md#2-every-port-in-production) lists for that machine, and nothing else | anything else that listens |
| `firewall-cmd --list-all-zones` | `public`: the services `dhcpv6-client http https`, the port `9001/tcp`, and the SSH rule for the internal network ([05 §5](05-ports-and-firewall.md#5-the-firewall-on-rhel-9)); `backend`: the two other machines' addresses and [05 §5](05-ports-and-firewall.md#5-the-firewall-on-rhel-9)'s ports | |
| from each machine, `curl -s http://10.0.0.X:8080/health` for the two others | `{"status":"ok"}` from both | |
| `journalctl -u backend-platform -n 20` | `the watch reads N target(s), 2 peer(s)…`, and no `ERROR` | |
| `getenforce`, then `ausearch -m avc -ts boot \| grep -c -E 'comm="(java\|mysqld\|nginx)"'` | the owner's mode (`Enforcing` is expected), and `0`: no denial for a backend process. If one is found, record its line, and run `runuser -u <the unit's user> -- /opt/backend/scripts/check-bind.sh <its port>` ([D-88](../architecture/03-decision-log.md#d-88--a-port-the-machine-refuses-is-named-with-its-policy-before-anything-starts)). The units were never run under SELinux enforcing here ([05 §9](05-ports-and-firewall.md#9-what-was-checked-and-what-was-not)); `check-selinux-el9.sh` saw SELinux only for a confined login's binds (D-90) | the mode; any denial, whole |
| `sysctl net.ipv4.ip_unprivileged_port_start` | `1024`. A higher value keeps the units' ports for root, and the units fail with `Permission denied` | the value |

**The private network's speed**, once, from A to C: copy the release tarball with `scp -v` and note its rate, or use
`iperf3` if the owner has it.

**The uplink** ([Q-1](../requirements/01-scope-and-nfrs.md#7-open-questions)): record the provider's figure. A
download of the tarball from the fourth machine through the front's address does not measure it, since nothing
serves files there. The bots' traffic in §6 is the measurement that matters.

## 2. Through the front

Run these from the fourth machine, through the site's public address alone:

| Check | Expected | Record |
|---|---|---|
| `openssl s_client -connect 203.0.113.10:443 -verify_ip 203.0.113.10 -CAfile ca.pem < /dev/null` | `Verify return code: 0 (ok)` and `subject=CN = backend`; for the names, pipe the same to `openssl x509 -noout -ext subjectAltName -enddate` ([06 §4](06-certificates.md#4-installing-it-on-every-machine)) | the certificate's `notAfter` |
| the same on `9001 -servername a1.arena.internal -verify_hostname a1.arena.internal`, for each arena's name | `0 (ok)` for each | |
| `curl -sv --cacert ca.pem https://203.0.113.10/health` | 404: `/health` is for the machines, not for the internet ([05 §1](05-ports-and-firewall.md#1-three-kinds-of-port)) | |
| `curl http://203.0.113.10/v1/health`, and 80's `/match/a1.arena.internal` (the `printf … \| nc` line of [11 §9](11-phone-to-backend-routes.md#9-checking-it)) | `200`, in clear; then `HTTP/1.1 101 Switching Protocols` from the arena (D-115) | |
| the Postman collection, with `baseUrl=https://203.0.113.10` and `--ssl-extra-ca-certs ca.pem` ([api/README](../api/README.md)) | every request as on the development machine; skip the admin folders, since the admin API is not public | the run's summary |
| the app, or the headless driver: a registration, a login, a match played | a match, with `tlsName` in the grant and the connection on 9001 | |

- **A refusal at the first row** points to the certificate or to nginx
  ([06 §10](06-certificates.md#10-when-something-goes-wrong)).
- **A refusal at the fifth row** (the Postman collection) points to the provider's forwarding
  ([05 §1](05-ports-and-firewall.md#1-three-kinds-of-port)): 80 to 80, 443 to 443 and 9001 to 9001, with the
  players' addresses passed on.

## 3. The watch, end to end

Run these on any machine, over an SSH tunnel to its admin API
([08 §5](08-monitoring.md#5-reading-and-silencing-alerts)):

| Step | Expected | Record |
|---|---|---|
| `GET /admin/watch` | three machines; every target and peer `up`; each machine's disks and memory | the disks' free space; each machine's `roundSeconds` |
| `GET /admin/alerts` | none, or only those a new site expects: `BackupProofStale` until the first Sunday's proof, and `BackupOffsiteStale` until a host is named (Q-53) | what fires, and why |
| `systemctl stop backend-worker@c2` on C; wait | within 2 min 15 s: `TargetDown` for `worker 127.0.0.1:9112` on every machine's `/admin/alerts`; the `ALERT firing` line in C's platform journal; the webhook's `POST`, once, if Q-60 named a receiver | how long it took; whether the receiver showed it |
| `systemctl start backend-worker@c2` | resolved within 30 s; the `resolved` `POST`, once | |
| `POST /admin/silences` with `{"alertname":"TargetDown","hours":1,"reason":"rehearsal"}`, then stop the worker again | the alert listed with `silenced: true`, and nothing sent; `DELETE` lifts the silence | |
| `systemctl stop backend-platform` on B (pulling B's cable would also fire the `events` store's alerts and the stores' replica alerts: that is §4's failover) | `PeerUnreachable {peer=B}` from A and C within 2 min 15 s, **sent once**; B's row gone from `/admin/watch` within a minute | |

## 4. The failovers

Run each failover by the [runbook §2](02-runbook.md#2-stateful-primary-failure). Keep the app or the headless
driver playing a match throughout. Run a stopwatch from the primary's stop to the first request served again.

| Failover | Expected (by design) | Record |
|---|---|---|
| the `session` store: stop A's, then promote B's with `promote-store.sh` | logins and matchmaking back once it is promoted; the match in progress plays on; `StoreConnectionDown` fired, then resolved | the seconds without logins |
| the `events` store: stop B's, then promote C's | results spool on the arenas and drain afterwards; `EventsStoreConnectionDown`; nothing lost (`backend_worker_applied_total` catches up) | the seconds; the results spooled |
| MySQL: stop A's, run `promote-mysql.sh` on C with `--demote-old`, then rebuild A by clone | logins back once it is promoted; `MysqlReplicaDown` on the way; the epoch raised ([06 §10](../detailed-design/06-persistence-mysql.md#10-backup-and-recovery)) | the seconds; how long the clone took |
| the front: ask the provider to forward 80, 443 and 9001 to A | players back once the forwarding changes; nothing to do on the machines | the provider's time to switch: NFR-4's real figure |
| each primary handed back to its own machine | as the runbook's "Moving back" says | |

## 5. The backups

Run these on C, by hand, as the timers would ([01 §10](01-deploy.md#10-copies-off-the-databases-machine)):

| Step | Expected | Record |
|---|---|---|
| `systemctl start backend-mysql-backup` | a dump in `/var/lib/backend/mysql-dumps`; `backend_backup_succeeded_timestamp_seconds{kind="dump"}` set | the dump's size and how long it took |
| `systemctl start backend-restore-proof` | the proof passes on the scratch server; `backend_backup_restore_seconds` set | how long it took (the alert is at 7 200 s) |
| `backend-backup-offsite`, once Q-53 names a host | the copy off the site made, and `fetch-offsite.sh` brings it back | |
| the restore drill to a moment ([runbook](02-runbook.md#recovering-to-a-moment)) | the exact counts the runbook gives | |

## 6. The load test from outside (Q-3)

The bots run on the fourth machine. They check the certificate as the app does, so they need the backend's root in
a PKCS#12 trust store ([tools/README](../../backend/tools/README.md#botclient)).

**Not every bot goes through 443.** All the bots share the fourth machine's one address. nginx lets one address log
in twice a second (with a burst of 30), and the platform 30 times a minute, so all but a few dozen of a thousand
bots would be refused with 429. So, for the run:

1. The bots' API and lobby go straight to one machine's platform and gateway on the private network. Each bot
   then sends its own address, in `X-Forwarded-For`.
2. Their matches go through the front's 9001, where the grants send them, as `./dev-backend.sh bots` does (D-92).
3. For the run alone, add the fourth machine's address to that machine's `backend` zone (8080 and 8090), and to
   `BACKEND_TRUSTED_PROXIES` in its `platform.env` and `gateway.env`, then restart both. This makes the platform
   believe each bot's own `X-Forwarded-For`. Take both out afterwards.

443's TLS is then not under load; the matches' TLS on 9001 is.

```bash
# once: the root into a trust store the JDK reads
keytool -importcert -noprompt -alias backend -file ca.pem -keystore ca.p12 -storetype PKCS12 -storepass rehearsal
# the API and the lobby straight to A, the matches through the front's 9001, every arena certificate checked
BACKEND_BOT_TRUSTSTORE=ca.p12 BACKEND_BOT_TRUSTSTORE_PASSWORD=rehearsal \
java -cp tools-0.1.0-SNAPSHOT-all.jar com.backend.tools.BotClient 1000 300 http://10.0.0.1:8080 ws://10.0.0.1:8090/lobby
```

Then run 2 500, 5 000 and 10 000 bots, for 300 s each, with a quiet minute between runs. The bots cost about one
core per thousand on their machine, so the fourth machine needs eleven cores. At each step, measure on the
machines:

| Measure | How | Why |
|---|---|---|
| each arena's tick p99 | `backend_arena_tick_p99_seconds{room}` on its metrics port, or `TickSlow` not firing | NFR-1b: under 15 ms; the deadline is 40 ms |
| each process's CPU | `top -H -p <pid>`, or `/proc/<pid>/task/*/stat` over a minute ([04 §7](04-scaling-and-performance.md#7-measuring-again)) | the capacity model's 25 arena cores at 50 000, and the ~87 the development machine projected ([architecture/01 §3](../architecture/01-system-topology.md#3-capacity-model)) |
| the front's nginx CPU | `top` on C, the nginx workers | the TLS cost that D-82 left unmeasured |
| the front's network | `/proc/net/dev` over a minute, or `sar -n DEV 60` | the 1.1 Gbit/s down and 0.35 up at 50 000; the uplink, Q-1 |
| each JVM's heap | `backend_jvm_heap_used_bytes` | against the units' `-Xmx` |
| the bots' own figures | `BotClient`'s summary: snapshots and KB/s per bot, disconnects, kicks | NFR-2: under 15 MB an hour per player; nothing dropped |
| the watch | `/admin/alerts` stays empty; `/admin/watch`'s `roundSeconds` | |

**Record** each step's row of the table, and the first thing that gave way: a room's tick, a machine's cores, the
front's link, or the bots' own machine. That answers Q-3, and Q-1 as far as the bots' traffic reaches.

## 7. Start, stop, restart

Follow [07](07-start-stop-restart.md), with the bots or the app playing:

1. `systemctl reload backend-nginx`: no connection is dropped.
2. `systemctl restart backend-arena@a1`: its journal shows the drain's `drained in N s`, and the other arenas take
   the new matches.
3. A restart of the platform, then of the gateway, one machine at a time: the front passes over the one that is
   restarting.

**Record** how long the drain took, and whether any player was cut off.

## 8. What to record, and where

| Step | Expected | Observed | Time |
|---|---|---|---|
| 1 install | every unit active on each machine | | |
| 2 the front | the certificate; a match through 443 and 9001 | | |
| 3 the watch | `TargetDown` within 2 min 15 s, once; `PeerUnreachable` once | | |
| 4 failovers | the seconds without logins for each; the provider's switch | | |
| 5 backups | how long the dump and the proof took | | |
| 6 load | the first thing that gave way, at how many players | | |
| 7 restarts | how long the drain took; nobody cut off | | |

The figures go into the documents whose questions they answer:

- Q-1 and Q-3: the [requirements](../requirements/01-scope-and-nfrs.md#7-open-questions);
- the capacity model's correction, if there is one:
  [architecture/01 §3](../architecture/01-system-topology.md#3-capacity-model) and
  [04 §5](04-scaling-and-performance.md#5-what-50-000-players-would-take-on-these-numbers);
- NFR-4's measured failover: the requirements' table;
- what changed hands: the [decision log](../architecture/03-decision-log.md);
- and a line for the day in [plan.md](../plan.md).

## 9. What the rehearsal cannot prove

- **How players behave**: how many are in battle rather than in the lobby (Q-2), and how they cluster (Q-4). Bots
  do not imitate that. The soft launch's analytics answer those questions
  ([05 §11](../detailed-design/05-worker-and-events.md)).
- **The provider's forwarding in a real outage**, unless §4's failover of the front is tried for real.
- **A flood** on the public address: nginx's limits guard the login paths, not the link
  ([05 §4](05-ports-and-firewall.md#4-who-connects-to-whom)).
- **The app on players' phones**: installed from the owner's copy (C-10), and its updates (Q-59).
