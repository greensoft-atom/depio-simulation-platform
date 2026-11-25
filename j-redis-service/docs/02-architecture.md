# 02 — Architecture

This page explains how the j-redis server is built inside: its threads, the path
of one request, the command loop, background work, how commands are checked and
run, and how the server slows down a client that sends too fast. It is for
engineers who change the server or want to know why it behaves as it does. Read
the [Redis primer](00-redis-primer.md) first if Redis is new to you. Class names
are those in `j-redis-server/src/main/java/com/jredis/server/` unless the text
names another module.

## 1. Process overview

The server is one Java virtual machine (JVM) process, `j-redis-server`. Many
threads read and write the network, but **one thread, the command thread, owns
all data**.

```
                    ┌──────────────────────────── j-redis-server (JVM) ─────────────────────────────┐
                    │                                                                                │
 clients ──TCP────► │  jredis-accept (1)  ──►  jredis-io threads (2)                                 │
 (services,         │                           • read bytes                                         │
  cli, tools)       │                           • RESP decode → Events.Command{connection, argv}     │
                    │                           • per-client backpressure                            │
                    │                                │ MPSC event queue (JCTools)                    │
                    │                                ▼                                               │
                    │                    ┌──────────────────────┐                                    │
                    │                    │ jredis-cmd (1)       │  owns ALL data:                    │
                    │                    │   execute in order   │  Db (Dict of keys), clients,       │
                    │                    │   encode replies     │  pub/sub, blocked clients,         │
                    │                    │   background slices  │  watched keys, expiry buckets      │
                    │                    └──┬────────────┬──────┘                                    │
                    │      reply ByteBufs    │            │ effect bytes / base-file chunks          │
                    │   (writeAndFlush)  ◄───┘            ▼                                          │
                    │                  jredis-aof-writer (1)   jredis-snapshot-writer (1)            │
                    │                  incr.N.aof, fsync       base.N.jrdb                           │
                    └────────────────────────────────────────────────────────────────────────────────┘
```

The event queue is an MPSC queue: many producers (the I/O threads and others)
put events in, and a single consumer (the command thread) takes them out.

## 2. Threads

Every thread name below is the name the code gives the thread, so it is what a
thread dump shows. Netty adds a number to the names of its pool threads.

| Thread | Count | What it owns or does | Touches data? |
|---|---|---|---|
| `jredis-accept` | 1 | Accepts connections | No |
| `jredis-io` | 2 (directive `io-threads`, 1 to 128) | Reads sockets, decodes RESP, puts events on the queue; does the socket writes that Netty schedules | No |
| `jredis-cmd` | **1** | Runs every command; encodes replies; runs active expiry, rehashing, snapshot slices, blocked-client timeouts and the once-a-second housekeeping | **Yes — the only one** |
| `jredis-aof-writer` | 1 | Appends effect bytes to the incremental append-only file (AOF); fsyncs once per second by default (`appendfsync-interval-millis`, 1000) | No (it receives immutable byte chunks) |
| `jredis-snapshot-writer` | 1 while a rewrite runs | Writes the base-file chunks that `jredis-cmd` produces during a rewrite | No |
| `jredis-sync-writer` | 1 while a primary sends a full sync (2.2) | Sends a snapshot to the replicas that share one full sync ([16 §5](16-replication.md#5-the-full-sync)) | No |
| `jredis-repl-link` | 1 on a replica (2.2) | The replica's connection to its primary ([16](16-replication.md)) | No (it hands what it reads to `jredis-cmd`) |
| Logback async appender | 1 | Writes the log. Its queue holds 65,536 events; when the queue is full it drops events rather than block the command thread. | No |

On Linux, Netty uses its native epoll transport; elsewhere, Java NIO. Embedded
mode uses Netty's in-VM transport, with threads named `jredis-local-accept` and
`jredis-local-io`.

**The single rule of the design:** only `jredis-cmd` reads or writes anything in
the data model. The data model is the keyspace, client state, subscriptions,
blocked clients, watched keys and expiry buckets. It has no `synchronized`, no
lock and no concurrent collection. Other threads talk to `jredis-cmd` through
exactly two doors: the event queue (in) and immutable byte buffers (out).

In this page, "`cmd`" is short for the command thread, `jredis-cmd`.

## 3. Request lifecycle

One request goes through seven steps:

1. **Read.** A Netty I/O thread reads bytes into the connection's cumulation
   buffer. A small handler first counts the bytes for `INFO`.
2. **Decode.** `RequestDecoder` (in `net/`) runs `RespRequestParser` from
   `j-redis-common` ([03](03-protocol.md)). It takes out every complete request
   as `byte[][] argv`; with pipelining, one read can hold several. The decoder
   copies each argument out of the Netty `ByteBuf` here, on the I/O thread, so
   `cmd` never handles Netty buffers on the input side.
3. **Enqueue.** The I/O thread first adds the request to the connection's
   in-flight counters ([§8](#8-backpressure)). Then it wraps the request in an
   `Events.Command` (the connection and `argv`) and offers it to the event queue.
   If `cmd` is parked, the offer wakes it.
4. **Execute.** `cmd` takes a batch of events from the queue. It passes each
   command through the checks in [§6](#6-command-dispatch). The handler reads and
   writes the data through `Db` and writes its reply into the client's pending
   reply buffer.
5. **Side effects.** After each command, `cmd` serves blocked clients whose keys
   became ready ([07](07-pubsub-blocking-transactions.md)), and records the
   command's latency and slow-log entry.
6. **Flush.** At the end of the batch, `cmd` closes the batch's effects into one
   chunk and hands it to `jredis-aof-writer` (and to the replication stream, if
   there is one). Then it calls `writeAndFlush` **once for each client that has
   pending replies**.
7. **Write.** Netty writes the buffer to the socket on that connection's I/O
   thread.

### Ordering guarantee

Replies to one client arrive in exactly the order in which the client sent its
requests. Four facts make this true:

1. Each connection is bound to one Netty I/O thread, so its requests enter the
   queue in order.
2. The queue keeps the order of each producer.
3. `cmd` runs the requests in queue order.
4. `cmd` appends the replies to one buffer, in the same order.

This is what lets the client match replies to requests by position alone: RESP
has no request IDs ([10](10-client-library.md#4-correlating-replies)).

## 4. The command thread loop

The loop below is `Engine.run()` (in `core/`), shortened. In each turn, `cmd`
runs up to 1,024 events or for about 1 ms, then flushes, then does a slice of
background work, then sleeps if nothing came in.

```java
public void run() {
    while (running) {
        clock.refresh();                                  // cached "now" for this batch
        int executed = 0;
        long deadline = System.nanoTime() + BATCH_MAX_NANOS;   // 1 ms
        Object ev;
        while (executed < BATCH_MAX && (ev = queue.poll()) != null) {  // BATCH_MAX = 1024
            handle(ev);                                   // command, connect, close, protocol error, task
            executed++;
            if ((executed & 15) == 0 && System.nanoTime() - deadline > 0) break;   // time checked every 16
        }
        endOfBatch();                                     // effects to the AOF writer, one writeAndFlush per client
        if (shutdownPending) { performShutdown(); break; }
        background.run();                                 // one slice of background work (§5)
        afterCommands();                                  // serve clients that background work unblocked
        endOfBatch();
        if (executed == 0) {
            queue.park(background.parkNanos());           // until work arrives or a timer is due
        }
    }
}
```

The events are `Events.Connected`, `Events.Command`, `Events.ProtocolError`,
`Events.InputClosed` (the client closed its sending side) and `Events.Closed`.
Other threads can also queue a `Runnable` task, for example a shutdown request.

### Parking without lost wake-ups

`EventQueue` parks `cmd` when there is nothing to do. The two sides work like
this:

```java
// cmd thread                                  // I/O thread, after queue.offer(event)
sleeping = true;         // volatile           if (sleeping) LockSupport.unpark(cmdThread);
if (queue.isEmpty()) LockSupport.parkNanos(timeoutNanos);
sleeping = false;
```

`cmd` sets the flag **before** it checks the queue again. So an offer that races
with parking has two possible outcomes. Either the offering thread sees
`sleeping == true` and unparks `cmd`, or the re-check sees the new event.
`LockSupport` remembers an `unpark` that comes before the `park`, so neither
order loses the wake-up.

How long `cmd` parks depends on what is pending. It parks 10 ms when idle,
about 50 µs while a rewrite, a full sync or a rehash can progress, and never
past the next blocked-client timeout.

## 5. Background work

`cmd` does background work between batches, in **slices of at most 250 µs**
(directive `background-slice-micros`). So a command that arrives meanwhile waits
at most about one slice. `Background.run()` (in `core/`) does this work:

| Work | When | Detail |
|---|---|---|
| Blocked-client timeouts | Every slice | Cheap. A waiter whose timeout passed gets a null reply ([07](07-pubsub-blocking-transactions.md)). |
| Statistics sample | Every 100 ms | Samples for `INFO` |
| Housekeeping | Every second | Closes idle clients (directive `timeout`), applies the soft output-buffer limits, samples the command thread's CPU time |
| Active expiry | Every 10 ms | Deletes keys whose time bucket has passed ([06](06-expiry-and-memory.md)) |
| Incremental rehash | While the keyspace `Dict` is rehashing | Moves buckets until the slice ends ([04](04-data-structures.md)) |
| Snapshot progress | While a rewrite runs | Writes the next keys to the base file ([08](08-persistence.md)) |
| Full-sync progress (2.2) | While a primary sends a full sync | The same kind of step as a snapshot, for a replica ([16](16-replication.md)) |

A **duty-cycle cap** stops background work from taking more than 25 % of the
command thread in any 100 ms window (directive `background-max-duty`). Over the
cap, `cmd` skips active expiry, rehashing and snapshot progress until the next
window. Blocked-client timeouts and housekeeping always run. Snapshot and
full-sync progress have the lowest priority, but they get at least half a slice
in every 100 ms window, so a rewrite always finishes.

## 6. Command dispatch

`Engine.execute()` checks every command, in this order, before its handler runs.
The first check that fails decides the reply.

| # | Check | Reply on failure |
|---|---|---|
| 1 | The name is known (case-insensitive) and the command is not disabled in the configuration (`disable-command`) | `-ERR unknown command '<name>', with args beginning with: …` (a disabled command looks unknown) |
| 2 | The number of arguments matches the table | `-ERR wrong number of arguments for '<name>' command` |
| 3 | The client is authenticated, or the command is flagged `NOAUTH`, or no password is set | `-NOAUTH Authentication required.` |
| 4a | Not a write on a replica (2.2) | `-READONLY You can't write against a read only replica.` ([16 §6](16-replication.md#6-applying-the-stream-on-a-replica)) |
| 4b | Not a write while there are fewer good replicas than `min-replicas-to-write` (2.2) | `-NOREPLICAS Not enough good replicas to write.` ([16 §8](16-replication.md#8-acknowledgements-wait-and-refusing-writes-without-a-replica)) |
| 5 | In subscriber mode, only commands flagged `PUBSUB` | `-ERR Can't execute '<name>': only (P\|S)SUBSCRIBE / (P\|S)UNSUBSCRIBE / PING / QUIT / RESET are allowed in this context` |
| 6 | Persistence is healthy, or the command is neither a write nor `PING` | `-MISCONF Errors writing to the AOF file…` ([11](11-operations-and-security.md#8-runbook)) |
| 7 | Memory is under `maxmemory`, or the command is not flagged `DENYOOM` | `-OOM command not allowed when used memory > 'maxmemory'.` |
| 8 | **Run the handler** | |

Inside `MULTI`, a command that is not a transaction-control command (flag
`TX_CONTROL`) is queued instead of run. Before it queues the command, `cmd`
checks three things after check 5:

- a command flagged `NO_MULTI` is refused with
  `-ERR Command not allowed inside a transaction`;
- a write or `PING` is refused with `MISCONF` while persistence is failing;
- **any** command is refused with `OOM` while memory is over `maxmemory`, as in
  Redis.

Otherwise `cmd` replies `+QUEUED`. A refused command marks the transaction, so
`EXEC` later fails with `EXECABORT` ([07](07-pubsub-blocking-transactions.md)).

### The command table

Each command is a `CommandSpec` (in `command/`), registered once at start-up:

```java
public final class CommandSpec {
    public final String name;            // upper case
    public final int arity;              // N = exactly N args incl. name; -N = at least N
    public final int flags;              // bit set, below
    public final CommandHandler handler; // a method reference or lambda
    public boolean disabled;             // set by disable-command
}

public interface CommandHandler { void execute(CommandContext ctx); }

// registration
t.register("GET",    2, READONLY | FAST,          StringCommands::get);
t.register("SET",   -3, WRITE | DENYOOM,           StringCommands::set);
t.register("BLPOP", -3, WRITE | BLOCKING,          ctx -> bpop(ctx, true));
t.register("PING",  -1, FAST | PUBSUB,             ConnectionCommands::ping);
```

| Flag | Meaning |
|---|---|
| `WRITE` | May change data. Refused on a replica (`READONLY`), without enough replicas (`NOREPLICAS`), and while persistence fails (`MISCONF`). |
| `READONLY` | Never changes data. Reported by `COMMAND INFO`. |
| `DENYOOM` | May grow memory. Refused while memory is over `maxmemory`, and checked again at `EXEC`. |
| `FAST` | O(1) or O(log n). Reported by `COMMAND INFO`. |
| `BLOCKING` | May park the client ([07](07-pubsub-blocking-transactions.md)). Reported by `COMMAND INFO`. |
| `PUBSUB` | Allowed in subscriber mode |
| `NOAUTH` | Allowed before `AUTH`: `AUTH`, `HELLO`, `QUIT`, `RESET` |
| `ADMIN` | An operational command, for example `CONFIG`, `FLUSHALL`, `FLUSHDB`, `KEYS`, `SAVE`, `SHUTDOWN`, `DEBUG`, `REPLICAOF`. Reported by `COMMAND INFO`. |
| `NO_MULTI` | Refused inside `MULTI`, for example `SUBSCRIBE`, `AUTH`, `HELLO`, `SAVE`, `BGSAVE`, `BGREWRITEAOF`, `SHUTDOWN`, `REPLICAOF`, `WAIT` |
| `TX_CONTROL` | Runs inside `MULTI` instead of being queued: `MULTI`, `EXEC`, `DISCARD`, `WATCH`, `QUIT`, `RESET` (`WATCH` itself then refuses with `-ERR WATCH inside MULTI is not allowed`) |

Any command can be disabled with `disable-command`, not only `ADMIN` ones. A
name that is no command is a configuration error, so a typo cannot leave a
command enabled by mistake. The one exception is `DEBUG`, which exists only with
`enable-debug-command yes`. A disabled command can still be replayed from the
AOF, because other commands log it as their effect (for example, `FLUSHDB` logs
`FLUSHALL`).

`CommandTable` looks commands up without allocating. At start-up it builds an
open-addressing table keyed by a hash of the name with its case folded. It never
changes the table after that. A lookup hashes and compares the request's name
bytes with the case folded, so it needs no upper-case copy of the name.

## 7. The `Db` facade and mutation hooks

Commands **never** touch the keyspace `Dict` directly. Every access goes
through `Db` (in `db/`), and `Db` is where the cross-cutting rules live. This is
the most important structural rule in the server. Forgetting a hook breaks
snapshots, `WATCH` or blocking pops in ways that are hard to see.

| Method | What it does besides the lookup or the change |
|---|---|
| `lookupRead(key)` | Deletes the key first if its TTL (time to live) has passed: lazy expiry |
| `lookupWrite(key)` | Lazy expiry; then the **snapshot pre-image** if a rewrite is in progress ([08](08-persistence.md#5-the-fork-free-snapshot)); then marks the command as having changed data |
| `prepareWrite(entry)` | The same pre-image and mark, for an entry the handler already holds, before it changes the value in place |
| `add(key, type, value)` | Creates the entry; stamps it as *new since the snapshot started*; signals the change |
| `setValue(key, type, value, keepTtl)` | Pre-image of the old entry; replaces the value, whatever its type, as `SET` does; clears the TTL unless `keepTtl`; signals the change |
| `delete(key)`, `deleteEntry(entry)` | Pre-image; removes the entry; unregisters its TTL; signals the change |
| `signalModified(key)` | Invalidates `WATCH`ers; marks the key *ready* for blocked clients |

`Db` reports each change to the engine through `DbHooks.keyModified`, and each
expired key through `DbHooks.keyExpired`. `add`, `setValue`, `delete` and
`deleteEntry` signal the change themselves. A handler that changes a collection
in place calls `signalModified` itself.

The handler logs its **effect** for the AOF and the replicas with
`ctx.propagate(...)`, or `ctx.propagateAsIs()` when the command as received is
already deterministic ([08](08-persistence.md#3-logging-effects-not-commands)).

### Three rules for every handler

1. **Validate first, change second.** Parse and check every argument before the
   first write. Redis has no rollback, and neither does j-redis: a handler that
   fails half-way leaves half a change.
2. **Every write goes through `lookupWrite` (or `prepareWrite`), `add`,
   `setValue` or `delete`,** and every changed key is signalled.
3. **Every state change is propagated exactly once**, in effect form. A write
   that changed nothing propagates nothing.

The model-based test ([12](12-testing-strategy.md)) and the snapshot-consistency
test catch breaks of rules 2 and 3. An item on the code-review checklist covers
rule 1.

## 8. Backpressure

The server never drops a command. So a client that sends faster than `cmd` runs
commands must be slowed down instead. `ServerConnection` (in `net/`) does this:

- Each connection has an `AtomicInteger inFlight` (commands queued but not yet
  run) and an `AtomicLong inFlightBytes`.
- The I/O thread adds to them when it queues a command. Above the high-water
  mark (more than 10,000 commands or more than 64 MB) it calls
  `channel.config().setAutoRead(false)`. Netty then stops reading that socket,
  and TCP flow control pushes back on the sender.
- `cmd` subtracts from them after it runs the command. When both fall below the
  low-water marks (1,000 commands and 8 MB), it schedules
  `setAutoRead(true)` on the connection's event loop.

The connection's atomic fields (these counters, its pending output and its last
I/O time) and a few atomic counters for `INFO` are the only mutable state that
the I/O threads and `cmd` share.

Output is limited separately ([07 §1.5](07-pubsub-blocking-transactions.md#15-slow-subscribers)
and [11](11-operations-and-security.md)).

## 9. Client state

The state of a client is split by the thread that owns it:

| Class | Owner thread | Holds |
|---|---|---|
| `ServerConnection` (`net/`) | Netty I/O | The `Channel`, the backpressure atomics, the pending output, the last I/O time |
| `Client` (`core/`) | `cmd` | Id, name, library name and version, authenticated flag; the transaction state (`inMulti`, queued commands, watched keys); the blocking state; subscriptions; the pending reply `ByteBuf`; deferred commands; closing flags; statistics; and, on a primary, a replica's session (2.2) |

The engine sees a connection only through the `ClientOutput` interface, which
`ServerConnection` implements. The AOF loader uses another implementation that
throws replies away.

Connect and disconnect are **events on the same queue** as commands
(`Events.Connected`, `Events.Closed`). So `cmd` creates and destroys `Client`
objects itself, and it never sees a command from a client it does not know.
On `Events.Closed`, `cmd` removes the client from the pub/sub, blocking and
watch registries, and from replication.

### Deferred commands

While a client is blocked in `BLPOP`, Redis does not run the commands that the
client pipelined after it. j-redis does the same. `cmd` appends them to
`Client.deferred` and runs them, in order, as soon as the client is unblocked.

## 10. Reply encoding

- Handlers write replies through `CommandContext` (`ok()`, `integer(long)`,
  `bulk(byte[])`, `nullBulk()`, `arrayHeader(long)`, `error(message)` and
  others). It appends RESP bytes to the client's pending `ByteBuf`, which comes
  from Netty's `PooledByteBufAllocator`.
- Constant replies (`+OK`, `+QUEUED`, `+PONG`, `:0`, `:1`, `$-1`, `*-1`, `*0`)
  come from static `byte[]`s in `RespWriter` (`j-redis-common`), so they
  allocate nothing. Headers and integers up to 1,023 are cached the same way.
- `cmd` hands the buffer to `writeAndFlush` at the end of the batch, and Netty
  releases it after writing. In Netty, calling `writeAndFlush` from a thread
  outside the event loop is thread-safe: it puts a task on the channel's event
  loop.

## 11. Failure handling inside a command

| Failure | Reply | Server |
|---|---|---|
| `CommandException` (a validation error) | `-<PREFIX> message` | Continues |
| Unexpected exception **before** any change to data | `-ERR internal error while executing '<name>' (see the server log)` | Logs an error with the command name and the client id; continues |
| Unexpected exception **after** a change started | — | Logs `FATAL`; drops the failing command's effects; flushes and fsyncs the AOF of the completed commands; **exits with code 4** |

The last row is fail-stop on purpose. A change that stopped half-way may have
left a data structure (a skip list, a hash table in the middle of a rehash)
inconsistent inside. Going on would serve wrong answers from then on. The
failing command was never propagated. So after systemd restarts the process
(the unit restarts on exit code 4, but not on 1, 2 or 3) and the server replays
the AOF, the data is exactly the state before that command.

`Db` sets its "changed" flag at the first `lookupWrite`, `prepareWrite`, `add`,
`setValue` or `delete`, so the line between the two rows is precise, and it
costs one boolean.

## 12. Clock

Code reads time through a `Clock` interface (`nowMillis()`, `refresh()`), and
tests use a manual clock (`ManualClock`). TTLs use **wall-clock milliseconds**,
because they must survive a restart as absolute times. `cmd` reads the clock
once per batch, so every command in a batch sees the same time, and the expiry
checks inside one command agree with each other. A jump of the wall clock (an
NTP step) moves all expiries with it, as in Redis. Run the time service (NTP)
in slewing mode on the server.

## 13. Start-up and shutdown

**Start-up**, in order (`JRedisServer.start`):

1. Load the configuration.
2. With `appendonly yes`: lock the data directory (a second server on the same
   directory stops here, with exit code 2).
3. With `appendonly yes`: load the data from disk
   ([08 §8](08-persistence.md#8-start-up-and-recovery)), then start
   `jredis-aof-writer`.
4. With `appendonly yes`: read the replication epoch from the data directory
   (2.2, [16 §9](16-replication.md#9-promotion-and-epochs)).
5. Start `jredis-cmd`.
6. Bind the port. The port opens only after loading completes, so a client never
   sees a half-loaded data set.
7. With `replicaof` set, start following the primary (2.2).
8. Log `j-redis <version> ready (<n> keys)`.

**Shutdown** comes from `SIGTERM` (through a JVM shutdown hook) or the
`SHUTDOWN` command:

1. On `SIGTERM`, the server stops accepting connections.
2. `cmd` finishes its current batch, which holds at least everything queued
   before the shutdown request, and flushes the replies.
3. With `SHUTDOWN SAVE`, `cmd` runs a blocking rewrite, for a fast next start.
   Otherwise it aborts any rewrite in progress. The manifest is always
   consistent ([08 §7](08-persistence.md#7-crash-analysis)).
4. `cmd` writes the remaining AOF bytes and **fsyncs, whatever the fsync
   policy**. It waits up to 30 s for that final fsync.
5. The server closes the client connections (after their pending writes), stops
   the event loops, releases the data directory and exits with code 0.

The shutdown hook waits up to 60 s for these steps.

The process exit codes are:

| Code | Meaning |
|---|---|
| 0 | Clean shutdown |
| 1 | Configuration or start-up error |
| 2 | The data directory is locked by another server |
| 3 | The data could not be loaded |
| 4 | Fail-stop ([§11](#11-failure-handling-inside-a-command)) |

## 14. Server package layout

The server's code is under `j-redis-server/src/main/java/com/jredis/`. The RESP
parsers and writer, the number codec and glob matching are in `j-redis-common`.

```
com.jredis.server
├── Main, JRedisServer, Version, FatalHandlers
├── config/     ServerConfig, ConfigLoader, Directives, ConfigException
├── net/        NettyServer, ServerHandler, RequestDecoder, ServerConnection
├── core/       Engine (the command loop and dispatch), EventQueue, Events, Client, ClientOutput,
│               CommandContext, Background, Effects, Clock, ManualClock, Stats, …
├── command/    CommandTable, CommandSpec, CommandHandler, CommandStat, Commands,
│               ConnectionCommands, KeyCommands, StringCommands, HashCommands, ListCommands,
│               SetCommands, ZSetCommands, StreamCommands, StreamGroupCommands, PubSubCommands,
│               TransactionCommands, ServerCommands, ReplicationCommands, ExtensionCommands, …
├── db/         Db, DbHooks, Dict, DictEntry, KeyEntry, SipHash, ExpiryBuckets, MemoryEstimator,
│               HashValue, ListValue, SetValue, ZSetValue, SkipList, StreamValue, StreamGroup, Digest, …
├── pubsub/     PubSub
├── blocking/   BlockingRegistry, BlockingServer, BlockState
├── tx/         WatchRegistry
├── persist/    Persistence, AofPersistence, AofWriter, Manifest, Loader, Snapshot,
│               SnapshotFileWriter, BaseFormat, AofChecker, DataDirLock, …
├── repl/       Replication, PrimaryLink, ReplicaSession, Backlog, SnapshotLinkWriter, EpochFile, EndMark
└── info/       Info, SlowLog
```
