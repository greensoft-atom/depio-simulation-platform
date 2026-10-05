# 02 — Architecture

## 1. Process overview

One JVM process, `j-redis-server`.

```
                    ┌──────────────────────────── j-redis-server (JVM) ─────────────────────────────┐
                    │                                                                                │
 clients ──TCP────► │  Netty boss (1)  ──►  Netty I/O threads (2)                                    │
 (services,         │                        • read bytes                                            │
  cli, tools)       │                        • RESP decode → Command{client, argv}                   │
                    │                        • per-client backpressure                               │
                    │                                │ MPSC event queue (JCTools)                    │
                    │                                ▼                                               │
                    │                    ┌──────────────────────┐                                    │
                    │                    │   COMMAND THREAD (1) │  owns ALL data:                    │
                    │                    │   execute in order   │  Db (Dict of keys), clients,       │
                    │                    │   encode replies     │  pub/sub, blocked clients,         │
                    │                    │   background slices  │  watched keys, expiry buckets      │
                    │                    └──┬────────────┬──────┘                                    │
                    │      reply ByteBufs    │            │ effect bytes / base-file chunks          │
                    │   (writeAndFlush)  ◄───┘            ▼                                          │
                    │                         AOF writer (1)      snapshot writer (1)                │
                    │                         incr.N.aof, fsync   base.N.jrdb                        │
                    └────────────────────────────────────────────────────────────────────────────────┘
```

## 2. Threads

| Thread | Count | Owns / does | Touches data? |
|---|---|---|---|
| `netty-boss` | 1 | Accepts connections | No |
| `netty-io-N` | 2 (config `io-threads`) | Reads sockets, decodes RESP, enqueues events; performs socket writes Netty schedules | No |
| `cmd` | **1** | Executes every command; encodes replies; runs expiry, rehash, snapshot slices, blocked-client timeouts | **Yes — the only one** |
| `aof-writer` | 1 | Appends effect bytes to the incremental AOF; fsync once per second | No (receives immutable byte chunks) |
| `snapshot-writer` | 1 | Writes base-file chunks produced by `cmd` during a rewrite | No |
| Logback async appender | 1 | Log output | No |

**The single rule of the design:** everything in the data model — the keyspace,
client state, subscriptions, blocked clients, watched keys, expiry buckets —
is read and written **only by `cmd`**. There is no `synchronized`, no lock,
and no concurrent collection anywhere in the data model. Other threads
communicate with `cmd` through exactly two doors: the event queue (in) and
immutable byte buffers (out).

## 3. Request lifecycle

1. **Read.** A Netty I/O thread reads bytes into the connection's cumulation
   buffer.
2. **Decode.** `RespDecoder` ([03](03-protocol.md)) extracts every complete
   request (pipelining gives several per read) as `byte[][] argv`. Arguments are
   copied out of the `ByteBuf` here, on the I/O thread, so `cmd` never handles
   Netty buffers on the input side.
3. **Enqueue.** Each request becomes `Command{connectionId, argv, receivedNanos}`
   and is offered to the MPSC event queue. The connection's in-flight counter is
   incremented ([§8](#8-backpressure)). If `cmd` is parked, it is unparked.
4. **Execute.** `cmd` drains a batch of events and dispatches each through the
   checks in [§6](#6-command-dispatch). The handler reads and writes the `Db`
   and writes its reply into the client's pending reply buffer.
5. **Side effects.** After each command: wake blocked clients whose keys became
   ready ([07](07-pubsub-blocking-transactions.md)); record latency and slowlog.
6. **Flush.** At the end of the batch: hand accumulated AOF bytes to
   `aof-writer`, then call `writeAndFlush` **once per client that has pending
   replies**.
7. **Write.** Netty writes the buffer to the socket on that connection's I/O
   thread.

### Ordering guarantee

Replies to one client arrive in exactly the order its requests were sent.
A connection is bound to one Netty I/O thread, so its requests enter the MPSC
queue in order; the queue preserves per-producer order; `cmd` executes them in
queue order and appends replies to one buffer in that order.

This is what lets the client correlate replies by position alone — RESP has no
request IDs ([10](10-client-library.md#4-correlating-replies)).

## 4. The command thread loop

```java
public void run() {
    while (running) {
        clock.refresh();                                  // cached "now" for this batch
        int executed = 0;
        long deadline = System.nanoTime() + BATCH_MAX_NANOS;   // 1 ms
        Object ev;
        while (executed < BATCH_MAX && (ev = queue.poll()) != null) {  // BATCH_MAX = 1024
            dispatcher.dispatch(ev);                      // command, connect, or close event
            executed++;
            if (System.nanoTime() > deadline) break;
        }
        persistence.endOfBatch();                         // hand AOF bytes to aof-writer
        replies.flushTouchedClients();                    // one writeAndFlush per client
        background.runSliceIfDue();                       // ≤ 250 µs of background work
        if (executed == 0 && !background.hasUrgentWork()) {
            idle.park(background.nextDeadlineNanos());    // until work arrives or a timer is due
        }
    }
}
```

### Parking without lost wake-ups

```java
// cmd thread                                  // I/O thread, after queue.offer(cmd)
sleeping = true;         // volatile           if (sleeping) LockSupport.unpark(cmdThread);
if (queue.isEmpty()) LockSupport.parkNanos(timeoutNanos);
sleeping = false;
```

`cmd` sets the flag **before** re-checking the queue, so an offer that races
with parking either sees `sleeping == true` and unparks, or is seen by the
re-check. `unpark` before `park` is remembered by `LockSupport`, so neither
order loses the wake-up.

## 5. Background work

Four kinds of work run on `cmd` between batches, each in **slices of at most
250 µs**, so a command arriving meanwhile waits at most about that long:

| Work | Trigger | Detail |
|---|---|---|
| Blocked-client timeouts | Every slice | Cheap; expired waiters get a null reply ([07](07-pubsub-blocking-transactions.md)) |
| Active expiry | Every 10 ms | Delete keys whose time bucket has passed ([06](06-expiry-and-memory.md)) |
| Incremental rehash | While any `Dict` is rehashing | Move buckets for up to the slice budget ([04](04-data-structures.md)) |
| Snapshot progress | While a rewrite is running | Serialise the next keys to the base file ([08](08-persistence.md)) |

A **duty-cycle cap** stops background work taking more than 25 % of the
command thread over any 100 ms window (config `background-max-duty`). Snapshot
progress has the lowest priority, but gets at least one slice every 100 ms so a
rewrite always finishes.

## 6. Command dispatch

Every command passes these checks, in this order, before its handler runs:

| # | Check | Failure reply |
|---|---|---|
| 1 | Name known (case-insensitive) | `-ERR unknown command '<name>', with args beginning with: …` |
| 2 | Arity matches the table | `-ERR wrong number of arguments for '<name>' command` |
| 3 | Authenticated, or the command is flagged `NOAUTH` | `-NOAUTH Authentication required.` |
| 4 | Not disabled in config | treated as unknown (#1) |
| 4a | Not a write on a replica (2.2) | `-READONLY You can't write against a read only replica.` ([16 §6](16-replication.md#6-applying-the-stream-on-a-replica)) |
| 4b | Not a write while fewer good replicas than `min-replicas-to-write` (2.2) | `-NOREPLICAS Not enough good replicas to write.` ([16 §8](16-replication.md#8-acknowledgements-wait-and-refusing-writes-without-a-replica)) |
| 5 | In subscriber mode, only `PUBSUB`-flagged commands | `-ERR Can't execute '<name>': only (P\|S)SUBSCRIBE / (P\|S)UNSUBSCRIBE / PING / QUIT / RESET are allowed in this context` |
| 6 | Persistence healthy, or the command is not a write | `-MISCONF Errors writing to the AOF file…` ([11](11-operations-and-security.md#8-runbook)) |
| 7 | Under `maxmemory`, or the command is not `DENYOOM` | `-OOM command not allowed when used memory > 'maxmemory'.` |
| 8 | Inside `MULTI` and not a transaction-control command | queue it, reply `+QUEUED` |
| 9 | **Run the handler** | |

### The command table

```java
public final class CommandSpec {
    final String name;            // upper case
    final int arity;              // N = exactly N args incl. name; -N = at least N
    final int flags;              // bit set, below
    final CommandHandler handler; // static method reference
}

public interface CommandHandler { void execute(CommandContext ctx); }

// registration
table.register("GET",    2, READONLY | FAST,          StringCommands::get);
table.register("SET",   -3, WRITE | DENYOOM,           StringCommands::set);
table.register("BLPOP", -3, WRITE | BLOCKING,          ListCommands::blpop);
table.register("PING",  -1, FAST | PUBSUB,             ConnectionCommands::ping);
```

| Flag | Meaning |
|---|---|
| `WRITE` | May modify data; rejected under `MISCONF` |
| `READONLY` | Never modifies data |
| `DENYOOM` | May grow memory; rejected when over `maxmemory` |
| `FAST` | O(1) or O(log n); excluded from slowlog noise analysis |
| `BLOCKING` | May park the client ([07](07-pubsub-blocking-transactions.md)) |
| `PUBSUB` | Allowed in subscriber mode |
| `NOAUTH` | Allowed before `AUTH` (`AUTH`, `HELLO`, `QUIT`) |
| `ADMIN` | Operational; disable-able in config (`FLUSHALL`, `CONFIG`, `SHUTDOWN`, `DEBUG`) |
| `NO_MULTI` | Not allowed inside `MULTI` (`WATCH`, `SUBSCRIBE`…) |

Command lookup uses a `HashMap<String, CommandSpec>` built at start-up and never
modified afterwards; the name bytes are upper-cased into a reused buffer so
lookup allocates nothing in the common case.

## 7. The `Db` facade and mutation hooks

Commands **never** touch the keyspace `Dict` directly. Every access goes
through `Db`, which is where the cross-cutting rules live. This is the most
important structural rule in the server: forgetting a hook breaks snapshots,
`WATCH`, or blocking pops in ways that are hard to see.

| `Db` method | What it does besides the lookup |
|---|---|
| `lookupRead(key)` | Deletes the key first if its TTL has passed (lazy expiry) |
| `lookupWrite(key)` | Lazy expiry; then **snapshot pre-image** if a rewrite is in progress ([08](08-persistence.md#5-the-fork-free-snapshot)); marks the command as having mutated |
| `add(key, value)` | Creates the entry; marks it as *new since the snapshot started* |
| `overwrite(key, value)` | Pre-image of the old entry; replaces the value; clears TTL unless told to keep it |
| `delete(key)` | Pre-image; removes; unregisters the TTL |
| `signalModified(key)` | Invalidates `WATCH`ers; marks the key *ready* for blocked clients; bumps the dirty counter |
| `propagate(argv…)` | Appends the command's **effect** to the AOF ([08](08-persistence.md#3-logging-effects-not-commands)) |

### Three rules for every handler

1. **Validate first, mutate second.** Parse and check every argument before the
   first write. Redis has no rollback, and neither do we: a handler that fails
   half-way leaves half a change.
2. **Every write goes through `lookupWrite` / `add` / `overwrite` / `delete`**,
   and every modified key gets `signalModified`.
3. **Every state change is propagated exactly once**, in effect form. A write
   that changed nothing propagates nothing.

The model-based test ([12](12-testing-strategy.md)) and the snapshot
consistency test catch violations of 2 and 3; a code-review checklist item
covers 1.

## 8. Backpressure

Commands can never be dropped, so a client that sends faster than `cmd`
executes must be slowed, not discarded.

- Each `Connection` has an `AtomicInteger inFlight` (commands queued but not yet
  executed) and an `AtomicLong inFlightBytes`.
- The I/O thread increments them on enqueue. Above the high-water mark
  (10,000 commands or 64 MB) it calls `channel.config().setAutoRead(false)`:
  Netty stops reading that socket and TCP flow control pushes back on the
  sender.
- `cmd` decrements after executing. Crossing the low-water mark (1,000 / 8 MB)
  it schedules `setAutoRead(true)` on the connection's event loop.

These two atomics per connection are the only shared mutable state between
threads in the whole server.

Output is bounded separately ([07 §1.5](07-pubsub-blocking-transactions.md#15-slow-subscribers)
and [11](11-operations-and-security.md)).

## 9. Client state

State is split by owner:

| Class | Owner thread | Holds |
|---|---|---|
| `Connection` | Netty I/O | `Channel`, `connectionId`, the two backpressure atomics |
| `Client` | `cmd` | id, name, authenticated flag, flags (`MULTI`, `BLOCKED`, `PUBSUB`, `CLOSE_AFTER_REPLY`), queued transaction, blocking state, subscriptions, watched keys, pending reply `ByteBuf`, deferred commands, stats |

Connect and disconnect are **events on the same queue** as commands
(`ClientConnected`, `ClientClosed`), so `cmd` creates and destroys `Client`
objects itself and never sees a command from a client it does not know about.
On `ClientClosed`, `cmd` removes the client from pub/sub, blocking, and watch
registries.

### Deferred commands

While a client is blocked in `BLPOP`, Redis does not execute its later
pipelined commands. `cmd` appends them to `Client.deferred` and runs them, in
order, as soon as the client is unblocked.

## 10. Reply encoding

- Handlers write replies through `ReplyWriter` (`ok()`, `integer(long)`,
  `bulk(byte[])`, `nullBulk()`, `arrayHeader(int)`, `error(prefix, msg)`), which
  appends RESP bytes to the client's pending `ByteBuf` from
  `PooledByteBufAllocator`.
- Constant replies (`+OK`, `:0`, `:1`, `$-1`, `*0`) are written from static
  `byte[]`s — no allocation.
- The buffer is handed to `writeAndFlush` at the end of the batch; Netty
  releases it after writing. `writeAndFlush` from a non-event-loop thread is
  thread-safe in Netty: it enqueues a task on the channel's event loop.

## 11. Failure handling inside a command

| Failure | Reply | Server |
|---|---|---|
| `CommandException` (validation) | `-<PREFIX> message` | continues |
| Unexpected exception **before** any mutation (`ctx.mutated == false`) | `-ERR internal error` | logs ERROR with command name and client id; continues |
| Unexpected exception **after** a mutation started | — | logs ERROR, flushes and fsyncs the AOF of completed commands, **exits non-zero** |

The last row is fail-stop on purpose. A half-applied change may have left a
data structure (a skip list, a hash table mid-rehash) internally inconsistent,
and continuing would serve wrong answers from then on. The failing command was
never propagated, so after systemd restarts the process and replays the AOF,
the dataset is exactly the state before that command. `ctx.mutated` is set by
the first `lookupWrite` / `add` / `overwrite` / `delete`, so the distinction is
precise and costs one boolean.

## 12. Clock

A `Clock` interface (`nowMillis()`, `nanoTime()`, `refresh()`) is injected
everywhere; tests use a manual clock. TTLs use **wall-clock milliseconds**
because they must survive restarts as absolute times. `now` is read once per
batch, so every command in a batch sees the same time, and expiry checks inside
one command are consistent. A wall-clock jump (NTP step) moves all expiries
with it, as in Redis; run NTP in slewing mode on the server.

## 13. Start-up and shutdown

**Start-up:** load config → load data from disk ([08 §8](08-persistence.md#8-start-up-and-recovery))
→ start `aof-writer` → start `cmd` → bind the port. The port opens only after
loading completes, so a client never sees a half-loaded dataset.

**Shutdown** (`SIGTERM`, or the `SHUTDOWN` command):

1. Close the listening socket.
2. `cmd` executes everything already queued and flushes replies.
3. Abort any rewrite in progress — the manifest is always consistent
   ([08 §7](08-persistence.md#7-crash-analysis)).
4. Write the remaining AOF bytes and **fsync, whatever the fsync policy**.
5. `SHUTDOWN SAVE` additionally runs a synchronous rewrite for a fast next start.
6. Close client connections, stop event loops, exit 0.

A JVM shutdown hook triggers the same sequence for `SIGTERM` and waits up to
30 s.

## 14. Server package layout

```
jredis.server
├── Main, ServerConfig, ConfigLoader
├── net/        NettyServer, ConnectionHandler, Connection
├── core/       CommandThread, EventQueue, Client, CommandContext, ReplyWriter,
│               BackgroundScheduler, Clock
├── command/    CommandTable, CommandSpec, Flags,
│               ConnectionCommands, KeyCommands, StringCommands, HashCommands,
│               ListCommands, SetCommands, ZSetCommands, PubSubCommands,
│               TxCommands, ServerCommands, ExtensionCommands
├── db/         Db, Dict, Entry, SipHash, ExpiryBuckets, MemoryEstimator
│   └── types/  HashValue, ListValue (ByteArrayRing), SetValue, ZSetValue, SkipList
├── pubsub/     PubSub, GlobMatcher
├── blocking/   BlockingRegistry
├── tx/         MultiState, WatchRegistry
├── persist/    Persistence, AofWriter, Manifest, Effects, SnapshotController,
│               BaseFileWriter, BaseFileReader, Loader
└── info/       InfoBuilder, SlowLog, CommandStats
```

`jredis` is a placeholder root package; rename it to your organisation's
package in milestone M0.
