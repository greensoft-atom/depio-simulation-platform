# Glossary

Every term this system uses, what it means, and where it appears. Updated
2026-10-04.

This exists because the same idea kept acquiring second names, and two of them
had genuinely collided — "session" meant both an authenticated lobby session
and a player's stay in an arena, which is how someone eventually believes that
losing one ends the other.

**How to use it.** If a word is here, use it as defined and nowhere else. If
you need a word that is not here, add it here first. The
[naming rules](README.md#naming) come before this list: the one word they
exclude is used nowhere else, in prose or in identifiers.

Columns: **Term** · what it means · where it is realised in code, schema or on
the wire.

---

## 1. Processes and places

| Term | Meaning | Where |
|---|---|---|
| **backend** | The whole system. The namespace for everything: packages, paths, units, the OS user. | `com.backend.*`, `/opt/backend`, `backend-arena@a1` |
| **arena** | A process that runs match simulation and speaks to clients directly. Also, loosely, the place a match happens. | `arena` module, `ArenaMain`, `arena:{name}` |
| **platform** | The process serving everything between matches: accounts and guests, sessions, tickets, the matcher, teams, tournaments' registration, the boards, the shop and payments, the social layer, and the admin API. | `platform` module, `PlatformHttpServer`, `AdminServer` |
| **gateway** | The process that holds client lobby connections and forwards to `platform`. Makes no product decisions. | `gateway` module, `GatewayServer` |
| **worker** | The process that applies results to MySQL and grants rewards. | `worker` module, `WorkerMain` |
| **handoff** | The module holding what `platform` and `arena` both touch, and nothing else. It exists so `arena` never inherits a database driver. | `handoff` module |
| **room** | One simulated world inside an arena, with its own thread and its own players. An arena hosts several. | `Room`, `RoomThread`, `RoomRegistry` |
| **store** | j-redis. Two instances of one binary ([D-7](architecture/03-decision-log.md#d-7--two-j-redis-instances-not-one)): **session** — sessions, tickets, the arena directory, throttles, the gateway registry, the queues, parties and leases, the score boards — and **events** — the result stream and the lists beside it. With `BACKEND_EVENTS_STORE` unset, one instance is both. The instance named *session* is not a **session**. | `j-redis-service`, `StoreClients` |
| **primary** / **replica** | Of a stateful role (MySQL, either store): the server that takes writes, and the copy on another machine that follows it and takes none. | `replicaof`, `super_read_only`, `BACKEND_STORE_ADDRESSES`, `BACKEND_DB_URL` naming two hosts |
| **promotion** | Making a replica the primary, by an operator with a script, never automatically ([D-8](architecture/03-decision-log.md#d-8--failover-is-scripted-not-automatic)). | `promote-store.sh`, `promote-mysql.sh`, [runbook §2](operations/02-runbook.md#2-stateful-primary-failure) |
| **epoch** | A number only a promotion raises. A process never uses a primary whose epoch is below one it has seen, so an old primary that comes back is passed over. | j-redis `ROLE`, MySQL's `ha_epoch` |
| **heartbeat** | The one row (`ha_heartbeat`) every `worker` stamps on MySQL's primary once a second; a replica's copy is as far as it has applied, so the primary's stamp less the replica's is its lag ([D-58](architecture/03-decision-log.md#d-58--a-replica-is-measured-by-what-it-has-applied-a-heartbeat-for-mysql-the-primarys-own-account-for-the-stores)). | V21, `ReplicaWatch`, `promote-mysql.sh` |
| **fence** | Stop an old primary serving before its replica is promoted: stopped, cut off, or, in a **handover**, made read-only and closed to the processes by the script. | `--old-is-down`, `--demote-old` |
| **handover** | A planned promotion, the old primary still up and fenced by the script, as against one that follows a failure. | `MYSQL_FAILOVER=demote` in the drill |
| **arena directory** | Where `platform` and `worker` find arenas: each arena announces its host, port and load every 3 s, kept 10 s, and the matcher chooses from it (D-20, D-42). | `ArenaDirectory`, `arena:{name}`, `arenas` |
| **lease** | A store key taken with `SET NX` and an expiry so that one process of many does a job, given back when done or left to lapse: the matcher's, the ledger check's, the season's close. Not a fencing token: each job it guards is safe to repeat ([availability §5](architecture/02-availability.md#5-prerequisites-still-missing)). | `mm:leader`, `job:ledger-check`, `job:season` |
| **drain** | An arena stopping: it leaves the directory, sends the public arena's players back to the lobby with their results, and lets made matches end, for up to eleven minutes ([01 §8.6](detailed-design/01-arena.md#86-draining-an-arena-designed-2026-09-29-plan-item-7), D-29). | `systemctl stop`, `drained in N s` |
| **admin API** | `platform`'s operator interface: its own listener, on loopback, behind a bearer token, every call audited in MySQL ([04 §10](detailed-design/04-platform-services.md#10-admin-api), D-30, D-50). | `AdminServer`, `BACKEND_ADMIN_ADDR`, `/admin/*`, `admin_audit` |
| **clone** (a server) | Make a MySQL replica, or remake an old primary as one, by copying a whole server from the primary with MySQL's clone plugin: data, accounts and GTID history, never its persisted settings ([D-36](architecture/03-decision-log.md#d-36--a-mysql-replica-is-made-and-an-old-primary-remade-by-clone)). | `CLONE INSTANCE`, [01 §9](operations/01-deploy.md#9-mysql), runbook §2 step 5 |

## 2. Play

| Term | Meaning | Where |
|---|---|---|
| **match** | The umbrella: **one recorded unit of play**. Always qualify it as *open* or *timed* when the difference matters. | `matches` table, `MatchOutcome` |
| **open match** | A recorded **stay** in the public arena — or, for a stay longer than ten minutes, one ten-minute piece of it (**checkpoint**). No roster, no placement, no winner. The room never resets ([D-15](architecture/03-decision-log.md#d-15--the-public-arena-runs-continuously-structured-modes-are-timed-matches)). | `MatchOutcome.KIND_OPEN`, `matches.kind = 0`, `MatchRules.Lifecycle.OPEN` |
| **timed match** | A contest with a fixed roster, a start and an end, and one result for everybody. Every mode but the public arena's, tournament matches included. | `MatchOutcome.KIND_TIMED`, `matches.kind = 1`, `MatchMode.timed` |
| **public arena** | The free-for-all room every arena runs without end, joined with "play now", no queue: its players' results are **open matches** (D-15). | `MatchMode.FFA`, `Lifecycle.OPEN` |
| **made match** | A timed match `platform` or `worker` makes for a roster — by the matcher, a tournament's round or a sandbox request — whose room the arena makes from the first ticket that names it (D-20). | `Lifecycle.MADE`, `matchUid` |
| **battle** | The **activity**, never a unit: "in battle", "battle rewards", "the in-battle ratio". Good for player-facing wording. | prose, UI labels |
| **combat** | **Not used as a unit.** A mass noun — "a combat" reads wrongly — and it names the shooting, not the bounded contest. Acceptable as a heading inside code. | — |
| **checkpoint** | Closing a long open match and starting a fresh one for the same player, every ten minutes, so a long stay is paid for before it ends. | `MatchRules.checkpointTicks`, `MatchTally.checkpointOpenMatch` |
| **life** | One tank, from spawn to death. **Not** a recorded unit: a result per life would be ~1 700 events a second at 50 000 players. | `Entity`, respawn |
| **mode** | The rules of a match: map, win condition, teams. Ten: `ffa`, the public arena; `duel`; `tvt`, team-vs-team, three against three; `rffa`, ranked free-for-all, eight; `coop`, three against waves; `teams`, a **team match**; `domination`; `tag`; `maze`, eight each for themselves among walls; `sandbox`. All but `ffa` are timed, and all but `ffa` and `sandbox` queued. | `matches.mode`, `MatchMode` |
| **wave** | Co-op's: a number of the arena's own **hunting tanks**, which go for the nearest player of another team; the next comes once the last is dead ([01 §8.5](detailed-design/01-arena.md#85-co-op-waves-designed-2026-09-29-plan-item-6)). A **wipe** is none of the players' team alive at once. | `arena/Waves`, `Entity.hunts` |
| **queue** | Where players wait for a timed match of one mode, oldest first. Not the result queue of §7. | `mmq:{mode}`, `MatchQueue` |
| **entry** (queue) | What the queue holds and the matcher places: a player alone, or a **party** under its leader. Queued, taken out and matched whole. | `mmp:{playerId}`, `MatchQueue.Waiting` |
| **matcher** | The loop that makes matches out of the queues once a second, run by whichever `platform` holds the lease. | `Matchmaker`, `mm:leader` |
| **rating window** | How far apart in rating the matcher lets an entry be matched: ±100, 50 wider for every 10 s the oldest has waited. Not a board's **window**. | `Matchmaker.window` |
| **rating** | A player's Elo in one rated mode, from 1 200, moved by placement. A **side** in team-vs-team is rated as its players' mean ([D-26](architecture/03-decision-log.md#d-26--a-team-is-rated-by-its-players-mean-each-moved-by-their-own-k)); a free-for-all each against each other ([D-28](architecture/03-decision-log.md#d-28--a-free-for-all-is-rated-pairwise-each-player-against-each-other)). A **team** has a rating of its own, moved only by team matches (Q-18). | `player.rating_duel`, `rating_tvt`, `rating_rffa`, `team.rating`, `worker/EloRating` |
| **confirm step** | Asking every player of a match found whether they are coming, before it is made: ten seconds to accept; a decline, or silence, calls it off ([D-27](architecture/03-decision-log.md#d-27--a-match-found-asks-every-player-before-it-is-made)). A player asked is **confirming**; one whose party changed meanwhile has **withdrawn**: that entry is out of the match, nobody locked out (T-32). | `evt.match.ready`, `Matchmaker.settle`, `mmc:{matchUid}` |
| **walkover** | A timed match one side wins because the other never came: it moves no rating, is not a rated match and pays no match coins; lasting no time, it counts toward no stats, achievements, goals or pass points either (defect D-45 and decision D-76, the owner's, 2026-10-05; before, it counted as a win, as defect T-44 records). It is recorded, and decides a tournament's match. | `RoomThread.startMadeMatch` |
| **party** | Up to three players who queue together, made by an invitation, in the store only ([D-25](architecture/03-decision-log.md#d-25--a-party-lives-in-the-session-store-and-only-its-leader-queues)). Its **leader** queues it, and is its first member. Not a clan. | `party:{id}`, `Parties`, `evt.party.*` |
| **tick** | One simulation step. 25 per second. | `Room.tick()` |
| **experience** (xp) | What killing something is worth. The same number levels the killer's tank and adds to their match score, so a player is never shown two scores that disagree. | `KillLog.xp`, `TankStats.xp` |
| **level** | A tank's progression **within one life**, 1 to 45. Lost at death but for the **level rebate**; the match score is not. | `TankStats.level`, `F_LEVEL` |
| **level rebate** | What a tank respawned after a death keeps: in the public arena a quarter of the experience its last life reached, up to what level 20 needs; in a made match none, level 1 (Q-36). | `RoomThread.respawn` |
| **class** / **class table** | What a tank is: its barrels, reload, view and body. Basic, and 48 more opened at levels 15, 30 and 45, chosen with `ChooseClass`; and the arena's own (the Guardian, the dominator), which no player can choose. The table is served by `platform`, versioned by its content ([D-24](architecture/03-decision-log.md#d-24--the-class-table-reaches-the-device-from-platform-versioned-by-its-content)). | `ClassTable`, `GET /v1/content/classes` |
| **class tier** | The classes a level opens: the first at 15, the second at 30, the third at 45. Not a season pass's **tier** (§5). | `ClassTable` |
| **barrel** | One gun of a class: its angle, offset, delay, multipliers for a bullet's speed, damage and penetration, spread and lifetime, fired in volleys ([01 §4](detailed-design/01-arena.md#4-levelling-xp-tank-tree)). Also the name of an equipment **slot**. | `ClassTable.Barrel` |
| **unit** | What a class makes that is steered, not predicted: drones, traps, minions and missiles, streamed as tanks are and drawn by their create's radius. | `Entity.WIRE_UNIT` |
| **skill point** | Granted on levelling, spent to raise one stat. 33 by level 45, at most 7 in any one stat — so four stats can be maxed and a fifth cannot. | `TankStats.unspentPoints`, `ClientMessage.UPGRADE_STAT` |
| **stat** | One of the eight upgradeable numbers: health regen, max health, body damage, bullet speed, penetration, bullet damage, reload, movement speed. The order is a wire contract. | `Stat` |
| **effective stats** | A tank's stats after level and points are applied. Recomputed when something changes, never per tick. | `TankStats.refresh` |
| **content table** | Balance as data rather than code: what a stat is worth, what a level costs, what shapes exist. One set per arena, fixed while a room runs. | `Content`, `StatTable`, `LevelTable`, `ShapeTable` |
| **phrase** | What a player can say: one of a fixed list, sent as its id, never free text ([D-14](architecture/03-decision-log.md#d-14--communication-is-a-fixed-phrase-list-never-free-text)). Heard by the speaker's team in a mode with teams, otherwise by those who see the speaker. The list is content, versioned by its hash. | `PhraseTable`, `ClientMessage.PHRASE`, `Wire.EVT_PHRASE` |
| **shape kind** | Which shape: square, triangle, pentagon or alpha pentagon. Differs in size, health and experience. Its id is on the wire as the static entity's subtype. | `ShapeTable.Type`, `Entity.subtype` |
| **snapshot** | One outgoing frame of world state for one client. 15 per second, 10 at `saver`. | `SnapshotEncoder`, `Wire.MSG_SNAPSHOT` |
| **stay** | One player's continuous presence in one room: from `Join` to `Leave`, a kick, the arena stopping, or the end of a lost connection's wait — across any number of **resumes**. An open match records a stay, cut into ten-minute pieces by checkpoints. | `MatchTally`, `backend_arena_stays_*` |
| **waiting stay** | A stay whose connection was lost: its tank **parked** for 10 s, then out of the world with its level and points kept, until a resume or 60 s. Its play time stops meanwhile. Called *suspended* in the code. | `RoomThread.Suspended`, `MatchRules.resumeGraceTicks`/`resumeKeepTicks`, `backend_arena_stays_waiting` |
| **resume** | Coming back to a waiting stay on a new connection to the same arena, with `Resume` and the resume secret. An app killed and restarted resumes the same way, with the secret it kept on the device: the lobby hands nothing back ([D-18](architecture/03-decision-log.md#d-18--a-lost-connection-waits-in-the-arenas-memory-not-the-store), [D-51](architecture/03-decision-log.md#d-51--a-cold-resume-is-the-devices-the-app-keeps-its-stays-secret)). | `ClientMessage.RESUME` (10), `RoomThread.resume` |
| **resume secret** | 128 random bits in every `Welcome`, spent by one `Resume`; each `Welcome` carries a new one. Held in the arena's memory, not the store. | `Connection.resumeSecret` |
| **parked** | A tank in the world that no input drives: backgrounded, input silent for a second, or waiting for a resume. It can still be killed. | `RoomThread` (`STALE_INPUT_NANOS`), [P-23](defects.md#2-protocol--the-client-contract) |

## 3. People and identity

| Term | Meaning | Where |
|---|---|---|
| **account** | The login: a username and a password hash. One per player. | `account` table, `AccountRepository` |
| **player** | The person in the domain — name, level, currency, stats. Shares its id with the account. | `player` table, `playerId` |
| **account level** | 1–100, from lifetime xp, kept across matches; what later features unlock by. Never goes down. Not the **tank level** (1–45), which lasts one life. | `player.level`, `AccountLevels` |
| **session** | **The authenticated lobby session only.** A token issued at login, valid for about a day. Never used for a player's time in an arena — that is a **stay**, recorded as one or more open matches. | `sess:{token}`, `SessionStore` |
| **ticket** | A single-use, short-lived credential that lets one player into one arena. Claimed atomically and destroyed on use. | `ticket:{id}`, `Ticket`, `TicketStore` |
| **connection** | One client socket on an arena, and the boundary between Netty threads and the room thread. | `Connection` |
| **player tag** | A number identifying a player inside one room, never reused. Kill credit travels by tag, because a pool slot can be reused by someone else while a bullet is still in the air. | `Entity.playerTag` |
| **handle** | A one-byte per-client alias for an entity, so the wire never carries a 32-bit id. | `ClientView`, `Wire.MAX_HANDLES` |
| **display name** | What other players see: 1–16 characters, any script, checked by `DisplayName` at registration and at a rename (once in 30 days, [04 §1](detailed-design/04-platform-services.md#1-auth-and-sessions)). Distinct from **username**, which is what you log in with (ASCII, never shown to others). Need not be unique. | `player.display_name` vs `account.username` |
| **public code** | A twelve-character code every player is given, unique, meant to be shared. Shown by no API yet: friends are asked by player id (Q-20). | `player.public_code` |

## 4. The world

| Term | Meaning | Where |
|---|---|---|
| **entity** | Anything in the simulation with a position: a tank, a bullet, a shape. Pooled, never allocated during a tick. | `Entity`, `World` |
| **tank** | A player's or a bot's vehicle. | `Entity.KIND_TANK` |
| **bullet** | A projectile. Simulated by the client from its creation, not streamed ([D-9](architecture/03-decision-log.md#d-9--deterministic-entities-are-simulated-by-the-client)). | `Entity.KIND_BULLET`, `WIRE_PREDICTED` |
| **shape** | Neutral scenery that can be shot for score. | `Entity.KIND_SHAPE`, `WIRE_STATIC` |
| **side** | A side within a match: 1 and 2 in team-vs-team. 0 in a mode without teams, every player's: the public arena, a duel, a free-for-all. Written `team` in the code and in documents before 2026-09-30, when **team** came to mean a lasting group of players: the code keeps its name, documents say side from then on. | `Entity.team`, `match_player.team`, `Ticket.team` |
| **view** | The rectangle of world one client can see, and the handle table for it. | `ClientView` |
| **interest management** | Choosing which entities are worth sending to a given client, within a budget. | `SnapshotEncoder` |

## 5. Results and reward

| Term | Meaning | Where |
|---|---|---|
| **outcome** | What the arena reports: who played, what they killed, how long. **Facts only** — never what they are worth. | `MatchOutcome` |
| **reward** | What those facts are worth in xp and currency. Decided by `worker` alone, so a balance change is one deploy and never two arenas disagreeing. | `RewardRules` |
| **placement** | Finishing position in a timed match. **0 in an open match** — there is no field to be placed in, and a number there would look meaningful and not be. | `match_player.placement` |
| **score** | Points earned within one match. Shapes and tanks are worth different amounts. | `match_player.score` |
| **assist** | A hit on a tank, by a player, within five seconds before another player killed it: a quarter of the kill's experience, and counted with the match and the player's totals ([01 §7](detailed-design/01-arena.md#7-deaths-respawn-spectate), plan item 65). Not a kill. | `TankStats.attackerTags`, `KillLog.assists`, `match_player.assists` |
| **kill / death** | A death is always recorded; a kill only if the killer is still in the room. Totals satisfy *kills ≤ deaths*, never equality — bullets outlive the player who fired them. | `MatchTally.applyKill` |
| **kind** | Which lifecycle produced a recorded match. Stated in the row rather than inferred from the placement. | `matches.kind` |
| **ledger** | The append-only record of every currency movement. The balance must always re-derive from it. | `ledger` table, `EconomyRepository` |
| **offer** | One thing the shop sells, named by its **sku**: an item, a price in coins or gems, and optionally a level it needs and a time it is on sale. From `shop.json`, read at start. | `Catalogue.Offer`, [04 §8](detailed-design/04-platform-services.md#8-economy-shop-inventory-and-equipment) |
| **purchase key** | The client's transaction id for one tap of Buy, sent again unchanged with every retry of it, so the tap is charged once. A lowercase UUID. | `ShopService`, `ledger.idem_key` as `buy:{playerId}:{key}` |
| **item** | Something a player can hold, defined in `items.json` on `platform`: an id, a type (**equipment**, **boost** or **skin**), and what it does. What a player holds is `inventory_item`. | `Items`, [04 §8](detailed-design/04-platform-services.md#equipment-designed-2026-09-30-plan-item-15) |
| **equipment** / **slot** | An item worn in one of four **slots**, barrel, armor, core and treads, one each (a **skin** has a fifth slot of its own and gives nothing), giving a whole percent in one to three stats. | `equipment` table, `/v1/equipment`, `EquipmentService` |
| **loadout** | What a player wears: a slot each, an item or nothing. | `GET /v1/equipment` |
| **bonus** (equipment's) | What a loadout gives, added a stat and capped at 25 %: "better by that much", so for reload shots that much more often. Resolved on `platform` and carried in the ticket to the arena, which puts it on every tank the player spawns ([D-37](architecture/03-decision-log.md#d-37--equipment-reaches-the-arena-as-a-capped-percentage-a-stat-in-the-ticket)). | `Ticket.bonus`, `TankStats.setBonus` |
| **boost** | An item activated to raise a match's experience or coins by a percent for some minutes, one of a kind at a time; a match counts if it ended while the boost ran. Applied by `worker` to rewards, never to a rating or inside a match ([D-38](architecture/03-decision-log.md#d-38--a-boost-raises-a-matchs-rewards-in-worker-if-the-match-ended-while-it-ran), Q-13). | `boost` table, `/v1/boosts`, `BoostRepository`, `RewardRules` |
| **season** | Two calendar months of the rating boards (duel, ranked free-for-all, team-vs-team). At its end its places are kept, paid in gems, and every rating is moved halfway back to 1 200 with every rated count 0 ([04 §7](detailed-design/04-platform-services.md#seasons-designed-2026-10-03-plan-item-71-a), D-63). | `season`, `SeasonKeeper` |
| **daily goal** | One of a player's three goals for a UTC day, drawn by player and day from a table of kinds (stays, kills, wins, assists, score, time, rated matches), each easy or hard; paid in coins as a result meets it, and the three together 3 gems ([04 §8](detailed-design/04-platform-services.md#daily-goals-designed-2026-10-04-plan-item-74), D-66). | `DailyGoals`, `daily_goal` |
| **board of teams** | Teams by their rating, listed after ten rated team matches; its seasons pay each member who played for the team that season, by the players' table ([04 §7](detailed-design/04-platform-services.md#a-board-of-teams-and-its-seasons-designed-2026-10-04-plan-item-73), D-65). | `TeamBoards`, `season_team_place` |
| **season place** | A player's final place on a board in a past season, its rating and rated matches, and the gems it paid. | `season_place` |
| **soft reset** | A season's end for the ratings: halfway back to 1 200, so the strong start the next season higher, and the rated count to 0, so the board lists only those who have played it. | D-63 |
| **achievement** | A threshold on one of a player's counted stats (kills, wins, stays, assists, best score, time played), paid in gems once, when a result carries the stat across it ([04 §8](detailed-design/04-platform-services.md#achievements-designed-2026-10-03-plan-item-71-b), D-64). | `Achievements` |
| **gems** | The rare currency: paid by the system, for a tournament's places (from four entries), a level **milestone**, a **season**'s place, an **achievement** and a day's three **daily goals**; bought for money in **packs** (plan item 75); and spent in the shop on what is a convenience, never strength (D-67). Moved, as coins are, only through the ledger ([04 §8](detailed-design/04-platform-services.md#the-catalogue-and-gems-designed-2026-10-02-plan-item-68), plan item 68). | `player.gems`, `ledger.currency` 1 |
| **pack** | So many gems for so much money, named by its `productId`, priced in US cents. From `packs.json`, read at start. A player's first paid order pays its gems twice, once ever ([04 §8](detailed-design/04-platform-services.md#revenue-designed-2026-10-04-plan-item-75)). | `Packs.Pack` |
| **order** | A player's ask to buy a pack: pending until its **provider** confirms it paid or declined, final and once; a paid order's gems granted through the ledger, keyed by the order. Left pending a day, it expires. A paid order may later be refunded (D-68). Not a shop purchase, which spends gems or coins. | `payment_order`, `PaymentRepository.Order`, `ledger.idem_key` as `payment:{orderId}` |
| **provider** | Who takes a payment and confirms an order. The one built is **simulated**: the player's own call stands in for the provider's page and its notification, and it runs only where the operator names it, as it grants gems to whoever asks (D-68, Q-52). | `PaymentService`, `BACKEND_PAYMENT_PROVIDER` |
| **season pass** | Each season's track of rewards every player holds: **pass points** from play, a **tier** every 250, 40 tiers; a free track paying everyone and a premium track paying more to whoever bought it for the season with gems; each tier paid as the points cross it ([04 §8](detailed-design/04-platform-services.md#revenue-designed-2026-10-04-plan-item-75), D-69). | `SeasonPass`, `season_pass` |
| **pass points** | What a season pass counts: 10 a result paid, 50 a daily goal met, for the season being played when the result is applied. | `season_pass.points` |
| **tier** (season pass) | Every 250 pass points; reaching one pays its free reward, and its premium one with premium. The last paid on each track is the pass's mark. | `season_pass.free_paid`, `premium_paid`, `ledger.idem_key` as `pass:{season}:{track}:{tier}:{player}` |
| **skin** | How a tank is drawn, and nothing else: an item worn in a slot of its own, sold for gems, carried in the ticket and told to every client the tank is created for by the event `Skin` ([04 §8](detailed-design/04-platform-services.md#revenue-designed-2026-10-04-plan-item-75), D-70). Not a class, which is what a tank is. | `items.json` type `SKIN`, `Ticket.skin`, `TankStats.skin`, `EVT_SKIN` |
| **refund debt** | The gems a refunded order could not take back, because they were spent: kept on the order; a player in debt cannot order until an operator clears it (D-68). | `payment_order.debt` |
| **milestone** | An account level that pays gems when a result first reaches it: level 5, then every tenth to 100, 20 each, once a level ([D-61](architecture/03-decision-log.md#d-61--a-levels-gems-are-paid-for-the-milestones-between-the-level-stored-and-the-new-one)). | `MatchResultRepository.milestoneGems`, `ledger.idem_key` as `milestone:{playerId}:{level}` |
| **item level** | How far a piece of equipment held has been raised, 1 to 5, for coins; each of its modifiers × (3 + level) / 4, inside the 25 % cap (04 §8, plan item 67). | `inventory_item.item_level`, `POST /v1/inventory/{itemId}/level` |
| **team** | A lasting group of players, up to 30, one a player, with a leader, at most two vice leaders and members; joined by invitation or by an **application** the leader or a vice leader accepts (Q-49), left with a day's wait before another ([04 §2](detailed-design/04-platform-services.md#2-teams), Q-14). Not a **party**, which lasts a queue, nor a match's **side**. | `team`, `team_member`, `team_application`, `/v1/teams`, `TeamRepository` |
| **tournament** | A contest an operator creates for a prize: of duels (an **entry** a player) or of team matches (an entry a team and its **roster**, Q-19); single elimination, 2 to 32 entries, or round robin, 2 to 8 (plan item 66); an entrant needs ten rated matches in its mode (Q-43); registration until a deadline, then run round by round by `worker` (04 §6, Q-15). Its states: registration, seeded, running, finished, cancelled. | `tournament`, `/v1/tournaments`, `TournamentRepository`, `TournamentScheduler` |
| **seed** / **bye** / **bracket** | A **seed** is an entry's place in the order of rating in the tournament's mode, a player's duel rating or a team's rating, 1 the highest. The **bracket** is the draw of every round, sized to the next power of two, seed 1 against the lowest; a seed with nobody against it has a **bye** into round 2. A draw, or no result, sends the higher seed on. | `tournament_entry.seed`, `tournament_match`, `Bracket` |
| **team match** | A match between two teams (§5, **team**), three of each, mode `teams`: each side a party of three of one team, queued by its leader or a vice leader, and the team rated. Not team-vs-team (`tvt`), whose sides are whoever queued ([04 §4](detailed-design/04-platform-services.md#the-sixth-slice-team-matches-designed-2026-09-30-plan-item-19), Q-18). | `MatchMode.TEAMS`, `team.rating`, `match_team` |
| **roster** | The three players who registered a team for a teams' tournament: its leader's or a vice leader's party then. They play every match of it for the team; one who has left the team is sent no ticket, and there are no substitutes (Q-19, D-44). | `tournament_roster` |
| **inbox** | What a player should learn on their return: an item a kind (a friend request, a request accepted, a team invitation, a tournament prize, a team application, a season's reward) and a reference, never text; one a player, kind and reference; kept 30 days. Pushed as `evt.inbox`, "look" ([04 §9](detailed-design/04-platform-services.md#the-social-layers-first-slice-designed-2026-09-30-plan-item-21), Q-20). | `inbox`, `InboxRepository`, `/v1/inbox` |
| **active player** / **new player** | Active on a day: a match or a stay of theirs ended that day (UTC); a lobby visit alone does not count. New on a day: their first active day ([05 §11](detailed-design/05-worker-and-events.md#11-analytics-designed-2026-09-30-plan-item-25), Q-24). | `player_day`, `player.first_played_on` |
| **tag** / **conversion** | Tag's rule: a player killed by a player of the other team goes over to it, and comes back as that team; one team with everyone still playing ends it. Each player is placed by the team they **started** on ([01 §8.8](detailed-design/01-arena.md#88-tag-designed-2026-10-01-plan-item-31), Q-30). | `MatchMode.TAG`, `arena/Tag` |
| **dominator** / **anchored** / **captured** | Domination's prize: a tank of the arena's own that is **anchored** (drives nowhere, no knock moves it) and **captured**, never killed: a lethal blow from a player's tank makes it that player's team's at full health. Neutral, team 0, at the start ([01 §8.7](detailed-design/01-arena.md#87-domination-designed-2026-10-01-plan-item-30), Q-29). | `Entity.anchored`, `Entity.captures`, `ClassTable.DOMINATOR`, `arena/Domination` |
| **maze** / **wall** / **maze's seed** | The maze mode's map: ten cells a side cut by recursive division, a fifth of the walls then taken out. A **wall** is an axis-aligned rectangle 40 units thick on a cell's edge: tanks, shapes and units slide along it, a bullet ends the first tick its centre is within its radius of one. The **maze's seed**, the hash of the match's id, is all that is sent: both sides make the walls from it with the same generator ([01 §8.9](detailed-design/01-arena.md#89-maze-designed-2026-10-01-plan-item-32), Q-31, D-48). | `MatchMode.MAZE`, `sim/MazeGenerator`, `sim/Walls`, `Welcome.MazeSeed`, C# `Maze` |
| **sandbox** | A private room a player opens for themselves, or a party's leader for the party, never queued for: each player may rebuild their tank at any level and summon a Guardian; it ends at twenty minutes or a minute after it empties, and publishes nothing ([01 §8.10](detailed-design/01-arena.md#810-sandbox-designed-2026-10-01-plan-item-33), Q-32, D-49). | `MatchMode.SANDBOX`, `POST /v1/sandbox`, `arena/Sandbox`, the `Sandbox` message |
| **boss** / **Guardian** | Co-op's boss: a tank of the arena's own at waves 5 and 10, of a class no player can choose, twelve times a tank's health; **enraged** below half of it, firing twice as fast ([01 §8.5](detailed-design/01-arena.md#85-co-op-waves-designed-2026-09-29-plan-item-6), Q-28). | `ClassTable.GUARDIAN`, `GUARDIAN_ENRAGED`, `Room.assignClass`, `arena/Waves` |
| **render tick** | The tick a frame is drawn at: the newest frame's, plus the time since it came, less two frames' worth, never past it nor back. Other tanks are drawn between their two samples there; the own tank, predicted, in the present ([08 §4](detailed-design/08-client.md#4-the-world-and-when-things-are-drawn), §8). | `RenderClock`, `Scene` |
| **funnel** | Of the accounts made on a day, how many went how far by now: played, came back within a week, reached level 5, played rated, bought in the shop, paid money; no step required of the next ([05 §11](detailed-design/05-worker-and-events.md#the-funnel-designed-2026-10-04-plan-item-76-c), plan item 76 (c)). | `StatsRepository.funnel`, `GET /admin/stats/funnel` |
| **first-day use** | What a new player did on their first active day (UTC): played a queued match, bought, boosted, entered a tournament, made a friend, joined a team. Set against coming back, it is fixed before the retention it is compared with; use at any time would favour whoever stayed longest ([05 §11](detailed-design/05-worker-and-events.md#by-feature-designed-2026-09-30-plan-item-27), Q-26). | `GET /admin/stats/features` |
| **day-N retention** | Of a day's new players, how many were active again exactly N days later (1, 7, 30); empty until that day has ended. | `d1`, `d7`, `d30` in `GET /admin/stats` |
| **guest** / **guest key** | An account made with nothing asked, under a username nobody can register and with no password; it logs in by a random key its device keeps, stored as the key's SHA-256. **Upgrading** gives it a username and password, the same player, and the key stops working ([04 §1](detailed-design/04-platform-services.md#1-auth-and-sessions), [D-46](architecture/03-decision-log.md#d-46--a-guests-credential-is-a-random-key-kept-as-its-sha-256-under-a-username-nobody-can-choose), Q-22). | `account.guest_key_hash`, `/v1/guests`, `/v1/accounts/upgrade` |
| **friend** / **block** | Two players who asked each other, both ways ([D-45](architecture/03-decision-log.md#d-45--a-friendship-is-two-rows-and-a-change-to-it-locks-both-players-lowest-id-first)); a block stops the blocked one's requests and invitations reaching the blocker, who is never named to them. | `friend`, `block`, `FriendRepository` |
| **idempotency key** | The value that makes applying something twice the same as applying it once. | `ledger.idem_key`, `match_player`'s primary key |
| **best score** | The highest score a player has reached in any one **recorded** match. Chosen as the board metric because a maximum is idempotent under the queue's at-least-once redelivery where a running total is not. In the public arena a stay is checkpointed every ten minutes, so this measures the best ten-minute stretch rather than the peak a tank ever carried ([04 §7](detailed-design/04-platform-services.md#what-best-measures-exactly)). | `player_stat.best_score`, `ZADD … GT` |
| **board** (leaderboard) | A ranking by one metric over one window. The **score boards** (best score, all-time, daily, weekly) are in the store: their scores written by `worker` alone, their names by `worker` and by `platform` at a rename (D-60). The **rating boards** (duel, ranked free-for-all, team-vs-team) and the **board of teams** are read by `platform` from MySQL, by season ([04 §7](detailed-design/04-platform-services.md#7-leaderboards), Q-40). | `LeaderboardStore.Board`, `RatingBoards`, `TeamBoards` |
| **window** | The stretch of time a score board covers: **all-time**, **daily** or **weekly**. UTC, and an ISO week. A rating board's is a **season**. | `lb:score:day:{yyyy-MM-dd}` |
| **rank** | Position on a board. **0-based in the store, 1-based on the wire** — a player is "#1", and the conversion happens once, at the API. | `ZREVRANK`, `J.ZAROUND` |
| **around me** | A player's own rank with the rows on either side of it. One command, so the rank cannot disagree with the window it labels. | `J.ZAROUND`, `/v1/leaderboards/{board}/me` |
| **spool** | The arena's on-disk copy of a result, written before the network is touched and deleted only once the queue has it. | `MatchResultPublisher` |
| **dead letter** | Where an entry that can *never* be applied is set aside — unreadable, or carrying a value no column can hold — so it stops blocking every good entry behind it. Kept as evidence. | `q:match-result:dead` |
| **deferred** | An entry this build cannot read but a newer one can. Not garbage: kept, and offered again when a worker starts. | `q:match-result:deferred` |
| **unapplicable** | A player named in a result who does not exist, so whose part of it can never be paid. Reported at WARN and counted — never mistaken for a duplicate, which is what `INSERT IGNORE` used to do. | `MatchResultConsumer.unapplicablePlayerCount` |
| **re-drive** | Retrying everything pending for this worker, on a timer and as soon as the database recovers; first draining the inbox and taking over entries idle a minute. | `MatchResultConsumer.redrive` |
| **pending** (entry) | Delivered to a worker by the stream's group and not yet acknowledged. It stays with that worker until acknowledged or taken over. | `XPENDING`, `backend_worker_pending` |
| **take over** | Claim for this worker an entry pending on another, once untouched for a minute: how a retired worker's entries are finished. | `XAUTOCLAIM`, `MatchResultStream.claimIdle` |
| **result inbox** | The list `q:match-result`, kept after the move to a stream so that producers of the list release, and an operator putting dead entries back, still work. Drained into the stream by every worker (D-53). Called "the inbox" in 05 and the runbook, where nothing else is meant; not a player's **inbox**. | `MatchResultStream.drainInbox` |

## 6. On the wire

| Term | Meaning | Where |
|---|---|---|
| **match traffic** | Client ↔ arena. Binary, direct, never through nginx or the gateway. | [02](detailed-design/02-networking.md) |
| **lobby traffic** | Client ↔ gateway ↔ platform. JSON over WebSocket at `/lobby`, low volume. | `LobbyHandler` |
| **kill feed** | Who killed whom, as `Kill` events: to the killer in every room, and to every player in a made match; a shape broken is not in it ([01 §9](detailed-design/01-arena.md#the-kill-feed-designed-2026-10-01-plan-item-37), Q-34). | `Wire.EVT_KILL`, `RoomThread.relayKills`, C# `MatchEvent.Victim` |
| **push** | A lobby message with no `id`, sent by the server unprompted: `evt.session.replaced` by the gateway itself, and `evt.match.found`, `evt.queue.update`, `evt.party.*`, `evt.team.update`, `evt.friend.*` and `evt.inbox` by `platform`, and `evt.tournament.match`, `evt.inbox` and `evt.rewards` by `worker`; and `evt.notice`, an operator's, to everyone on `push:all`, through the gateway that holds the player's connection. A notification: the truth is fetched ([03 §7](detailed-design/03-gateway.md#7-what-must-not-be-lost)). | `evt.*` in [03](detailed-design/03-gateway.md), `LobbyPush`, `push:{gateway}` |
| **held push** / **resync** | A push the gateway keeps while the player's connection cannot take more, sixteen at most, and sends in order when it drains; past sixteen the backlog is dropped and one `evt.resync` sent instead, telling the client to fetch the truth as after a reconnect. A connection that stays unwritable 30 s is closed ([03 §8](detailed-design/03-gateway.md#8-backpressure-and-slow-clients)). | `gateway/Pushes`, `evt.resync` |
| **grant** | What a player is handed to go to a match: the arena's host and port, a single-use ticket, and whether to use TLS. The answer to "play now", carried by `evt.match.found`, and kept for a tournament's call as `tgrant:`. | `JoinService.Grant`, `MatchQueue.Grant` |
| **notice** | An operator's message to everyone in the lobby, before a stop: `POST /admin/notice`, published once on `push:all` and pushed as `evt.notice` ([04 §10](detailed-design/04-platform-services.md#10-admin-api)). | `AdminServer`, `evt.notice` |
| **protocol version** | The number a `Join` names and the arena checks, raised when the wire changes in a way an older client cannot read: 4 since the third class tier. An older client is refused with a kick. | `Wire.VERSION`, `Wire.KICK_PROTOCOL_VERSION` |
| **golden vector** | A snapshot frame and what it decodes to, written by the spike's independent codec, which both the server's reference decoder and the C# core must read field for field: the contract between the two languages. | `protocol-spike/vectors/vectors.txt`, `GoldenVectorTest` |
| **welcome** | The first frame an arena sends after a join or a resume is accepted. Carries a new **resume secret**. | `Wire.MSG_WELCOME` |
| **kick** | A typed refusal sent **before** the socket closes, so the client can tell "get a new ticket" from "re-queue" from "update the app". | `Wire.MSG_KICK`, `KICK_*` |
| **event** | Something that happened, carried inside a snapshot rather than as its own packet. Framed `type, byteLength, payload` so an older client can skip a type it has never heard of. | `Wire.EVT_DEATH`, `EventBuffer` |
| **stats event** | A player's own level, experience and skill points. Sent only to them, and only when it changes — immediately for a level or a spent point, at most once a second for experience alone. | `Wire.EVT_STATS` |
| **prediction** (own tank) | The client moving its own tank by its own input at once, by a port of the room's movement rule, instead of waiting a round trip for the server to say where it went. Only the own tank; others are drawn a little in the past ([02 §9](detailed-design/02-networking.md#predicting-the-own-tank), D-62). | `OwnTank`, `TankMotion.Step` |
| **step** (client) | One tick of the own tank's prediction, at the room's 25 Hz, stamped with the seq of the input that will carry it and holding the state after it; 64 are kept. | `OwnTank` |
| **reconciliation** / **replay** | On each frame, putting the own tank where the server has it at the step that matches the frame's tick, then stepping again every step after it, each with its own input. | `OwnTank` |
| **correction** | How far a reconciliation moved the predicted present. Drawn away, halving every 50 ms; more than 64 units is a **jump** (a respawn, a resume) and is taken at once. | `OwnTank.DrawX` |
| **prediction error** | The matched step's position as predicted before the frame came, against the frame's: what the drill measures. | `prediction` drill |
| **input ticks** | How many ticks the input a frame echoes has driven the tank, through the frame's tick: where in the input's span the tick fell. | `Motion`, `ClientView` |
| **motion event** / **motion rule** | `Motion` (type 5): the input ticks and the own tank's velocity, every frame while it lives. `MotionRule` (type 6): its acceleration and radius, as the server's floats, when either changes. State for the prediction, not news: the layer above never sees them. | `Wire.EVT_MOTION`, `Wire.EVT_MOTION_RULE` |
| **ack tick** | The last snapshot the client fully applied, carried on every `Input`. It decides when a removed handle may be reused and whether a create has arrived ([02 §5](detailed-design/02-networking.md#5-entity-handles)), and how much is queued on the client's link, which steps its traffic profile ([D-17](architecture/03-decision-log.md#d-17--a-client-is-stepped-down-on-queueing-not-on-round-trip-time-or-writability)). It is **not** the delta baseline: deltas are against the last frame sent ([D-16](architecture/03-decision-log.md#d-16--deltas-are-measured-against-the-last-frame-sent)). | `Connection.takeAck`, `ClientView.acknowledge`, `TrafficControl.acknowledged` |
| **lifecycle** (client) | Whether the app is in the foreground. Backgrounding parks the tank and stops its snapshots. **Unrelated** to a match lifecycle. | `ClientMessage.LIFECYCLE` |
| **varint** | A length-prefixed integer encoding. One byte for anything under 128, which is nearly everything. | `SnapshotWriter.varint` |
| **traffic profile** | How much one client is sent: `high` 15 Hz / 60 entities, `mobile` 15 Hz / 30 (the default), `saver` 10 Hz / 20. The client names the most it wants in `Join` or `Resume`; the arena steps it down on a queueing link and back up, never above that ([D-17](architecture/03-decision-log.md#d-17--a-client-is-stepped-down-on-queueing-not-on-round-trip-time-or-writability)). | `TrafficProfile`, `TrafficControl`, `Wire.PROFILE_*` |
| **round** (send) | One chance to send a client a snapshot, at its profile's rate. | `TrafficControl.round` |
| **held round** | A round not sent because more than a second of snapshots is already queued on the link; the next frame just grows, since deltas are against the last frame sent. | `backend_arena_snapshots_held_total` |
| **floor** | The shortest acknowledgement delay a connection has shown; queueing is measured above it. It never rises. | `TrafficControl` |

## 7. Store keys

Every key j-redis holds, and who writes it, and the pub/sub channels beside
them. `s:match-result` and the `q:match-result*` keys live on the **events**
instance when `BACKEND_EVENTS_STORE` names one; every other key on
**session**.

| Key | Holds | Written by |
|---|---|---|
| `sess:{token}` | An authenticated session → `playerId`. ~24 h, jittered. | `platform` |
| `ticket:{id}` | A single-use join grant. 60 s. | `platform`, and `worker` for a tournament's match; consumed by `arena` |
| `rl:login:addr:{address}:{minute}` | Registrations and logins from one address (IPv6: one /64) this minute. Expires with the window. | `platform` |
| `rl:login:acct:{username}:{quarter-hour}` | Logins naming one account this quarter hour, whether or not it exists. Expires with the window. | `platform` |
| `rl:ask:{playerId}:{hour}` | Friend requests one player made this hour, every one counted (Q-46). Expires with the window. | `platform` |
| `rl:tinv:{playerId}:{hour}` | Team invitations one player made this hour (Q-46). Expires with the window. | `platform` |
| `rl:tapp:{playerId}:{hour}` | Team applications one player made this hour (Q-49). Expires with the window. | `platform` |
| `arena:{name}` | A live arena's host, port and load. 10 s, refreshed every 3. | `arena` |
| `arenas` | The set of arena names to look in. | `arena` |
| `s:match-result` | The stream of finished matches, one field `e` holding the envelope; kept 24 hours, read or not. Read by the group `rewards`, one consumer per worker named by its id; an entry stays pending for its consumer until acknowledged, and any worker takes over one idle a minute. | `arena`; `worker`, from the inbox |
| `q:match-result` | The inbox: finished matches pushed by arenas of the list release, and dead entries an operator puts back. Every worker moves it into `s:match-result`, at start and every 30 s. | `arena` of the list release, operators; drained by `worker` |
| `q:match-result:processing:{workerId}` | Entries this worker is moving from the inbox into the stream, removed once added; and what a list-reading worker had claimed when it stopped, moved the same way. | `worker` |
| `q:match-result:deferred` | Entries from a newer producer than this build reads. Returned through the inbox whenever a worker starts. | `worker` |
| `q:match-result:processing` | The single shared list of earlier builds. Emptied back onto the queue at start-up; nothing writes it now. | — |
| `q:match-result:dead` | Entries nobody could parse. Kept as evidence. | `worker` |
| `conn:{playerId}` | Which gateway holds a player's lobby connection: `{gatewayId}#{nonce}`, the nonce naming that one connection so only its own cleanup can delete it. 60 s, refreshed every 20 without overwriting. | `gateway` |
| `lb:score:alltime` | Best single-match score ever, per player. No expiry. | `worker`, read by `platform` |
| `lb:score:day:{yyyy-MM-dd}` | Best single-match score that UTC day. 3 days. | `worker`, read by `platform` |
| `lb:score:week:{yyyy-Www}` | Best single-match score that ISO week. 10 days. | `worker`, read by `platform` |
| `lb:name` | Player id → display name, so rendering a board is one extra round trip rather than N. | `worker`, as it applies a result; `platform`, at a rename (D-60) |
| `job:ledger-check` | Which worker runs today's ledger check. `SET NX`, 23 h; handed back if the check fails. | `worker` |
| `job:season` | Which worker closes a season that has ended, looked at each minute. `SET NX`, 10 min; handed back when the run ends. What makes a run right is each step's record in MySQL, not the lease (D-63). | `worker` |
| `ledger:check:last` | The fleet's last ledger-check result, read back by every worker's metrics. | `worker` |
| `push:{gatewayId}` | The channel a push to a player on that gateway is published on. | `platform` and `worker`, read by `gateway` |
| `push:all` | The channel an operator's notice is published on, once, for every gateway to hand to each lobby connection (`evt.notice`). | `platform`, read by every `gateway` |
| `arena-admin:{name}` | The channel an operator's room close and a player's removal reach that arena on (04 §10). | `platform`, read by `arena` |
| `mmq:{mode}` | A mode's queue: each entry's leader, scored by when it queued. | `platform` |
| `mmp:{playerId}` | A player's place: queued (mode, since; the leader's entry names its members, their ratings and names; a member's names its leader), confirming (the match asked about, its deadline, and the answer: accept, decline or withdrawn) or matched (the grant). A team match's entry names its team too. 15 min queued, 60 s confirming, 60 s matched. | `platform` |
| `mmc:{matchUid}` | A match found and waiting for answers: its mode, deadline and lineup. 60 s. | `platform` |
| `mmc` | The matches waiting for answers, scored by deadline. | `platform` |
| `mmlock:{playerId}` | A player who declined a match, or did not answer, locked out of the queue. 60 s. | `platform` |
| `sess:of:{playerId}` | The tokens of a player's sessions, so a ban ends every one. Past the longest session, about 26 h. | `platform` |
| `mm:leader` | Which `platform` runs the matcher. `SET NX PX`, 5 s, renewed every round. | `platform` |
| `party:{partyId}` | A party's leader, members and names. 1 h, renewed on every change. | `platform` |
| `partyOf:{playerId}` | The party a player is in. 1 h. | `platform` |
| `pinv:{playerId}:{partyId}` | An invitation, spent on accepting. 60 s. | `platform` |
| `rl:say:{playerId}` | A phrase said to the party: the next may follow once it lapses. 2 s. | `platform` |
| `tcall:{playerId}` | A player called to a tournament match, by any tournament: while it lasts the player may not queue or open a sandbox (Q-44). 60 s, as the ticket. | `worker`, read by `platform` |
| `marr:{matchUid}` | Somebody came to that made match: set by the arena at the first arrival, read by the tournament clock when no result has come (Q-45). 1 h. | `arena`, read by `worker` |
| `sbx:{playerId}` | The sandbox a player holds: taken with `NX` for a ticket's life when one is opened, then kept by the arena for the room's life and a minute (21 min), deleted when the player leaves or the room ends (D-54). | `platform`, then `arena` |
| `tgrant:{tournamentId}:{playerId}` | A tournament match's grant for a player: arena, ticket, round. 60 s, as the ticket. | `worker`, read by `platform` (`GET /v1/tournaments/{id}/match`) |
| `rooms:promised:{arena}` | Rooms promised to matches on that arena, by match id, scored by when each promise lapses: 60 s, as the ticket. Counted against the arena's free rooms until the arena drops it, in the announcement that first counts the match's room ([D-42](architecture/03-decision-log.md#d-42--a-matchs-room-is-promised-in-the-store-when-its-arena-is-chosen)). | `platform` and `worker`; dropped by `arena` |

## 8. Words we do not use

| Instead of | Use | Why |
|---|---|---|
| the word the naming rules exclude, and its compounds | platform, match traffic, match mode, meta layer | [Naming rules](README.md#naming), which list them — applies to prose and identifiers alike |
| combat, fight, round (as a unit of play) | match, open match, timed match | Vague, or already means something else. A tournament round (FR-7) and a send **round** (§6) are fine |
| session (for time in an arena) | stay, or open match for its record | "Session" is the authenticated one |
| high score, hiscore, top score | best score | One name for the metric, and it matches the column |
| leaderboard period, era, bracket (of a leaderboard) | window | Three words had appeared for the same thing. A tournament's **bracket** (§5) is fine |
| stat point, upgrade point, talent | skill point | One name for the thing a level grants |
| skill tree, upgrade tree | tank tree | "Tree" is the class progression (**class**, **class tier**), not the stats |
| score (for a tank's current experience) | level, or experience | Score is the match total and survives death; experience is the life's and does not |
| user | player, or account | Two different things, and "user" hides which |
| server (unqualified) | arena, gateway, platform, worker | Four different processes; "the server" names none of them |
| match (unqualified, when the kind matters) | open match / timed match | They behave differently and are stored differently |

## 9. Building, checking and running

| Term | Meaning | Where |
|---|---|---|
| **core** (client) | The client's engine-free library: the wire, the world, the match connection, the lobby, the API, the own tank's prediction, and what a frame draws. Tested on the development machine against the golden vectors and the real servers ([D-19](architecture/03-decision-log.md#d-19--the-client-is-an-engine-free-core-and-a-thin-unity-layer)). | `Backend.Client.Core`, `client/Core` |
| **Unity layer** | The thin scripts over the core that draw, read touches, report the app's lifecycle and keep the account: a Unity package, compiled here against stubs and not run in Unity (D-73). | `Backend.Client.Unity`, `client/Unity/com.backend.client` |
| **headless client** | The core driven without Unity, by a program that plays scenarios against a running stack. | `client/Headless` |
| **drill** / **scenario** | A **drill** starts the whole stack from a release and runs the headless client against it; each **scenario** is one path a player takes (`play`, `duel`, `tournament`, …), 44 at plan item 78, plaintext and over TLS. The backups have a drill of their own. | `client/headless-drill.sh`, `backend/scripts/backup-drill.sh` |
| **soak** | Two hours of bots that come and go and a duel every 30 s against a release, each process sampled each minute and judged on the second hour: nothing may grow that should not ([07 §4](detailed-design/07-threading-and-performance.md#4-tick-budget-and-what-to-measure)). | `SOAK=1`, `tools/SoakJudge` |
| **mutation check** | Breaking each new rule on purpose to see a test fail; a mutant no test catches is a missing test, or a rule that does nothing. | each plan item's "mutation-checked n of n" |
| **restore proof** | The weekly restore of the newest dump and the binlog copies onto the backup machine's scratch server, checked and recorded ([06 §10](detailed-design/06-persistence-mysql.md#10-backup-and-recovery), D-71). | `backend-restore-proof.timer`, `backup_run` |
| **copy off the site** | The dumps and closed binlog copies, encrypted and pushed by rsync over SSH each hour to a host the owner names; off until there is one (Q-53). | `backup-offsite.sh`, `backend-backup-offsite.timer` |
| **baseline** (schema) | The two migrations that make the schema from nothing since 2026-10-04: `V1__schema.sql` and `V2__seed.sql`, squashed from V1 to V34 ([D-75](architecture/03-decision-log.md#d-75--the-migrations-are-squashed-into-one-baseline-before-the-first-launch)). A `V`-number above 2 in a document is a step of that history. | `backend/persistence/src/main/resources/db/migration` |
