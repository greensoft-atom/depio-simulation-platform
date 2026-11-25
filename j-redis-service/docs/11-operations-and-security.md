# 11 — Operations and security

This page is the operator's reference for j-redis. It is for the people who
install, configure, run, monitor and secure the server. It covers the tools,
every configuration directive with its default, start-up and exit codes,
running under systemd on Linux and in a console on Windows, the `INFO` fields,
the slowlog and logging, security, the runbook with alerts, and upgrades. For
step-by-step instructions, see
[guide 4](guide/04-run-and-operate.md).

Inside the backend, the release runs j-redis as `backend-store@session` and
`backend-store@events`; the backend's
[deploy guide §3](../../docs/operations/01-deploy.md#3-j-redis-built-and-tested)
says how. The directives, `INFO` fields and runbook on this page apply there
too.

## 1. Tools

All the tools are j-redis's own programs, built on `j-redis-client`. No
third-party Redis tool is used (D-14).

| Tool | Purpose |
|---|---|
| `j-redis-server` | The server (`bin/j-redis-server`, `bin\j-redis-server.cmd`) |
| `j-redis-cli` | An interactive shell, and one-shot commands. It prints replies the familiar way: `(integer) 1`, `1) "a"`. Unlike the application client, it allows every command, including `MULTI`, `WATCH`, `BLPOP` and `SUBSCRIBE`. Its options are below. |
| `j-redis-tools benchmark` | A load generator. It reports throughput and latency percentiles (HdrHistogram). Older text, including the records [13](13-implementation-plan.md) and [14](14-decision-log.md), calls it `j-redis-benchmark`. Its options are below. |
| `j-redis-tools check-aof [--fix] <data-dir \| file.aof>` | Checks a data directory (the manifest, the base file's CRC, every incr file) or a single incr file. `--fix` cuts only a torn end of the last incr file ([08 §8](08-persistence.md#8-start-up-and-recovery)). For a directory, and for `--fix`, it takes the directory's `LOCK`, so it refuses while the server runs. Exit code 0 means healthy, 1 a problem found (or fixed), 2 a usage error. Older text calls it `j-redis-check-aof`. |
| `j-redis-tools dump [--values] [--max-elements n] <base-file \| data-dir>` | Lists a base file, one line per key (type, key, TTL, size). `--values` also prints the contents, up to `--max-elements` elements per key (20 by default). It only reads, so it is safe while the server runs. Older text calls it `j-redis-dump`. |

**`j-redis-cli` options:**

| Option | Default | Meaning |
|---|---|---|
| `-h host` | `127.0.0.1` | The server's address |
| `-p port` | `6379` | The server's port |
| `-a password` | none | Sends `AUTH` first |
| `--raw` | off | Prints replies without the formatting |
| `--scan [--pattern p] [--count n]` | | Lists keys with `SCAN` |
| `--bigkeys` | | Finds the largest collection of each type, with `SCAN` |
| `--latency` | | Measures `PING` round trips continuously |
| `--help` | | Prints the usage |

**`j-redis-tools benchmark` options:**

| Option | Default | Meaning |
|---|---|---|
| `-h`, `-p`, `-a` | `127.0.0.1`, `6379`, none | The server's address, port and password |
| `-c` | 50 | Connections |
| `-n` | 100000 | Requests per test |
| `-P` | 1 | Requests in flight per connection (the pipeline depth) |
| `-d` | 3 | Value size in bytes |
| `-r` | 0 | Key space: the number of random keys (0: one fixed key) |
| `-t` | all | Tests, separated by commas: `ping,set,get,incr,lpush,rpush,lpop,rpop,sadd,hset,zadd,zincrby,zrevrank,zrevrange10,zaround,mset10` |
| `--threads` | 2 | Client I/O threads |
| `-q` | off | Quiet: one line per test |

**The distribution.** `scripts/make-dist.sh` builds `target/dist/j-redis-<version>/`.
It holds:

- `bin/`: the launchers, as `sh` scripts and `.cmd` files, for `j-redis-server`,
  `j-redis-cli`, `j-redis-tools` and `j-redis-examples`;
- `lib/`: four self-contained jars, `j-redis-server.jar`, `j-redis-cli.jar`,
  `j-redis-tools.jar` and `j-redis-examples.jar`;
- `conf/`: `j-redis.conf` for production and `j-redis-dev.conf` for a
  workstation;
- `systemd/j-redis.service`, and `README.txt`.

On Linux, install it as `/opt/j-redis`. Every launcher honours `JAVA_HOME`. In
the server's launcher, `JREDIS_JAVA_OPTS` replaces the default JVM options
(`-Xms1g -Xmx1g -XX:+UseZGC -XX:+ZGenerational`); in the tools' launcher,
`JREDIS_TOOLS_JAVA_OPTS` replaces `-Xmx2g`. The CLI's launcher has fixed
options (`-Xmx256m -XX:+UseSerialGC`).

## 2. Configuration

The configuration is a text file in Redis's style: one `directive value` per
line, and `#` starts a comment.

- Sizes accept `k`, `m` and `g` (powers of 1000) and `kb`, `mb` and `gb`
  (powers of 1024), as in Redis.
- Any directive can be overridden on the command line, for example
  `--port 6380`.
- Directives marked "no" in the `CONFIG SET` column are fixed at start-up.
  The others can be changed while the server runs, with `CONFIG SET`.

| Directive | Default | `CONFIG SET` | Meaning |
|---|---|---|---|
| `port` | `6379` | no | TCP port, 0 to 65535 (0: a free port) |
| `bind` | `127.0.0.1` | no | The listen addresses, up to 16 |
| `protected-mode` | `yes` | yes | Refuse clients that are not on loopback while no password is set |
| `requirepass` | *(empty)* | yes | The password for `AUTH` |
| `maxclients` | `1000` | yes | 1 to 1,000,000 |
| `timeout` | `0` | yes | Close clients idle for this many seconds; 0 means never |
| `tcp-keepalive` | `300` | no | Seconds |
| `io-threads` | `2` | no | Netty I/O threads, 1 to 128 |
| `dir` | `./data` | no | The data directory ([08 §2](08-persistence.md#2-files-and-the-manifest)) |
| `appendonly` | `yes` | no | Persistence on or off |
| `appendfsync` | `everysec` | yes | `everysec` or `no` |
| `appendfsync-interval-millis` | `1000` | yes | With `everysec`: how often the AOF is fsynced, 10 to 60000 ms. A power failure loses about this much time of writes. |
| `aof-load-truncated` | `yes` | yes | Accept a cut-off final record at load |
| `auto-aof-rewrite-percentage` | `100` | yes | `0` turns automatic rewrites off |
| `auto-aof-rewrite-min-size` | `64mb` | yes | |
| `maxmemory` | `0` | yes | The limit on the estimated data size; `0` means none |
| `maxmemory-policy` | `noeviction` | no | The only value accepted |
| `proto-max-bulk-len` | `64mb` | yes | The largest argument, 1kb to 2gb |
| `client-query-buffer-limit` | `128mb` | yes | Unparsed bytes per client; at least 1mb |
| `client-output-buffer-limit` | `normal 0 0 0`, `pubsub 32mb 8mb 60`, `replica 256mb 64mb 60` | yes | One or more groups of `<class> <hard> <soft> <seconds>`, where the class is `normal`, `pubsub` or `replica` (2.2). It disconnects slow subscribers ([07 §1.5](07-pubsub-blocking-transactions.md#15-slow-subscribers)) and slow replicas ([16 §12](16-replication.md#12-configuration-commands-info)) |
| `replicaof` | *(none)* | no | 2.2: start as a replica of `<host> <port>` ([16](16-replication.md)). The file decides the role at start-up, so a promotion edits the file too |
| `primaryauth` | *(empty)* | yes | 2.2: the password a replica sends to its primary |
| `repl-backlog-size` | `64mb` | no | 2.2: the ring of recent replication bytes, 16kb to 2gb. It also holds a replica's changes while its full sync is sent |
| `repl-timeout` | `60` | yes | 2.2: seconds without a byte from the primary, or an acknowledgement from a replica, before the link is dropped; 1 to 3600 |
| `repl-ping-replica-period` | `10` | yes | 2.2: seconds between a primary's `PING`s on the link; 1 to 3600 |
| `min-replicas-to-write` | `0` | yes | 2.2: refuse writes (`NOREPLICAS`) while fewer replicas than this have acknowledged within `min-replicas-max-lag`; 0 turns it off |
| `min-replicas-max-lag` | `10` | yes | 2.2: seconds; 1 to 3600 |
| `slowlog-log-slower-than` | `10000` | yes | Microseconds; `-1` turns the slowlog off |
| `slowlog-max-len` | `128` | yes | Entries kept |
| `background-slice-micros` | `250` | yes | The longest background slice, 50 to 100,000 µs ([02 §5](02-architecture.md#5-background-work)) |
| `background-max-duty` | `25` | yes | The most background work may use of the command thread, in percent, 1 to 100 |
| `disable-command` | *(none)* | no | Repeatable. A disabled command looks unknown. A name that is no command is a configuration error |
| `enable-debug-command` | `no` | no | Turns on `DEBUG SLEEP`, `DEBUG RELOAD`, `DEBUG DIGEST`, `DEBUG DIGEST-VALUE` and `DEBUG SET-ACTIVE-EXPIRE` |

The logging level and destination are set in `logback.xml`, not here.

### Production sample (`/etc/j-redis/j-redis.conf`)

This is the shipped `conf/j-redis.conf` (`dist/conf/j-redis.conf`), without
its comments. Replace the `requirepass` value with a long random secret, for
example from `openssl rand -hex 32`.

```
port 6379
bind 127.0.0.1
requirepass CHANGE-ME-TO-A-LONG-RANDOM-SECRET
dir /var/lib/j-redis
appendonly yes
appendfsync everysec
appendfsync-interval-millis 1000
maxmemory 3gb
disable-command FLUSHALL
disable-command FLUSHDB
disable-command KEYS
disable-command SHUTDOWN
```

- `CONFIG` stays enabled. The backup procedure and tuning at run time need it,
  and it is behind the password.
- `SHUTDOWN` is disabled, because production stops go through systemd.
- `DEBUG` needs no `disable-command`: without `enable-debug-command yes`, the
  command does not exist.

## 3. Start-up and shutdown

```
java <JVM flags> -jar j-redis-server.jar /etc/j-redis/j-redis.conf [--directive value …]
bin/j-redis-server conf/j-redis.conf [--directive value …]      # the same, via the launcher
```

`--version` (or `-v`) prints the version, and `--help` (or `-h`) prints the
usage. The server logs `j-redis <version> ready (<n> keys)` when it is ready.

| Exit code | Meaning | Restart? |
|---|---|---|
| 0 | Clean shutdown | — |
| 1 | Configuration or start-up error | **No**: fix the configuration |
| 2 | The data directory is locked by another process | **No** |
| 3 | The data could not be loaded (corruption) | **No**: an operator decides ([§8](#8-runbook)) |
| 4 | Fail-stop: an internal error or an fsync failure ([02 §11](02-architecture.md#11-failure-handling-inside-a-command)) | Yes |

`SIGTERM` or `SIGINT` (Ctrl+C) runs the clean shutdown sequence
([02 §13](02-architecture.md#13-start-up-and-shutdown)). The JVM then exits
with **143** (128 + SIGTERM). That is why the unit file has
`SuccessExitStatus=143`. A fail-stop always exits with 4, even when logging
itself fails while the fail-stop is reported, so that systemd restarts the
server.

## 4. Running on Linux

This is `/etc/systemd/system/j-redis.service`, shipped as
`systemd/j-redis.service` in the distribution:

```ini
# Install: copy the distribution to /opt/j-redis, create the user (useradd --system jredis),
# copy conf/j-redis.conf to /etc/j-redis/j-redis.conf (set requirepass), then
#   cp systemd/j-redis.service /etc/systemd/system/ && systemctl daemon-reload && systemctl enable --now j-redis
#
# Requires Java 21 or newer. On RHEL 9:  dnf install java-21-openjdk-devel
# Check the ExecStart path matches your JDK:  readlink -f $(which java)
[Unit]
Description=j-redis-service
After=network.target

[Service]
User=jredis
# creates /var/lib/j-redis (data) and /var/log/j-redis (GC log, heap dumps) owned by User.
# Both private, and every file the server writes: the data holds every live session token, and a
# heap dump holds the data. With systemd's defaults (0755, umask 0022) the AOF was world-readable.
# On an existing install, also: chmod -R go= /var/lib/j-redis /var/log/j-redis
StateDirectory=j-redis
StateDirectoryMode=0700
LogsDirectory=j-redis
LogsDirectoryMode=0700
UMask=0077
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
  configuration, a held lock or corrupt data. Those need a human.
- `taskset -c 0-1` pins the server to cores 0 and 1, the cores reserved for
  j-redis in the host's CPU plan; the other services are pinned elsewhere.
  Widen it to 0–2 if `INFO cpu` shows the command thread above 50 %.
- Heap sizing is explained in [06 §6](06-expiry-and-memory.md#6-jvm-heap-sizing).
- Start order: j-redis first, then the services that use it. Put
  `After=j-redis.service` in their units. If j-redis restarts later, the
  clients reconnect on their own.
- `StateDirectory=` and `LogsDirectory=` make systemd create `/var/lib/j-redis`
  and `/var/log/j-redis`, owned by the service user. `StateDirectoryMode=0700`,
  `LogsDirectoryMode=0700` and `UMask=0077` keep both directories, and every
  file the server writes, private to that user.

## 5. Running on Windows (development)

Production is Linux only. On Windows, the server runs in a console.

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

- Stop the server with **Ctrl+C**, which runs the clean shutdown. Closing the
  window gives the JVM only a few seconds.
- Binding to `127.0.0.1` avoids the Windows Firewall prompt.
- The server skips the directory fsync on Windows
  ([08 §11](08-persistence.md#11-platform-notes)).

## 6. `INFO`

`INFO [section]` returns `field:value` lines, grouped under `# Section`
headers, as in Redis. With no section, it returns `server`, `clients`,
`memory`, `persistence`, `stats`, `replication`, `cpu` and `keyspace`.
`commandstats` appears only when you name it, or with `INFO all` (or
`INFO everything`).

| Section | Fields |
|---|---|
| `server` | `jredis_version`, `redis_mode` (`standalone`), `compatible_with`, `os`, `java_version`, `java_vendor`, `process_id`, `run_id`, `tcp_port`, `io_threads`, `uptime_in_seconds`, `uptime_in_days`, `config_file` |
| `clients` | `connected_clients`, `blocked_clients`, `pubsub_clients`, `watching_clients`, `maxclients`, `client_recent_max_output_bytes` |
| `memory` | `used_memory` (the estimate), `used_memory_human`, `maxmemory`, `maxmemory_human`, `maxmemory_policy`, `jvm_heap_used`, `jvm_heap_committed`, `jvm_heap_max`, `estimate_to_heap_ratio`, `compressed_oops` (1 under G1, 0 under ZGC), `gc_count`, `gc_time_ms` |
| `persistence` | `loading`, `loading_time_ms`, `aof_enabled`, `aof_dir`, `aof_fsync`, `aof_fsync_interval_ms`, `aof_generation`, `aof_base_file`, `aof_base_size`, `aof_incr_files`, `aof_incr_size`, `aof_pending_bytes`, `aof_last_write_status`, `aof_last_write_error`, `aof_last_fsync_age_ms`, `aof_rewrite_in_progress`, `aof_rewrite_state`, `aof_last_rewrite_status`, `aof_last_rewrite_time_sec`, `aof_rewrite_longest_record_ms`, `lastsave` |
| `stats` | `total_connections_received`, `total_commands_processed`, `instantaneous_ops_per_sec`, `total_net_input_bytes`, `total_net_output_bytes`, `rejected_connections`, `expired_keys`, `keyspace_hits`, `keyspace_misses`, `pubsub_channels`, `pubsub_patterns`, `client_output_limit_disconnects`, `total_error_replies` (the sum of the next two), `rejected_calls`, `failed_calls`, `auth_failures`, `internal_errors`, `aof_load_errors` |
| `replication` (2.2) | `role` (`primary` or `replica`), `replication_epoch`, `replid`. On a primary: `repl_offset`, `connected_replicas`, one `replicaN:ip=…,port=…,state=…,offset=…,lag=…` line per replica (its acknowledged offset, and the seconds since that acknowledgement), `full_syncs`, `partial_syncs`, `waiting_clients` (clients in `WAIT`), `repl_backlog_active`, `repl_backlog_size`, `repl_backlog_first_offset`. On a replica: `primary_host`, `primary_port`, `primary_link_status` (`up` or `down`), `primary_link_state`, `primary_last_io_seconds_ago`, `primary_sync_in_progress`, `replica_applied_offset`, `replica_apply_errors`, `last_sync_error` |
| `cpu` | `cmd_thread_cpu_percent`, `background_duty_percent`, and `used_cpu_process_seconds` where the JVM reports it |
| `commandstats` | One `cmdstat_<name>:calls=…,usec=…,usec_per_call=…,rejected_calls=…,failed_calls=…,p50=…,p99=…,p999=…` line per command that has been called or refused. The percentiles are in microseconds |
| `keyspace` | `db0:keys=…,expires=…,avg_ttl=0` |

The latency percentiles come from an HdrHistogram per command, recorded on the
command thread with no allocation.

### Slowlog

The server keeps the commands slower than `slowlog-log-slower-than`
microseconds in a ring of `slowlog-max-len` entries. Each entry holds an id, a
timestamp, the duration, the arguments (cut to 32 arguments of 128 bytes each),
and the client's address and name. Read it with `SLOWLOG GET [n]`; `SLOWLOG LEN`
and `SLOWLOG RESET` also work.

### Logging

The server logs with Logback, through an asynchronous appender. It **never**
logs command arguments at INFO or above, because they can contain tokens and
passwords.

| Level | Examples |
|---|---|
| ERROR | An internal command error, a persistence failure, a fail-stop |
| WARN | Backpressure engaged, an AOF cut at load, a slow subscriber disconnected, the AOF backlog wait, a failed `AUTH` (with the client's id and address) |
| INFO | Start-up, the load time, a rewrite's start and end, configuration changes |

## 7. Security

**Threat model.** j-redis is an internal service whose legitimate clients are
the backend's own processes. It must be protected against other things on the
network, and against a compromised or buggy client that exhausts resources.

| Measure | Detail |
|---|---|
| Bind to loopback | The default is `127.0.0.1`. In the backend's one-machine launch, every client is on the same machine ([01 C-4](01-requirements-and-scope.md#2-constraints)). A client or a replica on another machine needs `bind` to include a private address, with `requirepass` set. |
| Protected mode | While no password is set, a connection that is not from loopback gets `-DENIED …` and is closed |
| Password | `requirepass` + `AUTH`. The server compares the password with `MessageDigest.isEqual`, in constant time. It counts failures (`auth_failures` in `INFO stats`) and logs each one. |
| Secret handling | The password lives in the configuration file (mode `600`, owned by `jredis`), never on the command line, where `ps` would show it |
| Dangerous commands | Disabled in production ([§2](#2-configuration)) |
| Resource limits | `maxclients`, the query buffer, the output buffer and the bulk length: no single client can exhaust the heap |
| Hash flooding | SipHash-1-2 with a random key ([04 §3.2](04-data-structures.md#32-hashing)) |
| Pattern denial of service | The glob matcher never takes exponential time ([07 §1.3](07-pubsub-blocking-transactions.md#13-glob-patterns)) |
| TLS | Not built (2.2.2): `bind` keeps traffic on loopback or the private network. If TLS is ever needed, M8 would add Netty's `SslHandler` with `netty-tcnative`, which is already in the offline bundle. |

## 8. Runbook

| Symptom | Likely cause | Action |
|---|---|---|
| Clients get `-OOM` | The data exceeded `maxmemory` | Run `INFO memory` and `j-redis-cli --bigkeys`. Look for keys without a TTL (`expires` against `keys` in `INFO keyspace`). Raise `maxmemory` only after you find the cause. |
| Clients get `-MISCONF` | AOF writes are failing, usually because the disk is full | Free disk space. The server retries every second and recovers by itself; check that `aof_last_write_status` is `ok`. |
| Clients get `-READONLY` | The server is a replica (2.2) | Point the client at the primary, or give it every address so that it follows the primary ([16 §10](16-replication.md#10-clients-following-the-primary)) |
| Clients get `-NOREPLICAS` | Fewer replicas than `min-replicas-to-write` have acknowledged within `min-replicas-max-lag` (2.2) | Check the replicas' `INFO replication` and the link ([16 §8](16-replication.md#8-acknowledgements-wait-and-refusing-writes-without-a-replica)) |
| The process exits with 4 | Fail-stop | The log names the command and the client. systemd restarts the server, with consistent data. If it happens again, find and stop the client that sends that command, and file a bug. |
| It will not start: exit 3 | A corrupt data file | `j-redis-tools check-aof <data-dir>` names the file and the offset. `--fix` repairs a torn end of the last incr file. For corruption anywhere else, choose between restoring a backup and cutting the file by hand at the reported offset, which loses everything after it. |
| It will not start: exit 2 | Another instance owns the data directory | Stop the other instance |
| High latency | A slow command, a big key, garbage collection or heavy background work | Check `SLOWLOG GET`, the p99 in `INFO commandstats`, the duty in `INFO cpu`, `aof_rewrite_longest_record_ms` and the GC log |
| Pub/sub clients are disconnected | A slow subscriber hit the output limit | Fix the consumer. `client_output_limit_disconnects` counts these disconnects |
| The AOF keeps growing | Rewrites are not starting, or they fail | Check `aof_last_rewrite_status` and the automatic rewrite settings |

### Alerts

Alert on each of these:

- `used_memory / maxmemory` above 80 %;
- `aof_last_write_status` not `ok`, or `aof_last_fsync_age_ms` above 5000;
- `internal_errors` increasing;
- the p99 of `FAST` commands in `INFO commandstats` above 1000 (microseconds,
  that is 1 ms) for 5 minutes;
- `connected_clients` above 80 % of `maxclients`;
- any restart of the service.

## 9. Upgrades

- Base files and the manifest carry a format version. Incr files are RESP and
  stable.
- A server reads every older format it knows, and writes its own version at the
  next rewrite.
- **Upgrade:** back up ([08 §12](08-persistence.md#12-backup-and-restore)),
  stop the server, replace the jar, start it, and check `INFO server` and
  `INFO persistence`.
- **Downgrade:** restore the backup taken before the upgrade. An older server
  may not read a newer format.

[Guide 7](guide/07-upgrade-guide.md) has the full procedure and what each
release asks of an upgrade.
