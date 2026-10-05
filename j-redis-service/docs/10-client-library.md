# 10 — Client library: `j-redis-client`

This document explains how the client works inside. For a hands-on guide with
runnable examples, see [guide/05-client-guide.md](guide/05-client-guide.md).

## 1. Principles

- **The only client.** No third-party Redis client library is used anywhere:
  not in applications, not in tools, not in tests (D-14). `j-redis-cli`, the
  benchmark and every test use this library.
- **Non-blocking first.** Latency-critical threads (event loops, per-session
  worker threads) must never block, so the core API returns `CompletableFuture`s.
  A sync facade on top serves ordinary worker threads.
- **No extra dependencies.** Netty 4.1.122 and the shared RESP codec from
  `j-redis-common`. It can share the application's existing Netty event loops.
- **Same API, embedded or remote.** Tests run the real engine in-process with no
  socket and no code changes ([§10](#10-embedded-mode)).

## 2. Structure

```
com.jredis.client
├── JRedisClient          entry point; command connection(s); typed + generic commands (AsyncCommands)
├── JRedisSync            blocking facade (client.sync())
├── JRedisPubSub          dedicated subscriber connection (client.pubSub())
├── JRedisBlocking        dedicated connection for BLPOP / BLMOVE / BZPOPMIN (client.blocking())
├── Transaction           MULTI … EXEC builder (client.multi())
├── LeasedConnection      exclusive connection for WATCH (client.withLeasedConnection(...))
├── SetArgs, ScanResult, ScoredMember, ZAround      argument and result types
├── ClientMetrics         counters and per-command latency histograms
├── ThreadGuard           marks threads on which the sync facade must refuse to run
├── JRedisException       ├─ JRedisServerException    (-ERR …, prefix())
│                         ├─ JRedisTimeoutException
│                         ├─ JRedisConnectionException (outcome unknown)
│                         └─ JRedisClosedException
└── Connection, LeasePool, ReplyDecoder, …  (package-private internals)

com.jredis.common.Reply   SIMPLE, ERROR, INTEGER, BULK, ARRAY, NULL

com.jredis.embedded       module j-redis-embedded, so the client jar never depends on the server
└── JRedisEmbedded, EmbeddedConfig
```

All exceptions are unchecked.

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
  again is harmless.
- Typed methods cover the common commands; `send` covers every other one.
- A server error reply completes the future exceptionally with
  `JRedisServerException`; `prefix()` returns `WRONGTYPE`, `OOM`, `MISCONF`…
- Inside an `EXEC` result, a failed command is a `Reply` of type `ERROR`, because
  the other commands still ran ([07 §3.2](07-pubsub-blocking-transactions.md#32-exec)).

**Streams (2.1)** ([15](15-streams.md)) have typed methods too, their answers as
the records of `Streams`:

```java
String id = client.sync().xadd("s:match-result", XAddArgs.minId(oldestId).approximately(), "*", "e", json);
client.sync().xgroupCreate("s:match-result", "rewards", "0", true);          // MKSTREAM
Map<String, List<Streams.Entry>> got =
        client.sync().xreadgroup("rewards", "worker-1", 64, Map.of("s:match-result", ">"));
client.sync().xack("s:match-result", "rewards", id);
Streams.AutoClaim stale = client.sync().xautoclaim("s:match-result", "rewards", "worker-1", 60_000, "0", 64);
List<Streams.Group> groups = client.sync().xinfoGroups("s:match-result");     // lag per group
```

An entry's fields keep their order; a pending entry whose stream entry is gone
comes back with null fields. A read that finds nothing answers an empty map.
Waiting for entries is `client.blocking()`'s (§8): `XREAD` and `XREADGROUP`
with `BLOCK` on the shared connection are refused, as the list pops are. The
rest (`XINFO STREAM`, `XGROUP SETID`, …) is `send`.

## 4. Correlating replies

RESP has no request ids; the server answers each connection **in request
order** ([02 §3](02-architecture.md#ordering-guarantee)). The client therefore
keeps a FIFO of pending requests per connection and matches each reply to the
head of the queue.

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

- The pending queue is touched **only on the connection's event loop**, so it
  needs no lock.
- Commands sent from many threads within one event-loop iteration are written
  together and flushed once: **automatic pipelining** with no API for it.
- A reply with nothing pending means the stream is out of sync; the connection
  is closed rather than risk completing the wrong future.

### What follows from ordering

| Consequence | Handling |
|---|---|
| A timed-out request cannot be removed from the middle of the FIFO | It stays, marked; its reply is dropped when it arrives |
| One slow reply delays every reply behind it (head-of-line) | Busy services use two command connections ([09 §5](09-integration-patterns.md#5-connections-per-process)) |
| A blocking command would stall the connection indefinitely | Rejected on command connections; use `JRedisBlocking` |
| `SUBSCRIBE` changes the connection's protocol mode | Rejected on command connections; use `JRedisPubSub` |
| Another thread's command could land between `MULTI` and `EXEC` | A transaction is written as a single event-loop task ([§7](#7-transactions)) |
| With several command connections, one thread's commands could run out of order, for example `SET` then `EXPIRE` | Each thread is **pinned to one command connection** (thread id modulo the count). It moves to another connection only while its own is down, and requests already sent on it have then failed. Threads spread across the connections (D-19). |

## 5. Timeouts, failures, reconnects

### Timeouts

Each request carries a deadline. A task on the connection's own event loop
checks deadlines every 10 ms, so no extra timer thread and no locking are
needed. On expiry the future fails with `JRedisTimeoutException`, and the
request stays in the FIFO as described above. When its reply arrives later it
is dropped, never handed to the next request.

The connection is treated as **stuck** and closed if 5 requests in a row time
out, or the oldest pending request has waited more than 3× the timeout. A
transaction counts as one request here.

Futures are failed only after the scan of the pending queue. A callback that
sends a new request (a retry, say) then cannot disturb the scan, and no
exception can ever stop the periodic check.

### Connection loss

Every pending request fails with `JRedisConnectionException`. For a write, the
**outcome is unknown**: the server may or may not have executed it. **The
library never retries a request by itself**, because retrying `INCR` or `LPUSH`
could apply it twice. Callers retry only idempotent operations, and should make
writes idempotent where it matters (the work-queue pattern in
[09 §3](09-integration-patterns.md#3-recipes)).

While disconnected, new requests **fail immediately**. A caller that must not
lose data can react at once, for example by spooling it to local disk, instead
of waiting for a timeout.

### Reconnect

Exponential backoff from 100 ms to 5 s with ±20 % jitter. After connecting,
the handshake is pipelined before any user command: `AUTH default <password>`
(if configured; a server without a password accepts it too), `CLIENT SETNAME`,
and `CLIENT SETINFO lib-name j-redis-client lib-ver <v>`. A failed `AUTH` is
logged as ERROR and retried at the maximum backoff: a misconfiguration should be
loud, not a tight retry loop. Host names are resolved on a separate thread,
never on an event loop. A closed connection stays closed, even if a connect
attempt completes afterwards.

### Several addresses: following the primary (2.2)

With `addresses(...)` naming more than one server, a connection first asks each
of them, on the resolver thread with plain sockets, `AUTH` (if configured) and
`ROLE`, then connects to the primary with the highest epoch
([16 §10](16-replication.md#10-clients-following-the-primary)). The highest
epoch any server has shown, replicas included, is kept for the whole client:
no connection uses a primary below it, and if none qualifies it retries with
the usual backoff. A `-READONLY` reply means the primary was demoted: the
command fails with it, and the connection closes, so the next attempt looks
again. Blocking and pub/sub connections do the same, and subscriptions are made
again as after any reconnect.

## 6. Pub/Sub

A dedicated connection, because a RESP2 connection in subscriber mode cannot
run other commands ([03 §6](03-protocol.md#6-pubsub-frames)).

```java
JRedisPubSub ps = client.pubSub();
ps.subscribe("instance:web-1:cmd", (channel, message) -> worker.post(Command.decode(message)))
  .join();                                                   // completes when the server confirms
ps.psubscribe("group:*", (pattern, channel, message) -> fanOut(channel, message));
ps.onReconnect(() -> worker.post(Command.resync()));        // messages may have been missed
```

- `subscribe` returns a future that completes on the server's confirmation.
- Listeners run on the event loop; they must hand work off, never block.
- Subscriptions are remembered and **re-issued after a reconnect**, then
  `onReconnect` fires. Messages published while disconnected are gone, because
  Pub/Sub is at-most-once ([07 §1.4](07-pubsub-blocking-transactions.md#14-delivery-guarantees)).
  Anything that must not be lost goes through a list.

## 7. Transactions

```java
CompletableFuture<List<Reply>> result = client.multi()
        .send("HSET", "token:" + id, "userId", "42", "target", target, "payload", json)
        .send("EXPIRE", "token:" + id, 60)
        .exec();
```

`MULTI`, the queued commands and `EXEC` are encoded together and written in
**one event-loop task**, so no other request on the connection can interleave;
otherwise it would silently become part of the transaction. The `+OK` and
`+QUEUED` replies are consumed internally, and the future completes with the
`EXEC` array. If a command was rejected while queueing, the future fails with
`EXECABORT`.

### `WATCH` needs a leased connection

`WATCH` state belongs to a connection, and between `WATCH` and `EXEC` the
caller reads and decides. Nobody else may use the connection meanwhile, so it
is leased exclusively from a small pool (up to 4, created on demand). This is a
blocking pattern for ordinary worker threads:

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

The lease is reset with `UNWATCH` before it goes back to the pool, even if the
callback throws. If the connection was re-established between `WATCH` and
`EXEC`, the server no longer watches anything. `EXEC` then fails with
`JRedisConnectionException` instead of committing unchecked; read again and
retry. `withLeasedConnection` refuses to run on a client event-loop thread or a
thread marked non-blocking.

## 8. Blocking commands

```java
JRedisBlocking b = client.blocking();       // its own connection
b.blmove("q:jobs", "q:jobs:processing", false, true, 5.0)   // RIGHT → LEFT, wait up to 5 s
 .thenAccept(item -> { if (item != null) jobWorker.submit(item); });
```

Stream reads wait the same way, for `BLOCK` milliseconds: `b.xreadgroup(5_000,
"rewards", "worker-1", 64, Map.of("s:match-result", ">"))` completes with the
entries per stream, or an empty map when the time runs out.

One blocking command is outstanding per `JRedisBlocking` at a time; further
calls queue behind it. Each is sent from a fresh task, so a long queue drains
cleanly, with every call failing, when the connection is lost. The client-side
timeout is the server timeout plus the normal command timeout. A server timeout
of `0` (wait forever) disables it. `isConnected()` shows the connection's
state.

## 9. Sync facade

```java
JRedisSync sync = client.sync();
String userId = sync.get("sess:" + token);   // blocks up to the command timeout
```

Every method is `future.get(timeout)` with the exception unwrapped. Two guards
make misuse fail loudly instead of hanging:

| Guard | Why |
|---|---|
| Throws if called on one of the client's own event-loop threads | That thread is the one that must read the reply, so this is a guaranteed deadlock |
| Throws if the thread is marked non-blocking (`ThreadGuard.markNonBlocking()`) | Latency-critical threads mark themselves at start-up, turning an accidental blocking call into an immediate exception |

## 10. Embedded mode

```java
try (JRedisEmbedded server = JRedisEmbedded.start(EmbeddedConfig.inMemory())) {  // or .persistentAt(dir)
    JRedisClient client = server.newClient();
    // … identical API …
}
```

- Starts the real server (command thread, `Db` and optionally persistence)
  listening on **Netty's in-VM transport** (`LocalServerChannel`) instead of
  TCP. The embedded client connects with `LocalChannel`. Both sides run the
  same pipelines as over the network: RESP encoding, the request decoder,
  backpressure and reply decoding. So `Reply` objects, ordering and timeouts
  are identical, and code that works embedded works over TCP. No socket is
  opened.
- `EmbeddedConfig.inMemory()` (no persistence, fastest) or
  `.persistentAt(dir)` (the full AOF). `.clock(new ManualClock(t))` makes TTL
  tests deterministic, and `.configure(c -> …)` sets any server directive.
- A fail-stop does not halt the JVM in embedded mode. The engine stops and
  `fatalError()` returns the reason, which tests assert is null.

This needed no seam beyond the existing one: the server's `NettyServer` can
listen either on TCP or on a local address.

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
2. Never touch state owned by another thread (session state, caches).
3. Hand the result to its owner thread through that thread's queue.

## 12. Configuration

| Setting | Default | Notes |
|---|---|---|
| `address` | `127.0.0.1:6379` | `localAddress(name)` for embedded mode |
| `password` | none | Sent with `AUTH` on every (re)connect |
| `clientName` | none | Shown in `CLIENT LIST`. Set it: it makes incidents readable. |
| `eventLoopGroup` | own group | Share the application's group to save threads |
| `ioThreads` | 1 | Threads of the client's own group |
| `connectTimeoutMillis` | 2000 | |
| `commandTimeoutMillis` | 2000 | |
| `commandConnections` | 1 | 2 for busy services |
| `reconnectBackoffMillis` | 100 → 5000 | Exponential, ±20 % jitter |
| `pubsubPingMillis` | 5000 | A subscriber's connection pinged, so one answering nothing is noticed ([16 §10](16-replication.md#10-clients-following-the-primary)); 0, never |
| `leasePoolMax` | 4 | `WATCH` connections |
| `callbackExecutor` | event loop | Set an executor if callbacks cannot be kept short |

## 13. Metrics

`client.metrics()` returns counters (`sent`, `received`, `serverErrors`,
`timeouts`, `connectionLosses`, `reconnects` and `failedFast`) and a latency
histogram (HdrHistogram, microseconds) per command name via `latency()`.
Services export these alongside their own metrics.
