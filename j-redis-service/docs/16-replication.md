# 16 — Replication (release 2.2)

This page describes replication, which release 2.2 added: a primary server and
one or more replicas that hold a copy of its data. It is for engineers who
change the replication code, and for operators who run a replica, promote it or
plan for failures. It covers why replication was added, what is kept from
Redis, the shape of a primary and its replicas, the stream of changes, the full
sync, how a replica applies changes, continuing after a short drop, `WAIT` and
refusing writes without a replica, promotion and epochs, how clients follow
the primary, what each failure costs, the configuration, what is left out, and
how each part was proved. The steps an operator follows are in
[guide 4 §4.12](guide/04-run-and-operate.md#412-replication-a-primary-and-a-replica-22).

Terms used on this page:

| Term | Meaning |
|---|---|
| Primary | The server that accepts writes |
| Replica | A read-only server that holds a copy of the primary's data and follows its changes |
| Link | The TCP connection that a replica opens to its primary |
| Full sync | The primary sends a whole point-in-time image of its data, then every change after it |
| Backlog | A ring buffer on the primary that holds the most recent bytes of the change stream |
| Offset | The number of bytes of the change stream so far |
| Promotion | Making a replica the primary (`REPLICAOF NO ONE`) |
| Epoch | A number that counts promotions (§9) |

## 1. Why, and how much

The backend's design runs on three machines. Each of its two stores, `session`
and `events`, is a single process that holds state nothing else holds: every
login's session, and the match results not yet applied (the backend's
`docs/architecture/02-availability.md` §1 and §3). Losing the machine loses that
state. The backend's recovery plan (its D-8) is a scripted promotion of a
replica, and for that there must be a replica.

Before 2.2, replication was out of scope, because production was one machine
([01](01-requirements-and-scope.md), C-4). Release 2.2 adds it: one primary,
one or more replicas on other machines, and a promotion that an operator
triggers. (The site still launches on one machine, where the stores have no
replica: the backend's D-93. Its `docs/operations/10-two-machines.md` adds a
second machine as a standby, with replicas of both stores.)

The load is modest:

- The `session` store takes a few thousand writes a second at the backend's
  launch size, most of them small.
- The `events` store takes about 170 stream entries a second at design load.
  It holds up to a day of them: 1.6 GB at launch, and 7.8 GB at design load.
- A full copy of the larger store crosses a gigabit link in about a minute.

Correctness, and what happens at the edges (a link that drops, a sync that is
interrupted, a promotion done at night), matter far more than throughput.

## 2. What is kept from Redis, and what is not

The shape is Redis's, so the reasoning an operator knows applies:

- a replica connects to its primary and asks for a copy;
- the primary sends a point-in-time image, then every change after it, in
  order;
- each side counts the bytes of that change stream, so a replica that drops
  off briefly can ask to continue from where it was;
- `WAIT` blocks until replicas have acknowledged a write.

The commands are Redis's where Redis has one (`REPLICAOF`, `ROLE`, `WAIT`,
`PSYNC`, `REPLCONF`), so that an operator's habits and the client's code carry
over.

Where j-redis differs, it does so on purpose:

- **Only j-redis talks to j-redis.** The link's protocol is j-redis's own (the
  image is j-redis's base file, [08 §9](08-persistence.md#9-base-file-format)).
  A Redis replica cannot follow a j-redis primary, nor the reverse.
- **The words are "primary" and "replica"**, in the commands' replies, in `INFO`
  and in the configuration (`primaryauth`, not `masterauth`).
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

- **One primary, any number of replicas.** Each replica connects directly to
  the primary. A server becomes a replica through `replicaof <host> <port>` in
  its configuration, or through the `REPLICAOF` command.
- **The replid** names one history of changes. It is a random 40-character id
  that a primary takes when it starts, or when it is promoted.
- **The offset** is the number of bytes of that history's change stream so far.
  A replica that has applied everything up to offset _o_ of replid _r_ holds
  exactly the primary's data as it was at that point.
- **The epoch** counts promotions (§9). Every server has one, kept in its data
  directory.
- **Both sides keep their own files.** A replica writes what it applies to its
  own AOF (append-only file). So it restarts with its data, and a promoted
  replica has everything a primary needs.

## 4. The stream of changes

The stream a primary sends is **the AOF's effects**, the same bytes
([08 §3](08-persistence.md#3-logging-effects-not-commands)). For example,
`EXPIRE k 60` travels as `PEXPIREAT k <absolute>`, an `SPOP` as the `SREM` of
the member it took, a key that expired as `DEL`, and a transaction wrapped in
`MULTI` … `EXEC`. These effects are deterministic by construction, because the
AOF is replayed hours later. A replica that applies them minutes, or
milliseconds, later gets the same result for the same reason (D-35).

The server encodes the effects of each batch of commands once, into one
immutable chunk. Before 2.2 the chunk went only to the AOF writer. Since 2.2,
the server produces the chunk whether or not the AOF is on, and gives it to
each consumer:

- the AOF writer;
- the **backlog**: a ring buffer of the last `repl-backlog-size` bytes of the
  stream (64 MB by default). Each online replica's connection is written from
  the backlog.

A chunk is never copied again: the AOF writer, the backlog and the replicas
hold the same bytes.

Two kinds of record travel on the link but are not effects:

- `PING`, every `repl-ping-replica-period` (10 s), so that a replica can tell a
  quiet primary from a dead link;
- `REPLCONF GETACK *`, when a `WAIT` is waiting (§8).

They are part of the stream and counted in the offset. A replica applies
nothing for them, and writes nothing to its AOF.

## 5. The full sync

### 5.1 The handshake

The replica opens the connection and sends these commands, waiting for each
reply:

```
AUTH <primaryauth>                 when primaryauth is set
REPLCONF listening-port <port>     for ROLE and INFO on the primary
REPLCONF epoch <its epoch>         refused if it is greater than the primary's (§9)
PSYNC <replid> <offset>            the bytes it has applied; PSYNC ? -1 when it holds no history
```

The primary answers `PSYNC` with one of:

```
+FULLRESYNC <replid> <offset> <epoch>      then the image, then the stream from <offset>
+CONTINUE <replid> <epoch>                 then the stream from <offset> (§7)
```

### 5.2 The primary's side: a snapshot sent to the link

A full sync is the fork-free snapshot that a rewrite takes
([08 §5](08-persistence.md#5-the-fork-free-snapshot)), with its records sent to
the replica's connection instead of a file (D-36):

1. Between two commands, the server closes the effects of the batch so far
   into a chunk, so that the instant **T** falls between two chunks. The offset
   at T is the offset that the `+FULLRESYNC` names.
2. The snapshot starts. The background scan writes every key once, and a key
   that is about to change is written first, as its pre-image. Streams are
   encoded on the writer thread from a frozen view (D-33 of 2.1).
   - A writer thread sends the records to the connection, framed as
     `$EOF:<40 random characters>\r\n`, then the base file's bytes, then
     `<the same 40 characters>`. This framing is used because the length is
     not known when sending starts.
   - The writer goes at the connection's pace. While more than 4 MB sent to a
     replica is still unsent by Netty, the writer waits, and the scan waits for
     the writer, as it waits for the disk in a rewrite.
   - A replica that takes no bytes for 60 s during its full sync is given up,
     and logged.
3. The effects after T wait in the backlog, which holds every chunk from T on.
   Once the last byte of the image has been handed to the connection, they are
   sent from there, and the live stream follows.
4. If by then the backlog no longer holds T, because the write rate outran the
   transfer, the replica is dropped. The primary logs it, and the replica tries
   again. `repl-backlog-size` is what an operator raises.

**One snapshot at a time.** Only one snapshot runs at a time, because a key's
"already written" mark belongs to one snapshot. The marks are numbered by the
dataset itself, no longer by the AOF's generation, because a sync takes a
snapshot without starting a generation. Shared numbers would let the next
rewrite take a key that the sync had marked as written. So:

- a replica that asks while a rewrite runs is answered when the rewrite ends;
- a rewrite that falls due during a sync starts when the sync's snapshot is
  done;
- replicas that ask before a sync's snapshot starts share that snapshot.

### 5.3 The replica's side: to disk, then adopted as its base

The replica writes the image to a file in its data directory as it arrives. It
never holds the image in memory, and it checks the base format's CRC (cyclic
redundancy check) at the end. Then, on its command thread (D-36):

1. With persistence on, the file becomes the replica's new base. The AOF writer
   switches to a new generation whose manifest is `[base.(g+1), incr.(g+1)]`:
   the received file is renamed to `base.(g+1).jrdb`, and the old files are
   deleted, as a rewrite's commit does. Until that manifest is in place, a
   crash leaves the old data on disk, and the replica simply syncs again.
2. The replica empties its dataset and loads the base, as at start-up
   ([08 §8](08-persistence.md#8-start-up-and-recovery)). **This blocks the
   replica's command thread** for as long as a start-up load takes: its clients
   wait, as the clients of a starting server do.
3. The replica then applies the stream (§6), and writes each effect to the new
   incr file.

With persistence off, the replica loads the file and deletes it.

An interrupted sync (the link drops, the CRC is wrong, the disk is full) leaves
the replica with its previous data. It keeps serving reads, and it tries again.

## 6. Applying the stream on a replica

The replica reads the link on its own thread. It decodes each record there and
hands it to the command thread. The command thread applies the record the way
the AOF loader replays a file: through the normal command handlers, with no
reply, and with nothing deleted because of the time. Unlike the loader, it
writes each effect to the replica's own AOF, and it tells `WATCH` and blocking
readers that a key changed. A `MULTI` … `EXEC` in the stream is applied whole,
at `EXEC`.

Four rules make a replica what it is (D-38):

- **Read-only.** The replica refuses a write command from a client with
  `-READONLY You can't write against a read only replica.` The link's commands
  are the only writes. A blocking pop is a write, so it is refused too.
  `XREAD BLOCK` is a read, and entries that arrive on the link wake it.
- **It never expires keys itself.** A key whose time has passed is hidden from
  reads, as if it were absent, but it stays until the primary's `DEL` arrives.
  Active expiry does not run. Otherwise the replica's clock would decide what
  the primary still holds. `SCAN` and `KEYS` may still list such a key, as on
  Redis.
- **A command that throws while it is applied stops the replica**, as a corrupt
  AOF would. Otherwise the replica would serve data that the primary does not
  hold. The replica restarts, loads its own files and syncs again. A command
  refused with an error reply is logged and counted (`replica_apply_errors`),
  as the loader counts replay errors. Effects apply to the state they came
  from, so a count above zero is a fault to report.
- **Offsets advance by what was applied.** The replica reports the offset it
  has applied, not the one it has received. So a `WAIT` counts a write only
  once the write is in the replica's dataset.

## 7. Continuing after a short drop

A replica that loses its link keeps its replid and offset. Once it reconnects,
it sends `PSYNC <replid> <offset>`. Offsets count from 0, so the offset is both
the number of bytes applied and the position of the first byte missing.

The primary answers `+CONTINUE`, and sends from that offset, if the replid is
its own and every byte from that offset is still in the backlog. Otherwise it
answers `+FULLRESYNC`. At 64 MB and the `session` store's write rate, the
backlog covers minutes of disconnection.

**Not kept across restarts, in 2.2.** A primary that restarts takes a new
replid, and a replica that restarts has no offset. So either restart costs a
full sync. Keeping them would need the offset recorded in step with the AOF,
which is left out (§13).

## 8. Acknowledgements, `WAIT`, and refusing writes without a replica

Each replica sends `REPLCONF ACK <applied offset>` every second, and at once
when the stream carries `REPLCONF GETACK *`. The primary records it for each
replica, and shows the lag in `INFO` and `ROLE`.

**`WAIT <numreplicas> <timeout ms>`** blocks the calling client until one of
two things happens:

- that many replicas have acknowledged the stream's offset at the moment
  `WAIT` ran, which covers the client's last write; or
- the timeout passes (0 means no timeout).

It replies with the number of replicas that had acknowledged. It asks the
replicas where they are (`REPLCONF GETACK *`), rather than waiting for their
next acknowledgement a second later. `WAIT` is refused on a replica, and inside
`MULTI`. `WAIT` is how a caller makes one write survive the loss of the
primary's machine. For example, the backend's arena could delete a result from
its disk spool only once `WAIT 1` says a replica holds it, if the backend
decides so.

**`min-replicas-to-write <n>`** with **`min-replicas-max-lag <s>`** makes a
primary refuse writes (`-NOREPLICAS Not enough good replicas to write.`) while
fewer than _n_ replicas have acknowledged within _s_ seconds. This bounds how
long a primary cut off from its replica keeps accepting writes that a promotion
on the other side would lose. The price is that the primary refuses writes
whenever the replica is down. It is off by default (0): which store makes which
trade is the backend's decision.

## 9. Promotion and epochs

j-redis does not elect a primary. An operator, or a script that an operator
runs, decides (backend D-8). The epoch keeps a mistake in that procedure from
losing data (D-37).

- **`REPLICAOF NO ONE`** promotes a replica. The replica drops the link, takes
  a new replid, **increments its epoch and writes it to disk before it
  replies**, and accepts writes from then on.
- **A server never syncs from a primary with a lower epoch.** The replica sends
  its epoch in the handshake. A primary whose own epoch is lower refuses with
  `-EPOCH`. The replica then stays as it is: read-only, its data untouched,
  logging an error at each retry. This covers a promoted replica that is
  pointed back at the old primary by mistake, or that restarts with a
  configuration that still says `replicaof old-primary`. Without the epoch, the
  full sync would replace the newer data with the older data.
- **A replica takes its primary's epoch** at each sync. So an old primary that
  is demoted by `REPLICAOF new-primary` syncs normally, and carries the new
  epoch from then on.
- **Clients remember the highest epoch they have seen** (§10), and never write
  to a primary with a lower one. An old primary that comes back on its own,
  still believing it is the primary, is ignored by every client that saw the
  promotion.

**Where the epoch is kept.** The epoch is kept in `<dir>/replication`, a small
text file replaced atomically. The leftover cleaning of
[08](08-persistence.md) leaves it alone. With `appendonly no` there is no data
directory to keep it in, so the epoch starts at 0 with the process; such a
server should not be promoted.

**The role at start-up comes from the configuration file**, because j-redis has
no `CONFIG REWRITE`. So a promotion script must edit the file as well as send
`REPLICAOF NO ONE`, or the next restart makes the server a replica again. The
epoch makes that mistake fail safe: the restarted server refuses to sync from
the older primary, and serves its data read-only until the file is fixed.

**What the store cannot prevent** is two primaries, each with clients that
cannot see the other. An old primary cut off by the network keeps serving the
clients on its side. The promotion procedure fences it (stops it, or blocks it
with a firewall) before it promotes the replica. `min-replicas-to-write` bounds
how long the old primary can accept writes if that step is missed.

## 10. Clients following the primary

The Java client takes several addresses (`JRedisClient.builder().addresses(…)`).
To connect, and again after any connection is lost or any reply is
`-READONLY`, it asks each address `ROLE`. It uses the primary with the highest
epoch, and never one lower than the highest epoch it has seen.

A replica's epoch counts too. A replica at epoch 5 follows a primary at 5, so a
primary at 4 that the client can reach is a stale one.

Each of the client's connections (the shared one, the blocking ones, pub/sub)
finds the primary the same way, and the client makes its pub/sub subscriptions
again on it, as after any reconnect. With one address, the client behaves as in
2.1.

**A subscriber is made to look too (2.2.1, D-39).** Before 2.2.1, there were
only two signs that the primary had changed: losing the connection, and a
`-READONLY` reply. A subscriber's connection sees neither. It is sent no
replies, and a server demoted by `REPLICAOF` kept every client except its
replicas. So a subscriber stayed on the demoted server, and heard nothing,
because what is published on the new primary does not cross to it. And on a
primary whose machine was lost, a subscriber waited for TCP keepalive, about
two hours with Linux's defaults, because it sent nothing that could time out.
The backend found this in its second audit (its defect O-6). Now:

- **A server that is made a replica closes the connections that no
  `-READONLY` will reach**: every subscriber's, and every client's that is
  blocked waiting for data (on a replica, a replicated write could even serve
  such a client). Each one looks for the primary again, as after any drop.
  Every other client is told at its first write, as before.
- **The client sends `PING` on a subscriber's connection** every
  `pubsubPingMillis` (5 s). The `PING` is a request like any other, so the
  rule that already closes a stuck command connection (a request overdue by
  three timeouts) closes this one too. So a lost machine is noticed within
  about 11 s, and the client looks for the primary. The server answers a
  subscribed connection's `PING` as `["pong", ""]`, which the client does not
  mistake for a message.

**What one connection finds, the others follow (2.2.1, D-40).** Before 2.2.1, a
connection that was told nothing stayed where it was. After a handover, the
demoted server closed the backend gateway's subscriber, which found the new
primary at once. But the gateway's command connection stayed on the demoted
server until its next writes were refused. The idle leases that `WATCH` needs
stayed until their next transaction was refused at `EXEC`. Now:

- each connection records the epoch its client had seen when it found its
  primary;
- a connection whose search raises the epoch closes every other connection of
  the client that was found under a lower epoch, and those look for the primary
  again at once;
- the lease pool closes an idle lease with a lower epoch, rather than lend it.

The first connection to learn of the change still learns by a drop or by a
`-READONLY`.

## 11. Failures, and what each costs

| What happens | What the store does | Cost |
|---|---|---|
| The link drops for seconds | The replica reconnects every second and continues from its offset | Nothing, if the backlog still holds the offset; a full sync otherwise |
| The replica restarts | It loads its own files, serves them read-only, and syncs in full | One full sync |
| The primary restarts | It keeps its data (AOF) and takes a new replid | One full sync per replica |
| The primary's machine is lost | The replicas keep serving reads and retrying | No writes until an operator promotes a replica. What the replica had not received is lost, unless the caller used `WAIT`. Subscribers notice within about 11 s (§10) |
| The old primary is demoted (`REPLICAOF`) | It closes its subscribers' and blocked clients' connections | Each looks for the primary again. What is published meanwhile is lost (pub/sub delivers at most once) |
| A replica is slow, or its output passes the limit | The primary drops it; it reconnects | A partial or full sync |
| Writes outrun a full sync | The backlog loses the image's instant; the primary drops the replica | The replica tries again; raise `repl-backlog-size` |
| A full sync is interrupted | The replica keeps its previous data | It tries again |
| The replica's disk fills during a sync | The sync fails; the previous data stays | Retried every few seconds, and logged |
| Applying a record fails on the replica | The replica stops, restarts and syncs in full | Seconds without a replica |
| The promoted server is pointed at the old primary | The old primary refuses it, by epoch | Nothing is lost; an error until someone corrects it |
| Two primaries, each with its own clients | The store does not prevent this | Bounded by fencing and `min-replicas-to-write` (§9) |

## 12. Configuration, commands, `INFO`

| Directive | Default | Meaning |
|---|---|---|
| `replicaof <host> <port>` | none | Start as a replica of that primary |
| `primaryauth <password>` | none | The password the replica sends to its primary |
| `repl-backlog-size` | 64mb | The ring of recent stream bytes, for `CONTINUE`; 16kb to 2gb |
| `repl-timeout` | 60 | Seconds without a byte before either side drops the link |
| `repl-ping-replica-period` | 10 | Seconds between the primary's `PING`s on the link |
| `client-output-buffer-limit replica` | 256mb 64mb 60 | As for the other client classes, for a replica's connection. During a full sync, the image's writer waits at 4 MB unsent, so only a limit below that drops a replica then |
| `min-replicas-to-write`, `min-replicas-max-lag` | 0, 10 | §8 |

`replicaof`, `repl-backlog-size` are fixed at start-up; the others can be
changed with `CONFIG SET` ([11 §2](11-operations-and-security.md#2-configuration)).

**Commands:**

| Command | Use |
|---|---|
| `REPLICAOF <host> <port>`, `REPLICAOF NO ONE` | Make this server a replica, or a primary again (admin) |
| `ROLE` | The server's role, offsets and epoch (below) |
| `WAIT` | Wait for replicas to acknowledge (§8) |
| `PSYNC`, `REPLCONF` | The link's own |
| `DEBUG DIGEST` | A 40-character digest of the whole dataset. Two servers that hold the same data give the same digest, whatever their order in memory. For tests, and for an operator who checks a replica (needs `enable-debug-command yes`) |

`ROLE` replies, with the epoch last:

```
primary: ["primary", <offset>, [[<host>, <port>, <acked offset>], …], <epoch>]
replica: ["replica", <primary host>, <primary port>, <state>, <applied offset>, <epoch>]
```

`INFO replication` gives `role`, `replication_epoch`, `replid`, `repl_offset`,
the backlog's size and first offset, and for each replica its address, state,
acknowledged offset and lag in seconds. On a replica, it gives the primary's
address, `primary_link_status` (up or down), the seconds since the link's last
byte, whether a sync is in progress, and the last sync's error. The full list
of fields is in [11 §6](11-operations-and-security.md#6-info).

## 13. Left out, on purpose

| Left out | Why |
|---|---|
| Automatic failover, Sentinel, Cluster | Promotion is the operator's decision (backend D-8): automating it needs consensus |
| Chained replicas (a replica of a replica) | One replica per store is the backend's topology |
| Writable replicas | A write that the primary never sees is lost at the next sync |
| Continuing after a restart | It needs the offset recorded in step with the AOF; a full sync costs about a minute at the largest size |
| Pub/sub messages crossing to replicas | Messages are not data; the backend's subscribers use the primary |
| Loading a new image while serving the old one | The replica's command thread blocks while it loads (§5.3) |
| TLS on the link | As for clients: a private network ([01](01-requirements-and-scope.md#6-out-of-scope)) |

## 14. Parts, and how each is proved

Release 2.2 was built in six parts, (a) to (f).

| Part | What | Proved by |
|---|---|---|
| (a) | This design; decisions D-35 to D-38; C-4 revised | Review against the code it relies on |
| (b) | The effect stream apart from the AOF: one chunk per batch for any consumer; the backlog and offsets; `DEBUG DIGEST` | The persistence tests, unchanged; the backlog's wrap-around and offsets; digests equal for equal data in any order, and different for any difference. **Built 2026-09-29**: the backlog holds exactly the AOF's bytes, and nothing from before it started. Mutation-checked: 20 of 21 mutants were caught once five tests were added; the last one was equivalent, and its code was removed |
| (c) | The full sync and the stream: `PSYNC` on the primary, `REPLICAOF` and the link on the replica, the image adopted as the replica's base, the replica's rules, `ROLE`, `INFO` | Two servers in one test: every type, streams with groups, TTLs; writes during the sync applied once (digests equal); transactions whole; expiry hidden, then deleted by the primary; `READONLY`; a replica restarted alone keeps its data; a random workload, digests compared. **Built 2026-09-29**. A fake replica that asks and never reads holds a sync open, to test: rewrites refused and `FLUSHALL` ending the sync; a backlog outrun; a link cut and joined again; the end mark cut at every point |
| (d) | `CONTINUE` from the backlog, `ACK`, timeouts and `PING`, `WAIT` | A dropped link continues without a snapshot; past the backlog, it syncs in full; `WAIT` counts; a silent link is dropped. **Built 2026-09-29**: both sides' offsets agree; ten `WAIT`s answered in well under the second an unrequested acknowledgement would take; a replica that never acknowledges is dropped; a primary that never speaks is left and tried again; a quiet link is kept up by `PING`s. Mutation-checked: 18 of 18 once waiting clients showed in `INFO` |
| (e) | Epochs, `REPLICAOF NO ONE`, `min-replicas-to-write` | The epoch persisted across a restart; a lower-epoch primary refused; the demoted old primary syncs from the new one; writes refused without a replica. **Built 2026-09-29**: the promoted server, pointed back at the old one, keeps its newer data and says why; a replica connected but past the lag does not count. Found: a delivery's logged time was capped at the replica's clock (backend D-28) |
| (f) | The client following the primary; the CLI and tools; 2.2.0 released | A primary killed with `kill -9` under a workload, and the replica promoted: every write that `WAIT` confirmed is there, and the client finds the new primary on its own. **Client and crash test built 2026-09-29**: real server processes; 174 writes confirmed before the kill and none lost; 110 written after the promotion by the client on its own; the old primary, restarted as a replica, followed the new one to the same digest. Mutation-checked: 7 of 7 once a replica's epoch was tested |

Each part is mutation-checked, as 2.1's were: each new rule was broken on
purpose, to confirm that a test catches it.
