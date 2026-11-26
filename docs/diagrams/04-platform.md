# Diagrams: platform

This file draws the flows of [04 — Platform services](../detailed-design/04-platform-services.md):
accounts, the queue, parties, teams, tournaments, payments, the shop and the admin API. It is for
engineers working on `platform`, or on a client that calls it. The diagrams are drawn from the code
in `backend/platform`, and in `handoff`, `persistence` and `worker` where a flow continues there.
Every route, answer and refusal named here is listed in the
[platform README](../../backend/platform/README.md).

A client calls the API through the front's nginx: over HTTPS on 443, or in clear on 80 (D-115).
nginx passes both to a platform the same way, so the diagrams draw one nginx. Where a diagram has no
nginx, the client's calls still pass it.

## Log in by the account service's token

Since D-117 an account signs up and logs in at the account service, which hashes the password and
signs an access token ([10](../detailed-design/10-accounts.md)); the platform takes that token once
for a session. The classes: `PlatformHttpServer.login`, `AuthService.exchange`, `AccountTokens.access`,
`persistence/AccountRepository.findBySubject`, `erasedSubject` and `createForSubject`, and
`handoff/SessionStore.create`.

- The token is checked by the account service's published keys, kept five minutes: a login asks the
  account service nothing, unless the token names a key the set lacks (10 §4.2).
- Every reason a token is not taken gets the one answer, 401 `invalid_token`; why is logged.
- Before the platform, nginx limits `/v1/sessions` to 2 requests a second for each address, with a
  burst of 30 (429).

```mermaid
sequenceDiagram
    participant C as client
    participant AS as account service
    participant N as nginx
    participant P as platform
    participant S as store
    participant D as MySQL
    C->>AS: POST /account/v1/sessions with username and password
    AS-->>C: access token 15 min, refresh token
    C->>N: POST /v1/sessions with accessToken
    N->>P: forwarded, X-Forwarded-For appended
    P->>P: ES256 by a key of the set, at+jwt, issuer, audience, times, claims
    opt a key the set lacks, at most twice in 30 s
        P->>AS: GET /.well-known/jwks.json on the private network
    end
    alt not taken
        P-->>C: 401 invalid_token
    else no key set to be had
        P-->>C: 503 keys_unavailable
    else taken
        P->>D: the player of the token's subject
        alt the subject was erased
            P-->>C: 401 invalid_token
        else no player yet
            P->>D: account, player and stats for the subject, named from preferred_username
        end
        alt banned, or suspended until later
            P-->>C: 403 banned
        else
            P->>D: record the login time
            P->>S: MULTI, HSET the session, EXPIRE a day and up to 10 percent either way, index it by player, EXEC
            P-->>C: 200 token, playerId, expiresInSeconds, newPlayer
        end
    end
```

## Guests, and linking an account

A guest is an account made with nothing asked, which the device logs in to with a secret key. The
classes: `AuthService.createGuest`, `loginGuest` and `link`, and
`persistence/AccountRepository.createGuest`, `findGuest` and `link`. A guest's key is never stored,
only its SHA-256, so a guest's login needs no slow hash.

```mermaid
sequenceDiagram
    participant C as client
    participant P as platform
    participant S as store
    participant D as MySQL
    C->>P: POST /v1/guests
    P->>S: count the address, else 429
    P->>P: 32 random bytes, the key, and a name Guest and four digits
    P->>D: the account with no subject, the key's SHA-256
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
    Note over C: later, signed up or logged in at the account service
    C->>P: POST /v1/accounts/link with the session and accessToken
    P->>S: the session's player
    P->>P: the access token checked, as at a login
    P->>D: the subject set and the key cleared, in one statement, guarded by the key being there
    alt not a guest
        P-->>C: 409 not_a_guest
    else the subject has a player already
        P-->>C: 409 account_in_use
    else linked
        P-->>C: 200 playerId, the same player, the key no longer works
    end
```

## The account service's events

A password changed or an account deleted at the account service is written to its outbox in the
same transaction, and posted to the platform until taken (10 §3.5). The classes:
`PlatformHttpServer.accountEvents`, `AccountTokens.event`, and
`AccountDeletion.deletedAtTheAccountService` and `sessionsRevokedAtTheAccountService`.

```mermaid
sequenceDiagram
    participant AS as account service
    participant P as platform
    participant S as store
    participant D as MySQL
    AS->>P: POST /v1/account-events, a signed event, application/secevent+jwt, on the private network
    P->>P: ES256, secevent+jwt, issuer, audience, one event it knows
    alt not taken
        P-->>AS: 400 err authentication_failed, retried and logged there
    else deleted
        P->>S: out of the queue and the party
        P->>D: out of the team whatever its role; then erased in one transaction, the subject recorded
        P->>S: off the boards, every session ended, each lobby told deleted
        P-->>AS: 202
    else sessions-revoked
        P->>S: the sessions made before the event ended, those after kept
        opt one ended
            P->>S: the lobby told signed_out, whichever session it was on
        end
        P-->>AS: 202
    end
```

## A seat in the public arena

"Play now" in the public arena, the room every arena runs without end. The classes:
`JoinService.requestJoin`, `EquipmentService.bonusOf` and `skinOf`, `handoff/ArenaDirectory.pick`
and `promiseSeat`, and `TicketStore`. The arena claims the ticket in `MatchFrameHandler`. The ticket
carries everything the arena needs, so the arena never reads MySQL. The client reaches the arena
through the front: 9001 over TLS, or 80 in clear with `GET /match/<tlsName>` (D-115,
[02](02-arena-and-wire.md#join-with-the-ticket-claim-and-the-welcome)).

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
        P-->>C: 200 arenaHost, arenaPort, ticketId, tls, tlsName
        C->>A: through the front's nginx by tlsName, 9001 over TLS or 80 in clear, then Join with the ticket
        A->>S: MULTI, HGETALL ticket, DEL ticket, EXEC
        A-->>C: Welcome, or Kick with a reason
    end
```

## The queue and the confirm step: a player's record

`MatchQueue` keeps one record for each player, `mmp:{playerId}`. It says whether the player is
queued, asked to confirm, or matched. Each move between states is one watched transaction (D-55).
The record expires, so a player stuck by a crash returns to `none` by themselves.

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

The classes: `QueueService.join` and `answer`; `Matchmaker.round`, `settle`, `match`, `lineups`,
`make` and `callOff`; and `MatchQueue.join`, `ask`, `answer`, `make` and `end`. Only the platform
that holds the lease `mm:leader` runs the round. A step that another write has overtaken writes
nothing, and the next round reads again.

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

A party is up to three players who queue together. The classes are `PartyService` and `Parties`;
the client keeps the newest state with `PartyState` (D-74).

- Each change reads what it depends on under a watch, and writes in one transaction, which raises
  the party's version by one.
- An answer and a push can arrive in either order. The client applies a state only if it names
  another party, or has a higher version.

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

A team is a lasting group of players, kept in MySQL. The classes are `TeamService` and
`persistence/TeamRepository`. Each action is one MySQL transaction, which locks the team first and
then the players it changes (D-39). The pushes are sent after the commit. A refusal tells nobody.

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

`persistence/TournamentRepository` holds a tournament's state, and `worker/TournamentScheduler`
moves it on every 5 s. Each move is a conditional update on the state and the version, so two
workers cannot make the same move twice. An operator creates a tournament with
`POST /admin/tournaments`, and players enter while it is in `registration`.

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

One match of the bracket, in `TournamentScheduler.make` and `settle`. A match nobody came to is
decided 270 s after its tickets. A match somebody came to waits for its result for up to 30
minutes; the arena marks the first arrival in `marr:{matchUid}`.

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

The methods: `TournamentScheduler.tick`, `step`, `run`, `make`, `settle` and `over`. A player's grant
is kept for the ticket's 60 s (`tgrant:`), and the player is marked as called (`tcall:`), which the
queue treats as being in a match.

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

An order is a player's request to buy a pack of gems; the payment provider is simulated (D-68). In
`persistence/PaymentRepository` an order moves only forward, each step under its row's lock.
`worker`'s retention marks an order expired once it has waited a day.

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

The classes: `persistence/SeasonPassRepository.earn`, `pay` and `buyPremium`, and the table in
`SeasonPass`. Points are earned in `worker`'s result transaction; premium is bought through
`platform`. Each track (free and premium) has a mark, the last tier it paid. The mark moves with
what it paid, so each tier pays once.

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

The classes: `ShopService.buy`, `EquipmentService.wear` and `bonusOf`, and `BoostService.activate`,
with `persistence/EconomyRepository`, `EquipmentRepository` and `BoostRepository`. Each key is made
by the client, once for each tap, and sent again with every retry of that tap. So a retry gets the
same answer as the first attempt.

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

The admin API listens on loopback; an operator reaches it over SSH. The classes:
`net/AdminServer.player`, `kickEverywhere` and `notice`; `persistence/AdminRepository`; and
`handoff/SessionStore.revokeAll`, `LobbyPush` and `ArenaDirectory.command`. Every call is checked
against the secret token and audited, refusals included.

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
