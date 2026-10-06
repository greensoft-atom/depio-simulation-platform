# handoff

This module defines what the processes agree on through the store (j-redis): key names, field
names, lifetimes and wire formats. Each lives in one class that both sides use, so two processes
deployed apart cannot drift in a way nothing detects until a player cannot join. The module also
opens every process's store client: the password, a store named together with its replica, and a
refusal at start-up for a store that wants a password nobody gave.

It depends on the store client and `common`, and never on MySQL. That keeps the MySQL driver off
an arena's classpath: the arena is specified never to touch MySQL, and the module graph enforces it
better than a sentence does.

Design: [04 §1, §3, §4, §7](../../docs/detailed-design/04-platform-services.md),
[05](../../docs/detailed-design/05-worker-and-events.md),
[03 §4–§5](../../docs/detailed-design/03-gateway.md#4-connection-lifecycle). Flows drawn:
[diagrams/03](../../docs/diagrams/03-lobby-and-store.md).

## Layout

`com.backend.handoff`:

| Class | What it does |
|---|---|
| `SessionStore` | Lobby sessions: `open`/`create` at login, `playerIdOf`, `revoke` (logout), `revokeAll` (a ban). The token is 32 random bytes, base64url, 43 characters |
| `LobbyPush` | A message to a player's lobby connection from any process: `send` (look up `conn:`, publish on that gateway's channel), `broadcast` (`push:all`), `connected` (one player, or many in one read), `Delivery.parse` for the gateway, and `unheard()` |
| `Ticket` | The join ticket: id (16 random bytes, 22 characters), player, display name, team, and for a made match its `matchUid` and mode; also the equipment bonus and the skin. Validated on construction |
| `TicketStore` | `issue`, `claim` (single use, atomic), `revoke` |
| `ArenaDirectory` | Live arenas and their capacity: `announce`, `withdraw`, `live`, `pick` (open seats), `reserveForMatch`/`release` (a made match's room). Also the operator channel (`command`, `listen`, `stopListening`) and `roomList` |
| `LeaderboardStore` | The score boards: `record` (a match's score), `raise` (rebuild), `rename`, `top`, `around` |
| `MatchResultStream` | The stream a result crosses to `worker`: produce, read as a group, acknowledge, set aside, take over idle entries, drain the old list inbox ([below](#the-result-stream-contract)) |
| `MatchResultQueue` | The lists beside the stream: the inbox, each worker's processing list, the dead and deferred lists |
| `MatchResultCodec`, `MatchOutcome` | A result's envelope and its payload: facts, not rewards |
| `MatchMode` | The ten modes, one definition read by platform, arena, worker and persistence |
| `SandboxHolds` | `sbx:{playerId}`: the sandbox a player holds |
| `TournamentGrants` | `tgrant:` and `tcall:`: a tournament match's grant, and the player called |
| `MatchArrivals` | `marr:{matchUid}`: whether anyone came to a made match |
| `Ulid` | 26-character, time-ordered ids for matches |
| `StoreUnavailableException` | What a blocking store call throws when the store did not answer, so an HTTP layer can say 503 |
| `StoreClients` | Opens a process's store clients and registers their metrics ([below](#opening-the-store)) |

Who uses what:

| Class | Used by |
|---|---|
| `SessionStore` | platform (writes, reads, revokes), gateway (reads) |
| `LobbyPush` | platform, worker (send); gateway (`Delivery`, the channel names) |
| `Ticket`, `TicketStore` | platform and worker (issue), arena (claim) |
| `ArenaDirectory` | arena (announce, listen), platform and worker (choose, command) |
| `LeaderboardStore` | worker (write, rebuild), platform (read, rename) |
| `MatchResultStream`, `MatchResultCodec`, `MatchOutcome` | arena (produce), worker (consume) |
| `MatchResultQueue` | worker |
| `MatchMode` | arena, platform, worker, persistence |
| `SandboxHolds` | platform (take, release), arena (keep, release) |
| `TournamentGrants` | worker (write), platform (read) |
| `MatchArrivals` | arena (mark), worker (read) |
| `StoreClients` | all four process types |

## Store key families

There are two instances
([D-7](../../docs/architecture/03-decision-log.md#d-7--two-j-redis-instances-not-one)):
- **session**: every key below except the result queue.
- **events**: `s:match-result` and the `q:match-result` lists.

With `BACKEND_EVENTS_STORE` unset, one instance holds both.

| Key | Type | Lifetime | Written by | Read by | Purpose |
|---|---|---|---|---|---|
| `sess:{token}` | hash `playerId`, `createdAt` | 86 400 s ±10 %, so 77 760 to 95 040 s | platform at login (`SessionStore.open`) | gateway at lobby auth, platform for every bearer call (`HGET … playerId`); deleted by logout and by `revokeAll` | A session |
| `sess:of:{playerId}` | set of tokens | 95 100 s, renewed at each login | platform at login | platform admin (`revokeAll`) | Every session of a player, so a ban ends them all |
| `conn:{playerId}` | string `{gatewayId}#{nonce}-{n}` | 60 s; refreshed every 20 s | gateway (`ConnectionRegistry`) | platform and worker (`LobbyPush.send`, `connected`) | Which gateway holds the player's lobby connection |
| `push:{gatewayId}` | pub/sub channel | — | platform, worker (`LobbyPush.send`) | that gateway | A message for one player |
| `push:all` | pub/sub channel | — | platform admin (`LobbyPush.broadcast`) | every gateway | A notice to everyone |
| `ticket:{id}` | hash `playerId`, `name`, `team`; `match`, `mode` for a made match; `bonus`, `skin` when present | 60 s | platform (open seat, matcher, sandbox), worker (tournament) | arena (`claim`); platform and worker `revoke` | A single-use join credential |
| `arena:{name}` | hash `host`, `port`, `players`, `maxPlayers`, `tls` (`1`/`0`), `rooms`, `maxRooms`, `roomList` (JSON) | 10 s; announced every 3 s | arena (`ArenaAnnouncer`) | platform, worker | A live arena and its capacity |
| `arenas` | set of names | none | arena (`SADD` at each announce, `SREM` at withdraw) | platform, worker; a name whose entry is gone is removed by the reader | The directory's index |
| `rooms:promised:{arena}` | sorted set, `matchUid` scored by its deadline (ms) | 60 s from the last promise | platform, worker (`reserveForMatch`, `release`); arena `ZREM` in the announce that first counts the match's room | platform, worker | Rooms promised to made matches the arena has not announced yet ([D-42](../../docs/architecture/03-decision-log.md#d-42--a-matchs-room-is-promised-in-the-store-when-its-arena-is-chosen)) |
| `seats:promised:{arena}` | sorted set, player id scored by its deadline (ms) | 60 s from the last promise | platform (`promiseSeat`); arena `ZREM` in the announce that first counts the player | platform (`pick`) | Public seats given out that the arena has not counted yet ([D-79](../../docs/architecture/03-decision-log.md#d-79--a-public-seat-is-promised-in-the-store-when-its-arena-is-chosen)) |
| `arena-admin:{name}` | pub/sub channel | — | platform admin (`command`) | that arena (`listen`) | An operator's close and kick |
| `lb:score:alltime` | sorted set, member the player id | none | worker (`record`, `raise`) | platform | The best score in one match, ever |
| `lb:score:day:{yyyy-MM-dd}` | sorted set | 3 days after its last write | worker | platform | The best that UTC day |
| `lb:score:week:{YYYY-Www}` | sorted set | 10 days after its last write | worker | platform | The best that ISO week |
| `lb:name` | hash player id → display name | none | worker (`record`, `raise`), platform (`rename`) | platform | Names for a board, in one read |
| `marr:{matchUid}` | string `1` | 3 600 s | arena, at the first arrival | worker (tournament scheduler) | Whether anyone came |
| `sbx:{playerId}` | string `matchUid` | 60 s when platform takes it (`NX`); 1 260 s when the arena keeps it | platform, arena | platform | The sandbox a player holds ([D-54](../../docs/architecture/03-decision-log.md#d-54--a-sandbox-is-held-by-a-key-of-the-players-own-for-as-long-as-its-room)) |
| `tgrant:{tournamentId}:{playerId}` | string, the grant's JSON | 60 s | worker | platform (`GET /v1/tournaments/{id}/match`) | A tournament match's grant |
| `tcall:{playerId}` | string, the tournament id | 60 s | worker, with the grant | platform (queue and sandbox) | The player has been called |
| `s:match-result` | stream, one field `e` | entries older than 24 h trimmed at each add | arena | worker, as the group `rewards` | Match results ([below](#the-result-stream-contract)) |
| `q:match-result` | list | none | arenas of the list release, an operator returning dead entries | worker, which moves it into the stream | The inbox |
| `q:match-result:processing:{workerId}` | list | none | worker | that worker | An entry on its way from the inbox to the stream |
| `q:match-result:processing` | list | none | builds before per-worker lists | worker, which returns it to the inbox | The legacy shared list |
| `q:match-result:dead`, `q:match-result:deferred` | lists | none | worker | an operator; worker (deferred, at start) | Set aside: unreadable, or from a newer producer |

Keys of `platform`'s own (`mmq:`, `mmp:`, `mmc:`, `party:`, `pinv:`, `mm:leader`) are not defined
here: see [04 §4](../../docs/detailed-design/04-platform-services.md#4-matchmaking).

### Rules each family keeps

- **Sessions.**
  - `open` writes the hash, its jittered expiry and the index in one `MULTI`.
  - The jitter, ±10 %, keeps a launch's logins from expiring together a day later; a client is told
    the real lifetime.
  - **A token is checked for its form before it names a key**: exactly 43 characters of
    `A–Z a–z 0–9 - _`, as `open` mints them. `playerIdOf` answers -1 and `revoke` answers false for
    anything else, without a store read
    ([S-20](../../docs/defects.md#5-security-and-input)). Before, the token `of:42` named
    `sess:of:42`, player 42's index: a logout with it deleted the index, and a later ban found
    nothing to end.
  - `revokeAll` deletes every indexed token and the index in one `MULTI`, and counts those that
    were live.
  - A token ended by a logout or its expiry stays in the index until then, harmless.
- **The lobby registration** is the gateway's ([its README](../gateway/README.md#the-registration)).
  - A sender reads `conn:{playerId}` and publishes on `push:` plus the part of the value before `#`:
    `{"to": playerId, "msg": {"t": type, "d": data}}`.
  - `send` answers whether any gateway was listening on that channel. A registered player whose
    gateway nobody heard is counted in `unheard()`
    ([03 §5](../../docs/detailed-design/03-gateway.md#5-push-routing)).
  - A push is at most once.
- **Tickets.**
  - `issue` is `MULTI · HSET · EXPIRE 60 · EXEC`, so no ticket outlives its minute.
  - `claim` is `MULTI · HGETALL · DEL · EXEC`, so of any number of simultaneous claims exactly one
    gets the fields.
  - An error inside either transaction is raised, not taken as "no ticket".
  - A ticket that is unknown, used, expired or malformed claims as null. The arena cannot tell these
    apart, and should not.
  - Field rules:
    - `team` is 0–255.
    - `match` (26 characters) and `mode` (1–255) appear together or not at all.
    - `bonus` is `stat:percent` pairs, stats 0–7, 1–25 % each, a stat once; the field is absent
      when there is none.
    - `skin` is 1–255; the field is absent when there is none.
- **The arena directory.**
  - An arena writes its hash, its 10 s expiry and its index entry in one `MULTI` every 3 s. Liveness
    is that expiry, not a health check
    ([04 §3](../../docs/detailed-design/04-platform-services.md#3-arena-registry-rooms-and-tickets)).
  - `pick` takes the arena with the most free places less its seats promised and still running;
    `promiseSeat` promises one, by player id
    ([D-79](../../docs/architecture/03-decision-log.md#d-79--a-public-seat-is-promised-in-the-store-when-its-arena-is-chosen)).
    The arena drops it in the announcement that first counts that player.
  - `reserveForMatch` counts each arena's free rooms less its live promises and takes the most free
    rooms, then the most free places. It reads that arena's promises and writes its own under `WATCH`,
    as one change: a chooser whose write another overtook counts again, five times at most. When no
    arena has a room, the caller gets none: the matcher and the tournament scheduler try again next
    round, and a sandbox is refused
    ([D-42](../../docs/architecture/03-decision-log.md#d-42--a-matchs-room-is-promised-in-the-store-when-its-arena-is-chosen)).
  - The arena drops a promise in the announcement that first counts that match's room.
- **Boards.**
  - Every write is `ZADD … GT`, which only raises a score, so a redelivered or reordered result
    changes nothing
    ([04 §7](../../docs/detailed-design/04-platform-services.md#7-leaderboards)).
  - A score of 0 or less is not ranked.
  - The day and week are those the match ended in, in UTC. A result whose match ended longer ago
    than a board's lifetime is not written to that board.
  - `record` checks every reply of its `MULTI`.
  - `raise` writes in chunks of 500, and gives a rebuilt short board `EXPIREAT … NX` at the moment
    the live one would have expired.

## The result stream contract

What an arena writes and a worker reads
([05 §2–§4](../../docs/detailed-design/05-worker-and-events.md#2-streams),
[D-33](../../docs/architecture/03-decision-log.md#d-33--the-result-queue-is-a-stream-read-by-one-group-the-list-stays-an-inbox)).

| | |
|---|---|
| Stream | `s:match-result`, on the events store |
| Entry | One field, `e`: the envelope as JSON |
| Envelope | `{"id": matchUid, "type": "match.result", "v": 1, "ts": epoch ms, "payload": MatchOutcome}`. The id is the match's, which is also the idempotency key in MySQL |
| Produce (arena) | `XADD s:match-result MINID ~ <now − 24 h>-0 * e <envelope>`: added, and the stream trimmed to a day. With the events store named with its replica, `replicated(ms)` is `WAIT 1 ms` |
| Group | `rewards`, made from `0` with `MKSTREAM` when missing (`BUSYGROUP` ignored). A read refused `NOGROUP` makes it again |
| Consumer | The worker's id, `worker-1` by default: stable across restarts, and unique among workers |
| Read | `XREADGROUP GROUP rewards <worker> COUNT 1 BLOCK <ms> STREAMS s:match-result >`, on the client's blocking connection (the worker waits 2 s) |
| Done | `XACK`, after the MySQL commit, never before |
| Unreadable | `LPUSH q:match-result:dead`, then `XACK`: two commands, so a crash between them sets it aside twice and loses nothing |
| From a newer producer | `LPUSH q:match-result:deferred`, then `XACK`; returned through the inbox when a worker starts |
| Its own unfinished | `XREADGROUP … STREAMS s:match-result 0`. An entry trimmed away comes back without fields and is left out |
| Another's unfinished | `XAUTOCLAIM s:match-result rewards <worker> 60000 0 COUNT 64`. Answers the ids found trimmed away before anyone applied them |
| The inbox | Returns the deferred list (when asked) and the legacy shared list to `q:match-result`. Then this worker's processing list, then the inbox, one entry at a time: `LMOVE` onto `q:match-result:processing:{worker}`, `XADD`, `LREM` |
| Depth | The group's lag plus the inbox's length; pending is `XPENDING`'s count |

**Decoding** (`MatchResultCodec.decode`):
- A field the reader does not know is ignored.
- `v` above 1 is `FutureEntry`, kept for a newer worker.
- `v` below 1 or absent, another `type`, no payload or no `matchUid`, or malformed JSON is
  `UnreadableEntry`, set aside as evidence.

**The payload**, `MatchOutcome`:

| Field | Meaning |
|---|---|
| `matchUid` | A ULID |
| `kind` | 0 an open match (a stay in the public arena), 1 a timed match |
| `mode` | `MatchMode` id |
| `arena` | The arena's name |
| `startedAtMillis`, `endedAtMillis` | The match's own clock |
| `players[]` | `playerId`, `displayName`, `team`, `placement` (1 is a win), `kills`, `deaths`, `score`, `playtimeSeconds`, `assists` |
| `cutShort` | Ended by a stop the drain could not wait out: recorded and paid, not rated. Absent reads false |

A field may be added, never repurposed: the two ends are deployed apart.

### Modes

| id | key | Roster | Map | Shapes | Seconds | Kills to win | Join window | Rated | Team size |
|---|---|---|---|---|---|---|---|---|---|
| 0 | `ffa` | — | the arena's own | — | — | — | — | no | — |
| 1 | `duel` | 2 | 2 000 | 40 | 180 | 3 | 30 s | yes | 1 |
| 2 | `tvt` | 6 | 3 000 | 80 | 300 | 10 | 30 s | yes | 3 |
| 3 | `rffa` | 8 | 3 500 | 120 | 240 | — | 30 s | yes | 1 |
| 4 | `coop` | 3 | 3 000 | 60 | 600 | — | 30 s | no | 3 |
| 5 | `teams` | 6 | 3 000 | 80 | 300 | 10 | 30 s | yes | 3 |
| 6 | `domination` | 6 | 3 000 | 80 | 300 | — | 30 s | no | 3 |
| 7 | `tag` | 6 | 3 000 | 80 | 300 | — | 30 s | no | 3 |
| 8 | `maze` | 8 | 3 000 | 120 | 240 | — | 30 s | no | 1 |
| 9 | `sandbox` | never queued; holds a party, 3 at most | 2 000 | 60 | 1 200 | — | — | no | 1 |

Every mode but `ffa` is made: its room is made by the first ticket naming its match
([D-20](../../docs/architecture/03-decision-log.md#d-20--a-matchs-room-is-made-by-its-first-ticket-not-by-a-command)).

## Opening the store

`StoreClients.open(host, port, name)` opens the session store's client and `openEvents(session,
name)` the events store's.

| Variable | Default | Meaning |
|---|---|---|
| `BACKEND_STORE_ADDRESSES` | unset: the host and port given | `host:port`, or `host:port,host:port` for a store and its replica in any order, spaces around the comma allowed ([D-34](../../docs/architecture/03-decision-log.md#d-34--each-store-has-a-replica-every-process-knows-both-a-promotion-is-a-script)). The client uses the primary with the highest epoch and follows a promotion. Not in that form: exit 2 |
| `BACKEND_STORE_PASSWORD_FILE`, `BACKEND_STORE_PASSWORD` | unset: no password, with a warning | The session store's password; the file wins (`common`'s `Secrets`) |
| `BACKEND_EVENTS_STORE` | unset: the events client is the session client | The events store, `host:port` or a list as above |
| `BACKEND_EVENTS_STORE_PASSWORD_FILE`, `BACKEND_EVENTS_STORE_PASSWORD` | unset: the session store's password | The events store's password |

- **What is refused at start-up.** Each address is asked `PING`, waiting 2 s. A store that answers
  `NOAUTH` wants a password this process was not given: `RefusedConfiguration`, exit 2.
- **What is not refused.** A store that cannot be reached may simply not be up yet: the client logs,
  retries in the background, and fails requests fast until it connects. A wrong password reads the
  same from here; look for the client's `authentication … failed` line.
- **`eventsReplicated()`** is whether the store holding results was named with its replica. The arena
  then waits up to 100 ms for a replica to hold each result it adds (`WAIT 1`), and counts those it
  did not see held. It lets the spooled file go either way.
- **The client's defaults hold**, since `StoreClients` sets nothing else:
  - one I/O thread and one command connection;
  - 2 s to connect and 2 s a command;
  - the subscriber pinged every 5 s;
  - up to 4 leased connections for `WATCH`.

**Metrics** (`registerMetrics(m, client[, prefix])`, prefix `store` or `events_store`):
- `backend_<prefix>_timeouts_total`, `_failed_fast_total`, `_reconnects_total`,
  `_server_errors_total`, and `_connected` (1 while a command connection is up).
- `registerReplicaMetrics` adds `backend_<prefix>_replicas`, `_replica_behind_bytes` and
  `_replica_ack_seconds`, read from the primary's `INFO replication` at each scrape
  ([D-58](../../docs/architecture/03-decision-log.md#d-58--a-replica-is-measured-by-what-it-has-applied-a-heartbeat-for-mysql-the-primarys-own-account-for-the-stores)).

## Build and test

From `backend/`, with j-redis 2.2.1 installed first (see the
[backend README](../README.md#commands)):

```bash
export JAVA_HOME=/opt/jdk21 PATH=/opt/jdk21/bin:$PATH
/opt/maven/bin/mvn -o -pl handoff -am install
/opt/maven/bin/mvn -o -pl handoff test -Dtest=TicketStoreTest
```

Each test class runs an embedded j-redis; nothing outside the JVM is needed.

| Class | Tests | What it covers |
|---|---|---|
| `LeaderboardStoreTest` | 17 | `GT` under redelivery and reordering; the day and week a result belongs to; expiry, and a late result that does not revive a board; a refused write reported; top, ties, around-me, missing names, renames, board names |
| `ArenaDirectoryTest` | 13 | Announce, list, pick, full arenas, expired entries pruned, malformed entries ignored, withdraw, TLS. A made match's arena by free rooms; the promise held until announced; eight choosers racing for one last room never given it twice. A burst of 600 seats spread 300 and 300 over two arenas by their promises; a seat's promise one a player, dropped by the announcement that counts them, lapsing with the ticket (T-59, D-79) |
| `StoreClientsTest` | 13 | The password sent; none for a store that wants one refused, with one address or both, naming the right store's setting; the events store shared or its own; a malformed address refused, spaces around a comma read; following a promotion; an unreachable store not refused; `INFO replication` parsed and measured against a real replica |
| `TicketStoreTest` | 9 | Issue and claim with every field; bonus and skin rules; single use, sixteen racing claims with one winner; unknown, expired and half-written tickets |
| `SessionStoreTest` | 8 | Create and resolve; unknown, empty and corrupt; revoke and `revokeAll`; a token not as minted names no key (S-20); token uniqueness; spread lifetimes |
| `LobbyPushTest` | 5 | The envelope round trip, anything else refused, routing by the part before `#`, the unheard count, a player not held |
| `MatchModeTest` | 5 | Domination, tag, maze and sandbox as decided; which modes are made, and what a room holds |
| `TournamentGrantsTest` | 1 | A grant and the call kept for the ticket's life and read back |

`MatchResultStream`, `MatchResultQueue` and `MatchResultCodec` are tested where they are used:
`worker`'s `MatchResultPipelineTest` and `LeaderboardRebuildTest`, and `arena`'s
`MatchResultPublisherTest`. `SandboxHolds` and `MatchArrivals` are tested in `arena`'s
`ArenaServerTest`, and the platform and worker tests.

## Operational notes

- **Look at what is there** with `j-redis-cli` against the session store:
  - `SMEMBERS arenas` and `HGETALL arena:<name>` for the directory;
  - `TTL conn:<id>` for a player's lobby registration;
  - `PUBSUB NUMSUB push:all` for how many gateways listen (one per gateway).
- **On the events store:** `XINFO GROUPS s:match-result` and `LLEN q:match-result:dead`.
- **Nothing here is the system of record.** A lost session store costs logins, tickets, queues and
  presence, all rebuilt as players come back. The boards are rebuilt from MySQL (`worker`'s
  `LeaderboardRebuild`, [05 §8](../../docs/detailed-design/05-worker-and-events.md#8-rebuilding-after-a-store-loss)).
- **A store failing a blocking call.** `ArenaDirectory` throws `StoreUnavailableException`, which the
  HTTP layer answers 503. `MatchResultStream` and `MatchResultQueue` throw `IllegalStateException` to
  the worker's loop.

## Design documents

- [04 — Platform services](../../docs/detailed-design/04-platform-services.md): sessions (§1), the
  directory and tickets (§3), matchmaking (§4), boards (§7), the admin API (§10).
- [05 — Worker and events](../../docs/detailed-design/05-worker-and-events.md): the stream and its
  consumer.
- [03 — Gateway](../../docs/detailed-design/03-gateway.md): lobby pushes and the registration.
- [Diagrams: the lobby and the store](../../docs/diagrams/03-lobby-and-store.md).
