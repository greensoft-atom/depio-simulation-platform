# Plan and status

What is built, what is next, and in what order. Updated 2026-10-05.

**Where it stands (2026-10-05).** Plan items 1 to 80 are done, but for item 7,
which stays open by design: what is left of it waits on the domain and the CA,
and on Q-3's hardware. Every component is built and tested: `j-redis-service`
2.2.1 (229 tests), the backend's ten modules (1 020 tests at item 79) and the
client's engine-free core with its headless driver (169 tests). The Unity layer
is written as a package of scripts, compiled against stubs of the Unity API and
not yet run in Unity. A player can register or play as a guest, hold a lobby
connection, play the public arena, queue for any of eight queued modes or open a
sandbox, and have the result reach MySQL, the boards and their wallet, told back
through the gateway. Item 78 drove all of it from one release: 44 live drill
scenarios, plaintext and TLS, a two-hour soak of 300 bots, and the backup drill;
item 79 reviewed all of it, fixed what the review found, and drove every scenario
and drill again.
Nothing has run on the production hardware, and nothing has launched.

**What is open is the owner's** ([Q-54](requirements/01-scope-and-nfrs.md#7-open-questions)):
opening the Unity package in the editor, which the owner put off on 2026-10-05; where the backups' copy off the site
goes (Q-53); a real payment provider (Q-52); and what waits on the owner's
machines and players (Q-1 to Q-4: the uplink, the share of players in battle,
measurements on the production hardware, how players cluster); and whether an
admin panel is wanted, and how (Q-55, put off by the owner on 2026-10-05). The certificate's domain and CA are
deferred by the owner.

**How the list came to be ordered.** The audit of 2026-09-23 found that the
contract between the components had never been executed end to end: nothing
decoded what the snapshot encoder sent, and three of the five durability
guarantees the documents stated did not hold. The wire became a tested contract
and the guarantees true on 2026-09-25 (items 1 and 2), and the client got an
owner on 2026-09-27 (item 3). The [defect register](defects.md) records what
the audit found and everything found since; [§What to do next](#what-to-do-next-in-order)
was re-ordered around it, and has since grown one item at a time.

The previous 16-week milestone plan is in
[archive/implementation-guide-2026-09.md](archive/implementation-guide-2026-09.md);
it assumed one machine, a browser client and MongoDB, so it is kept for history
rather than followed.

**Scope decisions of 2026-09-23:** launch at **5 000–10 000 concurrent
players** (two machines with replication, not the full three-machine
scale-out); v1 includes the **full meta layer** — leaderboards, accounts and
progression, shop and currency, equipment and inventory, teams and tournaments,
and fixed-phrase chat
([D-14](architecture/03-decision-log.md#d-14--communication-is-a-fixed-phrase-list-never-free-text)).

## 1. Status

| Component | State |
|---|---|
| **j-redis-service** | **2.2.1 shipped** (2026-10-01): a subscriber follows the primary as every other connection does (plan item 50). 2.2.0 (2026-09-29) added replication, a primary and its replicas with promotion guarded by epochs; 2.1.0 (the same day) streams, on 2.0.0's Java 21 base. 229 tests, crash-tested with and without a replica, offline build, documented. See its [README](../j-redis-service/README.md). |
| `java21-offline` bundle | Built and verified: 244 jars, 0 above Java 21, offline build proven from an empty repository. |
| Requirements | [Written](requirements/01-scope-and-nfrs.md), with 54 questions recorded. Four wait on the owner's machines or players: Q-1 uplink, Q-2 in-battle ratio, Q-3 production measurements, Q-4 how players cluster. Two wait on the owner's choices: Q-52, a real payment provider (the flow is built, its provider simulated), and Q-53, where the backups' copies off the site go. The rest are answered, or built on their recommendations unless the owner decides otherwise. |
| Architecture | [Topology](architecture/01-system-topology.md), [availability](architecture/02-availability.md) and the [decision log](architecture/03-decision-log.md), D-1 to D-75, written. What runs where at launch on two machines is not written yet (topology §2). |
| `arena` design | [Current](detailed-design/01-arena.md), with what is not built marked in place: replays, the in-room events beyond the kill feed, and co-op's tiers. The modes, the maze's walls, the whole tank tree and the kill feed are built. |
| `networking` design | **[Written](detailed-design/02-networking.md)** — transport, wire format, snapshot protocol, client-side simulation contract, traffic profiles, the own tank's prediction. |
| `gateway` design | **[Written](detailed-design/03-gateway.md)** — lobby connection, auth handshake, push routing, rate limiting. |
| `platform` design | **[Written](detailed-design/04-platform-services.md)** and extended module by module in Phase 5 — auth and guests, teams, matchmaking and every queued mode, tournaments, boards and seasons, the economy (shop, items, gems, payments, the season pass, skins), the social layer, the admin API. |
| `worker` / events design | **[Written](detailed-design/05-worker-and-events.md)** — streams, consumer loop, dead-lettering, result pipeline, scheduled jobs, analytics and the funnel. The worker reads the result stream (item 10). |
| MySQL persistence design | **[Written](detailed-design/06-persistence-mysql.md)** — schema, transaction patterns, idempotency via natural keys, pooling, retention and growth, backups proved weekly. The schema is one baseline since 2026-10-04, `V1__schema.sql` and `V2__seed.sql` (D-75). |
| Operations docs | [Mostly written](operations/01-deploy.md) — install, TLS, MySQL, backups and the certificate run for real; the stores' promotion scripted and drilled ([runbook §2](operations/02-runbook.md#2-stateful-primary-failure)), MySQL's too (2026-09-29, rehearsed under load 2026-09-30); backups proved by a weekly restore (item 76). What is left is the alert thresholds, which wait on Q-3's hardware ([runbook](operations/02-runbook.md)). |
| Application code | **[backend/](../backend/README.md)**: `common`, `protocol`, `sim`, `arena`, `handoff`, `persistence`, `platform`, `worker`, `gateway`, `tools`. 994 tests, 0 compiler warnings (item 78, 2026-10-04). Multi-room arena, largest clean run 1 200 bots, every one sent every snapshot (item 14), and 600 through a failover (item 23); MySQL transactions tested for idempotency and double-spend under concurrency; single-use join tickets; Argon2id login and sessions; register → login → lobby → ticket → play → result persisted → **leaderboard updated**, verified end to end across four processes against real MySQL and real j-redis. |
| Unity client | **Claude writes it** ([D-19](architecture/03-decision-log.md#d-19--the-client-is-an-engine-free-core-and-a-thin-unity-layer), 2026-09-27): the engine-free core and a headless driver in [client/](../client/README.md), 148 tests (2026-10-04), driven against the real stack by the live drills. The Unity layer is written as scripts over the core, compiled against stubs and not yet run in Unity (item 77, the owner's Q-51); own-tank prediction, deferred with it on 2026-09-27, was brought back on 2026-10-03 and built (item 70). |

### Build checklist

✅ built and tested ·  🟡 partly built, gap named ·  ⬜ not started

**Foundations**

| | Item |
|---|---|
| ✅ | j-redis 2.0.0 — server, client, embedded, CLI; 138 tests, crash-tested |
| ✅ | `java21-offline` bundle — 244 jars, offline build proven from an empty repository |
| 🟡 | Offline bundle covers **28 of the 30** third-party runtime artifacts. The two gaps are `com.jredis:*`, which is ours and is built from source — `cd j-redis-service && mvn install -DskipTests` before building `backend`. Checked by diffing every module's runtime dependencies against the bundle |
| ✅ | j-redis 2.1 streams (2026-09-29): the type, consumer groups, blocking reads, persistence, client ([docs/15](../j-redis-service/docs/15-streams.md)) |
| ✅ | j-redis 2.2 replication, and the stores deployed with replicas that every process follows (items 11, 12) |

**Simulation (`sim`)**

| | Item |
|---|---|
| ✅ | Fixed timestep, six phases, pooled entities, spatial hash, zero steady-state allocation |
| ✅ | Kill attribution by non-reusable `playerTag`; `KillLog`; world reset |
| ✅ | **Levels, XP curve and stat upgrades** — eight stats, 45 levels, 33 skill points capped at 7 per stat (a class may set its own: a Smasher's are 10 and 0), all from content tables. A player levels by playing and spends points over the real protocol ([01 §3–§4](detailed-design/01-arena.md#3-stats-and-modifier-pipeline)) |
| ✅ | **Shape variety** — square, triangle, pentagon and alpha pentagon, differing in size, health and experience, spawned by weight. Kind travels on the wire as the static entity's subtype |
| ✅ | **Balance is data**: `Content` (`StatTable`, `LevelTable`, `ShapeTable`) rather than constants in the collision loop |
| ✅ | **Tank classes and the barrel model, first tier** (2026-09-27): Basic, and at level 15 Twin, Sniper, Machine Gun and Flank Guard, as a content table; barrels with angle, offset, delay, bullet multipliers and spread, fired in volleys; `ChooseClass` over the real protocol; a Sniper's longer view; bots choose too. The class and the exact bullet speed on the wire, as protocol 2 ([01 §4](detailed-design/01-arena.md#the-barrel-model-and-the-first-tier-designed-2026-09-27-plan-item-5)) |
| ✅ | **The whole tree** (2026-09-28, plan item 5): tiers 30 and 45, 49 classes; drones, traps, minions and missiles as units, sent by the server ([D-9](architecture/03-decision-log.md#d-9--deterministic-entities-are-simulated-by-the-client)); recoil, turrets, body size, hiding, zoom; the class table served by `platform` ([D-24](architecture/03-decision-log.md#d-24--the-class-table-reaches-the-device-from-platform-versioned-by-its-content)) |
| ✅ | Body damage per second of contact against tanks and shapes; tanks are pushed off what they touch; shapes slow down after a knock ([M-8](defects.md#3a-simulation)) |
| ✅ | Burst health regeneration after 30 s without damage (`sim/Recovery`) |
| ✅ | A safe spawn point, clear of tanks and shapes, and 3 s of spawn protection shown to clients (`sim/Spawning`, [01 §7](detailed-design/01-arena.md#7-deaths-respawn-spectate)) |
| ✅ | Team rules for bullets, bodies and spawn points (2026-09-28, [01 §8.3](detailed-design/01-arena.md#83-team-rules-designed-2026-09-28-plan-item-6)) |
| ✅ | The level rebate on respawn (2026-10-01, item 41, [Q-36](requirements/01-scope-and-nfrs.md#7-open-questions)): in the public arena a quarter of the last life's experience, up to level 20's; a made match's respawn at level 1 |
| ✅ | **Equipment's bonus** on a tank's stats (2026-09-30, plan item 15): a whole percent a stat on `TankStats`, applied in `refresh`, from the ticket at every spawn. The modifier list, with operations, sources and expiry, waits for boosts or in-match effects: it would have one kind of source today |
| ⬜ | Content tables are code-defined defaults; loading them from JSON is not built, and not wanted yet ([Q-27](requirements/01-scope-and-nfrs.md#7-open-questions), item 28) |
| ✅ | Modes (2026-09-29, `MatchMode`): the public arena, the duel, team-vs-team, ranked free-for-all and co-op waves with the arena's hunting tanks ([01 §8](detailed-design/01-arena.md#8-match-modes)) |
| 🟡 | Co-op's bosses **built** (2026-10-01, item 29, [Q-28](requirements/01-scope-and-nfrs.md#7-open-questions)): a Guardian at waves 5 and 10, enraged below half its health. **Domination built** (2026-10-01, item 30, [Q-29](requirements/01-scope-and-nfrs.md#7-open-questions)): two teams of three over three dominators. **Tag built** (2026-10-01, item 31, [Q-30](requirements/01-scope-and-nfrs.md#7-open-questions)): a kill converts its victim. **Maze built** (2026-10-01, item 32, [Q-31](requirements/01-scope-and-nfrs.md#7-open-questions)): walls made from a seed on both sides. **The sandbox built** (2026-10-01, item 33, [Q-32](requirements/01-scope-and-nfrs.md#7-open-questions)): a private room, a level and a Guardian at will, nothing published. Co-op's tiers not ([Q-7](requirements/01-scope-and-nfrs.md#7-open-questions), [Q-27](requirements/01-scope-and-nfrs.md#7-open-questions)) |

**Arena (`arena`)**

| | Item |
|---|---|
| ✅ | Raw TCP, varint framing, rate limiting, typed kick reasons |
| ✅ | Interest management, delta snapshots against the last frame sent, in world space ([D-16](architecture/03-decision-log.md#d-16--deltas-are-measured-against-the-last-frame-sent)), handle recycling confirmed by acknowledgement |
| ✅ | Several rooms per process with reserved join slots. The reservation counter exists *because* 200 bots against 4 rooms of 60 admitted 54 and then disconnected them; the largest clean run on record is now **1 200 bots**, none dropped (item 14), and 600 kept through a failover (item 23) |
| ✅ | Ticketed joins; self-announcement to the arena directory; bind and advertise addresses separated |
| ✅ | Per-player tally, match results published, spooled to disk before the network |
| ✅ | Both lifecycles ([D-15](architecture/03-decision-log.md#d-15--the-public-arena-runs-continuously-structured-modes-are-timed-matches)): the public arena runs **open matches** and records one per player's stay; **timed matches** remain for ranked and tournament modes. Long open matches are checkpointed every ten minutes |
| ✅ | `Ping`/`Pong`, `Respawn`, `Lifecycle` (backgrounding parks the tank and stops snapshots) and **`UpgradeStat`** implemented; `ChooseClass` since the tank tree (plan item 5), and `Phrase` since 2026-09-29, heard by the team or by those who see the speaker ([01 §9](detailed-design/01-arena.md#phrases-designed-2026-09-29-plan-item-8)) |
| ✅ | `EVT_STATS` tells a player their own level, experience and points: at once on a level or a spent point, throttled to once a second for experience alone |
| ✅ | Death reaches the client as a snapshot event carrying score and killer, and the server no longer respawns anyone silently — the client asks |
| ✅ | A timed match's end is told: `Kick(6)`, match over, after its result is published (2026-09-27); a stop drains, letting made matches end ([01 §8.6](detailed-design/01-arena.md#86-draining-an-arena-designed-2026-09-29-plan-item-7)) |
| ✅ | Reconnect and resume ([02 §10](detailed-design/02-networking.md#10-session-reconnect-and-app-lifecycle)): a lost connection's stay waits a minute, its tank parked in the world for the first ten seconds; a resume takes over a connection the server still holds; a death while away is told on return. Kept in the arena's memory; an app restarted resumes with the secret it kept on the device ([D-51](architecture/03-decision-log.md#d-51--a-cold-resume-is-the-devices-the-app-keeps-its-stays-secret)), not through the lobby |
| ✅ | Connection limits: `Join` within 10 s, a frame every 30 s, unwritable at most 5 s, kicks that always close ([S-9](defects.md#5-security-and-input)) |
| ✅ | A tank whose input has been silent for a second is parked until input returns; the last input no longer drives it ([P-23](defects.md#2-protocol--the-client-contract)) |
| ✅ | Found by auditing the designs against the code, 2026-09-26, and fixed: a joining player could lose handle 1 (P-15); shapes streamed their spin, a fifth of a calm frame (P-16); levels never updated (P-17); creates re-sent for everything moving (P-18); a hung room kept taking players (T-9); a stopping arena closed sockets bare (P-22) |
| ✅ | Traffic profiles ([02 §8](detailed-design/02-networking.md#8-traffic-profiles), [D-17](architecture/03-decision-log.md#d-17--a-client-is-stepped-down-on-queueing-not-on-round-trip-time-or-writability)): high, mobile and saver, chosen in `Join`, stepped down on a queueing link and back up. Behind shaped links the view a player sees stayed under a second old, against 20 s and growing before. The bytes cap is not built: the metrics that would justify it now exist |
| ✅ | A **respawn or a return from background** keeps the client's view, so its tick, camera and handles carry on (fixed 2026-09-25, [P-6](defects.md#2-protocol--the-client-contract)); handles are keyed by slot and generation, so a slot reused between frames is never passed off as the entity before it ([P-13](defects.md#2-protocol--the-client-contract)) |

**Handoff and platform**

| | Item |
|---|---|
| ✅ | Single-use join tickets, claimed atomically; arena directory with TTL liveness |
| ✅ | Argon2id passwords, sessions with jittered TTL, register/login/logout, bans |
| ✅ | A password is rehashed at its next login when the Argon2 cost is raised; before, a higher cost protected new passwords only |
| ✅ | `JoinService`: session → profile → ticket → arena endpoint |
| ✅ | **HTTP API** — accounts, sessions, match requests, on virtual threads. Verified with `curl` across four processes: register → login → ticket pointing at a live arena |
| ✅ | **Leaderboards** — best single-match score over all-time, daily and ISO-week windows, written by `worker` as `ZADD … GT` and read over `GET /v1/leaderboards/{board}[/me]`. Idempotent by arithmetic rather than by bookkeeping ([04 §7](detailed-design/04-platform-services.md#7-leaderboards)) |
| ✅ | **Matchmaking, first slice: the ranked duel** (2026-09-27, [04 §4](detailed-design/04-platform-services.md#the-first-slice-a-ranked-duel-end-to-end-designed-2026-09-27-plan-item-6)): `/v1/queue` and the lobby's `queue.join`; a matcher under a lease pairing by rating with a widening window; tickets naming the match, whose room the arena makes from the first of them ([D-20](architecture/03-decision-log.md#d-20--a-matchs-room-is-made-by-its-first-ticket-not-by-a-command)); `evt.match.found`; the duel's rules and walkover; Elo applied by `worker` under the lock (V4) |
| ✅ | **Parties and team-vs-team** (2026-09-29, [04 §4](detailed-design/04-platform-services.md#the-second-slice-parties-and-team-vs-team-designed-2026-09-28-plan-item-6)): parties of up to three in the store, queued whole by their leader; a matcher that fills two teams for any queued mode; three against three with team rules; a team Elo on the teams' means (V5) |
| ✅ | **A confirm step, ranked free-for-all and co-op** (2026-09-29, [04 §4](detailed-design/04-platform-services.md#the-third-slice-a-confirm-step-designed-2026-09-29-plan-item-6)): every match found asked of its players first ([D-27](architecture/03-decision-log.md#d-27--a-match-found-asks-every-player-before-it-is-made)); eight each for themselves, rated pairwise ([D-28](architecture/03-decision-log.md#d-28--a-free-for-all-is-rated-pairwise-each-player-against-each-other)); three against the arena's waves. Built on recommendations open with the user as Q-5 to Q-7 |
| ✅ | **Shop** — catalogue, inventory and purchases over the API, charged once per client key however often it is retried, a retry answered as its first attempt was ([04 §8](detailed-design/04-platform-services.md#8-economy-shop-inventory-and-equipment)). Built ahead of Phase 5 at the owner's request, 2026-09-26. The release's catalogue sells equipment for coins, and boosts and skins for gems (items 68 and 75); an offer may be priced in coins or gems (item 68 (b)). Limited stock is not built |
| ✅ | Tournaments, team matches, friends, presence, notifications, each in a first slice (items 17–21, Q-12's order). **Teams' first slice built 2026-09-30** (item 17): created, joined by invitation, ranked, handed over, disbanded. **Equipment built 2026-09-30** (item 15): items, wearing, the bonus in the ticket and on the tank. **Boosts built 2026-09-30** (item 16): activated once per key, raising a match's experience or coins. **Tournaments' first slice built 2026-09-30** (item 18): an operator's duel tournaments, registered for, seeded, run round by round and paid by `worker`, drilled to the end. **Team matches built 2026-09-30** (item 19): two teams, three a side, the team rated. **Teams' tournaments built 2026-09-30** (item 20): a roster of three a team, drilled to a finish. **Friends, presence and the inbox built 2026-09-30** (item 21): requests, blocks, presence from the lobby connection, an inbox of references |
| ✅ | **Guest accounts and upgrading one** (2026-09-30, item 22, [04 §1](detailed-design/04-platform-services.md#1-auth-and-sessions)): made with nothing asked, in again by a key the device keeps, upgraded to a username and password as the same player |

**Persistence and worker**

| | Item |
|---|---|
| ✅ | Baseline schema; pool, retrying transactions, idempotent match results, economy with a ledger |
| ✅ | Accounts: registration decided by the unique index, not a prior read |
| ✅ | Result pipeline: spool → queue → worker → MySQL, exactly-once effect, dead-lettering, crash recovery |
| ✅ | Reward rules in one place, with a minimum playtime |
| ✅ | Ledger reconciliation once a day across the fleet, reported on every worker's metrics ([05 §9](detailed-design/05-worker-and-events.md#9-scheduled-work)). Its cost at production size is not measured yet |
| ✅ | The result queue on a stream, read by one group, a retired worker's entries taken over, 24 hours kept, drilled from the release (item 10) |
| ✅ | Leaderboard projection written after the commit, on every delivery including redeliveries — so a crash between the two repairs itself instead of losing a ranking |
| 🟡 | Tables for teams, tournaments, friends: **built** (V10–V14, 2026-09-30; in the baseline since D-75). Partitioning ruled out by measurement and the tables' growth watched against its triggers (item 76 (b), D-72); archival not built, its trigger in [06 §9](detailed-design/06-persistence-mysql.md#9-growth-and-retention) |
| ✅ | Retention: match history kept 90 days and purged daily by every worker; a result more than 30 days late is refused, so a purge can never let one pay twice ([06 §9](detailed-design/06-persistence-mysql.md#9-growth-and-retention)) |
| ✅ | Tournament scheduling: **built** (`worker/TournamentScheduler`, items 18 and 20); boost refunds settled, none to make (item 24, Q-23); analytics' first slice, whether players come back, built (item 25, Q-24); by feature (item 27), and the funnel and the guests measured (item 76 (c)) |
| ✅ | Leaderboards rebuilt from MySQL after a store loss, or to rank results applied while the store was down: safe to run any time, rehearsed with the shipped unit ([05 §8](detailed-design/05-worker-and-events.md#8-rebuilding-after-a-store-loss)) |

**Gateway, client, operations**

| | Item |
|---|---|
| ✅ | `gateway` — WebSocket lobby, auth handshake, match requests, the queue, connection registry and rate limiting all work end to end; a replaced connection is told so, and a silent one is closed ([P-19, P-20](defects.md#2-protocol--the-client-contract)). **Pushes from any process are built** (2026-09-27): `evt.match.found`, `evt.match.ready`, `evt.queue.update`, `evt.party.*` and, since 2026-10-01, `evt.team.update`; parties and the confirm step's answers are forwarded (2026-09-29); a flooded connection is closed as the protocol closes ([P-31](defects.md#2-protocol--the-client-contract)). A slow connection's pushes are held, then a resync (2026-10-01, item 35) |
| 🟡 | Unity client — **Claude writes it** (decided 2026-09-27, [D-19](architecture/03-decision-log.md#d-19--the-client-is-an-engine-free-core-and-a-thin-unity-layer)); designed in [08](detailed-design/08-client.md). **Built: the core's wire, world, match connection, API and lobby** ([client/](../client/README.md)): every field of the golden vectors, and a headless client that goes the whole path against the real stack — log in, lobby, match, resume, leave, the reward in the inventory — plaintext and TLS. The own tank's prediction (item 70): held bit for bit to the room's own steps, and live within the wire's rounding at the median. The Unity layer, deferred by the owner on 2026-09-27 and brought back on 2026-10-04 (Q-51), **written as scripts** (item 77): what it draws, how it steers and the account kept are in the core and tested; the package's scripts compile against stubs. **Gap: nothing has run in Unity or on a phone** |
| ✅ | Golden vectors regenerated for the event framing (`realistic_mobile` is now 119 bytes, up from 117). The C# reference decoder compiled unchanged and applied all four vectors on 2026-09-27, with the .NET 8 SDK installed for the client; the client core applies every field of every vector, the motion events' too (item 70) |
| ✅ | Release build, four sandboxed systemd units, nginx configuration, runbook with real commands, all run for real ([operations/01](operations/01-deploy.md)) |
| ✅ | Metrics on every process; MySQL and store backups with restore drills that have actually restored |
| ✅ | Every process logs through a queue on its own thread: a journal that stops reading costs lines, never a tick ([T-10](defects.md#4-concurrency)) |
| ✅ | TLS for match traffic, and MySQL set up with TLS from nothing, both run for real. The certificate for players waits for a domain ([S-6](defects.md#5-security-and-input)) |
| ✅ | HTTPS for login, purchases and the lobby ready for its certificate: nginx run for real in front of the release, and one script that checks, installs and renews a certificate for everything a machine serves, run from placeholder to renewal ([operations/01 §11](operations/01-deploy.md#11-the-certificate)). What waits is two choices: the domain and the CA |
| ✅ | Binlogs streamed off the database's machine as they are written, the nightly dump taken from there over TLS, and a restore onto another server from those copies rehearsed ([operations/01 §10](operations/01-deploy.md#10-copies-off-the-databases-machine)) |
| ✅ | The store's backups: the dead list, the one thing in it neither disposable nor rebuilt from MySQL, copied off by hand when it alerts, run against j-redis 2.0 ([runbook §3](operations/02-runbook.md#3-per-component-procedures)). No copy on a timer: it would recover nothing a store loss costs ([D-31](architecture/03-decision-log.md#d-31--the-store-is-not-copied-off-its-machine-on-a-timer-before-replication)), and since 2026-09-29 each store has a replica on another machine (D-34) |

**Measurements still owed**

| | Item |
|---|---|
| ✅ | Tick cost measured on the development VM; login cost measured (88 ms per password) |
| ✅ | **NFR-2 measured at design density** (2026-09-26): 150 bots in one room, map 5700, 1 500 shapes, every bot firing, through the whole stack. 15.06 snapshots a second each, frames of 79 bytes, **1.17 KB/s of payload, ~2.24 KB/s on the wire** against `mobile`'s 2.85; with input at the contract's ten packets a second, **about 11 MB an hour** against NFR-2's 15. Tick p99 14.8 ms, no overruns, on the loaded VM. A mean, from random bots: a real fight clusters, which is what `backend_arena_snapshot_bytes_total{profile}` will show. The bot tool then counted from each bot's first frame, while others were still connecting: hence 15.06 a second against the 15 sent, so these figures are 0.4 % high, inside their rounding; it now measures only once every bot is in |
| ⬜ | Q-1 actual uplink — can make the capacity target physically impossible |
| ⬜ | Q-2 fraction of players in battle at once |
| ⬜ | Q-3 tick cost on production hardware with CPU pinning |
| 🟡 | Q-4 map density versus the 12 visible tanks the traffic budget assumes. 150 spread at random on the design map put 150 × (1600/5700)² ≈ 12 in a view, and measured 2.24 KB/s; how real players cluster is still unknown |

### What to do next, in order

Re-ordered on 2026-09-23 by the [defect register](defects.md). The previous
order was driven by which feature was most interesting; this one is driven by
what would invalidate the most work if it stayed wrong.

Items 1–5 of the old list are done and are kept in
[§Completed](#completed-2026-09-23) below so the dates are not lost.

---

#### 1. ~~Make the wire a contract~~ — **done 2026-09-25**

The gap is closed. `protocol.SnapshotReader` decodes a frame and
`protocol.ClientWorld` accumulates one into a handle table and world-space
positions — the reference the Unity client is a port of. Three tests now bind the
chain: the real encoder's bytes decode to the server's own state
(`SnapshotRoundTripTest`), that decoder reads the golden vectors
(`GoldenVectorTest`), and the encodings are pinned at their boundaries
(`WireFormatTest`). `protocol/` went from zero tests to 19.

**Five of the six hidden defects were found by running it**, each confirmed by
watching the test fail first:

- **P-1** the delta baseline. The server measured deltas from the last
  *acknowledged* frame while the client accumulated onto its current value, so
  every entity sat a snapshot's travel from where the server had it and the error
  grew while an ack was outstanding. Now measured from the last frame **sent**, in
  world space ([D-16](architecture/03-decision-log.md#d-16--deltas-are-measured-against-the-last-frame-sent)).
- **P-3** self-identity. The welcome said "you are handle 1" while the encoder
  *excluded* the client's own tank, so handle 1 was the nearest opponent and a
  client could not learn its own health at all.
- **P-2** bullet speed — one table, in one unit, used by both sides. And while
  fixing it: bullets were being **knocked sideways** by collisions they survived,
  31 of 299 measured, which makes a "predictable" entity unpredictable. They are
  no longer knocked.
- **P-5** two coordinate frames under one field; **P-7/P-8** rendering
  conventions the document had wrong; **P-10** an unvalidated ack; and a living
  tank that encoded as dead.

P-6 (2026-09-25) and P-9 (2026-09-26) are fixed, with P-13, which P-6 exposed: the
snapshot now carries everything a client needs to reconcile its own tank, and
keeps doing so through death, respawn and background. `common/` has had tests
since 2026-09-26 (`SecretsTest`, `MetricsTest`, `LoggingTest`, `ArgumentsTest`).

**204 tests, 0 warnings.**

#### 2. ~~Make the durability guarantees true~~ — **done 2026-09-25**

All five guarantees now hold, and the bar the plan set — *the kill-and-restart
drill loses nothing* — was met twice, across five processes, with forty
connected players:

| Drill | Result |
|---|---|
| **Deploy:** arena sent SIGTERM mid-play | 40 published, 0 left on disk, **40 of 40** players' results in MySQL |
| **Outage:** store killed, *then* arena stopped, then both restarted | 40 on disk with nowhere to send them; replayed on restart; **40 of 40** in MySQL; the worker lived through the store vanishing |

Every fix was watched failing first, and removing each one fails its test:

- **Exactly-once** — `INSERT IGNORE` read a missing player as "already applied"
  and clamped impossible values while paying on the unclamped ones. Now a plain
  insert where only a duplicate key means "applied", and values are checked
  against their column widths first.
- **Deadlock** — the documented lock ordering did not prevent it: 24 overlapping
  matches failed on 3 runs of 3. Players are now locked up front.
- **"Never lost"** — the spool held only the result being worked on, the rest
  waited in memory behind a push that retried for ever, and `close()` abandoned
  them. The disk is now the queue, and shutdown drains it.
- **A deploy** dropped up to ten minutes of every connected player's progress.
  Rooms now publish what their players are owed, and are waited for.
- **The worker** parked failures until a restart, died on one bad
  acknowledgement, could re-apply another worker's work, and dead-lettered
  results from a newer arena. All four fixed.

One thing the drill caught that the unit tests had not: the first version of the
fix *spooled* everything on shutdown but delivered only 1 of 40, leaving 39 on
the disk of an arena that might be being retired. Shutdown now delivers the
backlog when the store will take it, and falls back to disk only when it won't.

Nothing is open in the data section. Defects D-11 (timed-match reconnect), D-12
(account level), D-13 (one path moves a balance) and D-14 (retention) were
fixed on 2026-09-26, with two purchase bugs defect D-13 exposed (D-17, D-18) and
a view reset at the timed-match boundary defect D-11 exposed (P-14). The "store call with no
timeout" listed here was re-measured on 2026-09-26 and is not a defect: the
j-redis client bounds every call at 2 s.

#### 3. ~~Decide who writes the client.~~ — **decided 2026-09-27: Claude writes it**

The owner's decision. The client is an engine-free core, built and tested against
the real server on the development machine, and a thin Unity layer
([D-19](architecture/03-decision-log.md#d-19--the-client-is-an-engine-free-core-and-a-thin-unity-layer),
[08-client](detailed-design/08-client.md)). **It comes next, before item 5**:
phases 0 to 2 each need a real client, and every feature added before one exists
is more that it must catch up with. Its order of work is 08 §7.

What the item said before the decision:

**Still nobody, and it is still the item most likely to decide the schedule.**
It has been listed as overdue since the plan was written. Every feature added
since then has increased what a client must implement.

This is not a scheduling problem any more, it is a sequencing one: after item 1
the protocol is executable and a client author has something that cannot lie to
them. Before item 1 they would have hit P-1 and P-3 on their first afternoon.

Options worth costing: hire it; build it; or cut v1 to something a solo
developer can ship a client for. **Pick one before starting item 5** — the
product-depth work is months and none of it is worth anything without a client.

#### 4. ~~Close the security gaps before anything is exposed.~~ — **done**, but TLS's certificate, moved to item 7

Not urgent for development; absolutely blocking for a closed alpha.

- ~~**Validate display names** ([S-1](defects.md#5-security-and-input)).~~
  **Done 2026-09-25.** RFC 8266 plus the UTS #39 mark checks, refused rather
  than repaired, and tested in both directions: the hostile cases, and real
  names in eleven scripts, including the Indic, Thai, Arabic and Hebrew ones
  built from combining marks. A first cut of the rules refused a Hindi word
  and accepted a blank name made of Hangul filler; both are now tests.
- ~~**Rate-limit login** ([S-2](defects.md#5-security-and-input)).~~ **Done
  2026-09-25**, in `platform` rather than the gateway the design named, because
  login never passes through the gateway. Also done: a store outage answers
  503 rather than 500 (S-4, which was misdiagnosed as a hang), and a permanent
  ban no longer lapses (S-5).
- ~~**Configuration and secrets** ([S-3](defects.md#5-security-and-input)).~~
  **Secrets done 2026-09-25**: the database password comes from a file or the
  environment, never argv ([operations/01 §2](operations/01-deploy.md#secrets)).
  Found on the way: a process that failed while starting stayed up with nothing
  bound, invisible to systemd. Fixed in all four mains. The rest of
  configuration (ports, hosts, pool sizes) was left to item 7, which found on
  2026-09-29 that ports and hosts were already named in each process's env
  file, the units mapping them to arguments, and made the pool size a variable
  too, `BACKEND_DB_POOL_SIZE` ([operations/01 §2](operations/01-deploy.md#2-conventions)).
- **TLS** ([S-6](defects.md#5-security-and-input)): the design no longer claims
  it exists. **Moved to item 7**: it needs a domain, arena hostnames and a
  certificate source, and is built with the nginx configuration and units that
  need the same things. Bandwidth is not the obstacle; the budget already
  includes it.

#### 5. ~~Tank classes and the barrel model.~~ — **done 2026-09-28**

What `ChooseClass` needs, and what turns one tank into a roster. Gated on item 3:
a class tree is a lot of client work, and committing to it without knowing who
writes the client is how the backend gets further ahead of its consumer.

**The model and the first tier are built, 2026-09-27** ([01 §4](detailed-design/01-arena.md#the-barrel-model-and-the-first-tier-designed-2026-09-27-plan-item-5)). A class is a
row of data: barrels, reload and view. The wire changed with it, to protocol 2:
a bullet's speed travels exactly, because a barrel's multiplier made speeds the
old table of eight could not hold. What remains is content and the kinds of
entity a class can make: tiers 30 and 45, drones and traps (which are steered,
so not predictable, and cost what a tank costs on the wire), and recoil. None of
it changes the model; the next tier is rows in the table.

**Second slice designed 2026-09-27: the tier at level 30**
([01 §4](detailed-design/01-arena.md#the-second-tier-sizes-recoil-traps-and-drones-designed-2026-09-27-plan-item-5)):
bullet sizes, recoil, traps and drones, and protocol 3 for them (a bullet's
radius in its create; a `UNIT` kind, streamed, for what cannot be extrapolated).
Drones go a reach along the aim
([D-21](architecture/03-decision-log.md#d-21--drones-go-a-fixed-reach-along-the-aim)).
First because it is the part that could break the wire and its budget; tier 45
after it.

**Built 2026-09-27**, in four parts, each with its tests and mutation checks:
the bullet classes, recoil, sizes and protocol 3; traps; drones; and the
measurement. **The wire's budget holds** (92 bytes a frame, 8.4 MB an hour);
**the tick's does not, in the worst case**: 150 level-45 tanks of the tier, all
firing, reach 2.9 ms p99 on the development VM against NFR-1a's 2 ms, while a
room grown from level 1 is 1.2 ms. Recorded against Q-3. Found on the way: the
shape mix drifted toward alpha pentagons (M-12), now fixed.

**Third slice designed 2026-09-28: the tier at level 45**
([01 §4](detailed-design/01-arena.md#the-third-tier-turrets-bodies-hiding-and-minions-designed-2026-09-28-plan-item-5)),
30 classes. Because the tick is already over budget in the worst case, the
tier may add kinds of projectile but not more of them
([D-22](architecture/03-decision-log.md#d-22--the-third-tier-adds-kinds-of-projectile-not-more-of-them)):
no class keeps more alive than the second tier's most, and a test holds it.
Protocol 4 gives a unit's create its radius, which protocol 3 left out.
Turrets aim themselves; the Smasher line has no barrels and its own caps on
points; a hidden tank is not sent to anyone else
([D-23](architecture/03-decision-log.md#d-23--a-hidden-tank-is-not-sent));
squares become the Necromancer's drones, and the Factory's minions shoot.
Built in seven parts, (a) to (g), the last a measurement.

**Built 2026-09-28**, in those seven parts, each with its tests and mutation
checks (5, 12, 11, 9, 10 and 13 deliberate faults, every one caught: two only
after a test was added for each, and four gaps found by review and tested before
the run): protocol 4; the rows that needed
nothing new, and what fires without the trigger; turrets; the Smasher line;
hiding; the Necromancer and the Factory. **45 classes in all.** The measurement:
**the tier costs what the second did** (2 193 bullets in flight against 2 247,
the same tick within the noise), so the worst case is where it was, over NFR-1a
on this VM and open under Q-3. Found on the way: P-30 (a unit's size), M-13
(a test timeout that could not stop a loop), M-14 (the tick allocated every
time, which only the benchmark was watching; a test holds it now), T-14 (a test
read the room off its thread), and P-31 (open, with item 7).

**What remains in item 5** is four classes that each need a mechanism of their
own, and the client's side: the Mega Smasher (a bigger body, where every rule
takes a tank's radius to be 30), the Battleship (drones with a lifetime and no
cap), the Skimmer and the Rocketeer (projectiles that fire), the Predator's zoom
(no input for it), knocking traps, and the client drawing classes from a table
on the device.

**Fourth and last slice designed 2026-09-28: the rest of the tree**
([01 §4](detailed-design/01-arena.md#the-rest-of-the-tree-designed-2026-09-28-plan-item-5)),
in seven parts: traps knocked; drones with a lifetime and the Battleship;
missiles that fire, the Rocketeer and the Skimmer; a body of its own size, the
Mega Smasher; the Predator's zoom, one input flag; the class table served by
`platform` and versioned by its content
([D-24](architecture/03-decision-log.md#d-24--the-class-table-reaches-the-device-from-platform-versioned-by-its-content)),
since `Welcome.contentVersion` has been a constant 1 through three tiers; and the
measurement. The tree is then whole; only the Unity layer's drawing of it
remains, deferred with that layer.

**Built 2026-09-28**, in its seven parts, each with its tests and mutation
checks (4, 5, 8, 4, 6 and 6 deliberate faults, every one caught; three only
after a test was added for each): traps knocked, the Battleship's drones with a
lifetime, the Rocketeer's and the Skimmer's missiles, the Mega Smasher's body,
the Predator's zoom, and the class table served by `platform` and named by the
`Welcome`, which the live drill checks across processes. **Item 5 is done: 49
classes, the whole tree.** The measurement: 13 % more at p50 than the third
tier, from drones, which D-22 does not count (the Battleship keeps 16); open as
a balance question with Q-3. Found on the way: T-15 (a test read a count before
the server made it), and P-31 twice more (open, with item 7).

#### 6. ~~Matchmaking.~~ — **done 2026-09-29**, its numbers open as Q-5 to Q-7

Unlocks both timed matches and the gateway's unbuilt pushes —
`evt.match.found` has nothing to announce until a queue exists.

**First slice designed 2026-09-27: a ranked duel, end to end**
([04 §4](detailed-design/04-platform-services.md#the-first-slice-a-ranked-duel-end-to-end-designed-2026-09-27-plan-item-6)).
A queue and a matcher in `platform`, pairing by rating; a room the arena makes
from the first ticket that names the match
([D-20](architecture/03-decision-log.md#d-20--a-matchs-room-is-made-by-its-first-ticket-not-by-a-command));
`evt.match.found` pushed through the gateway; the duel's rules; Elo applied by
`worker`, with the V4 migration. Built in that order, each part committed with
its tests: the push route, the queue and matcher, the arena's match rooms, the
rating, then the client core and a drill of two players. Parties, a confirm
step and every other mode come after.

**Built 2026-09-27**, each part with its tests and mutation checks (push route
7 of 7, queue and matcher 20 of 20, rooms 15 of 15, rating 9 of 9), and driven
live by two headless clients from the release.

**Second slice designed 2026-09-28: parties and team-vs-team**
([04 §4](detailed-design/04-platform-services.md#the-second-slice-parties-and-team-vs-team-designed-2026-09-28-plan-item-6)).
A party has nothing to do without a mode with teams, so the two come together:
parties of up to three in the store, queued by their leader as one entry
([D-25](architecture/03-decision-log.md#d-25--a-party-lives-in-the-session-store-and-only-its-leader-queues));
a matcher that fills two teams for any queued mode, a duel's being teams of one;
three against three, with the team rules the simulation never had
([01 §8.3](detailed-design/01-arena.md#83-team-rules-designed-2026-09-28-plan-item-6));
and a team Elo on the teams' means
([D-26](architecture/03-decision-log.md#d-26--a-team-is-rated-by-its-players-mean-each-moved-by-their-own-k)),
V5. Six parts, the last a live drill of six players. A confirm step and the
other modes come after.

**Progress**, each part committed with its tests and mutation checks: (a) the
team rules, (b) the mode and its tally, (c) the team rating, V5, built
2026-09-28; (d) parties, built 2026-09-29, the store, the service, the routes and
the gateway's `party.*` (35 mutants, 35 killed once five survivors had their
tests: an invitation spent on accepting, the pointer of the one left in a party
that is no more, the retry of an overtaken change, and two lifetimes); (e) the queue
of parties and the matcher with teams, built 2026-09-29 (33 mutants, 32 killed;
the watch on the party during a join survives, a race no test drives, argued in
04 §4). The matcher was measured before it was committed: its first version
sorted the whole window for each entry and took 5.3 s a round for a queue of
5 000; one pass keeping the ten best takes 100 to 180 ms; (f) the client core's
party messages and the live drill of six, 2026-09-29: a party of three and three
alone matched into one room, the party one team, played to the clock, the
result in MySQL. **The second slice is built.**

**Third slice designed 2026-09-29: a confirm step**
([04 §4](detailed-design/04-platform-services.md#the-third-slice-a-confirm-step-designed-2026-09-29-plan-item-6)).
A match found asks every player, who has ten seconds to accept; one decline or
silence calls it off, locks that player out of the queue for a minute, and puts
everyone else back where they were
([D-27](architecture/03-decision-log.md#d-27--a-match-found-asks-every-player-before-it-is-made)).
Whether to have the step, and its numbers, is the user's
([Q-5](requirements/01-scope-and-nfrs.md#7-open-questions)); built on the
recommendation meanwhile. Three parts: the store and the matcher, the API and
the lobby, the client and a drill. Ranked FFA and co-op come after, each with
its own questions.

**Progress**: (a) the store and the matcher and (b) the API and the lobby,
built 2026-09-29 and committed together, since a matcher that asks with no way
to answer would call every match off (21 and 12 mutants, all killed, after two
survivors named dead code and one redundant check, removed, and two named
missing tests). A test caught the first design's flaw: a player yet to answer
when another declined was counted silent, and locked. The live drill caught the
second: six answering together overtook one another on one key, and the sixth
was refused ([T-16](defects.md#4-concurrency)); each answer is now the player's
own. (c) the client core's `AcceptMatch` and `DeclineMatch`, and the drill:
every queued scenario accepts, and `decline` declines, 2026-09-29. **The confirm
step is built.** Next in item 6: ranked FFA and co-op, each a set of product
questions first.

**Fourth slice designed 2026-09-29: ranked free-for-all**
([04 §4](detailed-design/04-platform-services.md#the-fourth-slice-ranked-free-for-all-designed-2026-09-29-plan-item-6)).
Eight players, four minutes, placed by score, rated pairwise
([D-28](architecture/03-decision-log.md#d-28--a-free-for-all-is-rated-pairwise-each-player-against-each-other));
its numbers are the user's ([Q-6](requirements/01-scope-and-nfrs.md#7-open-questions)),
built on the recommendation meanwhile. The matcher fills more than two sides,
and a mode without teams gives every player team 0, the duel's too. Four
parts: the mode, the matcher, the rating (V6), the client and a drill of eight.
Co-op comes after, as its own questions.

**Progress**, 2026-09-29: (a) the mode, placed by score, team 0 in every mode
without teams (3 mutants of 3); (b) the matcher for more than two sides (8 of
8); (c) the rating, V6, one Elo rule over placements for every rated shape (12
of 13, the 13th equivalent); (d) the drill of eight: matched, played to the
clock, rated. **Ranked free-for-all is built.**

**Fifth slice designed 2026-09-29: co-op waves**
([04 §4](detailed-design/04-platform-services.md#the-fifth-slice-co-op-waves-designed-2026-09-29-plan-item-6)).
Three players against ten waves of the arena's own tanks, which hunt; the
dead back at the next wave; a wipe, the tenth wave or ten minutes ends it;
unrated. The smallest co-op that plays, with bosses, tiers and a reward for
waves left for the user's answer ([Q-7](requirements/01-scope-and-nfrs.md#7-open-questions)).
Four parts: the hunting tank, the mode and its waves, the matcher for one
side, the client and a drill.

**Progress**, 2026-09-29: (a) the hunting tank (11 mutants of 11); (b) the mode
and its waves (14 of 15, the 15th equivalent); (c) the matcher for one side and
an unrated queue (6 of 6); (d) the drill: a pair and one alone, matched as one
team, hunted down by the first wave, the wipe ending it. **Co-op's first slice
is built**, and with it every queued mode the plan names. Item 6 is done, but
for what waits on the user: the confirm step's numbers (Q-5), ranked
free-for-all's (Q-6), and co-op's rules, bosses and tiers (Q-7).

#### 7. Concurrency, operations and TLS. *(continuous)*

**[P-31](defects.md#2-protocol--the-client-contract) fixed 2026-09-29**, found
2026-09-28 — a flooded client was not always told why it was cut off, which
broke seven full builds in two days. The gateway now closes as the protocol
does: the refusal, a Close frame, then its input drained until the client's
Close or two seconds. It could not be reproduced on demand, so the builds that
follow are the proof.

**Arena drain designed 2026-09-29**
([01 §8.6](detailed-design/01-arena.md#86-draining-an-arena-designed-2026-09-29-plan-item-7),
[D-29](architecture/03-decision-log.md#d-29--an-arena-drains-before-it-stops-and-a-match-cut-short-is-not-rated)),
unblocked by item 6's timed modes: a stop withdraws the arena from the
directory, hands its public rooms' players back, lets its made matches end, for
up to eleven minutes, then stops; a match still cut short is paid and not
rated. Parts: (a) the drain in the arena; (b) a cut-short result unrated; (c)
the unit, the runbook and a live drill. (A draining mark in the directory was
dropped: the stop already withdraws the arena from it.) **Built 2026-09-29**,
all three (7 mutants of 7), and drilled: an arena stopped sixteen seconds into
a duel played it out, had it rated, and exited, `drained in 163 s`.

**Admin API, first slice, designed 2026-09-29**
([04 §10](detailed-design/04-platform-services.md#the-first-slice-designed-2026-09-29-plan-item-7),
[D-30](architecture/03-decision-log.md#d-30--admin-calls-are-audited-in-mysql-until-there-are-streams)):
FR-9's operator, on loopback behind a shared secret: the arenas, and a ban that
ends every session at once, each call audited in MySQL (V7). Parts: (a) the
session index and revocation, with the gateway's close; (b) the listener, its
secret and the audit; (c) the calls, and the runbook. Rooms wait for a command
channel to the arenas, the second slice. **Built 2026-09-29**, all three (22
mutants of 22), and drilled: a player in the lobby banned over the admin API was
told, stayed out, could not log in, and could once unbanned.

**Admin API, second slice, designed 2026-09-29**
([04 §10](detailed-design/04-platform-services.md#the-second-slice-rooms-designed-2026-09-29-plan-item-7)):
the rooms, closing one, and taking a player out of a match, told to the arenas
on a channel each subscribes to; `Kick(7)`, removed, a new reason an older
client reads as the server's fault; a ban now kicks too. Parts: (a) the arena's
channel; (b) the admin calls; (c) the client and a drill. **Built 2026-09-29**
(17 mutants of 17), and drilled.

**The store's backups, decided 2026-09-29**
([D-31](architecture/03-decision-log.md#d-31--the-store-is-not-copied-off-its-machine-on-a-timer-before-replication)):
not copied off its machine on a timer before replication. Key by key, what a
lost store costs is results in flight, younger than any copy, and the dead
list; the runbook now copies the dead list out when it alerts, and puts it back
on the queue after a fix (run against j-redis 2.0). Checking that alert found
the runbook's alert table short of the deploy guide's list, and the guide's
metrics table short of today's metrics
([O-1](defects.md#6-operations)); both corrected, and three compiler warnings
added this week removed.


The concurrency defects ([T-1…T-7](defects.md#4-concurrency)) are all closed as of
2026-09-26: T-1, T-2 and T-6 reproduced by tests first; T-4 and T-7 fixed by
construction, with the argument in the code. Operations is partly done: a release
build, four sandboxed systemd units and the nginx configuration were written on
2026-09-26 and run for real ([operations/01 §7](operations/01-deploy.md#7-installing-a-machine)).
Metrics followed the same day: a Prometheus scrape per process, on loopback,
checked value by value against a live run. Then the MySQL restore drill: a
point-in-time restore that comes back row for row. Its first three attempts each
failed silently, and would have in production. And the store's drill, which
brought the all-time leaderboard back score for score from a backup taken while
the store served. And D-7's split of j-redis into `session` and `events`,
which the code had never done. Then arena TLS, run from the release and under
systemd ([operations/01 §8](operations/01-deploy.md#8-tls-for-match-traffic)),
and with it the connection limits the design had and the arena had never
enforced ([S-9](defects.md#5-security-and-input)): a connection that never
joined, a phone that vanished in the background, and a kicked client that had
stopped reading all kept their sockets. Then MySQL's setup with TLS, run from
nothing ([operations/01 §9](operations/01-deploy.md#9-mysql)), which found that
the documented database URL could not connect and that backups were readable by
every local user (S-10). Then HTTPS for login and purchases, with nginx run for
real and a certificate taken from placeholder through renewal under connected
players ([operations/01 §11](operations/01-deploy.md#11-the-certificate)): it
found port 80 redirecting logins, and expired intermediates going unseen. Then
logging, which the design had queued and the code wrote directly: a journal that
stopped reading froze a room, its watchdog and its shutdown, drilled and fixed
([T-10](defects.md#4-concurrency)). Left:
the domain and the CA (S-6, decisions, not code); the database's
dumps and binlogs now leave its machine as they are written, and a restore from
those copies alone was rehearsed onto another server. The store's backups do
not leave its machine, by decision
([D-31](architecture/03-decision-log.md#d-31--the-store-is-not-copied-off-its-machine-on-a-timer-before-replication)):
the boards rebuild from MySQL, and the dead list is copied out when it alerts. Then an audit of the designs against the code found 27
mismatches. Ten were defects in the code, each reproduced by a test and fixed
(P-15 to P-22, T-9, D-20); three were comments or a metric label saying the wrong
thing; one, body damage, is recorded for the simulation work (M-8); the rest were
documents claiming more than was built, now corrected, with the gaps that matter
listed above. Operations —
systemd units, nginx, configuration, metrics, a backup drill — is a prerequisite
for item 4's "closed alpha" and nothing before it.

**What is left of item 7 waits on something else** (2026-09-29): the
certificate on the domain and the CA, alert thresholds on Q-3. (The store's
promotion, which waited on j-redis 2.2, is done: item 12.) It stays open, and
continuous.

#### 8. ~~Fixed-phrase chat, the rest of Phase 3.~~ — **done 2026-09-29**

**Added 2026-09-29**, when item 7 had nothing left that was not waiting. The
list above ended there; the roadmap ([§3](#3-phases)) orders what follows.
Phase 3's exit names multiple rooms and arenas, matchmaking, accounts,
progression, leaderboards, the gateway, **fixed-phrase chat** and operations'
basics, and all but the chat are built: the arena read `Phrase` and dropped
it, and `Welcome.phraseListVersion` was a constant 1. FR-11 and
[D-14](architecture/03-decision-log.md#d-14--communication-is-a-fixed-phrase-list-never-free-text)
make it the only way players speak, so it is v1 scope already agreed, not a
new feature. Phase 4 (streams, replication, the second machine) comes after.

**Designed 2026-09-29**
([01 §9](detailed-design/01-arena.md#phrases-designed-2026-09-29-plan-item-8),
[D-32](architecture/03-decision-log.md#d-32--a-phrase-is-heard-by-the-speakers-team-or-by-those-who-see-them)):
the list is content, served by `platform` and versioned by its hash as the
class table is; in a mode with teams the team hears a phrase, in one without,
those who see the speaker. The list and the audience are open with the user as
[Q-8](requirements/01-scope-and-nfrs.md#7-open-questions), built on the
recommendation. Parts: (a) the table, served, and its version in `Welcome`;
(b) the arena: the rate, the audience, the event; (c) the client and a drill.
Party phrases in the lobby are a second slice.

**Built 2026-09-29**, all three (29 mutants of 29 in the backend, 27 at the
first pass; 7 of 7 in the client), and drilled: a player in the public arena heard their own "Hello!" by
their own handle and name, a second at once was dropped, one two seconds on
heard; in co-op, a call for help reached the whole team by name. Two survivors
of the first mutation pass were answered: a check that could not fail was
removed, and a stale handle a backgrounded listener holds got its own test.
Building the client found [P-33](defects.md#2-protocol--the-client-contract),
a frame's events unseen after a stall, fixed.

**Party phrases, designed 2026-09-29**
([04 §4](detailed-design/04-platform-services.md#parties)): `party.say` in the
lobby, pushed to every member as `evt.party.said`, one every two seconds by a
key that lapses. Parts: (a) platform and the gateway; (b) the client and the
drill. **Built 2026-09-29** (12 mutants of 12), and drilled: a party of two
heard its own "Good luck!" in the lobby, the third player outside it did not,
and a second phrase at once was refused `too_soon`. **Item 8 is done**, and
with it Phase 3's exit, but for the certificate the owner has deferred.

#### 9. ~~j-redis 2.1: streams. Phase 4 begins.~~ — **done 2026-09-29**, 2.1.0 released

**Added 2026-09-29.** Phase 3 is done but the certificate, so the roadmap
([§3](#3-phases)) goes on to Phase 4, proving the scale-out: j-redis streams and
replication, `worker` split out, a scripted failover rehearsed, a load test to
10 000. Streams come first ([§5](#5-j-redis-roadmap)), as agreed with the owner
when j-redis moved to Java 21: the result queue becomes a stream with consumer
groups ([05 §1](detailed-design/05-worker-and-events.md#1-why-a-stream-and-not-a-queue)),
which is what replay, a second consumer, reclaiming a retired worker's entries
and retention by age all wait on, and the audit's stream too
([D-30](architecture/03-decision-log.md#d-30--admin-calls-are-audited-in-mysql-until-there-are-streams)).
The store's own design and plan hold the detail (j-redis-service `docs/`); the
backend moves onto streams as item 10, once 2.1 is released.

**Designed 2026-09-29** (j-redis `docs/15-streams.md`, its D-33 and D-34).
**Parts (a) and (b) built** the same day: the type, its reads and trims, and
its persistence, in immutable chunks encoded off the command thread. A rewrite
of 300 000 entries held the command thread 0 ms, where a sorted set of as many
held it 232 ms. **Part (c), consumer groups, built** the same day: deliveries
logged as `XCLAIM` with their time and count, groups kept in a base, and a
replay under a moved clock giving back every delivery's time. **Part (d),
blocking reads**, with `XREAD` itself: a waiter served only by an entry after
its position, a group reader told at once when its stream or group goes, and a
delivery to a waiter logged as any other. **Part (e)**: the client library's
typed stream calls and blocking reads, the CLI and dump tool, and **2.1.0
released**: j-redis full build 171 tests; the distribution run for real, 15
examples of 15, streams through the CLI, a clean stop and a restart with the
stream and its pending entry back.

#### 10. ~~The result queue on a stream.~~ — **done 2026-09-29**

**Added 2026-09-29**, the backend's half of item 9: 2.1.0 is released, and the
result pipeline was waiting on it
([05](detailed-design/05-worker-and-events.md#the-move-onto-streams-designed-2026-09-29-plan-item-10),
[D-33](architecture/03-decision-log.md#d-33--the-result-queue-is-a-stream-read-by-one-group-the-list-stays-an-inbox)).
The stream `s:match-result`, read by the group `rewards`; a retired worker's
entries claimed by the others after a minute; the list kept as an inbox the
workers drain, so the upgrade has no order but the store's; retention 24 hours
([Q-9](requirements/01-scope-and-nfrs.md#7-open-questions)). Parts: (a) the
backend on j-redis 2.1.0; (b) the worker on the stream; (c) the arena
publishing to it; (d) operations and a live drill.

**Parts (a) and (b) built 2026-09-29.** The backend on 2.1.0, its 611 tests
unchanged; 616 with (b)'s. The worker reads the stream as the group `rewards`, one consumer
each, acknowledges after the commit, re-drives its pending entries, takes over
a retired worker's after a minute, drains the list as an inbox, counts an entry
trimmed while pending as lost, and makes the group again when a store restored
empty took it. New metrics: `backend_worker_pending`, and the counter
`backend_worker_trimmed_unapplied_total`, alerted on above 0. Mutation-checked,
21 of 22 caught, the one left a guard for a race no test can time (05). Found:
[defect D-27](defects.md#3-data-and-the-result-pipeline), a lost stream would
have stopped the pipeline; DOC-8, the design's dead letter "in one transaction".

**Part (c) built 2026-09-29.** The arena adds each result to the stream,
trimming what is older than a day by its own clock; the list queue's producer
and reader methods removed, what is left of it being the inbox and the lists
beside the stream. Mutation-checked 5 of 5.

**Part (d) done 2026-09-29.** The runbook (a worker lost, the store restored
empty, a result lost to the trim, the dead list back through the inbox), the
deploy order (the `events` store on 2.1.0 first), the restore drill's stream
checks, and `TAKEOVER=1` for the drill: from the release, the results of a
killed worker taken over by another in 66 s and applied once each. Found: O-2,
the store scripts' prompt stripping, which compared an empty reply by port.

#### 11. ~~j-redis 2.2: replication.~~ — **done 2026-09-29**, 2.2.0 released

**Added 2026-09-29**, the next step of Phase 4 ([§3](#3-phases),
[§5](#5-j-redis-roadmap)). Streams are done, and replication is what the second
machine waits on and what the availability model assumes
([architecture/02 §5](architecture/02-availability.md#5-prerequisites-still-missing)):
today the `session` store is a single point of failure for every login, and
the `events` store for the results in flight, which no copy on a timer could
save ([D-31](architecture/03-decision-log.md#d-31--the-store-is-not-copied-off-its-machine-on-a-timer-before-replication)).
D-8's scripted promotion needs a replica to promote. Scope, from §5:
`REPLICAOF`, the handshake, a base transfer and then the stream of effects,
read-only replicas, and an epoch that fences the old primary after a promotion.
j-redis's own requirements exclude replication (its C-4, one machine), so they
change first. Designed in j-redis `docs/16-replication.md`, built in parts,
released as 2.2.0; the backend's side (the promotion script, the runbook, the
drill) follows as item 12.

**Designed 2026-09-29** (j-redis `docs/16-replication.md`, its D-35 to D-38).
The link carries the AOF's effects, which are already deterministic; a full sync
is the fork-free snapshot sent to the replica's connection, landed on its disk
and adopted as its base; replicas are read-only and never expire keys
themselves; an epoch, raised only by a promotion and persisted, makes a replica
refuse an older primary and clients ignore one. Parts: (b) the effect stream
apart from the AOF, the backlog, `DEBUG DIGEST`; (c) the full sync and the
stream; (d) continuing after a drop, acknowledgements, `WAIT`; (e) epochs,
promotion, `min-replicas-to-write`; (f) the client following the primary, and
2.2.0. Which store refuses writes without its replica, and whether the arena
waits for a replica before letting a result go, are the backend's to decide in
item 12.

**Part (b) built 2026-09-29.** Effects are encoded once per batch and handed to
the AOF and to a backlog with offsets; `DEBUG DIGEST`. The backlog holds exactly
the AOF's bytes. Measured: with the AOF off, encoding for a backlog costs the
command thread about 0.8 µs a small command; with it on, nothing measurable.
Mutation-checked, 20 of 21 once five tests were added; the survivors included
T-18, a fail-stop rule untested since 1.0.

**Part (c) built 2026-09-29.** A replica syncs in full and follows the stream:
the primary sends a snapshot straight to the connection, then the backlog; the
replica makes the image its base before loading it, applies the stream through
its own AOF, refuses writes and deletes nothing on its own clock. Snapshots are
numbered by the dataset now: numbered by the AOF generation, a sync would have
made the next rewrite skip keys. Tested with real servers over TCP, a random
workload compared by `DEBUG DIGEST`, and a fake replica that never reads to hold
a sync open. Mutation-checked, 25 of 25 once thirteen tests were added. Found
building it: a `PSYNC` pipelined behind another command could have had that
command's reply land inside the image; a replica's connection now carries the
stream alone.

**Part (d) built 2026-09-29.** A cut link continues from the backlog without a
second image; replicas acknowledge every second and when asked; `WAIT` counts
them; `PING`s keep a quiet link up and `repl-timeout` drops a silent one on
either side. Mutation-checked, 18 of 18.

**Part (e) built 2026-09-29.** A promotion raises the epoch and records it
before any write; a replica sends its epoch and an older primary refuses it, its
data untouched; a replica takes its primary's epoch at each sync;
`min-replicas-to-write` refuses writes without enough replicas that acknowledged
in time. Found on the way, by the random workload ending twice in eight runs
with different digests: [defect D-28](defects.md#3-data-and-the-result-pipeline),
a replica's clock moved the stream deliveries it applied; fixed, and
`DEBUG DIGEST-VALUE` added to find such a key. Mutation-checked, 14 of 14, one
equivalent mutant's code removed.

**Part (f) done 2026-09-29: 2.2.0 released.** The client, given several
addresses, uses the primary with the highest epoch, follows a promotion on its
own and never goes back to a lower epoch, a replica's epoch counting too.
Proved with server processes: a primary killed with `kill -9` under a
workload that `WAIT`s, the replica promoted, 377 confirmed writes and none
lost, the client writing to the new primary by itself, the old primary
following the new one when it came back. j-redis full build 223 tests; the
distribution smoke-tested: a primary and a replica, `WAIT`, `READONLY`, a
promotion. **Item 11 done**; the backend's side follows as item 12.

#### 12. ~~The stores with their replicas.~~ — **done 2026-09-29**

**Added 2026-09-29**, the backend's half of item 11: j-redis 2.2 replicates, and
the backend's stores should use it
([operations/01 §3](operations/01-deploy.md#3-j-redis-built-and-tested),
[D-34](architecture/03-decision-log.md#d-34--each-store-has-a-replica-every-process-knows-both-a-promotion-is-a-script)).
Each store gets a replica on another machine, `session` on B for A's primary,
`events` on C for B's; every process is given both addresses and follows a
promotion by itself; a promotion is a script an operator runs (D-8). Parts: (a)
the backend on j-redis 2.2.0; (b) the processes given both addresses; (c) the
arena waiting a moment for the `events` replica before letting a result go, and
counting those not confirmed; (d) the promotion script, the deploy, the runbook,
and a live drill: the `events` primary killed while matches end, the replica
promoted, every result applied. The owner's to decide:
[Q-10 and Q-11](requirements/01-scope-and-nfrs.md#7-open-questions), built on
the recommendations.

**Part (a) done 2026-09-29**: the backend on j-redis 2.2.0, its 617 tests
unchanged; the drill's server and CLI jars are 2.2.0's.

**Part (b) built 2026-09-29.** `BACKEND_STORE_ADDRESSES` and a
`BACKEND_EVENTS_STORE` list give a process both addresses of a store; its
client follows a promotion. A missing password is still refused at start-up,
each address asked plainly first, since the role probe would read a refused
`AUTH` as "no primary". Tested against two real j-redis servers: both stores'
clients on the primary with the replica listed first, then on the promoted
replica. Mutation-checked, 6 of 6 once out-of-range ports were tested.

**Part (c) built 2026-09-29**, on Q-11's recommendation: when the store that
holds results was named with its replica, the arena waits up to 100 ms after
each result for the replica to hold it (`WAIT 1 100`), counts the ones it did
not confirm (`backend_arena_results_unreplicated_total`, alerted on while
rising), and lets the spool file go either way. Without a replica named,
nothing waits. Tested against a real primary and replica: three confirmed, then
the replica stopped and the next counted and let go; a failed wait counts as not
confirmed. Mutation-checked, 7 of 7 once the failed wait was tested.

**Part (d) done 2026-09-29.** `promote-store.sh` (shipped in the release): it
refuses while the old primary answers as one, unless told it is down or told to
demote it; promotes; takes `replicaof` out of the file. The runbook's procedure
for a store (§2). Drilled from the release, `FAILOVER=1 client/headless-drill.sh
play duel`: the store's primary killed with `-9` after the scenarios, the
replica promoted by the script, and the same scenarios passing again against
the same processes, each back within about six seconds; four results applied,
two from each side. **Item 12 done.**

#### 13. ~~MySQL's replica and its promotion.~~ — **done 2026-09-30**

**Added 2026-09-29**, the rest of Phase 4's "scripted failover rehearsed"
([§3](#3-phases)): the stores fail over now (item 12), and MySQL, the third
stateful role and the system of record, is the one left
([architecture/02 §5](architecture/02-availability.md#5-prerequisites-still-missing),
[runbook §7](operations/02-runbook.md#7-still-to-be-written)). Designed in
[06 §10](detailed-design/06-persistence-mysql.md#10-backup-and-recovery) and
[D-35](architecture/03-decision-log.md#d-35--mysql-fails-over-as-the-stores-do-by-an-epoch-the-clients-follow):
a GTID replica on C, read-only; an epoch in a one-row table that only a
promotion raises; the backend given both hosts and using the writable one with
the highest epoch; a promotion script; the old primary brought back only as a
new replica. Parts: (a) the epoch's migration and the backend following the
primary; (b) the promotion script; (c) the runbook and a live drill, two
MySQL servers of the drill's own, the primary killed while matches end.

**Part (a) built 2026-09-29.** V8 adds `ha_epoch`, one row at 0. Given two
hosts, the pool opens each connection through `PrimaryDataSource`: every host is
asked whether it is writable and at which epoch, and the writable one with the
highest epoch is used, never below the highest seen, a read-only host's
included. A database without the table yet reads as epoch 0, so a fresh one can
migrate. With one host, nothing changes. Mutation-checked 7 of 8; the eighth,
reading `read_only`, needs a read-only server, which the drill has: run there,
it failed the drill, the platform unable to start on the read-only replica.

**Parts (b) and (c) done 2026-09-29.** `promote-mysql.sh`, in the release,
run as root on the replica's machine with its own least-privileged account
(each grant checked by taking it away). Told the old primary is down, it
refuses if it answers at all; told to hand over, it fences it. It waits for
the replica to apply what it received, stops replication, makes the replica
writable persistently, and raises the epoch; run again, it changes nothing.
The runbook's MySQL procedure (§2). **The drill found D-29**: in a handover,
the old primary made read-only alone kept every process's connections, their
writes refused; the fence is `offline_mode` too, which closes them. **And
D-30**: the script's 60 s wait for the replica ran under a 20 s limit per
statement, and a longer one ended it without a word. Drilled from the release
in both forms (`MYSQL_FAILOVER=1`, `MYSQL_FAILOVER=demote`), the replica
applying 30 s late so the promotion had to wait past that limit: the new primary
held every match the old one had, the scenarios passed again against the same
processes, and it restarted writable. Mutation-checked, 9 of 9, each against
the drill: every rule of the script, from the refusal to the second run.

**Part (d) done 2026-09-30: the replica in production, the old primary's
return, the backups with GTIDs.** The replica needs GTIDs on the primary, and
**D-31** was waiting there: the restore's replay kept each transaction's GTID,
so onto the primary's own server it was skipped without a word (959 matches of
962), onto a server with GTIDs off it was refused, and onto another it came back
short the second time. The replay drops them now; five restores, every table and
the ledger equal. GTIDs turned on live under writes, 40 of 40. **D-36**: a
replica is made, and an old primary remade, by MySQL's clone plugin, rehearsed
for what travels (data, accounts, GTID history; not persisted settings) and
what TLS checks (replication the CA and the address, the clone the CA only).
Because persisted settings stay, the fence no longer persists `offline_mode`: a
rebuilt old primary would have stayed closed, and once promoted closed every
process out. 01 §9 sets up the replica; the runbook's step 5 rebuilds the old
primary, moves the backups off the new primary's machine and moves back. The
drill runs the whole cycle in both forms: the failover, the old primary
rebuilt by clone over TLS (7 to 9 s at development size), handed back to at
epoch 2, and the scenarios three times against the same processes.
Mutation-checked: the replay keeping GTIDs fails three of the five restores,
and `offline_mode` persisted fails the drill. **Item 13 done.**

#### 14. ~~The load test, as far as this machine goes.~~ — **done 2026-09-30**; 10 000 waits on Q-3

**Added 2026-09-30.** Phase 4's last exit criterion is a load test to 10 000
players on two machines ([§3](#3-phases)); items 9–13 did the rest, and `worker`
has run as its own process from the start. Those machines are Q-3's and not
here. What can be done here is the same test against the release on the
development machine, as far as it goes: to find the first limit that is the
backend's rather than the machine's, and to have the harness ready for the real
run. The machine is shared, so the run is capped at about half its cores, in
steps of a few minutes. Designed in
[07 §4](detailed-design/07-threading-and-performance.md#4-tick-budget-and-what-to-measure).
Parts: (a) the harness: `tools/BotClient` against the drill's stack
(`BOTS=<n>`), the arena sized for it, and each step's numbers collected; (b)
the ramp, and whatever it finds; (c) the numbers written down, in 07 §4 and
against Q-3.

**Done 2026-09-30.** (a) `BOTS=<n>[:<s>] client/headless-drill.sh`: the bots
instead of the scenarios, the arena sized for them at the production shape
count, every process on ZGC, and each process's cores over the window every bot
plays in, the arena's by thread as user and kernel time, with the arena's own
report when it stops. (b) Ramped to 1 200 bots, 3.5 of the machine's twelve
shared cores: every bot got its 14.9 snapshots a second, none was dropped, no
tick overran. What it found: **O-3**, the arena's report at shutdown judged its
whole tick against the simulation's budget, fixed; and **the network threads
cost more than the rooms**, three quarters of it the kernel's, which the
capacity model's arena cores had left out: at this machine's rate the 50 000
need ~87 cores for arenas, and an arena's two Netty workers fill near 2 100
players. Loopback and a shared machine overstate both, so the real figures are
Q-3's, with the bots on another machine. Fewer wakeups of the network threads
were built, measured three runs each way, gained nothing, and were taken out.
(c) 07 §4, architecture/01 §3, Q-3. **Item 14 done as far as this machine
goes**; the run to 10 000, and whether 50 000 fits three machines, wait on
Q-3's hardware.

#### 15. ~~Equipment and the stat bonus it gives. Phase 5 begins.~~ — **done 2026-09-30**

**Added 2026-09-30.** Phase 4 is done but for the load test to 10 000, which
waits on Q-3's machines ([§3](#3-phases)). Phase 5, the meta layer, comes next.
Its order is [Q-12](requirements/01-scope-and-nfrs.md#7-open-questions), built
on the recommendation: equipment first, the one module that reaches into the
simulation. What exists: the shop, inventory, and the `equipment` table since
V1; what an item does is sketched in 04 §8 and not built, and the simulation
has no bonuses (`TankStats.refresh` is the hook). FR-10: a player equips owned
items into slots, the resolved loadout is applied when a match is joined, and
the arena never queries inventory. Designed in
[04 §8](detailed-design/04-platform-services.md#equipment-designed-2026-09-30-plan-item-15),
[01 §3](detailed-design/01-arena.md#equipments-bonus-designed-2026-09-30-plan-item-15)
and [D-37](architecture/03-decision-log.md#d-37--equipment-reaches-the-arena-as-a-capped-percentage-a-stat-in-the-ticket).
Parts: (a) the bonus in the simulation, measured against the tick; (b) the item
table and equipping, on platform; (c) the loadout in the ticket, applied by the
arena, and a drill that earns, buys, equips and plays; (d) the client core's
calls. Every item's numbers are a first cut in a table; what the shop sells, and
for how much, stays the owner's (04 §8).

**Part (a) built 2026-09-30.** `TankStats.setBonus`: a whole percent a stat,
applied by `refresh` after the table, reload's ticks divided; carried by a
resume, cleared when a slot is handed out anew. Mutation-checked, 6 of 6. The
tick, measured twice each way with `TickBenchmark` (150 tanks, 1 500 shapes):
0.468 and 0.479 ms p50 before, 0.484 and 0.478 after, the p99s overlapping
(1.10–1.25 against 1.06–1.35): nothing a tick pays, as the dirty flag means.

**Part (b), first half, built 2026-09-30: the item table.** `items.json` beside
`shop.json`, checked as strictly (an unknown field, a slot or stat it does not
know, a percent outside 1 to 25, no modifiers or more than three, a stat or an
id twice: each stops the start), and every shop offer must name an item in it.
Five first-cut items, one or two a slot. Mutation-checked, 9 of 9; the start's
call to the check is not tested apart from the start itself.

**Part (b), second half, built 2026-09-30: equipping.** `EquipmentRepository`
on the V1 table (wearing checks the item is held, in the same transaction);
`EquipmentService`, the slot and the item checked, the bonus added a stat and
capped at 25, an item worn and no longer held giving nothing, and `bonusOf`
for the ticket; `GET /v1/equipment`, `PUT` and `DELETE /v1/equipment/{slot}`.
Tested against the real MySQL; mutation-checked, the repository 6 of 6 once
wearing an item held at none was tested, the service and routes 11 of 11 once
taking off an unknown slot was. Persistence 40 tests, platform 137.

**Parts (c) and (d) done 2026-09-30.** The ticket carries the bonus
(`bonus`, `stat:percent` pairs, no field when there is none; a bad one refuses
the ticket). `JoinService` puts what the player wears in an open join's ticket;
for a made match `QueueService` reads it with the rating when the player
queues, the queue entry keeps it (`bonus`, `b:{id}` in a party's), it rides
through the confirm step, and the matcher writes it into the ticket, still from
the store alone. The arena puts it on every tank the player spawns: joining, a
respawn, and a new tank after a resume. The client core's calls,
`Equipment`, `Wear`, `TakeOff`. Mutation-checked: the ticket 9 of 9, platform's
carriage 9 of 9, the arena's three spawns 3 of 3, the client 4 of 4. **Drilled**
from the release, `client/headless-drill.sh equip` (the drill's own shop and
items, as the release's shop sells nothing): a player earned coins in a stay,
bought a barrel of +25 % bullet speed for a coin, wore it, and its bullets in
the next stay flew at 12.5 units a tick, 25 in half units, against 10 for
`play` in the same run. **Item 15 done.** Next in Q-12's order: boosts.

#### 16. ~~Boosts.~~ — **done 2026-09-30**

**Added 2026-09-30**, next in Q-12's order. A boost is an item held and
activated: for a number of minutes, a match's experience or coins are raised by
a percent. Its rules are [Q-13](requirements/01-scope-and-nfrs.md#7-open-questions),
built on the recommendation: experience and coins only, never a rating or
anything in a match, one a kind at a time. Designed in
[04 §8](detailed-design/04-platform-services.md#boosts-designed-2026-09-30-plan-item-16),
[06 §3](detailed-design/06-persistence-mysql.md#inventory-and-equipment) and
[D-38](architecture/03-decision-log.md#d-38--a-boost-raises-a-matchs-rewards-in-worker-if-the-match-ended-while-it-ran).
Parts: (a) boost items, V9 and activation, once per key; (b) worker raising a
match's rewards by the boosts running when it ended; (c) the API, the client
core's calls and a drill.

**Part (a) built 2026-09-30.** `items.json` takes `BOOST` items (a kind, `xp`
or `coins`, a percent 1 to 100, minutes 1 to 1 440; no slot, no stats), two
first-cut ones, +100 % for an hour. V9: `boost`, a row a run, and
`boost_activation`, a row a key. `BoostRepository.activate` takes the item and
starts or extends the run in one transaction, the player locked, once per key;
`percentsAt` gives a match's boosts at its end. **Writing the test found a flaw
in the design**: one row a kind, replaced when a new run started, would have
lost the run a late-applied result's match had ended in; every run is kept now.
Mutation-checked, the item table 6 of 6, the repository 9 of 9.

**Part (b) built 2026-09-30.** The worker reads a result's players' boosts as
they ran when the match ended (`BoostRepository.percentsAt`), inside the path
whose database errors are retried, and `RewardRules` raises experience and coins
by them, rounded down; a rating is never touched. Tested through the pipeline
against the real MySQL and stream: +100 % experience and +40 % coins running
gave 970 and 133 where 485 and 95 are unboosted, and a match that ended after
both ran out gave 485 and 95 again. Mutation-checked, 5 of 5, a boost judged at
the application's time rather than the match's end among them. Worker 45 tests.

**Part (c) done 2026-09-30.** `BoostService` and `GET`/`POST /v1/boosts`
(the key checked, an item that is no boost refused as unknown, one of a kind
running refused as `other_running`), mutation-checked 6 of 6; the client core's
`Boosts` and `ActivateBoost`; the headless drill's `boost` scenario. **Drilled**
from the release: a stay unboosted paid 10 coins; a coins boost bought and
activated, the same tap again taking nothing; the next stay paid 20. **Item 16
done.** Next in Q-12's order: teams.

#### 17. ~~Teams, the first slice.~~ — **done 2026-09-30**

**Added 2026-09-30**, next in Q-12's order, and what tournaments will be played
by. FR-6: teams with roles and permissions. 04 §2 designs the roles and the
rules; how a player gets in, and what a team may be called, are
[Q-14](requirements/01-scope-and-nfrs.md#7-open-questions), built on the
recommendation: by invitation, as a party. Designed in
[04 §2](detailed-design/04-platform-services.md#the-first-slice-designed-2026-09-30-plan-item-17),
[06 §3](detailed-design/06-persistence-mysql.md#teams-and-tournaments) and
[D-39](architecture/03-decision-log.md#d-39--a-teams-invitations-are-mysql-rows-and-every-change-locks-the-team-then-the-players).
Parts: (a) V10 and the team repository, every action one transaction; (b) the
API; (c) the client core's calls and a drill. Team matches and tournaments come
after.

**Part (a) built 2026-09-30.** V10 (`team`, `team_member` with one team a
player held by a unique key, `team_invite`, `player.team_left_at`) and
`TeamRepository`: create, invite, answer, leave, kick, set a role, transfer,
disband, each one transaction locking the team and then the players, a
player's team re-checked under the lock. Tested against the real MySQL, a name
taken however cased or accented, the last place taken by one of two at once
among them; mutation-checked 17 of 17, once a place freed by a leaver was
tested.

**Part (b) built 2026-09-30.** `TeamService` (the session, a name by a display
name's rules, a role by name) and the routes of 04 §2 under `/v1/teams` and
`/v1/team-invites`, each answering with the team as it then stands. Tested
through HTTP from creation to disbanding with three players; mutation-checked
9 of 9, once declining was tested. Persistence 50 tests, platform 142.

**Part (c) done 2026-09-30.** Building the client found the design's one gap: a
player's public code, by which Q-14 had invitations name a player, is shown by
no API, so nobody could invite anyone. Invitations name the player by id, as a
party's do, a player knowing their own and seeing others' on the boards; the
repository, the routes, their tests and the design changed together, the
invitation's mutants run again, 3 of 3. The client core's team calls; the
headless drill's `team` scenario. **Drilled** from the release, V10 migrated
onto the drill's database: a team made, a player invited who saw it and
accepted, promoted, handed the team, the first leaving, the team disbanded.
**Item 17 done.** Next in Q-12's order: tournaments, played by teams, which
needs team matches first.

#### 18. ~~Tournaments, the first slice: duels.~~ — **done 2026-09-30**

**Added 2026-09-30**, next in Q-12's order. 04 §6 designs the state machine,
single elimination and seeding; who creates tournaments, of what and for what
prizes are [Q-15](requirements/01-scope-and-nfrs.md#7-open-questions), built
on the recommendation: an operator creates duel tournaments with coin prizes
through the admin API. Teams' tournaments wait for team matches. Designed in
[04 §6](detailed-design/04-platform-services.md#the-first-slice-duels-designed-2026-09-30-plan-item-18),
[06 §3](detailed-design/06-persistence-mysql.md#teams-and-tournaments) and
[D-40](architecture/03-decision-log.md#d-40--a-tournament-match-is-a-made-match-its-winner-read-from-what-mysql-recorded).
Parts: (a) V11, the repository and the bracket; (b) creating through the admin
API, and registering and following through the public API; (c) the scheduler
in `worker`: rounds, matches, walkovers, prizes; (d) the client core's calls and
a drill of a whole bracket.

**Part (a) built 2026-09-30.** V11 and `TournamentRepository`: created,
registered for until the deadline and up to the entries allowed, withdrawn
from, seeded by duel rating (the standard order, `Bracket`, top seeds' byes
through to round 2), matches made ready and decided once each, the winner
into the next round's slot, rounds ended and the tournament finished, every
transition conditional on state and version; and `resultOf`, what MySQL
recorded for a match: nothing, one winner or a draw. Mutation-checked, the
repository 15 of 15, the bracket 3 of 3.

**Part (b) built 2026-09-30.** `POST /admin/tournaments`, each field checked
(2 to 32 entries, a deadline still ahead, a start no earlier, 1 to 60 minutes
between rounds, three whole prizes) and audited, refusals too; `GET
/v1/tournaments` (those registering, seeded or running), `GET
/v1/tournaments/{id}` (its entries and bracket), and `POST`/`DELETE
/v1/tournaments/{id}/entries` (`TournamentService`). Mutation-checked, 10 of 10
once a cancelled one kept off the list and a wrong method refused were tested.

**Part (c) built 2026-09-30.** `worker/TournamentScheduler`, every 5 s in every
worker with no lock
([D-41](architecture/03-decision-log.md#d-41--every-worker-runs-the-tournament-clock-and-a-match-is-claimed-before-its-tickets-are-written)):
seeded or cancelled at the deadline, started, each round's matches made (an
arena with a room, the match claimed, then the tickets, the grant at
`tgrant:{tournamentId}:{playerId}` and `evt.tournament.match`), decided from
the recorded result, a draw or 270 s without one going to the higher seed,
rounds ended, the prizes paid once through the ledger and the tournament
finished; `GET /v1/tournaments/{id}/match` for a player who missed the push.
Tickets carry no equipment bonus
([Q-16](requirements/01-scope-and-nfrs.md#7-open-questions), on the
recommendation). `backend_worker_tournament_failures_total` alerts on a
clock that keeps failing. DOC-10 and DOC-11 found and fixed on the way.
Mutation-checked, 32 of 32 once the prize's own ledger row was
checked for its reason.

**Part (d) done 2026-09-30.** The client core's calls (`ApiClient.Tournaments`,
`Tournament`, `EnterTournament`, `WithdrawFromTournament`, `TournamentMatch`,
and `LobbyClient.TournamentMatch` from the push) and the drill's `tournament`
scenario. The first drill found **T-21**: a round's matches, made in one tick,
all went to the arena's one free room, and the second was refused at the door,
to be decided by seed unplayed. **[D-42](architecture/03-decision-log.md#d-42--a-matchs-room-is-promised-in-the-store-when-its-arena-is-chosen)**: a room is promised in the store
when its arena is chosen, by the matchmaker and the scheduler alike, and the
arena drops the promise in the announcement that first counts the room. A first
version, in which choosers dropped the promises they saw open, failed the
drill's co-op: a walkover's room came and went unseen, and its promise held the
only room. **Drilled** from the release: four entered and were seeded in
order; round 1's second match waited for the room and was made 35 s after the
first was joined; each side that came won by walkover; the final 60 s after
the round ended, seed 4 winning it; the prizes, 300, 200 and 100 twice, in each
wallet; and walkover, decline and co-op again, the matchmaker promising. Client
core 78 tests, backend 678. Mutation-checked, the room promises 13, 12 killed:
the survivor, an arena with no room left chosen, is given back by the recount,
an equivalent. **Item 18 done.**

#### 19. ~~Team matches.~~ — **done 2026-09-30**

**Added 2026-09-30**, next in Q-12's order, on
[Q-17](requirements/01-scope-and-nfrs.md#7-open-questions)'s recommendation:
tournaments are not finished until teams play them, and a team has to play as
one side before it can enter one. What a team match is was open, FR-6 and 04 §5
deciding none of it: [Q-18](requirements/01-scope-and-nfrs.md#7-open-questions),
built on the recommendation, a mode of its own, `teams`, a party of three of one
team queued by its leader or a vice leader, the team rated. Designed in
[04 §4's sixth slice](detailed-design/04-platform-services.md#the-sixth-slice-team-matches-designed-2026-09-30-plan-item-19),
[06 §3](detailed-design/06-persistence-mysql.md#teams-and-tournaments) (V12) and
[D-43](architecture/03-decision-log.md#d-43--a-team-matchs-sides-are-known-by-their-players-teams-when-the-result-is-applied).
Parts: (a) V12, the mode, the team's entry in the queue, the matcher's rule; (b)
the team's rating and record in the result's transaction, retention, and the
team's view; (c) the client and a live drill.

**Part (a) built 2026-09-30.** V12 (the team's rating and record, `match_team`),
`MatchMode.TEAMS` (mode 5, team-vs-team's rules), the queue's team rules (400
`party_too_small`, 409 `not_one_team`, 403 `not_allowed`), each member queued at
the team's rating, the entry's team kept in the queue and through a declined
match, and two entries of one team never matched; a team match's result moves no
player's own rating. Mutation-checked, 14 of 14.

**Part (b) built 2026-09-30.** The teams' part of a team match's result, in its
one transaction: teams locked before players (D-39), each side's team read again
under the locks (D-43), Elo between the teams, a `match_team` row a side as the
duplicate check, the rating and wins, losses and draws; nothing for a walkover,
an unrated result, or sides that are not two teams. Retention deletes
`match_team`; the team's view shows its record. Mutation-checked, 14 of 15, and
the survivor removed: a check that a side's team had been locked, which only a
player joining another team between two statements, as a 24-hour cooldown ends,
could reach, and whose absence costs a retry (D-43).

**Part (c) done 2026-09-30.** The client core's `TeamInfo` carries the team's
rating and record; a team queues through `JoinQueue("teams")`. The drill's
`teammatch` scenario, from the release: two teams of three made through the
API, a party of each in the lobby; the second's, led by a member, refused
`not_allowed` until its leader made that member a vice leader; both asked,
accepting, and matched into one room, each team one side; nobody fired, the
five minutes ended it (299 s), all six paid, each team counting a draw at 1 200,
and the database's view two `match_team` rows. The draw between equal teams
moves no rating, so the live run shows none move; the worker's test feeds a
team match through the stream and the real Elo moves them 16 each way. Client
core 78 tests, backend 687. **Item 19 done.** Next: item 20, teams'
tournaments.

#### 20. ~~Teams' tournaments.~~ — **done 2026-09-30**

**Added 2026-09-30**, after item 19: tournaments whose entries are teams,
registered by a leader or vice leader, each match a team match made by item
18's scheduler. Who plays for a team, how it is seeded and who is paid were
open: [Q-19](requirements/01-scope-and-nfrs.md#7-open-questions), built on the
recommendation, a roster of three fixed at registration, seeding by team rating,
the prize to each roster member. Designed in
[04 §6's second slice](detailed-design/04-platform-services.md#the-second-slice-teams-tournaments-designed-2026-09-30-plan-item-20),
[06 §3](detailed-design/06-persistence-mysql.md#teams-and-tournaments) (V13) and
[D-44](architecture/03-decision-log.md#d-44--a-teams-tournament-entry-fixes-its-roster-and-the-bracket-holds-teams-ids-where-a-duels-holds-players).
Parts: (a) V13 and the repository; (b) the operator's mode, and registering a
team; (c) the scheduler for teams; (d) the client and a live drill.

**Parts (a) to (c) built 2026-09-30**, together. (a) V13, and
`TournamentRepository`'s team entries with rosters, seeding by team rating, and
the result by side; 12 mutants killed, one equivalent removed (a sort key for a
disbanded team's rating, which MySQL already sorts last). (b) The operator's
`mode`; a team registered by its party's leader, the team's leader or a vice
leader, withdrawn likewise; the view's teams, rosters and team-named bracket; 14
of 14, once a party of two and a party led by a member were tested. (c) The
scheduler: tickets to the rosters still in their teams, side 1 and side 2, the
winner by side, a draw to the higher seed, prizes to each roster member; 10 of
10, once a leaver on each side, one of them since in another team, was tested.
Found on the way, and not a defect of the code: running two modules' database
tests at once clobbers the shared test schema, each cleaning it, and one
mutant's kill had to be re-established alone.

**Part (d) done 2026-09-30.** The client core reads a teams' tournament:
`TournamentInfo.Mode`, an entry's `TeamId` and `Roster`, a match's `TeamA`,
`TeamB` and `WinnerTeam`. The drill's `teamcup` scenario, from the release: an
operator's teams' tournament; two teams made through the API, each leader's
party of three entered as its roster; at the start the final pushed to all six
as a team match; only the lower seed's roster came, and won by walkover; the
tournament finished, and each roster member paid the team's prize, 300 and 200.
Client core 79 tests, backend 693. **Item 20 done.** Next: item 21, friends,
presence and notifications, last in Q-12's order.

#### 21. ~~Friends, presence and notifications.~~ — **done 2026-09-30**

**Added 2026-09-30**, last in
[Q-12](requirements/01-scope-and-nfrs.md#7-open-questions)'s order: the
social layer of 04's `social` and `notify` modules (friends, a block list,
presence; pushes through the gateway, an inbox for players offline). What each
is was open: [Q-20](requirements/01-scope-and-nfrs.md#7-open-questions), built
on the recommendation. Designed in
[04 §9](detailed-design/04-platform-services.md#the-social-layers-first-slice-designed-2026-09-30-plan-item-21),
[06 §3](detailed-design/06-persistence-mysql.md#teams-and-tournaments) (V14) and
[D-45](architecture/03-decision-log.md#d-45--a-friendship-is-two-rows-and-a-change-to-it-locks-both-players-lowest-id-first).
Parts: (a) V14; friends and blocks, presence in the list, the pushes, and blocks
honoured by party and team invitations; (b) the inbox; (c) the client and a
live drill.

**Part (a) built 2026-09-30.** V14 (friends, requests, blocks, and the inbox's
table); `FriendRepository`, `FriendService` and `/v1/friends`,
`/v1/friend-requests`, `/v1/blocks`; presence from the lobby connection; the
pushes `evt.friend.request` and `evt.friend.accepted`; a block honoured by
friend requests, team invitations and party invitations, each answered as sent
and never delivered. Mutation-checked, 18 of 18, once being accepted by a full
player and a dropped request to someone online were tested.

**Part (b) built 2026-09-30.** The inbox: `InboxRepository`, items for a friend
request, an acceptance and a team invitation written in the transaction they
report, and for a prize after the ledger's credit, repaired by the next tick if
one died between; `evt.inbox` with each new item; `GET /v1/inbox`,
`POST /v1/inbox/read`; the daily retention deleting items past 30 days.
Mutation-checked, 16 of 16, once a team invitation's push and a prize's were
tested (the match's own pushes arriving after the tick had first been missed by
the test, not the code).
The full build then found **T-22**, an arena test from item 15 waiting for a
frame a dead player is never sent, failing only under load; the test was fixed.
The same build's tally had counted a stale report from a module the failure
skipped; the tally counts only reports the build itself wrote now.

**Part (c) done 2026-09-30.** The client core's social calls (`Friends`,
`AskFriend`, `RemoveFriend`, `DropFriendRequest`, `Blocks`, `Block`, `Unblock`,
`Inbox`, `ReadInbox`); the pushes through `LobbyClient.OnPush`. The drill's
`social` scenario, from the release: ada's request pushed to bob and in his
inbox, accepted by asking back and ada told; bob online, cyd offline; cyd's block
ending their friendship and silently dropping ada's next request; bob's inbox
read. Client core 80 tests, backend 701. **Item 21 done**, and with it Q-12's
order: Phase 5's modules each have a first slice.

#### 22. ~~Guest accounts, and upgrading one to a full account.~~ — **done 2026-09-30**

**Added 2026-09-30**, on
[Q-21](requirements/01-scope-and-nfrs.md#7-open-questions)'s recommendation:
Q-12's order is done, and a player on a phone expects to play before
registering. 04 §1 sketched it ("guest accounts, with a random name, upgraded
later"); its product choices are
[Q-22](requirements/01-scope-and-nfrs.md#7-open-questions), built on the
recommendation. Designed in
[04 §1](detailed-design/04-platform-services.md#1-auth-and-sessions),
[06 §3](detailed-design/06-persistence-mysql.md#teams-and-tournaments) (V15) and
[D-46](architecture/03-decision-log.md#d-46--a-guests-credential-is-a-random-key-kept-as-its-sha-256-under-a-username-nobody-can-choose).
Parts: (a) V15, making a guest, its login, and its upgrade (`persistence`,
`platform`); (b) the client and a live drill.

**(a) done 2026-09-30.** V15 (`account.guest_key_hash`, unique);
`POST /v1/guests`, a guest's login by `{"guestKey"}` on `/v1/sessions`, and
`POST /v1/accounts/upgrade`, as in 04 §1. Mutation-checked 12 of 12 (the
username's "~", the key hashed, the ban, a malformed key, the display name's
and the username's rules before hashing, the upgrade clearing the key and
guarded by it, a display name only when given, a taken name, the throttle, the
login's dispatch on the key). A full build found **T-23**, an arena test
stopping the replica while it still confirmed a result, fixed. Backend 704.

**(b) done 2026-09-30**, and drilled from the release (the drill's `guest`): a
guest made, in again by its key as after a restart, paid 10 coins for a stay,
upgraded, in by name as the same player with the same 10 coins, and its key
refused. `ApiClient.CreateGuest`, `LoginGuest` and `UpgradeAccount` (08); client
core 81 tests. **Item 22 done.**

#### 23. ~~A Phase 6 rehearsal on this machine: failover under load, and the runbook walked.~~ — **done 2026-09-30**; the soak waits on Q-3

**Added 2026-09-30**, on
[Q-21](requirements/01-scope-and-nfrs.md#7-open-questions)'s recommendation.
Phase 6's exit criteria ([§3](#3-phases)) are a 24-hour soak at target load, a
failover drill under load, N−1 capacity measured, and the runbook exercised end
to end. Target load, the soak and N−1 need Q-3's hardware. What this machine can
do is the failover under load. Every failover so far was drilled between
scenarios, with nobody playing, so architecture/02 §2's claim, **a match in
progress survives the loss of `platform`, `worker`, MySQL and the `events`
store**, has never been tested with anyone in a match. Nor has the path a
result takes when it ends while a primary is down: spooled to the arena's disk
for the store, waiting in the stream for MySQL.

Designed in
[architecture/02 §6](architecture/02-availability.md#6-rehearsed-under-load-designed-2026-09-30-plan-item-23):
the bots of item 14 playing, the primary killed halfway through their window,
the bots leaving while it is down, and the replica promoted by the runbook's
own command a set time after, as an operator would. Checked: no bot dropped;
every bot's stay paid exactly once after the promotion, and how long that took;
how long an authenticated request was refused, by a probe every half second;
the alert §5 names, read while the primary is down; the tick p99 over the
window. The runbook's §1 triage and §2 steps are followed as written; a step
wrong or missing is a defect (O-_n_), fixed in the runbook.

Parts: (a) the store (`BOTS=<n>:<s> FAILOVER=1`); (b) MySQL
(`MYSQL_FAILOVER=1`, killed, and `demote`, handed over alive); (c) the runbook
as walked, and the numbers in architecture/02 §6.

**Done 2026-09-30.** (a) and (b): 600 bots, a 120 s window, each failure beside
a control run. No bot was dropped by any of them, and every stay was paid
exactly once after the promotion: 76 s after it for the store, whose results
waited on the arena's disk, and 44 s after for MySQL, whose waited in the
stream. A signed-in request was answered again within 4 s of either promotion,
and a handover was never seen at all. The ticks' tails were as long in the
control as in the failures, and the simulation of item 14's build and this one
cost the same: the machine's load that evening, not a regression. The checks
were shown to fail: with the promotion taken out, no stay paid and the probe
never answered. (c) The runbook followed as written, step 4's
`backend_store_connected` 0 during and 1 after included; what it had wrong is
**DOC-12**, a MySQL outage refusing every request that reads it, not only
writes, fixed there and in architecture/02 §1. **O-4**, the MySQL script's
record line printing a literal `\n`, fixed. And a finding for Q-3: **one
worker applies about 13 results a second here**, bound by the commit's
synchronous writes (12.3 ms of a 34.7 ms result), so 05 §1's 170 a second at
50 000 would take six to thirteen workers at this machine's rate; measured on
Q-3's disks before it is believed. **Item 23 done as far as this machine
goes**; the soak, target load and N−1 wait on Q-3.

#### 24. ~~Boost refunds.~~ — **settled 2026-09-30**: nothing to refund

**Added 2026-09-30**, next on
[Q-21](requirements/01-scope-and-nfrs.md#7-open-questions)'s list. 04 §3
refunded a per-match boost taken at the ticket when the player never joined.
Boosts were built timed instead (item 16, Q-13): nothing is taken at a ticket,
so there is nothing to give back.
[Q-23](requirements/01-scope-and-nfrs.md#7-open-questions) records it: no
automatic refund, the sketch struck, and a player a failure kept from a boost
made good by an operator's refund of the purchase, built when support first
needs it. No code.

#### 25. ~~Analytics, the first slice: whether players come back.~~ — **done 2026-09-30**

**Added 2026-09-30**, the last on
[Q-21](requirements/01-scope-and-nfrs.md#7-open-questions)'s list. 05 had an
`analytics` group appending to "the analytics table" and said nothing of what
it measured. [Q-24](requirements/01-scope-and-nfrs.md#7-open-questions) asks,
and is built on its recommendation: the players active each day, the new ones,
and how many of each day's new players come back 1, 7 and 30 days on, read by
an operator. Designed in
[05 §11](detailed-design/05-worker-and-events.md#11-analytics-designed-2026-09-30-plan-item-25),
[04 §10](detailed-design/04-platform-services.md#whether-players-come-back-designed-2026-09-30-plan-item-25),
[06 §3](detailed-design/06-persistence-mysql.md#teams-and-tournaments) (V16) and
[D-47](architecture/03-decision-log.md#d-47--activity-is-recorded-in-the-results-own-transaction-a-row-a-player-a-day-and-counted-when-it-is-read).
Parts: (a) V16 and the activity written by the result's transaction
(`persistence`), its cost on the worker's rate measured before and after; (b)
the counts, `GET /admin/stats` (`persistence`, `platform`) and the 90 days'
retention (`worker`); (c) a live drill.

**Done 2026-09-30.** (a) V16, with both filled from the match history there is;
the result's transaction writes a player's day once and keeps the earliest as
their first, a late result from an earlier day moving it back. The statement it
adds costs less than this machine's noise: a result applied alone took 35.7 to
55.7 ms before and 37.4 to 45.8 ms after, four runs of each, alternated. (b)
`StatsRepository`, `GET /admin/stats?days=N` (audited), and `worker`'s
retention deleting days past 90. Designing the test found the first limit
wrong: at 31 days a day-30 figure could never be shown, since the oldest day's
thirtieth is today, so it is 60. Mutation-checked 19 of 19, the migration's
backfill included. (c) Drilled from the release (the drill's `stats`): a new
guest's stay counted one more player active today and one more new, day 1
empty, 61 days refused and the 60th day back with its day 30. A full build found
**T-24**, two tests on a clock that was partly real, fixed. Backend 709 tests.
**Item 25 done**, and with it Q-21's list.

#### 26. ~~The worker's rate: does it scale?~~ — **done 2026-09-30**: it does

**Added 2026-09-30**, on
[Q-25](requirements/01-scope-and-nfrs.md#7-open-questions)'s recommendation.
Item 23 found one worker applying about 13 results a second on this machine,
each result's commit waiting on two synchronous writes, where 05 §1 expects
~170 a second at 50 000 players. If commits made at once share their writes, as
MySQL's group commit lets them, more workers multiply the rate and the fix is
their number; if not, several results must share one transaction. Measured,
not assumed: (a) the apply alone, from 1, 2, 4 and 8 threads at once, each with
its own connection, alternated, results a second in all and each side's CPU a
result; (b) if it scales, the drill's backlog of 600 stays, built with the
worker paused, drained by 1, 2 and 4 workers and timed; (c) the numbers in
[05 §1](detailed-design/05-worker-and-events.md#1-why-a-stream-and-not-a-queue),
Q-3 and architecture/01's worker count.

**Done 2026-09-30. It scales.** (a) The transaction alone, 400 results a run:
18–19 a second from one thread, 27–34 from two, 66–69 from four, 104 from
eight, up and back down alike; MySQL's CPU a result, mostly the kernel's in the
synchronous writes, fell as the threads rose, one write serving every commit
waiting for it. (b) The drill's backlog of 600 stays (`BOTS=600:30 WORKERS=<k>`,
every worker ready and paused while the bots played): 1 worker applied them at
9.6 a second, 2 at about 18, 4 at 25.1, each worker spending about as long
again outside its transaction as in it; a second round, after P-34's fix, 16.3,
17.8 and 27.2, and 35.9, as the machine's load moved. (c) 05 §1, Q-3, architecture/01 and /02
§6: the rate is a number of workers, set on Q-3's machines with the new
`tools/ApplyBenchmark`, which refuses any database not named for tests since it
drops everything in it (mutation-checked 3 of 3). Found: **P-34**, open, a
third of one run's bots taken for lost connections as they left, their pay a
minute late; and **T-25**, the failover drill's probe stamping a request when
asked, fixed. Backend 710 tests. **Item 26 done**; P-34 is next.

**P-34 fixed 2026-09-30** (02 §10): a client that leaves waits for the arena
to close the connection, up to 2 s, and closes only after it; the load runs
count bots taken for lost connections, none in four runs of 600 since. Client
core 83 tests.

#### 27. ~~Analytics by feature: whether what players do on day one goes with coming back.~~ — **done 2026-09-30**

**Added 2026-09-30**, next on
[Q-25](requirements/01-scope-and-nfrs.md#7-open-questions)'s list, Q-24's next
slice; its choices are
[Q-26](requirements/01-scope-and-nfrs.md#7-open-questions), built on the
recommendation. The soft launch's question (§3) is which of equipment, shop,
teams and tournaments keep players; this counts, for each day's new players,
those who used each on their first day and how many of them came back. Designed
in [05 §11](detailed-design/05-worker-and-events.md#by-feature-designed-2026-09-30-plan-item-27)
and 04 §10. Parts: (a) the counts (`persistence`) and `GET
/admin/stats/features` (`platform`); (b) a live drill.

**Done 2026-09-30.** (a) `StatsRepository.byFeature`: six features, each counted
four ways in grouped queries whatever the number of days, the first day's window
UTC, a purchase the day before not counted; `GET /admin/stats/features`, sharing
the days' rule with `/admin/stats`. Mutation-checked 15 of 15, after two gaps the
list of mutants showed were closed first: nothing before the first day, and a
day-1 figure the admin test could see. (b) Drilled from the release: two new
guests who made friends on their first day, counted under `friend`. Backend 712.
**Item 27 done.**

#### 28. ~~Content tables from JSON.~~ — **settled 2026-09-30**: not now

**Added 2026-09-30**, next on
[Q-25](requirements/01-scope-and-nfrs.md#7-open-questions)'s list. The
simulation's balance is code; the class table is written out as JSON for
clients (D-24) but not read from it.
[Q-27](requirements/01-scope-and-nfrs.md#7-open-questions) records the choice:
not now, since a balance change is a restart either way and the one who makes
it builds the code; files would add a parser, a check of every field and a
second home for the numbers (C-8). Revisit when someone who does not build the
code tunes balance, or it must differ between environments. Next, the modes 01
§8 designs and the arena does not play, co-op's bosses first. No code.

#### 29. ~~Co-op's bosses.~~ — **done 2026-10-01**

**Added 2026-09-30**, on
[Q-27](requirements/01-scope-and-nfrs.md#7-open-questions)'s recommendation: the
first of the modes 01 §8 designs and the arena does not play. Co-op's first slice
(Q-7) left bosses out; their choices are
[Q-28](requirements/01-scope-and-nfrs.md#7-open-questions), built on the
recommendation. Designed in
[01 §8.5](detailed-design/01-arena.md#85-co-op-waves-designed-2026-09-29-plan-item-6).
Parts: (a) a class's health multiplier, the two boss classes, and
`Room.assignClass` (`sim`); (b) waves 5 and 10 and the enraging (`arena`); (c) the
class table as clients fetch it, and a live check.

**Done 2026-10-01.** (a) `TankClass.healthMul`, applied where health keeps step
with a tank's stats; the Guardian and its enraged form appended as classes 49 and
50, opened at no level; `Room.assignClass`. D-22's budget test holds them without
change: the enraged form's shorter bullets keep it at the Guardian's count. (b)
`Waves`: waves 5 and 10 a Guardian of level 45, hunting; below half its health,
enraged, and so it stays; only a Guardian enrages. Mutation-checked 12 of 12, two
pieces of code nothing could observe taken out instead (the multiplier at spawn,
where every tank is Basic; a volley dropped on a swap of identical barrels). The
public tick unchanged within the benchmark's resolution. (c) The drill's `play`:
the class table a client fetches, the one the Welcome names, has the Guardian,
opened by no level; `coop` still plays to its wipe. A boss fought live is not
drilled: the drill's players wipe before wave 5. Backend 716. **Item 29 done.**

#### 30. ~~Domination.~~ — **done 2026-10-01**

**Added 2026-10-01**, next on
[Q-27](requirements/01-scope-and-nfrs.md#7-open-questions)'s list; its choices
are [Q-29](requirements/01-scope-and-nfrs.md#7-open-questions), built on the
recommendation. Designed in
[01 §8.7](detailed-design/01-arena.md#87-domination-designed-2026-10-01-plan-item-30).
Parts: (a) anchored tanks, capture instead of death, the Dominator class
(`sim`); (b) the mode: `MatchMode.DOMINATION`, dominators placed, the clock,
teams placed by dominators held (`handoff`, `arena`); (c) queued and paid
(`platform`, `worker`), the client, and a live drill.

**Done 2026-10-01.** (a) `Entity.anchored` and `captures`; a capture in both kinds
of death, by a player's blow only, so dominators never take one another and a
team does not win without playing; an anchored turret leaves other dominators
alone; the Dominator, class 51. (b) `MatchMode.DOMINATION`, 6; `Domination`: three
on the middle line, the 60 s hold, what each team holds; `RoomThread`; the tally
placing teams by holds. A server-level test plays the real 60 s: team 1 given all
three, the match ended by the hold, well inside its 110 s clock, team 1 first
with no kills. (c) The platform and the worker needed nothing. Drilled from the
release: six matched three a side, a neutral dominator seen, the five minutes a
draw, each paid, no rating moved. Mutation-checked 21 of 21, after closing two
gaps the list showed (a body blow's capture; the room's wiring) and one a
survivor showed (a player's turret aiming at a dominator, tested now with the
shapes cleared away). Backend 728. **Item 30 done.**

#### 31. ~~Tag.~~ — **done 2026-10-01**

**Added 2026-10-01**, next on
[Q-27](requirements/01-scope-and-nfrs.md#7-open-questions)'s list; its choices
are [Q-30](requirements/01-scope-and-nfrs.md#7-open-questions), built on the
recommendation. Designed in
[01 §8.8](detailed-design/01-arena.md#88-tag-designed-2026-10-01-plan-item-31).
Parts: (a) `MatchMode.TAG` and the room's conversions, end and placement
(`handoff`, `arena`); (b) the client and a live drill.

**Done 2026-10-01.** (a) `MatchMode.TAG`, 7; `Tag`; the room registering teams at
a first spawn, converting from each tick's kill log, respawning a player on the
team they are on now, ending when one team has everyone, and handing the tally
the heads. Three server-level tests, the third built because heads and kills
agreed in the first two and a finish by kills would have passed: there b,
converted, takes c, the kills tied one a side, and only heads place a first.
Writing them found two things in the tests, not the code: an aim of 0 on the wire
is -π, so +x is 32 768; and a hook must read a stage from the teams, since a
respawn asked for can follow a death within one tick, which made one flake 2 in 3
until it did. Mutation-checked 12 of 12; a line that could never matter (a first
spawn's team, which is always the ticket's) taken out. (b) Drilled from the
release: six matched three a side, nobody firing, a draw at five minutes, each
paid; domination re-drilled through the routine the two now share. Backend 734.
**Item 31 done.**

#### 32. ~~Maze.~~ — **done 2026-10-01**

**Added 2026-10-01**, next on
[Q-27](requirements/01-scope-and-nfrs.md#7-open-questions)'s list; its choices
are [Q-31](requirements/01-scope-and-nfrs.md#7-open-questions), built on the
recommendation. Designed in
[01 §8.9](detailed-design/01-arena.md#89-maze-designed-2026-10-01-plan-item-32)
and [D-48](architecture/03-decision-log.md#d-48--a-maze-is-sent-as-a-seed-and-a-predicted-bullet-stops-at-its-walls-by-one-rule-on-both-sides).
Parts: (a) the generator, walls and their collisions (`sim`), the tick measured;
(b) `MatchMode.MAZE` and the Welcome's seed (`handoff`, `protocol`, `arena`);
(c) the client's generator held to the golden vector, its walls, and a predicted
bullet ending at one; a live drill.

**Done 2026-10-01.** (a) `MazeGenerator` and `Walls`: 65 walls, every cell
reachable for fifty seeds, the draw order pinned by seed 2026's walls in a golden
vector; tanks, shapes and drones pushed out to slide, bullets ended, spawns placed
clear. Measured with `TickBenchmark`, which takes a maze's seed now, two runs each
on the loaded VM: the public arena, without walls, unchanged (the same 3 860
entities at the end; a mean of 1.13 and 1.12 ms a tick against 1.14 and 1.11
before, p99 1.17 and 1.18 against 1.23 and 1.09); a maze match's room, 8 tanks and
120 shapes on 3 000 units, 0.06 and 0.05 ms a tick with its walls against 0.12 and
0.13 without, since bullets end at walls (31 alive at the end against 258), its
shapes' phase 0.011 ms at the median against 0.001 for pushing them out; nothing
allocated per tick in either. (b) `MatchMode.MAZE`, 8; the arena's seed the CRC-32
of the match's id; the Welcome's appended `mazeSeed`. Backend 750;
mutation-checked W1–W13 and Z1–Z5. (c) The client: `Maze`, the generator and its
random numbers in C#, equal to the golden vector the first time it ran;
`Welcome.MazeSeed`, read when the frame has it; `ClientWorld`'s walls; a predicted
bullet's wall tick found once at its create, the frame that reaches it removing it
there and reporting its hit, and nothing drawn past it. Client 95 tests;
mutation-checked 20 of 20, after two rules no test held yet were given one (the
first check is after a move; the radius counts). Drilled from the release: eight
matched, one seed for all, the CRC-32 of the match's id; 65 walls each; one
player's bullets, fired at a wall 204 units off, ended at a wall the client made;
a draw at four minutes, each paid, no rating moved. The first run failed on the
drill's own aim (T-26). 02 §3's Welcome row had missed three modes (DOC-13).
**Item 32 done.**

#### 33. ~~The sandbox.~~ — **done 2026-10-01**

**Added 2026-10-01**, last on
[Q-27](requirements/01-scope-and-nfrs.md#7-open-questions)'s list; its choices
are [Q-32](requirements/01-scope-and-nfrs.md#7-open-questions), built on the
recommendation. Designed in
[01 §8.10](detailed-design/01-arena.md#810-sandbox-designed-2026-10-01-plan-item-33),
[04 §4](detailed-design/04-platform-services.md#the-seventh-slice-the-sandbox-designed-2026-10-01-plan-item-33)
and [D-49](architecture/03-decision-log.md#d-49--a-sandbox-is-a-made-room-that-publishes-nothing-opened-by-a-request-its-powers-one-message).
Parts: (a) `MatchMode.SANDBOX`, the room that plays at once and ends at its clock
or empty, publishing nothing, a tank rebuilt at a level, a Guardian summoned, and
the `Sandbox` message (`handoff`, `sim`, `protocol`, `arena`); (b)
`POST /v1/sandbox` (`platform`); (c) the client and a live drill.

**Done 2026-10-01.** (a) `MatchMode.SANDBOX`, 9, with `made()` and `places()`:
a made room had taken its capacity from the roster, which a sandbox has none
of, and `MatchRules` and the registry had asked whether a mode was queued to
mean made. `Room.setLevel`; `arena/Sandbox`, with co-op's Guardian summoned and
enraged by the same code; the room's empty count, in which a stay waiting is
someone; `publish` dropping a sandbox's outcome whichever way it ends. Eight
server-level tests. Mutation-checked 31 of 31, after four rules the first tests
did not hold were given one (the empty count starting again, a value wrapping
past an int, a malformed or repeated message in a room that is no sandbox) and
one check that could never matter taken out. (b) `QueueService.openSandbox` and
`POST /v1/sandbox`; `MatchQueue.matched` records the mode, which a sandbox's
player was never queued with. Mutation-checked 15 of 15, the last after the
matcher's recorded mode was asserted; an account missing under a live session,
which cannot happen, is not checked for. (c) `OpenSandbox`, `SetLevel`,
`SummonGuardian`, a power asked before the Welcome not sent; client 98,
mutation-checked 9 of 9, the wire's numbers pinned as numbers. Backend 767, no
compiler warnings. Drilled from the release: a party of two, the member refused,
the leader's sandbox pushed to both, joined at once, level 45, a Guardian came
hunting; both left, the room over 63 s after it started, nothing paid and no
match recorded. **Item 33 done.**

#### 34. ~~The stale "not built" marks (DOC-14).~~ — **done 2026-10-01**

**Added 2026-10-01** on [Q-33](requirements/01-scope-and-nfrs.md#7-open-questions)'s
recommendation. A sweep of the documents found marks saying "not built" of
things built since, in the arena, networking, gateway, platform and threading
designs, the system topology, the plan's status and the defect register's
summary. Where a mark states the present, it is corrected; where it records a
dated slice's scope, it keeps it and says where the rest was built.

**Done 2026-10-01**, each mark checked against the code first: [DOC-14](defects.md#7-documentation).
**Item 34 done.**

#### 35. ~~The lobby's push backpressure.~~ — **done 2026-10-01**

03 §8, designed when nothing pushed: a slow client's pushes held in a small ring
per connection, the oldest dropped on overflow, and the client told to resync.

**Done 2026-10-01.** `gateway/Pushes`, one per connection on its own loop: held
while unwritable, sent in order when it drains, a backlog past sixteen replaced
by one `evt.resync`, a revocation never held, and a connection unwritable for
30 s closed and counted. Seven tests, six on a connection made unwritable by
hand and one through a running gateway; mutation-checked 14 of 14, after the
slow close a connection closed otherwise must call off was given a test. The
gateway's 44 tests pass; no other module changed. Drilled from the release:
`lobby`, `party` and `sandbox`, pushes all the way; the first run found the
sandbox drill asking for a room the directory had not yet seen freed (T-27).
**Item 35 done.**

#### 36. ~~A cold resume.~~ — **done 2026-10-01**

02 §10: an app killed and restarted has lost its resume secret; the lobby could
hand the stay back. Designed in 02 §10 and
[D-51](architecture/03-decision-log.md#d-51--a-cold-resume-is-the-devices-the-app-keeps-its-stays-secret):
the device keeps the secret, and the core resumes with it.

**Done 2026-10-01.** `MatchConnection.Resume(secret)`: a new connection that
dials and sends `Resume` in place of `Join`, then goes on as after any lost
connection, trying again for the minute and taking `Kick(1)` back to the lobby.
Nothing changed in the backend. Three client tests (client 101); mutation-checked
3 of 3. Drilled from the release (`coldresume`): a connection closed with no
`Leave`, as a killed app's is, and a new one resuming with the kept secret, to
the same tank; the spent secret refused after, the resumed stay untouched. 08 §3
had said the server did not offer this (DOC-15). **Item 36 done.**

#### 37. ~~In-room events, the kill feed first.~~ — **done 2026-10-01**

01 §9: the kill feed, a class available, the mode's score, effects, respawn
timers; designed and not built. The kill feed's audience is
[Q-34](requirements/01-scope-and-nfrs.md#7-open-questions)'s, built on the
recommendation; designed in [01 §9](detailed-design/01-arena.md#the-kill-feed-designed-2026-10-01-plan-item-37).

**Done 2026-10-01.** `Kill`, event 4, both sides: to the killer in every room,
to every player in a made match, tanks only. Three server-level tests (a
sandbox's kill told to both, a public one to its killer alone, a shape broken
told to nobody) and one in the client. Mutation-checked 6 of 6 in the arena,
after the shape rule was given its own test and a check that could never matter
was taken out, and 3 of 3 in the client. Backend 777, client 102. Drilled from
the release: in a sandbox the leader, at level 45, its points in bullets, hunted
and killed the member, and both were told the kill by name. Drilling it found
two misses of the drill's own (T-28) and that no tank's name reaches a client
(P-35, item 38). The rest of 01 §9 needs no event yet (Q-34).
**Item 37 done.**

#### 38. ~~Tank names reach the client.~~ — **done 2026-10-01**

**Added 2026-10-01** for [P-35](defects.md#2-protocol--the-client-contract),
found drilling item 37: a tank's create carries an empty name, and nothing else
carries one, so a client can name nobody it sees. Put before the rest of Q-33's
list for Q-33's own reason: it is the client's contract, best settled before the
Unity layer is written against it.

**Done 2026-10-01** ([D-52](architecture/03-decision-log.md#d-52--a-tanks-name-travels-in-its-create)).
The create's name is its player's display name: `Entity.name`, set with a
player's tank where it is spawned and where a resume makes it again, forgotten
with the slot; the encoder writes it, or nothing past 64 bytes. Both clients
read the field already. Four tests (the encoder's round trip, a slot forgetting,
a player seeing another by name, a tank re-made for a resume named too);
mutation-checked 5 of 5. Measured: no change in the mean payload at the design
density, +0.3 bytes (0.3 %) with twelve tanks to a view; the simulation the same.
Drilled from the release: in the sandbox, the member's tank seen by its name.
**Item 38 done.**

#### 39. ~~Team events pushed.~~ — **done 2026-10-01**

The gateway's "clan events wait for clans": teams exist since item 17. Which
are pushed, and to whom, is [Q-35](requirements/01-scope-and-nfrs.md#7-open-questions)'s,
built on the recommendation; designed in
[04 §2](detailed-design/04-platform-services.md#team-events-pushed-designed-2026-10-01-plan-item-39).

**Done 2026-10-01.** `evt.team.update` from `TeamService`: the team, as
`GET /v1/teams/mine` gives it, to every member after a join, a role set, a hand
over, a kick or a leave, and no team to one out of it, a disband's members
included; a refused action tells nobody. The client keeps `LobbyClient.Team`.
One test through the API, its pushes caught as a gateway would; mutation-checked
13 of 14, after a leader leaving alone was given a test; the one left is
equivalent (a team in the answer is an OK one). Drilled from the release: the
`team` scenario now in the lobby, each push arriving.
**Item 39 done.**

#### 40. ~~The result inbox retired.~~ — **done 2026-10-01**: kept, by decision

D-33: the `q:match-result` list kept as an inbox "until a later release".

**Done 2026-10-01** as a decision, not code:
[D-53](architecture/03-decision-log.md#d-53--the-result-inbox-stays-the-operators-way-to-put-an-entry-back).
The inbox's upgrade use never had an older release to serve, and its other two
are the operator's atomic put-back from the dead list and the worker's of a
deferred entry; without it each would be a pop and a stream add, able to lose
an entry between them. It stays. **Item 40 done.**

#### 41. ~~The owner's open rules.~~ — **done 2026-10-01**

A level rebate on respawn (01 §6), co-op's tiers (Q-7), and a penalty for not
coming to a match accepted (04 §4): each its own question, with a
recommendation, when reached.

(a) The rebate: [Q-36](requirements/01-scope-and-nfrs.md#7-open-questions),
built on the recommendation; designed in [01 §7](detailed-design/01-arena.md#7-deaths-respawn-spectate).
**Done 2026-10-01**: two server-level tests (a public respawn keeping a quarter
of a level-10 life, and level 20's of a level-45 one; a sandbox's respawn at
level 1); mutation-checked 5 of 5, after a cap that could never matter was taken
out. Not drilled: the rule is the arena's alone, and its tests run through a
real arena server; a client sees only its level in `Stats`. Reading `respawn`
for it found P-36, fixed first.

(b) Co-op: [Q-37](requirements/01-scope-and-nfrs.md#7-open-questions), built on
the recommendation: a wave cleared pays, tiers wait; designed in
[01 §8.5](detailed-design/01-arena.md#85-co-op-waves-designed-2026-09-29-plan-item-6).
**Done 2026-10-01**: `MatchTally.addScore`, `RoomThread.payTheWave` as
`Waves.cleared()` rises past what was paid. One server-level test, three players
(one there, one lost with a stay waiting, one gone): 100, 100 and 0; its first
version cleared one tank of a three-tank wave and was corrected. Mutation-checked
5 of 5. Drilled from the release: `coop`, a wipe in the first wave as ever, paid
as before. Running the arena's tests whole found T-29 in (a)'s test, fixed.

(c) A penalty for not coming: [Q-38](requirements/01-scope-and-nfrs.md#7-open-questions),
built on the recommendation, which is none yet: a no-show cannot be told from a
dropped connection, and the counters to watch the rate exist (04 §11).
**Item 41 done.** With it, Q-33's list is done.

#### 42. ~~NFR-2 per connection.~~ — **done 2026-10-01**

**Added 2026-10-01** on [Q-39](requirements/01-scope-and-nfrs.md#7-open-questions)'s
recommendation. NFR-2 is checked as a profile's bytes over its clients; one
connection's own rate, its p50 and p95 across connections, is not measured.
Designed in [02 §13](detailed-design/02-networking.md#13-what-to-measure).

**Done 2026-10-01.** `backend_arena_connection_bytes_per_second{profile}`: each
connection's snapshot bytes over its time from its first frame, observed as its
channel closes, however it ends, if it lasted a minute; both sends now go
through one `deliver`, so neither can be missed. One server-level test;
mutation-checked 6 of 6, after its short connection was made to receive frames
before leaving. The load drill reports it as each bot ends, its buckets sorted
by bound (O-5). Measured with 150 bots for two minutes: mean 1 144 B/s down, p50
~1 250, p95 ~1 475, agreeing with the bots' 1.12 KB/s; about 10.4 MB an hour at
p95 with overhead and input, under NFR-2's 15. Not measured with
`TickBenchmark`, which has no connections: a frame now also adds its length to
one field. **Item 42 done.**

#### 43. ~~What a match paid, pushed.~~ — **done 2026-10-01**

05's `evt.rewards`: the coins and experience a result gave, pushed when `worker`
applies it, where a client now asks its inventory until it changes.

**Done 2026-10-01**, designed in
[05 §6](detailed-design/05-worker-and-events.md#what-a-match-paid-pushed-designed-2026-10-01-plan-item-43):
the repository's answer lists what it paid each player new to the result, and
the consumer pushes it after the commit, before the acknowledgement, so a
redelivery tells nobody twice. One pipeline test against MySQL, its pushes held
to what the database applied; mutation-checked 5 of 5, after the loser's
placement was asserted too. Drilled from the release: a drawn duel, each player
pushed `{"mode":"duel","placement":1,"coins":50,…}`. **Item 43 done.**

#### 44. ~~An operator's notice to the lobby.~~ — **done 2026-10-01**

04 §10: a message to every player in the lobby, before a stop.
Designed in [04 §10](detailed-design/04-platform-services.md#the-third-slice-a-notice-to-the-lobby-designed-2026-10-01-plan-item-44).

**Done 2026-10-01.** `POST /admin/notice`: published once on `push:all`, which
every gateway also listens on, and handed to each connection through its
`Pushes`. Two tests (the admin call with its refusals and audit; a running
gateway telling two lobbies); mutation-checked 8 of 8. Drilled from the release
(`notice`). The runbook's weekly restart now begins with one. **Item 44 done.**

#### 45. ~~Leaderboards by rating.~~ — **done 2026-10-01**

04 §7: the rated modes' ratings ranked. Who is listed and how fresh is
[Q-40](requirements/01-scope-and-nfrs.md#7-open-questions)'s, built on the
recommendation; designed in [04 §7](detailed-design/04-platform-services.md#rating-boards-designed-2026-10-01-plan-item-45).

**Done 2026-10-01.** `GET /v1/leaderboards/duel`, `rffa` and `tvt`, with `/me`:
read from MySQL (V17's indexes), a player listed after ten rated matches, each
board's top kept thirty seconds, one's own place fresh. Three tests (the order
and listing, a place and its window, the API with its thirty seconds);
mutation-checked 9 of 9. Drilled from the release: after a duel the board
answers, and one rated duel is `not_ranked`. **Item 45 done.**

#### 46. ~~The gateway's latency to platform.~~ — **done 2026-10-01**

03 §10's metric, not built.
Designed in [03 §10](detailed-design/03-gateway.md#10-what-to-measure).

**Done 2026-10-01.** `backend_gateway_platform_seconds{route}`: each call
`PlatformClient` makes, observed as it completes, answered or failed, by the
API's path. Three tests (a lobby's match request counted under its own route; a
failed call counted too; a 100 ms answer landing between 50 ms and 1 s, so the
unit is seconds); mutation-checked 6 of 6. Drilled from the release, the drill's
gateway now serving its metrics on 9198: a duel's two queue joins and two
accepts, each under its route. The joins took 0.22 s on average, cold, two
samples on the loaded machine; not judged from that. A sweep after it found
marks today's items had left stale (DOC-16), fixed. **Item 46 done.** With it,
Q-39's list is done.

#### 47. ~~A soak on this machine.~~ — **done 2026-10-01**

**Added 2026-10-01** on [Q-41](requirements/01-scope-and-nfrs.md#7-open-questions)'s
recommendation. Nothing has run for hours since item 23's failover; pushes,
notices, rewards, the kill feed and the rating boards came after it. Two hours
of bots that come and go, and duels throughout, judged on the second hour: does
anything grow that should not. Designed in
[07 §4](detailed-design/07-threading-and-performance.md#4-tick-budget-and-what-to-measure).

**Part (a) built 2026-10-01: the soak's tools.** `tools/SoakJudge`, 07 §4's
rules with a robust slope (the median of every pair's, Theil–Sen); eleven tests,
mutation-checked 32 of 32. `BotClient` with stays that end (`BACKEND_BOT_STAYS`),
pinging its lobby every 30 s as a client does: the gateway closes a lobby silent
for two minutes, and a stay may be five. The drill's `SOAK=1`: the duel scenario
every 30 s, each process sampled (its live objects' bytes, threads, open files;
the store's key families and stream; MySQL's connections), the judge, and the
classes that grew most in each heap. Three trials of ten to twelve minutes, 40
bots, found three things in the measuring, each fixed before the run: the load
run's arena had no room for a made match (T-30); a heap's "used" after a
collection moved by ±6 MB between rounds under ZGC, where the class histogram's
total is the same to the byte; and the arena's live set fell 4 MB in a round
taken between two duels' rooms, which a least-squares slope read as growth. In
the trials every stay was paid (751 of 751), no error was logged and no bot was
kicked; the bots' own login throttles, alive for their first 15 minutes, are why
the judging starts later than that.

**Part (b) run 2026-10-01: two hours, twice; passed.** An hour into the first,
a build of j-redis beside it deleted the command-line client the store is
sampled with; the judge threw on the empty samples, and would have passed a
failed scan as every key at 0 (T-31: two tests, mutation-checked; every drill
now copies the jars it runs from). The second passed every rule, 29 of 29: no
live heap grows by more than 1.4 MB a day, no thread, open file, key family or
MySQL connection grows, the stream holds an entry a result, every stay was paid
(12 156), the 34 duel runs passed and nothing was logged as an error. The
arena's tick p99, 73 ms in its report, was checked against a plain load run and
the earlier ones: it follows the shared machine's hour, not the code; the
medians have not moved ([07 §4](detailed-design/07-threading-and-performance.md#4-tick-budget-and-what-to-measure)).
**Item 47 done.** With it, Q-41's list is done.

#### 48. ~~How long a lobby connection stays unwritable.~~ — **done 2026-10-01**

03 §10's last measure, not built: whether the 30 s cut-off suits mobile networks.

Designed in [03 §10](detailed-design/03-gateway.md#10-what-to-measure).

**Done 2026-10-01.** `backend_gateway_unwritable_seconds{end}`: each spell a
lobby connection could take no more, timed in `Pushes` by its event loop's
clock from its becoming unwritable to its draining (`drained`) or its close
while still unwritable (`closed`, the 30 s rule's among them, at 30 s). Three
tests on `Pushes` with a frozen clock (a drained spell, two spells, a close
after draining not counted; the 30 s close and a client leaving mid-spell;
a connection keeping up never counted) and the running gateway's slow
connection timed; mutation-checked 9 of 9. Not drilled: no drill client stops
reading, and the series appears with the first spell; a connection made
unwritable by hand through a running gateway is the test. **Item 48 done.**

#### 49. ~~The second audit's register.~~ — **done 2026-10-01**

**Added 2026-10-01** on [Q-42](requirements/01-scope-and-nfrs.md#7-open-questions)'s
recommendation. Its reading ran during item 47's soak: four lenses (data,
concurrency, security and input, operations) over what was built since
2026-09-23, each candidate checked against the code before it was written down;
one measured (`EXPLAIN`). 28 held: D-32 to D-39, S-13 to S-19, T-32 to T-38,
O-6 to O-10 and DOC-17, in [the register](defects.md). Three are H. T-39, a
push lost once in a gateway test under the soak's load, is registered with them. What the
lenses checked and found sound is not listed; what was found already documented
as a choice was not registered.

**Done 2026-10-01**, with Q-42 and Q-43. **Item 49 done.**

#### 50. ~~Pushes follow the store (O-6, O-7, O-8).~~ — **done 2026-10-01**

A subscription that finds the primary as a command does: a demoted primary
closes its clients, and a subscriber's connection is checked as a command's
is, so a lost machine is noticed in seconds, not hours; `push:all` remembered
from the first; `evt.resync` to the lobby after the subscription comes back; the
subscription's state measured. The failover drill gains a demotion. In j-redis
and the backend both.

**Done 2026-10-01**, designed in
[03 §5](detailed-design/03-gateway.md#5-push-routing). j-redis 2.2.1 (its D-39):
a server made a replica closes its subscribers' and blocked readers'
connections, and the client pings a subscription every 5 s, closing one left
unanswered as it closes a stuck command connection; four tests, mutation-checked
6 of 7 (the pings' cancel on close, unobservable, kept). The drill found that a
connection told nothing did not follow either (O-11): the gateway's command
connection lost two lobby registrations to `-READONLY` after its subscriber had
already found the new primary, and `platform`'s idle leases two queue joins. A
connection whose search raises the epoch its client has seen now closes the
client's others found under a lower one, and an idle lease with a lower epoch is
closed rather than lent (its D-40); two tests, mutation-checked 6 of 8 (the two
left, kept, are invisible to a test); 229 in all. The
gateway subscribes to both channels before waiting for either, sends every lobby
`evt.resync` when its subscription comes back and reports it; `platform` and
`worker` count a push no gateway heard; three tests, mutation-checked 6 of 6.
The drill's `FAILOVER=demote` hands the store over as the runbook does, the old
primary alive: before, no subscription was on the new primary and the notice
was never heard; after, all three (the gateway's two, the arena's) were, and the
duel and the notice passed again against the same processes; the kill
(`FAILOVER=1`) still passes. The full build found two more, fixed with it: a
throttle test that could cross its window's end (T-40), and the compiler
warning every full build has had since item 41 (b) (DOC-18); and one for item
60, a store failure on a leased connection answered 500 (O-12). Backend 815
tests. **Item 50
done.**

#### 51. ~~A team's removal locks its members only (defect D-32).~~ — **done 2026-10-01**

**Done 2026-10-01.** `removeTeam`, reached by a disband and by a leader leaving
alone, reads the members by the team's key (`FOR UPDATE`, lowest first) and
clears each by their own id; every statement of it uses a key (`EXPLAIN`),
where the old `UPDATE … WHERE team_id = ?` walked all 4 975 rows of the
development database. Tested against MySQL with another player's row held: the
old statement waited past the test's five seconds. Mutation-checked: the old
statement and clearing one member are caught; the read without `FOR UPDATE`
and without its order are equivalent today (the team's lock comes before the
transaction's first plain read, and the key's order is the members'), kept so
that neither depends on it ([04 §2](detailed-design/04-platform-services.md#2-teams)).
**Item 51 done.**

#### 52. ~~One room at a time (S-13, T-38).~~ — **done 2026-10-02**

A player holds one queue, one match's grant or one tournament match at a time,
as 04 §4 says, sandboxes and tournament grants included; leaving gives back what
was promised.

Designed in [04 §4](detailed-design/04-platform-services.md#one-room-at-a-time-designed-2026-10-01-plan-item-52),
[D-54](architecture/03-decision-log.md#d-54--a-sandbox-is-held-by-a-key-of-the-players-own-for-as-long-as-its-room)
and [Q-44](requirements/01-scope-and-nfrs.md#7-open-questions) (recommended:
the tournament's call first, as far as it can without taking a match from
anyone).

**Part (a) built 2026-10-01: a sandbox is held until it ends** (S-13). A key of
the player's own, `sbx:{playerId}`: `platform` takes it for each player when it
opens one, refusing a second (409 `in_sandbox`), and gives back those it took if
any member holds one or no arena has a room; the arena keeps it for the room's
life from the join, a lost connection's included, and gives it back when the
player leaves, does not come back, or the room ends. Leaving the queue's record
of a sandbox not yet joined revokes the ticket and gives the hold back; one
joined keeps it, and a duel queued for from inside it takes nothing from it.
Two `platform` tests, two arena tests; mutation-checked 12 of 13, the one left
kept (a give-back for a connection the room had already let go, wrong only
within the 40 ms before its last drain). The drill's `sandbox` asks for a second
from inside the first, both players' queue records left: refused, 409
`in_sandbox`. Backend 819 tests.

**Part (b) built 2026-10-02: a tournament's call comes first** (T-38), on Q-44's
recommendation unless the owner decides otherwise. The scheduler writes
`tcall:{playerId}` with each grant, for the ticket's life; the queue and the
sandbox refuse a player called (409 `in_match`); the matcher drops a queued entry
with a member called, the party whole, telling them, and does not lock out one
asked who stayed silent because they were called; one already matched or playing
keeps that match. Four tests (`worker`'s scheduler, two of the matcher's, one of
the API), mutation-checked 7 of 7. The first build after the mutation run
failed on a mutant still compiled: the runner had restored the source older
than the mutant's class, which the compiler then kept (a call written without
its expiry); the runners now touch what they restore, and the build was made
clean, every module. The drill's `tournament`: seed 1, called, asks to queue,
and is refused, 409 `in_match`; `duel` and `sandbox` pass with it. Backend 822
tests. **Item 52 done.**

#### 53. ~~Timeouts on every MySQL call (O-9).~~ — **done 2026-10-02**

**Done 2026-10-02**, designed in
[06 §7](detailed-design/06-persistence-mysql.md#7-connection-pooling). Every
pooled connection: connect 2 s, a statement with no reply 30 s, the session's
lock wait 20 s (told as 1205, which `Tx` retries, before the socket gives up).
The primary's probe: 2 s for both, a frozen server accepting TCP and never
greeting. Migrations: a connection of their own, uncut. Four tests against MySQL
behind a proxy that falls silent and an address that never answers; mutation-
checked 10 of 10, after two fixes to the tests: the pool probes as it is made,
so its time counts, and a pool on one host sets the driver manager's login
timeout for the whole JVM, which had bounded every probe in the test's JVM as
it never is in a process whose one pool is the primary's. The drill gains
`MYSQL_FAILOVER=freeze` (SIGSTOP the primary, promote the replica, time
platform's first answer): Before the fix, platform did not answer within 120 s of the promotion and the duel's registration was refused (503); after it, platform answered again 11.3 s after the promotion, 43 s after the freeze (most of that the promotion's own waits), the duel passed against the same processes, and the old primary was then rebuilt by clone and handed back. Backend 826 tests. **Item 53 done.**

#### 54. ~~The tournament decides by what happened (defects D-33 and D-34, T-36, T-37).~~ — **done 2026-10-02**

No result is not the same as a late one; a match cut short is not a finish; the
bracket seeded from the entries it locks; tickets written before the claim
counts.

Designed in [04 §6](detailed-design/04-platform-services.md#deciding-by-what-happened-designed-2026-10-02-plan-item-54)
and [Q-45](requirements/01-scope-and-nfrs.md#7-open-questions) (recommended:
a result decides however late; the clock only a match nobody came to; one
somebody came to waits up to 30 minutes; a match cut short is not a finish).

**Part (a) built 2026-10-02: the seeding and the tickets** (T-36, T-37). The
deadline is one transaction, `TournamentRepository.close`: the tournament's row
first, as a registration and a withdrawal take it, then the entries with a
locking read, then cancelled or seeded from them. A match's tickets are all
written before it is claimed; a failure before the claim, or a claim another
worker won, gives the room's promise back, the next tick trying again. Three
tests (a registration in flight while the deadline runs is in the bracket; the
deadline's cancel and stale version; the tickets' store unreachable leaves the
match pending and its room given back), and the test that had recorded the
half-written match as the 270 s rule's now says it is not claimed.
Mutation-checked: the old reading (a plain read before the lock, the entries
read plain), the claim first, the room kept, the version unchecked and the
cancel lost are caught; the entries' locking read and the lock's place each
alone are equivalent today, each covering the other, and both are kept. A lost
claim's tickets are revoked, and waited for: removing that looked harmless, and
the clean build's existing test of a lost claim, that only the claim's winner
leaves tickets, caught it. Drilled: `tournament` and `teamcup` pass. Backend 829
tests.

**Part (b) built 2026-10-02: deciding by what happened** (D-33, D-34), on
Q-45's recommendation unless the owner decides otherwise. The arena marks each
made match's arrivals, `marr:{matchUid}` for an hour (a sandbox's not); with no
result 270 s after the tickets the scheduler decides by the higher seed only a
match nobody came to, waits up to 30 minutes for one somebody came to, and lets
a result decide however late. MySQL records a result cut short
(`matches.cut_short`, V18), which the worker fills from the outcome, and the
scheduler decides such a match by the higher seed, as one whose arena died.
Four tests (the scheduler's wait and its cut, the record and its reading, the
arena's mark) and the pipeline's cut test checking the worker's flag;
mutation-checked 10 of 10. Marking only the
match's first arrival could not be told from marking each player's, the same
key: each player's now, one write each. Drilled: `tournament`, `teamcup`, `duel`
and `sandbox` pass. Backend 833 tests.
**Item 54 done.**

#### 55. ~~A ban reaches every stay (T-35).~~ — **done 2026-10-02**

**Done 2026-10-02**, designed in
[04 §10](detailed-design/04-platform-services.md#10-admin-api). A removal, by
a kick or a ban, also ends the player's stay waiting for a resume: one whose
socket had just dropped came back with its secret and played the match out. A
ban's kick says so (`"ban": true`), and every arena refuses that player's joins
for a ticket's life, 60 s, with `Kick(7)`: one banned after `evt.match.found`
and before joining was let in, the kick having found them nowhere. A kick alone
is not remembered, as the drill's `removed` expects: the first form remembered
every removal, which would have kept a kicked player out for that minute.
`backend_arena_joins_total` gains `removed`. Four tests, mutation-checked 8 of
8 (the flag read by the arena needed its own test, through the announcer).
Drilled: `banned`, `removed` and `play` pass. Backend 835 tests. **Item 55
done.**

#### 56. ~~The confirm step's races (T-32, T-33, T-34).~~ — **done 2026-10-02**

**Done 2026-10-02**, designed in
[04 §4](detailed-design/04-platform-services.md#4-matchmaking) ("Each step one
transaction") and
[D-55](architecture/03-decision-log.md#d-55--each-step-of-the-confirm-step-is-one-watched-transaction).
Asking, making, calling off and joining each read what they decide by under a
watch and write in one transaction, or nothing. Asking takes the entries out of
the queue as it marks their players asked, only if each is still queued in the
entry read: a player who left was asked all the same, as a record with no mode.
Making writes every ticket first, then records the players matched only if each
still accepts; a decline after the round's read was matched anyway, and a
matcher failing between ending the wait and writing the tickets left its players
asked for fifteen minutes. Calling off, and putting back for want of a room,
read the answers again. The join reads the lockout with what it writes. An asked
player's record lasts 60 s, as the match's wait does. A party that changes while
asked is out of that match, nobody locked out (`withdrawn`, a new answer and a
new `confirms_total` outcome), and a party asked takes no newcomer.
Ten new tests, one replaced (taking a lineup and putting back what another took
is now one step), and the two HTTP tests that asked players never queued now
queue them first; mutation-checked 26 of 27, the survivor the join's watch on
the locks, a race no test drives (04 §4). Drilled: `duel`, `decline`, `party`
(a member leaves the party while six are asked: the party is out, nobody locked
out, the three alone queued again, then all six asked anew) and `rffa` pass.
Backend 844 tests.
**Item 56 done.**

#### 57. ~~Blocks unseen, and limits on asking (S-14, S-15, S-16).~~ — **done 2026-10-02**

**Done 2026-10-02**, designed in
[04 §9](detailed-design/04-platform-services.md#9-notifications) ("Blocks
unseen, and limits on asking"),
[D-56](architecture/03-decision-log.md#d-56--a-blocked-players-friend-request-is-kept-and-hidden-from-the-one-who-blocked-them)
and [Q-46](requirements/01-scope-and-nfrs.md#7-open-questions). A blocked
player's friend request is kept as theirs and hidden from the one who blocked
them, so nothing the asker can read differs from any other request; unblocking
deletes it. On Q-46's recommendation: 50 friend requests out a player and 20 made
an hour, 20 team invitations out a team and 20 made an hour a player, the lists
the 100 and 50 newest, and every friend's presence in one read. A party
invitation tells `not_in_lobby` only to a friend of the invitee. Designing it found
D-40, lapsed requests and invitations never deleted: `worker`'s retention deletes
them now, by V19's indexes on their expiry. Ten new tests, three extended; mutation-checked 27 of 27 (a party invitation pushed
only to one in the lobby is not mutated: a push to one not there goes nowhere).
Drilled: `social` (a blocked request seen by its asker, `already_asked` again, a
party invitation to a stranger not in the lobby answered as sent) and `party`
pass. Backend 854 tests. **Item 57 done.**

#### 58. ~~A rating board's place, measured, then cheaper (defect D-35).~~ — **done 2026-10-02**

**Done 2026-10-02**, designed in
[04 §7](detailed-design/04-platform-services.md#7-leaderboards) ("A board's
place, measured, then cheaper") and
[D-57](architecture/03-decision-log.md#d-57--a-rating-board-is-indexed-by-its-listed-players-only-and-a-place-read-by-key-not-by-offset).
Measured first with a new tool, `tools/RankBenchmark` (200 000 accounts, 5 000
listed): a place in the middle or at the bottom of the duel board read every
account twice, about 200 000 index rows, 200 ms on a quiet machine. Each board
now has a generated column, the rating for a listed player and NULL otherwise,
indexed (V20), so the unlisted are in no range a board reads, and the window is
read by key. Measured after, with the same tool: 5 021 and 10 016 rows, 14 and
18 ms. MySQL would not read the index backwards for the rows before a player,
so they come by a deferred join (keys sorted, only the rows shown fetched); the
first form read whole rows and took 36 ms. A place still costs about twice its
rank. Three new tests (the window by key at ties and the bottom; a place and the top
reading only the listed, by the session's handler reads; the schema's threshold
the code's); mutation-checked 10 of 11, the survivor the deferred join, a cost
the benchmark shows and a unit test cannot. Drilled: `duel` (V20 applied to the
drill's database; the board answers, one rated duel `not_ranked`). Backend 857
tests. **Item 58 done.**

#### 59. ~~Replicas measured and alerted (O-10).~~ — **done 2026-10-02**

**Done 2026-10-02**, designed in
[architecture/02 §7](architecture/02-availability.md#7-a-replicas-health-measured-designed-2026-10-02-plan-item-59)
and [D-58](architecture/03-decision-log.md#d-58--a-replica-is-measured-by-what-it-has-applied-a-heartbeat-for-mysql-the-primarys-own-account-for-the-stores).
MySQL's replica is measured by a heartbeat (V21) every worker stamps on the
primary each second: a replica's lag is the primary's stamp less its own, both
the primary's clock, read every five seconds on a thread of its own, so a
stopped receiver or applier shows as a lag growing a second a second. Each
store's replicas are read from the primary's `INFO replication`: how many, how
far the slowest has acknowledged, how long since. The runbook alerts on both.
`promote-mysql.sh` prints the replica's last heartbeat and refuses one older
than five minutes, before it fences anything, unless told `--stale-ok`. Five new tests,
mutation-checked 13 of 13 (two only once the sample listed its slowest replica
first). Drilled: `MYSQL_FAILOVER=1 duel`, the worker reading the replica, held
30 s behind, 30.4 s behind, a promotion with a 10 s limit refused before it
fenced anything, and the promotion after the kill made with the heartbeat 31 s
old; its first run found the script promoting a replica whose heartbeat it
could not read, which it now refuses. And `FAILOVER=1 duel`: the store's replica
counted. Backend 862 tests. **Item 59 done.**

#### 60. ~~The second audit's lows (S-17, S-18, S-19, defects D-36 to D-39, T-39, O-12, DOC-17).~~ — **done 2026-10-02**

In three parts, each committed on its own: (a) input checked as the player API
checks it (S-17, S-18, S-19); (b) the data's (D-36, D-37, and D-38's tables
retention does not cover; its friend requests and team invitations were D-40,
item 57's); (c) the rest (D-39's rule, T-39, O-12, DOC-17).

**(a) done 2026-10-02**, designed in
[04 §1](detailed-design/04-platform-services.md#routes-matched-as-nginx-limits-them-designed-2026-10-02-plan-item-60-a)
and [04 §10](detailed-design/04-platform-services.md#checked-as-the-player-api-checks-designed-2026-10-02-plan-item-60-a):
an id in a path is at most 18 digits, so a longer one is a 404, not a 500 and a
trace; the four routes nginx limits are matched exactly, and nginx limits the
two it did not; an upgrade reads whether it can succeed before it hashes; the
admin API reads every body up to 4 KB, refuses control and direction characters
in what every client is shown, and audits a notice before it sends it. Four
tests, mutation-checked 16 of 16. Drilled: `guest` and `lobby` pass. Backend 866
tests.

**(b) done 2026-10-02**, designed in
[06 §4, §6 and §9](detailed-design/06-persistence-mysql.md#9-growth-and-retention)
and [D-59](architecture/03-decision-log.md#d-59--a-team-match-reads-its-teams-from-the-player-rows-it-locks-and-rates-them-once):
a team match reads its players' teams again from the rows it locks them by, so a
team disbanded while its result waited is not rated rather than failing the
apply on a null, and a team formed in that time is not rated either; teams are
rated only by the delivery that first applies the result. A team's roster is
entered in ascending player id, and wearing an item locks the player first. The
worker's retention deletes boosts 31 days after their end, keys 30 days after
their use (V22's indexes), and finished or cancelled tournaments 90 days after
they were to start (Q-47, on its recommendation unless the owner decides
otherwise). Seven tests, mutation-checked 16 of 19: reading the teams again by
the first read's snapshot survives, the same in every case while a player who
leaves a team waits a day to join another; the lock the purge first took on a
finished tournament guarded nothing that can change one, and was removed; the
keys' 30 days is a choice, not a rule, so no test pins it. Drilled: `teammatch`,
`teamcup`, `equip` and `boost` pass, the team match rated, and the worker's
retention ran its new purges, each a range on V22's index by `EXPLAIN`. Backend
873 tests.

**(c) done 2026-10-02**, designed in
[06 §8](detailed-design/06-persistence-mysql.md#8-migrations),
[03 §4](detailed-design/03-gateway.md#4-connection-lifecycle) and
[04 §1](detailed-design/04-platform-services.md#a-store-that-fails-is-503-however-it-was-reached-designed-2026-10-02-plan-item-60-c):
a backfill is never one statement over a populated table, but batches by key
once every writer writes the column (D-39's rule); `auth.ok` is answered once
the store has the connection's registration, so a push sent the moment it
arrives reaches the player, and a registration the store refuses closes the
connection (T-39, reproduced first with the store behind a proxy that holds
writes 300 ms); a store failing on a leased connection is 503, as by any other
path, a demotion's `READONLY` and too few replicas' `NOREPLICAS` included, alone
or as why a transaction was discarded (O-12). DOC-17's corrections: the
runbook's §4, 06's header and V11, and this plan's own table. Three tests,
mutation-checked 12 of 12. Drilled: `FAILOVER=demote lobby party notice`
passes, the store handed over with the old primary demoted and alive and the
scenarios run again on the same processes, no request answered an error. Backend
876 tests. **Item 60 done**: the second audit's 28 defects are all fixed.

#### 61. ~~Tournaments' entrants (Q-43).~~ — **done 2026-10-02**

On Q-43's recommendation unless the owner decides otherwise: ten rated matches
in the mode to enter.

**Done 2026-10-02**, designed in
[04 §6](detailed-design/04-platform-services.md#6-tournaments): a duel
tournament's entrant has ten rated duels, the mark that lists a player on the
duel board; a team's entry needs the team's own ten rated team matches, since a
team match rates the team and no player has a count in that mode (Q-43 records
that reading for the owner). Otherwise 409 `too_few_rated`, after `closed` and
before `full`. The drill's two tournament scenarios give their fresh players
and teams the ten as a fixture, by SQL on the drill's database, the duel one
first refused without them. One test and three test classes' fixtures,
mutation-checked 6 of 6. Drilled: `tournament` and `teamcup` pass, a fresh
player refused `too_few_rated` across the processes before the fixture. Backend
877 tests. **Item 61 done.**

#### 62. ~~The soak again, and every drill scenario, against one release (Q-48).~~ — **done 2026-10-02**

On [Q-48](requirements/01-scope-and-nfrs.md#7-open-questions)'s recommendation:
items 48 to 61 came after item 47's two-hour soak and changed paths that run all
the time (the heartbeat every second, the replicas read every ten, the confirm
step's transactions, retention's purges, the gateway's registration). The soak
by item 47's rules, then every scenario once against the same release, TLS
included.

**Done 2026-10-02**, against item 61's release. The soak, 300 bots for two
hours with stays of one to five minutes and a duel every 30 s, passed every
rule, 29 of 29: no live heap grows by more than 0.08 MB an hour, no thread, open
file, key family or MySQL connection grows, the stream holds an entry a result,
every stay was paid (12 227), the 34 duel runs passed and nothing was logged as
an error. The tick, which the soak does not judge: each full room's p99 28 to
30 ms against NFR-1b's 15, with 0 to 2 overruns, where item 47's was 73 ms with
68; the medians as they were (collision 0.26 ms, encoding and writing 2.5 ms),
so it is this shared machine's hour again, not the code. Then all 32 scenarios
in one run, 346 checks, every one passed, and over TLS `play` and `untrusted`.

On Q-48's order, the owner having made the economy's balance Claude's to choose
(2026-10-02), each designed at its turn, its numbers first cuts with their
reasons beside them:

#### 63. ~~Renaming: a player's display name, and a team's.~~ — **done 2026-10-02**

**Done 2026-10-02**, designed in
[04 §1](detailed-design/04-platform-services.md#renaming-designed-2026-10-02-plan-item-63)
and [D-60](architecture/03-decision-log.md#d-60--a-boards-name-is-the-one-the-database-holds-when-a-result-is-applied):
`PUT /v1/accounts/name` for a player, `PUT /v1/teams/mine/name` for a team's
leader, by a display name's rules, free and once in 30 days each (V23), the
first at once, so a guest's made name changes the day it is chosen: a first cut
of the balance the owner left to Claude, its reasons in 04 §1. The rename writes
the score boards' name, and `worker` now writes a board's name from the one
MySQL holds when it applies a result, so a match begun before a rename does not
undo it when it ends. A team's members are told. The tests were written first
but run only after the code, the machine held by item 62's runs; mutation-
checked 12 of 12. Drilled: `rename` and `duel` pass, the team's rename reaching
its other member's lobby through the gateway. Backend 881 tests; client 103.

#### 64. ~~Team applications: a player asks to join, a leader or vice leader answers (Q-14's "applications later").~~ — **done 2026-10-02**

**Done 2026-10-02**, designed in
[04 §2](detailed-design/04-platform-services.md#applications-designed-2026-10-02-plan-item-64)
on [Q-49](requirements/01-scope-and-nfrs.md#7-open-questions)'s recommendation:
a player in no team asks one to take them, seven days, five out at once, twenty
an hour; the leader and vice leaders find it in their inbox; accepted, the
player joins as by an invitation and their other applications go; declined, it
is kept until it lapses, so it is not sent again. Found designing: no API let a
player find a team, so with it came a search by a name's start and one team's
public view. V24; retention deletes the lapsed. Mutation-checked 20 of 20, four
of them only after the tests gained what they first lacked (a declined one not
counted out, nor withdrawn; an accept into a team full since; the hourly limit
over HTTP). Drilled: `apply`, `rename` and `social` pass, the leader told through
the gateway. Backend 885 tests; client 104.

#### 65. ~~Assists: credit to those who hurt a tank shortly before another finished it.~~ — **done 2026-10-02**

**Done 2026-10-02**, designed in
[01 §7](detailed-design/01-arena.md#7-deaths-respawn-spectate): a tank keeps a
ring of the last three players who hurt it; at its death each of them but the
killer who hurt it within five seconds is paid a quarter of the kill's
experience and counted an assist, in the tally, the result, `match_player` and
`player_stat` (V25), a result from an arena not yet upgraded counting none. The
numbers are a first cut of the balance the owner left to Claude, their reasons in
01 §7. Found writing it: the killing blow, recorded as a hit first, took an
assister's place; a lethal hit is now the kill only. Measured before and after,
alternately on the same machine, `TickBenchmark`'s tanks now tagged as a
player's are: the collision phase's p50 0.361 ms to 0.377, about 4 %, every
other phase as it was. The ring first sat on every entity, bullets and shapes
too, and that measurement rose in every phase, so it moved to a tank's stats.
Mutation-checked 17 of 17, two after tests were added (a bot's hit takes no
place; one player holds one place). Drilled: `play`, `duel`, `rffa` and
`teammatch` pass, their results carrying the field to MySQL; no assist happened
in those matches, so the path end to end is shown by the arena's test with a
real room and the worker's against MySQL, not by a drill. Backend 896 tests.

#### 66. ~~Round robin: a tournament where every entry meets every other.~~ — **done 2026-10-02**

In three parts: (a) the schema and the schedule, draws and standings; (b) the
scheduler; (c) the API, the client and a drill.

**(a) and (b) done 2026-10-02**, designed in
[04 §6](detailed-design/04-platform-services.md#round-robin-designed-2026-10-02-plan-item-66):
the operator chooses `format`, `elimination` or `round_robin` (2 to 8 entries);
a round robin's every round is written at seeding by the circle method, a bye no
match; a win is 3, a draw 1 (both first, cut short, or no result 270 s after the
tickets: nobody can be shown at fault), a loss 0; the standings by points, wins,
then seed pay places 1, 2 and 3. V26. First cuts of the balance the owner left
to Claude, their reasons in 04 §6. Mutation-checked 16 of 17: the seed's
tie-break survives, the same in every case, the entries being read in seed order
and the sort stable; the wins' tie-break survived until a test had two level on
points. Drilled: `tournament` and `teamcup` pass, an elimination as it was.
Backend 900 tests.

**(c) done 2026-10-02**: a tournament's view says its `format` and, for a round
robin, its `standings` by place; the client reads both, an older server's
answer as an elimination; the headless driver gains `roundrobin`: three
players, a round each sat out, walkovers, standings 6, 3 and 0, prizes by place.
The client's test was written with its code, not before it. Mutation-checked
4 of 4. Drilled: `roundrobin` and `tournament` pass across the processes, three
rounds a minute apart, each sat out once, standings 6, 3 and 0, prizes by
place. Backend 901 tests; client 105. **Item 66 done.**

#### 67. ~~Item levels: an item raised by spending on it, its modifiers growing, inside the 25 % cap.~~ — **done 2026-10-02**

**Done 2026-10-02**, designed in
[04 §8](detailed-design/04-platform-services.md#item-levels-designed-2026-10-02-plan-item-67):
an equipment item held is raised a level, 1 to 5, by
`POST /v1/inventory/{itemId}/level`, once a key, for 500, 1 000, 2 000 then 4 000
coins through the ledger (reason 4); each modifier × (3 + level) / 4, level 5
twice level 1, the 25 % cap a stat standing; the bonus a worn item gives, and so
its ticket's, is its level's. First cuts of the balance the owner left to Claude,
their reasons in 04 §8. Mutation-checked 9 of 9, one after a test gained a
holding of none. Drilled: `levels`, `equip` and `boost` pass: 600 coins given
through the ledger as a fixture, the drill's barrel bought and raised to level
2 for 500, charged once a key, level 3 refused. Backend 904 tests; client 106.

#### 68. ~~Gems: where they come from (tournaments, achievements) and what they buy; with it, what the shop sells and for how much.~~ — **done 2026-10-03**

In three parts, designed in
[04 §8](detailed-design/04-platform-services.md#the-catalogue-and-gems-designed-2026-10-02-plan-item-68),
a first cut of the balance the owner left to Claude: (a) the catalogue; (b)
gem prices; (c) where gems come from.

**(a) done 2026-10-02**: the release's shop sells the five pieces of equipment
for coins, 1 200 to 2 000, two of them from level 5, each price with its reason
in 04 §8; a test pins the shipped catalogue to them. Drilled: `lobby`, the
platform reading "5 offers on sale now".

**(b) done 2026-10-02**: an offer may be priced in gems (`"currency": "gems"`),
taken through the ledger's one path as coins are, which now moves either
balance; the release's boosts sell for 20 gems each, a convenience and never
strength; the shop's list says each offer's currency and a purchase's answer
both balances; the client reads both. Mutation-checked 8 of 8. Drilled: `gems`
(25 given through the ledger as a fixture, the release's boost bought for 20,
the next refused), `lobby`, `equip`, `boost` and `levels` pass; the drill found
a purchase short of gems told "not enough coins", now said of either. Backend
908 tests; client 107.

**(c) done 2026-10-03**: gems come from two sources, both paid by the system
through the ledger's one path, each once whatever retries. A tournament's
places, 30, 15 and 5, from four entries and whatever its coin prizes, each
roster member of a team placed paid them. And account levels: 20 at level 5 and
at every tenth level to 100, 220 in all, paid in the result's transaction for
the milestones above the level stored
([D-61](architecture/03-decision-log.md#d-61--a-levels-gems-are-paid-for-the-milestones-between-the-level-stored-and-the-new-one)),
told by `evt.rewards`' new `gems`. The draft had milestones to level 45, the
tank's cap, where the account's is 100; recut against the account's curve. The
nightly ledger check reconciles gems with coins in its one statement: on the
development data (5 363 players, 47 977 ledger rows) 0.2 s before and after,
three runs each, the client's start included, on the loaded machine.
Mutation-checked 26 of 26, one after its first form failed to compile and was
redone. Drilled: `milestone` (50 000 xp with level 4 stored, a stay paid 20
gems, told and in the wallet), `tournament` (the bracket's 30, 15, 5 and 5),
`roundrobin` (three entries, no gems), `gems`, `lobby`, `equip`, `boost`, `levels`
and `teamcup`, 113 checks in one run. That run found the drill's own shop
put in place of the release's whenever `equip`, `boost` or `levels` ran, so
`gems` could not share a run with them (T-41); the drill's items are now added
to the release's, 9 offers on sale. Backend 915 tests; client 107. **Item 68 done.**

#### 69. ~~The soak again, and every drill scenario, against item 68's release (Q-50).~~ — **done 2026-10-03**

On [Q-50](requirements/01-scope-and-nfrs.md#7-open-questions)'s recommendation:
items 63 to 68 changed paths that run all the time, and none has run for hours:
each hit a tank survives recorded for assists in every room's tick, the result's
transaction (the names read under the lock, assists, milestones), the
tournament clock. The soak by item 47's rules, then every scenario once against
the same release, TLS included.

**Done 2026-10-03**, against item 68's release. The soak, 300 bots for two
hours with stays of one to five minutes and a duel every 30 s, passed every
rule, 30 of 30 (item 62's and the store's tickets, a family this run's samples
caught): no live heap grows by more than 0.15 MB an hour, no thread, open file,
key family or MySQL connection grows, the stream holds an entry a result, every
stay was paid (12 121), the 34 duel runs passed and nothing was logged as an
error. The arena's biggest grower, `TankStats`, 300 objects to 4 946 for 302
tanks, was looked into rather than passed over: the world's pool of a slot's
stats, made once a slot and reset after, filling towards the slots tanks have
used (16 384 a room at most), its increments falling from 900 a sample to 11; the
assists' ring holds tags and slot numbers, not references
([07 §4](detailed-design/07-threading-and-performance.md#4-tick-budget-and-what-to-measure)).
The tick, which the soak does not judge: each full room's p99 15.3 to 15.5 ms
against NFR-1b's 15, no overruns, where item 62's was 28 to 30 ms; the medians
as they were (collision 0.25 ms, encoding and writing 2.3 ms), so assists cost
nothing that shows, and the hour was a quieter one on this shared machine. Then
all 37 scenarios in one run, 402 checks, every one passed, and over TLS `play`
and `untrusted`. **Item 69 done.** With it the list is done again; what follows
is the owner's ([Q-50](requirements/01-scope-and-nfrs.md#7-open-questions)).

**The owner, 2026-10-03** ([Q-50](requirements/01-scope-and-nfrs.md#7-open-questions)):
the client's own-tank prediction in the engine-free core, then more for players
to earn, seasons for the rating boards or achievements beyond the level
milestones; each with its design, docs and terms, and built whole. The Unity
layer stays deferred.

#### 70. ~~The own tank, predicted.~~ — **done 2026-10-03**

[08 §4](detailed-design/08-client.md#4-the-world-and-when-things-are-drawn)'s step 4, designed in
[02 §9](detailed-design/02-networking.md#9-input-prediction-and-reconciliation)
and [D-62](architecture/03-decision-log.md#d-62--the-own-tank-is-predicted-by-the-servers-movement-rule-from-what-two-events-carry):
the player's tank moves at once by their own input, by the server's movement
rule, and is corrected by every snapshot without a jump. The client cannot do
that from what it is sent today: a snapshot gives the tank's position, but not
its velocity (which carries recoil and knocks), nor how long the input the
server echoes has driven it, nor its acceleration and size. In three parts:
(a) the server's half, two events that carry them, no new protocol version;
(b) the client's half, the movement rule ported and pinned by a trajectory the
server's own simulation writes, the replay and the smoothing; (c) the live
comparison, the predicted position against the server's frame by frame, and
the bytes it costs measured.

**(a) and (b) built 2026-10-03.** The server's half: `Motion` (5) in every frame
to the player whose tank lives, `MotionRule` (6) on a change, counted and
compared by the view, put in only as a frame is sent, so a held or skipped round
leaves none behind; the acceleration worked out in one place (`Room.tankAccel`),
the move bits read in one (`RoomThread.directionX/Y`). Two live tests: an
empty map's tank, every frame's velocity the room's rule from rest tick by tick,
so the input's ticks are counted exactly; a rule once, then again for a worn
bonus and for a bigger body. A golden vector for the two events, written by the
spike's independent codec. The client's half: `TankMotion.Step`, held to
`motion-2026.txt`, the room's own steps of a driven tank (an open map's corner
and edges; along, round and out of seed 2026's walls; a knock into one), every
one of 891 steps bit for bit; `OwnTank`, its steps stamped, matched, replayed,
corrected, on a frozen clock; `MatchConnection` stamping with the seq that
carries them and the input sent. Mutation-checked: the server's half 17 of 17;
the client's 19 of 19, after two tests were added for survivors (that a
correction is drawn away rather than doubled; that an echo of no ticks is no
input even where seq 0 was stamped), one mutant that did not compile was
redone, and one survivor showed a guard to be redundant, so it was taken out.

**(c) done 2026-10-03.** The `prediction` drill, against a release: a walk of
twelve legs that turns, stops and goes diagonally, then the same walk firing.
Walking, 106 of 108 frames matched a step (the two not, before the first input),
no jump, the error's median 0.16 units (the wire's quarter unit), p95 0.92, the
largest 1.40; firing, median 0.13, largest 1.60. What is left is at a change of
direction, a tick of the old one at most, drawn away in 50 ms (02 §9). The drawn
tank ran 3.5 units ahead of the newest sample: about 100 ms hidden on a link of
no delay, mostly the input's 100 ms between sends. The bytes, 150 bots for two
minutes back to back with the release before: frames 77.0 to 83.2 bytes, each
connection 1 148 to 1 242 B/s (8 %), encoding's median 2.2 to 2.1 ms (the
loaded machine's p99 moves more than that between runs). `prediction` and `play`
pass. Backend 922 tests; client 124. **Item 70 done.**

#### 71. ~~More for players to earn: seasons for the rating boards, and achievements.~~ — **done 2026-10-03**

After item 70, on the owner's answer to Q-50; its numbers first cuts with their
reasons beside them, as the economy's are (Q-48). In two parts:

(a) **Seasons**, designed in
[04 §7](detailed-design/04-platform-services.md#seasons-designed-2026-10-03-plan-item-71-a)
and [D-63](architecture/03-decision-log.md#d-63--a-season-ends-in-three-steps-each-safe-to-repeat-its-places-its-gems-then-its-reset):
two calendar months each; at the end the places kept, paid in gems (100, 60, 40,
25 to the tenth, 10 to the hundredth, 5 to everyone listed), then every rating
halfway back to 1 200 and every rated count 0; V27; the worker's season job; past
seasons' boards; an operator's "end it now"; the client; a drill.

(b) **Achievements**, designed in
[04 §8](detailed-design/04-platform-services.md#achievements-designed-2026-10-03-plan-item-71-b)
and [D-64](architecture/03-decision-log.md#d-64--an-achievement-is-paid-when-a-result-carries-its-stat-across-the-threshold):
seventeen thresholds on the counted stats, 430 gems in all, each paid in the
result's transaction when it is crossed, told by `evt.rewards`, listed with
the player's progress; the client; a drill.

Found on the way, designing (a)'s rewards against the inbox's kinds, and fixed
first, on its own: **P-37**, a team's answerer could not read their inbox once
someone applied (item 64's kind had no name on the wire); a test now holds every
kind to a name.

**(a) done 2026-10-03.** V27 (`season`, `season_place`, season 1 made by the
migration to the end of its two months, a test holding the SQL's boundary to
`SeasonRepository.endAfter`); `SeasonRepository`, its places in one transaction
with the next season, its reset in batches with their progress; the worker's
`SeasonKeeper`, each minute under the store's lock, which a second runner, or
a run after a crash, cannot make pay or halve twice (tested both ways, and a
run cut short finished); `GET /v1/seasons`, `?season=` on the rating boards
and `/me`; `GET /admin/seasons` and `POST /admin/seasons/end`; the inbox's
`season_reward`; the client's `Seasons()` and the season on its board reads.
Mutation-checked 34 of 34: 30 in the server, two of them killed by the test's
timeout (a page of places that never moves on, a reset whose progress never
does), one after a test was added for a survivor (one's own place in a season
that never was); 4 in the client. Drilled: `season`, two players given the duel board's top as a
fixture, an operator's end, the worker's close 54 s later: 100 and 60 gems, the
inbox item and its push, the past season's board keeping the first, and the
first not listed in the new season; on the development data 42 places paid and
5 571 players reset in under 3 s. `apply` passes. Backend 938 tests; client 125.

**(b) done 2026-10-03.** `Achievements`, seventeen thresholds and 430 gems,
their reasons in 04 §8; the locking read joined to `player_stat`, so a result
is judged by the counts it locks and writes (a test holds the counts worked out
to the row written); each crossing paid in the result's transaction (reason 7,
keyed by the achievement), told by `evt.rewards`' `achievements`; `GET
/v1/achievements` with the player's progress; the client's `Achievements`. Its
tests seen failing against a core stubbed to reach and count nothing, then
passing. Mutation-checked 19 of 19: 17 in the server, 2 in the client, four of them
caught by tests added before the run (an achievement whose key was paid
already is neither paid nor told again; at the threshold is reached; the stat's
name on the wire; a session that is not one). Drilled: `achievements`, 49 stays as a fixture, a fiftieth
paid 10 gems and named in `evt.rewards`, listed as reached at 50; `season` again
(closed 46 s after the operator's end), `milestone`, `gems`, `lobby`.
Backend 943 tests; client 126. **Item 71 done.** With it, Q-50's choices
are built and the list ends again ([Q-51](requirements/01-scope-and-nfrs.md#7-open-questions)).

#### 72. ~~The soak again, and every drill scenario, against item 71's release (Q-51).~~ — **done 2026-10-03**

On Q-51's recommendation: items 70 and 71 changed paths that run all the time,
two events in every frame to every player, the result's transaction reading and
paying by the stats, a job each minute in every worker, and none has run for
hours. The soak by item 47's rules, then every scenario once against the same
release, TLS included.

**Done 2026-10-03**, against item 71's release. The soak, 300 bots for two
hours with stays of one to five minutes and a duel every 30 s, passed every
rule, 29 of 29: no live heap grows by more than 0.13 MB an hour, no thread,
open file, key family or MySQL connection grows, the stream holds an entry a
result, every stay was paid (12 136), the 34 duel runs passed and nothing was
logged as an error. The tick, not judged: each full room's p99 16.9 and 17.2 ms,
no overruns; collision's median as before, encoding and writing 2.4 ms against
item 69's 2.3, the own tank's two events a frame; the bots' frames 81.9 bytes
against 75.5, as item 70 measured. Every scenario, 40 now, found one fault, the
drill's: `sandbox`'s leader looked for the member from the middle of a map its
view does not cover, and a member spawned near an edge was never seen (T-42; 3
runs in 14 alone, a printed failure showing both places). Fixed, it passed ten
runs of ten; then all 40 scenarios in one run, 427 checks, every one passed,
and over TLS `play` and `untrusted`. **Item 72 done.** The list is done again;
what follows is the owner's ([Q-51](requirements/01-scope-and-nfrs.md#7-open-questions)).

**The owner, 2026-10-04** (Q-51): a board of teams with seasons; daily goals;
the economy enhanced with a revenue stream, real-money purchases coming into
scope; data work at Claude's judgement, backups and recovery strengthened; and
the Unity layer as scripts, as many as can be written and checked without
Unity. In the owner's order, each designed at its turn, its numbers first cuts
with their reasons (Q-48); what only the owner can supply (store accounts, tax,
the law) recorded as questions, not waited on.

#### 73. ~~A board of teams, with seasons as the players' boards have.~~ — **done 2026-10-04**

Q-40 left the teams' rating without a board, a player board being unable to
show it. A team listed after ten rated team matches, ordered as the player
boards are; `GET /v1/leaderboards/teams` and `/me` (the caller's team); and in
each season's close its places kept, paid, and its ratings halved back.

**Done 2026-10-04**, designed in
[04 §7](detailed-design/04-platform-services.md#a-board-of-teams-and-its-seasons-designed-2026-10-04-plan-item-73)
and [D-65](architecture/03-decision-log.md#d-65--a-teams-season-place-pays-each-member-who-played-for-it-that-season):
V28; `TeamBoards`, by the player boards' own queries, now taking their table and
columns (`RatingBoards.Columns`; the player boards' tests pass unchanged); a
season's first step writes the listed teams' places and their payees, each
member at the end who played one of the team's rated team matches in the season
(not one who played on the other side, joined late without playing, played a
cut-short or another mode's match, or before the season), each paid the place's
gems by the players' table; the teams' reset, once; `GET /v1/leaderboards/teams`,
`/me`, `?season=`; the client's `RankRow.TeamId`. Mutation-checked 19 of 19: 18 in the server, three of them caught by tests
added before the run (a player on the other side of a team's match; a past
place's window from second place; the top kept thirty seconds), and 1 in the
client. Drilled: `season`,
with a team at the top of the board of teams as a fixture: its player paid 100
for the duel board's place and 100 for the team's, a member who did not play
none for the team, both inbox items, the past season's board of teams keeping
it first; `teammatch` and `teamcup`. Backend 950 tests; client 127.

#### 74. ~~Daily goals.~~ — **done 2026-10-04**

More for players to earn: a few goals a day, met by play, each paid once, a new
set at each UTC midnight.

**Done 2026-10-04**, designed in
[04 §8](detailed-design/04-platform-services.md#daily-goals-designed-2026-10-04-plan-item-74)
and [D-66](architecture/03-decision-log.md#d-66--a-days-goals-are-drawn-not-stored-and-paid-as-results-meet-them):
seven kinds, each an easy and a hard goal, 100 to 300 coins, the three together
3 gems; three a player a day, drawn by a fixed function of player and day, so
the worker counting and the platform listing agree without a write; counted
and paid in the result's transaction, by the result's UTC day; V29, kept a week.
The draw is injected into the result repository: every existing test applies
with none, so no coin balance it checks moves with the date it runs on.
Mutation-checked 19 of 19: 18 in the server, one of them caught by a test
added before the run (the set listed as done once all three are), and 1 in
the client; two mutants were left out as equivalent, the ledger's key refusing
a second payment whether or not the goal or the set was met before. Drilled: `goals`, a new player whose day has a stays goal, the other
two met and it a stay short as a fixture: one stay met it and the set, 100
coins and 3 gems, told and in the wallet; `boost`, `equip`, `levels`,
`milestone`. Backend 956 tests; client 128.

#### 75. ~~The economy, with a revenue stream.~~ — **done 2026-10-04**

What a player can buy with money, how a purchase is confirmed and kept, and what
it buys; the shop's catalogue and its prices extended to suit (Q-48). **The
owner, 2026-10-04: no third-party payment or sign-in integration for now; the
payment flow simulated** (Q-52, D-68). In three parts: (a) gems for money, an
order a simulated provider confirms; (b) a season pass; (c) looks, tank skins.

**(a) Done 2026-10-04**, designed in
[04 §8](detailed-design/04-platform-services.md#revenue-designed-2026-10-04-plan-item-75)
and [D-68](architecture/03-decision-log.md#d-68--a-purchase-is-an-order-its-provider-confirms-granted-once-and-a-refund-is-taken-back):
V30's `payment_order`; five packs in `packs.json`, checked as the shop's
catalogue; an order made pending for a pack, the client's key making a retried
ask the same order; the simulated provider's `paid` or `declined` confirming it
once, a paid order's gems through the ledger (reason 9) and a player's first
paid order's as many again, keyed by the player, so once ever; an operator's
refund taking the gems and the bonus back as far as the balance allows (reason
10), the rest a debt that blocks ordering until an operator clears it, each
written with its audit row in one transaction (D-30); orders pending a day
expired by retention. The simulated provider runs only where the operator names
it (`BACKEND_PAYMENT_PROVIDER=simulated`); unset, every payment route answers
503 `payments_off`. The client's `Packs`, `PlaceOrder`, `GetOrder` and
`SimulatePayment`. Mutation-checked 35 of 35: 32 in the server (one, expiry of
every state, caught by its test never ending) and 3 in the client; three tests
were added before the run (an order pending exactly a day, a paid order left
alone, expiry past one batch), and a check the ledger's key made redundant was
removed (a count of the player's paid orders before the bonus). Drilled:
`purchase`, an order paid with the first purchase's bonus, a second word not
heard, one declined, 40 gems spent, the operator's refund taking 120 and leaving
40 owed, an order refused while owed, the debt cleared, the next paid without a
bonus, the balance its ledger's; `gems`. Backend 968 tests; client 130.

**(b) Done 2026-10-04**, designed in
[04 §8](detailed-design/04-platform-services.md#revenue-designed-2026-10-04-plan-item-75)
and [D-69](architecture/03-decision-log.md#d-69--a-season-pass-pays-each-tier-as-its-points-cross-it-each-track-to-its-own-mark-and-its-premium-never-pays-coins):
V31's `season_pass`; forty tiers of 250 points, the free track 150 coins a tier
and every fifth 5 gems, the premium track 15 gems a tier and every fifth a
boost, never coins; points earned in the result's transaction, 10 a result and
50 a daily goal it meets, for the season being played when it is applied; each
tier paid as the points cross it, on each track to its own mark, coins and gems
through the ledger (reason 11) and a boost into the inventory; premium bought
for 500 gems (reason 1, keyed by season and player), paying the premium tiers
reached at once, refused once the season has ended; passes kept six seasons.
`GET /v1/pass`, `POST /v1/pass/premium`; `evt.rewards`' `pass`; the platform
will not start if a tier names an item its table has not. The client's
`SeasonPass` and `BuyPremium`. Found and fixed
[T-43](defects.md#4-concurrency): two daily goals tests applied results at a
fixed date and would have failed from 2026-11-03. Mutation-checked 27 of 27: 25
in the server, one of them caught by a test added after the run (a second boost
of the same item added to the first), and 2 in the client; two tests were
strengthened before the run (a result's goals fixed, so its points do not hang
on the day's draw; the next season's points). Drilled: `pass`, 245 points as a
fixture, one stay to the first tier and its 150 coins told, premium bought
paying the first premium tier's 15 gems, a second tap answered as the first;
`purchase`, `goals` (its stay told 60 points), `milestone`, `boost`. Backend
975 tests; client 131.

**(c) Done 2026-10-04, and with it item 75**, designed in
[04 §8](detailed-design/04-platform-services.md#revenue-designed-2026-10-04-plan-item-75)
and [D-70](architecture/03-decision-log.md#d-70--a-tanks-skin-travels-in-its-own-ticket-field-and-is-told-by-an-event-beside-the-tanks-create):
skins, `items.json` type `SKIN`, each a number 1 to 255 on the wire, worn in a
slot of their own, the fifth, giving nothing and raised by nothing; five in the
release, sold for gems, three at 150 and two at 400; the number of the skin
worn and held in the ticket, in a field of its own, and in the queue entry and
the confirm step's lineup; kept on the tank's stats, a resume's too; each
client a tank is created for told it by the event `Skin` (7) in the frame with
the create, for a skinned tank only, with no new protocol version;
`GET /v1/content/skins`, versioned by its content. The client's `Entity.Skin`,
applied as state, and `Skins()`. Measured with `TickBenchmark`, two rounds each
way: mean payload 28.8 bytes before and after (its tanks wear none), the tick
and the encoding within the dev VM's run-to-run spread; a skinned tank costs 4
bytes as it enters a view. Mutation-checked 32 of 32: 29 in the server, two of
them caught by assertions added after the run (a sandbox's tickets), and 3 in
the client; two tests were added before it (the skin carried by a resume and
cleared with its slot; the table's version its content's CRC32). Drilled:
`skin`, the crimson skin bought for 150 gems and worn, the player's own tank
told it in the public arena, another wearing none told none; `play`, `phrase`,
`equip`, `gems`, `prediction`. Backend 984 tests; client 133.

#### 76. ~~Data work, and backups and recovery strengthened.~~ — **done 2026-10-04**

Partitioning, guests' clean-up and funnels, each done or deferred by measured
need; and backups made recoverable to a point in time, kept off the machine and
proved by restoring them, automatically. In three parts, the backups first, as
the owner asked about them (Q-51): (a) backups proved, kept off the site and
watched; (b) the tables' growth, measured and watched against its triggers;
(c) guests and funnels, measured.

**(a) Done 2026-10-04**, designed in
[06 §10](detailed-design/06-persistence-mysql.md#proved-kept-away-and-watched-designed-2026-10-04-plan-item-76-a)
and [D-71](architecture/03-decision-log.md#d-71--backups-are-proved-by-restoring-them-every-week-under-live-writes-copied-off-the-site-encrypted-and-watched-through-mysql):
a restore to a moment (`STOP_AT`, UTC), checked to hold nothing at or after it;
a weekly proof by timer, the newest dump and the copies restored onto the backup
machine's own scratch server while the primary writes, checked by what holds
under writes (every table and the newest migration restored, nothing committed
before the capture missing, the ledger reconciled); a copy off the site, hourly,
the binary logs flushed and the dumps and every closed binlog copy encrypted and
pushed by rsync over SSH, brought back and restored by a monthly proof, off
until the owner names the host (Q-53); V32's `backup_run`, written by each step,
from which every worker reports when each last succeeded and whether its last
run failed, with the runbook's alerts and a procedure for recovering to a
moment. Found and fixed [O-13](defects.md#6-operations): the drill reconciled
coins only. `backup-drill.sh` runs every step on MySQL servers of its own,
seeded with a copy of `backend_dev`: 23 checks, among them what must be refused
(a gap in the copies, a dump whose gems do not reconcile, a replay stopped
short, a moment before the dump). Mutation-checked 8 of 8 in Java and 13 of 13
in the scripts, three of those after the drill was strengthened and one guard
found doubled and removed. Drilled too: `play` from the release, the worker
reporting the backups' families and the database migrated to V32. Backend 987
tests; client 133.

**(b) Done 2026-10-04**, designed in
[06 §9](detailed-design/06-persistence-mysql.md#9-growth-and-retention) and
[D-72](architecture/03-decision-log.md#d-72--the-tables-growth-is-watched-against-measured-triggers-and-a-restore-not-the-purge-is-what-binds):
found and fixed [DOC-20](defects.md#7-documentation), a plan to partition two
tables that could not have been followed (foreign keys, no auto-increment id,
the ledger's key); measured instead with `tools/PurgeBenchmark`, the worker's
own batches, 11 600 rows a second for one-player results and 24 000 for four,
so 21 minutes a day at the design target in the worst case: no partitioning,
the 30-minute trigger kept. What binds is a restore, 24 000 rows a second read
back and the ledger kept for ever: a last-resort restore is to take at most 4
hours, and at 2, physical backups by the clone plugin, designed and built at
the trigger. Every worker reports the triggers: the last proof's restore time
(V33's `backup_run.seconds`), the growing tables' rows read fresh, a retention
run's time; the runbook's alerts read them. Mutation-checked 7 of 7 in Java,
one after a test was added, and the proof's seconds' recording in the scripts.
Drilled: the backup drill, 23 checks; `play` from the release, the worker
reporting the new gauges. Backend 989 tests; client 133.

**(c) Done 2026-10-04, and with it item 76**, designed in
[05 §11](detailed-design/05-worker-and-events.md#the-funnel-designed-2026-10-04-plan-item-76-c):
the funnel, `GET /admin/stats/funnel`, of the accounts made each day how many
played, came back within the week after their first day, reached level 5,
played rated, bought in the shop and paid money, each by now, one grouped query
by V34's index on `account.created_at`; and the guests measured,
`GET /admin/stats/guests`, those never upgraded and those idle 90 days, their
deletion deferred with its trigger (Q-22: past a million, or a quarter of the
players). Found and fixed [O-14](defects.md#6-operations): the restore drill's
check of a moment compared times in the session's zone where the application
writes UTC. Mutation-checked 12 of 12 in Java, two after the test was made to
tell them apart, and the moment's zone in the scripts; the funnel's tests
strengthened before the run. Drilled: `stats` from the release, a new guest's
stay one more registered, guest and played in today's funnel and one more guest
measured; the backup drill, 23 checks. Backend 992 tests; client 133.

#### 77. ~~The Unity layer, as scripts.~~ — **done 2026-10-04**: written, compiled against stubs, not run in Unity

The layer 08 §1 describes, written as a Unity package of scripts over the core:
what can be engine-free is put in the core and tested here; what must touch
Unity is kept thin. Without Unity on this machine nothing here is compiled
against the engine; the package says so, and what was checked. In two parts
([08 §8](detailed-design/08-client.md#8-the-unity-layer-as-scripts-designed-2026-10-04-plan-item-77),
D-73): (a) the core grows what the layer would otherwise compute, tested here:
the scene at a render tick, the sticks, the account kept; (b) the package's
scripts, compiled here against stubs of the Unity API.

**(a) Done 2026-10-04**: `Scene`, what a frame draws at a render tick, a tank or
a unit between its two samples (the world now keeps both, a frame without an
update a sample where it was), the angle the shorter way round, a bullet
extrapolated and none before its spawn, a shape where it is, the own tank in the
present or where its prediction draws it, the list reused; `RenderClock`, two
frames behind the newest, never past it nor back; `TouchSticks`, eight
directions past a dead zone, the aim, fire past half the reach, keys and a mouse
in the editor; `ISecureStore` and `AccountKeeper`, the first launch a guest kept,
a refused key forgotten and no stranger made of an upgraded player.
Mutation-checked 20 of 20, two redone as valid mutants; a redundancy found
(a newborn's first sample, never read) removed and four cases added before the
run. Drilled: `play`, `prediction`, `maze`, the world's new samples in every
frame. Client 144 tests; backend unchanged.

**(b) Written 2026-10-04, and with it item 77**:
[`client/Unity/com.backend.client`](../client/Unity/com.backend.client/README.md),
a UPM package of thin scripts over the core's built library
(`client/unity-package.sh`): `BackendClient`, which owns the core's objects and
pumps them, joins a grant, feeds the render clock and builds the scene, the
pause the match's lifecycle; `WorldView`, the scene in pooled sprites by a
`LookTable` asset, the world's y turned over there only; `TouchControls`, two
sticks or keys and a mouse; `FollowCamera`; `LobbyScreen` and `Hud`, the least
screens to play, in IMGUI; `SecureStores`, the iOS keychain by a small
Objective-C plugin and the Android keystore by a small Java one, no library,
`PlayerPrefs` in the editor only. **Not run, not in Unity**:
`client/unity-check.sh` compiles the C# against stubs of the Unity API it uses,
in each of four platform branches (a deliberate error in the iOS branch failed
that build only), and the Java against stubs of Android's; the iOS plugin is not
compiled here. Not mutation-checked: nothing here runs to be wrong, beyond its
types. The package's README lists what the owner checks first in the editor.


#### 78. ~~The soak again, and every drill scenario, against item 77's release (Q-54).~~ — **done 2026-10-04**

On Q-54's recommendation: items 73 to 77 changed paths that run all the time,
the result's transaction paying daily goals and pass points, the arena's
encoder telling skins, the worker's retention and its gauges, and none has run
for hours. The soak by item 47's rules, then every scenario once against the
same release, TLS included, and the backup drill.

**Done 2026-10-04**, against item 77's release. The soak, 300 bots for two
hours with stays of one to five minutes and a duel every 30 s, passed every
rule, 29 of 29: no live heap grows by more than 0.08 MB an hour, no thread,
open file, key family or MySQL connection grows, the stream holds an entry a
result, every stay was paid (12 159), the 34 duel runs passed and nothing was
logged as an error. The tick, not judged: each full room's p99 18.8 and 19.6 ms
against item 72's 16.9 and 17.2, one overrun, the medians as before (encoding
and writing 2.4 ms); the machine at load 12 on 12 cores through it, another
user's job on eight of them, and the one change on the tick's path since, item
75's skin event, measured then by `TickBenchmark` within the run-to-run spread.
Not measured again; the bots' frames 81.8 bytes against 81.9.

Every scenario, 44 now, in one run: 468 of 470 checks passed, and each failure
was a fault found. A tournament's prize was checked by the whole wallet, which
a daily goal met by a walkover fills too (T-44, the drill's own since item 74).
A party of three was not seen as three by all its members (T-45): its state is
sent whole, as an answer and as pushes, by paths nothing orders, and an older
state could stand. Fixed by
[D-74](architecture/03-decision-log.md#d-74--a-partys-state-is-sent-whole-with-its-version-and-the-client-keeps-the-newest):
a version a party, raised in each change's own transaction and carried by every
state, a state of no party naming the party it ended, and the client keeping
the newest (`PartyState`). Mutation-checked 20 of 20, 14 in the server and 6 in
the client, two of them first written so they did not compile and rewritten.
Not reproduced on demand: five runs of `party` alone against the old release
passed; the drill now prints each member's view when that check fails. Against
a release with both fixes, every scenario in one run, 470 checks, all passed,
the tournaments' checks meeting a seed's goal coins beside its prize twice;
over TLS `play` and `untrusted`, 17. The soak is not run again: the fix is the
parties', which its bots do not form. The backup drill, run beside the
scenarios, failed twice, neither the backups': the scenario drill's fixtures
stamped in this machine's CEST, two hours ahead of UTC, read as after its
moment (T-46), and its own writer killed in the middle of a write before the
exact count (T-47). Both fixed, it passed, 23 of 23. Backend 994 tests; client
148. **Item 78 done.** The list is done again; what follows is the owner's
([Q-54](requirements/01-scope-and-nfrs.md#7-open-questions)).

#### 79. ~~The review of everything built; one baseline migration; the READMEs and diagrams.~~ — **done 2026-10-05**

**The owner, 2026-10-04:** no new plan items; review and recheck all the work
done, code, comments, documents, packages, configuration and its values; a README
for the project and for each module and sub-project, with enough guide to use
it; the database as one complete schema and seed for a first launch, in place of
the migrations added along the way; and diagrams of the whole: architecture,
components, flows and sequences, algorithms, the schema, deployment. And a
question: is there an admin API for an admin panel, or should one be a separate
backend with a React frontend
([Q-55](requirements/01-scope-and-nfrs.md#7-open-questions)).

- **(a) The review.** Every module read by the same lenses as the audits: data,
  concurrency, security and input, operations, and documents against code. Each
  defect found gets an ID in the [register](defects.md), test first where a test
  can hold it, and a mutation check of each new rule.
- **(b) One baseline.** The owner chose squashing over keeping V1 to V34
  ([D-75](architecture/03-decision-log.md)): `V1__schema.sql`, every table by
  domain, and `V2__seed.sql`, the rows a first launch needs. The once-only
  exception to "never edit an applied migration"; forward-only again from V3.
  Proved by comparing `SHOW CREATE TABLE` of both against a private server.
- **(c) READMEs** for the project, each backend module, the scripts, the deploy
  files, and each client project.
- **(d) Diagrams** in [diagrams/](diagrams/README.md), Mermaid, rendered by the
  Mermaid CLI to check them.
- **(e) Verification:** a full build, the release, every drill scenario, TLS,
  the backup drill and the store's promotion, against one release.

**Done 2026-10-05.** The review found 64 defects, every one fixed, seven of them
H ([register](defects.md), P-38 to P-54, defects D-42 to D-51, M-17 and M-18,
T-48 to T-57, S-20 to S-26, O-15 to O-31, DOC-21): among them a crafted token that
deleted a player's session index so a ban ended nothing (S-20), a restore proof
that recorded nothing when it failed (O-15), a scratch server that could not start
on Ubuntu (O-16), the Unity client stuck signing in (T-48) and its lobby screen
stuck after a first match (P-38), and a player of the Unity build who could not
respawn (P-54, found while fixing P-53). Every new rule was broken on purpose and
caught, after two tests were strengthened and one written where a mutant
survived (defects D-44 and D-47). The squash (decision D-75) was proved by comparing
every table's `SHOW CREATE TABLE`: two differ, on purpose (defects D-41 and D-42).

Verified against one release: the backend's full build, 1 020 tests (a stale
expectation stopped it twice, each fixed and the build resumed from that module);
the client's 169 tests; the Unity package compiled against stubs, not run in Unity.
Every scenario, 44, took four runs: the first found the `achievements` fixture
broken by defect D-43's fix and P-53, P-39's own fault; the second, P-53's first
fix keyed on the wrong signal (P-54) and the drill outrunning the login limit
(T-56); the third, the maze check's lost frames (T-57); the fourth passed, 470 of
470. Over TLS 17 of 17; the backup drill 27 of 27; the store's promotion, `play`
before and after, 28 of 28; MySQL's, `play` and `duel` before and after, 87 of 87. Measured
with `TickBenchmark`, whose room T-53 changed, so not comparable with figures before
it: p99 1.24 and 1.10 ms of the 2 ms budget in two runs at load 5.5, mean payload
30.4 bytes; a few of the 20 000 ticks allocated, 14 then 7, varying run to run
where the simulation does not, with the steady-state test holding none. The list is done again; what follows is the owner's
([Q-54](requirements/01-scope-and-nfrs.md#7-open-questions), and Q-55 on an admin
panel).

#### 80. ~~Everything the backend builds and runs with, in the repository and in the release (Q-56, D-77).~~ — **done 2026-10-05**

**The owner, 2026-10-05:** Java 21 only and MySQL 8.4 LTS
([Q-56](requirements/01-scope-and-nfrs.md#7-open-questions)); the JDK, Maven,
MySQL and nginx packaged and deployed with the release, not installed as the
operating system's packages; every component and binary the backend needs to be
built and run committed to the repository, extracted; the client's toolchain not
included; the target RHEL 9.x. Designed in
[09](detailed-design/09-release-and-packaging.md).

- **(a) `vendor/`**: the JDK, Maven, MySQL 8.4 and nginx, with the sources nginx is
  compiled from, and the scripts that refresh them.
- **(b) The release** carries its own runtime, MySQL, nginx and j-redis; the units
  and scripts use them.
- **(c) The build from the repository alone**, `build-offline.sh`.
- **(d) Verified:** an offline build in a container with no network; the release on
  UBI 9.5 with nothing installed; every drill on the release's runtime and MySQL
  8.4; the backend's tests against MySQL 8.4.

**Done 2026-10-05.** `vendor/` holds Temurin 21.0.12.1+1 (every module and tool,
re-linked compressed so no file passes GitHub's 100 MB), Maven 3.9.16, MySQL
8.4.11 LTS (pruned; `libaio` and `libnuma` from RHEL 9's packages in its private
library directory) and nginx 1.30.5 compiled in Rocky Linux 9 with OpenSSL 3.5.9,
PCRE2 10.49 and zlib 1.3.2 built in, linking glibc alone (its newest symbol 2.34),
with the sources and the scripts that refresh it all. The release carries a 63 MB
runtime made by `jlink`, j-redis, MySQL and nginx; three new units
(`backend-mysql`, `backend-nginx`, `backend-store@`); the scripts and the drills
use what the release carries. Verified: `build-offline.sh` built the release in
a Rocky Linux 9 container with no network interface; `check-release-el9.sh` ran
it in UBI 9.5 with nothing installed (MySQL initialised, the platform migrating
and serving, the gateway, a worker, an arena on epoll, nginx over HTTP and HTTPS,
`nginx -t` on the shipped configuration) and `check-units-el9.sh` its units under
systemd 252, both mutation-checked; every scenario on MySQL 8.4, 470 of 470; its
failover, 87 of 87; TLS on the jlinked runtime, 17 of 17; the backup drill on 8.4,
27 of 27; the backend's database tests on 8.4 (persistence 167, platform 219,
worker 74), after T-58, tests that assumed port 3306. Found on the way: `jdeps`
cannot resolve the libraries' modules, and a computed module list had left
`jdk.httpserver` out unnoticed, so the list is named and guarded (09 §4.1).
**Not verified:** `LoadCredential` for a non-root service on RHEL 9's systemd 252
(a container cannot; it works on 255, 01 §2); SELinux enforcing; anything on the
owner's machines. The client's toolchain is not vendored, as the owner chose.

#### 81. ~~Guides: build and run, install on RHEL 9, vendor/ — tested as written.~~ — **done 2026-10-05**

**The owner, 2026-10-05:** guides for the vendored pieces, the deployment, the
build and running; asked whether a clone on a fresh hosting server is enough to
build, run and operate everything with them.

- **[Build and run](development/03-build-and-run.md)**: build, test (with MySQL
  from `vendor/`), run the stack by hand from a release, the drills, the checks.
- **[Install guide](operations/03-install-guide.md)**: a fresh RHEL 9 server and
  a clone to every process running, with nothing installed but `git`; operating
  it; three machines.
- **[vendor/README](../vendor/README.md)**: what each piece is for, moving a
  version, the security advisories to watch.
- **Tested as written:** `check-install-guide-el9.sh` runs the install guide's
  every step in UBI 9.5 with systemd and no network, on a plain copy of the
  repository; the other guides' commands run as written here.

**Done 2026-10-05.** `check-install-guide-el9.sh` ran the install guide's ten
steps as written in UBI 9.5 with systemd and no network interface, on a copy of
the repository's files: built with `build-offline.sh`, installed, MySQL
initialised, the schema migrated by the platform, all seven units `active`, a
registration through nginx `201`. Its first two runs found three faults, each
of which a fresh server would have hit: `jlink --strip-debug` needs `objcopy`,
which a minimal RHEL 9 lacks (O-32); MySQL's initialisation needed its log
directory before the unit makes it (O-33); the placeholder certificate's
`nginx -t` needed the directories the nginx unit makes (O-34, reproduced and
mutation-checked). `check-units-el9.sh` now starts the gateway, a worker and an
arena too (0 failed). The build-and-run guide's commands ran as written on this
machine: MySQL from `vendor/` and one test class against it, the build by hand
with the committed JDK and Maven, the stack by hand from the release (twenty
bots, no disconnect). **Not run:** `build-offline.sh` with every test in one go
(an hour); the clone itself and the firewall (`console` blocks); anything on a
real RHEL host, so `LoadCredential` for a non-root service stays unverified there
(the check gives the Java units their secrets by a drop-in, as
`check-units-el9.sh` does).

#### 82. ~~A server with Java and MySQL already installed; cryptography, with examples (D-78).~~ — **done 2026-10-05**

**The owner, 2026-10-05:** what to do when the server already has an old Java and
MySQL from its packages; whether the backend can do RSA, ECDSA, hashing,
encryption, signatures and X.509, and what it would need; then: update the
documents, with enough example code and usage for each scenario.

- **The server's own Java and MySQL** ([install guide §1](operations/03-install-guide.md#a-java-or-a-mysql-already-on-the-server)):
  - Java is never used: the units run the release's runtime.
  - MySQL's port is the one conflict. Stop the system's MySQL, or give ours another port
    (`MYSQL_PORT`, new in the guide).
  - Proved first by the failure (O-35): `check-install-guide-el9.sh` in Rocky Linux 9 with
    Java 8 and MySQL 8.0 installed and running stopped at our MySQL. Then it passed with
    `MYSQL_PORT=3307`: every unit `active`, a registration `201`, the system's MySQL still
    `active` on 3306.
  - Then the fresh UBI 9.5 run passed again with the default.
  - The check takes extra values (`GUIDE_VALUES`), and its header holds the image's recipe.
- **Cryptography** ([development/04](development/04-crypto.md), D-78): `backend/crypto-examples`.
  - 15 classes: hashing, HMAC, signed tokens, random tokens, AEAD, signatures, RSA-OAEP,
    key agreement with HKDF and KEM, PEM with Java and with `bcpkix`, certificates, a private CA,
    PKCS#12, TLS, and a probe for the release's runtime.
  - 35 tests, against FIPS 180-4, RFC 4231 and RFC 5869, and against `openssl` 3.0.13 both ways.
  - Mutation-checked over 14 broken rules. 13 were caught. The PSS-parameter mutant survived
    until an OpenSSL signature was added. The constant-time comparison cannot be seen by a test.
  - Found on the way: BouncyCastle's encrypted PKCS#8 needs its own provider (handed to the call,
    not registered).
  - The probe on the release's runtime: every check holds; PKCS#11 absent (09 §4.1).
- **Not verified:**
  - stopping the system's MySQL (the guide's `console` line), and MariaDB instead of MySQL;
  - a real RHEL host;
  - a hardware module, CMS, OCSP.

#### 83. ~~The API reference: HTTP, the lobby WebSocket, the admin API; a Postman collection.~~ — **done 2026-10-06**

**The owner, 2026-10-06:** full documents for the backend's HTTP and WebSocket endpoints,
with paths, parameters, examples and responses, and a Postman collection to test them.

- **[docs/api/](api/README.md)**:
  - [01](api/01-http-api.md), the player API: every `/v1/` route, in eleven sections.
  - [02](api/02-lobby-websocket.md), the lobby: frames, authentication, the 12 messages, the
    16 pushes, the errors, the limits, a whole session.
  - [03](api/03-admin-api.md), the admin API: 16 calls.
  - Each route with its body, its answer and its errors; the examples are real requests and
    answers.
- **[backend.postman_collection.json](api/backend.postman_collection.json)**: 131 requests in
  13 folders, one scenario with three players and a guest, each request testing its status
  and keeping what the next needs.
- **[lobby-example.mjs](api/lobby-example.mjs)**: the lobby with two players, a whole duel
  included, every answer checked. Postman's file format cannot hold WebSocket requests.
- **Verified** against a stack from the release (MySQL 8.4 from `vendor/`, empty; admin API
  on; payments simulated):
  - newman 6.2.2: 131 of 131 checks.
  - The walkthrough: every answer as expected.
  - The first newman run failed 5, all the collection's own: a request to the wrong listener,
    and a duel polled over HTTP, which the matcher never makes for players without a lobby
    connection (now in 01 §6).
- **The contracts were read from the code by three agents**, then held against the runs.
  - Found: DOC-22 (fixed); P-55 to P-59 and O-36, small and open.
  - P-55 was seen live: an order's `createdAt` is a millisecond apart between its creation
    and its reads.
- **Not verified:** anything through nginx; the match protocol (its own drills); the pushes
  no request of the runs triggered (given from the code).

---

### Completed 2026-09-23

1. **The public arena runs continuously.** Ten bots, ten open-match records,
   every one with placement 0, no match boundary.
2. **The match protocol.** `UpgradeStat` reaches the simulation; `ChooseClass`
   and `Phrase` are tolerated rather than fatal.
3. **The gateway.** A client reaches the lobby over a WebSocket, authenticates
   and is handed a live arena and a single-use ticket; sixty bots end to end.
4. **Leaderboards.** Best single-match score over three windows, written with
   `ZADD … GT` so redelivery cannot corrupt them.
5. **Content, levels and stat upgrades.** Eight stats, 45 levels, 33 skill
   points, four shape kinds; a player levels by playing and spends points over
   the real protocol.

**Every balance number shipped is a first cut that has never been played.** The
experience curve hits the design's four anchors by construction and the shape
values are diep.io's, because starting from numbers known to produce a playable
match beats inventing them — but "defensible" is not "tuned". They live in
tables so that tuning them is a data change.

## 2. How this roadmap is ordered

Four principles. The first three are things the previous version of this plan
got wrong.

**Retire the assumptions that can invalidate everything, first.** Q-1 (the
uplink) can make the capacity target physically impossible, and
[D-9](architecture/03-decision-log.md#d-9--deterministic-entities-are-simulated-by-the-client)
— client-simulated bullets — carries ~80 % of the bandwidth plan. Both are
cheap to test and were previously scheduled months in. They are now Phase 0.

**Get to something playable early, then deepen.** A thin end-to-end slice you
can play on a phone is worth more than several finished subsystems: it is the
only thing that validates the parts against each other, and on a solo project
it is what sustains momentum.

**Prove the scale-out before building the bulk of the features.** Replication,
streams and a second machine are far cheaper to add to a small codebase than to
retrofit after eight months of meta-layer code. Phase 4 therefore comes before
Phase 5, even though Phase 5 is what players see.

**Documentation is a deliverable of every phase, not a prelude to the work.**
See [§4](#4-documentation-as-part-of-the-work).

## 3. Phases

Each phase lists the documents it must produce or correct. A phase is not done
until they match what was built. The goals, exits and efforts are as set on
2026-09-23; the last column is where each stands on 2026-10-04.

| Phase | Goal | Exit criterion | Documents | Effort | State, 2026-10-04 |
|---|---|---|---|---|---|
| **0** | Retire the killer assumptions | Uplink measured (Q-1); tick cost measured on the real hardware (Q-3 — **harness written and passing here**, needs a run on the production machine); Unity spike proves client-simulated bullets look acceptable (D-9). **Numerical half done** — see [protocol-spike](../protocol-spike/README.md); the visual judgement and Q-1/Q-3 remain. | Close Q-1 and Q-3 in requirements; confirm or replace D-9 | **1–2 weeks** | Open: Q-1 and Q-3 wait on the owner's machines; the visual judgement on the Unity layer in the editor ([08 §7](detailed-design/08-client.md#7-order-of-work), step 6) |
| **1** | Thinnest playable slice | One machine, one room, FFA only, no matchmaking. Login → join → play → die → result persisted → leaderboard updated. Real client, real protocol, real MySQL. Bot harness from day one. | `01-arena` updated | 6–10 weeks | Done; the real client is the engine-free core and its headless driver (items 1 to 3) |
| **2** | Make the match real | 150 bots in a room, interest management, full delta protocol, reconnect and resume. NFR-1 and NFR-2 **measured**, not derived. | `01-arena` completed; NFR-1/NFR-2 targets replaced with measurements | 6–10 weeks | Done; NFR-1a, NFR-1b and NFR-2 measured on the development VM (NFR-2 per connection, item 42) |
| **3** | Product core (~1 000 CCU, one machine) | Multiple rooms and arena processes, matchmaking and allocation, accounts, progression, leaderboards, gateway, fixed-phrase chat, ops basics. **Soft-launchable.** | `03-gateway` written; `04-platform-services` rewritten for MySQL; `operations/01-deploy` completed | 3–4 months | Done but the certificate, whose domain and CA the owner has deferred (items 6 to 8) |
| **4** | Prove the scale-out (two machines) | j-redis streams and replication; `worker` split out; scripted failover rehearsed; load test to 10 000 | `05-worker-and-events` written; `architecture/02-availability` §5 gaps closed; `operations/02-runbook` completed with real commands | 2–3 months | Done but the load test to 10 000, which waits on Q-3's machines (items 9 to 14); availability §5 keeps two gaps |
| **5** | Meta-layer depth | Equipment and inventory, shop and currency, teams, tournaments | `04-platform-services` extended per module | 4–8 months | Done, every module and more (items 15 to 77) |
| **6** | Launch hardening | 24-hour soak at target load, failover drill under load, N−1 capacity measured, runbook exercised end to end. **The failover under load rehearsed on the development machine** (2026-09-30, item 23): every primary, 600 players, the runbook walked; the rest needs Q-3's machines | Every derived number in the docs replaced with a measured one | 1–2 months | Partly: the failover under load (item 23) and two-hour soaks after each list (items 47, 62, 69, 72, 78) on the development machine; the 24-hour soak at target load and N−1 wait on Q-3's machines |

**Total, as estimated on 2026-09-23: roughly 18–26 months for one developer**,
assuming the client was staffed by someone else. Since then the client has been
Claude's ([D-19](architecture/03-decision-log.md#d-19--the-client-is-an-engine-free-core-and-a-thin-unity-layer)),
built beside the backend, so the figure is kept as written, as history rather
than a forecast.

### Strongly recommended: soft launch after Phase 3

Even though v1 needs the full meta layer, **put it in front of real players at
Phase 3**, at whatever scale one machine gives. A thousand real players will
tell you which of equipment, shop, teams and tournaments actually drives
retention — which turns eight months of Phase 5 into work spent on evidence
rather than assumption. It does not reduce final scope; it re-orders what gets
built first inside Phase 5.

**Not taken.** Phase 5 was ordered by
[Q-12](requirements/01-scope-and-nfrs.md#7-open-questions)'s recommendation
instead, there being no players to ask; the analytics that would read a soft
launch are built (items 25, 27 and 76 (c)).

## 4. Documentation as part of the work

These documents exist to be correct, not merely to exist. Three rules keep them
that way. The design documents were all brought current on 2026-09-23, after a
period in which four of them described a system that no longer existed — which
is what these rules are meant to prevent recurring.

**A phase is not complete until its documents match reality.** The "Documents"
column in [§3](#3-phases) is an exit criterion, not a suggestion. Code that
ships with a document still describing the previous design has created debt
that costs more to repay later than to pay now.

**Decisions are recorded when taken, not reconstructed afterwards.** Every
significant choice goes in the [decision log](architecture/03-decision-log.md)
with its reasoning *and its cost*, while the alternatives are still fresh. A
decision reconstructed six months later is a rationalisation.

**Derived numbers are marked as derived, and replaced when measured.** Almost
every performance figure in these documents is calculated rather than observed.
Each one is labelled, and Phase 0, 2 and 6 exist partly to replace them. A
number that quietly loses its "estimated" qualifier is how a plan starts lying.

Two supporting habits, cheap and worth keeping:

- **Stale documents are labelled, not silently left wrong.** Nothing is marked
  stale today. When something becomes so, the label goes in the README
  immediately — a visible label is honest, an unlabelled stale document is a
  trap for whoever reads it next.
- **Links are checked mechanically.** Every internal link and anchor resolves
  (checked 2026-10-04, by GitHub's heading rules); a restructure that breaks
  them silently makes the documentation less trustworthy than none.

### Documentation debt to clear

| Document | Problem | Cleared in |
|---|---|---|
| [operations/02-runbook](operations/02-runbook.md) | Alert thresholds without real numbers: nothing is measured on the production hardware | Q-3 |

The operations documents' earlier gaps (per-process units, MySQL's deployment,
the failover commands) were cleared in Phases 3 and 4, each run for real. The
design documents were checked against the code by the review of 2026-09-26 and
by the sweeps of 2026-10-01 (DOC-14, DOC-16), and the documents as a set by the
sweep of 2026-10-04.

## 5. j-redis roadmap

Two features the architecture assumed and the store did not have. **Both were
deliberately scheduled in Phase 4**, not earlier: nothing before a playable
product needed either, a list queue — which j-redis had, and which the
original design used — being sufficient for a single-machine slice. Both were
released on 2026-09-29 (plan items 9 and 11); 2.2.1 followed on 2026-10-01
(item 50), a subscriber following the primary as every other connection does.

| Release | Scope | Needed by |
|---|---|---|
| **2.1 — Streams**, released 2026-09-29 | `XADD`, `XLEN`, `XRANGE`, `XREAD`, `XGROUP`, `XREADGROUP`, `XACK`, `XPENDING`, `XCLAIM`/`XAUTOCLAIM`, `XDEL`, `XTRIM`, `XINFO`; consumer groups with a pending-entries list; blocking reads. Needed a **JRDB format version bump**. | Phase 4, when `worker` becomes a separate consumer |
| **2.2 — Replication**, released 2026-09-29 (plan item 11) | `REPLICAOF`, handshake, base transfer plus command stream, read-only replicas, replication epoch for fencing on promotion | Phase 4, when the second machine appears |

Why both were expected to be tractable, as written before they were built, for
reasons that were properties of the existing code rather than optimism:

- The AOF already logs **effects, not commands** — a large `SPOP` is logged as
  `SREM`. That is exactly what replication needs for non-deterministic
  commands, so the replication stream can reuse the AOF encoder.
- The fork-free copy-on-write snapshot already produces a consistent
  point-in-time image while writes continue, which is precisely the primitive a
  replica's initial sync needs.
- Values derive from `TrackedValue` with built-in memory accounting, and
  persistence writes per key through `SnapshotSink`, so a new type is an
  encoder plus a type byte rather than surgery.

## 6. Risks

Ordered by how much damage they do, not by likelihood.

| Risk | Why it matters | Mitigation |
|---|---|---|
| ~~**The Unity client has no owner**~~ **Resolved 2026-09-27** ([D-19](architecture/03-decision-log.md#d-19--the-client-is-an-engine-free-core-and-a-thin-unity-layer)) | Claude writes it: its core and headless driver are built and drilled; the Unity layer is written as scripts (item 77) and has not run in Unity | The wire is a contract tested on both sides against the golden vectors. What remains is the editor and a phone, the owner's next step (Q-54) |
| The uplink cannot carry the target (Q-1) | Invalidates the capacity plan entirely; no architecture fixes it | Measure in Phase 0. It takes an afternoon. The snapshot budget is the only lever. |
| Client-side entity simulation (D-9) proves visually unacceptable | ~80 % of the bandwidth saving rests on it, and the protocol is built around it | The numerical half is done ([protocol-spike](../protocol-spike/README.md)); the visual judgement waits for the Unity layer in the editor (08 §7, step 6). The protocol is versioned, so a change to it is a new version, not a break |
| Scope: full meta layer in v1 | Phase 5 is the largest block of work and has the least technical risk — it is where a solo project quietly runs out of energy | Built (items 15 to 77), each module a first slice or more. What is left of the risk is whether players want what was built, which only players can tell; the analytics and the funnel are in place to read it |
| 18–26 months solo | Schedule risk, and a bus factor of one | Phases end in shippable states rather than half-finished layers. Designs and decision logs are written before code — j-redis is the precedent that this works. |
| Documentation drifts from the code | The docs become untrustworthy, and then unused, and then the design exists only in one person's head — which is the bus-factor risk with extra steps | The per-phase document exit criteria in [§3](#3-phases) and the rules in [§4](#4-documentation-as-part-of-the-work) |
| Production measurements never taken | The numbers in these documents are measured on the development VM, shared and loaded, or derived; none on the production hardware | Phase 0 for tick cost, Phase 6 for the whole system, both on the owner's machines. Q-3 stays open until then. |

## 7. Documentation order

Designs come before code, and some designs block others.

| # | Document | Blocked by | Needed for |
|---|---|---|---|
| ~~1~~ | ~~detailed-design/02-networking~~ | — | **done 2026-09-23** |
| ~~2~~ | ~~detailed-design/06-persistence-mysql~~ | — | **done 2026-09-23** |
| ~~3~~ | ~~detailed-design/01-arena update~~ | 1 | **done 2026-09-26**: as-built notes and "not built" marks |
| ~~4~~ | ~~detailed-design/03-gateway~~ | 1 | **done 2026-09-23** |
| ~~5~~ | ~~detailed-design/04-platform-services~~ | 2 | **done 2026-09-23**; extended per module in Phase 5 |
| ~~6~~ | ~~detailed-design/05-worker-and-events~~ | 2 | **done 2026-09-23** |
| ~~7~~ | ~~[operations](operations/01-deploy.md) completion~~ | 4, 6 | **done in Phases 3 and 4**, every procedure run for real; the alert thresholds wait on Q-3 |
