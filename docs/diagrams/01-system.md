# 01 — The system

This file draws the backend whole, for a reader who wants the big picture first: who uses it, what
runs, what each Maven module depends on, how a match goes from a login to its rewards, and how the
stateful parts fail over. The words are the [glossary](../glossary.md)'s. The reasons are in the
[topology](../architecture/01-system-topology.md), the
[availability model](../architecture/02-availability.md) and the
[decision log](../architecture/03-decision-log.md). The diagrams follow the code as of 2026-10-04,
the site's edge as of 2026-10-19 (D-82), and port 80 as of 2026-11-25 (D-115).

## 1. System context

Who and what the backend meets:

- **A player** uses only the Unity app. The app's engine-free core (`Backend.Client.Core`) does all
  the talking.
- **An operator** reaches the machines by SSH from the internal network, and then uses
  `platform`'s admin API (`AdminServer`) on loopback.
- **The backend's own CA** (certificate authority). The site has one public address, an IP with no
  domain, so no public CA certifies it. Its certificate comes from a CA of the backend's own, whose
  root the app ships ([operations/06](../operations/06-certificates.md)).
- **The payment provider** is simulated (`PaymentService`, D-68).
- **The storage host** for the backups' copy off the site is not chosen yet (Q-53).
- **The alerts' destination**, a webhook that the watch posts alerts to, is not named yet (Q-60,
  D-86). Until it is, alerts are listed by the admin API and written to the journal.

Solid arrows are live connections. Dotted arrows are not: they wait on the owner's choice, are
simulated, or, for the CA, happen by hand, off the servers, when a certificate is issued.

```mermaid
flowchart LR
    player["Player<br/>Unity app on Android"]
    operator["Operator<br/>SSH, scripts, admin API"]
    backend["The backend<br/>arena, gateway, platform, worker<br/>j-redis, MySQL, nginx"]
    provider["Payment provider<br/>simulated, Q-52"]
    offsite["Storage host off the site<br/>not chosen, Q-53"]
    hook["Alerts' webhook<br/>not named, Q-60"]
    ca["The backend's own CA<br/>its root in the app, its key off the servers"]
    player -->|"HTTPS, WSS and TLS matches on 443 and 9001, or in clear on 80, to one public address"| backend
    operator -->|"SSH from the internal network, then the admin API on loopback"| backend
    backend -.->|"confirms an order"| provider
    backend -.->|"encrypted dumps and binlogs, rsync over SSH"| offsite
    backend -.->|"alerts as JSON, HTTPS POST"| hook
    ca -.->|"one certificate, for nginx"| backend
```

## 2. Containers

Every process and store, what each one speaks, and the ports of the example settings in
`backend/deploy/env/` (every port is a setting).

- Everything a player sends reaches the front's nginx
  ([D-82](../architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name)).
  nginx ends TLS on 443 and 9001, and takes the same requests in clear on 80 (D-115).
- The API goes on to a platform, and the lobby to a gateway.
- Match traffic goes to the arena that its name gives: on 9001 the TLS name, on 80 the name in
  `GET /match/<name>`. nginx does not read the frames, and match traffic never passes the gateway
  (D-5).
- Pushes and an operator's room commands are published on the `session` store, and reach the
  gateway and the arena by pub/sub.
- Each process also serves its metrics, in the Prometheus text format, on a loopback address
  (`BACKEND_METRICS_ADDR`). The watch in each platform reads them (D-86). Neither is drawn.

The diagram is drawn for three machines. At launch all of it is on one machine, the front, on
loopback ([D-93](../architecture/03-decision-log.md#d-93--the-site-launches-on-one-machine-for-up-to-about-5-000-players),
[07](07-deploy-and-operations.md#the-launchs-one-machine)).

```mermaid
flowchart LR
    app["Client<br/>Backend.Client.Core, or client-simple"]
    op["Operator"]
    subgraph edgeTier["The front, one of the three machines"]
        nginx["nginx<br/>443 and 9001, ends TLS<br/>80 in clear"]
    end
    subgraph procs["Processes: gateway, platform and arenas on each machine, the workers on C"]
        gateway["gateway<br/>8090 on the private address"]
        platform["platform<br/>8080 on the private address<br/>admin API 9120 on loopback"]
        arena["arena<br/>9011 to 9013 on the private address"]
        worker["worker"]
    end
    subgraph stores["State"]
        session["j-redis session<br/>6379"]
        events["j-redis events<br/>6380"]
        mysql["MySQL primary<br/>3306, TLS"]
    end
    app -->|"HTTPS /v1/, or HTTP on 80"| nginx
    app -->|"WSS /lobby, or WS on 80"| nginx
    app -->|"binary frames, TLS on 9001, or in clear on 80 after GET /match/name"| nginx
    nginx -->|"HTTP"| platform
    nginx -->|"WebSocket"| gateway
    nginx -->|"the same frames, by the arena's name"| arena
    gateway -->|"HTTP /v1/match-requests, /v1/queue, /v1/party"| platform
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

What each module of `backend/` depends on, read from its `pom.xml`. An arrow points from a module
to what it uses.

- `arena` never reaches MySQL: nothing it depends on brings a database driver. That is why
  `handoff` exists.
- `platform` sees `arena` in its tests only.
- `platform` uses Nimbus JOSE + JWT to check the account service's ES256 tokens (`AccountTokens`, D-117). The
  account service, a project of its own beside `backend/` (`account-service/`), hashes the passwords with Bouncy
  Castle's `bcprov`, as the platform did until D-117. `crypto-examples` uses Bouncy Castle too, and is never in the
  release.

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
    cx["crypto-examples<br/>never in the release"]
    jredis["j-redis-client 2.2.2"]
    netty["Netty 4.2"]
    mysqllibs["mysql-connector-j<br/>HikariCP, Flyway"]
    bc["Bouncy Castle<br/>bcprov, bcpkix"]
    nimbus["Nimbus JOSE + JWT 10.10"]
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
    platform -->|"the account service's tokens"| nimbus
    platform -.->|"tests only"| arena
    worker --> handoff
    worker --> persistence
    tools --> sim
    tools --> arena
    tools --> handoff
    tools --> persistence
    cx --> bc
```

## 4. From a login to the rewards

The main path, for a queued duel. The classes involved:

- the login: `AuthService` and `SessionStore`;
- the lobby: `LobbyHandler` and `ConnectionRegistry`;
- the queue, the confirm step and the tickets: `QueueService`, `MatchQueue` and `Matchmaker`;
- the join: `TicketStore` and `RoomThread`;
- the spool and the stream: `MatchResultPublisher`;
- the result: `MatchResultConsumer` and `MatchResultRepository`;
- every push: `LobbyPush`. Each push goes on the channel of the gateway that holds the player's
  connection, which the key `conn:` names.

The full client and `client-simple` reach the arena over TLS, through the front's 9001.
`client-simple-no-tls` reaches it in clear, through the front's 80 (D-115), shown as the second
branch.

```mermaid
sequenceDiagram
    participant P as client
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
    G-->>P: evt.match.found, arena host, port, ticket, TLS name
    alt over TLS, the full client or client-simple
        P->>A: TLS to the front's 9001 with the TLS name, nginx passing it on
    else in clear, client-simple-no-tls
        P->>A: TCP to the front's 80, GET /match/ with the TLS name, the arena answers 101
    end
    P->>A: Join with the ticket
    A->>S: claim the ticket, MULTI HGETALL DEL EXEC
    A-->>P: Welcome, then snapshots 15 a second
    P->>A: Input, 10 packets a second
    Note over A: the duel ends at three kills or on the clock
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

The three stateful roles, each a primary with a replica on another machine, in the three-machine
layout of the topology. At launch the site is one machine, with no replica, and nothing here fails
over until there is a second machine (D-93, topology §2).

- Promotion is a script that an operator runs (D-8).
- An epoch, a number that only a promotion raises, keeps every process off an old primary.
- Each promotion script refuses to make two primaries (D-95): it refuses an old primary that still
  answers when the operator said it was down, and one it cannot fence when asked to fence it.

The classes and scripts: `StoreClients` and the j-redis client (the stores), `PrimaryDataSource`
(MySQL), `ReplicaWatch` (the heartbeat and the lag), `MatchResultPublisher` (the wait for the
`events` replica), `promote-store.sh` and `promote-mysql.sh`.

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
    promS -.->|"refuses an old primary still answering, or one it cannot demote, then promotes and raises the epoch"| sessR
    promS -.-> evR
    promM -.->|"refuses a stale heartbeat or a live old primary, fences it in a handover, raises ha_epoch"| myR
    watch --> myP
    watch -.-> myR
    arenaW -.->|"counts the results not confirmed"| evR
```

### Leases

A job that only one process of many must run is taken by a lease in the `session` store: `SET NX`
with an expiry, given back when done. No lease carries a fencing token, because each job is safe
to repeat (availability §5). The classes are `Matchmaker`, `LedgerCheck` and `SeasonKeeper`. The
tournament clock (`TournamentScheduler`) runs in every worker and needs no lease: it claims a match
in MySQL instead (D-41).

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
    work -->|"in every worker, no lease"| ts
```
