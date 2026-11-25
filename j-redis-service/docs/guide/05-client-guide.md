# 5 — Use it from Java: client guide

This guide shows how an application uses j-redis-service through its Java
client, `j-redis-client`. It is for developers who write application code
against the store. It covers adding the client to a project, creating it, the
sync and async styles, every data type, transactions, locks, queues, streams,
pub/sub, errors, replicas, settings, metrics and tests. Every pattern here also
exists as a complete, runnable class in the `j-redis-examples` module. The
build runs them all on every test run, so they are known to work
([§5.15](#515-the-runnable-examples)). How the client works inside is in
[../10-client-library.md](../10-client-library.md).

## 5.1 Add the client to your project

1. Run `mvn install` in `j-redis-service` ([guide 3](03-build-and-test.md)).
   This puts its jars into your local Maven repository. In the backend's
   repository, `./dev-jredis.sh build` does this, into `work/m2`.
2. Add the dependencies to your service's `pom.xml`:

```xml
<dependency>
  <groupId>com.jredis</groupId>
  <artifactId>j-redis-client</artifactId>
  <version>2.2.2</version>
</dependency>

<!-- for tests: the real server, in-process -->
<dependency>
  <groupId>com.jredis</groupId>
  <artifactId>j-redis-embedded</artifactId>
  <version>2.2.2</version>
  <scope>test</scope>
</dependency>
```

The client brings in `j-redis-common`, Netty 4.1.122 (`netty-common`,
`-buffer`, `-resolver`, `-transport`, `-codec`), `slf4j-api` and `HdrHistogram`.
Netty is the network library; SLF4J is the logging interface. To see the
client's log, add an SLF4J binding such as `logback-classic`. Everything is
Java 21 and is in the offline bundle. If your service already uses Netty, keep
one Netty version for both. The backend, for example, sets Netty 4.2.18.Final
for all its modules, j-redis's included, in `backend/pom.xml`.

**Without Maven**, put these jars on the classpath. All are in
`java21-offline/repository`, except the two j-redis jars, which are in their
modules' `target/` directories: `j-redis-client-2.2.2.jar`,
`j-redis-common-2.2.2.jar`, `netty-common`, `netty-buffer`, `netty-resolver`,
`netty-transport`, `netty-codec` (all `4.1.122.Final`), `slf4j-api-2.0.17.jar`
and `HdrHistogram-2.2.2.jar`.

## 5.2 Create one client for the whole application

```java
import com.jredis.client.JRedisClient;

JRedisClient redis = JRedisClient.builder()
        .address("127.0.0.1", 6379)
        .password(System.getenv("JREDIS_PASSWORD"))   // if the server has requirepass
        .clientName("orders-service")                  // shows up in CLIENT LIST
        .build()
        .start();                                      // connect now, waiting up to connectTimeoutMillis

// … use it everywhere; it is thread-safe …

redis.close();                                         // once, at application shutdown
```

- **Create one client per application.** It is thread-safe. It pipelines the
  requests of all threads automatically: it sends them without waiting for
  each reply. It also reconnects by itself.
- **`start()` connects.** It waits up to the connect timeout. If the server is
  not up yet, it logs a warning and keeps trying in the background. Until it
  connects, requests fail at once. `awaitConnected(millis)` waits for the
  connection and returns `true` once one is ready; `isConnected()` tells
  without waiting. On a client I/O thread, or on a thread marked non-blocking
  ([§5.3](#53-sync-or-async)), `start()` does not wait at all.
- **Nothing connects before `start()`.** Requests then fail with "the client
  was never started: call start() first". Calling `start()` twice is harmless.
- **Busy services can use `.commandConnections(2)`**, so that one slow reply
  does not delay the others. The client pins each thread to one connection, so
  a thread's commands still run in the order it sent them. A thread moves to
  another connection only while its own is down.

## 5.3 Sync or async?

The client has two styles. The async methods are on the client itself; the
sync ones are on `redis.sync()`.

| | Async: `redis.get(k)` returns `CompletableFuture<String>` | Sync: `redis.sync().get(k)` returns `String` |
|---|---|---|
| Blocks the calling thread | never | until the reply or the command timeout |
| Use on | event loops, request handlers of non-blocking servers, latency-critical threads | ordinary worker threads, scripts, tests |
| Errors | the future completes exceptionally | thrown directly |

```java
// sync: easiest to read
String name = redis.sync().get("user:42:name");

// async: compose instead of waiting
redis.get("sess:" + token)
     .thenCompose(userId -> userId == null ? CompletableFuture.completedFuture(null)
                                           : redis.hgetall("user:" + userId))
     .whenComplete((profile, error) -> worker.execute(() -> respond(profile, error)));
```

Async callbacks run on a client I/O thread. Follow these rules:

1. Never block in them: no `join()`, no `get()`, no `sync()`, no I/O.
2. Hand results over to the thread that owns the state (for example with
   `executor.execute(...)`).
3. Keep them short, or set `.callbackExecutor(executor)` on the builder so that
   they run on your executor instead.

The sync facade refuses to run where it would cause trouble. It throws
`IllegalStateException` when called on a client I/O thread, because that would
be a certain deadlock: that thread must process the very reply it waits for.
It also throws on any thread marked with `ThreadGuard.markNonBlocking()`
(`ThreadGuard.unmark()` removes the mark). Mark your latency-critical threads
once at start-up. An accidental blocking call then becomes an immediate,
obvious error instead of a latency spike.

## 5.4 Strings, counters and TTLs

A TTL (time to live) makes a key expire by itself after a given time.

```java
JRedisSync r = redis.sync();
r.set("config:mode", "live");                               // SET
r.set("lock:x", "me", SetArgs.nx().andPx(30_000));          // SET NX PX: false if it exists
r.set("cache:7", json, SetArgs.ex(300));                    // with a 5-minute TTL
String v = r.get("config:mode");                            // null if missing
List<String> vs = r.mget("a", "b", "c");                    // nulls for missing keys
long n = r.incr("visits");                                  // atomic counter
long m = r.incrBy("stock:sku-9", -1);
double d = r.incrByFloat("balance:7", 2.5);
r.expire("config:mode", 60);  r.persist("config:mode");     // add / remove a TTL
long ttl = r.ttl("cache:7");                                // seconds; -1 no TTL; -2 no key
r.del("a", "b");  r.exists("a");                            // how many were deleted / exist
```

`SetArgs` starts with `none()`, `nx()`, `xx()`, `ex(seconds)` or `px(millis)`,
and adds more with `andNx()`, `andXx()`, `andEx(…)`, `andPx(…)`, `andExAt(…)`,
`andPxAt(…)` and `andKeepTtl()`.

How the client sends values:

| Java value | Sent as |
|---|---|
| `String` | UTF-8 bytes |
| `byte[]` | The bytes as they are, for binary values. Read them back with `getBytes(key)`. |
| `Long`, `Integer`, `Short`, `Byte`, `Double`, `Float` | A decimal string |
| `null` | Refused with `IllegalArgumentException` |
| Any other object | Its `String.valueOf(...)`, as UTF-8 |

*Example: `StringsAndCounters`, `CacheAside`, `SessionStore`.*

## 5.5 Hashes, lists, sets and sorted sets

```java
// hash: an object's fields
r.hset("user:42", "name", "Ada");
r.hset("user:42", fields);                          // Map<String, ?>
Map<String, String> user = r.hgetall("user:42");
r.hincrBy("user:42", "logins", 1);

// list: a queue or a bounded history
r.lpush("feed:7", "event-1");
r.ltrim("feed:7", 0, 199);                          // keep the newest 200
List<String> recent = r.lrange("feed:7", 0, 9);

// set: membership
r.sadd("online", "user:42");
boolean on = r.sismember("online", "user:42");

// sorted set: rankings, time-ordered indexes
r.zincrby("lb:weekly", 10, "user:42");
List<ScoredMember> top = r.zrevrangeWithScores("lb:weekly", 0, 9);
Long rank = r.zrevrank("lb:weekly", "user:42");     // 0-based, null if absent
ZAround around = r.zaround("lb:weekly", "user:42", 5, true);   // J.ZAROUND: me ± 5 rows
```

`zaround(key, member, count, descending)` calls the extension `J.ZAROUND`. It
returns the member's 0-based `rank` and a `window` of up to `count` neighbours
on each side, with scores; `firstRank` is the rank of the window's first row.
Use `descending = true` for leaderboards. It returns `null` if the key or the
member does not exist.

*Example: `Leaderboard`, `ServiceRegistry`, `WorkQueue`.*

## 5.6 Any other command: `send`

`send` sends any command, including those without a typed method. Arguments
may be `String`, `byte[]` or numbers, as in [§5.4](#54-strings-counters-and-ttls):

```java
Reply reply = r.send("ZRANGE", "lb:weekly", 0, 9, "REV", "WITHSCORES");
for (Reply item : reply.asList()) {
    System.out.println(item.asString());
}
```

`Reply` (in `com.jredis.common`) has `type()` (`SIMPLE`, `ERROR`, `INTEGER`,
`BULK`, `ARRAY`, `NULL`), `asString()`, `asBytes()`, `asLong()`, `asDouble()`,
`asList()`, `isNull()` and `isError()`. An error reply from the server does not
come back as a `Reply`: the call fails with `JRedisServerException`
([§5.12](#512-errors-timeouts-and-reconnection)).

## 5.7 Transactions: several commands, atomically

A transaction runs several commands as one block. `redis.multi()` builds it:

```java
List<Reply> result = redis.multi()
        .send("HSET", "token:" + id, "userId", "42", "target", "checkout")
        .send("EXPIRE", "token:" + id, 60)
        .exec()
        .join();
```

- **The commands run one after the other, with nothing in between.** The
  client writes the whole block (`MULTI`, the commands, `EXEC`) in one round
  trip, so no other request on the connection can end up inside it.
- **A command that fails at run time** (a wrong type, say) gives an error
  `Reply` in the list. The others still run.
- **A malformed command** stops the whole block: nothing runs, and the future
  fails with `EXECABORT`. The message names the first rejected command.
- **Some commands cannot be queued**: `MULTI`, `EXEC`, `DISCARD`, `WATCH`,
  `UNWATCH`, `QUIT`, `RESET`, `SELECT`, `AUTH`, `HELLO` and the subscribe
  commands. `send` refuses them with `IllegalArgumentException`. A transaction
  runs once; a second `exec()` throws `IllegalStateException`.

*Example: `OneTimeToken`, `RateLimiter`, `SessionStore`.*

## 5.8 Read-decide-write: `WATCH` (optimistic locking)

When the writes depend on values you read first, watch the keys. If another
client changes a watched key before `EXEC`, `EXEC` returns `null` and changes
nothing. Then you read again and retry:

```java
boolean ok = redis.withLeasedConnection(conn -> {
    for (int attempt = 0; attempt < 10; attempt++) {
        conn.sync().watch("balance:alice");
        long balance = Long.parseLong(conn.sync().get("balance:alice"));
        if (balance < amount) { conn.sync().unwatch(); return false; }
        List<Reply> r = conn.sync().exec(conn.multi()
                .send("DECRBY", "balance:alice", amount)
                .send("INCRBY", "balance:bob", amount));
        if (r != null) return true;              // committed
    }                                            // null: changed under us; read again
    throw new IllegalStateException("too much contention");
});
```

- **`WATCH` belongs to a connection.** So `withLeasedConnection` lends you a
  connection for your use alone. Afterwards it sends `UNWATCH` and returns the
  connection to a small pool (`leasePoolMax`, 4 by default). When all of them
  are in use, a caller waits up to the command timeout, then gets
  `JRedisTimeoutException`. `sync().watch(…)` on any other connection throws
  `IllegalStateException`.
- **It blocks**, so use it on worker threads. It refuses to run on a client
  I/O thread or a thread marked non-blocking (`IllegalStateException`).
- **A reconnect loses the watch.** If the connection dropped and reconnected
  between `WATCH` and `EXEC`, the server no longer watches anything. `exec`
  then fails with `JRedisConnectionException` rather than commit without the
  check. Treat it like a conflict: read again and retry.

*Example: `OptimisticLocking`.*

## 5.9 Locks: `J.CAS` and `J.CAD`

A lock here is a key with a unique token as its value and a lease (a TTL), so
that it frees itself if its owner dies:

```java
String token = UUID.randomUUID().toString();
if (r.set("lock:report", token, SetArgs.nx().andPx(30_000))) {   // acquire, with a lease
    try {
        // … work; for long work, renew: r.cas("lock:report", token, token, SetArgs.px(30_000))
    } finally {
        r.cad("lock:report", token);                               // release only if still ours
    }
}
```

`J.CAS` (compare-and-set) and `J.CAD` (compare-and-delete) are the service's
extension commands. `cas(key, expected, value)` sets the key only if it still
holds `expected`; `cad(key, expected)` deletes it only then. Both return
`true` if they acted. Without a TTL option, `cas` removes the key's TTL, so
pass `SetArgs.px(…)` or `SetArgs.ex(…)` when you renew a lease. They make
renewal and release safe without Lua scripts: a process whose lease has
already expired can never touch the next owner's lock.
*Example: `DistributedLock`.*

## 5.10 Queues and blocking pops

Blocking commands (`BLPOP`, `BRPOP`, `BLMOVE`, `BZPOPMIN`, `BZPOPMAX`) wait on
the server until an item arrives. They get a dedicated connection,
`redis.blocking()`:

```java
JRedisBlocking blocking = redis.blocking();
blocking.blmove("q:jobs", "q:jobs:processing", false, true, 5.0)   // RIGHT → LEFT, wait up to 5 s
        .thenAccept(job -> { if (job != null) handle(job); });     // null: timed out
```

- **The blocking connection runs one blocking command at a time.** Further
  calls queue behind it. The client's own timeout is the server timeout plus
  the command timeout; a server timeout of 0 (wait for ever) turns it off.
- **On the shared connection, `send` refuses them** with
  `IllegalArgumentException` and an explanation, because they would stall
  every other request.
- **For a queue that loses nothing when a consumer crashes**, move each item
  to a processing list and remove it only after handling it.

*Example: `WorkQueue`.*

**Streams (2.1)** do the same and more. Several consumer groups read one log.
Each entry stays pending for its consumer until the consumer acknowledges it.
Another consumer can claim a crashed consumer's entries
([15](../15-streams.md)):

```java
redis.sync().xgroupCreate("s:events", "billing", "0", true);             // MKSTREAM
redis.sync().xadd("s:events", "e", json);
redis.blocking().xreadgroup(5_000, "billing", "worker-1", 64, Map.of("s:events", ">"))
        .thenAccept(got -> got.getOrDefault("s:events", List.of())
                .forEach(e -> { handle(e.fields()); redis.xack("s:events", "billing", e.id()); }));
redis.sync().xautoclaim("s:events", "billing", "worker-1", 60_000, "0", 64);   // a dead consumer's
```

`blocking().xreadgroup(blockMillis, group, consumer, count, streams)` waits up
to `blockMillis` (0: for ever). It returns the entries per stream, or an empty
map on timeout. Each entry, a `Streams.Entry`, has an `id()` and its
`fields()`. `XREAD` and `XREADGROUP` with `BLOCK` are refused on the shared
connection, as the blocking pops are; use `blocking().xread(…)` and
`blocking().xreadgroup(…)`.

## 5.11 Publish/subscribe

Pub/sub sends a message to every client subscribed to a channel at that moment.
Subscriptions use a dedicated connection, `redis.pubSub()`:

```java
JRedisPubSub ps = redis.pubSub();
ps.subscribe("user:42", (channel, message) -> notifyUser(new String(message, UTF_8))).join();
ps.psubscribe("group:*", (pattern, channel, message) -> fanOut(channel, message)).join();
ps.onReconnect(() -> refreshStateThatMayHaveChanged());
redis.publish("user:42", "your order has shipped");        // returns the receiver count
```

- `subscribe` and `psubscribe` return a future that completes when the server
  confirms. A message arrives as `byte[]`. `unsubscribe(channel)` and
  `punsubscribe(pattern)` stop them.
- Listeners run on a client I/O thread, as async callbacks do
  ([§5.3](#53-sync-or-async)): keep them short.
- After a reconnect, the client re-issues the subscriptions automatically,
  then runs the `onReconnect` hook.
- Pub/sub is **at-most-once**: a subscriber loses the messages published while
  it is disconnected. Use the hook to catch up on state, and use a list for
  anything that must arrive.

*Example: `PubSubNotifications`.*

## 5.12 Errors, timeouts and reconnection

Every exception of the library extends `JRedisException`, a
`RuntimeException`.

| Exception | Meaning | What to do |
|---|---|---|
| `JRedisServerException` | The server rejected the command. `prefix()`: `WRONGTYPE`, `ERR`, `OOM`, `MISCONF`, `NOAUTH`… | A bug or bad input; retrying does not help (except `OOM`/`MISCONF` after the operator fixes the cause) |
| `JRedisTimeoutException` | No reply within `commandTimeoutMillis` | The command **may or may not** have run |
| `JRedisConnectionException` | Not connected, or the connection dropped with the request pending | Same: the outcome is unknown. The client reconnects by itself (backoff 100 ms → 5 s). |
| `JRedisClosedException` | The client was closed | A programming error at shutdown |
| `IllegalArgumentException` | A stateful command on the shared connection (`BLPOP`, `XREADGROUP … BLOCK`, `SUBSCRIBE`, `MULTI`, `WATCH`, `QUIT`, `SELECT`, `AUTH`…) | Use `blocking()`, `pubSub()`, `multi()` or `withLeasedConnection()` |
| `IllegalStateException` | A sync call on a client I/O thread or a thread marked non-blocking | Use the async API there |

**The library never retries by itself**, because retrying an `INCR` could
count twice. Retry only operations that are safe to repeat (reads, a `SET` of a
fixed value, `DEL`), or make writes idempotent, for example with unique ids.

The client also notices a stuck connection. After 5 timeouts in a row, or when
a timed-out request has had no reply for 3 times the command timeout, it
closes the connection and reconnects. Reconnection waits 100 ms first, then twice as long
each time, up to 5 s, each wait varied by up to 20 % so that many clients do not
reconnect at the same moment. After a failed `AUTH`, it waits the maximum.

*Example: `ErrorHandling`.*

### A primary and its replicas (2.2)

Give the client every server, and it uses the primary among them:

```java
JRedisClient redis = JRedisClient.builder()
        .addresses("10.0.0.1:6379", "10.0.0.2:6379")      // a primary and its replica, in any order
        .password(secret)
        .build().start();
```

1. Each connection asks every address `ROLE` and uses the primary with the
   highest epoch. The epoch is a number that grows with each promotion
   ([16](../16-replication.md)).
2. When the connection loses that primary, or the server answers `READONLY`
   because the primary was demoted, the connection asks again.
3. After an operator promotes the replica (`REPLICAOF NO ONE`), the client finds
   it by itself within a reconnect.

The client never uses a primary whose epoch is lower than that of any server it
has seen, replicas included. So an old primary that comes back without being
fenced gets nothing from it
([16 §10](../16-replication.md#10-clients-following-the-primary)). Subscriber
connections follow too: the client sends `PING` on them every
`pubsubPingMillis` so that a silent one is noticed. Commands in flight at the
moment of a failover fail as on any lost connection: retry what is safe to
retry.

To make a write survive the loss of the primary's machine, wait for a replica to
hold it: `redis.sync().send("WAIT", "1", "1000")` returns how many replicas
did. A write that `WAIT` confirmed survives a promotion; one it did not confirm
may be lost.

## 5.13 Configuration and metrics

All settings are builder methods on `JRedisClient.builder()`:

| Builder method | Default | Notes |
|---|---|---|
| `address(host, port)` | `127.0.0.1`, `6379` | One server |
| `addresses("host:port", …)` | none | A primary and its replicas ([§5.12](#a-primary-and-its-replicas-22)). With one address, the same as `address`. |
| `localAddress(name)` | none | An embedded server, over Netty's in-VM transport (no socket); `JRedisEmbedded.newClient()` sets it |
| `password(p)` | none | Sent with `AUTH` on every connect and reconnect |
| `clientName(n)` | none | Set it; it makes `CLIENT LIST` readable |
| `connectTimeoutMillis(ms)` / `commandTimeoutMillis(ms)` | 2000 / 2000 | |
| `commandConnections(n)` | 1 | 2 for busy services |
| `reconnectBackoffMillis(min, max)` | 100, 5000 | Doubles each time, with jitter (±20 %) |
| `pubsubPingMillis(ms)` | 5000 | How often the subscriber connection is pinged; 0, never |
| `leasePoolMax(n)` | 4 | Connections for `withLeasedConnection` |
| `ioThreads(n)` / `eventLoopGroup(g)` | 1 thread / own | Share your application's Netty group to save threads |
| `callbackExecutor(e)` | the I/O thread | Where async callbacks run |

`build()` refuses a value below 1 for `commandConnections`, `leasePoolMax` and
`ioThreads` (`IllegalArgumentException`).

`redis.metrics()` returns a `ClientMetrics`. It has counters (`sent`,
`received`, `timeouts`, `connectionLosses`, `reconnects`, `failedFast`,
`serverErrors`, each an `AtomicLong`) and `latency()`: an HdrHistogram per
command name, in microseconds. Export them to your monitoring:

```java
ClientMetrics m = redis.metrics();
long p99 = m.latency().get("GET").getValueAtPercentile(99);    // µs
```

## 5.14 Testing your code

Tests start the real server in-process, with `j-redis-embedded`. There is no
installation and no socket, and each test gets a fresh, empty server. A manual
clock (`com.jredis.server.core.ManualClock`) makes TTLs deterministic:

```java
class LoginAttemptsTest {
    private final ManualClock clock = new ManualClock(1_767_225_600_000L);
    private JRedisEmbedded server;

    @BeforeEach void start() { server = JRedisEmbedded.start(EmbeddedConfig.inMemory().clock(clock)); }
    @AfterEach  void stop()  { server.close(); }

    @Test void failuresExpire() {
        LoginAttempts attempts = new LoginAttempts(server.newClient());   // your class, a normal client
        for (int i = 0; i < 4; i++) attempts.recordFailure("bob");
        clock.advance(15 * 60 * 1000 + 1);                                 // no sleeping
        assertThat(attempts.recordFailure("bob")).isFalse();
    }
}
```

The complete class is `EmbeddedTestingExampleTest` in
`j-redis-examples/src/test/java`.

| Call | Does |
|---|---|
| `JRedisEmbedded.start()` | Starts an in-memory server (the same as `start(EmbeddedConfig.inMemory())`) |
| `EmbeddedConfig.inMemory()` | No persistence: the fastest choice for unit tests |
| `EmbeddedConfig.persistentAt(dir)` | Full persistence in `dir`, exactly as the standalone server |
| `.clock(clock)` | A controllable clock, such as `ManualClock` (`advance(ms)`, `set(ms)`) |
| `.configure(c -> c.requirepass("x"))` | Sets any server directive |
| `server.newClient()`, `server.newClient(b -> …)` | A started client, optionally with builder changes |
| `server.close()` | Stops the server and closes the clients it created |
| `server.fatalError()` | The fail-stop reason if the server stopped itself, otherwise `null` |

## 5.15 The runnable examples

`j-redis-examples/src/main/java/com/jredis/examples/` has one class per topic:

| Name | Class | Shows |
|---|---|---|
| `quickstart` | `QuickStart` | connect, SET/GET, async vs. blocking calls |
| `strings` | `StringsAndCounters` | SET options (NX/XX/EX), MSET/MGET, counters, TTLs |
| `cache` | `CacheAside` | cache-aside with TTL and invalidation |
| `sessions` | `SessionStore` | session tokens, sliding expiry, attributes in a hash |
| `leaderboard` | `Leaderboard` | top N, my rank, rows around me (`J.ZAROUND`), best score, daily boards |
| `ratelimit` | `RateLimiter` | fixed-window and sliding-window rate limiting |
| `lock` | `DistributedLock` | acquire, renew, release (`J.CAS`/`J.CAD`) |
| `watch` | `OptimisticLocking` | `WATCH`/`MULTI`/`EXEC` with retries, under concurrency |
| `token` | `OneTimeToken` | issue with TTL, redeem exactly once |
| `queue` | `WorkQueue` | reliable queue with `BLMOVE` and crash recovery |
| `pubsub` | `PubSubNotifications` | channels and patterns |
| `registry` | `ServiceRegistry` | heartbeats and automatic cleanup |
| `async` | `AsyncPipelining` | pipelining 10,000 requests, composing futures |
| `scan` | `ScanKeys` | `SCAN` with `MATCH`/`COUNT`, deleting by pattern |
| `errors` | `ErrorHandling` | server errors, timeouts, connection loss, safe retries |

Run them from the distribution, or with
`java -jar j-redis-examples/target/j-redis-examples-2.2.2-all.jar`:

```bash
bin/j-redis-examples --list                  # names and summaries
bin/j-redis-examples                         # all, against an in-process server
bin/j-redis-examples leaderboard lock        # some of them
bin/j-redis-examples -p 6379 -a secret       # against a running server
```

Without `-h` or `-p`, the examples start their own in-process server.
`-h <host>` and `-p <port>` (default `127.0.0.1`, `6379`) run them against a
server instead, and `-a` gives its password. An unknown name stops the run with
exit code 2. Every example uses its own key prefix and cleans up first, so you
can run it against a shared development server. The build's `ExamplesTest`
runs every example twice against an embedded server, so the examples cannot
rot.
