# 05 — Commands

## 1. How to read this document

- **Semantics:** for every command listed, the Redis 7.2 command documentation
  is the specification — arguments, replies, edge cases, error prefixes
  ([01 §7](01-requirements-and-scope.md#7-compatibility-target)). This document
  records only what that reference does not tell an implementer: the milestone,
  how the command is written to the AOF, and our deviations.
- **M:** milestone that implements it ([13](13-implementation-plan.md)).
- **AOF effect:** what `propagate` writes ([08 §3](08-persistence.md#3-logging-effects-not-commands)).
  "*same*" means the command itself, which is safe because it is deterministic
  given the state before it. "—" means read-only: nothing is written. Every
  write command logs nothing when it changed nothing.
- `abs` = absolute Unix time in milliseconds, computed from the batch clock.

## 2. Supported commands

### Connection and server

| Command | M | AOF effect | Notes |
|---|---|---|---|
| `PING [msg]` | 1 | — | Special reply in subscriber mode ([03 §6](03-protocol.md#6-pubsub-frames)) |
| `ECHO msg` | 1 | — | |
| `QUIT` | 1 | — | Reply `+OK`, then close |
| `RESET` | 5 | — | Leaves `MULTI`, unwatches, unsubscribes, clears name |
| `HELLO [2 [AUTH u p] [SETNAME n]]` | 1 | — | `HELLO 3` → `NOPROTO` |
| `AUTH [user] password` | 1 | — | Constant-time comparison; user must be `default` if given |
| `SELECT index` | 1 | — | Only `0`; else `-ERR DB index is out of range` |
| `CLIENT ID\|SETNAME\|GETNAME\|SETINFO` | 1 | — | `SETINFO` accepted and stored, for our client's library name/version |
| `CLIENT LIST\|INFO\|KILL` | 7 | — | `KILL ID n` and `KILL ADDR ip:port` |
| `COMMAND [COUNT\|INFO\|DOCS]` | 1 | — | `DOCS` returns an empty array |
| `TIME` | 2 | — | |
| `DBSIZE` | 2 | — | |
| `INFO [section]` | 2 → 7 | — | M2: `server`, `keyspace`; M7: all sections ([11](11-operations-and-security.md#6-info)) |
| `CONFIG GET\|SET\|RESETSTAT` | 7 | — | `SET` only for runtime-safe directives ([11](11-operations-and-security.md#2-configuration)) |
| `SLOWLOG GET\|LEN\|RESET` | 7 | — | |
| `SAVE` | 4 | — | Synchronous rewrite; blocks everyone — operator use only |
| `BGREWRITEAOF`, `BGSAVE` | 4 | — | Both start a background rewrite ([08](08-persistence.md)) |
| `LASTSAVE` | 4 | — | Time of the last completed rewrite |
| `SHUTDOWN [NOSAVE\|SAVE]` | 1 → 4 | — | Always fsyncs the AOF; `SAVE` also rewrites first |
| `DEBUG SLEEP s \| RELOAD \| DIGEST \| DIGEST-VALUE key…` | 4 | — | Only if `enable-debug-command yes`; for tests. `DIGEST` (2.2): 40 hex characters, equal for equal datasets whatever their order in memory, all zeros when empty ([16 §12](16-replication.md#12-configuration-commands-info)) |
| `REPLICAOF host port \| NO ONE` | 10 | — | Admin (2.2): makes this server a replica of that primary, dropping its own data at the full sync, or a primary again ([16](16-replication.md)) |
| `ROLE` | 10 | — | `primary` with its offset and replicas, or `replica` with its primary, link state and applied offset; the epoch last ([16 §12](16-replication.md#12-configuration-commands-info)) |
| `PSYNC replid offset`, `REPLCONF listening-port p \| ACK offset` | 10 | — | The replication link's own ([16 §5.1](16-replication.md#51-the-handshake)): `PSYNC` answers `+CONTINUE` when the backlog holds the history it names, else a full sync; a replica refuses it |
| `WAIT numreplicas timeout` | 10 | — | Until that many replicas acknowledged the stream so far, or the timeout (ms, 0 none); the count ([16 §8](16-replication.md#8-acknowledgements-wait-and-refusing-writes-without-a-replica)) |

### Keys

| Command | M | AOF effect | Notes |
|---|---|---|---|
| `DEL key…`, `UNLINK key…` | 2 | `DEL` | `UNLINK` = `DEL`: removal is O(1) and freeing is the GC's job, so there is no blocking free to avoid |
| `EXISTS key…` | 2 | — | |
| `TYPE key` | 2 | — | |
| `EXPIRE` / `PEXPIRE` / `EXPIREAT` / `PEXPIREAT` `key t [NX\|XX\|GT\|LT]` | 2 | `PEXPIREAT key abs`; `DEL key` if `abs` is already past | |
| `TTL` / `PTTL` / `EXPIRETIME` / `PEXPIRETIME` | 2 | — | `-2` no key, `-1` no TTL |
| `PERSIST key` | 2 | *same* | |
| `RENAME`, `RENAMENX` | 2 | `RENAME` | TTL moves with the value |
| `COPY src dst [REPLACE]` | 2 | *same* | `DB` option rejected |
| `SCAN cursor [MATCH p] [COUNT n] [TYPE t]` | 2 | — | [04 §3.5](04-data-structures.md#35-scan-the-reverse-binary-cursor) |
| `KEYS pattern` | 2 | — | O(n); flagged `ADMIN`, disabled in the production sample config |
| `RANDOMKEY` | 2 | — | |
| `TOUCH key…` | 2 | — | Returns the count; no LRU to update |
| `FLUSHALL`, `FLUSHDB` `[ASYNC\|SYNC]` | 2 | `FLUSHALL` | O(1): swap in an empty `Dict`, the GC frees the old one. Aborts a running rewrite ([08 §5.5](08-persistence.md#55-flushall-during-a-rewrite)). |

### Strings

| Command | M | AOF effect | Notes |
|---|---|---|---|
| `GET`, `MGET` | 2 | — | |
| `SET key v [NX\|XX] [GET] [EX\|PX\|EXAT\|PXAT t\|KEEPTTL]` | 2 | `SET key v [PXAT abs \| KEEPTTL]` | Conditions are already decided, so the effect carries only the outcome |
| `SETNX` | 2 | `SET key v` | |
| `SETEX`, `PSETEX` | 2 | `SET key v PXAT abs` | |
| `GETSET` | 2 | `SET key v` | Clears TTL, like `SET` |
| `GETDEL` | 2 | `DEL key` | |
| `GETEX key [EX\|PX\|EXAT\|PXAT t\|PERSIST]` | 2 | `PEXPIREAT key abs` or `PERSIST key` | |
| `MSET`, `MSETNX` | 2 | `MSET` | |
| `INCR`, `DECR`, `INCRBY`, `DECRBY` | 2 | *same* | Overflow → `-ERR increment or decrement would overflow` |
| `INCRBYFLOAT` | 2 | `SET key <result> KEEPTTL` | Logs the result, so replay never re-does float arithmetic |
| `APPEND`, `SETRANGE` | 2 | *same* | Result length ≤ `proto-max-bulk-len` |
| `STRLEN`, `GETRANGE`, `SUBSTR` | 2 | — | |

### Hashes

| Command | M | AOF effect | Notes |
|---|---|---|---|
| `HSET`, `HMSET` | 3 | `HSET` | |
| `HSETNX` | 3 | `HSET key f v` | |
| `HDEL` | 3 | *same* | Key removed when the hash becomes empty |
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
| `LMOVE src dst LEFT\|RIGHT LEFT\|RIGHT`, `RPOPLPUSH` | 3 | `LMOVE` | `RPOPLPUSH` kept for compatibility with the earlier memstore design; it is deprecated in Redis |
| `LLEN`, `LRANGE`, `LINDEX`, `LPOS` | 3 | — | |
| `BLPOP`, `BRPOP` `key… timeout` | 5 | `LPOP key` / `RPOP key` for the key served | |
| `BLMOVE src dst … timeout`, `BRPOPLPUSH` | 5 | `LMOVE src dst …` | |

### Sets

| Command | M | AOF effect | Notes |
|---|---|---|---|
| `SADD`, `SREM`, `SMOVE` | 3 | *same* | |
| `SPOP key [count]` | 3 | `SREM key <members actually popped>` | Random, so the concrete result is logged |
| `SINTERSTORE`, `SUNIONSTORE`, `SDIFFSTORE` | 3 | *same* | |
| `SISMEMBER`, `SMISMEMBER`, `SMEMBERS`, `SCARD`, `SRANDMEMBER` | 3 | — | |
| `SINTER`, `SUNION`, `SDIFF`, `SINTERCARD`, `SSCAN` | 3 | — | |

### Sorted sets

| Command | M | AOF effect | Notes |
|---|---|---|---|
| `ZADD key [NX\|XX] [GT\|LT] [CH] [INCR] score member…` | 3 | *same* | `GT` replaces memstore's `ZADDMAX` ([09](09-integration-patterns.md#1-relation-to-the-memstore-design)) |
| `ZINCRBY` | 3 | *same* | IEEE addition is deterministic |
| `ZREM`, `ZREMRANGEBYRANK`, `ZREMRANGEBYSCORE` | 3 | *same* | |
| `ZPOPMIN`, `ZPOPMAX` `[count]` | 3 | *same* | |
| `ZSCORE`, `ZMSCORE`, `ZCARD`, `ZCOUNT` | 3 | — | |
| `ZRANK`, `ZREVRANK` `[WITHSCORE]` | 3 | — | 0-based |
| `ZRANGE key start stop [BYSCORE] [REV] [LIMIT o c] [WITHSCORES]` | 3 | — | `BYLEX` in M8 |
| `ZREVRANGE`, `ZRANGEBYSCORE`, `ZREVRANGEBYSCORE` | 3 | — | Older forms, kept because existing code uses them |
| `ZSCAN`, `ZRANDMEMBER` | 3 | — | |
| `BZPOPMIN`, `BZPOPMAX` `key… timeout` | 5 | `ZPOPMIN key` / `ZPOPMAX key` | |

### Pub/Sub

| Command | M | AOF effect | Notes |
|---|---|---|---|
| `SUBSCRIBE`, `UNSUBSCRIBE`, `PSUBSCRIBE`, `PUNSUBSCRIBE` | 5 | — | |
| `PUBLISH channel message` | 5 | — | Messages are not data; never persisted |
| `PUBSUB CHANNELS [p] \| NUMSUB [ch…] \| NUMPAT` | 5 | — | |

### Transactions

| Command | M | AOF effect | Notes |
|---|---|---|---|
| `MULTI`, `EXEC` | 5 | `MULTI`, the effects, `EXEC` — only if something changed | |
| `DISCARD`, `WATCH`, `UNWATCH` | 5 | — | |

### Extensions

| Command | M | AOF effect | Notes |
|---|---|---|---|
| `J.CAS key expected new [EX s\|PX ms\|KEEPTTL]` | 2 | `SET key new [PXAT abs \| KEEPTTL]` on success | [09 §2](09-integration-patterns.md#2-extension-commands) |
| `J.CAD key expected` | 2 | `DEL key` on success | |
| `J.ZAROUND key member count [REV] [WITHSCORES]` | 3 | — | |

**Total: 170 distinct command names** (151 in 2.0; 2.1 added the 14 stream commands, [15](15-streams.md); 2.2 adds `REPLICAOF`, `ROLE`, `PSYNC`, `REPLCONF` and `WAIT`, [16](16-replication.md)), counting aliases (`HMSET`, `UNLINK`, `SUBSTR`…) separately and each multi-subcommand command (`CLIENT`, `CONFIG`…) once.

## 3. Deviations from Redis 7.2

| Area | Redis 7.2 | j-redis-service |
|---|---|---|
| Databases | 16, `SELECT n` | Only 0 |
| `HELLO 3` / RESP3 | Supported | `NOPROTO` |
| Bulk length limit | 512 MB | 64 MB default |
| `UNLINK`, `FLUSHALL ASYNC` | Background freeing | Same as synchronous; the GC frees |
| `LINDEX`, `LSET` | O(n) | O(1) |
| Expiry boundary | A key is expired once `now > expireAt` | Expired once `now >= expireAt`, so a key with a 10 s TTL is gone at exactly 10 s. This differs by one millisecond at most ([06 §2](06-expiry-and-memory.md#2-lazy-expiry)). |
| `SHUTDOWN` reply | None; the connection closes | Same |
| Disabled commands (`disable-command`) | `rename-command X ""` | Reported as unknown commands, as in Redis |
| Double formatting | shortest round-trip | round-trip exact; occasionally one extra digit ([03 §7](03-protocol.md#7-number-formatting)) |
| `COMMAND DOCS` | Full docs | Empty array |
| `CLIENT` | Many subcommands | `ID`, `SETNAME`, `GETNAME`, `SETINFO`, `LIST`, `INFO`, `KILL` |
| `DEBUG` | Many subcommands, always on | `SLEEP`, `RELOAD`, `DIGEST`; off by default. `DIGEST` is a bulk string, and leaves out a stream consumer's seen time |
| `BGSAVE` | Writes an RDB file | Same as `BGREWRITEAOF` |
| `WAIT` inside `MULTI` | Answered at once | Refused (`NO_MULTI`); a blocking pop inside `EXEC` does not block, as in Redis, but a `WAIT` there would have nothing to count yet |
| Error message text | Redis wording | Same prefix; wording may differ. Errors are cut to 1024 characters (they may echo client input). |
| Inside `MULTI`: `AUTH`, `HELLO`, `(P)SUBSCRIBE`, `(P)UNSUBSCRIBE`, `BGSAVE`, `BGREWRITEAOF` | Queued | Refused with `-ERR Command not allowed inside a transaction`. A rewrite started inside `EXEC` would record the transaction's effects twice; the others change the connection's state. |
| Which error wins, in a few malformed calls | For example `SRANDMEMBER str abc` gives not-an-integer, `ZPOPMIN str 0` an empty array, `HSCAN missing 0 BOGUS x` an empty scan, `EXPIRE k abc FOO` "Unsupported option" | Another error of the same call is reported first (WRONGTYPE, a syntax error or not-an-integer). Valid calls behave identically. |
| `ZRANGE … BYSCORE BYSCORE` | Syntax error | Accepted |
| `COMMAND INFO` key positions | Real first/last key and step | Always 0, 0, 0 (no key specifications) |
| `INCRBYFLOAT` arithmetic | `long double` | Exact decimal, same presentation ([03 §7](03-protocol.md#7-number-formatting)) |

## 4. Not implemented

These reply `-ERR unknown command`:

| Group | Commands | Reason |
|---|---|---|
| Scripting | `EVAL`, `EVALSHA`, `SCRIPT`, `FUNCTION`, `FCALL` | No Lua (D-7) |
| HyperLogLog, Geo, Bitmaps | `PF*`, `GEO*`, `SETBIT`, `GETBIT`, `BITCOUNT`, `BITOP`, `BITFIELD`, `BITPOS` | Not needed by the backend |
| Streams: two of them | `XSETID`, `XINFO STREAM FULL` | The 2.1 stream commands are in ([15 §3](15-streams.md#3-commands)); these two have no use in the backend |
| Cluster, and the rest of replication | `SYNC`, `WAITAOF`, `CLUSTER`, `READONLY`, `SSUBSCRIBE`, `SPUBLISH` | No cluster; replication from 2.2 is `REPLICAOF`, `PSYNC` and `WAIT` ([16](16-replication.md)) |
| Databases | `MOVE`, `SWAPDB` | One database |
| Serialisation | `DUMP`, `RESTORE`, `MIGRATE` | Not needed |
| Introspection | `OBJECT`, `MEMORY`, `LATENCY`, `MONITOR`, `MODULE`, `ACL` | `MONITOR` possible in M8 |
| Other | `SORT`, `LCS`, `LMPOP`, `ZMPOP`, `HEXPIRE` family | Not needed |
| M8 candidates | `ZUNIONSTORE`, `ZINTERSTORE`, `ZDIFF*`, `ZRANGESTORE`, `ZRANGEBYLEX` and lex family | Not needed yet |
