# Diagrams: platform

The flows of [04 — Platform services](../detailed-design/04-platform-services.md), drawn from the
code in `backend/platform` (and, where a flow continues there, `handoff`, `persistence` and
`worker`). Every route, answer and refusal named here is listed in the
[platform README](../../backend/platform/README.md).

## Register and log in

`PlatformHttpServer.register` and `login`, `PasswordHasher.admit`, `LoginThrottle.attempt`,
`AuthService.register` and `login`, `handoff/SessionStore.open`. Every check that can refuse runs
before Argon2, so a refusal costs no hash; a refusal for load is answered before anything is
counted.

```mermaid
sequenceDiagram
    participant C as client
    participant N as nginx
    participant P as platform
    participant S as store
    participant D as MySQL
    C->>N: POST /v1/accounts with username, password, displayName
    N->>P: forwarded, X-Forwarded-For appended
    P->>P: a place in the hasher's line, else 503 busy
    P->>S: count the address, LoginThrottle
    alt over 30 a minute
        P-->>C: 429 too_many_attempts with Retry-After
    else allowed
        P->>P: username, password and display name rules, else 400
        P->>P: Argon2id hash, m 19 MiB, t 2, p 1
        P->>D: insert the account and its player
        P-->>C: 201 playerId, or 409 username_taken
    end
    C->>N: POST /v1/sessions with username and password
    N->>P: forwarded
    P->>P: a place in the hasher's line, else 503 busy
    P->>S: count the address and the account
    alt over a limit
        P-->>C: 429 too_many_attempts with Retry-After
    else allowed
        P->>D: find the account by its username
        P->>P: verify, against a decoy hash when there is no account
        alt wrong password or no such account
            P-->>C: 401 invalid_credentials
        else banned, or suspended until later
            P-->>C: 403 banned
        else verified
            P->>D: rehash if the stored cost is old, record the login time
            P->>S: MULTI, HSET the session, EXPIRE a day and up to 10 percent either way, index it by player, EXEC
            P-->>C: 200 token, playerId, expiresInSeconds
        end
    end
```

## Guests and the upgrade

`AuthService.createGuest`, `loginGuest` and `upgrade`; `persistence/AccountRepository.createGuest`,
`findGuest`, `upgradable` and `upgrade`. A guest's key is never stored, only its SHA-256, and its
login hashes nothing slow.

```mermaid
sequenceDiagram
    participant C as client
    participant P as platform
    participant S as store
    participant D as MySQL
    C->>P: POST /v1/guests
    P->>S: count the address, else 429
    P->>P: 32 random bytes, the key, and a name Guest and four digits
    P->>D: the account under a username nobody can register, the key's SHA-256
    P-->>C: 201 playerId, guestKey, displayName
    Note over C: the key is kept on the device
    C->>P: POST /v1/sessions with guestKey
    P->>S: count the address, else 429
    P->>D: find the account by the key's SHA-256
    alt none, or a malformed key
        P-->>C: 401 invalid_credentials
    else banned
        P-->>C: 403 banned
    else found
        P->>S: open a session
        P-->>C: 200 token, playerId, expiresInSeconds
    end
    C->>P: POST /v1/accounts/upgrade with the session, username, password
    P->>P: a place in the hasher's line, then the address counted
    P->>S: the session's player
    P->>P: the rules of registering
    P->>D: a guest, and the name free, before any hash
    alt not a guest, or the name taken
        P-->>C: 409 not_a_guest or username_taken
    else upgradable
        P->>P: Argon2id hash
        P->>D: username and hash set, the key cleared, in one statement
        P-->>C: 200, the same player, the key no longer works
    end
```

## A seat in the public arena

`JoinService.requestJoin`, `EquipmentService.bonusOf` and `skinOf`, `handoff/ArenaDirectory.pick` and `promiseSeat`
and `TicketStore`; the arena claims the ticket in `MatchFrameHandler`. The ticket carries
everything the arena needs, so the arena never reads MySQL.

```mermaid
sequenceDiagram
    participant C as client
    participant P as platform
    participant S as store
    participant D as MySQL
    participant A as arena
    C->>P: POST /v1/match-requests with the session
    P->>S: the session's player, one HGET
    P->>D: the profile, and what the player wears and holds
    P->>S: the live arenas, the one with the most free places less its seats promised (D-79)
    alt no arena has a place
        P-->>C: 503 no_arena
    else an arena
        P->>S: the seat promised to the player, 60 s
        P->>S: MULTI, HSET ticket with player, name, team 0, bonus, skin, EXPIRE 60, EXEC
        P-->>C: 200 arenaHost, arenaPort, ticketId, tls
        C->>A: connect, Join with the ticket
        A->>S: MULTI, HGETALL ticket, DEL ticket, EXEC
        A-->>C: Welcome, or Kick with a reason
    end
```

## The queue and the confirm step: a player's record

`MatchQueue`'s record of a player, `mmp:{playerId}`, which decides whether they are queued, asked
or matched; each move is one watched transaction (D-55). The record expires, which returns a
player stuck by a crash to none.

```mermaid
stateDiagram-v2
    [*] --> none
    none --> queued : joins, the record kept 900 s
    queued --> none : leaves, or dropped with no lobby connection or called to a tournament
    queued --> confirming : the matcher asks, the record kept 60 s
    confirming --> matched : everyone accepted, a room promised, tickets written
    confirming --> queued : another declined or withdrew, or no room, back where it was
    confirming --> none : declined or silent and locked 60 s, or the party changed
    matched --> none : the ticket lapses after 60 s, or leaves
    none --> matched : opens a sandbox
    matched --> [*]
```

## The queue and the confirm step: a round

`QueueService.join` and `answer`, `Matchmaker.round`, `settle`, `match`, `lineups`, `make` and
`callOff`, and `MatchQueue.join`, `ask`, `answer`, `make` and `end`. Only the platform holding
`mm:leader` runs the round; a step overtaken by another writes nothing, and the next round reads
again.

```mermaid
sequenceDiagram
    participant C as players
    participant P as platform
    participant M as matcher
    participant S as store
    participant G as gateways
    C->>P: POST /v1/queue with the mode, by a player or a party's leader
    P->>S: watched MULTI, every member's record queued, ZADD mmq for the mode
    P->>G: evt.queue.update to the party's other members
    loop every second, while holding the lease
        M->>S: SET mm:leader NX PX 5000, or renew it
        M->>S: settle the matches asked about, below
        M->>S: read each mode's queue, drop stale entries
        M->>S: drop entries with a member with no lobby or called to a tournament
        M->>M: line up sides, oldest first, inside its rating window
        M->>S: ask, watched MULTI, entries out of mmq, the match in mmc, members confirming
        M->>G: evt.match.ready with matchUid and 10 seconds
    end
    C->>P: POST /v1/queue/accept or decline with matchUid
    P->>S: the answer in the player's own record, watched
    alt every player accepted
        M->>S: reserveForMatch, a room promised to an arena
        M->>S: a ticket for each player, team by side
        M->>S: make, watched MULTI, each player matched with the grant, the match out of mmc
        M->>G: evt.match.found to each
    else a decline, a withdrawal, or 10 s of silence
        M->>S: end, watched MULTI, offending entries out, decliners and the silent locked 60 s, the rest back
        M->>G: evt.queue.update to each
    else accepted, no arena with a room
        M->>S: end, every entry back, first in line
        M->>G: evt.queue.update to each
    end
```

## A party, its versions

`PartyService` and `Parties`; the client keeps the newest state by `PartyState` (D-74). Each change
reads what it depends on under a watch and writes in one transaction, its version one more; an
answer and a push may arrive in either order, and the client applies only another party or a
higher version.

```mermaid
sequenceDiagram
    participant A as Ada the leader
    participant B as Bo
    participant P as platform
    participant S as store
    participant G as gateways
    A->>P: POST /v1/party/invite with Bo's id
    P->>S: watched, Ada's party made, version 1, an invitation for 60 s
    P->>G: evt.party.invite to Bo, if in the lobby and not blocking Ada
    P-->>A: 200 the party, version 1
    B->>P: POST /v1/party/accept with partyId
    P->>S: watched, the invitation, room, the leader neither queued nor asked
    P->>S: MULTI, Bo added, version 2, the invitation spent
    P->>G: evt.party.update, version 2, to Ada and Bo
    P-->>B: 200 the party, version 2
    Note over A,B: each applies a state only if it names another party or a higher version
    B->>P: POST /v1/party/leave
    P->>S: MULTI, a party of one is no party, the party and both pointers deleted
    P->>S: the entry out of the queue, or withdrawn from a match asked about
    P->>G: evt.party.update to both, partyId null, was the party, version 3
    P-->>B: 200 the same state
```

## A team action

`TeamService` and `persistence/TeamRepository`. Each action is one MySQL transaction that locks the
team first, then the players it changes (D-39); the pushes follow the commit, and a refusal tells
nobody.

```mermaid
flowchart TD
    R["request to /v1/teams, /v1/team-invites or /v1/team-applications"] --> T{"session valid"}
    T -->|no| E1["401 invalid_session"]
    T -->|yes| N{"a name given"}
    N -->|yes| NC{"DisplayName rules"}
    NC -->|no| E2["400 invalid_name"]
    NC -->|yes| K
    N -->|no| A{"an invitation or an application"}
    A -->|yes| L{"20 an hour, AskThrottle"}
    L -->|over| E3["429 too_soon"]
    L -->|under| K
    A -->|no| K["one transaction: the team row locked, then each player by id, ascending"]
    K --> O{"outcome"}
    O -->|refused| E4["4xx with its code, nothing changed, nobody told"]
    O -->|ok| W["the team read after the commit"]
    W --> P1["evt.team.update with the team to every member"]
    W --> P2["evt.team.update with null to one who left, was kicked or disbanded"]
    W --> P3["an inbox row and evt.inbox for an invitation or an application"]
    W --> AN["200: the team, the remaining invitations, or an empty object"]
```

## A tournament

`persistence/TournamentRepository` holds the state; `worker/TournamentScheduler` moves it every
5 s, each move a conditional update on the state and version, so two workers cannot move it twice.
`POST /admin/tournaments` creates it; players enter while it registers.

```mermaid
stateDiagram-v2
    [*] --> registration : created by an operator
    registration --> seeded : the deadline, two entries or more, the bracket written
    registration --> cancelled : the deadline, fewer than two entries
    seeded --> running : startsAt, round 1
    running --> running : every match of a round done, the next round after the minutes between rounds
    running --> finished : the last round done, the prizes paid
    finished --> [*]
    cancelled --> [*]
```

## A tournament match

A match of the bracket, in `TournamentScheduler.make` and `settle`. A match nobody came to is
decided 270 s after its tickets; one somebody came to waits for its result up to 30 minutes
(`marr:{matchUid}`).

```mermaid
stateDiagram-v2
    [*] --> pending : seeded, or a side decided
    pending --> ready : a room promised, every ticket written, then claimed with a new match id
    pending --> pending : no room, or the claim lost, tickets revoked, the next tick tries again
    ready --> done : a result with one side first, the winner
    ready --> done : a draw, a result cut short, or no result in time, the higher seed or a draw in a round robin
    done --> [*]
```

## The scheduler's round

`TournamentScheduler.tick`, `step`, `run`, `make`, `settle` and `over`. The grant is kept for the
ticket's 60 s (`tgrant:`), and the player called (`tcall:`), which the queue reads as a match.

```mermaid
flowchart TD
    T["every 5 s, every worker: tournaments registering, seeded or running"] --> S{"state"}
    S -->|registration| R1{"deadline passed"}
    R1 -->|yes| R2["close: seed, or cancel with fewer than two"]
    R1 -->|no| X["nothing this tick"]
    S -->|seeded| S1{"startsAt passed"}
    S1 -->|yes| S2["start: running, round 1"]
    S1 -->|no| X
    S -->|running| U["each match of the current round not done"]
    U --> Q{"match state"}
    Q -->|pending| O{"round 1, or the minutes between rounds passed"}
    O -->|no| X
    O -->|yes| M1["reserveForMatch, a room promised"]
    M1 --> M2["a ticket each seat, no bonus, teams by side"]
    M2 --> M3{"claimed, pending to ready"}
    M3 -->|lost| M4["tickets revoked, the promise given back"]
    M3 -->|won| M5["grant kept 60 s, tcall set, evt.tournament.match pushed"]
    Q -->|ready| D1{"a result in MySQL"}
    D1 -->|yes| D2["decide: one first wins, a draw or a cut short the higher seed, or a draw in a round robin"]
    D1 -->|no| D3{"270 s passed, and nobody came or 30 min passed"}
    D3 -->|no| X
    D3 -->|yes| D4["the higher seed, or a draw in a round robin"]
    D2 --> V{"every match of the round done"}
    D4 --> V
    V -->|no| X
    V -->|yes| V2{"the last round"}
    V2 -->|no| W1["end the round"]
    V2 -->|yes| W2["pay places 1 to 3, coins and gems, inbox, then finished"]
```

## A simulated payment order: its states

`persistence/PaymentRepository`: an order moves only forward, each step under its row's lock.
`worker`'s retention expires what waited a day.

```mermaid
stateDiagram-v2
    [*] --> pending : ordered, the same order for a key used again
    pending --> paid : confirmed paid, the gems granted, twice on a first order
    pending --> declined : confirmed declined, nothing granted
    pending --> expired : a day without a confirm
    paid --> refunded : an operator refunds it, gems taken back, the rest a debt
    declined --> [*]
    expired --> [*]
    refunded --> [*]
```

## A simulated payment order: the calls

`PaymentService`, `persistence/PaymentRepository` and `AdminRepository.refund` and `clearDebt`.
Every payment route answers 503 `payments_off` unless `BACKEND_PAYMENT_PROVIDER=simulated`.

```mermaid
sequenceDiagram
    participant C as client
    participant P as platform
    participant D as MySQL
    participant O as operator
    C->>P: POST /v1/payments with productId and a key
    P->>D: place, a debt refuses, a key used finds its order
    alt the player owes a refund's debt
        P-->>C: 409 refund_debt
    else ordered
        P-->>C: 200 the order, pending
    end
    C->>P: POST /v1/payments/orderId/simulate, outcome paid
    P->>D: the order is the player's own, else 404 no_such_order
    P->>D: confirm under the order's lock, the pack's gems by the ledger keyed by the order
    P->>D: on the player's first paid order, as many again, keyed by the player
    P-->>C: 200 the order, confirmed, the gems after
    O->>P: POST /admin/payments/orderId/refund with a reason
    P->>D: one transaction, paid to refunded, gems and bonus taken back as far as the balance allows, the rest a debt, the audit row
    P-->>O: 200 orderId, playerId, taken, debt
    C->>P: POST /v1/payments for another pack
    P-->>C: 409 refund_debt
    O->>P: POST /admin/players/playerId/refund-debt with a reason
    P->>D: the debt cleared, audited in the same transaction
    P-->>O: 200 playerId, cleared
```

## The season pass crossing tiers

`persistence/SeasonPassRepository.earn`, `pay` and `buyPremium`, the table in `SeasonPass`. Points
are earned in `worker`'s result transaction; premium is bought through `platform`. A track's mark
moves with what it paid, so each tier pays once.

```mermaid
flowchart TD
    A["worker applies a result, the player locked"] --> B["points: 10 for a paid result, 50 for each daily goal it meets"]
    B --> C["the season being played's pass row, points added"]
    P1["POST /v1/pass/premium"] --> P2{"premium already"}
    P2 -->|yes| P3["200 already_bought"]
    P2 -->|no| P4{"the season's end passed"}
    P4 -->|yes| P5["409 season_ended"]
    P4 -->|no| P6{"500 gems by the ledger"}
    P6 -->|short| P7["409 insufficient_funds"]
    P6 -->|taken| P8["premium set for the season"]
    C --> T["tier reached: points over 250, at most 40"]
    P8 --> T
    T --> F["each free tier after the free mark, up to the tier reached"]
    F --> F1["150 coins, or 5 gems every fifth tier"]
    T --> Q{"premium bought"}
    Q -->|yes| G["each premium tier after the premium mark"]
    G --> G1["15 gems, and a boost every fifth tier"]
    Q -->|no| H["the premium mark stays"]
    F1 --> L["by the ledger, reason 11, keyed by season, track, tier and player, boosts into the inventory"]
    G1 --> L
    L --> M["both marks moved to the tier reached"]
    M --> N["evt.rewards carries the pass, from worker, or 200 bought from premium"]
```

## Buying, wearing and boosting

`ShopService.buy`, `EquipmentService.wear` and `bonusOf`, `BoostService.activate`, with
`persistence/EconomyRepository`, `EquipmentRepository` and `BoostRepository`. Each key is the
client's, made once a tap and sent with every retry, so a retry is answered as the first attempt
was.

```mermaid
flowchart TD
    subgraph buy ["POST /v1/purchases"]
        B1["sku and key"] --> B2{"key 16 to 48 of a-z, 0-9 and hyphen"}
        B2 -->|no| B3["400 invalid_key"]
        B2 -->|yes| B4{"key used before"}
        B4 -->|yes| B5["200 already_bought, what it bought"]
        B4 -->|no| B6{"offer known and on sale"}
        B6 -->|no| B7["404 unknown_sku or 409 not_available"]
        B6 -->|yes| B8{"account level high enough"}
        B8 -->|no| B9["403 level_required"]
        B8 -->|yes| B10{"one transaction, the player locked, coins or gems by the ledger"}
        B10 -->|short| B11["409 insufficient_funds"]
        B10 -->|done| B12["200 bought, the balance and how many held"]
    end
    subgraph wear ["PUT /v1/equipment/slot"]
        W1["itemId"] --> W2{"slot and item known, the item for that slot"}
        W2 -->|no| W3["400 invalid_slot, 404 unknown_item or 409 wrong_slot"]
        W2 -->|yes| W4{"held"}
        W4 -->|no| W5["409 not_owned"]
        W4 -->|yes| W6["worn, 200 the loadout, the bonus capped at 25 a stat"]
        W6 --> W7["the next ticket carries the bonus and the skin"]
    end
    subgraph boost ["POST /v1/boosts"]
        X1["itemId and key"] --> X2{"key valid, item a boost"}
        X2 -->|no| X3["400 invalid_key or 404 unknown_item"]
        X2 -->|yes| X4{"one held, no other of its kind running"}
        X4 -->|no| X5["409 not_owned or other_running"]
        X4 -->|yes| X6["one taken, it runs, or the same item's minutes added, 200 activated"]
        X6 --> X7["worker raises a match's xp or coins if it ended while the boost ran"]
    end
```

## An operator's ban, kick and notice

`net/AdminServer.player`, `kickEverywhere` and `notice`; `persistence/AdminRepository`;
`handoff/SessionStore.revokeAll`, `LobbyPush` and `ArenaDirectory.command`. Every call is checked
against the secret and audited, refusals included.

```mermaid
sequenceDiagram
    participant O as operator
    participant A as admin API
    participant D as MySQL
    participant S as store
    participant G as gateways
    participant R as arenas
    O->>A: POST /admin/players/42/ban with a reason, and until for a suspension
    A->>A: the secret, the reason, until an instant in the future
    A->>D: the account's status and the audit row, one transaction
    A->>S: every session of the player revoked
    alt the sessions cannot be ended
        A-->>O: 503 sessions_not_ended, the ban recorded, call again
    else ended
        A->>G: evt.session.revoked, the lobby connection closed
        A->>R: kick with ban, on each live arena's channel
        R->>R: the player out of any match, Kick 7, their tickets refused for 60 s
        A-->>O: 200 playerId, status, sessionsEnded
    end
    O->>A: POST /admin/players/42/kick with a reason
    A->>R: kick, on each live arena's channel
    A->>D: audited, sent
    A-->>O: 202 playerId and the arenas that heard
    O->>A: POST /admin/notice with text and a reason
    A->>A: text 1 to 200 characters, none a control or direction character
    A->>D: audited first, so nothing goes out unrecorded
    A->>G: evt.notice on push all, to everyone in every lobby
    A-->>O: 202 the gateways that heard
```
