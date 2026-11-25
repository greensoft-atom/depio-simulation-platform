# 07 — Pub/Sub, blocking operations, transactions

This page describes three features that change a *client's* state, not only
the data: publish/subscribe ("pub/sub"), blocking operations and transactions.
It is for engineers who change or review these parts of the server, and for
application developers who want to know exactly what each feature guarantees.
All three run on the command thread and use the hooks in
[02 §7](02-architecture.md#7-the-db-facade-and-mutation-hooks).

---

## 1. Pub/Sub

In pub/sub, a client **subscribes** to a channel and then receives every
message that another client **publishes** to that channel. The server stores no
message.

### 1.1 Registries

The server keeps these structures (`pubsub/PubSub.java`, `core/Client.java`):

| Structure | Holds |
|---|---|
| `HashMap<ByteKey, ArrayList<Client>>` | Channel → its subscribers ([14 D-20](14-decision-log.md#d-20--registries-use-javautilhashmap)) |
| `ArrayList<PatternSub>` | One entry per pattern subscription: the pattern's bytes, its compiled matcher and the client |
| `Client.channels`, `Client.patterns` | The reverse index, used to unsubscribe from everything and to clean up when the client disconnects |

A client with at least one subscription is in **subscriber mode**. In that
mode it may run only the commands listed in
[03 §6](03-protocol.md#6-pubsub-frames). When it unsubscribes from everything,
it returns to normal mode.

### 1.2 `PUBLISH channel message`

The server runs `PUBLISH` in five steps:

1. It encodes the `message` frame once, into a `byte[]`.
2. It appends that frame to the pending reply buffer of every subscriber of the
   channel.
3. For every pattern subscription whose glob pattern matches the channel, it
   encodes and appends a `pmessage` frame.
4. It marks those clients as touched. The flush at the end of the batch sends
   the frames ([02 §3](02-architecture.md#3-request-lifecycle)).
5. It replies to the publisher with the number of deliveries. A client
   subscribed both to the channel **and** to a matching pattern receives two
   frames and counts twice, as in Redis.

Pattern matching costs O(number of patterns) for each publish. The backend uses
exact channels (`user:{id}`, `instance:{name}:cmd`), so this cost is
negligible. If patterns ever number in the hundreds, index them by their
literal prefix.

### 1.3 Glob patterns

One matcher (`Glob` in `j-redis-common`) serves `PSUBSCRIBE`, `SCAN … MATCH`
and `KEYS`. It supports `*`, `?`, `[abc]`, `[^abc]`, `[a-z]` and `\` escapes,
and it compares bytes, not characters.

Patterns come from clients, so the matcher **must not take exponential time**.
A simple recursive matcher explodes on `*a*a*a*a*b` against a long string of
`a`s. So the matcher uses the iterative two-pointer algorithm, which remembers
only the position of the last `*`. Its worst case is O(pattern × text), with
no recursion.

### 1.4 Delivery guarantees

- **At most once.** The server never stores messages. A subscriber that is
  disconnected, or that subscribes a moment late, misses them.
- **Ordered.** The single command thread appends every frame for a subscriber
  in the order in which it runs the `PUBLISH` commands. So a subscriber sees
  messages in the order the server ran the publishes, and the messages of one
  publishing connection in the order that connection sent them.

Anything that must not be lost goes through a list, not pub/sub
([09](09-integration-patterns.md#3-recipes)).

### 1.5 Slow subscribers

A subscriber that stops reading would make its pending output grow without
limit. So each client tracks its **pending output bytes**: the bytes handed to
Netty, minus the bytes a `ChannelFutureListener` has confirmed as written, plus
the current batch buffer.

The `client-output-buffer-limit` directive sets the limits for each class of
client ([11](11-operations-and-security.md#2-configuration)). The defaults are:

| Class | Hard limit | Soft limit | Action |
|---|---|---|---|
| Subscriber (`pubsub`) | 32 MB | 8 MB for 60 s | **Disconnect**, and log a WARN line with the client's id, name and address |
| Normal (`normal`) | None by default (configurable) | None | — |
| Replica (`replica`, 2.2) | 256 MB | 64 MB for 60 s | Disconnect, as for a subscriber ([16](16-replication.md)) |

A client is disconnected when its pending output passes the hard limit, or
stays above the soft limit for the soft limit's time.

Disconnecting, instead of silently dropping messages, is deliberate. The client
knows that it missed something: it reconnects, and its library reports the
reconnect so that the application can resynchronise
([10 §6](10-client-library.md#6-pubsub)).

---

## 2. Blocking operations

A **blocking operation** waits for data when there is none, instead of
replying at once. The blocking commands are `BLPOP`, `BRPOP`, `BLMOVE`,
`BRPOPLPUSH`, `BZPOPMIN` and `BZPOPMAX`. Since 2.1, `XREAD` and `XREADGROUP`
with `BLOCK` also block; §2.6 lists how they differ.

### 2.1 Structures

| Structure | Holds |
|---|---|
| `HashMap<ByteKey, ArrayDeque<Client>>` | Key → the clients waiting on it, first in, first out (FIFO) |
| `Client.block` (a `BlockState`) | The keys, the timeout, which operation, and for `BLMOVE` the destination and directions |
| `TreeMap<Long, ArrayList<Client>>` | Timeout time → clients. Few clients block, so a tree is fast enough |
| The ready set | The keys that gained elements during the current command |

These structures are in `blocking/BlockingRegistry.java`.

### 2.2 Blocking

When a blocking command finds every key empty or missing:

- **Inside `EXEC`**, it behaves as its non-blocking form and replies null at
  once. A transaction never waits.
- **Otherwise**, the server:
  1. appends the client to each key's queue;
  2. registers its timeout: `0` means wait forever, a negative timeout replies
     `-ERR timeout is negative`, and the seconds may have a fractional part;
  3. marks the client as blocked, by setting its `BlockState` in
     `Client.block`;
  4. sends **no reply yet**.

Any later pipelined commands from a blocked client wait in `Client.deferred`
([02 §9](02-architecture.md#deferred-commands)).

### 2.3 Waking

1. Every write that adds elements to a list or a sorted set calls
   `signalModified(key)`. If the key has waiting clients, it goes into the
   ready set.
2. `serveReadyKeys()` runs **after the command completes**. For a transaction,
   it runs after the whole `EXEC`, so waiting clients never see intermediate
   states.
3. For each ready key, while the key has waiting clients and holds a
   **non-empty value of the type they wait for**, the server:
   - takes the first waiting client;
   - runs its pop against the key, **propagates the non-blocking effect**
     (`LPOP`, `RPOP`, `LMOVE`, `ZPOPMIN`, `ZPOPMAX`) and replies to it;
   - removes it from every other key's queue and from the timeout tree;
   - puts it on the *unblocked* list.
4. A `BLMOVE` pushes into its destination, which can make that key ready. The
   loop continues until the ready set is empty.
5. Before the command thread drains the event queue again, it runs the
   deferred commands of each unblocked client, in the order the clients were
   unblocked.

If a key is replaced by a value of another type, its waiting clients keep
waiting.

### 2.4 Timeouts and disconnects

- In every background slice, the server takes the expired entries from the
  timeout tree. Each such client gets `*-1` (a null array), is unblocked, and
  its deferred commands run.
- When a blocked client disconnects, the server removes it from every key's
  queue and from the timeout tree.

### 2.5 Fairness

The server serves the clients waiting on one key first come, first served. A
client waiting on several keys is served by whichever key becomes ready first.

---

### 2.6 Stream readers (2.1)

`XREAD BLOCK ms` and `XREADGROUP … BLOCK ms` wait in the same structures, with
the timeout in milliseconds ([15 §6](15-streams.md#6-blocking)). They differ
from the pops in three ways:

- **They wait for an entry after a position, not for any change.** Every write
  to a stream wakes its readers, `XACK` and `XDEL` included. So the server
  serves a woken reader only if an entry exists after the reader's position.
  Otherwise the reader keeps waiting, in its place. The position is:
  - for a plain reader, fixed when it blocked (`$` means the last ID at that
    moment);
  - for a group reader, the group's last delivered ID at the moment it is
    served.
- **A group reader whose stream is deleted, or whose group is destroyed, gets
  an answer at once**: `-UNBLOCKED the stream key no longer exists` or
  `-NOGROUP the consumer group this client was blocked on no longer exists`,
  as Redis 7 answers. A plain reader on a deleted stream keeps waiting for the
  stream to come back. So serving does not stop at a missing key while a
  stream reader is in the queue.
- **A group reader served later delivers exactly as a read that did not
  wait**: the same code runs, and the server logs the same effects (D-34). If
  the reader created a consumer when it blocked, that was logged then. A read
  of history (ID `0`, not `>`) never waits.

A served reader gets only the stream that served it, `[[key, entries]]`, as in
Redis.

## 3. Transactions

A **transaction** is a group of commands that the server runs together, with
nothing else in between: `MULTI`, the commands, then `EXEC`.

### 3.1 `MULTI`

`MULTI` sets the client's `MULTI` flag. Each later command goes through the
dispatch checks **when it is queued** ([02 §6](02-architecture.md#6-command-dispatch)):
checks 1 to 5, then the queue-time checks for `NO_MULTI`, `MISCONF` and `OOM`.

- If every check passes, the server appends the command's `argv` to the queue
  and replies `+QUEUED`.
- If a check fails (an unknown command, the wrong number of arguments,
  `NOAUTH`, `READONLY` or `NOREPLICAS` on a write, a command not allowed in
  subscriber mode, a `NO_MULTI` command, `MISCONF` or `OOM`), the server replies
  with the error and marks the transaction **aborted**.

These are not allowed inside `MULTI`:

- a nested `MULTI`: `-ERR MULTI calls can not be nested`;
- `WATCH`: `-ERR WATCH inside MULTI is not allowed`;
- every command flagged `NO_MULTI`, such as the subscribe commands: the full
  list is in [05 §2](05-commands.md#transactions).

### 3.2 `EXEC`

`EXEC` runs these steps in order (`command/TransactionCommands.java`):

1. If the transaction is aborted, it replies
   `-EXECABORT Transaction discarded because of previous errors.`
2. If the queue holds a write and persistence is failing, it replies
   `-EXECABORT Transaction discarded because of: ` followed by the `MISCONF`
   error. If the queue holds a `DENYOOM` command and memory is over
   `maxmemory`, it replies the same way with the `OOM` error. Conditions may
   have changed since the commands were queued, as in Redis.
3. It looks up every watched key, so that lazy expiry fires `signalModified`
   for a watched key that has expired (§3.3). If the client is then **dirty**,
   it replies `*-1` (a null array).
4. Otherwise it writes `*N`, where N is the number of queued commands. N is
   known in advance, because each command produces exactly one reply. Then it
   runs each command's handler in order. A runtime error such as `WRONGTYPE`
   becomes an error **element** of the array, and the other commands still
   run. **There is no rollback.**
5. It propagates the effects wrapped in `MULTI` … `EXEC`, but only if at least
   one command changed something. If a crash cuts the AOF inside the wrapper,
   the server discards the partial transaction when it loads the file
   ([08 §8](08-persistence.md#8-start-up-and-recovery)).
6. `serveReadyKeys()` runs after the whole transaction.
7. It clears the queue and all watches. (After an `EXECABORT` or a `*-1`, it
   clears them too.)

The transaction is atomic at no extra cost: nothing else runs on the command
thread between the first and the last queued command.

### 3.3 `WATCH`

`WATCH` lets a client make its transaction depend on keys it read before.

- `WATCH key…` works only outside `MULTI`. It adds the client to the key's
  watchers in `tx/WatchRegistry` (`HashMap<ByteKey, ArrayList<Client>>`, key →
  watchers), and adds the key to `Client.watchedKeys`. It first looks the key
  up, so an already-expired key is removed then, and does not count as a
  change later.
- `signalModified(key)` marks every watcher **dirty**. Every change, deletion
  and expiry goes through that hook, so nothing is missed.
- A watched key that has expired, but that active expiry has not reached yet,
  is caught at `EXEC` step 3.
- `FLUSHALL` marks dirty every client that watches a key that exists, as Redis
  does. A `WATCH` on a missing key survives it.
- `UNWATCH`, `EXEC`, `DISCARD`, `RESET` and a disconnect clear the client's
  watches.

### 3.4 `DISCARD`

`DISCARD` clears the queue and the `MULTI` flag, unwatches every key, and
replies `+OK`. Without `MULTI`, it replies `-ERR DISCARD without MULTI`.

### 3.5 When to use what

| Need | Use |
|---|---|
| Several writes that must apply together | `MULTI` … `EXEC`, sent pipelined in one round trip |
| Compare-and-set of one string | `J.CAS` ([09 §2](09-integration-patterns.md#2-extension-commands)) |
| Read several keys, decide, then write | `WATCH` + `MULTI`/`EXEC` on a leased connection ([10 §7](10-client-library.md#7-transactions)); retry when `EXEC` replies `*-1` |
