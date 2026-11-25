# 10 — Client library: `j-redis-client`

This page explains how the Java client library, `j-redis-client`, works inside.
It is for engineers who change the client, and for application developers who
need to know exactly how it behaves under timeouts, failures and failover. It
covers the design principles, the classes, the API, how replies are matched to
requests, timeouts and reconnects, following a new primary, pub/sub,
transactions, blocking commands, the blocking facade, embedded mode, the
threading rules, configuration and metrics. For a hands-on guide with runnable
examples, see [guide/05-client-guide.md](guide/05-client-guide.md).

## 1. Principles

- **The only client.** No third-party Redis client library is used anywhere:
  not in applications, not in tools, not in tests (D-14). `j-redis-cli`, the
  benchmark and every test use this library.
- **Non-blocking first.** Latency-critical threads (event loops, worker threads
  that serve one session each) must never block. So the core API returns
  `CompletableFuture`s. A blocking ("sync") facade on top of it serves ordinary
  worker threads.
- **No extra dependencies.** The client needs only Netty (4.1.122 in j-redis's
  own build) and the shared RESP (REdis Serialization Protocol) codec from
  `j-redis-common`. It can share the application's existing Netty event loops.
  Inside the backend, the backend's build puts it on Netty 4.2 with the rest of
  the backend (`backend/pom.xml`).
- **The same API, embedded or remote.** Tests run the real engine in the same
  process, with no socket and no code changes ([§10](#10-embedded-mode)).

## 2. Structure

```
com.jredis.client
├── JRedisClient          entry point; command connection(s); typed + generic commands (AsyncCommands)
├── JRedisSync            blocking facade (client.sync())
├── JRedisPubSub          dedicated subscriber connection (client.pubSub())
├── JRedisBlocking        dedicated connection for BLPOP / BLMOVE / BZPOPMIN / XREAD(GROUP) BLOCK (client.blocking())
├── Transaction           MULTI … EXEC builder (client.multi())
├── LeasedConnection      exclusive connection for WATCH (client.withLeasedConnection(...))
├── SetArgs, XAddArgs, ScanResult, ScoredMember, ZAround, Streams   argument and result types
├── ClientMetrics         counters and per-command latency histograms
├── ThreadGuard           marks threads on which the sync facade must refuse to run
├── JRedisException       ├─ JRedisServerException    (-ERR …, prefix())
│                         ├─ JRedisTimeoutException
│                         ├─ JRedisConnectionException (outcome unknown)
│                         └─ JRedisClosedException
└── Connection, LeasePool, PrimaryFinder, ReplyDecoder, …  (package-private internals)

com.jredis.common.Reply   SIMPLE, ERROR, INTEGER, BULK, ARRAY, NULL

com.jredis.embedded       module j-redis-embedded, so the client jar never depends on the server
└── JRedisEmbedded, EmbeddedConfig
```

All exceptions are unchecked. `XAddArgs` and `Streams` arrived with streams
(2.1); `PrimaryFinder`, which finds the primary among several addresses,
arrived with replication (2.2).

## 3. API

```java
JRedisClient client = JRedisClient.builder()
        .address("127.0.0.1", 6379)
        .password(secret)
        .clientName("orders-service")
        .eventLoopGroup(sharedEventLoopGroup)   // optional: reuse the application's Netty threads
        .commandTimeoutMillis(2000)
        .build()
        .start();                               // connect now (waits up to the connect timeout)

// typed methods; String arguments are UTF-8, values may be String, byte[] or numbers
CompletableFuture<String>  v   = client.get("user:42:name");
CompletableFuture<byte[]>  raw = client.getBytes("blob:7");
CompletableFuture<Boolean> ok  = client.set("sess:" + token, userId, SetArgs.nx().andEx(86400));
CompletableFuture<Long>    n   = client.incr("counter:visits");

// generic: any command, including the J.* extensions
CompletableFuture<Reply>   r   = client.send("J.ZAROUND", "lb:weekly:points", "user:42", 5, "REV", "WITHSCORES");
```

- `start()` connects; nothing connects before it. A request sent to a client
  that was never started fails with "call start() first". Calling `start()`
  again does no harm.
- Typed methods cover the common commands; `send` covers every other one.
- When the server replies with an error, the future completes exceptionally
  with `JRedisServerException`. Its `prefix()` returns the error's first word:
  `WRONGTYPE`, `OOM`, `MISCONF`…
- Inside an `EXEC` result, a failed command is a `Reply` of type `ERROR`,
  because the other commands still ran
  ([07 §3.2](07-pubsub-blocking-transactions.md#32-exec)).

**Commands a command connection refuses.** A command connection is shared by
many threads, so `send` (and every typed method) refuses the commands that
would stall it or change its state. Each throws `IllegalArgumentException`
before anything is sent:

| Commands | Use instead |
|---|---|
| `BLPOP`, `BRPOP`, `BLMOVE`, `BRPOPLPUSH`, `BZPOPMIN`, `BZPOPMAX`; `XREAD` and `XREADGROUP` with `BLOCK` | `client.blocking()` (§8) |
| `SUBSCRIBE`, `PSUBSCRIBE`, `UNSUBSCRIBE`, `PUNSUBSCRIBE` | `client.pubSub()` (§6) |
| `MULTI`, `EXEC`, `DISCARD` | `client.multi()`, so that the transaction is written atomically (§7) |
| `WATCH`, `UNWATCH` | `client.withLeasedConnection(...)` (§7) |
| `QUIT`, `RESET`, `SELECT`, `AUTH`, `HELLO` | Nothing: they would change the shared connection's state |

**Streams (2.1)** ([15](15-streams.md)) have typed methods too. Their results
are the records in `Streams`:

```java
String id = client.sync().xadd("s:match-result", XAddArgs.minId(oldestId).approximately(), "*", "e", json);
client.sync().xgroupCreate("s:match-result", "rewards", "0", true);          // MKSTREAM
Map<String, List<Streams.Entry>> got =
        client.sync().xreadgroup("rewards", "worker-1", 64, Map.of("s:match-result", ">"));
client.sync().xack("s:match-result", "rewards", id);
Streams.AutoClaim stale = client.sync().xautoclaim("s:match-result", "rewards", "worker-1", 60_000, "0", 64);
List<Streams.Group> groups = client.sync().xinfoGroups("s:match-result");     // lag per group
```

- An entry's fields keep their order.
- A pending entry whose stream entry is gone comes back with null fields.
- A read that finds nothing returns an empty map.
- To wait for entries, use `client.blocking()` (§8). On the shared connection,
  `XREAD` and `XREADGROUP` with `BLOCK` are refused, as the blocking list pops
  are.
- Everything else (`XINFO STREAM`, `XGROUP SETID`, …) goes through `send`.

## 4. Correlating replies

RESP has no request ids. The server answers each connection **in request
order** ([02 §3](02-architecture.md#ordering-guarantee)). So the client keeps a
first-in, first-out (FIFO) queue of pending requests for each connection, and
matches each reply to the request at the head of the queue.

```
send (any thread)                                  receive (event loop)
─────────────────                                  ────────────────────
encode argv → ByteBuf  (caller thread, pooled)     decoder emits a Reply
eventLoop.execute(() -> {                          p = pending.poll()
    pending.add(p);                                if (p == null) → desync: close connection
    channel.write(buf);                            else if (p.timedOut) → drop the reply
    scheduleFlush();       // one flush per        else → complete p.future
})                         // loop iteration
```

- Only the connection's event loop touches the pending queue, so the queue
  needs no lock.
- Commands sent from many threads within one event-loop iteration are written
  together and flushed once. This is **automatic pipelining**, with no API for
  it.
- A reply with nothing pending means the stream of replies is out of step. The
  client then closes the connection, rather than risk completing the wrong
  future.

### What follows from ordering

| Consequence | Handling |
|---|---|
| A timed-out request cannot be removed from the middle of the FIFO | It stays, marked as timed out; its reply is dropped when it arrives |
| One slow reply delays every reply behind it (head-of-line blocking) | Busy services use two command connections ([09 §5](09-integration-patterns.md#5-connections-per-process)) |
| A blocking command would stall the connection indefinitely | Refused on command connections (§3); use `JRedisBlocking` |
| `SUBSCRIBE` changes the connection's protocol mode | Refused on command connections (§3); use `JRedisPubSub` |
| Another thread's command could land between `MULTI` and `EXEC` | A transaction is written as a single event-loop task ([§7](#7-transactions)) |
| With several command connections, one thread's commands could run out of order, for example `SET` then `EXPIRE` | Each thread is **pinned to one command connection** (its thread id modulo the number of connections). It moves to another connection only while its own is down, and the requests already sent on its own have then failed. Threads spread across the connections (D-19). |

## 5. Timeouts, failures, reconnects

### Timeouts

Each request carries a deadline. Every 10 ms, a task on the connection's own
event loop checks the deadlines, so no extra timer thread and no locking are
needed. When a deadline passes, the future fails with `JRedisTimeoutException`,
and the request stays in the FIFO, as described above. When its reply arrives
later, the client drops it; it never hands it to the next request.

The client treats the connection as **stuck**, and closes it, in two cases:

- 5 requests in a row have timed out; or
- the oldest pending request has waited more than 3 times the command timeout.

A transaction counts as one request here.

The client fails the futures only after it has scanned the pending queue. So a
callback that sends a new request (a retry, for example) cannot disturb the
scan, and no exception can ever stop the periodic check.

### Connection loss

Every pending request fails with `JRedisConnectionException`. For a write, the
**outcome is unknown**: the server may or may not have run it. **The library
never retries a request by itself**, because retrying `INCR` or `LPUSH` could
apply it twice. Callers retry only idempotent operations (operations that give
the same result when done twice), and should make writes idempotent where it
matters (the work-queue pattern in
[09 §3](09-integration-patterns.md#3-recipes)).

While the client is disconnected, new requests **fail at once**. A caller that
must not lose data can then react immediately, for example by spooling the
data to local disk, instead of waiting for a timeout.

### Reconnect

1. The client waits before each new attempt. The wait grows exponentially from
   100 ms to 5 s, with ±20 % random jitter.
2. After it connects, it pipelines a handshake before any user command:
   - `AUTH default <password>`, if a password is configured (a server without
     a password accepts it too);
   - `CLIENT SETNAME <name>`, if a client name is configured. A pub/sub
     connection adds `-pubsub` to the name and a blocking one `-blocking`, so
     `CLIENT LIST` shows what each connection is for;
   - `CLIENT SETINFO LIB-NAME j-redis-client` and
     `CLIENT SETINFO LIB-VER <version>`.
3. If `AUTH` fails, the client logs an ERROR and retries at the maximum
   backoff. A misconfiguration should be loud, not a tight retry loop.

Host names are resolved on a separate thread, never on an event loop. A closed
connection stays closed, even if a connect attempt completes afterwards.

### Several addresses: following the primary (2.2)

`addresses(...)` can name more than one server: a primary and its replicas.
Then each connection finds the primary before it connects
([16 §10](16-replication.md#10-clients-following-the-primary)):

1. On the resolver thread, with plain sockets, it sends each server `AUTH` (if
   a password is configured) and `ROLE`.
2. It connects to the primary with the highest epoch.

The client keeps, for its whole life, the highest epoch that any server has
shown, replicas included. No connection uses a primary below that epoch. If no
server qualifies, the connection retries with the usual backoff.

A `-READONLY` reply means that the primary was demoted. The command fails with
that error, and the connection closes, so the next attempt looks for the
primary again. Blocking and pub/sub connections do the same, and the client
subscribes again, as after any reconnect. Since 2.2.1, a subscriber's
connection is also pinged every `pubsubPingMillis`; a ping left unanswered
for three command timeouts closes the connection as a stuck one, and once one
connection of the client has found a new primary, its other connections follow.

## 6. Pub/Sub

Pub/sub uses a dedicated connection, because a RESP2 connection in subscriber
mode cannot run other commands ([03 §6](03-protocol.md#6-pubsub-frames)).

```java
JRedisPubSub ps = client.pubSub();
ps.subscribe("instance:web-1:cmd", (channel, message) -> worker.post(Command.decode(message)))
  .join();                                                   // completes when the server confirms
ps.psubscribe("group:*", (pattern, channel, message) -> fanOut(channel, message));
ps.onReconnect(() -> worker.post(Command.resync()));        // messages may have been missed
```

- `subscribe` returns a future that completes when the server confirms the
  subscription.
- Listeners run on the event loop. They must hand work off to another thread,
  and never block.
- The client remembers the subscriptions and **makes them again after a
  reconnect**, then calls the `onReconnect` hook. Messages published while the
  client was disconnected are gone, because pub/sub delivers at most once
  ([07 §1.4](07-pubsub-blocking-transactions.md#14-delivery-guarantees)).
  Anything that must not be lost goes through a list.

## 7. Transactions

```java
CompletableFuture<List<Reply>> result = client.multi()
        .send("HSET", "token:" + id, "userId", "42", "target", target, "payload", json)
        .send("EXPIRE", "token:" + id, 60)
        .exec();
```

The client encodes `MULTI`, the queued commands and `EXEC` together, and writes
them in **one event-loop task**. So no other request on the connection can come
in between; such a request would otherwise silently become part of the
transaction. The client consumes the `+OK` and `+QUEUED` replies itself, and
the future completes with the `EXEC` array. If the server refused a command
while queueing it, the future fails with `EXECABORT`.

### `WATCH` needs a leased connection

`WATCH` state belongs to a connection, and between `WATCH` and `EXEC` the
caller reads data and decides. Nobody else may use the connection meanwhile.
So the client leases it exclusively from a small pool: up to 4 connections,
created on demand. This is a blocking pattern, for ordinary worker threads:

```java
boolean added = client.withLeasedConnection(conn -> {
    for (int attempt = 0; attempt < 5; attempt++) {
        conn.sync().watch("group:" + id);
        String current = conn.sync().hget("group:" + id, "members");
        long members = current == null ? 0 : Long.parseLong(current);
        if (members >= maxMembers) { conn.sync().unwatch(); return false; }
        List<Reply> r = conn.sync().exec(conn.multi().send("HINCRBY", "group:" + id, "members", 1));
        if (r != null) return true;          // null: the key changed under us, so retry
    }
    throw new IllegalStateException("group " + id + ": too much contention");
});
```

- Before the lease goes back to the pool, the client resets it with `UNWATCH`,
  even if the callback throws.
- If the connection was re-established between `WATCH` and `EXEC`, the server
  no longer watches anything. `EXEC` then fails with
  `JRedisConnectionException`, instead of committing without the check. Read
  again and retry.
- `withLeasedConnection` refuses to run on one of the client's event-loop
  threads, or on a thread marked non-blocking.

## 8. Blocking commands

```java
JRedisBlocking b = client.blocking();       // its own connection
b.blmove("q:jobs", "q:jobs:processing", false, true, 5.0)   // RIGHT → LEFT, wait up to 5 s
 .thenAccept(item -> { if (item != null) jobWorker.submit(item); });
```

Stream reads wait the same way, for `BLOCK` milliseconds:
`b.xreadgroup(5_000, "rewards", "worker-1", 64, Map.of("s:match-result", ">"))`
completes with the entries for each stream, or with an empty map when the time
runs out.

- Only one blocking command is outstanding on a `JRedisBlocking` at a time.
  Later calls queue behind it.
- Each call is sent from a fresh task. So when the connection is lost, a long
  queue drains cleanly, with every call failing.
- The client-side timeout is the server timeout plus the normal command
  timeout. A server timeout of `0` (wait forever) turns the client-side
  timeout off.
- `isConnected()` shows the connection's state.

## 9. Sync facade

```java
JRedisSync sync = client.sync();
String userId = sync.get("sess:" + token);   // blocks up to the command timeout
```

Every method is `future.get(timeout)`, with the exception unwrapped. Two guards
make misuse fail loudly instead of hanging:

| Guard | Why |
|---|---|
| Throws if called on one of the client's own event-loop threads | That thread is the one that must read the reply, so the call would certainly deadlock |
| Throws if the thread is marked non-blocking (`ThreadGuard.markNonBlocking()`) | Latency-critical threads mark themselves at start-up. An accidental blocking call then fails at once with an exception |

## 10. Embedded mode

```java
try (JRedisEmbedded server = JRedisEmbedded.start(EmbeddedConfig.inMemory())) {  // or .persistentAt(dir)
    JRedisClient client = server.newClient();
    // … identical API …
}
```

- `JRedisEmbedded` starts the real server (the command thread, the `Db` and
  optionally persistence). The server listens on **Netty's in-VM transport**
  (`LocalServerChannel`) instead of TCP, and the embedded client connects with
  `LocalChannel`. Both sides run the same pipelines as over the network: RESP
  encoding, the request decoder, backpressure and reply decoding. So `Reply`
  objects, ordering and timeouts are identical, and code that works embedded
  works over TCP. No socket is opened.
- `EmbeddedConfig.inMemory()` runs without persistence and is the fastest.
  `.persistentAt(dir)` runs with the full AOF (append-only file).
  `.clock(new ManualClock(t))` makes TTL tests deterministic, and
  `.configure(c -> …)` sets any server directive.
- In embedded mode, a fail-stop does not halt the JVM. The engine stops, and
  `fatalError()` returns the reason. Tests assert that it is null.

This needed no new seam in the server: its `NettyServer` can already listen
either on TCP or on a local address.

## 11. The threading contract, by example

A Netty-based service redeeming a one-time token:

```java
// on the service's own Netty I/O thread, handling Redeem{tokenId}
jredis.multi()
      .send("HGETALL", "token:" + tokenId)
      .send("DEL", "token:" + tokenId)
      .exec()
      .whenComplete((replies, err) ->                          // runs on a j-redis event loop
          session.executor().execute(() -> onRedeemed(replies, err)));   // hop back to the owner thread
```

Three rules for every callback:

1. Never block: no `join()`, no `sync()`, no I/O.
2. Never touch state that another thread owns (session state, caches).
3. Hand the result to its owner thread, through that thread's queue.

## 12. Configuration

These are the builder's settings (`JRedisClient.builder()`):

| Setting | Default | Notes |
|---|---|---|
| `address` | `127.0.0.1:6379` | `address(host, port)`. `localAddress(name)` is for embedded mode |
| `addresses` | none | `addresses("host:port", …)`: several servers, to follow the primary (§5, 2.2) |
| `password` | none | Sent with `AUTH` on every connect and reconnect |
| `clientName` | none | Shown in `CLIENT LIST`. Set it: it makes incidents readable. |
| `eventLoopGroup` | the client's own group | Share the application's group to save threads |
| `ioThreads` | 1 | The threads of the client's own group |
| `connectTimeoutMillis` | 2000 | |
| `commandTimeoutMillis` | 2000 | |
| `commandConnections` | 1 | 2 for busy services |
| `reconnectBackoffMillis` | 100 → 5000 | `reconnectBackoffMillis(min, max)`. Exponential, with ±20 % jitter |
| `pubsubPingMillis` | 5000 | How often a subscriber's connection is pinged, so that a server that answers nothing is noticed ([16 §10](16-replication.md#10-clients-following-the-primary)); 0 means never |
| `leasePoolMax` | 4 | The most `WATCH` connections |
| `callbackExecutor` | the event loop | Set an executor if callbacks cannot be kept short |

## 13. Metrics

`client.metrics()` returns counters and latency histograms:

| Metric | What it counts |
|---|---|
| `sent` | Requests sent |
| `received` | Replies received |
| `serverErrors` | Error replies from the server |
| `timeouts` | Requests that timed out |
| `connectionLosses` | Connections lost |
| `reconnects` | Reconnections |
| `failedFast` | Requests failed at once because their connection was not connected |
| `latency()` | One latency histogram (HdrHistogram, in microseconds) for each command name |

Services export these alongside their own metrics.
