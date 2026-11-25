# handoff

This module holds what the processes agree on when they pass data to each other through the store.
The store is [j-redis](../../j-redis-service/README.md), a Redis-compatible in-memory store that
this repository builds itself ([glossary: store](../../docs/glossary.md)). The module defines the
key names, the field names, the lifetimes and the wire formats. Each of them lives in one class
that both sides use. Two processes are deployed apart, and with two copies of a key name they
could drift apart in a way that nothing detects until a player cannot join.

The module also opens every process's store client. It sends the password, accepts a store named
together with its replica, and refuses to start when a store wants a password that nobody gave.

All four process types use it: `platform`, `gateway`, `arena` and `worker`. It depends on the store
client and `common`, and never on MySQL. That keeps the MySQL driver off an arena's classpath. The
arena must never touch MySQL, and the module graph enforces that better than a sentence does.

This page is for engineers who change a key, a ticket, a result or a store setting, or who look at
the store in operation. It covers the classes and who uses them, every key family with its rules,
the result stream's contract, the match modes, how the store client is opened, and the tests.

Design: [04 §1, §3, §4, §7](../../docs/detailed-design/04-platform-services.md),
[05](../../docs/detailed-design/05-worker-and-events.md),
[03 §4–§5](../../docs/detailed-design/03-gateway.md#4-connection-lifecycle). The flows are drawn in
[diagrams/03](../../docs/diagrams/03-lobby-and-store.md).

## Layout

All classes are in the package `com.backend.handoff`.

| Class | What it does |
|---|---|
| `SessionStore` | Lobby sessions: `open` and `create` at login, `playerIdOf`, `revoke` (a logout), and `revokeAll` (a ban, or the account deleted). A token is 32 random bytes in base64url: 43 characters |
| `LobbyPush` | A message to a player's lobby connection, from any process. `send` looks up `conn:` and publishes on that gateway's channel; `broadcast` publishes on `push:all`; `connected` answers for one player, or for many in one read. `Delivery.parse` is the gateway's side, and `unheard()` counts pushes nobody heard |
| `Ticket` | The join ticket: its id (16 random bytes, 22 characters), the player, the display name and the team. For a made match, also its `matchUid` and mode. Also the equipment's bonus and the skin. The constructor checks every field |
| `TicketStore` | `issue`, `claim` (single use, atomic) and `revoke` |
| `ArenaDirectory` | The live arenas and their capacity: `announce`, `withdraw`, `live`, `pick` (open seats), `promiseSeat` (a seat given out, D-79), and `reserveForMatch` and `release` (a made match's room, D-42). Also the operator's channel (`command`, `listen`, `stopListening`) and `roomList` |
| `LeaderboardStore` | The score boards: `record` (a match's score), `raise` (a rebuild), `rename`, `remove` (a player erased, D-84), `top` and `around` |
| `MatchResultStream` | The stream that carries a result to `worker`: produce, read as a group, acknowledge, set aside, take over idle entries, and drain the old list inbox ([below](#the-result-stream-contract)). It also reads the stream's retention, `BACKEND_RESULT_RETENTION_HOURS` (D-83) |
| `MatchResultQueue` | The lists beside the stream: the inbox, each worker's processing list, and the dead and deferred lists |
| `MatchResultCodec`, `MatchOutcome` | A result's envelope and its payload. The payload holds facts, not rewards |
| `MatchMode` | The ten match modes: one definition, read by platform, arena, worker and persistence |
| `SandboxHolds` | `sbx:{playerId}`: the sandbox a player holds |
| `TournamentGrants` | `tgrant:` and `tcall:`: a tournament match's grant, and the mark that the player was called |
| `MatchArrivals` | `marr:{matchUid}`: whether anyone came to a made match |
| `Ulid` | 26-character, time-ordered ids for matches |
| `StoreUnavailableException` | What a blocking store call throws when the store did not answer, so that an HTTP layer can answer 503 |
| `StoreClients` | Opens a process's store clients and registers their metrics ([below](#opening-the-store)) |

Who uses what:

| Class | Used by |
|---|---|
| `SessionStore` | platform (writes, reads, revokes), gateway (reads) |
| `LobbyPush` | platform and worker (send); gateway (`Delivery`, the channel names, the gateway a registration names) |
| `Ticket`, `TicketStore` | platform and worker (issue, revoke), arena (claim) |
| `ArenaDirectory` | arena (announce, withdraw, listen); platform (pick, promise a seat, reserve a room, list, command); worker (reserve a room for a tournament match) |
| `LeaderboardStore` | worker (write, rebuild, and remove an idle guest erased by retention, D-87); platform (read, rename, and remove a player who deleted the account, D-84) |
| `MatchResultStream`, `MatchResultCodec`, `MatchOutcome` | arena (produce), worker (consume) |
| `MatchResultQueue` | worker |
| `MatchMode` | arena, platform, worker, persistence |
| `SandboxHolds` | platform (take, release), arena (keep, release) |
| `TournamentGrants` | worker (write), platform (read) |
| `MatchArrivals` | arena (mark), worker (read) |
| `StoreClients` | all four process types |

## Store key families

There are two store instances
([D-7](../../docs/architecture/03-decision-log.md#d-7--two-j-redis-instances-not-one)):
- **session** holds every key below except the result queue.
- **events** holds `s:match-result` and the `q:match-result` lists.

With `BACKEND_EVENTS_STORE` unset, one instance holds both.

| Key | Type | Lifetime | Written by | Read by | Purpose |
|---|---|---|---|---|---|
| `sess:{token}` | hash `playerId`, `createdAt` | 86 400 s ±10 %, so 77 760 to 95 040 s | platform at login (`SessionStore.open`) | gateway at lobby auth, platform for every call with a bearer token (`HGET … playerId`); deleted by a logout and by `revokeAll` | A session |
| `sess:of:{playerId}` | set of tokens | 95 100 s, renewed at each login | platform at login | platform (`revokeAll`, for a ban or an account deleted) | Every session of a player, so a ban ends them all |
| `conn:{playerId}` | string `{gatewayId}#{nonce}-{n}` | 60 s; refreshed every 20 s | gateway (`ConnectionRegistry`) | platform and worker (`LobbyPush.send`, `connected`); a gateway, for the registration it replaced (T-60) | Which gateway holds the player's lobby connection |
| `push:{gatewayId}` | pub/sub channel | — | platform and worker (`LobbyPush.send`); a gateway, for `evt.session.replaced` (T-60) | that gateway | A message for one player |
| `push:all` | pub/sub channel | — | platform admin (`LobbyPush.broadcast`) | every gateway | A notice to everyone |
| `ticket:{id}` | hash `playerId`, `name`, `team`; `match` and `mode` for a made match; `bonus` and `skin` when present | 60 s | platform (open seat, matcher, sandbox), worker (tournament) | arena (`claim`); platform and worker (`revoke`) | A single-use join credential |
| `arena:{name}` | hash `host`, `port`, `players`, `maxPlayers`, `tls` (`1`/`0`), `tlsName` (empty for none), `rooms`, `maxRooms`, `roomList` (JSON) | 10 s; announced every 3 s | arena (`ArenaAnnouncer`) | platform, worker | A live arena and its capacity. `tlsName` is the name an arena behind the front announces: the name a client sends in its TLS handshake (D-82), and puts in `GET /match/<tlsName>` on port 80 without TLS (D-115) |
| `arenas` | set of names | none | arena (`SADD` at each announcement, `SREM` at withdraw) | platform, worker; a reader removes a name whose entry is gone | The directory's index |
| `rooms:promised:{arena}` | sorted set: `matchUid`, scored by its deadline (ms) | 60 s from the last promise | platform and worker (`reserveForMatch`, `release`); arena `ZREM`, in the announcement that first counts the match's room | platform, worker | Rooms promised to made matches that the arena has not announced yet ([D-42](../../docs/architecture/03-decision-log.md#d-42--a-matchs-room-is-promised-in-the-store-when-its-arena-is-chosen)) |
| `seats:promised:{arena}` | sorted set: player id, scored by its deadline (ms) | 60 s from the last promise | platform (`promiseSeat`); arena `ZREM`, in the announcement that first counts the player | platform (`pick`) | Public seats given out that the arena has not counted yet ([D-79](../../docs/architecture/03-decision-log.md#d-79--a-public-seat-is-promised-in-the-store-when-its-arena-is-chosen)) |
| `arena-admin:{name}` | pub/sub channel | — | platform admin (`command`) | that arena (`listen`) | An operator's `close` (a room) and `kick` (a player) |
| `lb:score:alltime` | sorted set, member the player id | none | worker (`record`, `raise`); platform and worker (`remove`) | platform | The best score in one match, ever |
| `lb:score:day:{yyyy-MM-dd}` | sorted set | 3 days after its last write | worker; platform and worker (`remove`) | platform | The best score that UTC day |
| `lb:score:week:{YYYY-Www}` | sorted set | 10 days after its last write | worker; platform and worker (`remove`) | platform | The best score that ISO week |
| `lb:name` | hash: player id → display name | none | worker (`record`, `raise`), platform (`rename`); platform and worker (`remove`) | platform | The names for a board, in one read |
| `marr:{matchUid}` | string `1` | 3 600 s | arena, at the first arrival | worker (tournament scheduler) | Whether anyone came |
| `sbx:{playerId}` | string `matchUid` | 60 s when platform takes it (`NX`); 1 260 s when the arena keeps it | platform, arena | platform | The sandbox a player holds ([D-54](../../docs/architecture/03-decision-log.md#d-54--a-sandbox-is-held-by-a-key-of-the-players-own-for-as-long-as-its-room)) |
| `tgrant:{tournamentId}:{playerId}` | string: the grant's JSON | 60 s | worker | platform (`GET /v1/tournaments/{id}/match`) | A tournament match's grant |
| `tcall:{playerId}` | string: the tournament id | 60 s | worker, with the grant | platform (queue and sandbox) | The player has been called |
| `s:match-result` | stream, one field `e` | entries older than the retention trimmed at each add: 24 h unless `BACKEND_RESULT_RETENTION_HOURS` sets 1 to 168 hours (D-83) | arena; worker, when it moves the inbox into the stream | worker, as the group `rewards` | Match results ([below](#the-result-stream-contract)) |
| `q:match-result` | list | none | arenas of the list release, and an operator returning dead entries | worker, which moves it into the stream | The inbox |
| `q:match-result:processing:{workerId}` | list | none | worker | that worker | An entry on its way from the inbox to the stream |
| `q:match-result:processing` | list | none | builds older than the per-worker lists | worker, which returns it to the inbox | The legacy shared list |
| `q:match-result:dead`, `q:match-result:deferred` | lists | none | worker | an operator; worker (deferred, at start) | Set aside: unreadable, or from a newer producer |

Keys that belong to `platform` alone (`mmq:`, `mmp:`, `mmc:`, `party:`, `pinv:`, `mm:leader`) are
not defined here: see [04 §4](../../docs/detailed-design/04-platform-services.md#4-matchmaking).

### Rules each family keeps

- **Sessions.**
  - `open` writes the hash, its expiry and the index in one `MULTI`. The expiry is spread by
    ±10 %.
  - The spread keeps the logins of a launch from all expiring together a day later. The client is
    told the real lifetime, not the nominal day.
  - **A token is checked for its form before it names a key.** It must be exactly 43 characters of
    `A–Z a–z 0–9 - _`, as `open` makes them. For anything else, `playerIdOf` answers -1 and
    `revoke` answers false, without a store read
    ([S-20](../../docs/defects.md#5-security-and-input)). Before this check, the token `of:42`
    named `sess:of:42`, player 42's index. A logout with it deleted the index, and a later ban
    found nothing to end.
  - `revokeAll` deletes every indexed token and the index in one `MULTI`, and counts the tokens
    that were live. A ban calls it, and so does the deletion of the player's own account (D-84).
  - A token that a logout or its expiry ended stays in the index until then. It does no harm.
- **The lobby registration** belongs to the gateway ([its README](../gateway/README.md#the-registration)).
  - A sender reads `conn:{playerId}` and publishes on `push:` plus the part of the value before
    `#`. The message is `{"to": playerId, "msg": {"t": type, "d": data}}`.
  - `send` answers whether any gateway was listening on that channel. A push to a registered
    player that no gateway heard is counted in `unheard()`
    ([03 §5](../../docs/detailed-design/03-gateway.md#5-push-routing)).
  - `Delivery.parse` refuses anything that is not that envelope: no numeric `to`, no `msg`
    object, or a `t` that is not text.
  - A push is delivered at most once.
- **Tickets.**
  - `issue` is `MULTI · HSET · EXPIRE 60 · EXEC`, so no ticket outlives its minute.
  - `claim` is `MULTI · HGETALL · DEL · EXEC`. Of any number of claims made at the same moment,
    exactly one gets the fields.
  - An error inside either transaction is raised, not taken as "no ticket".
  - A ticket that is unknown, used, expired or malformed claims as null. The arena cannot tell
    these cases apart, and should not.
  - Field rules:
    - `team` is 0–255.
    - `match` (26 characters) and `mode` (1–255) appear together or not at all.
    - `bonus` is `stat:percent` pairs: stats 0–7, 1–25 % each, each stat once. The field is
      absent when there is no bonus.
    - `skin` is 1–255. The field is absent when there is no skin.
- **The arena directory.**
  - An arena writes its hash, its 10 s expiry and its index entry in one `MULTI` every 3 s.
    Liveness is that expiry, not a health check
    ([04 §3](../../docs/detailed-design/04-platform-services.md#3-arena-registry-rooms-and-tickets)).
    The arena writes `tlsName` in every announcement, empty for none, so a name removed from its
    settings disappears at the next announcement.
  - `pick` takes the arena with the most free places, less the seats promised to it that are
    still running. `promiseSeat` promises one seat, by player id
    ([D-79](../../docs/architecture/03-decision-log.md#d-79--a-public-seat-is-promised-in-the-store-when-its-arena-is-chosen)).
    The arena drops the promise in the announcement that first counts that player.
  - `reserveForMatch` counts each arena's free rooms, less its live promises. It takes the arena
    with the most free rooms; between equals, the one with the most free places. It then reads
    that arena's promises and writes its own under `WATCH`, as one change. A chooser whose write
    another chooser overtook counts that arena again, five times at most. When no arena has a
    room, the caller gets none. The matcher and the tournament scheduler try again next round, and
    a sandbox request is refused
    ([D-42](../../docs/architecture/03-decision-log.md#d-42--a-matchs-room-is-promised-in-the-store-when-its-arena-is-chosen)).
  - The arena drops a room's promise in the announcement that first counts that match's room.
- **Boards.**
  - Every score is written with `ZADD … GT`, which only raises a score. So a redelivered or
    reordered result changes nothing
    ([04 §7](../../docs/detailed-design/04-platform-services.md#7-leaderboards)).
  - A score of 0 or less is not ranked.
  - The day and the week are those the match ended in, in UTC. A result whose match ended longer
    ago than a board's lifetime is not written to that board.
  - `record` checks every reply of its `MULTI`.
  - `raise` writes in chunks of 500. It gives a rebuilt short board `EXPIREAT … NX`, at the moment
    the live board would have expired.
  - `remove` takes a player off every board and removes their name, in one `MULTI`. For a board
    that expires, it clears every day's or week's key the store can still hold, a day at a time
    back to the board's lifetime (D-84).

## The result stream contract

This is what an arena writes and a worker reads
([05 §2–§4](../../docs/detailed-design/05-worker-and-events.md#2-streams),
[D-33](../../docs/architecture/03-decision-log.md#d-33--the-result-queue-is-a-stream-read-by-one-group-the-list-stays-an-inbox)).

| | |
|---|---|
| Stream | `s:match-result`, on the events store |
| Entry | One field, `e`: the envelope, as JSON |
| Envelope | `{"id": matchUid, "type": "match.result", "v": 1, "ts": epoch ms, "payload": MatchOutcome}`. The id is the match's own, which is also the key that makes the apply in MySQL idempotent |
| Retention | `BACKEND_RESULT_RETENTION_HOURS`: whole hours from 1 to 168; unset or empty, 24 h. Any other value refuses the start (`RefusedConfiguration`, exit 2). Arenas and workers read it, and each trims by its own, so the shortest set anywhere wins (D-83) |
| Produce (arena) | `XADD s:match-result MINID ~ <now − retention>-0 * e <envelope>`: the entry is added, and the stream is trimmed to the retention. When the store that holds results is named with its replica (`eventsReplicated()`, below), `replicated(ms)` is `WAIT 1 ms` |
| Group | `rewards`, made from `0` with `MKSTREAM` when it is missing (`BUSYGROUP` is ignored). A read refused with `NOGROUP` makes it again |
| Consumer | The worker's id, `worker-1` by default. It must stay the same across restarts and be unique among workers. It must be one word: an empty id, or one with a space, is refused |
| Read | `XREADGROUP GROUP rewards <worker> COUNT 1 BLOCK <ms> STREAMS s:match-result >`, on the client's blocking connection. The worker waits 2 s |
| Done | `XACK`, after the MySQL commit, never before |
| Unreadable | `LPUSH q:match-result:dead`, then `XACK`. These are two commands, so a crash between them sets the entry aside twice and loses nothing |
| From a newer producer | `LPUSH q:match-result:deferred`, then `XACK`. The entry returns through the inbox when a worker starts |
| Its own unfinished | `XREADGROUP … STREAMS s:match-result 0`. An entry trimmed away comes back without fields and is left out |
| Another's unfinished | `XAUTOCLAIM s:match-result rewards <worker> 60000 0 COUNT 64`. It answers the ids that were trimmed away before anyone applied them |
| The inbox | First the deferred list (when asked) and the legacy shared list go back to `q:match-result`. Then this worker's processing list, then the inbox, one entry at a time: `LMOVE` onto `q:match-result:processing:{worker}`, `XADD` (trimmed by the retention), `LREM` |
| Depth | The group's lag plus the inbox's length. Pending is the count from `XPENDING` |

**Decoding** (`MatchResultCodec.decode`):
- A field that the reader does not know is ignored.
- `v` above 1 is `FutureEntry`. It is kept for a newer worker.
- `UnreadableEntry` is set aside as evidence. It covers: `v` below 1 or absent, another `type`, no
  payload or no `matchUid`, and malformed JSON.

**The payload**, `MatchOutcome`:

| Field | Meaning |
|---|---|
| `matchUid` | A ULID |
| `kind` | 0 for an open match (a stay in the public arena), 1 for a timed match |
| `mode` | The `MatchMode` id |
| `arena` | The arena's name |
| `startedAtMillis`, `endedAtMillis` | The match's own clock |
| `players[]` | `playerId`, `displayName`, `team`, `placement` (1 is a win), `kills`, `deaths`, `score`, `playtimeSeconds`, `assists` |
| `cutShort` | The match was ended by a stop that the drain could not wait out: recorded and paid, not rated. When absent, it reads as false |

A field may be added, but never given a new meaning: the two ends are deployed apart.

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

Roster is the number of players a match is made for. Map is the map's size, in the simulation's
units. Join window is how long a match's room waits for its roster before it plays with whoever
came.

Every mode but `ffa` is a made match: the arena makes its room when the first ticket that names the
match is claimed
([D-20](../../docs/architecture/03-decision-log.md#d-20--a-matchs-room-is-made-by-its-first-ticket-not-by-a-command)).

## Opening the store

`StoreClients.open(host, port, name)` opens the session store's client, and
`openEvents(session, name)` opens the events store's client.

| Variable | Default | Meaning |
|---|---|---|
| `BACKEND_STORE_ADDRESSES` | unset: the host and port given | `host:port`, or `host:port,host:port` for a store and its replica in any order. Spaces around the comma are allowed ([D-34](../../docs/architecture/03-decision-log.md#d-34--each-store-has-a-replica-every-process-knows-both-a-promotion-is-a-script)). The client uses the primary with the highest epoch and follows a promotion. A value not in that form (no host, or a port that is not 1 to 65535): exit 2 |
| `BACKEND_STORE_PASSWORD_FILE`, `BACKEND_STORE_PASSWORD` | unset: no password, with a warning | The session store's password. The file wins (`common`'s `Secrets`). On the site, `BACKEND_STORE_PASSWORD` itself goes at the end of the process's settings file, `/etc/backend/<process>.env`, which only root can read (D-102) |
| `BACKEND_EVENTS_STORE` | unset: the events client is the session client | The events store: `host:port`, or a list as above |
| `BACKEND_EVENTS_STORE_PASSWORD_FILE`, `BACKEND_EVENTS_STORE_PASSWORD` | unset: the session store's password | The events store's password |

- **What is refused at start-up.** Each address is asked `PING`, with a 2 s wait. A store that
  answers `NOAUTH` wants a password that this process was not given. That is
  `RefusedConfiguration`, exit 2, and the message names the setting to fill in: the events store's
  own setting for the events store.
- **What is not refused.** A store that cannot be reached may simply not be up yet. The client
  logs it, keeps trying in the background, and fails requests at once until it connects (D-94).
  From here, a wrong password looks the same as an unreachable store. Look for the client's
  `authentication … failed` line.
- **`eventsReplicated()`** tells whether the store that holds results was named with its replica:
  the events store's list, or the session store's when no events store is named. When it was, the
  arena waits up to 100 ms after each result it adds for a replica to hold it (`WAIT 1`). It counts
  the results it did not see held, and deletes the spooled file either way.
- **The client's defaults apply**, since `StoreClients` sets nothing else:
  - one I/O thread and one command connection;
  - 2 s to connect and 2 s for each command;
  - a ping on the subscriber connection every 5 s;
  - up to 4 leased connections for `WATCH`.

**Metrics** (`registerMetrics(m, client[, prefix])`, with the prefix `store` or `events_store`):
- `backend_<prefix>_timeouts_total`, `_failed_fast_total`, `_reconnects_total`,
  `_server_errors_total`, and `_connected` (1 while a command connection is up).
- `registerReplicaMetrics` adds `backend_<prefix>_replicas`, `_replica_behind_bytes` and
  `_replica_ack_seconds`. They are read from the primary's `INFO replication` at each scrape
  ([D-58](../../docs/architecture/03-decision-log.md#d-58--a-replica-is-measured-by-what-it-has-applied-a-heartbeat-for-mysql-the-primarys-own-account-for-the-stores)).

## Build and test

From `backend/`, with j-redis 2.2.1 installed first (see the
[backend README](../README.md#commands)):

```bash
. ../dev-env.sh  # vendor/'s JDK and Maven, the bundle, work/ (D-80)
mvn -pl handoff -am install
mvn -pl handoff test -Dtest=TicketStoreTest
```

Each test class runs an embedded j-redis, so nothing outside the JVM is needed.

| Class | Tests | What it covers |
|---|---|---|
| `LeaderboardStoreTest` | 18 | `GT` under redelivery and reordering; the day and the week a result belongs to; expiry, and a late result that does not bring a board back; a refused write reported; the top, ties, around-me, missing names, renames and board names; an erased player taken off every board the store still holds, with their name (D-84) |
| `ArenaDirectoryTest` | 14 | Announce, list, pick, full arenas, expired entries pruned, malformed entries ignored, withdraw, TLS. A made match's arena chosen by free rooms; the promise held until the arena announces the room; eight choosers racing for one last room, which is never given twice. A burst of 600 seats spread 300 and 300 over two arenas by their promises; a seat's promise is one per player, dropped by the announcement that counts the player, and lapses with the ticket (T-59, D-79); the TLS name that an arena behind the front announces (D-82) |
| `StoreClientsTest` | 13 | The password sent; a store that wants a password and was given none refused, with one address or both, naming the right store's setting; the events store shared or its own; a malformed address refused, and spaces around a comma read; following a promotion; an unreachable store not refused; `INFO replication` parsed, and measured against a real replica |
| `TicketStoreTest` | 9 | Issue and claim with every field; the bonus and skin rules; single use, and sixteen claims racing with one winner; unknown, expired and half-written tickets |
| `SessionStoreTest` | 8 | Create and resolve; unknown, empty and corrupt tokens; `revoke` and `revokeAll`; a token not in the form `open` makes names no key (S-20); tokens unique; lifetimes spread |
| `LobbyPushTest` | 5 | The envelope's round trip, anything else refused, routing by the part before `#`, the unheard count, and a player no gateway holds |
| `MatchModeTest` | 5 | Domination, tag, maze and sandbox as decided; which modes are made, and how many players a room holds |
| `MatchResultStreamTest` | 4 | The retention read from `BACKEND_RESULT_RETENTION_HOURS`, and refused outside 1 to 168; an arena's add and a worker's move from the inbox, each trimming by it (D-83) |
| `TournamentGrantsTest` | 1 | A grant and the call kept for the ticket's life, and read back |

`MatchResultStream`'s retention is tested here. The rest of `MatchResultStream`, and
`MatchResultQueue` and `MatchResultCodec`, are tested where they are used: in `worker`'s
`MatchResultPipelineTest` and `LeaderboardRebuildTest`, and in `arena`'s
`MatchResultPublisherTest`. `SandboxHolds` and `MatchArrivals` are tested in `arena`'s
`ArenaServerTest`, and in the platform and worker tests.

## Operational notes

- **Look at what is there** with `j-redis-cli` against the session store:
  - `SMEMBERS arenas` and `HGETALL arena:<name>` for the directory;
  - `TTL conn:<id>` for a player's lobby registration;
  - `PUBSUB NUMSUB push:all` for how many gateways listen (one per gateway).
- **On the events store:** `XINFO GROUPS s:match-result` and `LLEN q:match-result:dead`.
- **Nothing here is the system of record.** A lost session store costs logins, tickets, queues and
  presence, and all of them are rebuilt as players come back. The boards are rebuilt from MySQL
  (`worker`'s `LeaderboardRebuild`,
  [05 §8](../../docs/detailed-design/05-worker-and-events.md#8-rebuilding-after-a-store-loss)).
- **A store that fails a blocking call.** `ArenaDirectory` throws `StoreUnavailableException`,
  which the HTTP layer answers with 503. `MatchResultStream` and `MatchResultQueue` throw
  `IllegalStateException` to the worker's loop.

## Design documents

- [04 — Platform services](../../docs/detailed-design/04-platform-services.md): sessions (§1), the
  directory and tickets (§3), matchmaking (§4), boards (§7), and the admin API (§10).
- [05 — Worker and events](../../docs/detailed-design/05-worker-and-events.md): the stream and its
  consumer.
- [03 — Gateway](../../docs/detailed-design/03-gateway.md): lobby pushes and the registration.
- [Diagrams: the lobby and the store](../../docs/diagrams/03-lobby-and-store.md).
