# 6 — Develop the service itself

This guide is for developers who change j-redis-service itself, not for
developers who only use it from an application (they read
[guide 5](05-client-guide.md)). It covers where each part of the code is, the
rules the code relies on, how to add a command from start to end, which kind of
test to write, and how to debug. The design documents (`docs/02` to `docs/08`,
and `docs/15` and `docs/16` for streams and replication) explain the reasons
behind the rules. Read [../02-architecture.md](../02-architecture.md) once
before a larger change.

> **Inside the backend's repository**, source `dev-env.sh` from the repository's
> root first: it puts the committed JDK and Maven on the `PATH` and points Maven
> at the offline bundle. `./dev-jredis.sh build` then compiles, tests and
> installs j-redis-service (`mvn clean install`, without `-Pfull`;
> `SKIP_TESTS=1` skips the tests). `./dev-jredis.sh start` runs the server from
> that build on `127.0.0.1:6380`, and `DEBUG=1` adds a debugger on
> `127.0.0.1:5009` ([build and run §5](../../../docs/development/03-build-and-run.md#5-running-it-by-hand)).

## 6.1 The code at a glance

Each Maven module has one Java package. The server is the largest module. Its
sub-packages follow the parts of the design.

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
              Effects           each batch's effects, encoded once, for the AOF and the backlog
              Client, CommandContext, Background, Stats, Clock/ManualClock, ByteKey
  command/    Commands          registers every command family into a CommandTable
              *Commands         the implementations, one class per family
              CommandSpec       arity and flags (WRITE, READONLY, DENYOOM, FAST, NO_MULTI…)
              Keys, ScanArgs, Words, CommandException    shared helpers
  db/         Db                the keyspace facade: every read and write of keys goes through it
              Dict, SkipList, ListValue, HashValue, SetValue, ZSetValue   data structures
              StreamValue, StreamGroup, StreamId, StreamDelivery          streams (2.1)
              ExpiryBuckets, MemoryTracker, MemoryEstimator, SipHash, Digest
  persist/    AofPersistence, AofWriter, Loader, BaseFormat, Manifest, …  the append-only file
  repl/       Replication, PrimaryLink, ReplicaSession, Backlog, EpochFile, …  replication (2.2)
  blocking/   BlockingRegistry, BlockingServer      BLPOP & co.
  tx/         WatchRegistry                        WATCH
  pubsub/     PubSub                               channels and patterns
  info/       Info, SlowLog                        INFO and SLOWLOG

j-redis-client/   com.jredis.client     (guide 5, ../10-client-library.md)
j-redis-embedded/ com.jredis.embedded   JRedisEmbedded, EmbeddedConfig
j-redis-cli/, j-redis-tools/, j-redis-examples/, j-redis-tests/
```

The abbreviations in this guide:

| Term | Meaning |
|---|---|
| RESP2 | The Redis Serialization Protocol, version 2: the wire format between clients and the server ([../03-protocol.md](../03-protocol.md)) |
| AOF | The append-only file: the log of changes that the server replays at start-up ([../08-persistence.md](../08-persistence.md)) |
| MPSC queue | A multi-producer, single-consumer queue: many I/O threads put events in, one command thread takes them out |
| Effect | The deterministic form of a change, which the server writes to the AOF and sends to replicas (§6.3) |
| TTL | Time to live: the time after which a key expires |

## 6.2 How a command travels

One thread, the **command thread**, runs every command. The steps below show
how a request reaches it and how the reply goes back.

1. A Netty I/O thread reads bytes. `RequestDecoder` turns them into a
   `byte[][]` argument vector.
2. `ServerHandler` puts a `Command` event on the `EventQueue`.
3. The command thread (`Engine`) takes the events in order and calls
   `execute()`. `execute()` checks, in this order: that the command exists and
   is not disabled, its arity, authentication, that a write does not reach a
   replica (`READONLY`) or a primary short of replicas (`NOREPLICAS`), the
   pub/sub mode, `MULTI` queueing, `MISCONF` and `OOM`. Then it calls the
   handler with a `CommandContext`.
4. The handler reads and changes data through `Db`. It writes its reply into
   the client's reply buffer (`ctx.integer(…)`, `ctx.bulk(…)` …). It records
   its **effect** with `ctx.propagate(…)`.
5. At the end of a batch, the engine hands the batch's effects on: to the AOF
   writer thread when the AOF is on, and to the replication backlog once a
   replica has connected (the first `PSYNC` starts the backlog). Then it
   flushes the replies of every client the batch touched to Netty, with one
   write per client.

Only the command thread touches data, so the data structures need no locks.

## 6.3 The rules

Every change to the server must follow these rules. The first column is the
rule, the second why it exists, the third how to follow it.

| Rule | Why | How |
|---|---|---|
| **Java 21** | Production and all dependencies are Java 21 | Records, sealed types, `var` and switch patterns are all available. Match the surrounding style. Do not rewrite working code only to use them ([guide 3 §3.4](03-build-and-test.md#34-check-the-java-21-guarantee)). |
| **No new dependencies casually** | Every dependency must be in the offline bundle and be Java 21 or older | Discuss it first; see [guide 7 §7.4](07-upgrade-guide.md#74-upgrade-a-dependency) |
| **Never block the command thread** | Every client waits behind it | No I/O, no sleeping, no locks. Nothing O(n) on big inputs without a limit. |
| **Validate before you mutate** | An unexpected exception (anything but a `CommandException`) after data has changed is a *fail-stop*: the process exits with code 4, so a half-applied change is never served. A `CommandException` after a change only replies an error, and the change stays. | Parse every argument and check every type first. Then change data. Throw every `CommandException` before the first change. |
| **Change keys only through `Db`** | `Db` does the snapshot pre-image, the `WATCH` and blocking notifications, TTL bookkeeping and memory accounting | Get the entry with `Keys.write(...)`, or with `Keys.read` and then `ctx.db().prepareWrite(e)`, *before* the first change. Afterwards call `signalModified(key)`, or `deleteEntry(e)` when a collection became empty. |
| **Log effects, not commands** | Replaying the AOF must reproduce exactly the same data, and a replica applies the same effects | Call `ctx.propagateAsIs()` when the command is deterministic. Otherwise log what actually happened: `SPOP` logs `SREM` of the chosen members; a relative TTL logs `PEXPIREAT <absolute>`. |
| **Log nothing when nothing changed** | It keeps the AOF small and the replay exact | Call `propagate` only on the paths that modified data |
| **An emptied collection is deleted** | Redis semantics: there are no empty hashes, lists, sets or sorted sets | `if (size == 0) ctx.db().deleteEntry(e)`. **Not a stream**: Redis keeps an empty stream, because its last ID and its groups outlive its entries ([15 §4](../15-streams.md#4-the-value-immutable-chunks)) |
| **Errors are Redis-compatible** | Clients and tools compare the error prefixes | `throw CommandException.WRONGTYPE / SYNTAX / NOT_INTEGER …`, or a new `CommandException("ERR …")` |
| **`ByteBuf` ownership** | Netty buffers are reference-counted | Handlers never allocate buffers. They use the `ctx.*` reply helpers. |

## 6.4 Adding a command, step by step

This section adds an example extension command, `J.HPOP key field`. It returns
a hash field's value and deletes the field, atomically. The code below was
compiled and tested exactly as shown when this guide was written. `J.HPOP` is
not part of the server: it only shows the steps.

**1. Choose the family and register the command** with its arity and flags.
The arity is the argument count, including the command name. A negative number
means "at least". The flags drive `MULTI`, `MISCONF`, `OOM` and
`INFO commandstats`:

```java
// command/ExtensionCommands.java, in register(CommandTable t)
t.register("J.HPOP", 3, WRITE | FAST, ExtensionCommands::hpop);
```

The flags are constants in `CommandSpec`:

| Flag | Meaning |
|---|---|
| `WRITE` | Changes data. The server rejects it with `MISCONF` while the AOF cannot be written, with `READONLY` on a replica, and with `NOREPLICAS` when `min-replicas-to-write` is not met |
| `DENYOOM` | Can grow memory. The server rejects it with `OOM` above `maxmemory`. `J.HPOP` only removes data, so it does not take this flag |
| `READONLY` | Only reads |
| `FAST` | O(1) or O(log n) |
| `NO_MULTI` | Not allowed inside `MULTI` |
| `ADMIN`, `NOAUTH`, `PUBSUB`, `BLOCKING`, `LOADING_OK`, `TX_CONTROL` | See `CommandSpec` |

**2. Write the handler.** Validate first, change data only after
`prepareWrite`, reply, and log the effect:

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

The handler logs `HDEL key field`, not `J.HPOP key field`. Both would replay
correctly. The plain effect keeps the AOF readable by older versions. `Words`
has no `HDEL` constant yet, so add it: `static final byte[] HDEL = a("HDEL");`.

**3. Test it** in `j-redis-tests`. Write one test for the behaviour and one for
the replay after a restart:

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

`EmbeddedTest` starts an in-memory embedded server on a manual clock, with
`DEBUG` enabled, and one client. `expect(line, rendered)` sends a command line
and compares the rendered reply. The rendering is: `+OK`, `:5`, `-ERR…`, a
bulk string as its text, `(nil)`, and arrays as `[a, :1]`.
`expectError(line, prefix)` checks that the reply is an error that starts with
the prefix.

**4. Optionally add it to the reference model** (`Model.java`, and the
generator in `ModelBasedTest`). Random command streams then exercise it
alongside everything else.

**5. Optionally add a typed client method** in `AsyncCommands` and
`JRedisSync`. Without one, `send("J.HPOP", key, field)` already works.

**6. Document it** in [../05-commands.md](../05-commands.md): add it to the
command list, with the effect it logs.

## 6.5 Tests: which kind, where

Choose the kind of test by what you changed. The table shows where each kind
lives.

| Kind | Where | Use for |
|---|---|---|
| Unit / property tests | `j-redis-common`, `j-redis-server` (`src/test`) | Data structures and codecs: compare against a simple Java collection under random operations |
| Command tests | `j-redis-tests`: extend `EmbeddedTest` | A command's replies and edge cases, with a manual clock |
| Model-based | `ModelBasedTest` + `Model` | Random command streams checked against a naive model |
| Protocol tests | `ProtocolTest` (real TCP, `RawClient`) | Wire-level behaviour: pipelining, errors, `MULTI`, `BLPOP`, pub/sub |
| Persistence tests | `PersistenceTest` | Restart, rewrite, torn tail, corruption |
| Client tests | `ClientLibraryTest` | Client behaviour, embedded and over TCP |
| Stream tests | `StreamCommandsTest`, `StreamGroupsTest`, `StreamBlockingTest`, `StreamClientTest` | Streams: entries and IDs, consumer groups, blocking reads, the client's stream methods ([../15-streams.md](../15-streams.md)) |
| Replication tests | `ReplicationTest`, `ClientFailoverTest`, `EffectStreamTest`, `DebugDigestTest` | A primary and a replica over TCP, a client following a promotion, the stream of effects, `DEBUG DIGEST` ([../16-replication.md](../16-replication.md)) |
| Slow tests | tagged `@Tag("slow")`, run with `-Pfull` | Child JVMs, `kill -9`: `CrashTest` and `ReplicationCrashTest` |

**Tests must not depend on timing.** Wait for a condition instead of sleeping:
for example, until `INFO clients` shows `blocked_clients:1`. Use `ManualClock`
for TTLs. The build machine may be heavily loaded.

## 6.6 Debugging

Each tool below answers a different question about a running server.

| What you want | How |
|---|---|
| **Run the server in the IDE** | Main class `com.jredis.server.Main`, arguments `--port 6380 --dir target/dev-data --enable-debug-command yes`. Then set breakpoints in a handler. Everything that touches data runs on the thread named `jredis-cmd`. Inside the backend's repository, `./dev-jredis.sh` uses port 6380: while it runs, give the IDE's server another port. |
| **More log output** | Set the environment variable `JREDIS_LOG_LEVEL=DEBUG` for the server (the default is `INFO`), or `JREDIS_TEST_LOG_LEVEL=DEBUG` for the tests (the default is `WARN`). |
| **Look at the AOF** | `incr.N.aof` is plain RESP, so `less` shows exactly which effects a command logged. `j-redis-tools dump --values <dir>` prints the base file. |
| **The server's view** | `INFO`, `CLIENT LIST`, `SLOWLOG GET`, and `DEBUG RELOAD`. `DEBUG RELOAD` saves and reloads everything, so it is a quick persistence round trip. |
| **A hung or slow server** | Run `jstack <pid>` and look at the `jredis-cmd` thread. `INFO cpu` shows how busy the command thread is (`cmd_thread_cpu_percent`). |
| **Protocol questions** | `j-redis-cli` shows replies the way redis-cli does. `RawClient` in the tests lets you send any bytes. |

## 6.7 Before you hand in a change

Check each item before you hand in a change:

1. `mvn clean install` is green. Run `-Pfull` too for changes to persistence,
   replication, the engine or networking.
2. There are no new compiler warnings. The build compiles with
   `-Xlint:all,-options,-serial,-processing` and `showWarnings`.
3. New behaviour has a test. A bug fix has a test that failed before the fix.
4. The docs are updated: `05-commands.md` for commands,
   `11-operations-and-security.md` for directives, `14-decision-log.md` for
   decisions, and `CHANGELOG.md`.
5. If a file format changed (the manifest, the base file, the AOF effects, the
   `replication` file), read
   [guide 7 §7.3](07-upgrade-guide.md#73-data-format-compatibility) first.
