# persistence

The `persistence` module is the backend's system of record on MySQL 8.4
([06](../../docs/detailed-design/06-persistence-mysql.md)). It holds the schema and its migrations, the
connection pool, and every repository and transaction that the backend runs against the database. The
`platform` and `worker` processes depend on it. The `arena` never does: the arena's `pom.xml` does not list
this module, so arena code cannot open a database connection
([06 §1](../../docs/detailed-design/06-persistence-mysql.md#1-boundaries)). This page lists the module's
classes, the database and its tables, the numbers stored as codes, the ledger's rules, the idempotency keys,
the migration rules, the connection limits, the settings and the tests. Terms such as *ledger* and
*idempotency key* are defined in the [glossary](../../docs/glossary.md).

Three rules hold the module together:

1. **One path moves a balance.** `EconomyRepository.move` is the only code that changes a player's coins or
   gems, and it writes a ledger row in the same transaction.
2. **A retry changes nothing.** Every write that a caller can repeat has a unique key, so a second attempt
   does nothing. The key is a natural key or the ledger's `idem_key`. There is no separate table that
   remembers which requests were seen.
3. **Locks are taken in one order**: a team first, then players by ascending id, then what they own. So
   transactions wait in line instead of deadlocking. `Tx` retries the deadlocks and lock waits that still
   happen.

- [Classes](#classes)
- [The database](#the-database)
- [Tables](#tables)
- [Codes stored as numbers](#codes-stored-as-numbers)
- [The ledger's one path](#the-ledgers-one-path)
- [Idempotency keys](#idempotency-keys)
- [Migrations](#migrations)
- [Connections and limits](#connections-and-limits)
- [Configuration](#configuration)
- [Tests](#tests)
- [Build and test](#build-and-test)

## Classes

All classes are in the package `com.backend.persistence`.

| Domain | Class | What it does |
|---|---|---|
| Infrastructure | `Database` | The HikariCP connection pool and its limits ([below](#connections-and-limits)). `migrate()` runs Flyway on a connection of its own. `heartbeat()` stamps `ha_heartbeat` on the primary, and `replicaLags()` reads each replica's lag from it. `resetForTests()` drops everything and migrates again, for tests only |
| | `DatabaseSettings` | Reads where the database is and how to log in, from the environment only, never from the command line ([Configuration](#configuration)). Its `toString()` never prints the password or the URL's query string |
| | `PrimaryDataSource` | Used when the URL names two hosts, the primary and its replica. For each new connection it asks every host whether it is writable and at which `ha_epoch`. It connects to the writable host with the highest epoch, and refuses one whose epoch is below the highest it has seen. It also works out each replica's lag from `ha_heartbeat` |
| | `Tx` | Runs one transaction. On a deadlock (MySQL error 1213), a lock wait timeout (1205) or any `SQLTransactionRollbackException`, it rolls the whole transaction back and runs it again: three attempts in all, waiting 10 ms and then 20 ms between them, each wait plus up to 10 ms at random (jitter) |
| Accounts | `AccountRepository` | Registration: the account, its player and the player's `player_stat` row in one transaction. Guests: made, found by the hash of their key, upgraded to a named account. The login lookup; a rename, once in 30 days; a password rehash; the last login. `erase`: a player's account erased in place (D-84). `idleGuests`: the guests that retention erases (D-87) |
| | `AdminRepository` | The admin API's writes: an account's status, a payment's refund, a refund debt cleared. Each is written with its `admin_audit` row in one transaction. `audit` writes the row for a call that changed nothing: a listing or a refusal |
| Results and history | `MatchResultRepository` | The result's transaction ([06 §4](../../docs/detailed-design/06-persistence-mysql.md#4-the-three-transactions-that-matter)); `validate`, which refuses a result that no column can hold; the 30-day window in which a result is still accepted; the purge of `matches` older than 90 days |
| | `LeaderboardSource` | What the store's score boards are made of, read back from MySQL for a rebuild: the all-time bests from `player_stat`, and a day's or a week's bests from `matches` |
| | `StatsRepository` | The statistics for operators: activity by day and who came back, the same by feature, the funnel, the guests; the purge of `player_day` |
| | `TableSizes` | InnoDB's row estimates for `ledger`, `match_player`, `matches`, `player_day`, `inbox` and `player` |
| Economy | `EconomyRepository` | `move` (the one path), purchases in coins or gems, raising an item's level, credits, the wallet and the inventory, and the ledger's reconciliation |
| | `BoostRepository` | Activates a boost once per key; lists the boosts running; gives each player's boost percents at the moment a match ended; purges old boosts and keys |
| | `EquipmentRepository` | What a player wears, one item a slot. An item can be worn only while the player holds it. `takeOff` empties a slot |
| | `PaymentRepository` | Orders for gems: placed, then confirmed as paid or declined, refunded, or expired; a player's refund debt |
| | `Achievements`, `AchievementRepository` | Seventeen thresholds on `player_stat`, 430 gems in all; a player's counts |
| | `DailyGoals`, `DailyGoalRepository` | The table of goals, and a player's three goals for a day. The three are drawn from the player and the day each time, not stored. Progress on them is kept a week |
| | `SeasonPass`, `SeasonPassRepository` | Forty tiers of 250 points each, on a free track and a premium track. Points are earned in the result's transaction; premium costs 500 gems. Six seasons are kept |
| Boards and seasons | `RatingBoards` | The rating boards of the duel, team-vs-team and the ranked free-for-all. A player is listed after ten rated matches in the mode. It reads the top of a board and a player's place |
| | `TeamBoards` | The board of teams, read the same way as the player boards |
| | `SeasonRepository` | Seasons: when one ends (`endAfter`), its places, its "paid" stamp, its rating reset in batches, the teams' reset; the boards of past seasons |
| Teams, social, tournaments | `TeamRepository` | Teams: made, found, renamed, joined by invitation or application, left, members kicked, roles changed, handed over, disbanded; an erased player taken out of their team (D-87); purges of lapsed invitations and applications |
| | `FriendRepository` | Friends, requests and blocks. A change locks both players, the lower id first. It also purges lapsed requests |
| | `InboxRepository` | What a player learns on their return: each item is a kind and a reference, never text. Items are kept 30 days |
| | `TournamentRepository` | The tournaments' state machine; player and team entries and rosters; seeding; matches made and decided; results read; standings; purge |
| | `Bracket`, `RoundRobin` | A single-elimination bracket's size and seed order; a round robin's schedule |
| Operations | `BackupRunRepository` | Each backup step's last success and last outcome, for the workers' metrics; purge |

## The database

**Where the schema is.** The schema is one baseline under `src/main/resources/db/migration`, and Flyway runs
it:

| File | What it makes |
|---|---|
| `V1__schema.sql` | Every table, grouped by domain and commented: 36 of them, each `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci` |
| `V2__seed.sql` | The rows a first launch needs: `ha_epoch` (1, 0), `ha_heartbeat` (1, now) and season 1, from now to 00:00 UTC on the first of the next odd month |

These two files replaced V1 to V34, the steps by which the schema grew. Those steps are in git up to commit
ce30aa7. The baseline was compared with them table by table and found the same, with two differences on
purpose:

- V17's three board indexes are not made
  ([defect D-41](../../docs/defects.md#3-data-and-the-result-pipeline)).
- `season_place.rated` is `INT UNSIGNED`
  ([defect D-42](../../docs/defects.md#3-data-and-the-result-pipeline);
  [D-75](../../docs/architecture/03-decision-log.md#d-75--the-migrations-are-squashed-into-one-baseline-before-the-first-launch)).

[06 §3](../../docs/detailed-design/06-persistence-mysql.md#3-schema) describes each table, its keys and what
it serves, and [diagrams/05](../../docs/diagrams/05-data-and-worker.md) draws them.

**A first launch.** The deploy guide,
[operations/01 §9](../../docs/operations/01-deploy.md#9-mysql), has the steps, the settings, the certificate
and every account's grants. The passwords are never written in a document or a unit
([§2, Secrets](../../docs/operations/01-deploy.md#secrets)). In order:

1. Install MySQL 8.4 with the guide's settings: TLS required, the binary log in row format, and
   `default-time-zone = '+00:00'`, so the times that MySQL stamps itself are UTC too.
2. Create the database `backend` and its accounts. The `backend` account is for the processes. It may change
   the schema, because Flyway runs as it, but it may not drop anything. The `backup` and `binlog` accounts
   are for the backup machine.
3. Start the first `platform` or `worker`. It migrates: Flyway makes `flyway_schema_history`, runs
   `V1__schema.sql`, then `V2__seed.sql`. When several processes start at once, Flyway's lock in the database
   makes them take turns.
4. After that first start, give the grants that need a table to exist: `INSERT` on `backend.backup_run` to
   `backup`; `SELECT, UPDATE` on `ha_epoch` and `SELECT` on `ha_heartbeat` to the `failover` account on the
   replica's machine.
5. Make the replica by cloning the primary
   ([operations/01 §9, the replica](../../docs/operations/01-deploy.md#the-replica-on-c-plan-item-13-d-35)).

The site launches on one machine
([D-93](../../docs/architecture/03-decision-log.md#d-93--the-site-launches-on-one-machine-for-up-to-about-5-000-players)).
There, the install guide ([operations/03 §6](../../docs/operations/03-install-guide.md#6-mysql)) runs MySQL on
loopback without TLS, makes the same accounts on `127.0.0.1`, and gives the `backup_run` grant after the
platform's first start. It makes no replica: a second machine adds one
([operations/10](../../docs/operations/10-two-machines.md)).

Nothing else is seeded. Players make the accounts. The operator's access is the admin API's secret. The
content (items, shop, skins, packs, levels) is the release's files, not rows.

## Tables

The schema has thirty-six tables, listed here by domain. "Kept" says how long a row lives. The deletes are
`worker`'s daily retention ([06 §9](../../docs/detailed-design/06-persistence-mysql.md#9-growth-and-retention)).

| Table | Holds | Written by | Kept |
|---|---|---|---|
| `account` | A login: name, password hash or a guest's key hash, status | `AccountRepository`, `AdminRepository` | for good; an erased account keeps its row, under the name `~deleted~<id>` (D-84) |
| `player` | The account's player: profile, level and xp, coins and gems, three ratings and rated counts, team, the first day played | `AccountRepository`, `MatchResultRepository`, `EconomyRepository.move`, `TeamRepository`, `SeasonRepository` | for good |
| `player_stat` | Lifetime totals: matches, wins, kills, deaths, assists, best score, time played | `AccountRepository` (made), `MatchResultRepository` | for good |
| `player_day` | A player active on a UTC day | `MatchResultRepository` | 90 days; each player's first day stays on `player.first_played_on` |
| `ledger` | Every move of a balance, with its reason and key | `EconomyRepository.move` only | for good |
| `inventory_item` | What a player holds, and an equipment's level | `EconomyRepository`, `SeasonPassRepository`, `BoostRepository` | for good |
| `equipment` | What a player wears, a slot each | `EquipmentRepository` | for good |
| `boost` | A boost's run | `BoostRepository` | 31 days after its end |
| `boost_activation` | A key used to activate a boost | `BoostRepository` | 30 days |
| `payment_order` | An order for gems and its state | `PaymentRepository`, `AdminRepository` | for good; one pending for more than a day is marked expired |
| `matches` | A match, or one player's stay in the open arena | `MatchResultRepository` | 90 days |
| `match_player` | Each player's part of a match | `MatchResultRepository` | 90 days, with its match |
| `team` | A team: name, members' count, rating and record | `TeamRepository`, `MatchResultRepository`, `SeasonRepository` | until disbanded |
| `team_member` | Who is in a team, and their role | `TeamRepository` | while a member |
| `team_invite` | A team's invitation to a player | `TeamRepository` | until answered or lapsed |
| `team_application` | A player's request to join a team | `TeamRepository` | until lapsed |
| `match_team` | A team's part of a team match | `MatchResultRepository` | 90 days, with its match |
| `tournament` | A tournament and its state | `TournamentRepository` | deleted 90 days after its start, if it has finished or been cancelled |
| `tournament_entry` | A player's entry and seed | `TournamentRepository` | with its tournament |
| `tournament_match` | The bracket, or a round robin's pairings | `TournamentRepository` | with its tournament |
| `tournament_team_entry` | A team's entry and seed | `TournamentRepository` | with its tournament |
| `tournament_roster` | The players a team entered with | `TournamentRepository` | with its tournament |
| `season` | A season of the boards, and how far its close has got | `SeasonRepository` | for good |
| `season_place` | A past season's places on each player board | `SeasonRepository` | for good |
| `season_team_place` | A past season's places on the board of teams | `SeasonRepository` | for good |
| `season_team_payee` | Whom a team's place paid | `SeasonRepository` | for good |
| `season_pass` | A player's pass points and the tiers paid in a season | `SeasonPassRepository` | the season being played and the five before it |
| `daily_goal` | A player's progress on a day's goals | `MatchResultRepository` | 7 days |
| `friend` | A friendship, one row each way | `FriendRepository` | for good |
| `friend_request` | A request, which lapses after seven days | `FriendRepository` | until answered or lapsed |
| `block` | A player another has blocked | `FriendRepository` | for good |
| `inbox` | An item for a player to see on return | `InboxRepository`, `TeamRepository`, `worker` | 30 days |
| `admin_audit` | Every admin call, allowed or refused | `AdminRepository` | for good |
| `ha_epoch` | The failover epoch, one row | the seed; `promote-mysql.sh` | one row |
| `ha_heartbeat` | The replica heartbeat, one row | the seed; `Database.heartbeat` | one row |
| `backup_run` | What each backup step did | `scripts/record-backup.sh` | 90 days, but each kind's newest success always |

## Codes stored as numbers

Each column below is `TINYINT UNSIGNED`, and its values are constants in Java. When this table and the code
disagree, the constant in the code is right.

| Column | Values | Defined in |
|---|---|---|
| `account.status` | 0 active; 1 suspended until `banned_until` (null: until lifted); 2 banned; 3 deleted, an account erased (D-84, D-87). Any other value is read as banned | `AccountRepository.Status` |
| `ledger.currency` | 0 coins, 1 gems | `EconomyRepository.CURRENCY_*` |
| `ledger.reason` | 0 match reward, 1 purchase (the pass's premium among them), 2 refund (nothing writes it yet), 3 tournament prize, 4 item level, 5 milestone, 6 season, 7 achievement, 8 daily goal, 9 payment, 10 payment refund, 11 pass tier | `EconomyRepository.REASON_*` |
| `matches.mode`, `tournament.mode` | 0 ffa, 1 duel, 2 tvt, 3 rffa, 4 coop, 5 teams, 6 domination, 7 tag, 8 maze, 9 sandbox (which publishes no result); a tournament is 1 or 5 | `handoff/MatchMode`; `MatchResultRepository.MODE_*` |
| `matches.kind` | 0 a stay in the open arena, 1 a timed match | `handoff/MatchOutcome.KIND_*` |
| `match_player.team`, `match_team.side` | the side, 1 or 2, in a mode with teams | the arena's tickets |
| `match_team.placement` | 1 won or drew, 2 lost | `MatchResultRepository` |
| `equipment.slot` | 0 barrel, 1 armor, 2 core, 3 treads, 4 skin | `platform/Items` |
| `boost.kind` | 0 experience, 1 coins | `BoostRepository.KINDS`, `worker/RewardRules` |
| `team_member.role` | 0 member, 1 vice leader, 2 leader | `TeamRepository` |
| `tournament.state` | 0 registration, 1 seeded, 2 running, 3 finished, 4 cancelled | `TournamentRepository` |
| `tournament.format` | 0 single elimination, 1 round robin | `TournamentRepository` |
| `tournament_match.state` | 0 pending, 1 ready, 2 done | `TournamentRepository` |
| `payment_order.state` | 0 pending, 1 paid, 2 declined, 3 refunded, 4 expired | `PaymentRepository` |
| `inbox.kind` | 1 friend request, 2 accepted, 3 team invitation, 4 tournament prize, 5 team application, 6 season reward | `InboxRepository` |
| `inbox.ref` | the other player's, the team's or the tournament's id; for a season reward, the season × 10 + the board | `InboxRepository` |
| `season_place.board` | the mode's id: 1 duel, 2 tvt, 3 rffa; the board of teams is 5 in ledger keys and inbox references | `RatingBoards.Board`, `worker/SeasonKeeper` |
| `backup_run.kind` | 1 dump, 2 restore proof, 3 copy off the site | `BackupRunRepository`, `scripts/record-backup.sh` |

## The ledger's one path

`EconomyRepository.move(connection, player, currency, delta, reason, ref, key)` runs in the caller's
transaction (D-13). `moveCoins` does the same for coins. It takes five steps:

1. `SELECT coins|gems FROM player WHERE id = ? FOR UPDATE`. Every writer of a balance takes this lock first,
   so the lock puts everything below in order, one transaction at a time.
2. `SELECT 1 FROM ledger WHERE idem_key = ?`, a plain read. If the key was used already, the answer is
   `ALREADY_APPLIED`, before the funds are checked. The read does not lock: a locking read of a key that does
   not exist locks a gap, and two such reads followed by inserts deadlock.
3. If the balance would go below zero, the answer is `INSUFFICIENT_FUNDS`, and nothing is written.
4. `INSERT INTO ledger (…, balance_after, reason, ref, idem_key)`. A duplicate key, error 1062 and no other,
   also answers `ALREADY_APPLIED`. This is the backstop.
5. `UPDATE player SET coins|gems = … + delta`.

`player` has `CHECK (coins >= 0 AND gems >= 0)`. A bug that would overdraw a balance therefore makes the
transaction fail instead. `reconcile` finds every player whose balance in either currency is not the sum of
their ledger rows. It reads a range of 10 000 player ids at a time, each range in its own statement.
`worker`'s daily ledger check runs it.

## Idempotency keys

An idempotency key makes a second attempt of a write do nothing. Two kinds are used.

**Natural keys**, the unique keys of the rows themselves:

- `matches.match_uid`;
- `match_player (match_id, player_id)`, which is the result's duplicate check;
- `match_team (match_id, side)`;
- `player_day (day, player_id)`;
- `inbox (player_id, kind, ref)`;
- `payment_order (player_id, client_key)`;
- the tournament tables' entries;
- `boost_activation.idem_key`, `act:{player}:{clientKey}`.

**The ledger's `idem_key`**, 80 characters at most. A key the client sends is 48 characters at most.

| Key | Moves |
|---|---|
| `match:{matchUid}:{player}` | a result's coins (reason 0) |
| `buy:{player}:{clientKey}` | a purchase, coins or gems (1) |
| `pass:{season}:{player}` | the pass's premium, 500 gems (1) |
| `tourney:{tournament}:{place}:{player}`, and the same with `:gems` | a tournament place's coins, and its gems (3) |
| `level:{player}:{clientKey}` | an item's next level (4) |
| `milestone:{player}:{level}` | an account level's gems (5) |
| `season:{season}:{board}:{player}` | a season place's gems (6) |
| `achievement:{player}:{achievementId}` | an achievement's gems (7) |
| `daily:{player}:{yyyy-mm-dd}:{goalId}`, `daily:{player}:{yyyy-mm-dd}:set` | a goal's coins; the gems for meeting all three of the day's goals (8) |
| `payment:{orderId}`, `payment:first:{player}` | a paid order's gems; for a player's first paid order, as many gems again (9) |
| `refund:{orderId}` | a refund's gems taken back (10) |
| `pass:{season}:{track}:{tier}:{player}`, track `free` or `premium` | a pass tier's coins or gems (11) |

## Migrations

From V3 on, migrations follow the rules of
[06 §8](../../docs/detailed-design/06-persistence-mysql.md#8-migrations):

- **Forward-only.** Nobody edits a migration once it has been applied. The next migration fixes a mistake.
  Flyway refuses a migration whose checksum changed, and the process does not start.
- **Expand, then contract.** A breaking change takes three releases: add the new column or table, switch the
  code to it, drop the old one. Every state in between is one that the running code understands, because an
  old and a new process run side by side during a rolling deploy.
- **A migration only adds.** A backfill is a `worker` task or a tool that runs after the deploy, once every
  process that writes the table writes the new column. It works in batches of a thousand rows by primary key,
  each batch its own transaction, and it is safe to run twice.
- **Migrations run at every `platform` and `worker` start**, on a connection of their own without the socket
  limit. A release therefore needs no separate migration step.

## Connections and limits

`Database` sets these limits ([06 §7](../../docs/detailed-design/06-persistence-mysql.md#7-connection-pooling)):

| Setting | Value |
|---|---|
| Pool size | `BACKEND_DB_POOL_SIZE`, else the process's own default: 16 for `platform`, 8 for `worker`, 2 for the leaderboard rebuild |
| Minimum idle | 4 connections, or the pool's size if that is smaller |
| Waiting for a connection | 3 s, then an error |
| A connection's life | 1 700 s, shorter than MySQL's `wait_timeout` |
| Connect, socket | 2 s to connect, 30 s on the socket; the migrations' connection has no socket limit |
| Login | 3 s for the server's greeting, TLS and the account check. This is the driver's login timeout: with one host, HikariCP sets it for the whole JVM from its 3 s wait. With two hosts nothing sets it, and a login has the socket's 30 s ([D-94](../../docs/architecture/03-decision-log.md#d-94--a-start-waits-for-its-database-in-the-same-process-for-up-to-a-minute)) |
| A start | `Database` tries the pool's first connection again after each failure, for up to a minute. Each failure is logged as a warning. After the minute the start fails, and the process exits 1 ([D-94](../../docs/architecture/03-decision-log.md#d-94--a-start-waits-for-its-database-in-the-same-process-for-up-to-a-minute)) |
| Lock wait | `innodb_lock_wait_timeout = 20` on every session, so a lock wait ends as error 1205, which `Tx` retries, before the socket gives up |
| Time zone | `connectionTimeZone=+00:00`, forced onto the session too: what the driver binds and what MySQL stamps itself are both UTC |
| Prepared statements | server-side; 256 cached on each connection, each up to 2 048 characters of SQL |
| Two hosts in the URL | `PrimaryDataSource`: each new connection probes every host, with a 2 s limit to connect and to read, and goes to the writable one with the highest epoch. A host that does not answer is passed over; a port not given is 3306 |

## Configuration

`DatabaseSettings` reads these from the environment. It never reads them from the command line, where every
local user could read them with `ps`. On a server, each process's settings file, `/etc/backend/<process>.env`,
sets them; every such file is `root:root 0600`
([D-102](../../docs/architecture/03-decision-log.md#d-102--a-processs-secrets-are-in-its-own-settings-file-as-plain-variables-the-units-carry-none)).

| Variable | Default | Meaning |
|---|---|---|
| `BACKEND_DB_URL` | `jdbc:mysql://127.0.0.1:3314/backend_dev?useSSL=false&allowPublicKeyRetrieval=true`, the development MySQL (O-40) | The JDBC URL. Two hosts, `host:port,host:port`, name the primary and its replica |
| `BACKEND_DB_USER` | `backend` | The user |
| `BACKEND_DB_PASSWORD` | the development password; `platform` and `worker` then log a warning | The password. On a server it is a plain variable in the process's settings file (D-102) |
| `BACKEND_DB_PASSWORD_FILE` | — | A file that holds the password, as a container's secrets provide one. It wins over `BACKEND_DB_PASSWORD`, and one trailing line ending is removed. A file that is named but cannot be read, or is empty, stops the process (exit 2) |
| `BACKEND_DB_POOL_SIZE` | the process's own (16, 8 or 2, [above](#connections-and-limits)) | 1 to 100, which stays under MySQL's default `max_connections` of 151. Any other value refuses the configuration: exit 2 |

The tests read `JDBC_URL`, `DB_USER` and `DB_PASSWORD`. Their defaults are
`jdbc:mysql://127.0.0.1:3314/backend_test?useSSL=false&allowPublicKeyRetrieval=true` (`DatabaseSettings.TEST_URL`),
`backend` and `backend-dev-password`: the development MySQL's `backend_test`. `dev-env.sh`, `build-offline.sh`
and VS Code's settings name the same database (D-80, O-40).

## Tests

The tests run against a real MySQL 8.4, never a fake. What matters here is transactional: the locking order,
a plain insert's duplicate key as the idempotency check, and the `CHECK` as the last defence. The tests need
the database `backend_test` and the user `backend` / `backend-dev-password`. The development MySQL has both
(`./dev-mysql.sh start`, [backend README, MySQL](../README.md#mysql)).

**Every database test drops everything in `backend_test` and migrates it afresh** (`Database.resetForTests`).
Never point `JDBC_URL` at a database that matters. The development database, `backend_dev`, is the default of
the processes and of the drills.

| Class | What it holds |
|---|---|
| `PersistenceTest` | The result's transaction end to end: the ratings of each mode, team matches and their teams, results cut short, names, assists, levels and milestones, achievements, daily goals, the pass's points; redelivery and concurrency; retention and the purges it refuses; purchases, item levels, gems; the ledger and its reconciliation; the `CHECK` |
| `AccountRepositoryTest` | Registration, a name taken in any case, registrations at the same moment, guests, renaming, login; an account erased, a guest's too, and no status set on an erased one (D-84); the idle guests listed (D-87) |
| `BackupRunRepositoryTest`, `TableSizesTest` | The backups' latest outcomes and their purge; the tables' row estimates |
| `BoostRepositoryTest`, `EquipmentRepositoryTest`, `PaymentRepositoryTest`, `SeasonPassRepositoryTest` | Each economy repository: once per key, and under concurrency where it matters; a refund written with its `admin_audit` row |
| `SeasonRepositoryTest`, `RatingBoardsTest`, `TeamBoardsTest` | Season ends, places, gems, resets; the boards' order and a place read by key |
| `TeamRepositoryTest`, `FriendRepositoryTest`, `InboxRepositoryTest`, `TournamentRepositoryTest`, `StatsRepositoryTest` | The rest of the repositories, each with its purge; an erased player taken out of their team, and a leader's team handed over (D-87) |
| `PrimaryDataSourceTest`, `DatabaseLimitsTest`, `StoredTimesTest` | The choice of the primary, and the heartbeat; the time limits (a slow migration from `src/test/resources/db/slow` is not cut off), and a start that waits for a first connection slower than the login's limit and gives up at the end of its wait (D-94); times stored as UTC whatever the process's zone |
| `DatabaseSettingsTest`, `BracketTest`, `RoundRobinTest`, `DailyGoalsTest` | No database needed: the settings, the bracket, the schedule, the draw of the goals |

## Build and test

From `backend/`, offline:

```bash
. ../dev-env.sh                                  # vendor/'s JDK and Maven, the bundle, work/ (D-80)
mvn -pl persistence -am install                  # this module, and common
mvn -pl persistence test -Dtest=PersistenceTest  # one class
mvn install                                      # everything
```

The benchmarks that measure this module's statements are in [tools](../tools/README.md). Each refuses any
database whose name does not end in `_test`. They are `ApplyBenchmark` (results a second, by number of
threads), `PurgeBenchmark` (retention's purge) and `RankBenchmark` (a player's place on a board).
