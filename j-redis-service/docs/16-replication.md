# 16 — Replication (release 2.2)

## 1. Why, and how much

The backend runs on three machines, and each of its two stores, `session` and
`events`, is a single process holding state nothing else holds: every login's
session, and the match results not yet applied (backend
`docs/architecture/02-availability.md` §5). Losing the machine loses them, and
the backend's recovery plan (its D-8) is a scripted promotion of a replica, for
which there must be a replica. Until now replication was out of scope, because
production was one machine ([01](01-requirements-and-scope.md), C-4). Release
2.2 brings it in: one primary, one or more replicas on other machines, and a
promotion an operator triggers.

The load is modest. The `session` store takes a few thousand writes a second at
the backend's launch size, most of them small; the `events` store about 170
stream entries a second at design load, and holds up to a day of them, 1.6 GB
at launch and 7.8 GB at design load. A full copy of the larger one crosses a
gigabit link in about a minute. Correctness, and what happens at the edges
(a link that drops, a sync that is interrupted, a promotion done at night), matter
far more than throughput.

## 2. What is kept from Redis, and what is not

The shape is Redis's, so the reasoning an operator knows applies: a replica
connects to its primary and asks for a copy; the primary sends a point-in-time
image, then every change after it, in order; each side counts the bytes of that
change stream, so a replica that drops off briefly can ask to continue from
where it was; `WAIT` blocks until replicas have acknowledged a write. The
commands are Redis's where Redis has one (`REPLICAOF`, `ROLE`, `WAIT`, `PSYNC`,
`REPLCONF`), so that an operator's habits and the client's code carry over.

Where it differs, on purpose:

- **Only j-redis talks to j-redis.** The link's protocol is ours (the image is
  our base file, [08 §9](08-persistence.md#9-base-file-format)); a Redis replica
  cannot follow a j-redis primary, nor the reverse.
- **The words are primary and replica**, in commands' replies, `INFO` and the
  configuration (`primaryauth`, not `masterauth`).
- **An epoch fences promotions** (§9). Redis has none; its fencing lives in
  Sentinel, which j-redis does not have.
- **Replicas are always read-only and never chained** (§6, §13).

## 3. The shape

```
             clients (read and write)                  clients (read only)
                     │                                         │
            ┌────────▼─────────┐     the link: one TCP  ┌──────▼──────────┐
            │ primary          │ ◄──── connection the ──│ replica          │
            │ epoch 4          │       replica opens    │ epoch 4          │
            │ replid a1f…      │ ───── image, then ───► │ applied offset   │
            │ offset 91 204 …  │       the stream of    │ 91 204 …         │
            └──────────────────┘       effects          └──────────────────┘
                     │                                         │
                own AOF files                             own AOF files
```

- **One primary, any number of replicas**, each connected directly to the
  primary. A replica is made one by `replicaof <host> <port>` in its
  configuration, or the `REPLICAOF` command.
- **The replid** names one history of changes: a random 40-character id a
  primary takes when it starts, or when it is promoted. **The offset** is the
  number of bytes of that history's change stream so far. A replica that has
  applied everything up to offset _o_ of replid _r_ holds exactly the primary's
  data as it was at that point.
- **The epoch** counts promotions (§9). Every server has one, persisted in its
  data directory.
- **Both sides keep their own files.** A replica writes what it applies to its
  own AOF, so it restarts with its data, and a promoted replica has everything
  a primary needs.

## 4. The stream of changes

The stream a primary sends is **the AOF's effects** ([08 §3](08-persistence.md#3-logging-effects-not-commands)),
the same bytes: `EXPIRE k 60` travels as `PEXPIREAT k <absolute>`, an `SPOP` as
the `SREM` of the member it took, a key that expired as `DEL`, a transaction
wrapped in `MULTI` … `EXEC`. These are deterministic by construction, since the
AOF is replayed hours later; a replica applying them minutes, or milliseconds,
later gets the same result for the same reason (D-35).

Effects are encoded once per command batch into one immutable chunk, which today
goes to the AOF writer. From 2.2 the chunk is produced whether or not the AOF is
on, and goes to each consumer: the AOF writer, and the **backlog**, a ring
buffer of the last `repl-backlog-size` bytes of the stream (64 MB by default),
from which each online replica's connection is written. A chunk is never copied
again: the AOF writer, the backlog and the replicas hold the same bytes.

Two kinds of record travel on the link and are not effects: `PING`, every
`repl-ping-replica-period` (10 s), so a replica can tell a quiet primary from a
dead link, and `REPLCONF GETACK *`, when a `WAIT` is waiting (§8). They are part
of the stream and counted in the offset, but a replica applies nothing for them
and writes nothing to its AOF.

## 5. The full sync

### 5.1 The handshake

The replica opens the connection and sends, waiting for each reply:

```
AUTH <primaryauth>                 when primaryauth is set
REPLCONF listening-port <port>     for ROLE and INFO on the primary
REPLCONF epoch <its epoch>         refused if it is greater than the primary's (§9)
PSYNC <replid> <offset>            the bytes it has applied; PSYNC ? -1 when it holds no history
```

and the primary answers `PSYNC` with one of:

```
+FULLRESYNC <replid> <offset> <epoch>      then the image, then the stream from <offset>
+CONTINUE <replid> <epoch>                 then the stream from <offset> (§7)
```

### 5.2 The primary's side: a snapshot sent to the link

A full sync is the fork-free snapshot a rewrite takes ([08 §5](08-persistence.md#5-the-fork-free-snapshot)),
with its records sent to the replica's connection instead of a file (D-36):

1. Between two commands, the effects of the batch so far are closed into a
   chunk, so that the instant **T** falls between two chunks; the offset at T
   is the offset the `+FULLRESYNC` names.
2. The snapshot starts: the background scan writes every key once, and a key
   about to change is written first, as its pre-image. Streams are encoded on
   the writer thread from a frozen view (D-33 of 2.1). A writer thread sends the
   records to the connection, framed as `$EOF:<40 random characters>\r\n` …
   the base file's bytes … `<the same 40 characters>`, since the length is not
   known when sending starts. Its pace is the connection's: while Netty's
   buffer for it is full, the writer waits, and the scan waits for the writer,
   as it waits for the disk in a rewrite.
3. The effects after T wait in the backlog, which holds every chunk from T on:
   once the last byte of the image has been handed to the connection, they are
   sent from there, and the live stream follows.
4. If by then the backlog no longer holds T, because the write rate outran the
   transfer, the replica is dropped: it is logged, and the replica tries again.
   `repl-backlog-size` is what an operator raises.

One snapshot runs at a time, since a key's "already written" mark belongs to
one. The marks are numbered by the dataset itself, no longer by the AOF's
generation, since a sync takes a snapshot without starting a generation: shared
numbers would let the next rewrite take a key the sync had marked for written. A replica that asks while a rewrite runs is answered when it ends; a
rewrite due during a sync starts when the sync's snapshot is done. Replicas
that ask before a sync's snapshot starts share it.

### 5.3 The replica's side: to disk, then adopted as its base

The replica writes the image to a file in its data directory as it arrives,
never holding it in memory, and checks the base format's CRC at its end. Then,
on its command thread (D-36):

1. With persistence on, the file becomes the replica's new base: the AOF writer
   switches to a new generation whose manifest is `[base.(g+1), incr.(g+1)]`,
   the received file renamed to `base.(g+1).jrdb`, the old files deleted, as a
   rewrite's commit does. Until that manifest is in place, a crash leaves the
   old data on disk, and the replica simply syncs again.
2. The dataset is emptied and the base loaded, as at start-up
   ([08 §8](08-persistence.md#8-start-up-and-recovery)). **This blocks the
   replica's command thread** for the time a start-up load takes: its clients
   wait, as clients of a starting server do.
3. The stream is then applied (§6), and each effect is written to the new incr
   file.

With persistence off, the file is loaded and deleted.

An interrupted sync (the link drops, the CRC is wrong, the disk is full) leaves
the replica with its previous data, serving reads, and it tries again.

## 6. Applying the stream on a replica

The link is read on its own thread; each record is decoded there and handed to
the command thread, which applies it the way the AOF loader replays a file:
through the normal command handlers, no reply, and nothing deleted on the
grounds of time. Unlike the loader, it writes each effect to the replica's own
AOF, and it tells `WATCH` and blocking readers that a key changed. A
`MULTI` … `EXEC` in the stream is applied whole at `EXEC`.

Four rules make a replica what it is (D-38):

- **Read-only.** A write command from a client is refused with
  `-READONLY You can't write against a read only replica.` The link's commands
  are the only writes. A blocking pop is a write, so it is refused too;
  `XREAD BLOCK` is a read, and is woken by entries arriving on the link.
- **It never expires keys itself.** A key whose time has passed is hidden from
  reads, as if absent, but stays until the primary's `DEL` arrives; active
  expiry does not run. Otherwise a replica's clock would decide what the
  primary still holds. `SCAN` and `KEYS` may still list such a key, as on Redis.
- **A command that throws while applying stops the replica**, as a corrupt AOF
  would: it would otherwise serve data the primary does not hold. It restarts,
  loads its own files and syncs again. One refused with an error reply is
  logged and counted (`replica_apply_errors`), as the loader counts replay
  errors: effects apply to the state they came from, so a count above zero is a
  fault to report.
- **Offsets advance by what was applied.** The replica reports the offset it
  has applied, not the one it has received, so a `WAIT` counts a write only once
  it is in the replica's dataset.

## 7. Continuing after a short drop

A replica that loses its link keeps its replid and offset and, once it
reconnects, sends `PSYNC <replid> <offset>`. Offsets count from 0: the offset
is both the number of bytes applied and the position of the first one missing. The primary answers
`+CONTINUE` if the replid is its own and every byte from that offset is still
in the backlog, and sends from there; otherwise `+FULLRESYNC`. At 64 MB and the
`session` store's write rate, the backlog covers minutes of disconnection.

Not kept across restarts, in 2.2: a primary that restarts takes a new replid,
and a replica that restarts has no offset, so either restart costs a full sync.
Keeping them needs the offset recorded in step with the AOF, which is left out
(§13).

## 8. Acknowledgements, `WAIT`, and refusing writes without a replica

Each replica sends `REPLCONF ACK <applied offset>` every second, and at once
when the stream carries `REPLCONF GETACK *`. The primary records it per replica
and shows the lag in `INFO` and `ROLE`.

**`WAIT <numreplicas> <timeout ms>`** blocks the calling client until that many
replicas have acknowledged the stream's offset when it ran, which covers its last
write, or the timeout passes (0: no timeout), and replies with how many had. It
asks the replicas where they are (`REPLCONF GETACK *`) rather than waiting for
their next second. Refused on a replica, and inside `MULTI`. It is how a caller makes one write
survive the loss of the primary's machine: the backend's arena can delete a
result from its disk spool only once `WAIT 1` says a replica holds it, if the
backend decides so.

**`min-replicas-to-write <n>`** with **`min-replicas-max-lag <s>`** makes a
primary refuse writes (`-NOREPLICAS Not enough good replicas to write.`) while
fewer than _n_ replicas have acknowledged within _s_ seconds. It bounds how long
a primary cut off from its replica keeps accepting writes that a promotion on
the other side would lose, at the price of refusing writes whenever the replica
is down. Off by default (0): which store trades which way is the backend's
decision.

## 9. Promotion and epochs

j-redis does not elect a primary: an operator, or a script an operator runs,
decides (backend D-8). The epoch is what keeps a mistake in that procedure from
losing data (D-37).

- **`REPLICAOF NO ONE`** promotes a replica: it drops the link, takes a new
  replid, **increments its epoch and writes it to disk before replying**, and
  accepts writes from then on.
- **A server never syncs from a primary with a lower epoch.** The replica sends
  its epoch in the handshake; a primary whose own is lower refuses with
  `-EPOCH`, and the replica stays as it is, read-only, its data untouched,
  logging an error each retry. This is the case of the promoted replica
  mistakenly pointed back at the old primary, or restarted with a configuration
  that still says `replicaof old-primary`: without the epoch, the full sync
  would replace the newer data with the older.
- **A replica takes its primary's epoch** at each sync, so an old primary
  demoted by `REPLICAOF new-primary` syncs normally and carries the new epoch
  from then on.
- **Clients remember the highest epoch they have seen** (§10) and never write
  to a primary with a lower one: an old primary that comes back on its own, still
  believing it is the primary, is ignored by every client that saw the
  promotion.

The epoch is kept in `<dir>/replication`, a small text file replaced atomically,
which [08](08-persistence.md)'s leftover cleaning leaves alone. With
`appendonly no` there is no data directory to keep it in, and it starts at 0
with the process; such a server should not be promoted.

**The role at start-up comes from the configuration file**, since j-redis has
no `CONFIG REWRITE`: a promotion script must edit the file as well as send
`REPLICAOF NO ONE`, or the next restart makes the server a replica again. The
epoch makes that mistake fail safe: the restarted server refuses to sync from
the older primary and serves its data read-only until the file is fixed.

What the store cannot prevent is two primaries, each with clients that cannot
see the other: an old primary cut off by the network keeps serving the clients
on its side. The promotion procedure fences it (stops it, or firewalls it)
before promoting; `min-replicas-to-write` bounds how long it can accept writes
if that step is missed.

## 10. Clients following the primary

The Java client takes several addresses (`JRedisClient.builder().addresses(…)`).
To connect, and again after any connection is lost or any reply is `-READONLY`,
it asks each address `ROLE` and uses the primary with the highest epoch, never
one lower than the highest it has seen. A replica's epoch counts too: a replica
at epoch 5 follows a primary at 5, so a primary at 4 the client can reach is a
stale one. Each of its connections (the shared
one, blocking ones, pub/sub) finds the primary the same way, and pub/sub
subscriptions are made again on it, as after any reconnect. With one address it
behaves as in 2.1.

**A subscriber is made to look too (2.2.1, D-39).** Losing the connection and
`-READONLY` are the only two signs, and a subscriber's connection sees neither:
it is sent no replies, and a server demoted by `REPLICAOF` kept every client
but its replicas. So a subscriber stayed on the demoted server, and heard
nothing, since what is published on the new primary does not cross to it; and
on a primary whose machine was lost it waited for TCP keepalive, about two
hours by Linux's defaults, since it sent nothing that could time out. The
backend found it in its second audit (its defect O-6). Now:

- **A server made a replica closes the connections no `-READONLY` will
  reach**: a subscriber's, and a client's blocked waiting for data, which on a
  replica could even be served by a replicated write. Each looks for the
  primary again as after any drop. Every other client is told at its first
  write, as before.
- **The client sends `PING` on a subscriber's connection** every
  `pubsubPingMillis` (5 s), a request like any other, so the rule that already
  closes a stuck command connection (a request overdue by three timeouts)
  closes this one too: a lost machine is noticed within about 11 s, and the
  primary looked for. The server answers a subscribed connection's `PING` as
  `["pong", ""]`, which is not mistaken for a message.

**What one connection finds, the others follow (2.2.1, D-40).** A connection
told nothing stays: after a handover the demoted server closed the backend
gateway's subscriber, which found the new primary at once, while the gateway's
command connection stayed on the demoted server until its next writes were
refused, and the idle leases `WATCH` needs stayed until their next transaction
was refused at `EXEC`. Each connection now records the epoch its client had
seen when it found its primary. A connection whose search raises it closes
every other connection of the client found under a lower one, which look for the
primary again at once; and the pool closes an idle lease with a lower epoch
rather than lend it. The first connection to learn still learns by a drop or a
`-READONLY`.

## 11. Failures, and what each costs

| What happens | What the store does | Cost |
|---|---|---|
| The link drops for seconds | The replica reconnects every second and continues from its offset | Nothing, if within the backlog; a full sync otherwise |
| The replica restarts | Loads its own files, serves them read-only, syncs in full | One full sync |
| The primary restarts | Keeps its data (AOF); new replid | One full sync per replica |
| The primary's machine is lost | Replicas keep serving reads and retrying | Writes until an operator promotes; what the replica had not received is lost unless the caller `WAIT`ed. Subscribers notice within about 11 s (§10) |
| The old primary is demoted (`REPLICAOF`) | Closes its subscribers' and blocked clients' connections | Each looks for the primary again; what is published meanwhile is lost (pub/sub is at most once) |
| A replica is slow, or its output passes the limit | Dropped; it reconnects | A partial or full sync |
| Writes outrun a full sync | The backlog loses the image's instant; the replica is dropped | It tries again; raise `repl-backlog-size` |
| A full sync is interrupted | The replica keeps its previous data | It tries again |
| The replica's disk fills during a sync | The sync fails; its previous data stays | Retried every few seconds, logged |
| Applying a record fails on the replica | The replica stops, restarts, syncs in full | Seconds of no replica |
| The promoted server is pointed at the old one | Refused by epoch | Nothing lost; an error until corrected |
| Two primaries, each with its own clients | Not prevented by the store | Bounded by fencing and `min-replicas-to-write` (§9) |

## 12. Configuration, commands, `INFO`

| Directive | Default | Meaning |
|---|---|---|
| `replicaof <host> <port>` | none | Start as a replica of that primary |
| `primaryauth <password>` | none | Password the replica sends to its primary |
| `repl-backlog-size` | 64mb | The ring of recent stream bytes for `CONTINUE` |
| `repl-timeout` | 60 | Seconds without a byte before either side drops the link |
| `repl-ping-replica-period` | 10 | Seconds between the primary's `PING`s on the link |
| `client-output-buffer-limit replica` | 256mb 64mb 60 | As for the other classes, for a replica's connection. During a full sync the image's writer waits at 4 MB unsent, so only a limit below that drops a replica then |
| `min-replicas-to-write`, `min-replicas-max-lag` | 0, 10 | §8 |

Commands: `REPLICAOF <host> <port>` and `REPLICAOF NO ONE` (admin);
`ROLE`; `WAIT`; `PSYNC` and `REPLCONF` (the link's own); `DEBUG DIGEST`, a
40-character digest of the whole dataset that two servers holding the same data
give alike whatever their order in memory, for tests and for an operator
checking a replica.

`ROLE` replies, epoch last:

```
primary: ["primary", <offset>, [[<host>, <port>, <acked offset>], …], <epoch>]
replica: ["replica", <primary host>, <primary port>, <state>, <applied offset>, <epoch>]
```

`INFO replication` gives `role`, `replication_epoch`, `replid`, `repl_offset`,
the backlog's size and first offset, and per replica its address, state,
acknowledged offset and lag in seconds; on a replica, the primary's address,
`primary_link_status` (up or down), seconds since its last byte, whether a
sync is in progress and the last sync's error.

## 13. Left out, on purpose

| Left out | Why |
|---|---|
| Automatic failover, Sentinel, Cluster | Promotion is the operator's (backend D-8): automating it needs consensus |
| Chained replicas (a replica of a replica) | One replica per store is the backend's topology |
| Writable replicas | A write the primary never sees is lost at the next sync |
| Continuing after a restart | Needs the offset recorded in step with the AOF; a full sync costs about a minute at the largest size |
| Pub/sub messages crossing to replicas | Not data; the backend's subscribers use the primary |
| Loading a new image while serving the old one | The replica's command thread blocks while loading (§5.3) |
| TLS on the link | As for clients: a private network ([01](01-requirements-and-scope.md#6-out-of-scope)) |

## 14. Parts, and how each is proved

| Part | What | Proved by |
|---|---|---|
| (a) | This design; decisions D-35 to D-38; C-4 revised | Review against the code it relies on |
| (b) | The effect stream apart from the AOF: one chunk per batch for any consumer; the backlog and offsets; `DEBUG DIGEST` | The persistence tests unchanged; the backlog's wrap and offsets; digests equal for equal data in any order, different for any difference. **Built 2026-09-29**: the backlog holds exactly the AOF's bytes, and nothing from before it started; mutation-checked, 20 of 21 caught once five tests were added, the last equivalent and its code removed |
| (c) | The full sync and the stream: `PSYNC` on the primary, `REPLICAOF` and the link on the replica, the image adopted as its base, the replica's rules, `ROLE`, `INFO` | Two servers in one test: every type, streams with groups, TTLs; writes during the sync applied once (digests equal); transactions whole; expiry hidden, then deleted by the primary; `READONLY`; a replica restarted alone keeps its data; a random workload, digests compared. **Built 2026-09-29**, with a fake replica that asks and never reads to hold a sync open: rewrites refused and `FLUSHALL` ending it; a backlog outrun; a link cut and joined again; the end mark cut at every point |
| (d) | `CONTINUE` from the backlog, `ACK`, timeouts and `PING`, `WAIT` | A dropped link continues without a snapshot; past the backlog it syncs in full; `WAIT` counts; a silent link is dropped. **Built 2026-09-29**: both sides' offsets agree; ten `WAIT`s answered in well under the second an unasked acknowledgement would take; a replica that never acknowledges dropped, a primary that never speaks left and tried again, a quiet link kept up by `PING`s. Mutation-checked, 18 of 18 once waiting clients showed in `INFO` |
| (e) | Epochs, `REPLICAOF NO ONE`, `min-replicas-to-write` | The epoch persisted across a restart; a lower-epoch primary refused; the old primary demoted syncs from the new; writes refused without a replica. **Built 2026-09-29**: the promoted server pointed back at the old one keeps its newer data and says why; a replica connected but past the lag does not count. Found: a delivery's logged time was capped at the replica's clock (backend D-28) |
| (f) | The client following the primary; the CLI and tools; 2.2.0 released | A primary killed with `kill -9` under a workload, the replica promoted: every write that `WAIT` confirmed is there, and the client finds the new primary on its own. **Client and crash test built 2026-09-29**: server processes, 174 writes confirmed before the kill and none lost, 110 written after the promotion by the client on its own, the old primary restarted as a replica following the new to the same digest. Mutation-checked, 7 of 7 once a replica's epoch was tested |

Each part is mutation-checked, as 2.1's were.
