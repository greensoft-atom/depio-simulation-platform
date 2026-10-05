# 6 — Develop the service itself

For developers who change j-redis-service: where things are, the rules the
code relies on, how to add a command end to end, and how to test and debug.
The design documents (`docs/02`–`08`) explain the reasons behind the rules.
Read [../02-architecture.md](../02-architecture.md) once before a larger
change.

## 6.1 The code at a glance

```
j-redis-common/   com.jredis.common
  RespRequestParser, RespReplyParser, RespWriter   the RESP2 wire format (both directions)
  Reply                                            a decoded reply (client side)
  NumberCodec, Glob, ArgSplitter, Bytes            strict numbers, pattern matching, helpers
  BuildVersion                                     the version, from the POM

j-redis-server/   com.jredis.server
  Main, JRedisServer, Version, FatalHandlers       start-up, lifecycle, exit codes
  config/     ServerConfig, Directives, ConfigLoader        configuration file, CLI and CONFIG
  net/        NettyServer, ServerHandler, RequestDecoder,   Netty: accept, decode, backpressure,
              ServerConnection                              write replies
  core/       Engine            THE command thread: event loop, dispatch, transactions, replies
              EventQueue        the only way into the command thread (MPSC queue)
              Client, CommandContext, Background, Stats, Clock/ManualClock, ByteKey
  command/    Commands          registers every command family into a CommandTable
              *Commands         the implementations, one class per family
              CommandSpec       arity and flags (WRITE, READONLY, DENYOOM, FAST, NO_MULTI…)
              Keys, ScanArgs, Words, CommandException    shared helpers
  db/         Db                the keyspace facade: every read and write of keys goes through it
              Dict, SkipList, ListValue, HashValue, SetValue, ZSetValue   data structures
              ExpiryBuckets, MemoryTracker, MemoryEstimator, SipHash
  persist/    AofPersistence, AofWriter, Loader, BaseFormat, Manifest, …  the append-only file
  blocking/   BlockingRegistry, BlockingServer      BLPOP & co.
  tx/         WatchRegistry                        WATCH
  pubsub/     PubSub                               channels and patterns
  info/       Info, SlowLog                        INFO and SLOWLOG

j-redis-client/   com.jredis.client     (guide 5, ../10-client-library.md)
j-redis-embedded/ com.jredis.embedded   JRedisEmbedded, EmbeddedConfig
j-redis-cli/, j-redis-tools/, j-redis-examples/, j-redis-tests/
```

## 6.2 How a command travels

1. A Netty I/O thread reads bytes, and `RequestDecoder` turns them into a
   `byte[][]` argument vector.
2. `ServerHandler` puts a `Command` event on the `EventQueue`.
3. The **command thread** (`Engine`) takes events in order and calls
   `execute()`. That checks the command exists, its arity, authentication,
   the pub/sub mode, `MULTI` queueing, `MISCONF` and `OOM`, then calls the
   handler with a `CommandContext`.
4. The handler reads and changes data through `Db`, writes its reply into the
   client's reply buffer (`ctx.integer(…)`, `ctx.bulk(…)` …), and records its
   **effect** for the AOF (`ctx.propagate(…)`).
5. At the end of a batch the engine hands the effects to the AOF writer thread
   and flushes every touched client's replies to Netty with one write each.

Only the command thread touches data, so the data structures need no locks.

## 6.3 The rules

| Rule | Why | How |
|---|---|---|
| **Java 21** | Production and all dependencies are Java 21 | Records, sealed types, `var` and switch patterns are all available. Match the surrounding style rather than rewriting working code to use them ([guide 3 §3.4](03-build-and-test.md#34-check-the-java-21-guarantee)). |
| **No new dependencies casually** | Everything must be in the offline bundle and be Java 21 or older | Discuss first; see [guide 7 §7.4](07-upgrade-guide.md#74-upgrade-a-dependency) |
| **Never block the command thread** | Every client waits behind it | No I/O, no sleeping, no locks, nothing O(n) on big inputs without a limit |
| **Validate before you mutate** | Once data has changed, an exception is a *fail-stop* (the process exits with code 4 so that a half-applied change is never served) | Parse every argument and check every type first, then change data |
| **Change keys only through `Db`** | `Db` does the snapshot pre-image, `WATCH`/blocking notifications, TTL bookkeeping and memory accounting | Get the entry with `Keys.write(...)` (or `Keys.read` then `ctx.db().prepareWrite(e)`) *before* the first change. Afterwards call `signalModified(key)`, or `deleteEntry(e)` when a collection became empty. |
| **Log effects, not commands** | Replaying the AOF must reproduce exactly the same data | `ctx.propagateAsIs()` when the command is deterministic. Otherwise log what actually happened: `SPOP` logs `SREM` of the chosen members; a relative TTL logs `PEXPIREAT <absolute>`. |
| **Log nothing when nothing changed** | Keeps the AOF small and replay exact | Only `propagate` on paths that modified data |
| **An emptied collection is deleted** | Redis semantics: no empty hashes, lists, sets or zsets | `if (size == 0) ctx.db().deleteEntry(e)`. **Not a stream**: Redis keeps an empty stream, whose last ID and groups outlive its entries ([15 §4](../15-streams.md#4-the-value-immutable-chunks)) |
| **Errors are Redis-compatible** | Clients and tools compare prefixes | `throw CommandException.WRONGTYPE / SYNTAX / NOT_INTEGER …`, or a new `CommandException("ERR …")` |
| **`ByteBuf` ownership** | Netty buffers are reference-counted | Handlers never allocate buffers; they use `ctx.*` reply helpers |

## 6.4 Adding a command, step by step

As an example, a new extension command `J.HPOP key field`: return a hash
field's value and delete the field, atomically. The code below was compiled
and tested exactly as shown.

**1. Choose the family and register it** with its arity and flags. Arity is
the argument count including the command name; a negative number means "at
least". The flags drive `MULTI`, `MISCONF`, `OOM` and `INFO commandstats`:

```java
// command/ExtensionCommands.java, in register(CommandTable t)
t.register("J.HPOP", 3, WRITE | FAST, ExtensionCommands::hpop);
```

| Flag | Meaning |
|---|---|
| `WRITE` | Changes data: rejected with `MISCONF` while the AOF cannot be written |
| `DENYOOM` | Can grow memory: rejected with `OOM` above `maxmemory` (J.HPOP only removes, so not this one) |
| `READONLY` | Only reads |
| `FAST` | O(1) or O(log n) |
| `NO_MULTI` | Not allowed inside `MULTI` |
| `ADMIN`, `NOAUTH`, `PUBSUB`, `BLOCKING`, `LOADING_OK`, `TX_CONTROL` | See `CommandSpec` |

**2. Write the handler.** Validate first, mutate after `prepareWrite`, reply,
and log the effect:

```java
/** J.HPOP key field → the field's value, removed from the hash; nil if the key or field is absent. */
static void hpop(CommandContext ctx) {
    byte[] key = ctx.arg(1);
    byte[] field = ctx.arg(2);
    KeyEntry e = Keys.read(ctx, key, KeyEntry.HASH);        // null if absent, WRONGTYPE if not a hash
    byte[] value = e == null ? null : e.hash().get(field);
    if (value == null) {
        ctx.nullBulk();                                     // nothing changes, nothing is logged
        return;
    }
    ctx.db().prepareWrite(e);                               // before the first change (snapshot, WATCH)
    HashValue h = e.hash();
    h.remove(field);
    if (h.size() == 0) {
        ctx.db().deleteEntry(e);                            // an emptied hash disappears
    } else {
        ctx.db().signalModified(key);                       // wakes WATCH and blocked clients
    }
    ctx.propagate(Words.HDEL, key, field);                  // the effect, for the AOF
    ctx.bulk(value);
}
```

It logs `HDEL key field` rather than `J.HPOP key field`. Both would replay
correctly, but logging the plain effect keeps the AOF readable by older
versions. Add the constant to `Words`: `static final byte[] HDEL = a("HDEL");`.

**3. Test it** in `j-redis-tests`. There is one test for the behaviour and one
for replay:

```java
class HpopTest extends EmbeddedTest {
    @TempDir Path dir;

    @Test void popsAFieldAndRemovesEmptyHashes() {
        expect("HSET h a 1 b 2", ":2");
        expect("J.HPOP h a", "1");
        expect("J.HPOP h a", "(nil)");
        expect("J.HPOP h b", "2");
        expect("EXISTS h", ":0");
        expect("SET s v", "+OK");
        expectError("J.HPOP s x", "WRONGTYPE");
        expectError("J.HPOP h", "ERR wrong number of arguments");
    }

    @Test void theEffectReplaysAfterARestart() {
        try (JRedisEmbedded r = JRedisEmbedded.start(EmbeddedConfig.persistentAt(dir))) {
            r.newClient().sync().send("HSET", "h", "a", "1", "b", "2");
            r.newClient().sync().send("J.HPOP", "h", "a");
        }
        try (JRedisEmbedded r = JRedisEmbedded.start(EmbeddedConfig.persistentAt(dir))) {
            assertThat(r.newClient().sync().hgetall("h")).containsOnlyKeys("b");
        }
    }
}
```

`expect(line, rendered)` sends a command line and compares the rendered reply:
`+OK`, `:5`, `-ERR…`, a bulk string as its text, `(nil)`, and arrays as
`[a, :1]`.

**4. Optionally add it to the reference model** (`Model.java` and the
generator in `ModelBasedTest`), so random command streams exercise it
alongside everything else.

**5. Optionally add a typed client method** in `AsyncCommands` and
`JRedisSync`. `send("J.HPOP", key, field)` already works without one.

**6. Document it** in [../05-commands.md](../05-commands.md): the command list,
and the effect it logs.

## 6.5 Tests: which kind, where

| Kind | Where | Use for |
|---|---|---|
| Unit / property tests | `j-redis-common`, `j-redis-server` (`src/test`) | Data structures and codecs: compare against a simple Java collection under random operations |
| Command tests | `j-redis-tests`: extend `EmbeddedTest` | A command's replies and edge cases, with a manual clock |
| Model-based | `ModelBasedTest` + `Model` | Random command streams checked against a naive model |
| Protocol tests | `ProtocolTest` (real TCP, `RawClient`) | Wire-level behaviour: pipelining, errors, `MULTI`, `BLPOP`, pub/sub |
| Persistence tests | `PersistenceTest` | Restart, rewrite, torn tail, corruption |
| Client tests | `ClientLibraryTest` | Client behaviour, embedded and over TCP |
| Slow tests | tagged `@Tag("slow")`, run with `-Pfull` | Child JVMs, `kill -9` |

Tests must not depend on timing. Wait for a condition (for example `INFO
clients` showing `blocked_clients:1`) instead of sleeping. Use `ManualClock`
for TTLs. The build machine may be heavily loaded.

## 6.6 Debugging

- **Run the server in the IDE**: main class `com.jredis.server.Main`,
  arguments `--port 6380 --dir target/dev-data --enable-debug-command yes`,
  then set breakpoints in a handler. Everything that touches data runs on the
  thread named `jredis-cmd`.
- **More log output**: the environment variable `JREDIS_LOG_LEVEL=DEBUG`
  (server), or `JREDIS_TEST_LOG_LEVEL=DEBUG` (tests).
- **Look at the AOF**: `incr.N.aof` is plain RESP. `less` shows exactly which
  effects a command logged. `j-redis-tools dump --values <dir>` prints the
  base file.
- **The server's view**: `INFO`, `CLIENT LIST`, `SLOWLOG GET`, and
  `DEBUG RELOAD`, which saves and reloads everything and is a quick
  persistence round trip.
- **A hung or slow server**: `jstack <pid>` and look at the `jredis-cmd`
  thread. `INFO cpu` shows how busy the command thread is.
- **Protocol questions**: `j-redis-cli` shows replies the way redis-cli does.
  `RawClient` in the tests lets you send any bytes.

## 6.7 Before you hand in a change

1. `mvn clean install` is green, and so is `-Pfull` for changes to
   persistence, the engine or networking.
2. No new compiler warnings (the build uses `-Xlint:all`).
3. New behaviour has a test. A bug fix has a test that failed before the fix.
4. Docs updated: `05-commands.md` for commands, `11-operations-and-security.md`
   for directives, `14-decision-log.md` for decisions, and `CHANGELOG.md`.
5. If a file format changed (manifest, base file, AOF effects), read
   [guide 7 §7.3](07-upgrade-guide.md#73-data-format-compatibility) first.
