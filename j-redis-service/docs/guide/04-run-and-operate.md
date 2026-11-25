# 4 — Run and operate the server

This guide shows how to start, configure, secure, deploy, back up, monitor and
repair a j-redis server, and how to run a replica and promote it. It is for
operators and for developers who run j-redis on their own machine. It starts
from a built distribution, `target/dist/j-redis-2.2.1/` (see
[guide 3 §3.5](03-build-and-test.md#35-package-the-distribution)). Paths such
as `bin/…` are relative to that directory. The reference tables for every
configuration directive, `INFO` field and runbook entry are in
[../11-operations-and-security.md](../11-operations-and-security.md).

**Inside the backend's repository and release**, two things differ:

- On a development machine, `./dev-jredis.sh build|start|stop|restart|status|test`,
  from the repository's root, builds j-redis and runs it from its own build on
  `127.0.0.1:6380`. Its data is in `work/run/store` and its log in
  `work/run/store.log`
  ([build and run §5](../../../docs/development/03-build-and-run.md#5-running-it-by-hand)).
- The backend's release carries j-redis itself. Two instances,
  `backend-store@session` and `backend-store@events`, run
  `/opt/backend/jredis/lib/j-redis-server.jar` as the user `jredis`. Each one
  reads its configuration from `/etc/backend/store-<name>.conf`. The backend
  has its own scripts for backups and promotion (`backup-store.sh`,
  `promote-store.sh`). Read
  [operations/01 §3](../../../docs/operations/01-deploy.md#3-j-redis-built-and-tested)
  first; it uses this guide for the store's own settings.

## 4.1 Quick start

Start the server with the development configuration:

```bash
# Linux
bin/j-redis-server conf/j-redis-dev.conf
```

```bat
rem Windows
bin\j-redis-server.cmd conf\j-redis-dev.conf
```

The server logs `j-redis 2.2.1 ready (0 keys)` and listens on `127.0.0.1:6379`.
Then, in a second terminal:

```bash
bin/j-redis-cli ping                 # PONG
bin/j-redis-cli set hello world      # OK
bin/j-redis-cli get hello            # "world"
bin/j-redis-examples -p 6379         # the client examples, against this server
```

The development configuration, `conf/j-redis-dev.conf`, keeps its data in
`data` under the current directory and turns on the `DEBUG` command
(`enable-debug-command yes`). Use it only on your own machine.

**Stopping.** Press Ctrl+C in the server's terminal, or send `SIGTERM`
(`kill <pid>`, `systemctl stop`). Both run the clean shutdown: the server stops
accepting clients, finishes writing the AOF (append-only file, its data file),
calls fsync (forces the file to disk) and exits. A clean stop loses nothing. On
Linux the exit status is then 143 (128 + SIGTERM). This is normal for Java. The
`SHUTDOWN` command also works, unless it is disabled, as it is in the
production configuration.

**Without the distribution**, the jar runs on its own:

```bash
java -Xms1g -Xmx1g -XX:+UseZGC -XX:+ZGenerational --add-opens java.base/java.nio=ALL-UNNAMED -jar j-redis-server-2.2.1-all.jar --port 6379 --dir ./data
```

The build makes this jar in `j-redis-server/target/`. The launcher
`bin/j-redis-server` runs the same command. The `--add-opens` option is
required: without it, Netty (the network library) cannot use its fast path for
direct buffers.

**Server command line.** `j-redis-server [config-file] [--directive value ...]`.

| Option | Meaning |
|---|---|
| `config-file` | A configuration file ([§4.2](#42-configuration)). It must come first. |
| `--directive value` | Sets any directive, for example `--port 6380 --appendonly no`. It overrides the file. |
| `--version`, `-v` | Prints `j-redis-server 2.2.1` and exits |
| `--help`, `-h` | Prints the usage and exits |

| Exit code | Meaning |
|---|---|
| 0 | Clean shutdown |
| 1 | Configuration or start-up error |
| 2 | The data directory is locked by another server |
| 3 | The data could not be loaded |
| 4 | Fail-stop: an internal error or a failed fsync stopped the server |

[§4.11](#411-troubleshooting) says what to do for each.

## 4.2 Configuration

The configuration is a text file in the same style as Redis. Each line holds
one directive and its value (`directive value`). A line that starts with `#` is
a comment, and a value with spaces goes in quotes. Pass the file as the first
argument. You can also give any directive on the command line as
`--directive value`. The command line overrides the file:

```bash
bin/j-redis-server conf/j-redis.conf --port 6380 --dir /tmp/j-redis-test
```

The directives you will change most often:

| Directive | Default | Meaning |
|---|---|---|
| `port` | `6379` | TCP port. `0` means any free port; `INFO server` reports it as `tcp_port`. |
| `bind` | `127.0.0.1` | Addresses to listen on. Up to 16 on one line; `*` means every IPv4 address (`0.0.0.0`). |
| `requirepass` | *(none)* | The password clients must send with `AUTH` |
| `dir` | `./data` | Data directory |
| `appendonly` | `yes` | Persistence on or off. With `no`, the data lives in memory only and is lost on stop. |
| `appendfsync` | `everysec` | `everysec`: fsync at a fixed period. `no`: leave it to the operating system. |
| `appendfsync-interval-millis` | `1000` | The fsync period for `everysec`, from 10 to 60000 ms. It limits how much a power failure can lose. |
| `maxmemory` | `0` (unlimited) | The limit on the data's estimated size. Above it, writes fail with `-OOM`. |
| `maxclients` | `1000` | The connection limit |
| `timeout` | `0` | Closes a client that is idle for this many seconds. `0` means never. |
| `disable-command` | *(none)* | One or more command names. You can repeat the line. A disabled command looks unknown to clients. A name that is not a command stops start-up with an error. |
| `enable-debug-command` | `no` | Turns on `DEBUG SLEEP`, `RELOAD`, `DIGEST`, `DIGEST-VALUE` and `SET-ACTIVE-EXPIRE`. For development only. |

Sizes are a plain number of bytes, or a number with a unit. `k`, `m` and `g`
multiply by 1000; `kb`, `mb` and `gb` multiply by 1024. Case does not matter:
`maxmemory 3gb` is 3 × 1024³ bytes. The full list of directives is in
[../11 §2](../11-operations-and-security.md#2-configuration).

**At runtime.** `CONFIG GET <pattern>` reads settings. `CONFIG SET <name>
<value> [<name> <value> …]` changes the directives that the full list marks
*yes* in its `CONFIG SET` column. It applies all the pairs or none of them: it
checks every pair first. The changes last until the next restart. The server
does **not** write them back to the file (it has no `CONFIG REWRITE`), so edit
the file too.

```bash
bin/j-redis-cli config get 'maxmem*'
bin/j-redis-cli config set slowlog-log-slower-than 5000      # microseconds
```

`CONFIG RESETSTAT` sets the command statistics back to zero.

**Logging** goes to standard output. The environment variable
`JREDIS_LOG_LEVEL` sets the level (default `INFO`). For a different format, or
for a log file, pass your own Logback file in the JVM (Java virtual machine)
options:
`JREDIS_JAVA_OPTS="-Xmx1g -Dlogback.configurationFile=/etc/j-redis/logback.xml"`.
`JREDIS_JAVA_OPTS` replaces the launcher's default options
(`-Xms1g -Xmx1g -XX:+UseZGC -XX:+ZGenerational`), so give the heap size again.
The server never logs command arguments at INFO or above, because they may
contain secrets.

## 4.3 Security checklist

Check these points before a server takes real traffic:

- **Keep `bind 127.0.0.1`** unless clients run on other hosts. With no password,
  *protected mode* refuses every client that does not come from the loopback
  interface anyway, with `-DENIED`.
- **Set `requirepass`** to a long random value (`openssl rand -hex 32`). The
  production file ships with `requirepass CHANGE-ME-TO-A-LONG-RANDOM-SECRET`;
  replace it. Keep the password in the configuration file, readable only by
  the server (see [§4.4](#44-production-on-linux-systemd)). Never put it on the
  server's command line, where `ps` shows it.
- **Disable what production does not need**: `disable-command FLUSHALL`,
  `FLUSHDB`, `KEYS` and `SHUTDOWN`, as `conf/j-redis.conf` does. `DEBUG` does
  not exist unless `enable-debug-command yes` is set.
- **Remember that traffic is not encrypted.** That is fine on one host or on a
  private network, but not across networks you do not trust.

## 4.4 Production on Linux (systemd)

Production runs on Linux under systemd. These steps install the distribution
in `/opt`, the configuration in `/etc/j-redis`, and the service unit:

```bash
# 1. a user and the program
sudo useradd --system --home /var/lib/j-redis --shell /usr/sbin/nologin jredis
sudo tar -xzf j-redis-2.2.1.tar.gz -C /opt
sudo ln -sfn /opt/j-redis-2.2.1 /opt/j-redis          # upgrades swap this link (guide 7)

# 2. the configuration (set requirepass!)
sudo mkdir -p /etc/j-redis
sudo cp /opt/j-redis/conf/j-redis.conf /etc/j-redis/j-redis.conf
sudo chown root:jredis /etc/j-redis/j-redis.conf && sudo chmod 640 /etc/j-redis/j-redis.conf
sudo editor /etc/j-redis/j-redis.conf

# 3. the service (edit the java path in ExecStart if your JDK is elsewhere)
sudo cp /opt/j-redis/systemd/j-redis.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now j-redis

# 4. check
systemctl status j-redis
journalctl -u j-redis -f                                # the log
/opt/j-redis/bin/j-redis-cli -a "$SECRET" info server
```

`make-dist.sh` writes `j-redis-2.2.1.tar.gz` beside the directory, in
`target/dist/`. The unit needs Java 21 or newer. Its `ExecStart` runs
`/usr/lib/jvm/java-21-openjdk/bin/java`; on RHEL 9, `dnf install
java-21-openjdk-devel` installs that JDK. To find your JDK's path, run
`readlink -f $(which java)`.

What the unit, `systemd/j-redis.service`, does:

| Setting | Effect |
|---|---|
| `User=jredis` | The server runs as `jredis` |
| `StateDirectory`, `LogsDirectory`, both mode `0700`; `UMask=0077` | systemd creates `/var/lib/j-redis` (data) and `/var/log/j-redis` (GC log, heap dumps). Both are private, and so is every file the server writes, because the data holds secrets such as session tokens. On an install made before this unit, also run `chmod -R go= /var/lib/j-redis /var/log/j-redis`. |
| `ExecStart` | `taskset -c 0-1` keeps the JVM on CPUs 0 and 1. The JVM runs with `-Xms8g -Xmx8g`, generational ZGC (the Z garbage collector), `-XX:+AlwaysPreTouch`, a heap dump on out-of-memory and a GC log in `/var/log/j-redis`. It reads `/etc/j-redis/j-redis.conf`. |
| `Restart=on-failure`, `RestartSec=2` | systemd restarts the server 2 s after a fail-stop (exit 4) |
| `RestartPreventExitStatus=1 2 3` | systemd does **not** restart after exit 1, 2 or 3. These need a person ([§4.11](#411-troubleshooting)). |
| `SuccessExitStatus=143` | Exit 143, after a clean shutdown started by `SIGTERM`, counts as success |
| `LimitNOFILE=65536`, `TimeoutStopSec=60` | Up to 65536 open files; up to 60 s for a clean stop |

**Heap size.** The unit sets `-Xms8g -Xmx8g`, and the production configuration
sets `maxmemory 3gb`. Keep the data estimate below about 40 % of the heap: the
snapshot's copies and garbage collection need the rest. For other sizes, keep
that ratio ([../06 §6](../06-expiry-and-memory.md#6-jvm-heap-sizing)).

## 4.5 Windows (development)

Production is Linux. On Windows, run the server in a console and stop it with
**Ctrl+C**:

```bat
set JREDIS_JAVA_OPTS=-Xms512m -Xmx512m -XX:+UseZGC -XX:+ZGenerational
bin\j-redis-server.cmd conf\j-redis-dev.conf --port 6380
```

`JREDIS_JAVA_OPTS` holds the JVM options, as on Linux. Do not close the window
to stop the server: that gives the JVM only a few seconds to shut down.

The data directory (`data` in the development configuration) is relative to
the current directory. Windows cannot fsync a directory, so the server skips
that step there. Nothing else differs.

## 4.6 The command-line client

`j-redis-cli` sends commands to a server, one at a time or in an interactive
shell. By default it connects to `127.0.0.1:6379`.

```bash
bin/j-redis-cli                                  # interactive, 127.0.0.1:6379
bin/j-redis-cli -h 10.0.0.5 -p 6380 -a secret    # another server, with a password
bin/j-redis-cli incr visits                      # one command; exit code 1 if it returns an error
bin/j-redis-cli --raw lrange q:jobs 0 -1         # plain values, one per line (for scripts)
bin/j-redis-cli --scan --pattern 'sess:*'        # list keys safely with SCAN
bin/j-redis-cli --bigkeys                        # the largest key of each type
bin/j-redis-cli --latency                        # continuous PING round trips (Ctrl+C to stop)
```

| Option | Meaning |
|---|---|
| `-h <host>`, `-p <port>` | The server (default `127.0.0.1`, `6379`) |
| `-a <password>` | Sends `AUTH` first. The CLI exits with 1 if it fails. |
| `--raw` | Prints plain values, one per line, without quotes or numbering |
| `--scan [--pattern <glob>] [--count <n>]` | Lists keys with `SCAN` (`COUNT` 100 by default), never blocking the server as `KEYS` does |
| `--bigkeys` | Walks the keys with `SCAN` and prints the largest key of each type |
| `--latency` | Sends `PING` every 10 ms and prints the minimum, maximum and average round trip each second |
| `--help` | Prints the usage |

With a command, the CLI prints the reply and exits: 0 on success, 1 if the reply
is an error or the server cannot be reached.

The interactive shell takes the same syntax as redis-cli. Put quotes around a
value with spaces (`set greeting "hello world"`). `MULTI`/`EXEC`, `WATCH`,
`BLPOP` and `SUBSCRIBE` all work there; after `SUBSCRIBE`, the shell prints
messages until you press Ctrl+C. `HELP` prints a short help, and `EXIT` or
`QUIT` leaves. Replies look like this:

```
127.0.0.1:6379> zadd lb 10 alice 20 bob
(integer) 2
127.0.0.1:6379> zrevrange lb 0 -1 withscores
1) "bob"
2) "20"
3) "alice"
4) "10"
```

Useful administration commands:

| Command | Shows or does |
|---|---|
| `INFO [section]` | Sections server, clients, memory, persistence, stats, replication, cpu, keyspace; `commandstats` only when named, or with `INFO all` |
| `DBSIZE` | The number of keys |
| `CLIENT LIST` / `CLIENT KILL ID <id>` | The connected clients / disconnect one |
| `SLOWLOG GET 10` / `SLOWLOG LEN` / `SLOWLOG RESET` | The slowest recent commands (slower than `slowlog-log-slower-than`, 10000 µs by default) |
| `CONFIG GET *` | Every setting |
| `BGREWRITEAOF` | Compacts the AOF now, in the background |
| `LASTSAVE` | The time (Unix seconds) of the last completed rewrite |

## 4.7 Persistence in practice

The server writes every change to disk, so a restart finds the same data. The
data directory holds these files:

```
manifest          which files make up the data (text, human-readable)
base.N.jrdb       a snapshot of all keys at one moment (binary, CRC-protected)
incr.N.aof        every change since that snapshot, as RESP commands (text-like)
LOCK              held while a server uses the directory
replication       the replication epoch (2.2): written when the server is promoted, or syncs from a primary with a higher epoch
```

RESP (REdis Serialization Protocol) is the wire format of Redis commands. A CRC
(cyclic redundancy check) detects damaged bytes.

- **Every write** goes to the end of the current `incr` file. The server calls
  fsync every `appendfsync-interval-millis` (1000 ms by default). A **power
  failure** can lose about that much. A **process crash** (`kill -9`) can lose
  the last few milliseconds of acknowledged writes. A **clean stop** loses
  nothing.
- **A rewrite** writes a new base file in the background. It starts by itself
  when the incr files together reach the size of the base file
  (`auto-aof-rewrite-percentage 100`) and are at least 64 MB
  (`auto-aof-rewrite-min-size`). It does not use `fork()` and does not pause
  clients, except very briefly for very large keys
  ([../08 §6](../08-persistence.md#6-the-big-key-caveat)). `BGREWRITEAOF`
  starts one by hand. After a failed rewrite, automatic ones wait 1 s, then
  twice as long each time, up to 1 hour.
- **Start-up** loads the base file and replays the incr files. A crash in the
  middle of a write can leave a torn last record. The server cuts it off and
  logs a warning, because `aof-load-truncated` is `yes` by default.

## 4.8 Backup and restore

You can back up a **running** server. This script makes a consistent copy:

```bash
#!/bin/sh
# backup-j-redis.sh <backup-dir>   (run as a user that can read /var/lib/j-redis)
set -eu
CLI="/opt/j-redis/bin/j-redis-cli -a $SECRET"
DEST="$1/j-redis-$(date +%Y%m%d-%H%M%S)"
OLD=$($CLI --raw config get auto-aof-rewrite-percentage | tail -1)
$CLI config set auto-aof-rewrite-percentage 0          # no rewrite may delete files during the copy
trap '$CLI config set auto-aof-rewrite-percentage "$OLD"' EXIT
mkdir -p "$DEST"
cp /var/lib/j-redis/manifest "$DEST/"                   # the manifest FIRST
for f in $(awk '$1=="base"||$1=="incr"{print $2}' /var/lib/j-redis/manifest); do
    cp "/var/lib/j-redis/$f" "$DEST/"
done
echo "backup in $DEST"
```

Why the copy is consistent:

1. The script turns automatic rewrites off. Only a rewrite deletes the files
   the manifest names, so none disappears during the copy.
2. It copies the manifest first, then every file the manifest names.
3. An incr file copied while the server appends to it only has a torn tail.
   Loading accepts that.
4. On exit, the `trap` sets `auto-aof-rewrite-percentage` back to its old
   value.

To make the backup smaller, you can run `BGREWRITEAOF` first and wait until
`INFO persistence` shows `aof_rewrite_in_progress:0`
([../08 §12](../08-persistence.md#12-backup-and-restore)).

**Restore:**

1. Stop the server.
2. Replace the contents of the data directory with the backup.
3. Start the server.

To check a backup, or any data directory, without a server, run
`bin/j-redis-tools check-aof <dir>` ([§4.10](#410-the-tools)).

## 4.9 Monitoring

Read `INFO` regularly, for example every 10 s, and alert on these conditions:

| Condition | Why |
|---|---|
| `used_memory` > 80 % of `maxmemory` | Writes will start failing with `-OOM` |
| `aof_last_write_status` ≠ `ok`, or `aof_last_fsync_age_ms` > 5000 | A disk problem. While the AOF cannot be written, writes (and `PING`) fail with `-MISCONF`. A failed fsync stops the server (exit 4). |
| `internal_errors` increasing | A bug: a command failed inside the server without changing data. The log names the command. |
| A `cmdstat_<command>` line in `INFO commandstats` with `p99` above 1000 (microseconds, so 1 ms) for a fast command | A latency problem (`SLOWLOG`, big keys, garbage collection) |
| `connected_clients` > 80 % of `maxclients` | A client leaks connections |
| `aof_rewrite_longest_record_ms` growing | A key is getting too big |

The client library has its own counters and latency histograms
(`client.metrics()`, [guide 5 §5.13](05-client-guide.md#513-configuration-and-metrics)).
Every `INFO` field is listed in
[../11 §6](../11-operations-and-security.md#6-info).

## 4.10 The tools

`j-redis-tools` holds three tools: `benchmark` (a load generator), `check-aof`
(checks data files) and `dump` (prints a base file).

```bash
# load test (see the notes below)
bin/j-redis-tools benchmark -c 50 -n 100000 -t set,get
bin/j-redis-tools benchmark -c 50 -n 1000000 -P 16 -r 100000 -t zadd,zincrby,zrevrank,zaround -q

# data files
bin/j-redis-tools check-aof /var/lib/j-redis           # exit 0 = healthy (server stopped)
bin/j-redis-tools check-aof /var/lib/j-redis --fix     # cut a torn tail of the last incr file (server stopped)
bin/j-redis-tools dump /var/lib/j-redis | head         # one line per key in the base file
bin/j-redis-tools dump --values --max-elements 5 /var/lib/j-redis/base.3.jrdb
```

**`check-aof [--fix] <data-dir | file.aof>`** checks the manifest, the base
file's CRC and every incr file. On a data directory it refuses to run while a
server uses the directory (the `LOCK` file), so stop the server first. Only the
last incr file may end in a torn record; `--fix` cuts it at the last complete
command. Any other damage is reported, not fixed. Exit codes: 0 healthy, 1 a
problem found (or fixed), 2 wrong usage.

**`dump [--values] [--max-elements n] <base-file.jrdb | data-dir>`** prints one
line per key: type, key, TTL (time to live) and size. `--values` also prints
the contents, up to `--max-elements` elements per key (default 20). It only
reads the file, so it is safe while the server runs. If there is no base file
yet, it says so.

**`benchmark`** options:

| Option | Meaning | Default |
|---|---|---|
| `-h`, `-p`, `-a` | Server host, port, password | `127.0.0.1`, `6379`, none |
| `-c` | Parallel connections | 50 |
| `-n` | Requests per test | 100000 |
| `-P` | Requests in flight per connection (pipelining) | 1 (no pipelining) |
| `-d` | Value size in bytes for `SET`, `LPUSH` and the like | 3 |
| `-r` | Random keys or members from `[0, r)`; `0` means one fixed key | 0 |
| `-t` | Comma-separated tests: `ping`, `set`, `get`, `incr`, `lpush`, `rpush`, `lpop`, `rpop`, `sadd`, `hset`, `zadd`, `zincrby`, `zrevrank`, `zaround` | all |
| `--threads` | Client I/O threads | 2 |
| `-q` | One line per test | off |

Measure on the production machine with nothing else running. On a shared or
busy machine, the time threads take to wake up dominates, and the numbers mean
little.

The launcher `bin/j-redis-tools` gives the tools `-Xmx2g`; set
`JREDIS_TOOLS_JAVA_OPTS` to change it.

## 4.11 Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Exit 1, `Address already in use` | Something else listens on the port | Stop it, or use `--port` |
| Exit 1, `configuration error: …` | An invalid directive or value | The message names the file and line, or the `--directive` on the command line |
| Exit 2, `data directory … is in use` | Another server uses the same `dir` | Stop it, or use another `dir` |
| Exit 3, `cannot load data: …` | A corrupt data file | Run `bin/j-redis-tools check-aof <dir>`; see the runbook in [../11 §8](../11-operations-and-security.md#8-runbook) |
| Exit 4 | Fail-stop after an internal error while a command was changing data, or after a failed fsync | systemd restarts it, and the restart reloads the last consistent state. The log line starting `FATAL:` names the cause. Report an internal error as a bug. |
| Clients get `-DENIED …protected mode` | A client from another host, and no password set | Set `requirepass` (and `bind` to the right address) |
| Clients get `-NOAUTH` | The server requires a password, and the client did not send it | Set `password(...)` in the client builder, or `-a` in the CLI |
| Clients get `-OOM command not allowed…` | `maxmemory` reached | Look at `INFO memory`, `--bigkeys`, and keys without a TTL |
| Clients get `-MISCONF …` | The server cannot write the AOF (disk full?) | Free disk space. The server recovers by itself. |
| `-ERR max number of clients reached` | `maxclients` reached | Look for a client that leaks connections (`CLIENT LIST`) |

## 4.12 Replication: a primary and a replica (2.2)

A replica holds a copy of its primary's data on another machine. The primary
sends it every change, so the copy stays current. If the primary's machine is
lost, an operator promotes the replica to primary
([16](../16-replication.md)). Both servers must run 2.2 or later.

**Configure** the replica with its primary's address and the password it sends
there:

```
# /etc/j-redis/j-redis.conf on the replica's machine
replicaof 10.0.0.1 6379
primaryauth <the primary's requirepass>
requirepass <this server's own>
```

Then:

1. Start the primary, then the replica.
2. On the replica, `ROLE` shows `connected` once its copy is complete.
   `INFO replication` shows `primary_link_status:up`.
3. On the primary, `INFO replication` lists the replica with its acknowledged
   offset and its lag in seconds.

The link between them carries all data in plain text, as client connections
do. Keep it on the private network.

**Make a write durable** beyond the primary's machine: send
`WAIT 1 <timeout ms>` after it. The reply is the number of replicas that hold
the write. `min-replicas-to-write 1` goes further: the primary refuses every
write with `-NOREPLICAS` while its replica is away (no acknowledgement for
`min-replicas-max-lag` seconds, 10 by default). That trades availability for
the guarantee.

**Promote** the replica when the primary's machine is lost. Follow this order:

1. **Fence the old primary.** Make sure it is down, or cut off from every
   client: stop it, power it off, or block its port with the firewall. The
   store cannot prevent one failure: two primaries that both take writes.
2. On the replica, run `j-redis-cli -a … REPLICAOF NO ONE`. The replica raises
   its epoch (a number that grows with each promotion), records it in the
   `replication` file in its data directory, and accepts writes.
3. **Edit its configuration file** to remove `replicaof`. The role at start-up
   comes from the file, and there is no `CONFIG REWRITE`. If you miss this
   step, a restart makes the server a replica of the old primary again. The
   epoch then refuses that sync, so the data stays intact and read-only until
   you fix the file.
4. Point the clients at it. Clients built with both addresses in
   `addresses(...)` find the new primary by themselves
   ([guide 5 §5.12](05-client-guide.md#512-errors-timeouts-and-reconnection)).
5. When the old machine returns, configure it as a replica of the new primary
   (`replicaof <new> 6379`) **before** you start it. It then syncs in full and
   takes the new epoch.

A server with `appendonly no` has no data directory to keep its epoch in, so
its epoch starts at 0 each time. Do not promote such a server.
