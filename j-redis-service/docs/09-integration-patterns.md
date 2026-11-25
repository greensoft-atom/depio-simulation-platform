# 09 — Integration patterns

This page shows how backend services use j-redis. It is for application
developers who write code against j-redis, and for reviewers of that code. It
covers how j-redis replaces the earlier "memstore" design, the three extension
commands, tested recipes for common needs, rules for key names and sizes, and
how many connections a service should open. The `j-redis-examples` module has
runnable versions of most recipes
([guide/05-client-guide.md](guide/05-client-guide.md)).

## 1. Relation to the memstore design

The earlier backend design (doc 04, "memstore") defined seven custom commands.
With `MULTI`/`EXEC` and a few options of standard commands, **four of them are
no longer needed**. Three remain, as extensions with the `J.` prefix: standard
Redis cannot do them atomically in one round trip without Lua scripts (D-8).

| memstore | j-redis-service |
|---|---|
| `CLAIM k`: read a hash and delete it atomically | `MULTI` · `HGETALL k` · `DEL k` · `EXEC` (one round trip, pipelined) |
| `ZADDMAX k score m`: raise the score only if the new one is higher | `ZADD k GT score m` (standard since Redis 6.2) |
| `HSETEXNX k sec f v…`: create a session hash with a TTL (time to live) | `SET sess:{token} {userId} NX EX 86400`, because a session needs only the user id |
| `LPUSHCAP k cap v`: push, then trim to a maximum length | `MULTI` · `LPUSH k v` · `LTRIM k 0 cap-1` · `EXEC` |
| `INCREX k sec`: a counter that gets a TTL when it is first used | `MULTI` · `INCR k` · `EXPIRE k sec NX` · `EXEC` (`NX`: only if the key has no TTL yet) |
| `CAS k expected new` | **`J.CAS`** |
| `ZAROUND k member count` | **`J.ZAROUND`** |
| (releasing a lock needed Lua) | **`J.CAD`** (new) |
| `BRPOPLPUSH src dst t` | Still supported, but prefer `BLMOVE src dst RIGHT LEFT t` |
| Port 6400 | **6379** by default; set `port 6400` to keep the old value |
| A custom binary protocol with request ids | RESP2 (the Redis protocol), with replies matched to requests by their order |
| An AOF plus a separate sliced snapshot | A multi-part AOF (append-only file) with copy-on-write for each key ([08](08-persistence.md)) |
| Slow pub/sub subscribers: their messages were dropped | Slow subscribers are **disconnected** ([07 §1.5](07-pubsub-blocking-transactions.md#15-slow-subscribers)) |

## 2. Extension commands

All three extension commands use the `J.` namespace, so their names can never
collide with a real Redis command.

### `J.ZAROUND key member count [REV] [WITHSCORES]`

`J.ZAROUND` returns a member's rank and its neighbours in one call: "my
position in the ranking". With standard commands, this needs `ZRANK` first,
then a `ZRANGE` whose bounds depend on that result. `MULTI` cannot express a
command that depends on an earlier result.

| | |
|---|---|
| Returns | `*2`, an array of two items. The first is the member's rank in the chosen order, counted from 0. The second is an array: up to `count` members before it, the member itself, and up to `count` members after it, cut off at the ends of the set. With `WITHSCORES`, each member is followed by its score. |
| Missing key or member | `*-1` (a null array) |
| Errors | `WRONGTYPE` if the key is not a sorted set; `-ERR count must be between 0 and 1000` |
| Order | Ascending score; `REV` gives descending score, which rankings use |
| Complexity | O(log n + count) |
| AOF | Nothing: the command only reads |

```
> J.ZAROUND lb:weekly:points user:42 2 REV WITHSCORES
1) (integer) 17
2) 1) "user:39"    2) "950"
   3) "user:40"    4) "940"
   5) "user:42"    6) "930"
   7) "user:44"    8) "925"
   9) "user:45"   10) "920"
```

To label every row, the client computes the first row's rank: the member's rank
minus the number of members shown before it. Here that is 17 − 2 = 15, so
`user:39` has rank 15. For display, add 1 to the rank, which starts at 0. The
client library does this arithmetic for you: `zaround(...)` returns a `ZAround`
whose `firstRank` field holds it.

### `J.CAS key expected new [EX s | PX ms | KEEPTTL]`

`J.CAS` is compare-and-set for a string.

| | |
|---|---|
| Returns | `:1` if the key held exactly `expected` (byte for byte) and now holds `new`; `:0` otherwise. A missing key returns `:0`, and the server does not create it. |
| TTL | As with `SET`, the TTL is removed, unless `EX` or `PX` sets a new one or `KEEPTTL` keeps it. The server also accepts `EXAT` and `PXAT` (absolute times), as `SET` does. It does not accept `NX`, `XX` or `GET`. |
| Errors | `WRONGTYPE` if the key is not a string |
| Complexity | O(length of the value) |
| AOF | On success: `SET key new`, with `PXAT abs` or `KEEPTTL` as applicable |

### `J.CAD key expected`

`J.CAD` is compare-and-delete: the safe way to release a lock.

| | |
|---|---|
| Returns | `:1` if the key held exactly `expected` and the server deleted it; `:0` otherwise |
| Errors | `WRONGTYPE` if the key is not a string |
| AOF | On success: `DEL key` |

Without `J.CAD`, releasing a lock that you may no longer own needs a Lua
script. A `GET` followed by a `DEL` can delete a lock that someone else took in
between.

## 3. Recipes

Send each block below **pipelined, as one write**, so that it costs one round
trip. A block that starts with `MULTI` is atomic.

### Sessions and presence

```
SET sess:{token} {userId} NX EX 86400             create (NX: never overwrite)
GET sess:{token}                                  resolve
EXPIRE sess:{token} 86400                         refresh, only when the TTL is below 12 h (see §4)
DEL sess:{token}                                  log out
SET presence:{userId} {instanceId} EX 60          presence, refreshed by a heartbeat
```

### One-time tokens (hand-off between services)

A one-time token is created by one service and redeemed by exactly one other
service. Examples: a sign-in link, a checkout hand-off, a connection ticket.

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

Two redeemers racing for one token cannot both succeed. The two transactions
run one after the other, and the second one sees an empty hash.

### Service registry

A sorted set scored by the time of the last heartbeat is an index of the live
instances that cleans itself:

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
crash at any point loses nothing. Processing an item again is safe when the
processing is idempotent, that is, when doing it twice has the same result as
doing it once; for example, when it is keyed by the job id.

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

With a single scheduler thread, reading and then removing has no race. If
several schedulers run, wrap the sequence in `WATCH`/`MULTI`/`EXEC`.

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
| `user:{id}` | Any service → the connection that serves that user |
| `group:{id}` | Any service → the connection of every member |
| `instance:{name}:cmd` | A coordinator → one service instance (reload, drain, notice) |
| `coordinator:replies` | Service instances → the coordinator |

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
| `entity:id[:sub]`, in lower case, with `:` as the separator | Readable in `SCAN`, and easy to group with `MATCH` |
| **Every transient key has a TTL**: sessions, presence, tokens, rate-limit windows, daily boards | Nothing leaks if a process dies in the middle of a flow |
| Refresh a TTL only when less than half of it remains | Halves the writes, and the AOF volume, for keys that are refreshed often |
| Keys of at most 128 bytes; values of at most 64 KB | Small values keep every command fast |
| Collections of at most about 100,000 elements, when a stall of a few tens of milliseconds per rewrite matters | The big-key caveat ([08 §6](08-persistence.md#6-the-big-key-caveat)) |
| No `KEYS` in application code | It is O(n); use `SCAN` or a real index |

A permanent key (one with no TTL) should be a deliberate choice: long-lived
rankings, queues, registry sorted sets and stock counters.

## 5. Connections per process

| Process | Connections | Use |
|---|---|---|
| Latency-critical service | 1 command connection (asynchronous, pipelined) | Tokens, heartbeats, results |
| Latency-critical service | 1 pub/sub connection | `instance:{name}:cmd` |
| Coordinator or API service | 2 command connections | Request traffic; with two, one slow reply cannot delay all the rest |
| Coordinator or API service | 1 blocking connection | `BLMOVE` on a work queue |
| Coordinator or API service | 1 pub/sub connection | User and group channels |
| Any | Leased connections, on demand | `WATCH` transactions ([10 §7](10-client-library.md#7-transactions)) |

A typical deployment needs a few dozen connections at most. `maxclients`
allows 1,000 by default.
