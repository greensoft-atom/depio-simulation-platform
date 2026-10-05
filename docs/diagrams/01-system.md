# 01 — The system

The backend drawn whole: who uses it, what runs, what each module depends on,
how a match goes from a login to its rewards, and how the stateful parts fail
over. The words are the [glossary](../glossary.md)'s; the reasons are in the
[topology](../architecture/01-system-topology.md), the
[availability model](../architecture/02-availability.md) and the
[decision log](../architecture/03-decision-log.md). Drawn from the code as of
2026-10-04.

## 1. System context

Who and what the backend meets. A player uses only the Unity app, whose
engine-free core (`Backend.Client.Core`) does all the talking; an operator
reaches the machines by SSH and `platform`'s admin API (`AdminServer`). The
payment provider is simulated (`PaymentService`, D-68), and the host for the
backups' copy off the site is not chosen yet (Q-53).

```mermaid
flowchart LR
    player["Player<br/>Unity app on iOS or Android"]
    operator["Operator<br/>SSH, scripts, admin API"]
    backend["The backend<br/>arena, gateway, platform, worker<br/>j-redis, MySQL, nginx"]
    provider["Payment provider<br/>simulated, Q-52"]
    offsite["Storage host off the site<br/>not chosen, Q-53"]
    ca["Certificate authority<br/>deferred by the owner"]
    player -->|"HTTPS, WSS, TCP with TLS"| backend
    operator -->|"SSH, then the admin API on loopback"| backend
    backend -.->|"confirms an order"| provider
    backend -.->|"encrypted dumps and binlogs, rsync over SSH"| offsite
    ca -.->|"certificate for nginx and the arenas"| backend
```

## 2. Containers

Every process and store, with what each speaks and the ports of the example
settings in `backend/deploy/env/` (all of them settings). Match traffic goes
straight to an arena, never through nginx or the gateway (D-5); the lobby and
the API go through nginx. Pushes and an operator's room commands are
published on the `session` store and reach the gateway and the arena by
pub/sub. Each process also serves Prometheus metrics on a loopback address
(`BACKEND_METRICS_ADDR`), not drawn.

```mermaid
flowchart LR
    app["Client core<br/>Backend.Client.Core"]
    op["Operator"]
    subgraph edgeTier["Each machine's edge"]
        nginx["nginx<br/>443 TLS, 80 for the CA only"]
    end
    subgraph procs["Processes"]
        gateway["gateway<br/>8090 on loopback"]
        platform["platform<br/>8080 on loopback<br/>admin API 9120 on loopback"]
        arena["arena<br/>9001, TCP with TLS"]
        worker["worker"]
    end
    subgraph stores["State"]
        session["j-redis session<br/>6379"]
        events["j-redis events<br/>6380"]
        mysql["MySQL primary<br/>3306, TLS"]
    end
    app -->|"HTTPS /v1/"| nginx
    app -->|"WSS /lobby"| nginx
    app -->|"binary frames, TCP with TLS"| arena
    nginx -->|"HTTP"| platform
    nginx -->|"WebSocket"| gateway
    gateway -->|"HTTP /v1/queue"| platform
    gateway -->|"sessions, conn: registry"| session
    session -.->|"pub/sub push: channels"| gateway
    platform -->|"sessions, tickets, queues, parties, leases, pushes"| session
    platform -->|"JDBC"| mysql
    session -.->|"pub/sub arena-admin: channel"| arena
    arena -->|"ticket claim, directory"| session
    arena -->|"XADD s:match-result"| events
    worker -->|"XREADGROUP rewards"| events
    worker -->|"JDBC"| mysql
    worker -->|"score boards, pushes, leases, tournament tickets"| session
    op -->|"admin API, over SSH"| platform
```

## 3. Maven modules

What each module of `backend/` depends on, from its `pom.xml`. `arena` never
reaches MySQL: nothing it depends on brings a database driver, which is why
`handoff` exists. `platform` sees `arena` in its tests only.

```mermaid
flowchart BT
    common["common<br/>metrics, logging, secrets"]
    protocol["protocol<br/>the wire and its reference client"]
    sim["sim<br/>the simulation, no IO"]
    handoff["handoff<br/>what processes share through j-redis"]
    persistence["persistence<br/>MySQL, Flyway, HikariCP"]
    arena["arena"]
    platform["platform"]
    worker["worker"]
    gateway["gateway"]
    tools["tools<br/>benchmarks, bots, soak judge"]
    jredis["j-redis-client 2.2.1"]
    netty["Netty 4.2"]
    mysqllibs["mysql-connector-j<br/>HikariCP, Flyway"]
    sim --> common
    handoff --> common
    handoff --> jredis
    persistence --> common
    persistence --> mysqllibs
    arena --> sim
    arena --> protocol
    arena --> handoff
    arena --> netty
    gateway --> handoff
    gateway --> netty
    platform --> handoff
    platform --> persistence
    platform --> sim
    platform -.->|"tests only"| arena
    worker --> handoff
    worker --> persistence
    tools --> sim
    tools --> arena
    tools --> handoff
    tools --> persistence
```

## 4. From a login to the rewards

The main path, a queued duel. The classes: `AuthService` and `SessionStore`
(login), `LobbyHandler` and `ConnectionRegistry` (the lobby), `QueueService`,
`MatchQueue` and `Matchmaker` (queue, confirm step, tickets), `TicketStore`
and `RoomThread` (the join), `MatchResultPublisher` (the spool and the
stream), `MatchResultConsumer` and `MatchResultRepository` (the result), and
`LobbyPush` (every push). Each push goes on the channel of the gateway that
holds the player's connection, found by `conn:`.

```mermaid
sequenceDiagram
    participant P as client core
    participant G as gateway
    participant PL as platform
    participant S as session store
    participant A as arena
    participant E as events store
    participant W as worker
    participant M as MySQL
    P->>PL: POST /v1/sessions, through nginx
    PL->>M: check the Argon2id hash
    PL->>S: write the session, a day
    PL-->>P: the token
    P->>G: WSS /lobby through nginx, auth with the token
    G->>S: read the session, register conn:playerId
    P->>G: queue.join duel
    G->>PL: POST /v1/queue
    PL->>S: the entry in the mode's queue
    Note over PL,S: the matcher, under the mm:leader lease, pairs by rating
    PL->>S: publish evt.match.ready
    S-->>G: on the gateway's push: channel
    G-->>P: evt.match.ready, ten seconds to answer
    P->>G: match.accept
    G->>PL: the answer
    PL->>S: a ticket each, the room promised
    PL->>S: publish evt.match.found with the grant
    S-->>G: on the push: channel
    G-->>P: evt.match.found, arena host, port, ticket, TLS
    P->>A: TCP with TLS, Join with the ticket
    A->>S: claim the ticket, MULTI HGETALL DEL EXEC
    A-->>P: Welcome, then snapshots 15 a second
    P->>A: Input, 10 packets a second
    Note over A: the duel ends at three kills or the clock
    A->>A: spool the result to disk
    A->>E: XADD s:match-result, then wait for the replica
    A-->>P: Kick 6, match over
    W->>E: XREADGROUP as the group rewards
    W->>M: one transaction: xp, coins, rating, stats, goals, the pass
    W->>S: publish evt.rewards
    S-->>G: on the push: channel
    G-->>P: evt.rewards
    W->>S: the score boards, ZADD GT
    W->>E: XACK
```

## 5. High availability

### Primaries, replicas and promotion

The three stateful roles, each a primary with a replica on another machine, on
the three-machine layout of the topology. What runs where at launch on two
machines is still open (topology §2). Promotion is a script an operator runs
(D-8); an epoch only a promotion raises keeps every process off an old primary.
The classes and scripts: `StoreClients` and the j-redis client (the stores),
`PrimaryDataSource` (MySQL), `ReplicaWatch` (the heartbeat and the lag),
`MatchResultPublisher` (the wait for the `events` replica),
`promote-store.sh` and `promote-mysql.sh`.

```mermaid
flowchart LR
    subgraph MA["Machine A"]
        sessP["j-redis session primary"]
        myP["MySQL primary<br/>ha_epoch, ha_heartbeat"]
    end
    subgraph MB["Machine B"]
        evP["j-redis events primary"]
        sessR["j-redis session replica<br/>read-only"]
    end
    subgraph MC["Machine C"]
        evR["j-redis events replica<br/>read-only"]
        myR["MySQL replica<br/>GTID, read-only"]
    end
    procs["Every process<br/>both addresses of each store it uses<br/>platform and worker both MySQL hosts<br/>the primary with the highest epoch seen"]
    promS["promote-store.sh<br/>on the replica's machine"]
    promM["promote-mysql.sh<br/>on the replica's machine"]
    watch["worker ReplicaWatch<br/>stamps the heartbeat each second<br/>reads each replica's lag"]
    arenaW["arena<br/>WAIT 1 100 after each result"]
    sessP -->|"replication stream, epoch"| sessR
    evP -->|"replication stream, epoch"| evR
    myP -->|"GTID replication"| myR
    procs --> sessP
    procs --> evP
    procs --> myP
    promS -.->|"refuses while the old primary answers, promotes, raises the epoch"| sessR
    promS -.-> evR
    promM -.->|"fences the old primary, raises ha_epoch"| myR
    watch --> myP
    watch -.-> myR
    arenaW -.->|"counts the results not confirmed"| evR
```

### Leases

Jobs that one process of many must run are taken by a lease in the `session`
store, `SET NX` with an expiry, and given back when done. None carries a
fencing token: each job is safe to repeat (availability §5). The classes:
`Matchmaker`, `LedgerCheck`, `SeasonKeeper`; the tournament clock
(`TournamentScheduler`) runs in every worker and claims a match in MySQL
instead (D-41).

```mermaid
flowchart LR
    plat["every platform"]
    work["every worker"]
    mm["mm:leader<br/>the matcher, 5 s, renewed each round"]
    lc["job:ledger-check<br/>the daily ledger check, 23 h"]
    se["job:season<br/>a season's close, 10 min"]
    ts["tournament clock<br/>a match claimed in MySQL before its tickets"]
    plat -->|"SET NX PX"| mm
    work -->|"SET NX EX"| lc
    work -->|"SET NX EX"| se
    work --> ts
```
