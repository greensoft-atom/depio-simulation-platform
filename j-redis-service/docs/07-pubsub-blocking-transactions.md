# 07 — Pub/Sub, blocking operations, transactions

Three features that change a *client's* state rather than only the data. All
three live on the command thread and use the hooks in
[02 §7](02-architecture.md#7-the-db-facade-and-mutation-hooks).

---

## 1. Pub/Sub

### 1.1 Registries

| Structure | Holds |
|---|---|
| `HashMap<ByteKey, ArrayList<Client>>` | channel → subscribers ([14 D-20](14-decision-log.md#d-20--registries-use-javautilhashmap)) |
| `ArrayList<PatternSub>` | (pattern bytes, compiled matcher, client) |
| `Client.channels`, `Client.patterns` | the reverse index, for unsubscribe-all and disconnect cleanup |

A client with at least one subscription is in **subscriber mode** and may run
only the commands listed in [03 §6](03-protocol.md#6-pubsub-frames).
Unsubscribing from everything returns it to normal mode.

### 1.2 `PUBLISH channel message`

1. Encode the `message` frame once into a `byte[]`.
2. Append it to the pending reply buffer of every subscriber of the channel.
3. For every pattern subscription whose glob matches, encode and append a
   `pmessage` frame.
4. Mark those clients as touched; the end-of-batch flush sends it
   ([02 §3](02-architecture.md#3-request-lifecycle)).
5. Reply to the publisher with the number of deliveries. A client subscribed by
   channel **and** by a matching pattern receives two frames and counts twice,
   as in Redis.

Pattern matching is O(number of patterns) per publish. The backend uses exact
channels (`user:{id}`, `instance:{name}:cmd`), so this is negligible. If patterns
ever number in the hundreds, index them by literal prefix.

### 1.3 Glob patterns

The same matcher serves `PSUBSCRIBE`, `SCAN … MATCH` and `KEYS`:
`*`, `?`, `[abc]`, `[^abc]`, `[a-z]`, `\` escapes; matched byte-wise.

Patterns come from clients, so the matcher **must not be exponential**. A naive
recursive matcher explodes on `*a*a*a*a*b` against a long string of `a`s. Use
the iterative two-pointer algorithm that remembers only the last `*` position:
O(pattern × text) in the worst case, no recursion.

### 1.4 Delivery guarantees

- **At most once.** Messages are never stored. A subscriber that is
  disconnected, or subscribes a moment late, misses them.
- **Ordered.** Every frame for a subscriber is appended by the single command
  thread in `PUBLISH` execution order, so a subscriber sees messages in the
  order the server executed the publishes — and messages from one publisher
  connection in the order it sent them.

Anything that must not be lost goes through a list, not pub/sub
([09](09-integration-patterns.md#3-recipes)).

### 1.5 Slow subscribers

A subscriber that stops reading would make its pending output grow without
bound. Each client tracks **pending output bytes**: bytes handed to Netty minus
bytes confirmed written by a `ChannelFutureListener`, plus the current batch
buffer.

| Class | Hard limit | Soft limit | Action |
|---|---|---|---|
| Subscriber | 32 MB | 8 MB for 60 s | **Disconnect**, log WARN with the client name |
| Normal | none (config) | none | — |

Disconnecting instead of silently dropping messages is deliberate: the client
knows it missed something, reconnects, and its library reports a reconnect so
the application can resynchronise ([10 §6](10-client-library.md#6-pubsub)).

---

## 2. Blocking operations

`BLPOP`, `BRPOP`, `BLMOVE`, `BRPOPLPUSH`, `BZPOPMIN`, `BZPOPMAX`; from 2.1
`XREAD` and `XREADGROUP` with `BLOCK`, whose differences are in §2.6.

### 2.1 Structures

| Structure | Holds |
|---|---|
| `HashMap<ByteKey, ArrayDeque<Client>>` | key → clients waiting on it, FIFO |
| `Client.blockState` | keys, timeout, which operation, `BLMOVE` destination and directions |
| `TreeMap<Long, ArrayList<Client>>` | timeout time → clients (few blocked clients, so a tree is fine) |
| `readyKeys` | keys that gained elements during the current command |

### 2.2 Blocking

When a blocking command finds every key empty or missing:

- **inside `EXEC`** it behaves as its non-blocking form and replies null
  immediately — a transaction may never park;
- otherwise the client is appended to each key's deque, its timeout is
  registered (`0` = forever; negative → `-ERR timeout is negative`; seconds
  may be fractional), it is flagged `BLOCKED`, and **no reply is sent yet**.

Later pipelined commands from a blocked client go to `Client.deferred`
([02 §9](02-architecture.md#deferred-commands)).

### 2.3 Waking

1. Any write that adds elements to a list or sorted set calls
   `signalModified(key)`; if the key has waiters, it goes into `readyKeys`.
2. **After the command completes** — after the whole `EXEC` for a transaction,
   so waiters never observe intermediate states — `serveReadyKeys()` runs.
3. For each ready key, while it has waiters and holds a **non-empty value of the
   type they wait for**:
   - take the first waiter;
   - run its pop against the key, **propagate the non-blocking effect**
     (`LPOP`, `RPOP`, `LMOVE`, `ZPOPMIN`, `ZPOPMAX`), and reply to it;
   - remove it from every other key's deque and from the timeout tree;
   - put it on the *unblocked* list.
4. A `BLMOVE` pushes into its destination, which can make that key ready; the
   loop continues until `readyKeys` is empty.
5. Before draining the event queue again, `cmd` runs the deferred commands of
   each unblocked client in unblocking order.

If a key is replaced by a value of another type, its waiters keep waiting.

### 2.4 Timeouts and disconnects

- The background scheduler pops expired entries from the timeout tree every
  slice; each such client gets `*-1`, is unblocked, and its deferred commands
  run.
- A client that disconnects while blocked is removed from every deque and from
  the tree.

### 2.5 Fairness

Waiters on one key are served first-come, first-served. A client waiting on
several keys is served by whichever becomes ready first.

---

### 2.6 Stream readers (2.1)

`XREAD BLOCK ms` and `XREADGROUP … BLOCK ms` wait in the same structures, with
the timeout in milliseconds ([15 §6](15-streams.md#6-blocking)). Three
differences from the pops:

- **They wait for an entry after a position, not for any change.** Every write
  to a stream wakes its readers, `XACK` and `XDEL` included, so a woken reader
  is served only if an entry after its position exists (a plain reader's, fixed
  when it blocked: `$` is the last ID then; a group reader's, the group's last
  delivered ID when it is served). Otherwise it keeps waiting, in its place.
- **A group reader whose stream is deleted, or whose group is destroyed, is
  answered at once** with `-UNBLOCKED the stream key no longer exists` or
  `-NOGROUP the consumer group this client was blocked on no longer exists`,
  as Redis 7 answers. A plain reader on a deleted stream keeps waiting for it
  to come back. So serving does not stop at a missing key while a stream
  reader is in the queue.
- **A group reader served later delivers as a read that did not wait**: the
  same code, and the same effects logged (D-34). A consumer it made when it
  blocked was logged then. A history read (`0`, not `>`) never waits.

A served reader gets only the stream that served it, `[[key, entries]]`, as in
Redis.

## 3. Transactions

### 3.1 `MULTI`

Sets the client's `MULTI` flag. Each subsequent command runs dispatch checks
1–7 ([02 §6](02-architecture.md#6-command-dispatch)) **at queue time**:

- pass → append `argv` to the queue, reply `+QUEUED`;
- fail (unknown command, arity, `NOAUTH`, `OOM`, `MISCONF`) → reply with the
  error and mark the transaction **aborted**.

Not allowed inside `MULTI`: nested `MULTI` (`-ERR MULTI calls can not be nested`),
`WATCH` (`-ERR WATCH inside MULTI is not allowed`), the subscribe commands.

### 3.2 `EXEC`

1. Aborted → `-EXECABORT Transaction discarded because of previous errors.`
2. Check watched keys for expiry (lazy expiry fires `signalModified`, see §3.3);
   if the client is **dirty** → `*-1`.
3. Otherwise write `*N` (N = number of queued commands — known in advance, since
   each produces exactly one reply) and run each command's handler in order.
   A runtime error such as `WRONGTYPE` becomes an error **element** of the array;
   the other commands still run. **There is no rollback.**
4. Effects are propagated wrapped in `MULTI` … `EXEC`, but only if at least one
   command changed something. A crash that truncates the AOF inside the
   wrapper discards the partial transaction on load
   ([08 §8](08-persistence.md#8-start-up-and-recovery)).
5. `serveReadyKeys()` runs after the whole transaction.
6. Clear the queue and all watches.

Atomicity is free: nothing else runs on the command thread between the first
and the last queued command.

### 3.3 `WATCH`

- `WATCH key…` (only outside `MULTI`) adds the client to
  `HashMap<ByteKey, ArrayList<Client>>` (key → watchers), and the key to
  `Client.watched`.
- `signalModified(key)` marks every watcher **dirty**. Because every
  modification, deletion, and expiry goes through that hook, nothing is missed.
- A watched key that has expired but not yet been reached by active expiry is
  caught at `EXEC` step 2.
- `FLUSHALL` marks dirty every client watching a key that exists, as Redis does (a `WATCH` on a missing key survives it).
- `UNWATCH`, `EXEC`, `DISCARD`, `RESET` and disconnect clear the client's
  watches.

### 3.4 `DISCARD`

Clears the queue and the `MULTI` flag, unwatches, replies `+OK`.

### 3.5 When to use what

| Need | Use |
|---|---|
| Several writes that must apply together | `MULTI` … `EXEC`, sent pipelined in one round trip |
| Compare-and-set one string | `J.CAS` ([09 §2](09-integration-patterns.md#2-extension-commands)) |
| Read several keys, decide, write | `WATCH` + `MULTI`/`EXEC` on a leased connection ([10 §7](10-client-library.md#7-transactions)); retry on `*-1` |
