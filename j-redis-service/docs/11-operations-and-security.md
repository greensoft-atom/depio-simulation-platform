# 11 — Operations and security

## 1. Tools

All are our own programs built on `j-redis-client`; no third-party Redis tool
is used (D-14).

| Tool | Purpose |
|---|---|
| `j-redis-server` | The server (`bin/j-redis-server`, `bin\j-redis-server.cmd`) |
| `j-redis-cli` | Interactive shell and one-shot commands. `-h -p -a`, `--raw`, `--scan --pattern p`, `--bigkeys` (largest collection per type, via `SCAN`), `--latency` (continuous `PING` round trips). Prints replies the familiar way: `(integer) 1`, `1) "a"`. Unlike the application client, it allows every command, including `MULTI`, `WATCH`, `BLPOP` and `SUBSCRIBE`. |
| `j-redis-tools benchmark` | Called `j-redis-benchmark` elsewhere in these documents. Load generator: `-c` connections, `-n` requests per test, `-P` requests in flight per connection, `-d` value size, `-r` key space, `-t` tests (`ping,set,get,incr,lpush,rpush,lpop,rpop,sadd,hset,zadd,zincrby,zrevrank,zrevrange10,zaround,mset10`), `-q`. Reports throughput and latency percentiles (HdrHistogram). |
| `j-redis-tools check-aof` | Called `j-redis-check-aof` elsewhere. Verifies a data directory (manifest, base CRC, every incr file) or a single incr file. `--fix` cuts only a torn tail of the last incr file ([08 §8](08-persistence.md#8-start-up-and-recovery)). It takes the directory `LOCK`, so it refuses while the server runs. Exit code 0 means healthy. |
| `j-redis-tools dump` | Called `j-redis-dump` elsewhere. Lists a base file one line per key (type, key, size, expiry). `--values` also prints contents, up to `--max-elements` per key. Read-only, so it is safe while the server runs. |

The distribution (`scripts/make-dist.sh` → `target/dist/j-redis-<version>/`)
holds `bin/` (sh and cmd launchers), `lib/` (three self-contained jars), `conf/`
(`j-redis.conf` for production, `j-redis-dev.conf` for a workstation) and
`systemd/j-redis.service`. Install it as `/opt/j-redis` on Linux. The launchers
honour `JAVA_HOME` and `JREDIS_JAVA_OPTS`.

## 2. Configuration

A text file in Redis's style: one `directive value` per line, `#` comments.
Sizes accept `k`/`m`/`g` (powers of 1000) and `kb`/`mb`/`gb` (powers of 1024),
as in Redis. Any directive can be overridden on the command line
(`--port 6380`).

| Directive | Default | `CONFIG SET` | Meaning |
|---|---|---|---|
| `port` | `6379` | no | TCP port |
| `bind` | `127.0.0.1` | no | Listen addresses |
| `protected-mode` | `yes` | yes | Refuse non-loopback clients when no password is set |
| `requirepass` | *(empty)* | yes | Password for `AUTH` |
| `maxclients` | `1000` | yes | |
| `timeout` | `0` | yes | Close clients idle this many seconds; 0 = never |
| `tcp-keepalive` | `300` | no | Seconds |
| `io-threads` | `2` | no | Netty I/O threads |
| `dir` | `./data` | no | Data directory ([08 §2](08-persistence.md#2-files-and-the-manifest)) |
| `appendonly` | `yes` | no | Persistence on/off |
| `appendfsync` | `everysec` | yes | `everysec` or `no` |
| `appendfsync-interval-millis` | `1000` | yes | With `everysec`: how often the AOF is fsynced, 10 to 60000 ms. The loss window on power failure is about this long. |
| `aof-load-truncated` | `yes` | yes | Tolerate a truncated final record |
| `auto-aof-rewrite-percentage` | `100` | yes | `0` disables automatic rewrites |
| `auto-aof-rewrite-min-size` | `64mb` | yes | |
| `maxmemory` | `0` | yes | Estimated data limit; `0` = none |
| `maxmemory-policy` | `noeviction` | no | Only value in v1 |
| `proto-max-bulk-len` | `64mb` | yes | Largest argument |
| `client-query-buffer-limit` | `128mb` | yes | Unparsed bytes per client |
| `client-output-buffer-limit` | `pubsub 32mb 8mb 60`, `replica 256mb 64mb 60` | yes | Slow-subscriber disconnect ([07 §1.5](07-pubsub-blocking-transactions.md#15-slow-subscribers)); the `replica` class (2.2) for a replica's connection ([16 §12](16-replication.md#12-configuration-commands-info)) |
| `replicaof` | *(none)* | no | 2.2: start as a replica of `<host> <port>` ([16](16-replication.md)). The role at start-up is this file's: a promotion edits it too |
| `primaryauth` | *(empty)* | yes | 2.2: the password a replica sends to its primary |
| `repl-backlog-size` | `64mb` | no | 2.2: the ring of recent stream bytes, which also holds a replica's changes while its full sync is sent |
| `repl-timeout` | `60` | yes | 2.2: seconds without a byte from the primary, or an acknowledgement from a replica, before the link is dropped |
| `repl-ping-replica-period` | `10` | yes | 2.2: seconds between a primary's `PING`s on the link |
| `min-replicas-to-write` | `0` | yes | 2.2: refuse writes (`NOREPLICAS`) while fewer replicas than this acknowledged within `min-replicas-max-lag`; 0 is off |
| `min-replicas-max-lag` | `10` | yes | 2.2: seconds |
| `slowlog-log-slower-than` | `10000` | yes | Microseconds; `-1` disables |
| `slowlog-max-len` | `128` | yes | |
| `background-slice-micros` | `250` | yes | ([02 §5](02-architecture.md#5-background-work)) |
| `background-max-duty` | `25` | yes | Percent |
| `disable-command` | *(none)* | no | Repeatable; disabled commands look unknown |
| `enable-debug-command` | `no` | no | `DEBUG SLEEP`, `DEBUG RELOAD`, `DEBUG SET-ACTIVE-EXPIRE`, `DEBUG DIGEST` |

Logging level and destination are configured in `logback.xml`, not here.

### Production sample (`/etc/j-redis/j-redis.conf`)

```
port 6379
bind 127.0.0.1
requirepass <long random secret>
dir /var/lib/j-redis
appendonly yes
appendfsync everysec
maxmemory 3gb
disable-command FLUSHALL
disable-command FLUSHDB
disable-command KEYS
disable-command DEBUG
disable-command SHUTDOWN
```

`CONFIG` stays enabled: the backup procedure and runtime tuning need it, and it
is behind the password. `SHUTDOWN` is disabled because production stops go
through systemd.

## 3. Start-up and shutdown

```
java <JVM flags> -jar j-redis-server.jar /etc/j-redis/j-redis.conf [--directive value …]
bin/j-redis-server conf/j-redis.conf [--directive value …]      # the same, via the launcher
```

| Exit code | Meaning | Restart? |
|---|---|---|
| 0 | Clean shutdown | — |
| 1 | Configuration error | **No** — fix the config |
| 2 | Data directory locked by another process | **No** |
| 3 | Data could not be loaded (corruption) | **No** — operator decision ([§8](#8-runbook)) |
| 4 | Fail-stop: internal error or fsync failure ([02 §11](02-architecture.md#11-failure-handling-inside-a-command)) | Yes |

`SIGTERM` or `SIGINT` (Ctrl+C) runs the clean shutdown sequence
([02 §13](02-architecture.md#13-start-up-and-shutdown)). The JVM then exits
with **143** (128 + SIGTERM), which is why the unit file has
`SuccessExitStatus=143`. A fail-stop always exits with 4. This includes the
case where logging itself fails while the fail-stop is reported, so systemd
restarts the server.

## 4. Running on Linux

`/etc/systemd/system/j-redis.service` (shipped as `systemd/j-redis.service` in the distribution):

```ini
# Install: copy the distribution to /opt/j-redis, create the user (useradd --system jredis),
# copy conf/j-redis.conf to /etc/j-redis/j-redis.conf (set requirepass), then
#   cp systemd/j-redis.service /etc/systemd/system/ && systemctl daemon-reload && systemctl enable --now j-redis
[Unit]
Description=j-redis-service
After=network.target

[Service]
User=jredis
# creates /var/lib/j-redis (data) and /var/log/j-redis (GC log, heap dumps) owned by User
StateDirectory=j-redis
LogsDirectory=j-redis
ExecStart=/usr/bin/taskset -c 0-1 /usr/lib/jvm/java-21-openjdk/bin/java \
    -server -Xms8g -Xmx8g \
    -XX:+UseZGC -XX:+ZGenerational -XX:+AlwaysPreTouch \
    --add-opens java.base/java.nio=ALL-UNNAMED \
    -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/log/j-redis \
    -Xlog:gc*:file=/var/log/j-redis/gc.log:time,uptime:filecount=5,filesize=20M \
    -Djava.net.preferIPv4Stack=true -Dio.netty.leakDetection.level=disabled \
    -jar /opt/j-redis/lib/j-redis-server.jar /etc/j-redis/j-redis.conf
# exit 4 (fail-stop) restarts; 1 (config), 2 (data dir locked), 3 (data not loadable) need a human
Restart=on-failure
# the JVM exits with 143 after a SIGTERM-initiated clean shutdown
SuccessExitStatus=143
RestartSec=2
RestartPreventExitStatus=1 2 3
LimitNOFILE=65536
TimeoutStopSec=60

[Install]
WantedBy=multi-user.target
```

- `RestartPreventExitStatus` stops a restart loop when the cause is a bad
  config, a held lock, or corrupt data — those need a human.
- Cores 0–1 are the ones reserved for j-redis in the host's CPU plan; the
  other services are pinned elsewhere. Widen to 0–2 if `INFO cpu` shows the
  command thread above 50 %.
- Heap sizing is explained in [06 §6](06-expiry-and-memory.md#6-jvm-heap-sizing).
- Start order: j-redis first, then the services that use it. Put
  `After=j-redis.service` in their units. Clients reconnect on their own if
  j-redis restarts later.
- `StateDirectory=` and `LogsDirectory=` make systemd create `/var/lib/j-redis`
  and `/var/log/j-redis`, owned by the service user.

## 5. Running on Windows (development)

Production is Linux only; on Windows the server runs in a console.

From the unpacked distribution, in a console:

```bat
set JAVA_HOME=C:\jdk21
bin\j-redis-server.cmd conf\j-redis-dev.conf
bin\j-redis-cli.cmd ping
```

`conf\j-redis-dev.conf`:

```
# j-redis-service - development configuration (Windows or Linux workstation).
# Start from the distribution directory:  bin\j-redis-server.cmd conf\j-redis-dev.conf
port 6379
bind 127.0.0.1
dir data
appendonly yes
enable-debug-command yes
```

- Stop with **Ctrl+C**, which runs the clean shutdown. Closing the window gives
  the JVM only a few seconds.
- Binding to `127.0.0.1` avoids the Windows Firewall prompt.
- Directory fsync is skipped on Windows ([08 §11](08-persistence.md#11-platform-notes)).

## 6. `INFO`

`INFO [section]`; the output format is `field:value` lines grouped under
`# Section` headers, as in Redis.

| Section | Key fields |
|---|---|
| `server` | `version`, `java_version`, `os`, `process_id`, `tcp_port`, `uptime_in_seconds`, `config_file` |
| `clients` | `connected_clients`, `blocked_clients`, `pubsub_clients`, `watching_clients`, `maxclients`, `client_recent_max_output_bytes` |
| `memory` | `used_memory` (estimate), `maxmemory`, `maxmemory_policy`, `jvm_heap_used`, `jvm_heap_committed`, `jvm_heap_max`, `estimate_to_heap_ratio`, `gc_count`, `gc_time_ms` |
| `persistence` | `aof_enabled`, `aof_generation`, `aof_base_size`, `aof_incr_size`, `aof_rewrite_in_progress`, `aof_last_rewrite_status`, `aof_last_rewrite_time_sec`, `aof_rewrite_longest_record_ms`, `aof_last_write_status`, `aof_pending_bytes`, `aof_last_fsync_age_ms`, `lastsave`, `loading_time_ms` |
| `stats` | `total_connections_received`, `total_commands_processed`, `instantaneous_ops_per_sec`, `rejected_connections`, `expired_keys`, `keyspace_hits`, `keyspace_misses`, `pubsub_channels`, `pubsub_patterns`, `total_net_input_bytes`, `total_net_output_bytes`, `client_output_limit_disconnects`, `internal_errors` |
| `commandstats` | per command: `calls`, `usec`, `usec_per_call`, `p50`, `p99`, `p999`, `rejected_calls`, `failed_calls` |
| `replication` (2.2) | `role` (`primary` or `replica`), `replication_epoch`, `replid`; on a primary `repl_offset`, `connected_replicas`, `replicaN:ip=…,port=…,state=…,offset=…,lag=…` (its acknowledged offset, and seconds since), `full_syncs`, `partial_syncs`, `waiting_clients` (in `WAIT`), `repl_backlog_active`, `repl_backlog_size`, `repl_backlog_first_offset`; on a replica `primary_host`, `primary_port`, `primary_link_status` (`up` or `down`), `primary_link_state`, `primary_last_io_seconds_ago`, `primary_sync_in_progress`, `replica_applied_offset`, `replica_apply_errors`, `last_sync_error` |
| `cpu` | `cmd_thread_cpu_percent`, `background_duty_percent` |
| `keyspace` | `db0:keys=…,expires=…` |

Latency percentiles come from an HdrHistogram per command, recorded on the
command thread with no allocation.

### Slowlog

Commands slower than `slowlog-log-slower-than` µs are kept in a ring of
`slowlog-max-len` entries: id, timestamp, duration, arguments (truncated to 32
arguments of 128 bytes), client address and name. `SLOWLOG GET [n]`.

### Logging

Logback with an async appender. Arguments are **never** logged at INFO or above,
because they can contain tokens and passwords.

| Level | Examples |
|---|---|
| ERROR | internal command error, persistence failure, fail-stop |
| WARN | backpressure engaged, AOF truncated on load, slow subscriber disconnected, AOF backlog wait |
| INFO | start-up, load time, rewrite start/finish, config changes |

## 7. Security

**Threat model:** an internal service on one host whose legitimate clients are
our own processes. Protect against other things on the network and against a
compromised or buggy client exhausting resources.

| Measure | Detail |
|---|---|
| Bind to loopback | Default `127.0.0.1`. In production every client is on the same box ([01 C-4](01-requirements-and-scope.md#2-constraints)). |
| Protected mode | With no password set, non-loopback connections get `-DENIED …` and are closed |
| Password | `requirepass` + `AUTH`, compared with `MessageDigest.isEqual` (constant time). Failures are counted and logged. |
| Secret handling | The password lives in the config file (mode `600`, owned by `jredis`), never on the command line where `ps` shows it |
| Dangerous commands | Disabled in production ([§2](#2-configuration)) |
| Resource limits | `maxclients`, query buffer, output buffer, bulk length — no single client can exhaust the heap |
| Hash flooding | SipHash-1-2 with a random key ([04 §3.2](04-data-structures.md#32-hashing)) |
| Pattern DoS | Non-exponential glob matcher ([07 §1.3](07-pubsub-blocking-transactions.md#13-glob-patterns)) |
| TLS | Not in v1 — traffic never leaves the host. If it ever must, M8 adds Netty's `SslHandler` with `netty-tcnative`, already in the offline bundle. |

## 8. Runbook

| Symptom | Likely cause | Action |
|---|---|---|
| Clients get `-OOM` | Data exceeded `maxmemory` | `INFO memory`; `j-redis-cli --bigkeys`; look for keys without TTL (`INFO keyspace` expires vs keys). Raise `maxmemory` only after finding the cause. |
| Clients get `-MISCONF` | AOF writes failing — usually disk full | Free disk space. The server retries every second and recovers by itself; check `aof_last_write_status:ok`. |
| Process exits with 4 | Fail-stop | The log names the command and client. systemd restarts it with consistent data. If it repeats, find and stop the client sending that command, and file a bug. |
| Won't start, exit 3 | Corrupt data file | `j-redis-tools check-aof <data-dir>` names the file and offset. A torn tail of the last incr file is fixed with `--fix`. For corruption anywhere else, choose between restoring a backup and cutting the file by hand at the reported offset, which loses everything after it. |
| Won't start, exit 2 | Another instance owns the data directory | Stop the other instance |
| High latency | Slow command, big key, GC, heavy background work | `SLOWLOG GET`; `INFO commandstats` p99; `INFO cpu` duty; `aof_rewrite_longest_record_ms`; GC log |
| Pub/Sub clients disconnected | Slow subscriber hit the output limit | Fix the consumer; `client_output_limit_disconnects` counts them |
| AOF keeps growing | Rewrites not triggering or failing | `aof_last_rewrite_status`; auto-rewrite settings |

### Alerts

- `used_memory / maxmemory` > 80 %
- `aof_last_write_status` ≠ `ok`, or `aof_last_fsync_age_ms` > 5000
- `internal_errors` increasing
- p99 of `FAST` commands > 1 ms for 5 minutes
- `connected_clients` > 80 % of `maxclients`
- any restart of the service

## 9. Upgrades

- Base files and the manifest carry a format version; incr files are RESP and
  stable.
- A server version reads every older format it knows, and writes its own
  version at the next rewrite.
- **Upgrade:** back up ([08 §12](08-persistence.md#12-backup-and-restore)), stop,
  replace the jar, start, check `INFO server` and `INFO persistence`.
- **Downgrade:** restore the backup taken before the upgrade — an older server
  may not read a newer format.
