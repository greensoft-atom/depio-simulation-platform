# 09 — Integration patterns

How backend services use j-redis-service: the extension commands, tested
recipes for common needs, key-naming rules, and how many connections a service
should open. Runnable versions of most recipes are in the `j-redis-examples`
module ([guide/05-client-guide.md](guide/05-client-guide.md)).

## 1. Relation to the memstore design

The earlier backend design (doc 04, "memstore") defined seven custom commands.
With `MULTI`/`EXEC` and a few standard command options, **four of them
disappear**. Three remain as `J.`-prefixed extensions, because standard Redis
cannot do them atomically in one round trip without Lua (D-8).

| memstore | j-redis-service |
|---|---|
| `CLAIM k`: read a hash and delete it atomically | `MULTI` · `HGETALL k` · `DEL k` · `EXEC` (one round trip, pipelined) |
| `ZADDMAX k score m`: raise the score only if higher | `ZADD k GT score m` (standard since Redis 6.2) |
| `HSETEXNX k sec f v…`: create a session hash with a TTL | `SET sess:{token} {userId} NX EX 86400`, since a session only needs the user id |
| `LPUSHCAP k cap v`: push and trim | `MULTI` · `LPUSH k v` · `LTRIM k 0 cap-1` · `EXEC` |
| `INCREX k sec`: a counter with a TTL on first use | `MULTI` · `INCR k` · `EXPIRE k sec NX` · `EXEC` (`NX`: only if it has no TTL yet) |
| `CAS k expected new` | **`J.CAS`** |
| `ZAROUND k member count` | **`J.ZAROUND`** |
| (lock release needed Lua) | **`J.CAD`** (new) |
| `BRPOPLPUSH src dst t` | Still supported; prefer `BLMOVE src dst RIGHT LEFT t` |
| Port 6400 | **6379** by default; set `port 6400` to keep the old value |
| Custom binary protocol with request ids | RESP2, with replies correlated by order |
| AOF plus a separate sliced snapshot | Multi-part AOF with per-key copy-on-write ([08](08-persistence.md)) |
| Slow pub/sub subscribers: messages dropped | Slow subscribers are **disconnected** ([07 §1.5](07-pubsub-blocking-transactions.md#15-slow-subscribers)) |

## 2. Extension commands

All three live in the `J.` namespace, so they can never collide with a real
Redis command name.

### `J.ZAROUND key member count [REV] [WITHSCORES]`

A member's rank plus its neighbours in one call: "my position in the ranking".
Doing this with standard commands needs `ZRANK` first, then a `ZRANGE` whose
bounds depend on that result, which `MULTI` cannot express.

| | |
|---|---|
| Returns | `*2`: the member's 0-based rank in the chosen order, then an array of up to `count` members before it, the member itself, and up to `count` after it, clipped at the ends. With `WITHSCORES`, each member is followed by its score. |
| Missing key or member | `*-1` |
| Errors | `WRONGTYPE`; `-ERR count must be between 0 and 1000` |
| Order | Ascending score; `REV` for descending (rankings) |
| Complexity | O(log n + count) |
| AOF | Read-only, so nothing is logged |

```
> J.ZAROUND lb:weekly:points user:42 2 REV WITHSCORES
1) (integer) 17
2) 1) "user:39"    2) "950"
   3) "user:40"    4) "940"
   5) "user:42"    6) "930"
   7) "user:44"    8) "925"
   9) "user:45"   10) "920"
```

The first row's rank is 17 minus the number of members shown before `user:42`,
so the client can label every row. For display, add 1 to the 0-based rank. The
client library does this arithmetic for you: `ZAround.firstRank`.

### `J.CAS key expected new [EX s | PX ms | KEEPTTL]`

Compare-and-set a string.

| | |
|---|---|
| Returns | `:1` if the key held exactly `expected` (byte for byte) and now holds `new`; `:0` otherwise. A missing key returns `:0` and is not created. |
| TTL | Like `SET`: removed unless `EX`/`PX` sets a new one or `KEEPTTL` keeps it |
| Errors | `WRONGTYPE` if the key is not a string |
| Complexity | O(length of value) |
| AOF | On success: `SET key new`, with `PXAT abs` or `KEEPTTL` as applicable |

### `J.CAD key expected`

Compare-and-delete: the safe way to release a lock.

| | |
|---|---|
| Returns | `:1` if the key held exactly `expected` and was deleted; `:0` otherwise |
| Errors | `WRONGTYPE` if the key is not a string |
| AOF | On success: `DEL key` |

Without it, releasing a lock you may no longer own needs a Lua script, because
`GET` followed by `DEL` can delete a lock that someone else acquired in between.

## 3. Recipes

Each block is sent **pipelined as one write**, so it costs one round trip.
`MULTI` blocks are atomic.

### Sessions and presence

```
SET sess:{token} {userId} NX EX 86400             create (NX: never overwrite)
GET sess:{token}                                  resolve
EXPIRE sess:{token} 86400                         refresh, only when the TTL is below 12 h (see §4)
DEL sess:{token}                                  log out
SET presence:{userId} {instanceId} EX 60          presence, refreshed by a heartbeat
```

### One-time tokens (hand-off between services)

A token that one service creates and exactly one other service may redeem: a
sign-in link, a checkout hand-off, a connection ticket.

```
# issuer: atomic, so a token never exists without its TTL
MULTI
HSET token:{id} userId 42 target checkout-7 payload {json}
EXPIRE token:{id} 60
EXEC

# redeemer: claim exactly once
MULTI
HGETALL token:{id}
DEL token:{id}
EXEC
→ [ [fields…], 1 ]   valid, and now consumed
→ [ [], 0 ]          unknown, expired or already claimed
```

Two redeemers racing for one token cannot both succeed. The transactions run
one after the other, and the second one sees an empty hash.

### Service registry

A sorted set scored by the last heartbeat is a self-cleaning liveness index:

```
# each service instance, every 3 s
MULTI
HSET instance:{name} host 10.0.0.5 port 9001 load 412
EXPIRE instance:{name} 10
ZADD instances {nowMillis} {name}
EXEC

# a caller choosing an instance
ZREMRANGEBYSCORE instances -inf ({nowMillis - 10000}   drop the dead ones
ZRANGE instances 0 -1                                  the live ones
```

### Reliable work queue

```
# producer
LPUSH q:jobs {json}

# consumer, on its dedicated blocking connection
BLMOVE q:jobs q:jobs:processing RIGHT LEFT 5
    → process it and commit to the database (idempotent by job id)
LREM q:jobs:processing 1 {json}

# consumer start-up: anything left in processing was never confirmed, so process it again
LRANGE q:jobs:processing 0 -1
```

An item leaves `processing` only after its database commit, so a consumer
crash at any point loses nothing. Reprocessing is safe as long as processing is
idempotent, for example keyed by the job id.

### Leaderboards and rankings

```
ZADD lb:{period}:best GT {score} {userId}                  best score
ZINCRBY lb:{period}:points {n} {userId}                    running total
ZRANGE lb:{period}:points 0 99 REV WITHSCORES              top 100
ZREVRANK lb:{period}:points {userId}                       my rank
J.ZAROUND lb:{period}:points {userId} 5 REV WITHSCORES     the rows around me

# a daily board: give it a TTL the first time it is written
MULTI
ZINCRBY lb:daily:{yyyymmdd}:points {n} {userId}
EXPIRE lb:daily:{yyyymmdd}:points 259200 NX
EXEC
```

### Waiting queue, oldest first

```
ZADD waitq:{kind} NX {enqueuedAtMillis} {requestId}    enqueue, oldest first
ZRANGE waitq:{kind} 0 99 WITHSCORES                     the scheduler reads the head
ZREM waitq:{kind} {requestId}…                          remove the ones it handled
```

With a single scheduler thread, read-then-remove has no race. If several
schedulers run, wrap the sequence in `WATCH`/`MULTI`/`EXEC`.

### Rate limiting (fixed window)

```
MULTI
INCR rl:{userId}:{windowStartSeconds}
EXPIRE rl:{userId}:{windowStartSeconds} 2 NX
EXEC
→ reject the request if the count exceeds the limit
```

### Locks

```
SET lock:{name} {randomToken} NX PX 30000                acquire
J.CAS lock:{name} {token} {token} PX 30000               renew, only if still ours
J.CAD lock:{name} {token}                                release, only if still ours
```

### Limited stock

```
DECR stock:{sku}          → if the result is < 0: INCR stock:{sku} and reject
```

### Pub/Sub channels

| Channel | Publisher → subscriber |
|---|---|
| `user:{id}` | any service → the connection serving that user |
| `group:{id}` | any service → every member's connection |
| `instance:{name}:cmd` | a coordinator → one service instance (reload, drain, notice) |
| `coordinator:replies` | service instances → the coordinator |

A group feed, broadcast with a bounded history:

```
MULTI
PUBLISH group:{id} {msg}
LPUSH feed:{id} {msg}
LTRIM feed:{id} 0 199
EXEC
```

## 4. Key naming and size guidelines

| Rule | Why |
|---|---|
| `entity:id[:sub]`, lower case, `:` separators | Readable in `SCAN`, and groupable with `MATCH` |
| **Every transient key has a TTL**: sessions, presence, tokens, rate-limit windows, daily boards | Nothing leaks if a process dies mid-flow |
| Refresh a TTL only when less than half of it remains | Halves the write and AOF volume for hot keys |
| Keys ≤ 128 bytes; values ≤ 64 KB | Small values keep every command fast |
| Collections ≤ about 100,000 elements when a stall of a few tens of milliseconds per rewrite matters | The big-key caveat ([08 §6](08-persistence.md#6-the-big-key-caveat)) |
| No `KEYS` in application code | It is O(n); use `SCAN` or a real index |

Keys that are permanent (no TTL) should be deliberate: long-lived rankings,
queues, registry sorted sets and stock counters.

## 5. Connections per process

| Process | Connections | Use |
|---|---|---|
| Latency-critical service | 1 command connection (async, pipelined) | tokens, heartbeats, results |
| Latency-critical service | 1 pub/sub connection | `instance:{name}:cmd` |
| Coordinator / API service | 2 command connections | request traffic; two stop one slow reply from delaying the rest |
| Coordinator / API service | 1 blocking connection | `BLMOVE` on a work queue |
| Coordinator / API service | 1 pub/sub connection | user and group channels |
| Any | leased connections on demand | `WATCH` transactions ([10 §7](10-client-library.md#7-transactions)) |

A typical deployment needs a few dozen connections at most, while `maxclients`
defaults to 1,000.
