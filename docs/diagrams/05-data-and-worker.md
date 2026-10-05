# Diagrams: data and worker

The tables of [06 — Persistence](../detailed-design/06-persistence-mysql.md) and the flows of
[05 — Worker and events](../detailed-design/05-worker-and-events.md), drawn from the schema,
`backend/persistence/src/main/resources/db/migration/V1__schema.sql`, and the code in
`backend/persistence` and `backend/worker`. The classes and every number named here are in the
[persistence README](../../backend/persistence/README.md) and the
[worker README](../../backend/worker/README.md).

## The tables

Thirty-six tables in four drawings, each table drawn once with its keys and the columns that say
what it is; [06 §3](../detailed-design/06-persistence-mysql.md#3-schema) has every column. A
solid line is a foreign key. A dashed line is a reference the schema does not enforce, on
purpose: a disbanded team's rows stay, and `player_day` and `boost_activation` spare their
inserts the check. `player` and `team` are repeated, with their key only, where another drawing's
tables point at them.

### Identity and economy

`account`, `player` and the tables that hang off a player's balances and belongings. Every
balance moves only through `ledger` (`EconomyRepository.move`).

```mermaid
erDiagram
    account {
        bigint id PK
        varchar username
        varchar username_key UK "LOWER of username, stored"
        varbinary password_hash
        tinyint status "0 active, 1 suspended, 2 banned"
        datetime banned_until
        datetime created_at "indexed, the funnel"
        datetime last_login_at
        binary guest_key_hash UK "a guest's key, SHA-256"
    }
    player {
        bigint id PK "and FK to account"
        char public_code UK
        varchar display_name
        smallint level
        bigint xp
        bigint coins "CHECK not below 0"
        bigint gems "CHECK not below 0"
        smallint rating_duel
        int rated_duels
        smallint rating_tvt
        int rated_tvts
        smallint rating_rffa
        int rated_rffas
        bigint team_id "kept in step with team_member"
        date first_played_on "indexed"
        smallint board_duel "virtual, indexed, listed after 10"
    }
    player_stat {
        bigint player_id PK "and FK to player"
        int matches
        int wins
        int kills
        int deaths
        int assists
        int best_score
        bigint playtime_s
    }
    player_day {
        date day PK
        bigint player_id PK
    }
    ledger {
        bigint id PK
        bigint player_id FK
        tinyint currency "0 coins, 1 gems"
        bigint delta
        bigint balance_after
        tinyint reason "0 to 11"
        varchar ref
        varchar idem_key UK "the retry's key"
        datetime created_at
    }
    inventory_item {
        bigint player_id PK "and FK to player"
        varchar item_id PK
        int qty
        tinyint item_level "1 to 5"
        datetime acquired_at
    }
    equipment {
        bigint player_id PK "and FK to player"
        tinyint slot PK "0 to 3 bonus, 4 skin"
        varchar item_id
    }
    boost {
        bigint player_id PK "and FK to player"
        tinyint kind PK "0 xp, 1 coins"
        datetime started_at PK
        varchar item_id
        tinyint percent
        datetime ends_at "indexed, retention"
    }
    boost_activation {
        varchar idem_key PK "act key"
        bigint player_id
        varchar item_id
        datetime at "indexed, retention"
    }
    payment_order {
        char id PK "a UUID"
        bigint player_id FK
        char client_key "unique with player_id"
        varchar product_id
        int gems
        int price_cents
        tinyint state "0 pending to 4 expired"
        int bonus
        int debt
        datetime created_at "indexed with state"
    }
    daily_goal {
        bigint player_id PK "and FK to player"
        date day PK "indexed, retention"
        varchar goal_id PK
        int progress
    }
    account ||--|| player : "plays as"
    player ||--|| player_stat : "totals"
    player ||..o{ player_day : "active on, no FK"
    player ||--o{ ledger : "balance moved by"
    player ||--o{ inventory_item : "holds"
    player ||--o{ equipment : "wears"
    player ||--o{ boost : "runs"
    player ||..o{ boost_activation : "used a key, no FK"
    player ||--o{ payment_order : "orders"
    player ||--o{ daily_goal : "progresses"
```

### Match history and seasons

What a result writes and what a season's close keeps. `match_player`'s key is the result's
duplicate check; `season_place` and `season_team_place` are a past season's boards.

```mermaid
erDiagram
    player {
        bigint id PK
    }
    team {
        bigint id PK
    }
    matches {
        bigint id PK
        char match_uid UK "the arena's ULID"
        tinyint mode "MatchMode id, 0 to 9"
        tinyint kind "0 open stay, 1 timed"
        varchar arena
        bigint tournament_id "indexed, written by nothing"
        datetime started_at
        datetime ended_at "indexed, retention"
        boolean cut_short
    }
    match_player {
        bigint match_id PK "and FK to matches"
        bigint player_id PK "and FK to player"
        tinyint team "the side"
        smallint placement
        smallint kills
        smallint deaths
        smallint assists
        int score
        int xp_gained
        smallint rating_delta
    }
    match_team {
        bigint match_id PK "and FK to matches"
        tinyint side PK "1 or 2"
        bigint team_id "indexed with match_id"
        tinyint placement "1 won or drew, 2 lost"
        smallint rating_delta
    }
    season {
        smallint id PK
        datetime starts_at
        datetime ends_at
        datetime placed_at "step 1 done"
        datetime paid_at "step 2 done"
        bigint reset_bound
        bigint reset_through "the reset's progress"
        datetime reset_at "step 3 done"
        bigint team_bound
        datetime team_reset_at "the teams' reset done"
    }
    season_place {
        smallint season_id PK "and FK to season"
        tinyint board PK "1 duel, 2 tvt, 3 rffa"
        int place PK
        bigint player_id FK "unique a season and board"
        smallint rating
        int rated
        smallint gems
    }
    season_team_place {
        smallint season_id PK "and FK to season"
        int place PK
        bigint team_id "unique a season"
        varchar name "the name it had then"
        smallint rating
        int rated
        smallint gems
    }
    season_team_payee {
        smallint season_id PK "and FK to season"
        bigint player_id PK "and FK to player"
        bigint team_id
    }
    season_pass {
        bigint player_id PK "and FK to player"
        smallint season_id PK "indexed, retention"
        int points
        boolean premium
        tinyint free_paid
        tinyint premium_paid
    }
    matches ||--|{ match_player : "has parts"
    player ||--o{ match_player : "played"
    matches ||--o{ match_team : "has sides"
    team ||..o{ match_team : "played as a side, no FK"
    season ||--o{ season_place : "placed"
    player ||--o{ season_place : "was placed"
    season ||--o{ season_team_place : "placed teams"
    team ||..o{ season_team_place : "was placed, no FK"
    season ||--o{ season_team_payee : "paid"
    player ||--o{ season_team_payee : "was paid"
    season ||..o{ season_pass : "counts points, no FK"
    player ||--o{ season_pass : "holds"
```

### Teams and tournaments

A team's members, invitations and applications, and a tournament's entries, rosters and
bracket. `tournament_match` holds players' ids, or teams' in a teams' tournament (mode 5), so
its sides and winner have no foreign key.

```mermaid
erDiagram
    player {
        bigint id PK
        bigint team_id "no FK"
    }
    team {
        bigint id PK
        varchar name UK "unique ignoring case and accents"
        tinyint member_count
        smallint rating
        int rated_matches
        int wins
        int losses
        int draws
        datetime renamed_at
        smallint board_rating "virtual, indexed, listed after 10"
    }
    team_member {
        bigint team_id PK "and FK to team"
        bigint player_id PK "and FK to player, unique"
        tinyint role "0 member, 1 vice, 2 leader"
        datetime joined_at
    }
    team_invite {
        bigint team_id PK "and FK to team"
        bigint player_id PK "indexed"
        bigint invited_by
        datetime expires_at "indexed, retention"
    }
    team_application {
        bigint team_id PK "and FK to team"
        bigint player_id PK "and FK to player"
        datetime expires_at "indexed, retention"
        boolean declined
    }
    tournament {
        bigint id PK
        varchar name
        tinyint state "0 registration to 4 cancelled, indexed"
        int version "every transition checks it"
        tinyint max_entries
        datetime registration_ends
        datetime starts_at
        tinyint round_minutes
        bigint prize_1
        tinyint current_round
        tinyint mode "1 duel, 5 teams"
        tinyint format "0 elimination, 1 round robin"
    }
    tournament_entry {
        bigint tournament_id PK "and FK to tournament"
        bigint player_id PK "and FK to player"
        tinyint seed
        datetime registered_at
    }
    tournament_team_entry {
        bigint tournament_id PK "and FK to tournament"
        bigint team_id PK
        tinyint seed
        datetime registered_at
    }
    tournament_roster {
        bigint tournament_id PK "and FK to tournament"
        bigint player_id PK "and FK to player"
        bigint team_id "indexed with tournament_id"
    }
    tournament_match {
        bigint tournament_id PK "and FK to tournament"
        tinyint round PK
        tinyint slot PK
        bigint player_a "a player or a team"
        bigint player_b "a player or a team"
        tinyint state "0 pending, 1 ready, 2 done"
        char match_uid "the made match"
        datetime ready_at
        bigint winner
    }
    team ||--o{ team_member : "has"
    player ||--o| team_member : "is in"
    team ||..o{ player : "team_id, no FK"
    team ||--o{ team_invite : "invites"
    player ||..o{ team_invite : "is invited, no FK"
    team ||--o{ team_application : "is asked by"
    player ||--o{ team_application : "applies"
    tournament ||--o{ tournament_entry : "has"
    player ||--o{ tournament_entry : "enters"
    tournament ||--o{ tournament_team_entry : "has"
    team ||..o{ tournament_team_entry : "enters, no FK"
    tournament ||--o{ tournament_roster : "has"
    player ||--o{ tournament_roster : "is on"
    tournament ||--o{ tournament_match : "has"
```

### Social and operations

Friendships, requests, blocks and the inbox; and the four tables operations write: the admin
API's audit, the failover epoch, the replica heartbeat and the backup steps' outcomes. The last
four point at nothing.

```mermaid
erDiagram
    player {
        bigint id PK
    }
    friend {
        bigint player_id PK "and FK to player"
        bigint friend_id PK "and FK to player"
        datetime since
    }
    friend_request {
        bigint from_id PK "and FK to player"
        bigint to_id PK "and FK to player, indexed"
        datetime expires_at "indexed, retention"
    }
    block {
        bigint player_id PK "and FK to player"
        bigint blocked_id PK "and FK to player, indexed"
        datetime since
    }
    inbox {
        bigint id PK
        bigint player_id FK "unique with kind and ref"
        tinyint kind "1 to 6"
        bigint ref "a player, team, tournament or season and board"
        datetime created_at "indexed, retention"
        datetime read_at
    }
    admin_audit {
        bigint id PK
        datetime at "indexed"
        varchar action
        varchar target
        varchar request
        varchar outcome
    }
    ha_epoch {
        tinyint id PK "always 1"
        bigint epoch "raised by a promotion"
    }
    ha_heartbeat {
        tinyint id PK "always 1"
        datetime at "microseconds, the primary's clock"
    }
    backup_run {
        bigint id PK
        tinyint kind "1 dump, 2 proof, 3 offsite"
        datetime started_at
        datetime finished_at "indexed with kind"
        boolean ok
        varchar detail
        double seconds "a proof's restore"
    }
    player ||--o{ friend : "has friends"
    player ||--o{ friend : "is a friend"
    player ||--o{ friend_request : "asks"
    player ||--o{ friend_request : "is asked"
    player ||--o{ block : "blocks"
    player ||--o{ block : "is blocked"
    player ||--o{ inbox : "is told"
```

## The result pipeline

From a match's end to the player's screen ([05 §3](../detailed-design/05-worker-and-events.md#3-the-consumer-loop),
§6): the arena's `MatchResultPublisher`, `handoff/MatchResultStream`, `worker/MatchResultConsumer`,
`persistence/MatchResultRepository`, `handoff/LobbyPush` and `LeaderboardStore`. The entry is
acknowledged only after the commit, so a crash anywhere before it delivers it again, and the
apply recognises the redelivery.

```mermaid
sequenceDiagram
    participant A as arena
    participant E as events store
    participant W as worker
    participant D as MySQL
    participant S as session store
    participant G as gateway
    A->>A: spool the result to disk
    A->>E: XADD to the result stream, trimmed to 24 hours
    A->>A: delete the spooled file
    W->>E: XREADGROUP as group rewards, one entry
    E-->>W: the entry, pending for this worker
    alt unreadable
        W->>E: LPUSH to the dead list, then XACK
    else written by a newer producer
        W->>E: LPUSH to the deferred list, then XACK
    else readable
        W->>D: read the boosts running when the match ended
        W->>W: rewards by RewardRules
        W->>D: validate, then one transaction, the result applied
        alt refused, or an error no retry can change
            W->>E: LPUSH to the dead list, then XACK
        else the database is failing
            W->>W: leave it pending, claim nothing, re-drive in 5 s
        else committed
            D-->>W: who was newly paid, and the names held
            W->>S: PUBLISH evt.rewards to each paid player's gateway
            S->>G: the push
            G->>G: to the player's lobby connection
            W->>S: ZADD GT on the boards, every delivery
            W->>E: XACK
        end
    end
```

The stream's key is `s:match-result`; the dead and deferred lists are `q:match-result:dead` and
`q:match-result:deferred`. An entry another worker left pending for a minute is taken over with
`XAUTOCLAIM` at the next re-drive.

## One result's transaction

`MatchResultRepository.apply`, in order, all in one transaction under the players' locks
([06 §4](../detailed-design/06-persistence-mysql.md#4-the-three-transactions-that-matter)). Every
payment goes through the ledger's one path under a key of its own, so nothing is paid twice.

```mermaid
flowchart TD
    V["validate every value, refuse a result over 30 days late"]
    T{"a rated team match?"}
    TL["read the players' teams, lock the teams by id"]
    PL["lock the players and their player_stat rows, lowest id first"]
    EX{"does any player exist?"}
    R0["return, nothing written"]
    MU["upsert matches by match_uid"]
    RD["rating deltas from the locked ratings"]
    SB["read the season being played"]
    LP["the next player, lowest id first"]
    MP{"insert match_player"}
    UP["update player: xp, level, rating, first day"]
    MS["each milestone crossed: gems"]
    LG{"long enough to be paid?"}
    ST["add to player_stat"]
    AC["each achievement crossed: gems"]
    DG["add to daily goals, read them locked, pay each met"]
    SP["add pass points, pay each tier crossed"]
    CO["the match's coins, if any"]
    NX{"another player?"}
    PD["insert player_day for each new part that counts"]
    TM{"a team match with a new part?"}
    MT["match_team for each side, the teams' ratings"]
    C["commit"]
    V --> T
    T -- "yes" --> TL
    T -- "no" --> PL
    TL --> PL
    PL --> EX
    EX -- "no" --> R0
    EX -- "yes" --> MU
    MU --> RD
    RD --> SB
    SB --> LP
    LP --> MP
    MP -- "1062, applied before" --> NX
    MP -- "inserted" --> UP
    UP --> MS
    MS --> LG
    LG -- "no" --> CO
    LG -- "yes" --> ST
    ST --> AC
    AC --> DG
    DG --> SP
    SP --> CO
    CO --> NX
    NX -- "yes" --> LP
    NX -- "no" --> PD
    PD --> TM
    TM -- "yes" --> MT
    TM -- "no" --> C
    MT --> C
```

## Retention

`worker/Retention`, in every worker, a minute after start and then every 24 hours, 1 000 rows a
transaction ([06 §9](../detailed-design/06-persistence-mysql.md#9-growth-and-retention)). Each step
stands on its own: one that fails is logged and the next still runs.

```mermaid
flowchart TD
    S["1 minute after start, then every 24 hours"]
    R1["matches ended over 90 days ago, with match_player and match_team"]
    R2["inbox items over 30 days old"]
    R3["player_day rows over 90 days old"]
    R4["lapsed friend requests, team invitations, team applications"]
    R5["boosts ended over 31 days ago, activation keys over 30 days"]
    R6["finished or cancelled tournaments 90 days after their start"]
    R7["daily goals' progress over 7 days old"]
    R8["payment orders pending over a day, marked expired"]
    R9["season passes before the six seasons kept"]
    R10["backup_run rows over 90 days, but each kind's newest success"]
    D["record the run's time: backend_worker_retention_seconds"]
    F["a step that fails is logged, and the next step runs"]
    S --> R1 --> R2 --> R3 --> R4 --> R5 --> R6 --> R7 --> R8 --> R9 --> R10 --> D
    F -.- R1
```

## The season's close

`worker/SeasonKeeper` each minute, under the store's lock `job:season`, which it extends while a
close runs; `persistence/SeasonRepository` writes each step and stamps it in the season's row
([D-63](../architecture/03-decision-log.md#d-63--a-season-ends-in-three-steps-each-safe-to-repeat-its-places-its-gems-then-its-reset)).
A run that stops part-way leaves the row where it stopped, and the next run goes on from there.

```mermaid
stateDiagram-v2
    state "Being played" as Running
    state "Ended, not placed" as Ended
    state "Placed" as Placed
    state "Paid" as Paid
    state "Players reset" as Reset
    state "Closed" as Closed
    [*] --> Running
    Running --> Ended : ends_at passes, or an operator ends it now
    Ended --> Placed : places on every board, the next season made, placed_at
    Placed --> Paid : each place's gems through the ledger, paid_at
    Paid --> Reset : ratings halfway to 1200 in batches of 1000, reset_at
    Reset --> Closed : the teams' ratings halfway, team_reset_at
    Closed --> [*]
```

## The ledger check

`worker/LedgerCheck` in every worker, once a day across the fleet, and
`EconomyRepository.reconcile` ([05 §9](../detailed-design/05-worker-and-events.md#9-scheduled-work)).
The lock needs no fencing token: the check only reads.

```mermaid
flowchart TD
    H["5 minutes after start, then every hour"]
    L{"SET job:ledger-check NX EX 23 hours"}
    N["another worker has today's check"]
    RG["the next range of player ids"]
    Q["one statement: each balance against its ledger rows' sum, coins and gems"]
    MR{"another range?"}
    WR["write the mismatches and the time to ledger:check:last"]
    MX["every worker's metrics read it at each scrape"]
    FL["a failure: delete the lock if still held, try next hour"]
    H --> L
    L -- "taken" --> N
    L -- "this worker's" --> RG
    RG --> Q
    Q --> MR
    MR -- "yes" --> RG
    MR -- "no" --> WR
    WR --> MX
    Q -- "failed" --> FL
```

## The replica's heartbeat and lag

`worker/ReplicaWatch` with `persistence/Database.heartbeat` and `PrimaryDataSource.replicaLags`
([06 §10](../detailed-design/06-persistence-mysql.md#10-backup-and-recovery),
[D-58](../architecture/03-decision-log.md#d-58--a-replica-is-measured-by-what-it-has-applied-a-heartbeat-for-mysql-the-primarys-own-account-for-the-stores)).
Both stamps are the primary's clock, so the two machines' clocks never meet.

```mermaid
sequenceDiagram
    participant W as worker
    participant P as MySQL primary
    participant R as MySQL replica
    loop every second
        W->>P: UPDATE ha_heartbeat with the time now, through the pool
    end
    P-->>R: replication carries the row
    loop every 5 s, on a thread of its own
        W->>P: read read_only and the stamp, 2 s limit
        W->>R: read read_only and the stamp, 2 s limit
        W->>W: lag is the writable host's stamp less the replica's
    end
    Note over W: backend_mysql_replica_lag_seconds and replica_up by host
```
