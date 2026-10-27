# The rehearsal on the three machines

The readiness review's first step on the owner's hardware ([plan item 100's list](../plan.md)): every
check that this repository could not run on a development machine, in order, each with its expected
outcome and what to record. Nothing here is new procedure; each step names the guide that is. A day,
with a quiet hour kept for the load test; the second half (§6 onwards) can be another day.

**Bring:** the release `.tar.gz` from `./build-offline.sh` ([03 §3](03-install-guide.md#3-build)); the
root's `ca.pem` and, kept apart from the servers, its key ([06 §2](06-certificates.md#2-making-the-root-once));
a fourth machine for the bots, reached from outside the site; the webhook's address if the owner has
named one ([Q-60](../requirements/01-scope-and-nfrs.md#7-open-questions)); and this page printed, with
§8's table to fill in.

The addresses are the examples': the site's `203.0.113.10`, the machines' `10.0.0.1`, `.2`, `.3`.

## 1. Install the three machines

By the [install guide](03-install-guide.md), §1 to §12 on each, with the differences its
[three machines](03-install-guide.md#three-machines) table gives. Then, on each:

| Check | Expected | Record |
|---|---|---|
| `cat /opt/backend/VERSIONS.md` | the release's commit, runtime, MySQL, nginx, j-redis | the commit |
| `systemctl status 'backend-*'` | every unit of that machine active ([07 §1](07-start-stop-restart.md#1-on-a-server)'s table) | |
| `ss -ltnp` | what [05 §2](05-ports-and-firewall.md#2-every-port-in-production) lists for that machine, nothing else | anything else that listens |
| `firewall-cmd --list-all-zones` | `public`: 443 and 9001 alone; `backend`: the two other machines' addresses and [05 §5](05-ports-and-firewall.md#5-the-firewall-on-rhel-9)'s ports | |
| from each machine, `curl -s http://10.0.0.X:8080/health` for the two others | `{"status":"ok"}` from both | |
| `journalctl -u backend-platform -n 20` | `the watch reads N target(s), 2 peer(s)…` and no `ERROR` | |
| `getenforce`, then `ausearch -m avc -ts boot \| grep -c -E 'comm="(java\|mysqld\|nginx)"'` | the owner's mode (`Enforcing` is expected), and `0`: no denial for a backend process. One found: its line, and `runuser -u <the unit's user> -- /opt/backend/scripts/check-bind.sh <its port>` ([D-88](../architecture/03-decision-log.md#d-88--a-port-the-machine-refuses-is-named-with-its-policy-before-anything-starts)); SELinux enforcing was never seen by a check here ([05 §9](05-ports-and-firewall.md#9-what-was-checked-and-what-was-not)) | the mode; any denial, whole |
| `sysctl net.ipv4.ip_unprivileged_port_start` | `1024`: a higher value keeps the units' ports for root, and they fail with `Permission denied` | the value |

**The private network's speed**, once, from A to C: a transfer of the release tarball with `scp -v`, its
rate noted, or `iperf3` if the owner has it. **The uplink** ([Q-1](../requirements/01-scope-and-nfrs.md#7-open-questions)):
the provider's figure, and a download of the tarball from the fourth machine through the front's
address is not it (nothing serves files there); the bots' egress in §6 is the measurement that matters.

## 2. Through the front

From the fourth machine, the site's public address alone:

| Check | Expected | Record |
|---|---|---|
| `openssl s_client -connect 203.0.113.10:443 -CAfile ca.pem < /dev/null` | `Verify return code: 0 (ok)`, `subject=CN = backend`, the names listed ([06 §4](06-certificates.md#4-installing-it-on-every-machine)) | the certificate's `notAfter` |
| the same on `9001 -servername a1.arena.internal`, and each arena's name | the same certificate | |
| `curl -sv --cacert ca.pem https://203.0.113.10/health` | 404: `/health` is for the machines, not the internet ([05 §1](05-ports-and-firewall.md#1-three-kinds-of-port)) | |
| `curl http://203.0.113.10/` | nothing answers on 80 | |
| the Postman collection with `baseUrl=https://203.0.113.10` and `--ssl-extra-ca-certs ca.pem` ([api/README](../api/README.md)) | every request as on the development machine; the admin folders skipped (the admin API is not public) | the run's summary |
| the app, or the headless driver: a registration, a login, a match played | a match, with `tlsName` in the grant and the connection on 9001 | |

A refusal at the first row is the certificate or nginx ([06 §10](06-certificates.md#10-when-something-goes-wrong)); at
the fifth, the provider's forwarding ([05 §1](05-ports-and-firewall.md#1-three-kinds-of-port): 443 to 443, 9001 to
9001, the players' addresses passed on).

## 3. The watch, end to end

On any machine, over an SSH tunnel to its admin API ([08 §5](08-monitoring.md#5-reading-and-silencing-alerts)):

| Step | Expected | Record |
|---|---|---|
| `GET /admin/watch` | three machines, every target and peer `up`, each machine's disks and memory | the disks' free space, each machine's `roundSeconds` |
| `GET /admin/alerts` | none, or the ones a new site expects: `BackupProofStale` until the first Sunday's proof, `BackupOffsiteStale` until a host is named (Q-53) | what fires, and why |
| `systemctl stop backend-worker@c2` on C; wait | within 2 min 15 s, `TargetDown` for `worker 127.0.0.1:9112` on every machine's `/admin/alerts`; the `ALERT firing` line in C's platform journal; the webhook's `POST`, once, if Q-60 named one | how long it took; whether the receiver showed it |
| `systemctl start backend-worker@c2` | resolved within 30 s, the `resolved` `POST` once | |
| `POST /admin/silences` `{"alertname":"TargetDown","hours":1,"reason":"rehearsal"}`, then stop the worker again | listed, `silenced: true`, nothing sent; `DELETE` lifts it | |
| pull B's private network cable (or `systemctl stop backend-platform` on B) | `PeerUnreachable {peer=B}` from A and C within 2 min 15 s, **sent once**; B's row gone from `/admin/watch` within a minute | |

## 4. The failovers

Each by the [runbook §2](02-runbook.md#2-stateful-primary-failure), with the app or the headless driver
playing a match throughout, and the stopwatch running from the primary's stop to the first request
served again:

| Failover | Expected (design) | Record |
|---|---|---|
| the `session` store: stop A's, promote B's with `promote-store.sh` | logins and matchmaking back once promoted; the match in progress plays on; `StoreConnectionDown` fired and resolved | seconds without logins |
| the `events` store: stop B's, promote C's | results spool on the arenas and drain after; `EventsStoreConnectionDown`; nothing lost (`backend_worker_applied_total` catches up) | seconds; results spooled |
| MySQL: stop A's, `promote-mysql.sh` on C with `--demote-old`, then A rebuilt by clone | logins back once promoted; `MysqlReplicaDown` on the way; the epoch raised ([06 §10](../detailed-design/06-persistence-mysql.md#10-backup-and-recovery)) | seconds; the clone's duration |
| the front: ask the provider to forward to A | players back once the forwarding changes; nothing to do on the machines | the provider's time to switch: NFR-4's real figure |
| each primary handed back to its machine | as the runbook's "handing back" | |

## 5. The backups

On C, by hand, as the timers would ([01 §10](01-deploy.md#10-copies-off-the-databases-machine)):

| Step | Expected | Record |
|---|---|---|
| `systemctl start backend-mysql-backup` | a dump in `/var/lib/backend/mysql-dumps`, `backend_backup_succeeded_timestamp_seconds{kind="dump"}` set | the dump's size and duration |
| `systemctl start backend-restore-proof` | the proof passes on the scratch server; `backend_backup_restore_seconds` | its duration (the alert is at 7 200 s) |
| `backend-backup-offsite`, once Q-53 names a host | the copy off the site, and `fetch-offsite.sh` brings it back | |
| the restore drill to a moment ([runbook](02-runbook.md#recovering-to-a-moment)) | the exact counts the runbook gives | |

## 6. The load test from outside (Q-3)

The bots on the fourth machine, through the front, as players come: `BotClient` with the backend's
root in a PKCS#12 trust store, since the bots check the certificate as the app does
([tools/README](../../backend/tools/README.md#botclient)):

```bash
# once: the root into a trust store the JDK reads
keytool -importcert -noprompt -alias backend -file ca.pem -keystore ca.p12 -storetype PKCS12 -storepass rehearsal
# the lobby and the API over TLS too: the JVM's own trust store for them, the bots' for the arenas
BACKEND_BOT_TRUSTSTORE=ca.p12 BACKEND_BOT_TRUSTSTORE_PASSWORD=rehearsal \
java -Djavax.net.ssl.trustStore=ca.p12 -Djavax.net.ssl.trustStorePassword=rehearsal \
     -cp tools-0.1.0-SNAPSHOT-all.jar com.backend.tools.BotClient 1000 300 https://203.0.113.10 wss://203.0.113.10/lobby
```

Then 2 500, 5 000 and 10 000 for 300 s each, a quiet minute between; the bots cost about a core a
thousand on their machine, so the fourth machine needs eleven. At each step, on the machines:

| Measure | How | Why |
|---|---|---|
| each arena's tick p99 | `backend_arena_tick_p99_seconds{room}` on its metrics port, or `TickSlow` not firing | NFR-1b: under 15 ms; the deadline 40 ms |
| each process's CPU | `top -H -p <pid>` or `/proc/<pid>/task/*/stat` over a minute ([04 §7](04-scaling-and-performance.md#7-measuring-again)) | the capacity model's 25 arena cores at 50 000, and the ~87 the development machine projected ([architecture/01 §3](../architecture/01-system-topology.md#3-capacity-model)) |
| the front's nginx CPU | `top` on C, the nginx workers | the TLS cost D-82 left unmeasured |
| the front's network | `/proc/net/dev` over a minute, or `sar -n DEV 60` | the 1.1 Gbit/s down and 0.35 up at 50 000; the uplink, Q-1 |
| each JVM's heap | `backend_jvm_heap_used_bytes` | against the units' `-Xmx` |
| the bots' own figures | `BotClient`'s summary: snapshots and KB/s a bot, disconnects, kicks | NFR-2: under 15 MB/hour a player; nothing dropped |
| the watch | `/admin/alerts` stays empty; `/admin/watch`'s `roundSeconds` | |

**Record** each step's row of the table, and the first thing that gave: a room's tick, a machine's cores,
the front's link, the bots' machine. That answers Q-3, and Q-1 as far as the bots' egress reaches.

## 7. Start, stop, restart

By [07](07-start-stop-restart.md), with the bots or the app playing: `systemctl reload backend-nginx` (no
connection dropped), `systemctl restart backend-arena@a1` (the drain's `drained in N s` in its journal,
the other arenas taking the new matches), a platform's and a gateway's restart one machine at a time
(the front passing over the one restarting). **Record** the drain's duration and whether any player was
cut.

## 8. What to record, and where

| Step | Expected | Observed | Time |
|---|---|---|---|
| 1 install | every unit active on each machine | | |
| 2 the front | the certificate, a match through 443 and 9001 | | |
| 3 the watch | `TargetDown` within 2 min 15 s, once; `PeerUnreachable` once | | |
| 4 failovers | seconds without logins for each; the provider's switch | | |
| 5 backups | the dump's and the proof's durations | | |
| 6 load | the first thing that gave, at how many players | | |
| 7 restarts | the drain's duration; nobody cut | | |

The figures go to the documents they answer: Q-1 and Q-3 in the
[requirements](../requirements/01-scope-and-nfrs.md#7-open-questions); the capacity model's correction, if
one, in [architecture/01 §3](../architecture/01-system-topology.md#3-capacity-model) and
[04 §5](04-scaling-and-performance.md#5-what-50-000-players-would-take-on-these-numbers); NFR-4's
measured failover in the requirements' table; what changed hands in the
[decision log](../architecture/03-decision-log.md); and a line in [plan.md](../plan.md) for the day.

## 9. What the rehearsal cannot prove

- **Players' behaviour**: how many are in battle rather than the lobby (Q-2) and how they cluster
  (Q-4), which bots do not imitate; the soft launch's analytics answer those
  ([05 §11](../detailed-design/05-worker-and-events.md)).
- **The provider's forwarding under a real outage**, unless §4's front failover is tried for real.
- **A flood** on the public address: nginx's limits guard the login paths, not the link
  ([05 §4](05-ports-and-firewall.md#4-who-connects-to-whom)).
- **The app on players' phones**: installed from the owner's copy (C-10), its updates (Q-59).
