# 4 — Run and operate the server

This guide starts from a built distribution (`target/dist/j-redis-2.1.0/`, see
[guide 3 §3.5](03-build-and-test.md#35-package-the-distribution)). Paths such
as `bin/…` are relative to that directory. The reference tables for every
configuration directive, `INFO` field and runbook entry are in
[../11-operations-and-security.md](../11-operations-and-security.md).

## 4.1 Quick start

```bash
# Linux
bin/j-redis-server conf/j-redis-dev.conf
```

```bat
rem Windows
bin\j-redis-server.cmd conf\j-redis-dev.conf
```

The server logs `j-redis 2.1.0 ready (0 keys)` and listens on `127.0.0.1:6379`.
In a second terminal:

```bash
bin/j-redis-cli ping                 # PONG
bin/j-redis-cli set hello world      # OK
bin/j-redis-cli get hello            # "world"
bin/j-redis-examples -p 6379         # the client examples, against this server
```

**Stopping.** Press Ctrl+C in the server's terminal, or send `SIGTERM`
(`kill <pid>`, `systemctl stop`). Both run the clean shutdown: stop accepting
clients, finish the AOF, fsync, exit. On Linux the exit status is then 143
(128 + SIGTERM), which is normal for Java. The `SHUTDOWN` command also works
unless it is disabled (as it is in the production config).

**Without the distribution**, the jar runs on its own:

```bash
java -Xms1g -Xmx1g -XX:+UseZGC -XX:+ZGenerational --add-opens java.base/java.nio=ALL-UNNAMED -jar j-redis-server-2.1.0-all.jar --port 6379 --dir ./data
```

## 4.2 Configuration

A text file, one `directive value` per line, `#` for comments, in the same
style as Redis. Pass it as the first argument. Any directive can also be given
on the command line as `--directive value`, which overrides the file:

```bash
bin/j-redis-server conf/j-redis.conf --port 6380 --dir /tmp/j-redis-test
```

The directives you will touch most:

| Directive | Default | Meaning |
|---|---|---|
| `port` | `6379` | TCP port (`0` = any free port, reported in `INFO server`) |
| `bind` | `127.0.0.1` | Addresses to listen on (several allowed) |
| `requirepass` | *(none)* | Password clients must send with `AUTH` |
| `dir` | `./data` | Data directory |
| `appendonly` | `yes` | Persistence on/off (`no`: memory only, lost on stop) |
| `appendfsync` | `everysec` | `everysec`: fsync periodically; `no`: leave it to the OS |
| `appendfsync-interval-millis` | `1000` | The fsync period for `everysec`, 10–60000 ms. It bounds data loss on a power failure. |
| `maxmemory` | `0` (unlimited) | Data size limit (estimate); writes fail with `-OOM` above it |
| `maxclients` | `1000` | Connection limit |
| `timeout` | `0` | Close idle clients after N seconds (`0` = never) |
| `disable-command` | *(none)* | Repeatable; the command then looks unknown to clients. A misspelled name stops start-up with an error. |
| `enable-debug-command` | `no` | Enables `DEBUG SLEEP/RELOAD/SET-ACTIVE-EXPIRE` (development only) |

Sizes accept `k`/`m`/`g` (×1000) and `kb`/`mb`/`gb` (×1024): `maxmemory 3gb`.
The full list is in [../11 §2](../11-operations-and-security.md#2-configuration).

**At runtime.** `CONFIG GET <pattern>` reads settings, and `CONFIG SET <name>
<value> [<name> <value> …]` changes those marked *yes* in the full list, all or
nothing. Such changes last until the next restart: they are **not** written
back to the file, so edit the file too.

```bash
bin/j-redis-cli config get 'maxmem*'
bin/j-redis-cli config set slowlog-log-slower-than 5000
```

**Logging** goes to standard output. The level comes from the environment
variable `JREDIS_LOG_LEVEL` (default `INFO`). For a different format or a log
file, pass your own Logback file:
`JREDIS_JAVA_OPTS="-Xmx1g -Dlogback.configurationFile=/etc/j-redis/logback.xml"`.
Command arguments are never logged at INFO or above, because they may contain
secrets.

## 4.3 Security checklist

- Keep `bind 127.0.0.1` unless clients run on other hosts. With no password,
  *protected mode* refuses non-loopback clients anyway (`-DENIED`).
- Set `requirepass` to a long random value (`openssl rand -hex 32`). Keep it in
  the config file with mode `600`, never on the command line, where `ps` shows
  it.
- Disable what production does not need: `disable-command FLUSHALL`,
  `FLUSHDB`, `KEYS`, `SHUTDOWN` (as in `conf/j-redis.conf`). `DEBUG` is off
  unless `enable-debug-command yes`.
- Traffic is not encrypted. That is fine on one host or a private network, but
  not across untrusted networks.

## 4.4 Production on Linux (systemd)

```bash
# 1. a user and the program
sudo useradd --system --home /var/lib/j-redis --shell /usr/sbin/nologin jredis
sudo tar -xzf j-redis-2.1.0.tar.gz -C /opt
sudo ln -sfn /opt/j-redis-2.1.0 /opt/j-redis          # upgrades swap this link (guide 7)

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

The unit creates `/var/lib/j-redis` (data) and `/var/log/j-redis` (GC log,
heap dumps), restarts the server after a fail-stop (exit 4), and deliberately
does **not** restart after exit 1, 2 or 3, which need a human
([§4.11](#411-troubleshooting)).

**Heap size.** The unit sets `-Xms8g -Xmx8g` with `maxmemory 3gb`. The data
estimate should stay below about 40 % of the heap, because the snapshot's
copies and garbage collection need headroom. For other sizes, keep that ratio
([../06 §6](../06-expiry-and-memory.md#6-jvm-heap-sizing)).

## 4.5 Windows (development)

Production is Linux. On Windows, run the server in a console with
`bin\j-redis-server.cmd conf\j-redis-dev.conf` and stop it with **Ctrl+C**.
Closing the window instead gives the JVM only a few seconds to shut down. JVM
options go in `JREDIS_JAVA_OPTS`:

```bat
set JREDIS_JAVA_OPTS=-Xms512m -Xmx512m -XX:+UseZGC -XX:+ZGenerational
bin\j-redis-server.cmd conf\j-redis-dev.conf --port 6380
```

The data directory (`data` in the dev config) is relative to the current
directory. Directory fsync does not exist on Windows and is skipped; nothing
else differs.

## 4.6 The command-line client

```bash
bin/j-redis-cli                                  # interactive, 127.0.0.1:6379
bin/j-redis-cli -h 10.0.0.5 -p 6380 -a secret    # another server, with a password
bin/j-redis-cli incr visits                      # one command; exit code 1 if it returns an error
bin/j-redis-cli --raw lrange q:jobs 0 -1         # plain values, one per line (for scripts)
bin/j-redis-cli --scan --pattern 'sess:*'        # list keys safely with SCAN
bin/j-redis-cli --bigkeys                        # the largest key of each type
bin/j-redis-cli --latency                        # continuous PING round trips (Ctrl+C to stop)
```

Interactive mode takes redis-cli syntax, with quotes for spaces
(`set greeting "hello world"`). `MULTI`/`EXEC`, `WATCH`, `BLPOP` and
`SUBSCRIBE` all work there. Replies look like this:

```
127.0.0.1:6379> zadd lb 10 alice 20 bob
(integer) 2
127.0.0.1:6379> zrevrange lb 0 -1 withscores
1) "bob"
2) "20"
3) "alice"
4) "10"
```

Handy administration commands:

| Command | Shows or does |
|---|---|
| `INFO [section]` | server, clients, memory, persistence, stats, commandstats, cpu, keyspace |
| `DBSIZE` | number of keys |
| `CLIENT LIST` / `CLIENT KILL ID <id>` | connected clients / disconnect one |
| `SLOWLOG GET 10` / `SLOWLOG RESET` | the slowest recent commands |
| `CONFIG GET *` | every setting |
| `BGREWRITEAOF` | compact the AOF now (runs in the background) |
| `LASTSAVE` | time of the last completed rewrite |

## 4.7 Persistence in practice

The data directory holds:

```
manifest          which files make up the data (text, human-readable)
base.N.jrdb       a snapshot of all keys at one moment (binary, CRC-protected)
incr.N.aof        every change since that snapshot, as RESP commands (text-like)
LOCK              held while a server uses the directory
```

- Every write is appended to the current `incr` file and fsynced every
  `appendfsync-interval-millis`. A **power failure** can lose about that much.
  A **process crash** (`kill -9`) can lose the last few milliseconds of
  acknowledged writes. A **clean stop** loses nothing.
- When the incr files grow to twice the base size (and at least 64 MB), a
  **rewrite** writes a new base in the background, without `fork()` and
  without pausing clients (except very briefly for very large keys,
  [../08 §6](../08-persistence.md#6-the-big-key-caveat)). `BGREWRITEAOF` starts
  one by hand.
- Start-up loads the base and replays the incr files. A torn last record, left
  by a crash in the middle of a write, is cut off with a warning.

## 4.8 Backup and restore

A consistent backup of a **running** server:

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

Copying the manifest first and blocking rewrites makes the copy consistent. An
incr file copied while it is being appended to just has a torn tail, which
loading tolerates.

**Restore:** stop the server, replace the contents of the data directory with
the backup, and start it. Check a backup (or any data directory) offline with
`bin/j-redis-tools check-aof <dir>`.

## 4.9 Monitoring

Poll `INFO` (for example every 10 s) and alert on:

| Condition | Why |
|---|---|
| `used_memory` > 80 % of `maxmemory` | Writes will start failing with `-OOM` |
| `aof_last_write_status` ≠ `ok`, or `aof_last_fsync_age_ms` > 5000 | Disk problem; writes fail with `-MISCONF` |
| `internal_errors` increasing | A bug; see the log |
| `commandstats` p99 above 1 ms for fast commands | Latency problem (`SLOWLOG`, big keys, GC) |
| `connected_clients` > 80 % of `maxclients` | Connection leak in a client |
| `aof_rewrite_longest_record_ms` growing | A key is getting too big |

The client library has its own counters and latency histograms
(`client.metrics()`, [guide 5 §5.13](05-client-guide.md#513-configuration-and-metrics)).

## 4.10 The tools

```bash
# load test (see the notes below)
bin/j-redis-tools benchmark -c 50 -n 100000 -t set,get
bin/j-redis-tools benchmark -c 50 -n 1000000 -P 16 -r 100000 -t zadd,zincrby,zrevrank,zaround -q

# data files (server stopped)
bin/j-redis-tools check-aof /var/lib/j-redis           # exit 0 = healthy
bin/j-redis-tools check-aof /var/lib/j-redis --fix     # cut a torn tail of the last incr file
bin/j-redis-tools dump /var/lib/j-redis | head         # one line per key in the base file
bin/j-redis-tools dump --values --max-elements 5 /var/lib/j-redis/base.3.jrdb
```

Benchmark options: `-c` connections, `-n` requests per test, `-P` requests in
flight per connection (pipelining), `-d` value size, `-r` random key space
(`0` = one key), `-t` tests, `-q` one line per test. Measure on the production
machine with nothing else running: on a shared or busy machine, thread
wake-ups dominate and the numbers mean little.

## 4.11 Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Exit 1, `Address already in use` | Something else listens on the port | Stop it, or use `--port` |
| Exit 1, `configuration error: …` | Invalid directive or value | The message names the line |
| Exit 2, `data directory … is in use` | Another server uses the same `dir` | Stop it, or use another `dir` |
| Exit 3, `cannot load data: …` | A corrupt data file | `bin/j-redis-tools check-aof <dir>`; see the runbook in [../11 §8](../11-operations-and-security.md#8-runbook) |
| Exit 4 | Fail-stop after an internal error | systemd restarts it; the log names the command. Report it as a bug. |
| Clients get `-DENIED …protected mode` | A non-loopback client and no password | Set `requirepass` (and `bind` to the right address) |
| Clients get `-NOAUTH` | The password is required but was not sent | Set `password(...)` in the client builder, or `-a` in the CLI |
| Clients get `-OOM command not allowed…` | `maxmemory` reached | `INFO memory`, `--bigkeys`, keys without TTL |
| Clients get `-MISCONF …` | The AOF cannot be written (disk full?) | Free disk space; it recovers by itself |
| `-ERR max number of clients reached` | `maxclients` reached | Look for a client that leaks connections (`CLIENT LIST`) |

## 4.12 Replication: a primary and a replica (2.2)

A replica holds a copy of its primary's data, kept current by the stream of
changes, on another machine, to promote if the primary's machine is lost
([16](../16-replication.md)). Both run 2.2 or later.

**Configure** the replica with its primary, and the password it sends there:

```
# /etc/j-redis/j-redis.conf on the replica's machine
replicaof 10.0.0.1 6379
primaryauth <the primary's requirepass>
requirepass <this server's own>
```

Start the primary, then the replica. `ROLE` on the replica shows
`connected` once its copy is complete; `INFO replication` on the primary lists
it with its acknowledged offset and lag. The link carries all data in
plaintext, as client connections do: keep it on the private network.

**Make a write durable** beyond the primary's machine with
`WAIT 1 <timeout ms>` after it: the reply is how many replicas hold it.
`min-replicas-to-write 1` makes the primary refuse writes altogether while its
replica is away, trading availability for that guarantee.

**Promote**, when the primary's machine is lost, in this order:

1. **Fence the old primary**: make sure it is down, or cut off from every
   client (stop it, power it off, or firewall its port). Two primaries taking
   writes is the one failure the store cannot prevent.
2. On the replica: `j-redis-cli -a … REPLICAOF NO ONE`. It raises its epoch,
   records it in its data directory, and accepts writes.
3. **Edit its configuration** to remove `replicaof`: the role at start-up is the
   file's, and there is no `CONFIG REWRITE`. (If this step is missed, a restart
   makes it a replica again, of the old primary, which refuses it by epoch: its
   data stays intact, read-only, until the file is fixed.)
4. Point clients at it: clients built with both `addresses(...)` find it on
   their own ([guide 5 §5.12](05-client-guide.md#512-errors-timeouts-and-reconnection)).
5. When the old machine returns, configure it as a replica of the new primary
   (`replicaof <new> 6379`) **before** starting it. It syncs in full and takes
   the new epoch.
