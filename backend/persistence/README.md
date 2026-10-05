# persistence

The system of record on MySQL 8.0
([06](../../docs/detailed-design/06-persistence-mysql.md)): the schema and its
migrations, the connection pool, and every repository and transaction the backend
runs against the database. `platform` and `worker` depend on it; `arena` never does,
and a module graph keeps it so ([06 §1](../../docs/detailed-design/06-persistence-mysql.md#1-boundaries)).

Three things hold it together. **One path moves a balance**,
`EconomyRepository.move`, writing a ledger row in the same transaction. **A retry
changes nothing**: every write that can be repeated has a unique key that makes the
second attempt a no-op, a natural key or the ledger's `idem_key`, never a dedupe
table. **Locks are taken in one order**, a team, then players by ascending id, then
what they own, so transactions queue rather than deadlock; `Tx` retries the deadlocks
and lock waits that remain.

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

Package `com.backend.persistence`.

| Domain | Class | What it does |
|---|---|---|
| Infrastructure | `Database` | The HikariCP pool and its limits; `migrate()` (Flyway, on a connection of its own); `heartbeat()` and `replicaLags()` for the replica's lag; `resetForTests()` |
| | `DatabaseSettings` | Where the database is and how to log in, from the environment only, never the command line |
| | `PrimaryDataSource` | Given two hosts: each new connection to the writable one with the highest `ha_epoch`, never below the highest seen; each replica's lag from `ha_heartbeat` |
| | `Tx` | Runs one transaction; rolls it back and retries on deadlock (1213) and lock wait timeout (1205), three attempts in all, with jittered backoff |
| Accounts | `AccountRepository` | Registration (the account, its player and the player's `player_stat` row in one transaction); guests, made, found by their key's hash, upgraded; login lookup; renaming once in 30 days; password rehash; last login |
| | `AdminRepository` | An account's status, a payment's refund, a refund debt cleared: each written with its `admin_audit` row in one transaction |
| Results and history | `MatchResultRepository` | The result's transaction ([06 §4](../../docs/detailed-design/06-persistence-mysql.md#4-the-three-transactions-that-matter)); `validate`; the 30-day acceptance window; the 90-day purge of `matches` |
| | `LeaderboardSource` | What the store's boards are made of, read back: all-time bests from `player_stat`, a day's or week's from `matches` |
| | `StatsRepository` | Activity by day and its returns, the same by feature, the funnel, guests; the `player_day` purge |
| | `TableSizes` | InnoDB's row estimates for `ledger`, `match_player`, `matches`, `player_day`, `inbox` and `player` |
| Economy | `EconomyRepository` | `move` (the one path), purchases in coins or gems, an item's level, credits, the wallet and inventory, the ledger's reconciliation |
| | `BoostRepository` | Activating a boost once a key; those running; each player's percents when a match ended; purge |
| | `EquipmentRepository` | What a player wears, a slot each, and wearing only what is held |
| | `PaymentRepository` | Orders for gems: placed, confirmed or declined, refunded, expired; a player's refund debt |
| | `Achievements`, `AchievementRepository` | Seventeen thresholds on `player_stat`, 430 gems in all; a player's counts |
| | `DailyGoals`, `DailyGoalRepository` | The goals' table and a player's three a day, drawn, not stored; progress kept a week |
| | `SeasonPass`, `SeasonPassRepository` | Forty tiers of 250 points on a free and a premium track; points earned in the result's transaction, premium bought for 500 gems; six seasons kept |
| Boards and seasons | `RatingBoards` | The rating boards of the duel, team-vs-team and the ranked free-for-all, listed after ten rated matches: the top, and a player's place |
| | `TeamBoards` | The board of teams, read as the player boards are |
| | `SeasonRepository` | Seasons: when one ends (`endAfter`), its places, its payment stamp, its reset in batches, the teams' reset; past seasons' boards |
| Teams, social, tournaments | `TeamRepository` | Teams: made, found, renamed, joined by invitation or application, left, kicked, roles, handed over, disbanded; purges of lapsed invitations and applications |
| | `FriendRepository` | Friends, requests and blocks, both players locked lowest id first; the purge of lapsed requests |
| | `InboxRepository` | What a player learns on return, an item a kind and a reference; 30 days kept |
| | `TournamentRepository` | Tournaments' state machine, player and team entries and rosters, seeding, matches made and decided, results read, standings, purge |
| | `Bracket`, `RoundRobin` | A single elimination's size and seed order; a round robin's schedule |
| Operations | `BackupRunRepository` | Each backup step's last success and last outcome, for the workers' metrics; purge |

## The database

**Where it is.** The schema is one baseline under
`src/main/resources/db/migration`, run by Flyway:

| File | What it makes |
|---|---|
| `V1__schema.sql` | Every table, grouped by domain and commented: 36 of them, each `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci` |
| `V2__seed.sql` | The rows a first launch needs: `ha_epoch` (1, 0), `ha_heartbeat` (1, now) and season 1, from now to 00:00 UTC on the first of the next odd month |

They replaced V1 to V34, the steps the schema grew by, which are in git to commit
ce30aa7, and were proved the same table by table, with two differences on purpose:
V17's three board indexes are not made ([defect D-41](../../docs/defects.md#3-data-and-the-result-pipeline)),
and `season_place.rated` is `INT UNSIGNED`
([defect D-42](../../docs/defects.md#3-data-and-the-result-pipeline);
[D-75](../../docs/architecture/03-decision-log.md#d-75--the-migrations-are-squashed-into-one-baseline-before-the-first-launch)).
Each table, its keys and what it serves are in
[06 §3](../../docs/detailed-design/06-persistence-mysql.md#3-schema), and drawn in
[diagrams/05](../../docs/diagrams/05-data-and-worker.md).

**A first launch.** The steps, the settings, the certificate and every account's
grants are in the deploy guide,
[operations/01 §9](../../docs/operations/01-deploy.md#9-mysql); the passwords are
never written in a document or a unit ([§2, Secrets](../../docs/operations/01-deploy.md#secrets)).
In order:

1. MySQL 8.0 installed, with the guide's settings: TLS required, the binary log in
   rows, and `default-time-zone = '+00:00'`, so what MySQL stamps itself is UTC too.
2. The database `backend` created, and its accounts: `backend` for the processes,
   which may change the schema, since Flyway runs as it, and may drop nothing;
   `backup` and `binlog` for the backup machine.
3. The first `platform` or `worker` to start migrates: Flyway makes
   `flyway_schema_history`, runs `V1__schema.sql`, then `V2__seed.sql`. Starts that
   race are serialised by Flyway's lock in the database.
4. The grants that need a table to exist, after that first start: `INSERT` on
   `backend.backup_run` to `backup`; `SELECT, UPDATE` on `ha_epoch` and `SELECT` on
   `ha_heartbeat` to the `failover` account on the replica's machine.
5. The replica, made by clone from the primary
   ([operations/01 §9, the replica](../../docs/operations/01-deploy.md#the-replica-on-c-plan-item-13-d-35)).

Nothing else is seeded: accounts are made by players, the operator's access is the
admin API's secret, and the content (items, shop, skins, packs, levels) is the
release's files, not rows.

## Tables

Thirty-six, by domain. "Kept" is how long a row lives; the deletes are `worker`'s
daily retention ([06 §9](../../docs/detailed-design/06-persistence-mysql.md#9-growth-and-retention)).

| Table | Holds | Written by | Kept |
|---|---|---|---|
| `account` | A login: name, password hash or a guest's key hash, status | `AccountRepository`, `AdminRepository` | for good |
| `player` | The account's player: profile, level and xp, coins and gems, three ratings and rated counts, team | `AccountRepository`, `MatchResultRepository`, `EconomyRepository.move`, `TeamRepository`, `SeasonRepository` | for good |
| `player_stat` | Lifetime totals: matches, wins, kills, deaths, assists, best score, time played | `AccountRepository` (made), `MatchResultRepository` | for good |
| `player_day` | A player active on a UTC day | `MatchResultRepository` | 90 days |
| `ledger` | Every move of a balance, with its reason and key | `EconomyRepository.move` only | for good |
| `inventory_item` | What a player holds, and an equipment's level | `EconomyRepository`, `SeasonPassRepository`, `BoostRepository` | for good |
| `equipment` | What a player wears, a slot each | `EquipmentRepository` | for good |
| `boost` | A boost's run | `BoostRepository` | 31 days after its end |
| `boost_activation` | A key used to activate a boost | `BoostRepository` | 30 days |
| `payment_order` | An order for gems and its state | `PaymentRepository`, `AdminRepository` | for good; pending past a day, expired |
| `matches` | A match, or one player's stay in the open arena | `MatchResultRepository` | 90 days |
| `match_player` | Each player's part of a match | `MatchResultRepository` | 90 days, with its match |
| `team` | A team: name, members' count, rating and record | `TeamRepository`, `MatchResultRepository`, `SeasonRepository` | until disbanded |
| `team_member` | Who is in a team, and their role | `TeamRepository` | while a member |
| `team_invite` | A team's invitation to a player | `TeamRepository` | until answered or lapsed |
| `team_application` | A player's request to join a team | `TeamRepository` | until lapsed |
| `match_team` | A team's part of a team match | `MatchResultRepository` | 90 days, with its match |
| `tournament` | A tournament and its state | `TournamentRepository` | 90 days after its start, once finished or cancelled |
| `tournament_entry` | A player's entry and seed | `TournamentRepository` | with its tournament |
| `tournament_match` | The bracket, or a round robin's pairings | `TournamentRepository` | with its tournament |
| `tournament_team_entry` | A team's entry and seed | `TournamentRepository` | with its tournament |
| `tournament_roster` | The players a team entered with | `TournamentRepository` | with its tournament |
| `season` | A season of the boards and its close's progress | `SeasonRepository` | for good |
| `season_place` | A past season's places on each player board | `SeasonRepository` | for good |
| `season_team_place` | A past season's places on the board of teams | `SeasonRepository` | for good |
| `season_team_payee` | Whom a team's place paid | `SeasonRepository` | for good |
| `season_pass` | A player's pass points and tiers paid in a season | `SeasonPassRepository` | the season being played and five before |
| `daily_goal` | A player's progress on a day's goals | `MatchResultRepository` | 7 days |
| `friend` | A friendship, one row each way | `FriendRepository` | for good |
| `friend_request` | A request, seven days | `FriendRepository` | until answered or lapsed |
| `block` | A player another has blocked | `FriendRepository` | for good |
| `inbox` | An item for a player to see on return | `InboxRepository`, `TeamRepository`, `worker` | 30 days |
| `admin_audit` | Every admin call, allowed or refused | `AdminRepository` | for good |
| `ha_epoch` | The failover epoch, one row | the seed; `promote-mysql.sh` | one row |
| `ha_heartbeat` | The replica heartbeat, one row | the seed; `Database.heartbeat` | one row |
| `backup_run` | What each backup step did | `scripts/record-backup.sh` | 90 days, but each kind's newest success always |

## Codes stored as numbers

Each column is `TINYINT UNSIGNED` with its constants in Java; the constant is the
source.

| Column | Values | Defined in |
|---|---|---|
| `account.status` | 0 active, 1 suspended until `banned_until` (null: until lifted), 2 banned; any other value read as banned | `AccountRepository.Status` |
| `ledger.currency` | 0 coins, 1 gems | `EconomyRepository.CURRENCY_*` |
| `ledger.reason` | 0 match reward, 1 purchase (the pass's premium among them), 2 refund (written by nothing yet), 3 tournament prize, 4 item level, 5 milestone, 6 season, 7 achievement, 8 daily goal, 9 payment, 10 payment refund, 11 pass tier | `EconomyRepository.REASON_*` |
| `matches.mode`, `tournament.mode` | 0 ffa, 1 duel, 2 tvt, 3 rffa, 4 coop, 5 teams, 6 domination, 7 tag, 8 maze, 9 sandbox (publishes no result); a tournament is 1 or 5 | `handoff/MatchMode`; `MatchResultRepository.MODE_*` |
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

`EconomyRepository.move(connection, player, currency, delta, reason, ref, key)`, in
the caller's transaction (D-13); `moveCoins` is the same in coins:

1. `SELECT coins|gems FROM player WHERE id = ? FOR UPDATE`: every writer of a
   balance takes this lock first, so everything below is serialised by it.
2. `SELECT 1 FROM ledger WHERE idem_key = ?`, a plain read: used already, and the
   answer is `ALREADY_APPLIED`, before the funds are looked at. A locking read of an
   absent key would lock a gap, and two such reads then inserting deadlock.
3. Below zero after the move: `INSUFFICIENT_FUNDS`, nothing written.
4. `INSERT INTO ledger (…, balance_after, reason, ref, idem_key)`: a duplicate key,
   1062 and nothing else, is `ALREADY_APPLIED`, the backstop.
5. `UPDATE player SET coins|gems = … + delta`.

`player`'s `CHECK (coins >= 0 AND gems >= 0)` turns a bug that would overdraw into
a failed transaction. `reconcile` finds every player whose balance in either currency
is not the sum of their ledger rows, a range of players at a time, each range its
own statement; `worker`'s daily check runs it.

## Idempotency keys

The natural keys: `matches.match_uid`; `match_player (match_id, player_id)`, the
result's duplicate check; `match_team (match_id, side)`; `player_day (day,
player_id)`; `inbox (player_id, kind, ref)`; `payment_order (player_id,
client_key)`; the tournament tables' entries; `boost_activation.idem_key`,
`act:{player}:{clientKey}`.

The ledger's `idem_key`, 80 characters at most; a client's key is at most 48:

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
| `daily:{player}:{yyyy-mm-dd}:{goalId}`, `daily:{player}:{yyyy-mm-dd}:set` | a goal's coins; the day's three's gems (8) |
| `payment:{orderId}`, `payment:first:{player}` | a paid order's gems; a player's first paid order's as many again (9) |
| `refund:{orderId}` | a refund's gems taken back (10) |
| `pass:{season}:{track}:{tier}:{player}`, track `free` or `premium` | a pass tier's coins or gems (11) |

## Migrations

From V3 on, as [06 §8](../../docs/detailed-design/06-persistence-mysql.md#8-migrations)
rules:

- **Forward-only.** An applied migration is never edited; a mistake is fixed by the
  next one. Flyway refuses a changed checksum and the process does not start.
- **Expand, then contract.** A breaking change is three releases: add the new
  column or table, switch the code, drop the old. Every state between is one the
  running code understands, since an old and a new process run side by side during
  a rolling deploy.
- **A migration only adds.** A backfill is a `worker` task or a tool, after the
  deploy, in batches of a thousand rows by primary key, each its own transaction and
  safe to run twice, once every process that writes the table writes the new column.
- **They run at every `platform` and `worker` start**, on a connection of their own
  without the socket limit, so a release needs no separate step.

## Connections and limits

`Database` ([06 §7](../../docs/detailed-design/06-persistence-mysql.md#7-connection-pooling)):

| Setting | Value |
|---|---|
| Pool size | `BACKEND_DB_POOL_SIZE`, else the process's own: 16 `platform`, 8 `worker`, 2 the leaderboard rebuild |
| Minimum idle | 4, or the pool's size if smaller |
| Waiting for a connection | 3 s, then an error |
| A connection's life | 1 700 s, under MySQL's `wait_timeout` |
| Connect, socket | 2 s, 30 s; migrations have no socket limit |
| Lock wait | `innodb_lock_wait_timeout = 20` on every session, so a wait ends as 1205, which `Tx` retries, before the socket gives up |
| Time zone | `connectionTimeZone=+00:00`, forced onto the session: what the driver binds and what MySQL stamps are both UTC |
| Prepared statements | server-side, 256 cached a connection |
| Two hosts in the URL | `PrimaryDataSource`: each new connection probes every host (2 s) and goes to the writable one with the highest epoch |

## Configuration

Read from the environment by `DatabaseSettings`, never from the command line, where
every local user could read it with `ps`:

| Variable | Default | Meaning |
|---|---|---|
| `BACKEND_DB_URL` | `jdbc:mysql://127.0.0.1:3306/backend_dev?useSSL=false&allowPublicKeyRetrieval=true` | The JDBC URL; two hosts, `host:port,host:port`, for the primary and its replica |
| `BACKEND_DB_USER` | `backend` | The user |
| `BACKEND_DB_PASSWORD_FILE` | — | **Preferred**: a file holding the password, one trailing line ending ignored. Named and unreadable or empty stops the process |
| `BACKEND_DB_PASSWORD` | the development password, with a warning | The password, when no file is named |
| `BACKEND_DB_POOL_SIZE` | the process's own | 1 to 100; anything else refuses the configuration, exit 2 |

The tests read `JDBC_URL`, `DB_USER` and `DB_PASSWORD`, defaulting to
`jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true`,
`backend` and `backend-dev-password`.

## Tests

The tests run against a real MySQL 8.0, never a fake: what matters here is
transactional, the locking order, a plain insert's duplicate key as the
idempotency check, the `CHECK` as the last defence. They need the database
`backend_test` and the user `backend` / `backend-dev-password` on 127.0.0.1:3306
([backend README, MySQL](../README.md#mysql)). **Every database test drops
everything in `backend_test` and migrates it afresh** (`Database.resetForTests`), so
never point `JDBC_URL` at a database that matters. The development database,
`backend_dev`, is the processes' default and the drills'.

| Class | What it holds |
|---|---|
| `PersistenceTest` | The result's transaction end to end: ratings of each mode, team matches and their teams, cut-short results, names, assists, levels and milestones, achievements, daily goals, the pass's points; redelivery and concurrency; retention's refusals; purchases, item levels, gems; the ledger and its reconciliation; the `CHECK` |
| `AccountRepositoryTest` | Registration, a taken name in any case, simultaneous registrations, guests, renaming, login |
| `BackupRunRepositoryTest`, `TableSizesTest` | The backups' latest outcomes and their purge; the tables' row estimates |
| `BoostRepositoryTest`, `EquipmentRepositoryTest`, `PaymentRepositoryTest`, `SeasonPassRepositoryTest` | Each economy repository, once a key, under concurrency where it matters; a refund written with its `admin_audit` row |
| `SeasonRepositoryTest`, `RatingBoardsTest`, `TeamBoardsTest` | Season ends, places, gems, resets; the boards' order and a place read by key |
| `TeamRepositoryTest`, `FriendRepositoryTest`, `InboxRepositoryTest`, `TournamentRepositoryTest`, `StatsRepositoryTest` | The rest of the repositories, each with its purge |
| `PrimaryDataSourceTest`, `DatabaseLimitsTest`, `StoredTimesTest` | The primary's choice and the heartbeat; the time limits (a slow migration from `src/test/resources/db/slow` is not cut); UTC stored whatever the process's zone |
| `DatabaseSettingsTest`, `BracketTest`, `RoundRobinTest`, `DailyGoalsTest` | No database: settings, the bracket, the schedule, the goals' draw |

## Build and test

From `backend/`, offline:

```bash
export JAVA_HOME=/opt/jdk21 PATH=/opt/jdk21/bin:$PATH
/opt/maven/bin/mvn -o -pl persistence -am install                    # this module, and common
/opt/maven/bin/mvn -o -pl persistence test -Dtest=PersistenceTest    # one class
/opt/maven/bin/mvn -o install                                        # everything
```

The benchmarks that measure this module's statements, each refusing any database
whose name does not end in `_test`, are in [tools](../tools/README.md):
`ApplyBenchmark` (results a second, by threads), `PurgeBenchmark` (retention's purge)
and `RankBenchmark` (a board's place).
