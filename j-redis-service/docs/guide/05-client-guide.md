# 5 — Use it from Java: client guide

How to use j-redis-service from an application with `j-redis-client`. Every
pattern here also exists as a complete, runnable class in the
`j-redis-examples` module. The build runs them all on every test run, so they
are known to work ([§5.15](#515-the-runnable-examples)). How the client works
inside is in [../10-client-library.md](../10-client-library.md).

## 5.1 Add the client to your project

First run `mvn install` in `j-redis-service` ([guide 3](03-build-and-test.md)),
which puts its jars into your local Maven repository. Then, in your service's
`pom.xml`:

```xml
<dependency>
  <groupId>com.jredis</groupId>
  <artifactId>j-redis-client</artifactId>
  <version>2.1.0</version>
</dependency>

<!-- for tests: the real server, in-process -->
<dependency>
  <groupId>com.jredis</groupId>
  <artifactId>j-redis-embedded</artifactId>
  <version>2.1.0</version>
  <scope>test</scope>
</dependency>
```

The client brings `j-redis-common`, Netty 4.1.122 (`netty-common`, `-buffer`,
`-resolver`, `-transport`, `-codec`), `slf4j-api` and `HdrHistogram`. Add an
SLF4J binding such as `logback-classic` to see its log. Everything is Java 21
and in the offline bundle. If your service already uses Netty 4.1, keep one
Netty version for both.

**Without Maven**, put these jars on the classpath (all in
`java21-offline/repository`, plus the two j-redis jars from their modules'
`target/` directories): `j-redis-client-2.1.0.jar`, `j-redis-common-2.1.0.jar`,
`netty-common`, `netty-buffer`, `netty-resolver`, `netty-transport`,
`netty-codec` (all `4.1.122.Final`), `slf4j-api-2.0.17.jar` and
`HdrHistogram-2.2.2.jar`.

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

- **One client per application** is the norm. It is thread-safe, pipelines
  requests from all threads automatically, and reconnects by itself.
- `start()` waits up to the connect timeout. If the server is not up yet, it
  logs a warning and keeps retrying in the background. Requests fail fast
  until it connects, and `awaitConnected(millis)` waits for it. Nothing
  connects before `start()` (requests then fail with "call start() first"),
  and calling it twice is harmless.
- Busy services can use `.commandConnections(2)`, so that one slow reply
  does not delay the others. Each thread sticks to one connection, so a
  thread's commands still run in the order it sent them.

## 5.3 Sync or async?

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

Rules for async callbacks (they run on a client I/O thread):

1. Never block in them: no `join()`, no `get()`, no `sync()`, no I/O.
2. Hand results over to the thread that owns the state (for example with
   `executor.execute(...)`).
3. Keep them short, or set `.callbackExecutor(executor)` on the builder so they
   run elsewhere.

The sync facade throws `IllegalStateException` if called on a client I/O
thread, which would be a certain deadlock. It also throws on any thread marked
with `ThreadGuard.markNonBlocking()`. Mark your latency-critical threads once
at start-up, and an accidental blocking call becomes an immediate, obvious
error instead of a latency spike.

## 5.4 Strings, counters and TTLs

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

Binary values: pass `byte[]` wherever a value is accepted, and read with
`getBytes(key)`. Numbers are sent as decimal strings.
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

*Example: `Leaderboard`, `ServiceRegistry`, `WorkQueue`.*

## 5.6 Any other command: `send`

Every command, including those without a typed method, goes through `send`.
Arguments may be `String`, `byte[]` or numbers:

```java
Reply reply = r.send("ZRANGE", "lb:weekly", 0, 9, "REV", "WITHSCORES");
for (Reply item : reply.asList()) {
    System.out.println(item.asString());
}
```

`Reply` has `type()` (SIMPLE, ERROR, INTEGER, BULK, ARRAY, NULL), `asString()`,
`asBytes()`, `asLong()`, `asDouble()`, `asList()` and `isNull()`.

## 5.7 Transactions: several commands, atomically

```java
List<Reply> result = redis.multi()
        .send("HSET", "token:" + id, "userId", "42", "target", "checkout")
        .send("EXPIRE", "token:" + id, 60)
        .exec()
        .join();
```

The commands run one after the other with nothing in between, and the whole
block is written in one round trip. If one command fails at run time (a wrong
type, say), its `Reply` is an error and the others still run. If a command is
malformed, nothing runs and the future fails with `EXECABORT`.
*Example: `OneTimeToken`, `RateLimiter`, `SessionStore`.*

## 5.8 Read-decide-write: `WATCH` (optimistic locking)

When the writes depend on values read first, watch the keys. `EXEC` returns
`null` if another client changed them in between, and then you retry:

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

`WATCH` belongs to a connection, so `withLeasedConnection` lends you one
exclusively and resets it afterwards. It blocks, so it is for worker threads
(it refuses to run on event-loop or non-blocking threads). If the connection
dropped and reconnected between `WATCH` and `EXEC`, the watch is gone, and
`exec` fails with `JRedisConnectionException` rather than committing unchecked.
Treat it like a conflict: read again and retry. *Example: `OptimisticLocking`.*

## 5.9 Locks: `J.CAS` and `J.CAD`

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
extension commands. They make renewal and release safe without Lua: a process
whose lease already expired can never touch the next owner's lock.
*Example: `DistributedLock`.*

## 5.10 Queues and blocking pops

Blocking commands (`BLPOP`, `BLMOVE`, `BZPOPMIN` …) get a dedicated connection:

```java
JRedisBlocking blocking = redis.blocking();
blocking.blmove("q:jobs", "q:jobs:processing", false, true, 5.0)   // RIGHT → LEFT, wait up to 5 s
        .thenAccept(job -> { if (job != null) handle(job); });     // null: timed out
```

On the shared connection they are refused with an explanation, because they
would stall every other request. For a queue that loses nothing when a
consumer crashes, move items to a processing list and remove them only after
handling. *Example: `WorkQueue`.*

**Streams (2.1)** do the same with more: several consumer groups read one
log, each entry stays pending for its consumer until acknowledged, and a
crashed consumer's entries are claimed by another ([15](../15-streams.md)):

```java
redis.sync().xgroupCreate("s:events", "billing", "0", true);             // MKSTREAM
redis.sync().xadd("s:events", "e", json);
redis.blocking().xreadgroup(5_000, "billing", "worker-1", 64, Map.of("s:events", ">"))
        .thenAccept(got -> got.getOrDefault("s:events", List.of())
                .forEach(e -> { handle(e.fields()); redis.xack("s:events", "billing", e.id()); }));
redis.sync().xautoclaim("s:events", "billing", "worker-1", 60_000, "0", 64);   // a dead consumer's
```

`XREAD` and `XREADGROUP` with `BLOCK` are refused on the shared connection, as
the pops are.

## 5.11 Publish/subscribe

```java
JRedisPubSub ps = redis.pubSub();
ps.subscribe("user:42", (channel, message) -> notifyUser(new String(message, UTF_8))).join();
ps.psubscribe("group:*", (pattern, channel, message) -> fanOut(channel, message)).join();
ps.onReconnect(() -> refreshStateThatMayHaveChanged());
redis.publish("user:42", "your order has shipped");        // returns the receiver count
```

Subscriptions are re-issued automatically after a reconnect. Pub/sub is
**at-most-once**: messages published while a subscriber is disconnected are
lost. Use a list for anything that must arrive. *Example: `PubSubNotifications`.*

## 5.12 Errors, timeouts and reconnection

| Exception | Meaning | What to do |
|---|---|---|
| `JRedisServerException` | The server rejected the command. `prefix()`: `WRONGTYPE`, `ERR`, `OOM`, `MISCONF`, `NOAUTH`… | A bug or bad input; retrying does not help (except `OOM`/`MISCONF` after the operator fixes the cause) |
| `JRedisTimeoutException` | No reply within `commandTimeoutMillis` | The command **may or may not** have run |
| `JRedisConnectionException` | Not connected, or the connection dropped with the request pending | Same: outcome unknown. The client reconnects by itself (backoff 100 ms → 5 s). |
| `JRedisClosedException` | The client was closed | Programming error at shutdown |
| `IllegalArgumentException` | A stateful command on the shared connection (`BLPOP`, `XREADGROUP … BLOCK`, `SUBSCRIBE`, `MULTI`, `WATCH`…) | Use `blocking()`, `pubSub()`, `multi()` or `withLeasedConnection()` |
| `IllegalStateException` | A sync call on a client I/O thread or a thread marked non-blocking | Use the async API there |

**The library never retries by itself**, because retrying an `INCR` could
count twice. Retry only operations that are safe to repeat (reads, `SET` of a
fixed value, `DEL`), or make writes idempotent (use unique ids).
*Example: `ErrorHandling`.*

### A primary and its replicas (2.2)

Give the client every server, and it uses the primary among them:

```java
JRedisClient redis = JRedisClient.builder()
        .addresses("10.0.0.1:6379", "10.0.0.2:6379")      // a primary and its replica, in any order
        .password(secret)
        .build().start();
```

Each connection asks every address `ROLE` and uses the primary with the highest
epoch. When it loses that primary, or is told `READONLY` because the primary was
demoted, it asks again; after an operator promotes the replica
(`REPLICAOF NO ONE`), the client finds it by itself within a reconnect. It never
uses a primary whose epoch is lower than any server it has seen, replicas
included: an old primary that comes back unfenced gets nothing from it
([16 §10](../16-replication.md#10-clients-following-the-primary)). Commands in
flight at the moment of a failover fail as any lost connection's do: retry what
is safe to retry.

To make a write survive the loss of the primary's machine, wait for a replica to
hold it: `redis.sync().send("WAIT", "1", "1000")` returns how many did. A write
that `WAIT` confirmed survives a promotion; one it did not may not.

## 5.13 Configuration and metrics

| Builder method | Default | Notes |
|---|---|---|
| `address(host, port)` | `127.0.0.1`, `6379` | |
| `password(p)` | none | Sent with `AUTH` on every (re)connect |
| `clientName(n)` | none | Set it; it makes `CLIENT LIST` readable |
| `connectTimeoutMillis(ms)` / `commandTimeoutMillis(ms)` | 2000 / 2000 | |
| `commandConnections(n)` | 1 | 2 for busy services |
| `reconnectBackoffMillis(min, max)` | 100, 5000 | Exponential with jitter |
| `leasePoolMax(n)` | 4 | Connections for `withLeasedConnection` |
| `ioThreads(n)` / `eventLoopGroup(g)` | 1 thread / own | Share your application's Netty group to save threads |
| `callbackExecutor(e)` | the I/O thread | Where async callbacks run |

`redis.metrics()` has counters (`sent`, `received`, `timeouts`,
`connectionLosses`, `reconnects`, `failedFast`, `serverErrors`) and
`latency()`, an HdrHistogram per command name in microseconds. Export them to
your monitoring:

```java
ClientMetrics m = redis.metrics();
long p99 = m.latency().get("GET").getValueAtPercentile(99);    // µs
```

## 5.14 Testing your code

Tests start the real server in-process: no installation, no socket, a fresh
empty server per test. A manual clock makes TTLs deterministic:

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
`j-redis-examples/src/test/java`. `EmbeddedConfig.persistentAt(dir)` enables
persistence, and `.configure(c -> c.requirepass("x"))` sets any directive.

## 5.15 The runnable examples

`j-redis-examples/src/main/java/com/jredis/examples/`, one class per topic:

| Name | Class | Shows |
|---|---|---|
| `quickstart` | `QuickStart` | connect, SET/GET, async vs. blocking calls |
| `strings` | `StringsAndCounters` | SET options, MSET/MGET, counters, TTLs |
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
| `errors` | `ErrorHandling` | server errors, guards, safe retries |

Run them from the distribution, or with `java -jar j-redis-examples/target/j-redis-examples-1.0.0-all.jar`:

```bash
bin/j-redis-examples --list                  # names and summaries
bin/j-redis-examples                         # all, against an in-process server
bin/j-redis-examples leaderboard lock        # some of them
bin/j-redis-examples -p 6379 -a secret       # against a running server
```

Every example uses its own key prefix and cleans up first, so it can be run
against a shared development server.
