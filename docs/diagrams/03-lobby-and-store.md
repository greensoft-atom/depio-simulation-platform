# Diagrams — the lobby and the store

The flows of the lobby connection and of what the processes hand each other through the store
(j-redis), as built. The design is [03 — Gateway](../detailed-design/03-gateway.md) and
[04 — Platform services](../detailed-design/04-platform-services.md). The references are the
READMEs of [gateway](../../backend/gateway/README.md), [handoff](../../backend/handoff/README.md)
and [common](../../backend/common/README.md).

## Lobby connect and auth

`LobbyHandler` takes the handshake and the `auth` message, `SessionStore.playerIdOf` reads the
session, and `ConnectionRegistry` writes the registration that pushes are routed by
([03 §4](../detailed-design/03-gateway.md#4-connection-lifecycle)). `auth.ok` waits for that
write, so a push sent the moment it arrives finds the player.

```mermaid
sequenceDiagram
    participant C as client
    participant N as nginx
    participant L as gateway LobbyHandler
    participant R as ConnectionRegistry
    participant S as session store
    C->>N: wss upgrade to /lobby
    N->>L: WebSocket upgrade, plain on loopback
    L-->>C: handshake complete
    Note over L: auth deadline starts, 5 s
    C->>L: auth with d.token
    alt token not 43 base64url characters
        L-->>C: error invalid_session, then Close 1000
    else well formed
        L->>S: HGET sess:{token} playerId
        S-->>L: playerId, or nil
        alt nil
            L-->>C: error invalid_session, then Close 1000
        else a player
            L->>R: register playerId and channel
            Note over R: an older connection here is sent evt.session.replaced and closed
            R->>S: MULTI SET conn:{playerId} gatewayId-nonce-n EX 60 EXEC
            S-->>R: OK
            R-->>L: written
            L-->>C: auth.ok with playerId
        end
    end
    loop every 20 s while connected
        R->>S: MULTI SET conn NX EX 60, EXPIRE conn 60 EXEC
    end
    C->>L: connection ends
    L->>R: unregister, if still this channel
    R->>S: WATCH, GET, compare, MULTI DEL EXEC, on the registry thread
```

The value written is `{gatewayId}#{nonce}-{n}`; the diagram writes the `#` as a dash, since a
diagram label cannot carry it.

## Push routing across gateways

`LobbyPush.send` looks up the registration and publishes on the channel of the gateway named
before its `#`. The gateway's subscription (`GatewayServer.listenForPushes`) hands the message to
`ConnectionRegistry.deliver`, which passes it to the connection's `Pushes` on that connection's
event loop ([03 §5](../detailed-design/03-gateway.md#5-push-routing)).

```mermaid
sequenceDiagram
    participant P as platform or worker
    participant S as session store
    participant G1 as gateway gw-1
    participant G2 as gateway gw-2
    participant C as client on gw-2
    P->>S: GET conn:{playerId}
    alt nil
        S-->>P: nil
        Note over P: not connected anywhere, nothing published
    else gw-2 and its nonce
        S-->>P: the registration
        P->>S: PUBLISH push:gw-2 with to and msg
        S-->>P: listeners heard, 0 is counted unheard
        S->>G2: message on push:gw-2
        G2->>G2: Delivery.parse, then the local map
        alt held here and active
            G2->>C: Pushes.offer, written or held
        else moved or gone
            Note over G2: counted not_here, dropped
        end
    end
    Note over P,G2: a notice to everyone is published once on push:all
    P->>S: PUBLISH push:all
    S->>G1: message on push:all
    S->>G2: message on push:all
    G2->>C: Pushes.offer to every connection held
```

## Ticket issue and claim

`platform`'s `JoinService` picks an arena from `ArenaDirectory` and issues the ticket with
`TicketStore.issue`. The arena's `MatchFrameHandler` claims it with `TicketStore.claim`, off its
event loop ([04 §3](../detailed-design/04-platform-services.md#the-join-ticket)). A made match's
tickets are issued the same way, after a room is reserved (next diagram).

```mermaid
sequenceDiagram
    participant C as client
    participant P as platform
    participant S as session store
    participant A as arena
    C->>P: POST /v1/match-requests, or match.request through the lobby
    P->>S: SMEMBERS arenas, then HGETALL arena:{name} for each
    Note over P: the live arena with the most free places
    P->>S: MULTI HSET ticket:{id} playerId name team bonus skin, EXPIRE 60, EXEC
    P-->>C: arenaHost, arenaPort, ticketId, tls
    C->>A: Join with ticketId, over TLS when the grant says
    A->>S: MULTI HGETALL ticket:{id}, DEL ticket:{id}, EXEC
    alt fields came back
        S-->>A: the fields, and the ticket is gone
        A-->>C: Welcome
    else nothing, or malformed
        S-->>A: empty
        A-->>C: Kick, bad ticket
    end
```

## Room reservation for a made match

`ArenaDirectory.reserveForMatch` is called by `platform`'s matcher (`Matchmaker.make`) and sandbox
opener (`QueueService`), and by `worker`'s `TournamentScheduler`. The promise keeps matches chosen
between two announcements from all going to an arena's last room
([D-42](../architecture/03-decision-log.md#d-42--a-matchs-room-is-promised-in-the-store-when-its-arena-is-chosen)).

```mermaid
flowchart TD
    start["reserveForMatch with a matchUid"] --> live["live: SMEMBERS arenas, HGETALL each, expired names removed"]
    live --> count["for each arena: free rooms = maxRooms - rooms - live promises"]
    count --> prom["live promises: ZREMRANGEBYSCORE rooms:promised:{arena} -inf now, then ZCARD"]
    prom --> any{"an arena with a free room?"}
    any -->|no| none["no arena: the caller tries next round, or refuses a sandbox"]
    any -->|yes| pickit["choose the most free rooms, then the most free places"]
    pickit --> watch["WATCH rooms:promised:{arena}, ZCOUNT its live promises"]
    watch --> fits{"a room still free?"}
    fits -->|no| none
    fits -->|yes| promise["MULTI ZADD rooms:promised:{arena} now+60s matchUid, PEXPIRE 60 s, EXEC"]
    promise --> won{"EXEC applied?"}
    won -->|"no: another chooser wrote first"| retry{"fewer than 5 tries?"}
    retry -->|yes| watch
    retry -->|no| none
    won -->|yes| grant["the arena returned: the caller issues one ticket per player naming the matchUid"]
    grant --> claim["arena: the first ticket claimed makes the room"]
    claim --> announce["next announce: the room counted, the promise ZREMed in the same MULTI"]
    promise -.-> lapse["a promise never announced lapses after 60 s"]
```

## Store failover, the subscriber following the primary

`StoreClients.open` gives the gateway a j-redis client that knows both addresses of the session
store. `GatewayServer.listenForPushes` subscribes to both channels and registers the reconnect
hook, which tells every connection to resynchronise through `ConnectionRegistry.resync`
([03 §5](../detailed-design/03-gateway.md#when-the-subscription-is-lost-designed-2026-10-01-plan-item-50),
[runbook §2](../operations/02-runbook.md#a-j-redis-store-built-2026-09-29-d-34)).

```mermaid
sequenceDiagram
    participant G as gateway
    participant J as j-redis client
    participant O as old primary
    participant N as new primary
    participant L as lobby connections
    G->>J: subscribe push:gw and push:all, both sent before either is awaited
    J->>O: SUBSCRIBE push:gw push:all
    loop every 5 s
        J->>O: PING on the subscriber connection
    end
    Note over O,N: promote-store.sh promotes the replica, the old primary demoted or lost
    alt old primary demoted
        O-->>J: subscriber connections closed
    else old machine lost
        Note over J: PINGs unanswered, the connection closed within about 11 s
    end
    J->>O: ROLE
    J->>N: ROLE
    N-->>J: primary, with the higher epoch
    J->>N: SUBSCRIBE push:gw push:all, the channels remembered
    J->>G: reconnect hook
    G->>L: evt.resync to every connection, through Pushes
    Note over G: resubscribed_total rises, store_subscribed back at 1
    Note over J,N: command connections move too, on READONLY or loss, and follow what one of them found
    Note over G,L: what was published during the gap is lost, and the client fetches the truth
```

## Slow-client backpressure

Each connection's `Pushes` holds pushes while Netty reports the connection unwritable (more than
64 KiB waiting to go out), and closes it after 30 s
([03 §8](../detailed-design/03-gateway.md#8-backpressure-and-slow-clients)). Replies to the
client's own requests do not pass through the ring.

```mermaid
stateDiagram-v2
    state "Keeping up, each push written at once" as KeepingUp
    state "Holding, up to 16 pushes, 30 s timer running" as Holding
    state "Overflowed, oldest dropped, resync owed" as Overflowed
    state "Closed" as Closed
    [*] --> KeepingUp
    KeepingUp --> Holding: unwritable
    Holding --> KeepingUp: writable again, held pushes written in order
    Holding --> Overflowed: a push arrives with 16 held
    Overflowed --> Overflowed: more pushes, oldest dropped
    Overflowed --> KeepingUp: writable again, backlog dropped, one evt.resync written
    Holding --> Closed: 30 s unwritable, slow_closed_total
    Overflowed --> Closed: 30 s unwritable, slow_closed_total
    Holding --> Closed: evt.session.revoked, closed at once
    Overflowed --> Closed: evt.session.revoked, closed at once
    KeepingUp --> Closed: evt.session.revoked written then closed, or the socket ends
    Closed --> [*]
```

Each spell from unwritable to writable or closed is timed into
`backend_gateway_unwritable_seconds{end}` (`drained` or `closed`).

## Who writes and reads each key family

In both diagrams below, a solid arrow writes or publishes and a dotted arrow reads or subscribes.
The full table, with types and lifetimes, is in
[handoff's README](../../backend/handoff/README.md#store-key-families).

### Sessions, the lobby and the operator's channels

`SessionStore`, `LobbyPush`, the gateway's `ConnectionRegistry`, and `ArenaDirectory`'s operator
channel. All of them are on the session store.

```mermaid
flowchart LR
    PL["platform"]
    WK["worker"]
    GW["gateway"]
    AR["arena"]
    K1["sess:{token} hash, sess:of:{playerId} set"]
    K2["conn:{playerId}"]
    K3["push:{gatewayId} and push:all channels"]
    K7["arena-admin:{name} channel"]
    PL --> K1
    K1 -.-> PL
    K1 -.-> GW
    GW --> K2
    K2 -.-> PL
    K2 -.-> WK
    PL --> K3
    WK --> K3
    K3 -.-> GW
    PL --> K7
    K7 -.-> AR
```

### Matches and their results

`TicketStore`, `ArenaDirectory`, `SandboxHolds`, `TournamentGrants`, `MatchArrivals` and
`LeaderboardStore` are on the session store. `MatchResultStream` and `MatchResultQueue` are on the
events store.

```mermaid
flowchart LR
    PL["platform"]
    WK["worker"]
    AR["arena"]
    subgraph session["session store"]
        K4["ticket:{id}"]
        K5["arena:{name} hash, arenas set"]
        K6["rooms:promised:{arena}"]
        K8["lb:score:* boards, lb:name"]
        K9["marr:{matchUid}"]
        K10["sbx:{playerId}"]
        K11["tgrant:{t}:{playerId}, tcall:{playerId}"]
    end
    subgraph events["events store"]
        K12["s:match-result stream"]
        K13["q:match-result lists"]
    end
    PL --> K4
    WK --> K4
    K4 -.-> AR
    AR --> K5
    K5 -.-> PL
    K5 -.-> WK
    PL --> K6
    WK --> K6
    AR --> K6
    K6 -.-> PL
    K6 -.-> WK
    WK --> K8
    PL --> K8
    K8 -.-> PL
    AR --> K9
    K9 -.-> WK
    PL --> K10
    AR --> K10
    K10 -.-> PL
    WK --> K11
    K11 -.-> PL
    AR --> K12
    WK --> K12
    K12 -.-> WK
    WK --> K13
    K13 -.-> WK
```

Two arrows need a word:
- **`rooms:promised:{arena}`:** the arena only removes promises, in its announcement.
- **`s:match-result`:** a worker writes to it only when it moves the old list inbox into it.
