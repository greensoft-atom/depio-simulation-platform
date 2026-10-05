# 06 — Persistence: MySQL

The system of record. Everything a player would file a support ticket about
lives here: accounts, progression, currency, inventory, teams, tournaments and
match history.

Replaces the MongoDB design
([D-3](../architecture/03-decision-log.md#d-3--mysql-is-the-system-of-record-mongodb-is-cancelled)),
archived at [archive/persistence-mongodb.md](../archive/persistence-mongodb.md).

**Implemented** in [`backend/persistence`](../../backend/persistence/README.md): the
schema as one baseline, `V1__schema.sql` with its first rows in `V2__seed.sql` (§8),
every table shown in [§3](#3-schema) with what it serves; the pool; and all three
transactions in [§4](#4-the-three-transactions-that-matter) (a match result, a purchase,
a reward), with tests that run against a real MySQL 8.0.

## 1. Boundaries

| Lives in MySQL | Lives in j-redis | Lives nowhere |
|---|---|---|
| Accounts, credentials, bans | Sessions and tokens | Per-tick simulation state |
| Profile, level, XP | Join tickets | Entity positions |
| Currency balances and the ledger | Live leaderboards (sorted sets) | Snapshot buffers |
| Inventory, equipment | Matchmaking queues | |
| Teams, tournaments | Presence | |
| Match history | Event streams | |

Two rules that follow from the architecture and are worth restating because
violating either is how a realtime system acquires a database-shaped stall:

**The arena never opens a database connection.** Everything it needs at join
time is pre-resolved into the join ticket; everything it produces goes to an
event stream. A room thread that can block on a query is a room thread that
will, at the worst possible moment.

**Live leaderboards are not in MySQL.** They are j-redis sorted sets, because
"rank among 50 000" is a data-structure problem, not a query. MySQL holds what
the boards are made of (`player_stat.best_score`, `matches` and `match_player`),
so they can be rebuilt after a store loss
([05 §8](05-worker-and-events.md#8-rebuilding-after-a-store-loss)), and that is its
only involvement.

### Write-through or cache-aside

| Data | Strategy | Why |
|---|---|---|
| Currency, inventory, purchases | **Write-through**, MySQL authoritative | A lost write here is a support ticket and a refund |
| Profile, aggregate stats | **Cache-aside** in j-redis, TTL 5 min | Stale by a few minutes is harmless |
| Leaderboards | j-redis authoritative, rebuilt from `player_stat` and `matches` after a loss | Rebuild path, not a query path |

## 2. Conventions

| Concern | Choice | Reason |
|---|---|---|
| Engine | InnoDB | Transactions, row locks, crash recovery |
| Charset | `utf8mb4` / `utf8mb4_0900_ai_ci`, named on every table | Real Unicode, including names outside the BMP; and a name unique whatever its case or accents, whatever the server's own default |
| Primary keys | `BIGINT UNSIGNED AUTO_INCREMENT` | Sequential inserts into a clustered index; UUIDs fragment it |
| Player-visible ids | separate opaque `CHAR(12)` code | Never expose a row count to players |
| Money and currency | `BIGINT`, integer minor units | Floating point in a ledger is a bug waiting for an audit |
| Time | `DATETIME(3)`, always UTC; a day is a `DATE`, UTC; `ha_heartbeat.at` is `DATETIME(6)` | `TIMESTAMP` carries the 2038 limit and a timezone surprise. Enforced since 2026-09-26 by the pool, for what the driver writes and, forced onto the session, for what MySQL stamps itself (`CURRENT_TIMESTAMP` defaults), and by the server's `default-time-zone`. Before, the driver wrote each process's local time (defect [D-19](../defects.md#3-data-and-the-result-pipeline)), and a server left on local time stamped ledger rows two hours off |
| Booleans | `BOOLEAN`, which MySQL stores as `TINYINT(1)` | MySQL has no native boolean |
| Enumerations | `TINYINT UNSIGNED` + a constant in code | `ENUM` columns make migrations painful |

Rows that record an event (`account`, `player`, `ledger`) carry
`created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)`; `inventory_item`
has `acquired_at`, and the match and stat tables carry their own times. Only
`player` carries `updated_at`; elsewhere a change is told by a time of its own
(`confirmed_at`, `refunded_at`, `renamed_at`, `read_at`, the season's steps). A
`version INT UNSIGNED` is checked by every change of a `tournament`'s state;
`player.version` is there and unused (§4).

## 3. Schema

The schema is the baseline's `V1__schema.sql` (§8), and every table below is shown as
it makes it, column for column, key for key; `V2__seed.sql` writes the rows a first
launch needs: the failover epoch (§10), the replica heartbeat (§10) and season 1. The
`V`-numbers in the notes are the steps each table grew by, kept in git to commit
ce30aa7 ([D-75](../architecture/03-decision-log.md#d-75--the-migrations-are-squashed-into-one-baseline-before-the-first-launch)).
The [persistence README](../../backend/persistence/README.md) lists which class writes
and reads each table, and [diagrams/05](../diagrams/05-data-and-worker.md) draws them.

Thirty-six tables, by domain:

| Domain | Tables |
|---|---|
| Identity and profile | `account`, `player`, `player_stat`, `player_day` |
| Economy | `ledger`, `inventory_item`, `equipment`, `boost`, `boost_activation`, `payment_order` |
| Matches | `matches`, `match_player` |
| Teams and tournaments | `team`, `team_member`, `team_invite`, `team_application`, `match_team`, `tournament`, `tournament_entry`, `tournament_match`, `tournament_team_entry`, `tournament_roster` |
| Seasons and boards | `season`, `season_place`, `season_team_place`, `season_team_payee`, `season_pass` |
| Daily goals | `daily_goal` |
| Social | `friend`, `friend_request`, `block`, `inbox` |
| Operations | `admin_audit`, `ha_epoch`, `ha_heartbeat`, `backup_run` |

Every table names its engine and character set, InnoDB and `utf8mb4` with
`utf8mb4_0900_ai_ci`, so a server's own default cannot change what a unique name
means. InnoDB gives a foreign key whose columns lead no index of its table an index of
its own, named after the constraint: `friend (friend_id)`, `tournament_entry
(player_id)`, `tournament_roster (player_id)` and `season_team_payee (player_id)` each
have one that no `KEY` line names.

### Identity and profile

```sql
-- A login: a name and a password's argon2id hash, or a guest's device key. One account, one player.
CREATE TABLE account (
  id             BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  username       VARCHAR(32)    NOT NULL,
  username_key   VARCHAR(32) AS (LOWER(username)) STORED,     -- unique whatever the case
  password_hash  VARBINARY(255) NOT NULL,
  status         TINYINT UNSIGNED NOT NULL DEFAULT 0,         -- 0 active, 1 suspended, 2 banned
  banned_until   DATETIME(3) NULL,                            -- a suspension's end
  created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  last_login_at  DATETIME(3) NULL,
  guest_key_hash BINARY(32) NULL,                             -- SHA-256 of a guest's device key; NULL once upgraded (D-46)
  UNIQUE KEY uq_account_username (username_key),
  UNIQUE KEY uq_account_guest (guest_key_hash),
  KEY ix_account_created (created_at)                         -- the funnel's cohorts (05 §11)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

`status` 1 is suspended until `banned_until`, or until lifted when that is null; 2 is
banned for good; any other value is read as banned (`AccountRepository.Status`), since
operators write the column. A guest's `username` is `~` and a ULID, and its
`password_hash` empty until it is upgraded (V15); the username rules refuse `~`, so no
login by name reaches it. `ix_account_created` (V34) serves the funnel's window of days.

```sql
-- The player an account plays as: profile, progress, balances and ratings. Its id is the account's.
CREATE TABLE player (
  id              BIGINT UNSIGNED PRIMARY KEY,
  public_code     CHAR(12)    NOT NULL,                       -- what others see and search by
  display_name    VARCHAR(32) NOT NULL,
  level           SMALLINT UNSIGNED NOT NULL DEFAULT 1,
  xp              BIGINT UNSIGNED   NOT NULL DEFAULT 0,
  coins           BIGINT NOT NULL DEFAULT 0,                  -- moved only through the ledger (06 §4)
  gems            BIGINT NOT NULL DEFAULT 0,                  -- likewise
  rating_duel     SMALLINT UNSIGNED NOT NULL DEFAULT 1200,
  team_id         BIGINT UNSIGNED NULL,
  version         INT UNSIGNED NOT NULL DEFAULT 0,
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  rated_duels     INT UNSIGNED NOT NULL DEFAULT 0,            -- the duel's Elo K falls from 32 to 16 after 30
  rating_tvt      SMALLINT UNSIGNED NOT NULL DEFAULT 1200,    -- team-vs-team (D-26)
  rated_tvts      INT UNSIGNED NOT NULL DEFAULT 0,
  rating_rffa     SMALLINT UNSIGNED NOT NULL DEFAULT 1200,    -- ranked free-for-all (D-28)
  rated_rffas     INT UNSIGNED NOT NULL DEFAULT 0,
  team_left_at    DATETIME(3) NULL,                           -- the 24-hour cooldown counts from it
  first_played_on DATE NULL,                                  -- the earliest day in player_day, kept past its 90 days
  -- Each rating board's rating for a player listed, ten rated matches in its mode, else NULL: the
  -- unlisted are in no range a board reads (D-57). Virtual: computed into the index on write.
  board_duel      SMALLINT UNSIGNED AS (IF(rated_duels >= 10, rating_duel, NULL)) VIRTUAL,
  board_rffa      SMALLINT UNSIGNED AS (IF(rated_rffas >= 10, rating_rffa, NULL)) VIRTUAL,
  board_tvt       SMALLINT UNSIGNED AS (IF(rated_tvts >= 10, rating_tvt, NULL)) VIRTUAL,
  name_changed_at DATETIME(3) NULL,                           -- one rename in 30 days (04 §1)
  UNIQUE KEY uq_player_code (public_code),
  KEY ix_player_first_played (first_played_on),
  KEY idx_player_listed_duel (board_duel DESC, rated_duels DESC, id),   -- the boards, in their order
  KEY idx_player_listed_rffa (board_rffa DESC, rated_rffas DESC, id),
  KEY idx_player_listed_tvt (board_tvt DESC, rated_tvts DESC, id),
  CONSTRAINT fk_player_account FOREIGN KEY (id) REFERENCES account (id),
  -- Last line of defence. The one code path that moves a balance refuses to overdraw;
  -- this turns a bug into a failed transaction rather than a negative balance.
  CONSTRAINT ck_player_balances CHECK (coins >= 0 AND gems >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

The `CHECK` constraint is the last line of defence, not the first. Balance
changes go through one code path that refuses to overdraw; the constraint
exists so that a bug becomes a failed transaction rather than a negative
balance nobody notices.

The columns grew in steps: the rated counts and the other two ratings (V4 to V6),
`team_left_at` (V10), `first_played_on` (V16), the listed ratings and their indexes
(V20, D-57) and `name_changed_at` (V23). V17's three indexes over the raw ratings,
which V20's replaced and which no query read, are not in the baseline
([defect D-41](../defects.md#3-data-and-the-result-pipeline)). `player.version` is 0
on every row: nothing edits a player optimistically yet (§4).

Aggregate statistics are a separate table from `player`, because they are
written after every match while the profile is nearly static — keeping them
apart avoids contending on the row that logins read.

```sql
-- Lifetime totals, written by every result; apart from player so results do not contend with logins.
CREATE TABLE player_stat (
  player_id  BIGINT UNSIGNED PRIMARY KEY,
  matches    INT UNSIGNED NOT NULL DEFAULT 0,
  wins       INT UNSIGNED NOT NULL DEFAULT 0,
  kills      INT UNSIGNED NOT NULL DEFAULT 0,
  deaths     INT UNSIGNED NOT NULL DEFAULT 0,
  best_score INT UNSIGNED NOT NULL DEFAULT 0,
  playtime_s BIGINT UNSIGNED NOT NULL DEFAULT 0,
  assists    INT UNSIGNED NOT NULL DEFAULT 0,                 -- hits shortly before another's kill (01 §7)
  CONSTRAINT fk_stat_player FOREIGN KEY (player_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

A player's row here is made with the player, in the transaction that makes the
account, so a result's locking read of it always finds a row: a read that found none
would lock the gap where it would go, and two first results inserting into that gap
would deadlock. `assists` came with V25. The achievements (04 §8, D-64) are thresholds
on these columns.

```sql
-- Activity: a row a player a day a match of theirs ended, written by the result's transaction (D-47).
CREATE TABLE player_day (
  day       DATE NOT NULL,                                    -- UTC
  player_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (day, player_id)                                -- a day's players are one range; retention deletes by it
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

No foreign key on `player_day`: it is written only for players the result's
transaction has locked, and every insert is spared the check. A result too short to be
paid writes no row (05 §11). V16 filled this table and `player.first_played_on` from
the match history there was; the baseline starts on an empty database, with nothing to
fill, and a backfill is a task, not a migration (§8).

### Economy

```sql
-- Every move of a balance, and the only way one moves (EconomyRepository.move): the row and the
-- balance in one transaction, so the nightly reconciliation finds them agreeing.
CREATE TABLE ledger (
  id            BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  player_id     BIGINT UNSIGNED NOT NULL,
  currency      TINYINT UNSIGNED NOT NULL,                    -- 0 coins, 1 gems
  delta         BIGINT NOT NULL,
  balance_after BIGINT NOT NULL,
  -- 0 match reward, 1 purchase, 2 refund, 3 tournament prize, 4 item level, 5 milestone, 6 season,
  -- 7 achievement, 8 daily goal, 9 payment, 10 payment refund, 11 pass tier (EconomyRepository)
  reason        TINYINT UNSIGNED NOT NULL,
  ref           VARCHAR(64) NOT NULL,
  idem_key      VARCHAR(80) NOT NULL,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  -- The idempotency mechanism for currency: a retry violates this and is caught.
  UNIQUE KEY uq_ledger_idem (idem_key),
  KEY ix_ledger_player (player_id, created_at),
  CONSTRAINT fk_ledger_player FOREIGN KEY (player_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

**The ledger is append-only and is the audit trail.** `player.coins` and
`player.gems` are denormalised running totals, maintained in the same transaction as
the ledger row. A daily job (05 §9) re-derives `SUM(delta)` per player in each
currency and alerts on any mismatch — a divergence means a code path has updated a
balance without writing a ledger row, which is exactly the bug that is impossible to
find after the fact. Reason 2, a refund, is defined and written by nothing yet: a
payment's refund is reason 10. Every key the ledger is written under is in §5.

### Inventory and equipment

```sql
-- What a player holds: equipment and boosts by the content's item id, with an equipment's level.
CREATE TABLE inventory_item (
  player_id   BIGINT UNSIGNED NOT NULL,
  item_id     VARCHAR(40) NOT NULL,
  qty         INT UNSIGNED NOT NULL DEFAULT 0,
  item_level  TINYINT UNSIGNED NOT NULL DEFAULT 1,
  acquired_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  -- Clusters a player's whole inventory together, so loading it is one sequential read.
  PRIMARY KEY (player_id, item_id),
  CONSTRAINT fk_inv_player FOREIGN KEY (player_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- What a player wears, a slot each.
CREATE TABLE equipment (
  player_id BIGINT UNSIGNED NOT NULL,
  slot      TINYINT UNSIGNED NOT NULL,                        -- 0 barrel, 1 armor, 2 core, 3 treads, 4 skin (Items)
  item_id   VARCHAR(40) NOT NULL,
  PRIMARY KEY (player_id, slot),
  CONSTRAINT fk_equip_player FOREIGN KEY (player_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

The composite primary key `(player_id, item_id)` is deliberate: it clusters a
player's whole inventory together on disk, so loading it is one sequential
read, and it makes "grant one of item X" an `INSERT … ON DUPLICATE KEY UPDATE`
rather than a read-modify-write. A row whose `qty` falls to 0 stays, with its level.

`equipment.slot` 4 is the skin (plan item 75 (c), D-70), after the four that
give a bonus: a value of the column, so nothing new in the schema.

**Boosts** (V9, V22; plan item 16, 04 §8): a row a boost's run, and a row a key used.

```sql
-- A boost's run, kept after it ends so a result applied late still finds the boost its match ended
-- in (D-38); retention deletes it later, by its end.
CREATE TABLE boost (
  player_id  BIGINT UNSIGNED NOT NULL,
  kind       TINYINT UNSIGNED NOT NULL,                       -- 0 experience, 1 coins
  item_id    VARCHAR(40) NOT NULL,
  percent    TINYINT UNSIGNED NOT NULL,
  started_at DATETIME(3) NOT NULL,
  ends_at    DATETIME(3) NOT NULL,
  PRIMARY KEY (player_id, kind, started_at),
  KEY ix_boost_ends (ends_at),
  CONSTRAINT fk_boost_player FOREIGN KEY (player_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- A key used to activate a boost, so a retry takes no second item.
CREATE TABLE boost_activation (
  idem_key  VARCHAR(80) NOT NULL PRIMARY KEY,                 -- act:{playerId}:{key}
  player_id BIGINT UNSIGNED NOT NULL,
  item_id   VARCHAR(40) NOT NULL,
  at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY ix_activation_at (at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

A boost running is the row of its kind whose `ends_at` is still ahead; the
same item activated again moves that row's `ends_at` on; one activated after it
ended is a new row. Rows are kept once ended: a result applied late, after a
worker's outage, must still find the boost its match ended in, and a replaced
row would have lost it (found writing the test). Activation takes the item,
writes the key and the boost in one transaction, the player locked, so two taps
at once take one item and a retry none. An ended row is kept 31 days, a day
longer than a result may be late, and a key 30 days, where a retry comes within
seconds, each deleted by its index (V22; §9, plan item 60 (b)).

Items are referenced by their content key, not a foreign key to an item table,
because the catalogue is JSON loaded at boot
([01-arena](01-arena.md)). The database stores what a player owns; the content
tables define what it does.

### Payments (V30, designed 2026-10-04, plan item 75 (a))

```sql
-- Gems bought for money: an order its provider confirms, simulated until the owner chooses one
-- (D-68, Q-52); granted once, refunded once, what a refund could not take back kept as a debt.
CREATE TABLE payment_order (
  id           CHAR(36) PRIMARY KEY,
  player_id    BIGINT UNSIGNED NOT NULL,
  client_key   CHAR(36) NOT NULL,                             -- the client's idempotency key
  product_id   VARCHAR(64) NOT NULL,
  gems         INT UNSIGNED NOT NULL,
  price_cents  INT UNSIGNED NOT NULL,
  currency     CHAR(3) NOT NULL,
  provider     VARCHAR(16) NOT NULL,
  state        TINYINT UNSIGNED NOT NULL,                     -- 0 pending, 1 paid, 2 declined, 3 refunded, 4 expired
  bonus        INT UNSIGNED NOT NULL DEFAULT 0,               -- the first purchase's extra gems
  debt         INT UNSIGNED NOT NULL DEFAULT 0,               -- gems a refund could not take back
  created_at   DATETIME(3) NOT NULL,
  confirmed_at DATETIME(3) NULL,
  refunded_at  DATETIME(3) NULL,
  UNIQUE KEY uq_order_client_key (player_id, client_key),
  KEY ix_order_state (state, created_at),
  CONSTRAINT fk_order_player FOREIGN KEY (player_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

An order moves only forward, each step under its row's lock
([D-68](../architecture/03-decision-log.md#d-68--a-purchase-is-an-order-its-provider-confirms-granted-once-and-a-refund-is-taken-back)):
pending to paid or declined (confirm), paid to refunded, pending to expired.
Its gems are granted by the ledger keyed `payment:{orderId}`, a first order's
bonus keyed `payment:first:{player}`; ledger reasons 9 a payment, 10 its
refund. A refund, and an operator clearing a debt, are each written with their
`admin_audit` row in one transaction (D-30). A player's debt is the sum of
their orders' `debt`, read by the unique key's first column. Retention expires an
order pending longer than a day by `ix_order_state`; the row is kept.

### Matches

```sql
-- A match, or one player's stay in the open arena (kind). Not `match`: MATCH is reserved in MySQL
-- (MATCH ... AGAINST), which would force backticks in every query.
CREATE TABLE matches (
  id            BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  match_uid     CHAR(26) NOT NULL,                            -- the arena's ULID
  -- 0 ffa, 1 duel, 2 tvt, 3 rffa, 4 coop, 5 teams, 6 domination, 7 tag, 8 maze, 9 sandbox (MatchMode)
  mode          TINYINT UNSIGNED NOT NULL,
  kind          TINYINT UNSIGNED NOT NULL DEFAULT 0,          -- 0 a stay in the open arena, 1 a timed match (D-15)
  arena         VARCHAR(32) NOT NULL,
  tournament_id BIGINT UNSIGNED NULL,
  started_at    DATETIME(3) NOT NULL,
  ended_at      DATETIME(3) NOT NULL,
  cut_short     BOOLEAN NOT NULL DEFAULT FALSE,               -- closed by an operator or a failed room: placements as they stood
  UNIQUE KEY uq_match_uid (match_uid),
  KEY ix_match_tournament (tournament_id),
  KEY ix_match_ended (ended_at)                               -- retention deletes by age, oldest first
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

**The table is `matches`, not `match`.** `MATCH` is reserved in MySQL
(`MATCH … AGAINST`), so `match` would need backticks in every query forever —
a permanent footgun for one word of elegance.

**A row is either an open match or a timed match, and `kind` says which**
([D-15](../architecture/03-decision-log.md#d-15--the-public-arena-runs-continuously-structured-modes-are-timed-matches)).
An open match is one player's stay in the public arena: one `match_player` row,
`placement = 0`, no winner. A timed match has a roster and real placements.
Both belong here — they are the same shape of record and share the same
idempotency key — but a query meaning "my ranked matches" must filter on a
stated fact rather than infer one from the placement. `kind` came with V2,
`ix_match_ended` with V3, and `cut_short` with V18: a match an operator closed or
a room that failed is recorded with its placements as they stood, not a finish, and
a tournament decides it by the higher seed (Q-45). Mode 9, the sandbox, publishes
no result and so has no rows.

Note the consequence for `player_stat.matches`: it counts **both kinds**, but only
results long enough to be paid. Splitting it needs a second counter and can wait
until a ranked mode exists to disagree with it.

```sql
-- Each player's part of a match.
CREATE TABLE match_player (
  match_id     BIGINT UNSIGNED NOT NULL,
  player_id    BIGINT UNSIGNED NOT NULL,
  team         TINYINT UNSIGNED NOT NULL,
  placement    SMALLINT UNSIGNED NOT NULL,
  kills        SMALLINT UNSIGNED NOT NULL,
  deaths       SMALLINT UNSIGNED NOT NULL,
  score        INT UNSIGNED NOT NULL,
  xp_gained    INT UNSIGNED NOT NULL,
  rating_delta SMALLINT NOT NULL DEFAULT 0,
  assists      SMALLINT UNSIGNED NOT NULL DEFAULT 0,
  -- This composite key IS the idempotency mechanism for results: the insert is both the
  -- write and the duplicate check, so there is no window in which they disagree.
  PRIMARY KEY (match_id, player_id),
  KEY ix_mp_player (player_id, match_id DESC),
  CONSTRAINT fk_mp_match  FOREIGN KEY (match_id)  REFERENCES matches (id),
  CONSTRAINT fk_mp_player FOREIGN KEY (player_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

**`PRIMARY KEY (match_id, player_id)` is the idempotency mechanism**
([§5](#5-idempotency)). The MongoDB design carried a separate
`match_results_applied` collection to dedupe; a unique constraint does the same
job with no extra table, no extra write, and no window in which the dedupe
record and the effect disagree.

`match_player.assists` and `player_stat.assists` (V25, plan item 65, 01 §7) sit
beside `kills`; a result without the field, from an arena not yet upgraded, counts
none. `matches.tournament_id` and its index are written and read by nothing: a
tournament's match is found by `tournament_match.match_uid` (below).

### Teams and tournaments

Detailed with the modules that own them in
[04-platform-services](04-platform-services.md). `team_member` is **the source of
truth for membership**, with `player.team_id` a denormalised pointer kept in step in
the same transactions (04 §2); a team's contribution, tags and settings wait for what
uses them. The `leaderboard_snapshot` table an earlier draft had was never built: the
boards are rebuilt from `player_stat` and `matches`
([05 §8](05-worker-and-events.md#8-rebuilding-after-a-store-loss)).

**Teams** (V10, plan item 17; rating and record V12; `renamed_at` V23; the listed
rating V28):

```sql
-- A team: its name, members' count, and its own rating and record from team matches (D-43).
CREATE TABLE team (
  id            BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  name          VARCHAR(64) NOT NULL,                         -- a display name's rules; unique by the collation,
  member_count  TINYINT UNSIGNED NOT NULL,                    -- which ignores case and accents
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  rating        SMALLINT UNSIGNED NOT NULL DEFAULT 1200,
  rated_matches INT UNSIGNED NOT NULL DEFAULT 0,              -- K 32, then 16 after 30
  wins          INT UNSIGNED NOT NULL DEFAULT 0,
  losses        INT UNSIGNED NOT NULL DEFAULT 0,
  draws         INT UNSIGNED NOT NULL DEFAULT 0,
  renamed_at    DATETIME(3) NULL,                             -- one rename in 30 days
  board_rating  SMALLINT UNSIGNED AS (IF(rated_matches >= 10, rating, NULL)) VIRTUAL,   -- listed, as player's (D-65)
  UNIQUE KEY uq_team_name (name),
  KEY idx_team_listed (board_rating DESC, rated_matches DESC, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE team_member (
  team_id   BIGINT UNSIGNED NOT NULL,
  player_id BIGINT UNSIGNED NOT NULL,
  role      TINYINT UNSIGNED NOT NULL,                        -- 0 member, 1 vice leader, 2 leader
  joined_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (team_id, player_id),
  UNIQUE KEY uq_member_player (player_id),                    -- one team a player, held by the schema too
  CONSTRAINT fk_member_team FOREIGN KEY (team_id) REFERENCES team (id),
  CONSTRAINT fk_member_player FOREIGN KEY (player_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE team_invite (
  team_id    BIGINT UNSIGNED NOT NULL,
  player_id  BIGINT UNSIGNED NOT NULL,
  invited_by BIGINT UNSIGNED NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  PRIMARY KEY (team_id, player_id),
  KEY ix_invite_player (player_id),
  KEY ix_invite_expiry (expires_at),                          -- retention deletes the lapsed
  CONSTRAINT fk_invite_team FOREIGN KEY (team_id) REFERENCES team (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- A player asks a team to take them (Q-49); a declined one is kept until it lapses, so it is not sent again.
CREATE TABLE team_application (
  team_id    BIGINT UNSIGNED NOT NULL,
  player_id  BIGINT UNSIGNED NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  declined   BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (team_id, player_id),
  KEY ix_application_player (player_id, expires_at),
  KEY ix_application_expiry (expires_at),
  CONSTRAINT fk_application_team FOREIGN KEY (team_id) REFERENCES team (id),
  CONSTRAINT fk_application_player FOREIGN KEY (player_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- A team's part of a team match.
CREATE TABLE match_team (
  match_id     BIGINT UNSIGNED NOT NULL,
  side         TINYINT UNSIGNED NOT NULL,                     -- 1 or 2, as match_player.team
  team_id      BIGINT UNSIGNED NOT NULL,                      -- no key to team: a disbanded team's rows stay
  placement    TINYINT UNSIGNED NOT NULL,                     -- 1 won or drew, 2 lost
  rating_delta SMALLINT NOT NULL,
  PRIMARY KEY (match_id, side),
  KEY ix_match_team_team (team_id, match_id),
  CONSTRAINT fk_mt_match FOREIGN KEY (match_id) REFERENCES matches (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

A team's `name` is unique by the table's collation, which ignores case and accents:
two names differing only so are the same name. `team_invite` (V10, its expiry index
V19) and `team_application` (V24, plan item 64, Q-49) are deleted by retention once
lapsed; reading deletes neither.

`match_team` is a team's `match_player` (V12,
[D-43](../architecture/03-decision-log.md#d-43--a-team-matchs-sides-are-known-by-their-players-teams-when-the-result-is-applied)):
its primary key is the duplicate check for the team's part of a result, and retention
deletes it with the match's other rows.

**Tournaments** (V11, plan item 18; `mode` and the teams' tables V13, D-44; `format`
V26):

```sql
-- A tournament (D-40, D-44): its state machine lives on this row, and every transition is a
-- conditional update on its state and version.
CREATE TABLE tournament (
  id                BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  name              VARCHAR(64) NOT NULL,
  state             TINYINT UNSIGNED NOT NULL,                -- 0 registration, 1 seeded, 2 running, 3 finished, 4 cancelled
  version           INT UNSIGNED NOT NULL DEFAULT 0,
  max_entries       TINYINT UNSIGNED NOT NULL,
  registration_ends DATETIME(3) NOT NULL,
  starts_at         DATETIME(3) NOT NULL,
  round_minutes     TINYINT UNSIGNED NOT NULL,
  prize_1           BIGINT UNSIGNED NOT NULL,
  prize_2           BIGINT UNSIGNED NOT NULL,
  prize_3           BIGINT UNSIGNED NOT NULL,                 -- to each semi-final's loser
  current_round     TINYINT UNSIGNED NOT NULL DEFAULT 0,
  round_ended_at    DATETIME(3) NULL,
  mode              TINYINT UNSIGNED NOT NULL DEFAULT 1,      -- MatchMode's id: 1 duel, 5 team match
  format            TINYINT UNSIGNED NOT NULL DEFAULT 0,      -- 0 single elimination, 1 round robin
  KEY ix_tournament_state (state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tournament_entry (
  tournament_id BIGINT UNSIGNED NOT NULL,
  player_id     BIGINT UNSIGNED NOT NULL,
  seed          TINYINT UNSIGNED NULL,                        -- set at seeding
  registered_at DATETIME(3) NOT NULL,
  PRIMARY KEY (tournament_id, player_id),
  CONSTRAINT fk_entry_tournament FOREIGN KEY (tournament_id) REFERENCES tournament (id),
  CONSTRAINT fk_entry_player FOREIGN KEY (player_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The bracket, or a round robin's pairings. Players' ids, or teams' when the mode is 5.
CREATE TABLE tournament_match (
  tournament_id BIGINT UNSIGNED NOT NULL,
  round         TINYINT UNSIGNED NOT NULL,                    -- 1 is the first
  slot          TINYINT UNSIGNED NOT NULL,                    -- 0.. within the round
  player_a      BIGINT UNSIGNED NULL,                         -- from the even slot of the round before
  player_b      BIGINT UNSIGNED NULL,                         -- from the odd; the seeds are the entries'
  state         TINYINT UNSIGNED NOT NULL,                    -- 0 pending, 1 ready, 2 done
  match_uid     CHAR(26) NULL,
  ready_at      DATETIME(3) NULL,
  winner        BIGINT UNSIGNED NULL,
  PRIMARY KEY (tournament_id, round, slot),
  KEY ix_tmatch_uid (match_uid),
  CONSTRAINT fk_tmatch_tournament FOREIGN KEY (tournament_id) REFERENCES tournament (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tournament_team_entry (
  tournament_id BIGINT UNSIGNED NOT NULL,
  team_id       BIGINT UNSIGNED NOT NULL,                     -- no key to team: a disbanded team's entry stays
  seed          TINYINT UNSIGNED NULL,
  registered_at DATETIME(3) NOT NULL,
  PRIMARY KEY (tournament_id, team_id),
  CONSTRAINT fk_tentry_tournament FOREIGN KEY (tournament_id) REFERENCES tournament (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tournament_roster (
  tournament_id BIGINT UNSIGNED NOT NULL,
  team_id       BIGINT UNSIGNED NOT NULL,
  player_id     BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (tournament_id, player_id),                     -- one roster a player a tournament
  KEY ix_roster_team (tournament_id, team_id),
  CONSTRAINT fk_roster_tournament FOREIGN KEY (tournament_id) REFERENCES tournament (id),
  CONSTRAINT fk_roster_player FOREIGN KEY (player_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

A match's winner is filled into the next round's slot `slot / 2`, as `player_a`
or `player_b` by which half it came from. A teams' tournament's `tournament_match`
holds teams' ids in `player_a`, `player_b` and `winner` (D-44). A round robin's
matches are all written at seeding; a draw is a match `DONE` whose `winner` is NULL.
Two things in the schema go unused: no query reads `tournament_match` by
`ix_tmatch_uid`, a round's matches being read by its key and their results by
`matches.match_uid`; and nothing writes `matches.tournament_id`. Both are small, and
stay until a contract migration has another reason to run (DOC-17). A finished or
cancelled tournament is deleted 90 days after it was to start, with its entries,
rosters and bracket (§9).

### Seasons and boards

**Seasons** (V27, designed 2026-10-03, plan item 71 (a); the teams' columns V28):

```sql
-- A season of the rating boards: two calendar months, ending at 00:00 UTC on the first of January,
-- March, May, July, September and November (SeasonRepository.endAfter). Its close places, pays and
-- resets the boards in steps, each recorded so a worker that stops resumes it (D-63).
CREATE TABLE season (
  id            SMALLINT UNSIGNED PRIMARY KEY,
  starts_at     DATETIME(3) NOT NULL,
  ends_at       DATETIME(3) NOT NULL,
  placed_at     DATETIME(3) NULL,
  paid_at       DATETIME(3) NULL,
  reset_bound   BIGINT UNSIGNED NULL,
  reset_through BIGINT UNSIGNED NOT NULL DEFAULT 0,
  reset_at      DATETIME(3) NULL,
  team_bound    BIGINT UNSIGNED NULL,                         -- the teams' reset, as the players' (D-65)
  team_reset_at DATETIME(3) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- A past season's places on each player board, and the gems each was paid.
CREATE TABLE season_place (
  season_id  SMALLINT UNSIGNED NOT NULL,
  board      TINYINT UNSIGNED NOT NULL,                       -- the mode's id: 1 duel, 2 tvt, 3 rffa
  place      INT UNSIGNED NOT NULL,
  player_id  BIGINT UNSIGNED NOT NULL,
  rating     SMALLINT UNSIGNED NOT NULL,
  rated      INT UNSIGNED NOT NULL,
  gems       SMALLINT UNSIGNED NOT NULL,
  PRIMARY KEY (season_id, board, place),
  UNIQUE KEY uq_season_board_player (season_id, board, player_id),
  KEY ix_season_place_player (player_id, season_id),
  CONSTRAINT fk_sp_season FOREIGN KEY (season_id) REFERENCES season (id),
  CONSTRAINT fk_sp_player FOREIGN KEY (player_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

`V2__seed.sql` makes season 1, from when it runs to the end of the two calendar
months it falls in (04 §7). A season's progress is in its row
([D-63](../architecture/03-decision-log.md#d-63--a-season-ends-in-three-steps-each-safe-to-repeat-its-places-its-gems-then-its-reset)):
each step's condition is the column before it — `placed_at`, then `paid_at`, then
`reset_at` and `team_reset_at` — and the reset's batches move `reset_through` in the
transaction that resets them. A place's own row is its proof: `uq_season_board_player`
keeps a player to one place a board, and the gems it records are the ones its ledger
row paid. `season_place.rated` is `INT UNSIGNED`, as wide as the rated counts it
copies; V27 had it `SMALLINT UNSIGNED`, which a count past 65 535 would have overflowed
and so stopped the season's close
([defect D-42](../defects.md#3-data-and-the-result-pipeline)). Seasons and their
places are kept for good: a past season's board is a player's record.

**A board of teams and its seasons** (V28, designed 2026-10-04, plan item 73): the
listed rating on `team` and its index are in the team's table above.

```sql
-- A past season's places on the board of teams (D-65).
CREATE TABLE season_team_place (
  season_id SMALLINT UNSIGNED NOT NULL,
  place     INT UNSIGNED NOT NULL,
  team_id   BIGINT UNSIGNED NOT NULL,
  name      VARCHAR(64) NOT NULL,
  rating    SMALLINT UNSIGNED NOT NULL,
  rated     INT UNSIGNED NOT NULL,
  gems      SMALLINT UNSIGNED NOT NULL,
  PRIMARY KEY (season_id, place),
  UNIQUE KEY uq_season_team (season_id, team_id),
  CONSTRAINT fk_stp_season FOREIGN KEY (season_id) REFERENCES season (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Whom a team's place paid: its members at the season's end who played a rated team match in it.
CREATE TABLE season_team_payee (
  season_id SMALLINT UNSIGNED NOT NULL,
  player_id BIGINT UNSIGNED NOT NULL,
  team_id   BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (season_id, player_id),
  CONSTRAINT fk_stpay_season FOREIGN KEY (season_id) REFERENCES season (id),
  CONSTRAINT fk_stpay_player FOREIGN KEY (player_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

Both are written in the season's first step, with the players' places
([D-65](../architecture/03-decision-log.md#d-65--a-teams-season-place-pays-each-member-who-played-for-it-that-season)):
a payee is read as a member of a listed team, in `team_member` at the end, with a
row in `match_player` for one of the team's rated team matches (`match_team` by
`ix_match_team_team`, `matches` timed, mode 5, not cut short) ended within the
season. One player, one team at a time (`uq_member_player`), so one payee row a
season. `team_id` keys neither to `team`: a team disbanded since keeps its place,
under the name it had then.

**The season pass** (V31, designed 2026-10-04, plan item 75 (b)):

```sql
-- The season pass (D-69): a player's points in a season and, for each track, the last tier paid.
CREATE TABLE season_pass (
  player_id    BIGINT UNSIGNED NOT NULL,
  season_id    SMALLINT UNSIGNED NOT NULL,
  points       INT UNSIGNED NOT NULL DEFAULT 0,
  premium      BOOLEAN NOT NULL DEFAULT FALSE,
  free_paid    TINYINT UNSIGNED NOT NULL DEFAULT 0,
  premium_paid TINYINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (player_id, season_id),
  KEY ix_pass_season (season_id),
  CONSTRAINT fk_pass_player FOREIGN KEY (player_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

A row is made by a player's first points or premium in a season, under the
player's row lock, and only ever raised
([D-69](../architecture/03-decision-log.md#d-69--a-season-pass-pays-each-tier-as-its-points-cross-it-each-track-to-its-own-mark-and-its-premium-never-pays-coins)):
points, premium from false to true, each mark to the tier paid. A tier's coins
and gems are ledger reason 11, keyed `pass:{season}:{free|premium}:{tier}:{player}`;
its boost is an `inventory_item` added in the same transaction, made once by
the mark. Premium is reason 1, a purchase, keyed `pass:{season}:{player}`. The
season being played, the newest not yet placed, must exist: without one a result
cannot be applied, and the error says so. Retention keeps the season being played
and the five before it (`ix_pass_season`).

### Daily goals

**V29, designed 2026-10-04, plan item 74:**

```sql
-- A player's progress on their day's goals, which are drawn, not stored (D-66); kept a week.
CREATE TABLE daily_goal (
  player_id BIGINT UNSIGNED NOT NULL,
  day       DATE NOT NULL,                                    -- the result's UTC day
  goal_id   VARCHAR(24) NOT NULL,                             -- DailyGoals' id, such as wins_1
  progress  INT UNSIGNED NOT NULL,
  PRIMARY KEY (player_id, day, goal_id),
  KEY ix_daily_goal_day (day),
  CONSTRAINT fk_dg_player FOREIGN KEY (player_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

Written only in the result's transaction, under the player's lock
([D-66](../architecture/03-decision-log.md#d-66--a-days-goals-are-drawn-not-stored-and-paid-as-results-meet-them));
which goals a day has is drawn, not stored. A result's count is added to each goal's
progress by the statement that writes it, and the sum read back under the row's lock,
so two results of one player add up whatever their order. A goal of rated matches
counts a rated match played against someone: a ranked mode whose ratings moved, or
a team match both teams' players were in; never a walkover (defect
[D-51](../defects.md#3-data-and-the-result-pipeline)). A result under five seconds
counts toward no goal at all (defect D-45). Ledger
reason 8 is a daily goal's, keyed `daily:{player}:{day}:{goal}`, and the day's three
together `daily:{player}:{day}:set`. Retention deletes days more than a week old
(`ix_daily_goal_day`).

### Social

**V14, the social layer** (plan item 21, 04 §9, D-45):

```sql
CREATE TABLE friend (
  player_id BIGINT UNSIGNED NOT NULL,
  friend_id BIGINT UNSIGNED NOT NULL,
  since     DATETIME(3) NOT NULL,
  PRIMARY KEY (player_id, friend_id),                         -- both ways: a friendship is two rows
  CONSTRAINT fk_friend_player FOREIGN KEY (player_id) REFERENCES player (id),
  CONSTRAINT fk_friend_friend FOREIGN KEY (friend_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE friend_request (
  from_id    BIGINT UNSIGNED NOT NULL,
  to_id      BIGINT UNSIGNED NOT NULL,
  expires_at DATETIME(3) NOT NULL,                            -- seven days
  PRIMARY KEY (from_id, to_id),
  KEY ix_request_to (to_id),
  KEY ix_request_expiry (expires_at),                         -- retention deletes the lapsed
  CONSTRAINT fk_request_from FOREIGN KEY (from_id) REFERENCES player (id),
  CONSTRAINT fk_request_to FOREIGN KEY (to_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE block (
  player_id  BIGINT UNSIGNED NOT NULL,
  blocked_id BIGINT UNSIGNED NOT NULL,
  since      DATETIME(3) NOT NULL,
  PRIMARY KEY (player_id, blocked_id),
  KEY ix_block_blocked (blocked_id),
  CONSTRAINT fk_block_player FOREIGN KEY (player_id) REFERENCES player (id),
  CONSTRAINT fk_block_blocked FOREIGN KEY (blocked_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inbox (
  id         BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  player_id  BIGINT UNSIGNED NOT NULL,
  kind       TINYINT UNSIGNED NOT NULL,                       -- 1 friend request, 2 accepted, 3 team invite, 4 prize,
                                                              -- 5 team application, 6 season reward
  ref        BIGINT UNSIGNED NOT NULL,                        -- the other player's, team's or tournament's id; for 6,
                                                              -- the season × 10 + the board (1, 2, 3, or 5 for teams)
  created_at DATETIME(3) NOT NULL,
  read_at    DATETIME(3) NULL,
  UNIQUE KEY uq_inbox_item (player_id, kind, ref),
  KEY ix_inbox_created (created_at),                          -- retention
  CONSTRAINT fk_inbox_player FOREIGN KEY (player_id) REFERENCES player (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

A friend request lapses after seven days and is deleted by retention once it has
(`ix_request_expiry`, V19); friendships and blocks are kept. An inbox item is one a
player, kind and reference (`uq_inbox_item`), so an item written again by a retry is
the same item; items are kept 30 days, read or not (`ix_inbox_created`).

### Operations

```sql
-- Every admin call, allowed or refused (04 §10, D-30): when, which call, its target, what it asked,
-- and what came of it; the change a call makes and its row here are written in one transaction.
CREATE TABLE admin_audit (
  id       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  at       DATETIME(3)     NOT NULL,
  action   VARCHAR(32)     NOT NULL,
  target   VARCHAR(128)    NULL,
  request  VARCHAR(1024)   NULL,
  outcome  VARCHAR(32)     NOT NULL,
  PRIMARY KEY (id),
  KEY ix_admin_audit_at (at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The failover epoch (06 §10, D-35): one row, raised only by a promotion, on the new primary;
-- replication carries it to the replica. A process given both hosts writes to the writable one with
-- the highest epoch, and never to one below the highest it has seen.
CREATE TABLE ha_epoch (
    id    TINYINT UNSIGNED NOT NULL PRIMARY KEY,
    epoch BIGINT UNSIGNED  NOT NULL,
    CONSTRAINT ha_epoch_one_row CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- A replica's lag, measured (D-58): every worker stamps this one row on the primary once a second,
-- and replication carries it; the primary's less a replica's is that replica's lag.
CREATE TABLE ha_heartbeat (
  id TINYINT UNSIGNED PRIMARY KEY,
  at DATETIME(6) NOT NULL,
  CONSTRAINT ck_heartbeat_one_row CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- What each backup step did (06 §10, D-71, D-72), written by the scripts as the backup account; every
-- worker reports when each last succeeded.
CREATE TABLE backup_run (
  id          BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  kind        TINYINT UNSIGNED NOT NULL,                      -- 1 dump, 2 restore proof, 3 copy off the site
  started_at  DATETIME(3) NOT NULL,
  finished_at DATETIME(3) NOT NULL,
  ok          BOOLEAN NOT NULL,
  detail      VARCHAR(255) NOT NULL,
  seconds     DOUBLE NULL,                                    -- a proof's restore and replay; NULL for the others
  KEY ix_backup_kind (kind, finished_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

`admin_audit` (V7) is the admin API's record, an `admin_audit` row written with the
change it describes or alone for a refusal or a listing, its request cut to 1 024
characters; it is kept for good. `ha_epoch` (V8) and `ha_heartbeat` (V21) are one row
each, held so by their `CHECK`s and written by the seed: the epoch a promotion raises,
and the stamp every worker writes on the primary each second, which a replica's lag is
measured by (§10). `backup_run` (V32, `seconds` V33) is written by the backup scripts
as the `backup` account, which may insert into it and do nothing else there (§10).

## 4. The three transactions that matter

Everything else is CRUD. These three are where money and progression are at
stake.

### Applying a match result

Consumed from the event stream by `worker`, which may deliver the same result
more than once
([NFR-7](../requirements/01-scope-and-nfrs.md#4-non-functional-requirements)).

```sql
-- 0. Before any transaction: every value is checked against its column width in Java, and a
--    result that ended more than 30 days ago is refused (§9). Either is dead-lettered intact
--    (see "Why not INSERT IGNORE"). The boosts that ran when the match ended are read first,
--    on their own (D-38), and the rewards worked out from them in worker.

START TRANSACTION;

-- 0b. A team match only (D-43): the teams of the players it names, read, then locked
--     before the players, the order every team action takes (D-39).
SELECT team_id FROM team_member WHERE player_id IN (?, ?, ...);
SELECT id, rating, rated_matches FROM team WHERE id IN (?, ?) ORDER BY id FOR UPDATE;

-- 1. Lock every player the result names, lowest id first, with their totals' rows. Exclusive,
--    and up front.
SELECT p.id, p.xp, p.rating_duel, p.rated_duels, p.rating_tvt, p.rated_tvts, p.rating_rffa,
       p.rated_rffas, p.team_id, p.display_name, p.level,
       s.kills, s.wins, s.matches, s.assists, s.best_score, s.playtime_s
  FROM player p LEFT JOIN player_stat s ON s.player_id = p.id
 WHERE p.id IN (?, ?, ...) ORDER BY p.id FOR UPDATE;
-- A team match reads the players' teams again here, from team_id under this lock: every
-- change of team writes the player's row, so this read is current, where a second plain
-- read of team_member repeats 0b's snapshot (D-36, D-59). A side's team is the one its
-- players still share, and one who left is passed over; a side whose team was not locked
-- in 0b (disbanded, or joined, since) is not rated. Teams are rated only when step 4
-- inserted a row: by the delivery that first applies the result, never by a redelivery.
-- The rows returned are the players that exist. The rest are reported as unapplicable.

-- 2. The match row. Idempotent on match_uid.
INSERT INTO matches (match_uid, kind, mode, arena, started_at, ended_at, cut_short)
VALUES (?,?,?,?,?,?,?)
ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id);

-- 3. The season being played, whose pass the result's points go to: the newest not yet placed.
SELECT id, ends_at FROM season WHERE placed_at IS NULL ORDER BY id DESC LIMIT 1;

-- 4. Per player, lowest id first: a plain insert. Error 1062 (duplicate key) means this
--    player's part was already applied, and the rest of it is skipped; any other error is an
--    error.
INSERT INTO match_player
  (match_id, player_id, team, placement, kills, deaths, score, xp_gained, rating_delta, assists)
VALUES (?,?,?,?,?,?,?,?,?,?);

-- 5. Only if step 4 inserted: progression. The level is computed in Java from the xp read
--    under the lock in step 1; so is a rated match's rating delta, from the ratings and counts
--    read there (04 §4): a duel's from the two players', a team-vs-team's from each team's
--    mean (D-26), a free-for-all's from the whole field's (D-28). Step 4 records it. A
--    team-vs-team writes rating_tvt and rated_tvts in place of the duel's two, a free-for-all
--    rating_rffa and rated_rffas. first_played_on is the earliest day, so a result arriving
--    late from an earlier day moves it back (05 §11).
UPDATE player
   SET xp = xp + ?, level = GREATEST(level, ?),
       rating_duel = GREATEST(0, CAST(rating_duel AS SIGNED) + ?),
       rated_duels = rated_duels + ?,
       first_played_on = LEAST(COALESCE(first_played_on, ?), ?)
 WHERE id = ?;
-- Each account level crossed that has a milestone, level 5 and every tenth: 20 gems through
-- the ledger (step 7's path), keyed milestone:{player}:{level} (D-61).

-- 6. Only if step 4 inserted and the result is long enough to be paid (RewardRules' minimum
--    play): the totals, and what they and the day count towards.
INSERT INTO player_stat (player_id, matches, wins, kills, deaths, best_score, playtime_s, assists)
VALUES (?,1,?,?,?,?,?,?)
ON DUPLICATE KEY UPDATE
  matches = matches + 1, wins = wins + VALUES(wins),
  kills = kills + VALUES(kills), deaths = deaths + VALUES(deaths),
  assists = assists + VALUES(assists),
  best_score = GREATEST(best_score, VALUES(best_score)),
  playtime_s = playtime_s + VALUES(playtime_s);
-- Each achievement whose stat the result carried across its threshold: its gems, keyed
-- achievement:{player}:{id} (D-64).
INSERT INTO daily_goal (player_id, day, goal_id, progress) VALUES (?,?,?,?)
ON DUPLICATE KEY UPDATE progress = progress + VALUES(progress);   -- each of the day's goals it moves
SELECT progress FROM daily_goal WHERE player_id = ? AND day = ? AND goal_id = ? FOR UPDATE;
-- Each goal that reached its target: its coins, keyed daily:{player}:{day}:{goal}; the
-- result that meets the day's third: the set's gems, keyed daily:{player}:{day}:set (D-66).
INSERT INTO season_pass (player_id, season_id, points) VALUES (?,?,?)
ON DUPLICATE KEY UPDATE points = points + VALUES(points);         -- 10, and 50 a goal met
SELECT points, premium, free_paid, premium_paid FROM season_pass
 WHERE player_id = ? AND season_id = ? FOR UPDATE;
-- Each tier crossed, on each track the pass has: its coins or gems, keyed
-- pass:{season}:{free|premium}:{tier}:{player}, and its boost an inventory_item (D-69).
UPDATE season_pass SET free_paid = ?, premium_paid = ? WHERE player_id = ? AND season_id = ?;

-- 7. The match's coins, if any, through the one path that moves a balance (defect D-13),
--    the purchase's. Every payment above is the same four statements, in coins or gems:
SELECT coins FROM player WHERE id = ? FOR UPDATE;         -- already held since step 1
SELECT 1 FROM ledger WHERE idem_key = ?;                  -- match:{uid}:{player}
INSERT INTO ledger (player_id, currency, delta, balance_after, reason, ref, idem_key)
VALUES (?,0,?,?,0,?,?);
UPDATE player SET coins = coins + ? WHERE id = ?;

-- 8. Activity (D-47): a row a player a day, for each player whose part step 4 inserted and
--    step 6 counted; a day already there is kept.
INSERT IGNORE INTO player_day (day, player_id) VALUES (?, ?), (?, ?), ...;

-- 9. A team match whose sides are two teams, by the delivery that first applied it: per
--    side, a plain insert, 1062 meaning done, and only if it inserted, the team's rating
--    and counts (players' ratings do not move).
INSERT INTO match_team (match_id, side, team_id, placement, rating_delta) VALUES (?,?,?,?,?);
UPDATE team SET rating = GREATEST(0, CAST(rating AS SIGNED) + ?), rated_matches = rated_matches + 1,
       wins = wins + ?, losses = losses + ?, draws = draws + ? WHERE id = ?;

COMMIT;
```

The shape to notice: **step 4 is both the write and the duplicate check.** No
separate "have I seen this?" query, so there is no window between checking and
acting. Every payment in the transaction is keyed in the ledger as well (§5), so
even a step that ran twice would pay once.

**Why not `INSERT IGNORE`.** This document specified it, and the code used it
until 2026-09-25. `IGNORE` downgrades *every* error on the statement to a
warning and zero rows, not only the duplicate key it was there for. Measured
against MySQL 8.0.46:

| What was inserted | With `IGNORE` | What the code concluded |
|---|---|---|
| A player id with no `player` row | `ROW_COUNT() = 0`, no exception | "already applied" — so the result was acknowledged and never paid |
| `kills = 70 000` into `SMALLINT UNSIGNED` | row **inserted** with 65 535 | the balances were then updated with 70 000, so row and balance disagreed for good |
| `score = -5` into `INT UNSIGNED` | row inserted with 0 | the same |

The ledger still reconciled in the second and third cases, because it was wrong
the same way — which is why nothing would have caught it.

**Why lock first.** Sorting the player loop by id was meant to prevent deadlock
and did not. The insert's foreign-key check takes a *shared* lock on the player
row; the balance update then wants an *exclusive* one. Two transactions each
holding the shared lock wait on each other. Measured: 24 concurrent matches
sharing two players failed on three runs out of three, several exhausting every
retry. Taking the exclusive lock at the start removes the upgrade, and puts this
path in the same lock order as a purchase, which also locks `player` first.
Under REPEATABLE READ a locking read of an id that does not exist takes a gap
lock; it lasts the few milliseconds of this transaction, and a missing player is
rare. The players' `player_stat` rows are locked with them, since step 6 writes them
and the achievements are judged from them. Each is made with its player (§3), so the
read finds a row and locks no gap: one that found none would lock the gap where the
row would go, and two first results inserting into that gap would deadlock.

**Nothing read plainly is written back.** Under REPEATABLE READ a plain read sees the
snapshot of the transaction's first plain read, and in a team match that is step 0b's,
taken before the players were locked: another result of the same player, committed
while this one waited for the lock, is not in it. So whatever a result adds to — the
totals, a goal's progress, the pass's points — is added by the statement that writes
it, and what the rule needs is read back under the row's lock. The ledger's key check
is a plain read too; its unique index is the backstop (§4, a purchase).

**`player.level` is the account level, written with the xp.** It is a different
thing from a tank's level, which lives for one life inside a match. It is a
function of lifetime xp, defined in `worker/AccountLevels` beside the other
balance rules: `3 000 × (L − 1)^p` to a cap of 100 at 6 000 000. It is computed
from the xp read under this transaction's lock and written as
`GREATEST(level, new)`, so a rebalance never takes a level away. Rows from
before it existed are corrected at the player's next match
(defect [D-12](../defects.md#3-data-and-the-result-pipeline)). `player.version` stays
0: it is for optimistic locking where a person edits a row, and nothing does
yet.

Leaderboards are updated *after* the commit, with **`ZADD … GT`** against
j-redis — not `ZINCRBY`, which
[05 §7](05-worker-and-events.md#7-the-one-place-exactly-once-nearly-leaked)
records as the rejected option because an increment double-counts under the
redelivery this pipeline is built to expect. The board write is a maximum and
therefore idempotent, so the entry is acknowledged **whether or not it lands**:
this path pays players, and holding a payment because an index is unwell would
be the wrong way round. A miss is counted as `unranked`.

### A purchase

```sql
START TRANSACTION;

SELECT coins FROM player WHERE id = ? FOR UPDATE;         -- lock first, read after
SELECT 1 FROM ledger WHERE idem_key = ?;                  -- used already -> already bought
-- only then: verify balance and that the sku is purchasable, in code

INSERT INTO ledger (player_id, currency, delta, balance_after, reason, ref, idem_key)
VALUES (?, 0, -?, ?, 1, ?, ?);                            -- 1062 -> already bought

UPDATE player SET coins = coins - ? WHERE id = ?;

INSERT INTO inventory_item (player_id, item_id, qty) VALUES (?,?,?)
ON DUPLICATE KEY UPDATE qty = qty + VALUES(qty);

COMMIT;
```

The `idem_key` is derived from the client's transaction id, so a player who
taps Buy twice, or whose connection drops mid-request and retries, is charged
once. The unique violation on retry is the *expected* path, not an error:
catch it, and report the original purchase as successful. Three details, each a
bug before 2026-09-26:

- **The key check comes before the funds check.** The other way round, a retry
  whose first attempt had spent the balance below the price was answered
  `INSUFFICIENT_FUNDS`: charged once, as promised, and told it had failed. The
  check is a plain read, not `FOR UPDATE`. A locking read of an absent key takes
  a gap lock, and gap-lock-then-insert on nearby keys deadlocks. The player row
  lock already serialises every writer of that player's ledger rows.
- **The stored key is `buy:{playerId}:{clientKey}`.** The ledger's unique index
  is global, and a client's key is unique only to that client: two players
  sending `txn-1` collided, and the second got nothing.
- **Only 1062 means "used already".** `SQLIntegrityConstraintViolationException`
  also covers a foreign-key failure, which is an error, not a duplicate.

This is `EconomyRepository.move` (`moveCoins` is the same in coins), and it is
**the only code that moves a balance**, in either currency: a match's reward is paid
through it too, inside the result transaction, and so is every gem a milestone, an
achievement, a goal, a pass tier, a season's place, a tournament's place or a paid
order brings. Until D-13 the match path had its own copy, and reconciliation
held because the two happened to agree.

Limited-stock items take a `DECR stock:{sku}` in j-redis *before* this
transaction and `INCR` it back if the transaction fails. The store is the
right place for a hot counter; the database is the right place for the
consequence.

### Granting a reward

Same shape as a purchase with the sign reversed, and the `idem_key` derived
from the source: `tourney:{id}:{place}:{playerId}`,
`daily:{playerId}:{yyyy-mm-dd}:{goalId}`; every form is in §5.
**A reward without a natural idempotency key is a bug**, because every delivery
path retries eventually.

## 5. Idempotency

The rule: **every write that can be retried has a unique key that makes the
second attempt a no-op.** Three forms, in order of preference.

| Form | Example | When |
|---|---|---|
| Natural composite key | `match_player (match_id, player_id)` | The row itself identifies the event. Best: no extra state. |
| Derived key on the ledger | `idem_key = "tourney:42:1:1001"` | Currency movements, where the row is not naturally unique |
| Client-supplied key | `clientTxnId` from the app | Player-initiated actions that may be retried from the device |

Every key the ledger is written under, `ledger.idem_key` (80 characters at most):

| Key | Moves | Written by |
|---|---|---|
| `match:{matchUid}:{player}` | a result's coins, reason 0 | `MatchResultRepository` |
| `buy:{player}:{clientKey}` | a purchase, coins or gems, reason 1 | `EconomyRepository.purchase` |
| `pass:{season}:{player}` | the pass's premium, 500 gems, reason 1 | `SeasonPassRepository.buyPremium` |
| `tourney:{tournament}:{place}:{player}`, and the same with `:gems` | a tournament place's coins, and its gems, reason 3 | `worker/TournamentScheduler` |
| `level:{player}:{clientKey}` | an item's next level, reason 4 | `EconomyRepository.raiseLevel` |
| `milestone:{player}:{level}` | an account level's gems, reason 5 | `MatchResultRepository` |
| `season:{season}:{board}:{player}` | a season place's gems, reason 6; board 5 is the teams' | `worker/SeasonKeeper` |
| `achievement:{player}:{achievementId}` | an achievement's gems, reason 7 | `MatchResultRepository` |
| `daily:{player}:{yyyy-mm-dd}:{goalId}`, `daily:{player}:{yyyy-mm-dd}:set` | a goal's coins; the day's three's gems; reason 8 | `MatchResultRepository` |
| `payment:{orderId}`, `payment:first:{player}` | a paid order's gems; a player's first paid order's as many again; reason 9 | `PaymentRepository.confirm` |
| `refund:{orderId}` | a refund's gems taken back, reason 10 | `PaymentRepository.refund` (through `AdminRepository.refund`) |
| `pass:{season}:{track}:{tier}:{player}`, track `free` or `premium` | a tier's coins or gems, reason 11 | `SeasonPassRepository` |

Beside the ledger, the natural keys: `matches.uq_match_uid`, `match_player` and
`match_team`'s primary keys, `player_day`'s, `inbox.uq_inbox_item`,
`payment_order.uq_order_client_key`, the tournament tables' entries, and
`boost_activation.idem_key` (`act:{player}:{clientKey}`).

There is deliberately **no generic `idempotency` table**. A shared table
becomes a write hotspot, adds a second row to every transaction, and — worst —
lets the dedupe record and the effect diverge if a migration or a cleanup job
touches one and not the other.

## 6. Concurrency

Contention here is low: a player's rows are touched by that player's requests,
and a match result touches each player once. The failure mode to design against
is not throughput, it is **deadlock**.

- **Lock in a consistent order**, always ascending by primary key. A match
  result touching twelve players sorts the ids before it starts. Two results
  sharing players then queue instead of deadlocking. A foreign key's check is a
  lock too, shared, on the row it names: a team's tournament roster is inserted
  in ascending player id, and wearing an item locks the player's row first, as a
  purchase does, then reads what it holds (D-37, plan item 60 (b)).
- **`SELECT … FOR UPDATE` before reading anything you will write.** Reading
  first and locking later is a lost update.
- **Keep transactions short and free of I/O.** No HTTP call, no j-redis
  round trip, no logging to disk between `START` and `COMMIT`.
- **Retry on error 1213 (deadlock) and 1205 (lock wait timeout)**, three
  attempts in all, with jittered backoff. The whole transaction is rolled back
  before each retry — by `Tx` itself, since InnoDB rolls back all of it only on
  a deadlock and just the statement on a lock wait timeout — so a retry is safe
  by construction, and idempotency makes it safe even so.
- Optimistic `version` columns are used only where a human is in the loop and a
  conflict should be reported rather than retried — editing team settings, for
  instance.

## 7. Connection pooling

HikariCP, one pool per process.

**Size the pool for the database, not for the application.** The pool's job is
to bound concurrent work reaching MySQL; making it large does not make MySQL
faster, it makes it thrash. A reasonable starting point is
`cores × 2` on the database host — around **12–16 per process**, giving
5 processes × 16 = 80 connections against a default `max_connections` of 151.

This is worth stating explicitly because Java 21 invites the opposite mistake:
**virtual threads do not change how many queries a database can run at once.**
A `platform` service can happily have 10 000 virtual threads waiting on a pool
of 16, and that is the correct arrangement — the queue is visible and bounded,
rather than pushed into MySQL where it turns into lock contention.

```
maximumPoolSize     16 platform, 8 worker, 2 the leaderboard rebuild; BACKEND_DB_POOL_SIZE sets it
minimumIdle         4 (or the pool's size, if smaller)
connectionTimeout   3000 ms     fail fast; the caller can retry
maxLifetime         1700000 ms  under MySQL's wait_timeout
```

**Every call has a time limit** (designed 2026-10-02, plan item 53, defect O-9).
The pool's own wait above was the only one; the driver waited on a socket for as
long as TCP did, so a primary whose machine vanished, or was fenced off by the
runbook's `DROP` rule, held each statement in flight for about two hours:
`worker`'s one consumer, the tournament's one tick, then every request once
`platform`'s sixteen connections were all waiting.

```
connectTimeout                 2000 ms   TCP to a host that does not answer
socketTimeout                  30000 ms  a statement that has had no reply for that long
innodb_lock_wait_timeout       20 s      the session's: a lock wait is told as 1205, which Tx
                                         retries, before the socket gives up on it
the primary's probe            2000 ms   connect and socket both: two point reads (§10)
migrations                     none      their own connection: one may run for minutes
```

- **30 s is above any statement that works**: the slowest in a request or a job
  are the admin's figures and a rating board's own place (defect D-35), seconds at
  most; retention deletes in batches, and the daily ledger check reads the ledger
  a range of players at a time, each range its own statement, since one statement
  over a ledger kept for good would outgrow any limit (05 §9). A statement cut is a
  `SQLException`: a
  request answers 503, the worker's consumer leaves the result to be delivered
  again, the tournament's tick counts a failure and tries at the next.
- **The probe is the short one**, since it runs for every new connection: a
  frozen server still accepts TCP, the kernel answering for it, and then sends
  no greeting, so a connect limit alone would leave each new connection waiting
  on the socket's. With the probe at 2 s a dead host costs each new connection
  that much while it is listed.
- **Migrations are not cut**: Flyway is given a connection of its own without a
  socket limit, opened for `migrate()` and closed after it.

## 8. Migrations

Flyway, versioned SQL under `src/main/resources/db/migration`.

- **The baseline** (squashed 2026-10-04,
  [D-75](../architecture/03-decision-log.md#d-75--the-migrations-are-squashed-into-one-baseline-before-the-first-launch)):
  `V1__schema.sql` makes every table as §3 shows it, and `V2__seed.sql` writes
  the rows a first launch needs: the failover epoch (§10), the replica
  heartbeat and season 1 (§3, seasons). They replaced V1 to V34, the steps by
  which the schema grew, before any database that matters held them; those
  steps are in git, to commit ce30aa7. The `V`-numbers the sections below name
  are those steps', kept as the history of each table.
- **Forward-only.** An applied migration is never edited; a mistake is fixed by
  a new migration, from V3 on. Flyway's checksum will refuse the edit anyway,
  which is the point.
- **Expand and contract** for anything breaking: add the new column, backfill,
  switch the code, drop the old column in a later release. Three deployments,
  no downtime, and every intermediate state is one the running code understands.
- **A backfill is never one statement over a populated table** (designed
  2026-10-02, plan item 60 (c),
  defect [D-39](../defects.md#3-data-and-the-result-pipeline)). V16 filled
  `player_day` with one `INSERT … SELECT` over every match a player played and
  one `UPDATE … JOIN` over all of them: under REPEATABLE READ an `INSERT …
  SELECT` holds a shared lock on every row it reads, so on a populated primary
  every result would wait for the whole of it. A backfill goes in batches of a
  thousand rows by primary key, each its own transaction, written so a batch
  run twice changes nothing; and it runs once every process that writes the
  table writes the new column too, or a process not yet upgraded leaves rows
  the backfill has already passed. A migration only adds the column or table;
  the backfill is a `worker` task or a tool, after the deploy.
- **Migrations run at every `platform` and `worker` start**
  ([operations/01 §9](../operations/01-deploy.md#9-mysql)), so a release needs no
  separate step; starts that race are serialised by Flyway's own lock in the
  database. An applied migration whose checksum no longer matches stops the start.
- Large `ALTER`s on a big table use `ALGORITHM=INPLACE, LOCK=NONE` where MySQL
  supports it, and are rehearsed on a copy first. The tables that will hurt are
  in [§9](#9-growth-and-retention).

## 9. Growth and retention

`match_player` is the only table that grows dangerously. At 5 minutes per match
it gains roughly one row per player per five minutes:

| Concurrent players | Rows/day | Storage/day |
|---|---|---|
| 1 000 (Phase 3) | 288 000 | ~30 MB |
| 10 000 (launch) | 2.9 M | ~290 MB |
| 50 000 (design target) | 14.4 M | ~1.4 GB |

At launch scale that is about **100 GB a year** from one table, so retention is
a design decision rather than an afterthought. **Built 2026-09-26**
(defect [D-14](../defects.md#3-data-and-the-result-pipeline)):

- **Keep 90 days of detail.** Everything a player sees beyond that — total
  kills, best score, matches played — is already in `player_stat`, which is
  updated at write time. Each worker runs `Retention` daily: matches older than
  90 days, with their `match_player` and `match_team` rows, deleted oldest first in
  batches of 1 000 (`ix_match_ended`, V3). Player totals, stats and the ledger are never
  touched. The same run deletes the inbox's items past 30 days, players' days
  past 90, and, from plan item 57, friend requests and team invitations past
  their seven days (`ix_request_expiry`, `ix_invite_expiry`, V19), and from
  plan item 64 team applications past theirs (`ix_application_expiry`, V24), which nothing
  deleted before (defect [D-40](../defects.md#3-data-and-the-result-pipeline)).
- **The other tables that grow, each with its limit** (plan item 60 (b),
  defect [D-38](../defects.md#3-data-and-the-result-pipeline)). `boost` gains a row a
  boost run and `boost_activation` a row a key used, together no faster than
  the ledger's purchases: an ended boost is deleted 31 days after its end, a
  day longer than a result may be late, so a late result still finds the boost
  its match ended in, and a key 30 days after its use, by indexes on `ends_at`
  and `at` (V22). A tournament is at most a few hundred rows across its five
  tables, made by an operator: a finished or cancelled one is deleted 90 days
  after it was to start, as a match's detail is, with its entries, rosters and
  bracket, one tournament a transaction; nothing changes a tournament once it
  is either, so nothing is locked first
  ([Q-47](../requirements/01-scope-and-nfrs.md#7-open-questions)). Its prizes
  stay in the ledger. One still running is kept: that is an operator's matter.
- **The rest of the daily run:** daily goals' progress more than a week old
  (`ix_daily_goal_day`); orders pending longer than a day, expired, their rows kept
  (`ix_order_state`); season passes older than the six seasons kept
  (`ix_pass_season`); and `backup_run` rows past 90 days, but never a kind's newest
  success, so the last time a step worked is not lost to age (§10). Each step stands
  on its own: one that fails is logged and the next still runs, and the run's time
  is reported either way. The order is in the
  [worker README](../../backend/worker/README.md#retention).
- **A result more than 30 days late is refused**, and dead-lettered as
  evidence. This is the other half of retention and not optional. A result is
  recognised as a redelivery by its `match_player` rows; once those are deleted
  it would be applied again. The ledger's key would still stop double coins,
  but not double xp, level or stats. So acceptance must close well before
  deletion: 30 days against 90. The purge refuses any cutoff inside the
  acceptance window, so a configuration change cannot break this.
- **The ledger is never deleted, and it is not small.** An earlier draft said
  it held only purchases and rewards, "not matches". But every match pays
  coins, so the ledger gains a row per player per match, as fast as
  `match_player`, and keeps them for good. It is the audit trail: losing it
  means being unable to answer "where did my gems go". At the design target
  that is about 5 billion rows a year, which makes it the table that forces the
  next step.
- **No partitioning: measured, the purge does not need it, and the tables could
  not have it** (plan item 76 (b),
  [D-72](../architecture/03-decision-log.md#d-72--the-tables-growth-is-watched-against-measured-triggers-and-a-restore-not-the-purge-is-what-binds)).
  The plan written here before was impossible
  ([DOC-20](../defects.md#7-documentation)): MySQL refuses foreign keys on a
  partitioned InnoDB table, `match_player` has no auto-increment id, and every
  unique key must include the partition column, which would end
  `uq_ledger_idem` and `uq_match_uid` as guarantees. **Measured instead**
  (`tools/PurgeBenchmark`, the worker's own batches over 200 000 matches past
  their days, on the development VM): a result of one player, the public
  arena's stay, is deleted at **11 600 rows a second**; one of four at 5 900
  matches and 24 000 players' rows a second, two runs within 2 %. At the design
  target's 14.5 M rows a day, the worst case, one player each, is **21 minutes
  a day**; at launch's 2.9 M, 4 minutes. Both are under the trigger, which
  stays: `match_player` past 500 M rows, or a day's retention run past 30
  minutes, both now reported by every worker (below).
- **What binds is a restore, not the purge.** The ledger is never deleted, and a
  logical restore reads every row back: the weekly proof restored 237 000 rows
  in about 10 s, **24 000 rows a second** on this VM. At that rate a year at
  launch, about 1.3 billion rows, is 15 hours; at the design target, days. A
  last-resort restore, both database machines lost or the data itself wrong, is
  meant to take **at most 4 hours** (D-72). So the weekly proof's restore time
  is reported, and at **2 hours** the next step is taken: physical backups,
  the clone plugin the replica's rebuild already uses copying the primary's
  files to the backup machine at disk speed, the binlog copies replayed from
  where the clone ends (06 §10). On this VM the 2 hours would come within about
  60 days of launch; on the production disks, which `restore-proof` measures
  every week, it is expected later, and is not known (Q-3).
- **Watched, by every worker:** `backend_mysql_table_rows{table}`, InnoDB's
  estimate for the tables that grow (`ledger`, `match_player`, `matches`,
  `player_day`, `inbox`) and for `player`; `backend_worker_retention_seconds`, how
  long the last retention run took; and `backend_backup_restore_seconds`, how long the last
  proof's restore and replay took (V33's `backup_run.seconds`).

## 10. Backup and recovery

| What | How | Target |
|---|---|---|
| Nightly full | `mysqldump --single-transaction` or a snapshot of the data directory | Restore tested monthly |
| Point-in-time | binlog shipping, `binlog_format=ROW`, retained 7 days | RPO ≈ 1 minute |
| Replica | asynchronous with GTIDs, on machine C, read-only | Promotion is a script an operator runs ([D-8](../architecture/03-decision-log.md#d-8--failover-is-scripted-not-automatic)), below |

**Binlog shipping is built** (2026-09-26): the binlogs are streamed to the backup
machine as they are written, 17 ms behind the primary when measured, and the
nightly dump is taken from there too, over TLS. A restore onto another server
from those copies alone was rehearsed and matched the primary table for table
([operations/01 §10](../operations/01-deploy.md#10-copies-off-the-databases-machine)).

**A backup that has never been restored is not a backup.** The monthly restore
drill is in the [runbook](../operations/02-runbook.md#the-restore-drill), and it
is the only way to discover that a dump has been silently failing for six
weeks. It was built and run on 2026-09-26, and its first three attempts failed
in exactly that silent way: a replay that applied nothing, a restore that wrote
itself into the binlog, and a position the script could not read. All three
are fixed, and a point-in-time restore now comes back row for row. The store has
its drill too, beside it: the all-time leaderboard exists only there.

### Proved, kept away and watched (designed 2026-10-04, plan item 76 (a))

On the owner's question (Q-51), "we should have also backup approaches?":
[D-71](../architecture/03-decision-log.md#d-71--backups-are-proved-by-restoring-them-every-week-under-live-writes-copied-off-the-site-encrypted-and-watched-through-mysql).
What was built kept the copies off the primary's machine and restored them by
hand once a month. Four things are added.

- **Recovery to a moment.** `restore-drill.sh` takes `STOP_AT`, a UTC time:
  the replay stops at the last transaction before it (`mysqlbinlog
  --stop-datetime`, read in UTC). That is the recovery from a mistake: an
  operator's wrong statement at 14:03 is undone by restoring to 14:02:59 and
  taking from the copy what the mistake removed (runbook, "Recovering to a
  moment"). Without it, the replay runs to the newest write, as before.
- **Proved weekly, automatically, under writes.** `backend-restore-proof.timer`
  on the backup machine, Sundays at 04:00 UTC, runs `restore-proof.sh`: the
  newest dump and the binlog copies onto the backup machine's scratch server, a
  MySQL server of its own (port 3309) that holds nothing else. Exact row counts
  hold only if nothing writes, so the proof checks what holds while the primary
  keeps writing:
  1. every table the source has is restored, and the newest migration is the
     source's (`flyway_schema_history`);
  2. nothing committed before the capture is missing: the source's highest
     `ledger` and `matches` ids, read before its binlog position, are in the
     copy;
  3. the ledger reconciles with every balance, **coins and gems** (the drill
     reconciled coins only, [O-13](../defects.md#6-operations));
  4. the recovery point, how far the copies were behind, and the restore's
     time are recorded.

  The monthly drill by hand stays, with its exact row counts, from the copy off
  the site when there is one.
- **Kept off the site.** `backup-offsite.sh`, run after each dump: the newest
  dump and every binlog copy since the oldest dump kept, each encrypted
  (`openssl enc -aes-256-cbc -pbkdf2`, a key in
  `/etc/backend/credentials/offsite.key` that is also kept away from the
  machines) into a local mirror. The mirror is pushed by `rsync --delete` over
  SSH to `OFFSITE_TARGET`, so the host off the site holds what the backup
  machine does: 8 days of dumps, and every binlog since the oldest. Unset, the
  copy is off and records nothing (Q-53). `fetch-offsite.sh` brings a copy back
  and decrypts it for a restore, and the drill restores from it.
- **Watched.** Each step records its outcome in `backup_run` (V32, `seconds` V33;
  its DDL in [§3](#operations)), through `scripts/record-backup.sh`: its kind (1
  dump, 2 proof, 3 off the site), when it started and finished, whether it worked,
  a line of `detail` (sizes, the recovery point, the restore's time, or why it
  failed), and for a proof the seconds its restore and replay took.

  The `backup` account may insert into it and nothing more. Every worker reports,
  as the ledger check's time is reported,
  `backend_backup_succeeded_timestamp_seconds{kind}`, when a run of the kind
  (`dump`, `proof`, `offsite`) last succeeded, and `backend_backup_failed{kind}`,
  1 when the last run of it failed. The alerts: no dump for 26 hours; no proof
  for 8 days, or the last failed; once a target is named, nothing copied off the
  site for 26 hours. Rows are kept 90 days, except each kind's newest success,
  which is kept whatever its age: a step failing for longer still shows when it
  last worked, rather than nothing.

The store is as it was (D-31): replicated, its boards rebuilt from MySQL, and
what only its disk holds copied out when it alerts.

**Physical copies, at the trigger** (designed 2026-10-04, plan item 76 (b),
D-72; **not built**: built when the weekly proof's restore passes 2 hours). A
logical dump is read back row by row, 24 000 a second on the development VM;
the ledger is never deleted, so a restore grows slower with every week. The
step after it copies files instead:

- **Nightly**, the backup machine's scratch server clones the primary
  (`CLONE INSTANCE FROM`, the plugin and the `clone` account the replica's
  rebuild uses, 06 §10 above, over TLS), at disk and network speed; it is then
  stopped, and its data directory archived, compressed and encrypted, beside
  where the dumps are now, kept as they are, 8 days.
- **The position:** `performance_schema.clone_status` names the binlog file and
  position the copy ends at; a restore starts the archived directory and
  replays the binlog copies from there, as a dump's restore does from its
  header (`--skip-gtids`, unlogged).
- **The proof** unpacks the newest archive onto the scratch server and replays:
  its time is the copy's size at disk speed, not the rows' count.
- **Off the site**, the archives go as the dumps do. The dumps stop once the
  first physical proof passes; until then both are taken.

### The replica and its promotion (built and drilled 2026-09-29, plan item 13)

The replica is on machine C, fed by MySQL's own asynchronous replication with
GTIDs (`gtid_mode=ON`, `enforce_gtid_consistency=ON`), and runs with
`super_read_only=ON`, so nothing but replication writes to it
([D-35](../architecture/03-decision-log.md#d-35--mysql-fails-over-as-the-stores-do-by-an-epoch-the-clients-follow)).

**An epoch, as the stores have** (j-redis D-37). A one-row table, `ha_epoch`,
holds a number that only a promotion raises, on the new primary, after it is
writable; replication carries it everywhere else. A backend process given both
hosts (`BACKEND_DB_URL` naming two, `jdbc:mysql://A:3306,C:3306/…`) opens each
new connection through a source that asks every host `SELECT @@global.read_only`
(on whenever `super_read_only` is) and `SELECT epoch FROM ha_epoch`, and connects
to the writable one with the highest epoch, never below the highest it has seen
on any host, a read-only one included. After a promotion the pool drops its
broken connections and the new ones reach the new primary; an old primary that
comes back writable, with the older epoch, is passed over by every process that
saw the promotion. With one host, the pool connects as before.

**Its lag, measured** (plan item 59,
[D-58](../architecture/03-decision-log.md#d-58--a-replica-is-measured-by-what-it-has-applied-a-heartbeat-for-mysql-the-primarys-own-account-for-the-stores),
[architecture/02 §7](../architecture/02-availability.md#7-a-replicas-health-measured-designed-2026-10-02-plan-item-59)). A second one-row table,
`ha_heartbeat` (§3), holds a time: every worker stamps it on the primary once a
second (`UPDATE ha_heartbeat SET at = NOW(6)`, through its pool), and replication
carries it. Every 5 s, on a thread of its own, each worker reads every listed host's
`@@global.read_only` and its row with the probe's limits; the newest stamp on a
writable host is the primary's, and each other host's lag is that less its own, both
written by the primary's clock. Reported as `backend_mysql_replica_lag_seconds{host}`
and `backend_mysql_replica_up{host}`, NaN and 0 for a host not read; with one host
there is nothing to report. `promote-mysql.sh` reads the same row to say what a
promotion would lose.

**Fencing the old primary drops the processes' connections to it.** The pool
drops only broken connections, and a server made read-only breaks none: after a
planned handover each process would have kept its connections to the old primary
for their lifetime, about half an hour, every write refused and every read stale
(defect [D-29](../defects.md), found by the drill). So the fence is two settings, not
one: `super_read_only`, persisted, which stops every write, the administrator's
included, restart or not; and `offline_mode`, which closes every connection but
an administrator's and refuses new ones. A busy connection's statement fails as
in a crash; an idle one fails the pool's check when next borrowed and is
replaced without a failed request; and a new one, refused, is chosen elsewhere.
`offline_mode` is set for as long as the server runs, not persisted: after a
restart no connection is left to close, a read-only server is never chosen, and
a persisted one would survive the server's rebuild (below) and close the next
primary it became.

**The promotion**, `promote-mysql.sh` on the replica's machine: told the old
primary is down, it refuses if the old one answers at all; told to demote it, it
fences it (`super_read_only` persisted, so a restart keeps it read-only). Then
it waits until the replica has applied every transaction it had received
(`WAIT_FOR_EXECUTED_GTID_SET`); stops replicating and forgets its source
(`RESET REPLICA ALL`); makes itself writable with `SET PERSIST`, which outranks
the configuration file's `super_read_only`, so a restart keeps it so; and raises
the epoch.

**The old primary never comes back as it was.** It may hold transactions that
never reached the replica, which GTIDs call errant and which would stop it
replicating, or worse, be replicated back. It returns as a new replica, rebuilt
from a copy of the new primary by MySQL's clone plugin: `CLONE INSTANCE`
replaces its data, its accounts and its GTID history with the new primary's,
errant transactions included in what is thrown away, and it then follows the
new primary by GTIDs from where the copy ends. Its own persisted settings stay,
the donor's do not travel: a fenced old primary comes back read-only, as a
replica should. Setting up the replica the first time is the same, onto an empty
server. It is started read-only from its configuration before anything else, so
that even before the copy it takes no writes. After a planned handover, handing
back is the same script the other way.

**The backups with GTIDs on** (defect [D-31](../defects.md)). The replica needs GTIDs on
the primary, and a binlog written with them names each transaction's GTID. A
point-in-time restore replays those binlogs onto a scratch server or a new one,
and with the GTIDs kept, the replay depends on where it lands: onto the source's
own server, every transaction is already executed there and is skipped without a
word; onto a server with GTIDs off, the replay is refused. So the replay drops
them (`mysqlbinlog --skip-gtids`): a restore is new writes on its target, logged
nowhere (`sql_log_bin = 0`), not the source's transactions. A server restored
this way starts a history of its own; a replica is made from it afresh, never
attached by GTIDs to what it was restored from. The dump already leaves them out
(`--set-gtid-purged=OFF`), and the binlog stream copies files whatever they hold.

**Where the copies are taken after a promotion.** The binlog stream and the
nightly dump run on the replica's machine, C, off the primary's (operations/01
§10). Once C holds the primary they are on the disk they protect, so they move
to the machine that holds the replica: A once it is rebuilt, B until then. The
new primary's binlogs are a series of their own, so they go into a directory of
their own, and a dump is taken from the new primary at once: it is the earliest
point the new series can restore to. A point before the promotion restores from
the old series, which stays where it was.

Recovery ordering matters: MySQL first, then j-redis, then the services.
Sessions and tickets in j-redis are disposable — players re-authenticate — but
a service that starts before the database is ready will fail its first requests
and may be restarted by systemd before it ever succeeds.

## 11. Read replicas (Phase 4)

Not needed at launch scale: the write rate is modest and the read rate is
absorbed by the j-redis cache-aside layer. When it becomes necessary:

- Route **only** queries that tolerate staleness — profile views, match
  history, leaderboard snapshots.
- **Never** route a read that a write depends on. Reading a balance from a
  replica and writing the result to the primary is how money is created.
- Replication lag is a metric with an alert, not a footnote.

## 12. What to measure

| Metric | Why | As built |
|---|---|---|
| Transaction duration p50/p99, by type | The three transactions in §4 are the ones that matter | not exported |
| Deadlock and lock-wait-timeout rate | Should be near zero; a rise means the lock-ordering rule is being violated somewhere | not exported: `Tx` retries both, three attempts in all, and logs a retry at debug |
| Pool: active, idle, wait time, timeouts | Wait time rising is the early warning; timeouts are the outage | not exported |
| Ledger reconciliation mismatches | Must be exactly zero. Anything else is a correctness bug. | `backend_worker_ledger_mismatches`, and `backend_worker_ledger_checked_timestamp_seconds` for when (05 §9) |
| Row counts of the tables that grow, and the retention run's time | Retention is working, or it is not; there are no partitions (§9) | `backend_mysql_table_rows{table}`, `backend_worker_retention_seconds` |
| Replication lag | Bounds how much a promotion loses, and how stale a replica read would be | `backend_mysql_replica_lag_seconds{host}`, `backend_mysql_replica_up{host}` (§10) |
