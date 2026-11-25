# 05 — Commands

This page lists every command j-redis supports, and the commands it does not.
It is for engineers who implement or review a command, and for anyone who needs
to know how j-redis differs from Redis. For each command it gives the milestone
that built it, what the command writes to the append-only file (AOF), and how it
differs from Redis 7.2. The stream commands have their own page,
[15 §3](15-streams.md#3-commands).

## 1. How to read this document

- **Semantics.** For every command listed here, the Redis 7.2 command
  documentation is the specification: arguments, replies, edge cases and error
  prefixes ([01 §7](01-requirements-and-scope.md#7-compatibility-target)).
  This page records only what that reference does not tell an implementer: the
  milestone, how the command is written to the AOF, and where j-redis differs.
- **M** is the milestone that implemented the command
  ([13](13-implementation-plan.md)).
- **AOF effect** is what the command's `propagate` call writes to the AOF
  ([08 §3](08-persistence.md#3-logging-effects-not-commands)). The same bytes
  also go to the replicas ([16](16-replication.md)).
  - "*same*" means the command is written as it was sent. That is safe because
    the command is deterministic: given the state before it, it always has the
    same result.
  - "—" means the command only reads: nothing is written.
  - A write command that changed nothing writes nothing.
- `abs` is an absolute Unix time in milliseconds. The server computes it from
  the clock of the current batch of commands.

## 2. Supported commands

The tables below group the commands as the Redis documentation does. The 14
stream commands are listed in [15 §3](15-streams.md#3-commands) and summarised
under [Streams](#streams) below.

### Connection and server

| Command | M | AOF effect | Notes |
|---|---|---|---|
| `PING [msg]` | 1 | — | A client in subscriber mode gets a special reply ([03 §6](03-protocol.md#6-pubsub-frames)) |
| `ECHO msg` | 1 | — | |
| `QUIT` | 1 | — | Replies `+OK`, then closes the connection |
| `RESET` | 5 | — | Leaves `MULTI`, unwatches all keys, unsubscribes from everything, clears the client's name |
| `HELLO [2 [AUTH u p] [SETNAME n]]` | 1 | — | `HELLO 3` replies `NOPROTO`: j-redis speaks only RESP2. The `role` field is `primary` or `replica`, as `ROLE` says, where Redis says `master` (2.2.2) |
| `AUTH [user] password` | 1 | — | The password comparison takes constant time. If a user is given, it must be `default` |
| `SELECT index` | 1 | — | Only `0` is accepted; any other index replies `-ERR DB index is out of range` |
| `CLIENT ID\|SETNAME\|GETNAME\|SETINFO` | 1 | — | `SETINFO` is accepted and stored; the j-redis client uses it for its library name and version |
| `CLIENT LIST\|INFO\|KILL` | 7 | — | `KILL` takes the old form `KILL ip:port`, or the filters `ID`, `ADDR`, `LADDR`, `TYPE`, `USER` and `SKIPME`. `KILL` with no filter is refused, so it never closes every connection |
| `COMMAND [COUNT\|LIST\|INFO\|DOCS]` | 1 | — | `DOCS` returns an empty array |
| `TIME` | 2 | — | |
| `DBSIZE` | 2 | — | |
| `INFO [section]` | 2 → 7 | — | M2 built the `server` and `keyspace` sections; M7 built all the others ([11](11-operations-and-security.md#6-info)) |
| `CONFIG GET\|SET\|RESETSTAT` | 7 | — | `SET` works only for directives that are safe to change at run time ([11](11-operations-and-security.md#2-configuration)). `CONFIG REWRITE` is refused: edit the configuration file instead |
| `SLOWLOG GET\|LEN\|RESET` | 7 | — | |
| `SAVE` | 4 | — | Runs a rewrite synchronously, so it blocks every client. For operators only |
| `BGREWRITEAOF`, `BGSAVE` | 4 | — | Both start a background rewrite ([08](08-persistence.md)) |
| `LASTSAVE` | 4 | — | The time of the last completed rewrite |
| `SHUTDOWN [NOSAVE\|SAVE]` | 1 → 4 | — | Always forces the AOF to disk (fsync) first; `SAVE` also rewrites first |
| `DEBUG SLEEP s \| RELOAD \| DIGEST \| DIGEST-VALUE key… \| SET-ACTIVE-EXPIRE 0\|1` | 4 | — | Exists only with `enable-debug-command yes`; for tests. `DIGEST` (2.2) returns 40 hexadecimal characters. Two equal datasets give the same digest, whatever their order in memory; an empty dataset gives all zeros ([16 §12](16-replication.md#12-configuration-commands-info)). `SET-ACTIVE-EXPIRE 0` turns active expiry off and `1` turns it back on |
| `REPLICAOF host port \| NO ONE` | 10 | — | An admin command (2.2). `REPLICAOF host port` makes this server a replica of that primary; the server drops its own data at the full sync. `REPLICAOF NO ONE` makes it a primary again ([16](16-replication.md)) |
| `ROLE` | 10 | — | On a primary: `primary`, its offset and its replicas. On a replica: `replica`, its primary, the link's state and the offset it has applied. The epoch comes last ([16 §12](16-replication.md#12-configuration-commands-info)) |
| `PSYNC replid offset`, `REPLCONF listening-port p \| ACK offset` | 10 | — | Used only by the replication link ([16 §5.1](16-replication.md#51-the-handshake)). `PSYNC` replies `+CONTINUE` when the backlog still holds the history it names, and starts a full sync otherwise. A replica refuses `PSYNC` |
| `WAIT numreplicas timeout` | 10 | — | Waits until that many replicas have acknowledged every write so far, or until the timeout (in milliseconds; 0 means no timeout). Replies with the number of replicas ([16 §8](16-replication.md#8-acknowledgements-wait-and-refusing-writes-without-a-replica)) |

### Keys

| Command | M | AOF effect | Notes |
|---|---|---|---|
| `DEL key…`, `UNLINK key…` | 2 | `DEL` | `UNLINK` is the same as `DEL`. Removing a key is O(1), and the garbage collector (GC) frees the memory, so there is no blocking free to avoid |
| `EXISTS key…` | 2 | — | |
| `TYPE key` | 2 | — | |
| `EXPIRE` / `PEXPIRE` / `EXPIREAT` / `PEXPIREAT` `key t [NX\|XX\|GT\|LT]` | 2 | `PEXPIREAT key abs`; `DEL key` if `abs` is already past | |
| `TTL` / `PTTL` / `EXPIRETIME` / `PEXPIRETIME` | 2 | — | `-2` means no such key; `-1` means the key has no time to live (TTL) |
| `PERSIST key` | 2 | *same* | |
| `RENAME`, `RENAMENX` | 2 | `RENAME` | The TTL moves with the value |
| `COPY src dst [DB 0] [REPLACE]` | 2 | *same* | `DB` is accepted only as `DB 0`; any other database replies `-ERR DB index is out of range` |
| `SCAN cursor [MATCH p] [COUNT n] [TYPE t]` | 2 | — | [04 §3.5](04-data-structures.md#35-scan-the-reverse-binary-cursor) |
| `KEYS pattern` | 2 | — | O(n). Flagged `ADMIN`; the production sample configuration disables it |
| `RANDOMKEY` | 2 | — | |
| `TOUCH key…` | 2 | — | Returns the number of keys that exist. There is no LRU (least recently used) data to update |
| `FLUSHALL`, `FLUSHDB` `[ASYNC\|SYNC]` | 2 | `FLUSHALL` | O(1): the server swaps in an empty `Dict`, and the GC frees the old one. It aborts a running rewrite ([08 §5.5](08-persistence.md#55-flushall-during-a-rewrite)) and a running full sync to a replica, which then asks again |

### Strings

| Command | M | AOF effect | Notes |
|---|---|---|---|
| `GET`, `MGET` | 2 | — | |
| `SET key v [NX\|XX] [GET] [EX\|PX\|EXAT\|PXAT t\|KEEPTTL]` | 2 | `SET key v [PXAT abs \| KEEPTTL]` | The server has already decided the conditions, so the effect carries only the outcome |
| `SETNX` | 2 | `SET key v` | |
| `SETEX`, `PSETEX` | 2 | `SET key v PXAT abs` | |
| `GETSET` | 2 | `SET key v` | Clears the TTL, like `SET` |
| `GETDEL` | 2 | `DEL key` | |
| `GETEX key [EX\|PX\|EXAT\|PXAT t\|PERSIST]` | 2 | `PEXPIREAT key abs` or `PERSIST key` | |
| `MSET`, `MSETNX` | 2 | `MSET` | |
| `INCR`, `DECR`, `INCRBY`, `DECRBY` | 2 | *same* | An overflow replies `-ERR increment or decrement would overflow` |
| `INCRBYFLOAT` | 2 | `SET key <result> KEEPTTL` | The AOF holds the result, so a replay never repeats the floating-point arithmetic |
| `APPEND`, `SETRANGE` | 2 | *same* | The result can be at most `proto-max-bulk-len` long |
| `STRLEN`, `GETRANGE`, `SUBSTR` | 2 | — | |

### Hashes

| Command | M | AOF effect | Notes |
|---|---|---|---|
| `HSET`, `HMSET` | 3 | `HSET` | |
| `HSETNX` | 3 | `HSET key f v` | |
| `HDEL` | 3 | *same* | The server removes the key when the hash becomes empty |
| `HINCRBY` | 3 | *same* | |
| `HINCRBYFLOAT` | 3 | `HSET key f <result>` | |
| `HGET`, `HMGET`, `HGETALL`, `HKEYS`, `HVALS`, `HLEN`, `HEXISTS`, `HSTRLEN` | 3 | — | |
| `HSCAN`, `HRANDFIELD` | 3 | — | |

### Lists

| Command | M | AOF effect | Notes |
|---|---|---|---|
| `LPUSH`, `RPUSH`, `LPUSHX`, `RPUSHX` | 3 | *same* | Wakes blocked clients ([07](07-pubsub-blocking-transactions.md#2-blocking-operations)) |
| `LPOP`, `RPOP` `[count]` | 3 | *same* | |
| `LSET`, `LINSERT`, `LREM`, `LTRIM` | 3 | *same* | |
| `LMOVE src dst LEFT\|RIGHT LEFT\|RIGHT`, `RPOPLPUSH` | 3 | `LMOVE` | `RPOPLPUSH` is kept for compatibility with the earlier memstore design. Redis has deprecated it |
| `LLEN`, `LRANGE`, `LINDEX`, `LPOS` | 3 | — | |
| `BLPOP`, `BRPOP` `key… timeout` | 5 | `LPOP key` / `RPOP key` for the key served | |
| `BLMOVE src dst … timeout`, `BRPOPLPUSH` | 5 | `LMOVE src dst …` | |

### Sets

| Command | M | AOF effect | Notes |
|---|---|---|---|
| `SADD`, `SREM`, `SMOVE` | 3 | *same* | |
| `SPOP key [count]` | 3 | `SREM key <members actually popped>` | The pop is random, so the AOF records the members that were really removed |
| `SINTERSTORE`, `SUNIONSTORE`, `SDIFFSTORE` | 3 | *same* | |
| `SISMEMBER`, `SMISMEMBER`, `SMEMBERS`, `SCARD`, `SRANDMEMBER` | 3 | — | |
| `SINTER`, `SUNION`, `SDIFF`, `SINTERCARD`, `SSCAN` | 3 | — | |

### Sorted sets

| Command | M | AOF effect | Notes |
|---|---|---|---|
| `ZADD key [NX\|XX] [GT\|LT] [CH] [INCR] score member…` | 3 | *same* | `GT` replaces memstore's `ZADDMAX` ([09](09-integration-patterns.md#1-relation-to-the-memstore-design)) |
| `ZINCRBY` | 3 | *same* | IEEE 754 floating-point addition is deterministic, so a replay gives the same score |
| `ZREM`, `ZREMRANGEBYRANK`, `ZREMRANGEBYSCORE` | 3 | *same* | |
| `ZPOPMIN`, `ZPOPMAX` `[count]` | 3 | *same* | |
| `ZSCORE`, `ZMSCORE`, `ZCARD`, `ZCOUNT` | 3 | — | |
| `ZRANK`, `ZREVRANK` `[WITHSCORE]` | 3 | — | Ranks start at 0 |
| `ZRANGE key start stop [BYSCORE] [REV] [LIMIT o c] [WITHSCORES]` | 3 | — | `BYLEX` is refused with `-ERR BYLEX is not supported by this server`; it is an M8 candidate |
| `ZREVRANGE`, `ZRANGEBYSCORE`, `ZREVRANGEBYSCORE` | 3 | — | Older forms, kept because existing code uses them |
| `ZSCAN`, `ZRANDMEMBER` | 3 | — | |
| `BZPOPMIN`, `BZPOPMAX` `key… timeout` | 5 | `ZPOPMIN key` / `ZPOPMAX key` | |

### Streams

Release 2.1 added 14 stream commands: `XADD`, `XLEN`, `XRANGE`, `XREVRANGE`,
`XDEL`, `XTRIM`, `XREAD`, `XGROUP`, `XREADGROUP`, `XACK`, `XPENDING`, `XCLAIM`,
`XAUTOCLAIM` and `XINFO`. Their arguments, AOF effects and differences from
Redis are in [15 §3](15-streams.md#3-commands).

### Pub/Sub

| Command | M | AOF effect | Notes |
|---|---|---|---|
| `SUBSCRIBE`, `UNSUBSCRIBE`, `PSUBSCRIBE`, `PUNSUBSCRIBE` | 5 | — | |
| `PUBLISH channel message` | 5 | — | Messages are not data: the server never persists them |
| `PUBSUB CHANNELS [p] \| NUMSUB [ch…] \| NUMPAT` | 5 | — | |

### Transactions

| Command | M | AOF effect | Notes |
|---|---|---|---|
| `MULTI`, `EXEC` | 5 | `MULTI`, the effects, `EXEC`, but only if something changed | |
| `DISCARD`, `WATCH`, `UNWATCH` | 5 | — | |

Inside `MULTI`, the server refuses these commands with
`-ERR Command not allowed inside a transaction`: `AUTH`, `HELLO`, `SUBSCRIBE`,
`UNSUBSCRIBE`, `PSUBSCRIBE`, `PUNSUBSCRIBE`, `SAVE`, `BGSAVE`, `BGREWRITEAOF`,
`SHUTDOWN`, `DEBUG`, `REPLICAOF`, `PSYNC`, `REPLCONF` and `WAIT` (they carry the
`NO_MULTI` flag). `MULTI`, `EXEC`, `DISCARD`, `WATCH`, `QUIT` and `RESET` are
never queued: they act on the transaction itself (the `TX_CONTROL` flag,
[02](02-architecture.md)).

### Extensions

| Command | M | AOF effect | Notes |
|---|---|---|---|
| `J.CAS key expected new [EX s\|PX ms\|KEEPTTL]` | 2 | `SET key new [PXAT abs \| KEEPTTL]` on success | [09 §2](09-integration-patterns.md#2-extension-commands) |
| `J.CAD key expected` | 2 | `DEL key` on success | |
| `J.ZAROUND key member count [REV] [WITHSCORES]` | 3 | — | |

**Total: 170 distinct command names.** Version 2.0 had 151. Release 2.1 added
the 14 stream commands ([15](15-streams.md)). Release 2.2 added `REPLICAOF`,
`ROLE`, `PSYNC`, `REPLCONF` and `WAIT` ([16](16-replication.md)). The count
includes each alias (`HMSET`, `UNLINK`, `SUBSTR`…) separately. It counts each
command with subcommands (`CLIENT`, `CONFIG`…) once. It includes `DEBUG`, which
exists only with `enable-debug-command yes`.

## 3. Deviations from Redis 7.2

| Area | Redis 7.2 | j-redis-service |
|---|---|---|
| Databases | 16, chosen with `SELECT n` | Only database 0 |
| `HELLO 3` / RESP3 | Supported | `NOPROTO` |
| Bulk length limit | 512 MB | 64 MB by default (`proto-max-bulk-len`) |
| `UNLINK`, `FLUSHALL ASYNC` | Free the memory in the background | The same as the synchronous forms; the GC frees the memory |
| `LINDEX`, `LSET` | O(n) | O(1) |
| Expiry boundary | A key is expired once `now > expireAt` | A key is expired once `now >= expireAt`, so a key with a 10 s TTL is gone at exactly 10 s. The difference is at most one millisecond ([06 §2](06-expiry-and-memory.md#2-lazy-expiry)). |
| `SHUTDOWN` reply | None; the connection closes | The same |
| Disabled commands (`disable-command`) | `rename-command X ""` | A disabled command replies as an unknown command, as in Redis |
| Double formatting | The shortest text that reads back as the same number | Text that reads back as exactly the same number; sometimes one digit longer ([03 §7](03-protocol.md#7-number-formatting)) |
| `COMMAND DOCS` | Full documentation | An empty array |
| `CLIENT` | Many subcommands | `ID`, `SETNAME`, `GETNAME`, `SETINFO`, `LIST`, `INFO`, `KILL` |
| `DEBUG` | Many subcommands, always on | `SLEEP`, `RELOAD`, `DIGEST`, `DIGEST-VALUE`, `SET-ACTIVE-EXPIRE`; off by default. `DIGEST` replies with a bulk string, and leaves out a stream consumer's seen time |
| `BGSAVE` | Writes an RDB (Redis database) snapshot file | The same as `BGREWRITEAOF` |
| `WAIT` inside `MULTI` | Answered at once | Refused (`NO_MULTI`). A blocking pop inside `EXEC` does not block, as in Redis, but a `WAIT` there would have nothing to count yet |
| Error message text | Redis's wording | The same prefix; the wording may differ. The server cuts an error to 1024 characters, because an error may echo the client's input. |
| Inside `MULTI`: `AUTH`, `HELLO`, `(P)SUBSCRIBE`, `(P)UNSUBSCRIBE`, `BGSAVE`, `BGREWRITEAOF` | Queued | Refused with `-ERR Command not allowed inside a transaction`. A rewrite started inside `EXEC` would record the transaction's effects twice; the other commands change the connection's state. The full list of commands refused inside `MULTI` is under [Transactions](#transactions). |
| Which error wins, in a few malformed calls | For example, `SRANDMEMBER str abc` gives not-an-integer, `ZPOPMIN str 0` an empty array, `HSCAN missing 0 BOGUS x` an empty scan, and `EXPIRE k abc FOO` "Unsupported option" | j-redis reports another error of the same call first (WRONGTYPE, a syntax error or not-an-integer). Valid calls behave the same as in Redis. |
| `ZRANGE … BYSCORE BYSCORE` | Syntax error | Accepted |
| `COMMAND INFO` key positions | The real first key, last key and step | Always 0, 0, 0 (no key specifications) |
| `INCRBYFLOAT` arithmetic | `long double` | Exact decimal arithmetic, with the same text form ([03 §7](03-protocol.md#7-number-formatting)) |

## 4. Not implemented

These commands reply `-ERR unknown command`:

| Group | Commands | Reason |
|---|---|---|
| Scripting | `EVAL`, `EVALSHA`, `SCRIPT`, `FUNCTION`, `FCALL` | No Lua (D-7) |
| HyperLogLog, Geo, Bitmaps | `PF*`, `GEO*`, `SETBIT`, `GETBIT`, `BITCOUNT`, `BITOP`, `BITFIELD`, `BITPOS` | The backend does not need them |
| Streams: two of them | `XSETID`, `XINFO STREAM FULL` | The 2.1 stream commands are built ([15 §3](15-streams.md#3-commands)); the backend has no use for these two |
| Cluster, and the rest of replication | `SYNC`, `WAITAOF`, `CLUSTER`, `READONLY`, `SSUBSCRIBE`, `SPUBLISH` | There is no cluster. Replication since 2.2 uses `REPLICAOF`, `PSYNC` and `WAIT` ([16](16-replication.md)) |
| Databases | `MOVE`, `SWAPDB` | There is one database |
| Serialisation | `DUMP`, `RESTORE`, `MIGRATE` | Not needed |
| Introspection | `OBJECT`, `MEMORY`, `LATENCY`, `MONITOR`, `MODULE`, `ACL` | `MONITOR` is possible in M8 |
| Other | `SORT`, `LCS`, `LMPOP`, `ZMPOP`, the `HEXPIRE` family | Not needed |
| M8 candidates | `ZUNIONSTORE`, `ZINTERSTORE`, `ZDIFF*`, `ZRANGESTORE`, `ZRANGEBYLEX` and the rest of the lex family | Not needed yet |

`XINFO STREAM FULL` is a form of `XINFO`, which j-redis has, so it is the one
exception: it replies `-ERR wrong number of arguments for 'xinfo|stream'
command`, not "unknown command". Milestone M8 is optional and has not started
([13](13-implementation-plan.md)).
