# Backend documentation

Backend for a diep.io-style realtime multiplayer arena with a Unity mobile
client, self-hosted. Launch target **5 000–10 000 concurrent players** on two
machines; the architecture is sized to grow to 50 000 on three.

Documents are grouped by **what kind of document they are**, because they answer
different questions and change at different rates.

| Folder | Answers | Changes when |
|---|---|---|
| [`requirements/`](requirements/) | What must it do, and how well? | The product target changes |
| [`architecture/`](architecture/) | What shape is the system, and why? | A structural decision is made |
| [`detailed-design/`](detailed-design/) | How does each part work? | A component is designed or revised |
| [`development/`](development/) | How do I write code for it? | Conventions change |
| [`operations/`](operations/) | How do I run it and fix it? | Deployment or procedures change |
| [`research/`](research/) | What did we read and learn? | Rarely — it is reference |
| [`archive/`](archive/) | What did we used to think? | Never; it is history |

## Start here

New to the project, in this order:

1. [The project's front page](../README.md) — what it is, its parts, and how to build and run it.
2. The three words under [Vocabulary](#vocabulary) below, then the [glossary](glossary.md) as you go — the words that used to collide.
3. [Requirements and scope](requirements/01-scope-and-nfrs.md), §1 to §6 — the targets, constraints and non-goals. §7 is the record of every open question, read as needed.
4. [System topology](architecture/01-system-topology.md) and its [diagrams](diagrams/01-system.md) — four process types, three machines, where the capacity budget comes from.
5. [Availability](architecture/02-availability.md) — what breaks when something fails.
6. [Plan and status](plan.md), §1 — what actually exists today. The items below it are the history of how it was built.
7. [Decision log](architecture/03-decision-log.md) — why it is shaped that way: D-1 to D-19 shape the system; the rest are per feature, best read beside their design document.
8. [Defect register](defects.md) — what was found wrong and what closed it.

Then the detailed design of whatever you are working on.

## Contents

### Requirements
| Document | Content |
|---|---|
| [01 — Scope and NFRs](requirements/01-scope-and-nfrs.md) | Product scope, functional requirements (FR-1…FR-11), constraints (C-1…C-8), measurable NFRs (NFR-1a…NFR-10), capacity targets, non-goals, open questions Q-1…Q-54 with their recommendations and outcomes |

### Architecture
| Document | Content |
|---|---|
| [01 — System topology](architecture/01-system-topology.md) | The four process types, machine layout (and the launch on two machines, open), capacity derivation, client flow, data and control plane, technology, core principles |
| [02 — Availability](architecture/02-availability.md) | Failure table, degradation tiers, failover policy, what is not covered, the gaps left, failover rehearsed under load, a replica's health measured |
| [03 — Decision log](architecture/03-decision-log.md) | D-1…D-75: every significant choice, its reasoning and its cost, and what later revisited it |

### Detailed design
| Document | Content | State |
|---|---|---|
| [01 — Arena](detailed-design/01-arena.md) | Room model, tick loop, entities, wire classification, stats, the tank tree, physics, collisions, deaths and respawn, the ten match modes, in-room events, determinism | current; what is not built marked in place |
| [02 — Networking](detailed-design/02-networking.md) | Transport, wire protocol, snapshot format, client-side simulation, interest management, traffic profiles, reconnect | current |
| [03 — Gateway](detailed-design/03-gateway.md) | Position, lobby protocol, connection lifecycle, push routing, rate limiting, backpressure, failure behaviour | current |
| [04 — Platform services](detailed-design/04-platform-services.md) | Module layout, auth and guests, teams, rooms and tickets, matchmaking and every queued mode, tournaments, leaderboards and seasons, the economy (shop, items, gems, payments, the season pass, skins, achievements, daily goals), notifications and the social layer, the admin API | current |
| [05 — Worker and events](detailed-design/05-worker-and-events.md) | Streams, consumer loop, failure handling, the result pipeline, leaderboard rebuild, scheduled jobs, analytics and the funnel | current |
| [06 — Persistence (MySQL)](detailed-design/06-persistence-mysql.md) | Boundaries, schema, the three transactions, idempotency, concurrency, pooling, migrations, retention, backups | current |
| [07 — Threading and performance](detailed-design/07-threading-and-performance.md) | Thread model, allocation discipline, data structures, tick budget, JVM and Linux tuning | current |
| [08 — Client](detailed-design/08-client.md) | The Unity app: an engine-free core tested against the real server, a thin Unity layer, threads, recovery, testing, the Unity scripts | current: the core built, own-tank prediction included; the Unity layer written as scripts, not run in Unity |
| [09 — The release and what it runs on](detailed-design/09-release-and-packaging.md) | The platform (RHEL 9, glibc 2.34), `vendor/` (the JDK, Maven, MySQL 8.4, nginx), the build with no network, the release's runtime, MySQL and nginx, how it is verified | designed 2026-10-05 (plan item 80) |

### Development
| Document | Content | State |
|---|---|---|
| [01 — Code patterns](development/01-code-patterns.md) | Module layout, dependency set, patterns, coding rules, error handling, testing | current; each pattern says where the code differs from the first design |
| [02 — Working here](development/02-working-here.md) | How a unit of work is done, the owner's standing decisions, the development machine and its traps | current |
| [03 — Build and run](development/03-build-and-run.md) | A guide: build, test and run the backend from a clone with what the repository carries (the JDK, Maven, MySQL), the drills and the RHEL 9 checks | tested as written, 2026-10-05 |

### Operations
| Document | Content |
|---|---|
| [01 — Deploy](operations/01-deploy.md) | What runs where, conventions and secrets, j-redis and its replicas, nginx, rolling deployment, installing a machine, TLS for match traffic, MySQL and its replica, copies off the database's machine, the certificate |
| [02 — Runbook](operations/02-runbook.md) | Triage, stateful failover, per-component procedures, whole-machine loss, what to watch, routine, the admin API, restores |
| [03 — Install guide](operations/03-install-guide.md) | Step by step: a fresh RHEL 9 server and a clone of the repository to every process running, with nothing installed; then operating it, and three machines | run as written by `check-install-guide-el9.sh` |

### Project
| Document | Content |
|---|---|
| [Plan and status](plan.md) | What exists, every plan item with its outcome, the phased roadmap with effort estimates, documentation as a per-phase deliverable, j-redis roadmap, risks |
| [Defect register](defects.md) | Every defect found, by area (protocol P, data D, simulation M, concurrency T, security S, operations O, documentation DOC): what was wrong, how it was verified, what fixed it |
| [Glossary](glossary.md) | Every term in the system — code, schema, wire and prose — with what it means and where it appears |

### Diagrams
| Document | Content |
|---|---|
| [Index](diagrams/README.md) | Every diagram, and where each comes from |
| [01 — System](diagrams/01-system.md) | System context, the processes and stores with protocols and ports, the Maven modules, the main runtime flow, high availability |
| [02 — Arena and wire](diagrams/02-arena-and-wire.md) | A room's lifecycle, the tick, join and resume, snapshot encoding, the collision broadphase, a made match, draining, every message on the match connection |
| [03 — Lobby and store](diagrams/03-lobby-and-store.md) | The lobby's connect and auth, pushes across gateways, tickets, a room reserved for a match, the store's failover, slow clients, who writes each key family |
| [04 — Platform](diagrams/04-platform.md) | Accounts and guests, the arena seat, the queue and confirm, parties, teams, tournaments, payments, the season pass, the shop, the admin API |
| [05 — Data and worker](diagrams/05-data-and-worker.md) | Every table and its keys, the result pipeline and its transaction, retention, a season's close, the ledger check, the replica's heartbeat |
| [06 — Client](diagrams/06-client.md) | The core's types, the connections' lifecycles, join and resume, the own tank's prediction, the render clock, sign-in, the party rule, the Unity wiring |
| [07 — Deploy and operations](diagrams/07-deploy-and-operations.md) | The three machines, nginx's routes, the release, a rolling deploy, failovers, the backups and their timers, a restore to a moment |

### Research
| Document | Content |
|---|---|
| [iohao framework study](research/iohao-framework-study.md) | What the third-party framework does, what to borrow, why it cannot be used directly |
| [articles/](research/articles/) | 73 reference articles on backend architecture |

### Archive
Superseded, kept for history. Where these disagree with anything above, they lose.

| Document | Replaced by |
|---|---|
| [memstore](archive/memstore.md) | [j-redis-service](../j-redis-service/README.md) |
| [persistence-mongodb](archive/persistence-mongodb.md) | MySQL ([D-3](architecture/03-decision-log.md#d-3--mysql-is-the-system-of-record-mongodb-is-cancelled)) |
| [implementation-guide-2026-09](archive/implementation-guide-2026-09.md) | [plan.md](plan.md) |

## Related

| Document | Content |
|---|---|
| [The project's front page](../README.md) | What the project is, its components, the repository's layout, status, a developer's quickstart |
| [backend/](../backend/README.md) | The code's layout, build, the wire contract, benchmarks, bots; each module has a README of its own |
| [client/](../client/README.md) | The C# core, its tests, the headless driver and the live drill |
| [client/Unity/com.backend.client](../client/Unity/com.backend.client/README.md) | The Unity package: what was and was not checked, setup, the scripts |
| [protocol-spike](../protocol-spike/README.md) | The golden vectors and the spike that wrote them |
| [j-redis-service](../j-redis-service/README.md) | The data store, a project of its own, built and documented: server, client library, CLI, tools, guides and its own decision log |

## Naming

**The word "game" is not used** — not in prose, identifiers, file names, paths
or unit names. Neutral vocabulary instead:

| Instead of | Use |
|---|---|
| game, gaming | platform, backend, system |
| gameplay, gameplay traffic | match traffic, realtime traffic |
| game state | simulation state, world state |
| game logic | simulation logic |
| game modes, `GameMode` | match modes, `MatchMode` |
| game data, `gamedata/` | content, `content/` |
| meta-game | meta layer (progression, economy, social) |

Domain nouns that *are* the subject matter stay as they are — player, match,
room, arena, tank, bullet, shape, leaderboard, tournament, season. The rule is
about branding, not about refusing to name the domain. A third-party product
whose own name contains the word is referred to by its author (the iohao
framework), and a third-party bundle whose paths contain it is not kept in the
tree. This section is the one place the word is written, because a rule has to
name what it excludes.

### Vocabulary

Every term this system uses is defined once, in the **[glossary](glossary.md)**
— including the ones that collide. Three worth knowing before reading anything
else:

| Word | Means |
|---|---|
| **match** | one recorded unit of play; say **open match** or **timed match** when the difference matters |
| **session** | the authenticated lobby session, and nothing else |
| **battle** | the activity, never a unit — "in battle", "battle rewards" |

If you need a word the glossary does not have, add it there before using it.

The system is called **`backend`** wherever a namespace is needed:

| Thing | Form |
|---|---|
| Install root | `/opt/backend/`, symlinked to `/opt/backend-<version>/` |
| Configuration | `/etc/backend/` |
| State and logs | `/var/lib/backend/`, `/var/log/backend/` |
| systemd units | `backend-arena@<name>`, `backend-worker@<id>`, `backend-gateway`, `backend-platform` |
| Java packages | `com.backend.arena.*`, `com.backend.platform.*`, … |
| C# namespaces (the client) | `Backend.Client.Core`, `Backend.Client.Unity` |
| OS user | `backend` |

The four process types are **`arena`**, **`gateway`**, **`platform`** and
**`worker`**. The data store keeps its own name,
[j-redis](../j-redis-service/README.md), and its own stricter terminology rule:
no domain nouns at all there, because it is a general-purpose store.

## Conventions

- **Requirements are numbered and testable.** C-*n* for constraints, FR-*n* for
  functional requirements, NFR-*n* for measurable targets, Q-*n* for open
  questions. Other documents refer to them by number.
- **Decisions are numbered D-*n*** and never rewritten in place. A superseded
  decision keeps its entry and names its replacement, marked **Superseded** or
  **Revisited**. Data defects in
  [defects.md](defects.md) §3 also use D-*n*, for historical reasons (commit
  messages cite them): write "defect D-*n*" for those.
- **A document marked "stale" describes the old target** (one machine, browser
  client, MongoDB, Java 8). Read it for the parts that have not changed —
  usually the simulation logic — and trust the architecture documents where they
  disagree.
- **The schema is one baseline** since 2026-10-04: `V1__schema.sql` and
  `V2__seed.sql` under `backend/persistence/src/main/resources/db/migration`
  ([D-75](architecture/03-decision-log.md#d-75--the-migrations-are-squashed-into-one-baseline-before-the-first-launch)).
  A `V`-number above 2 in a document names a step of the history it was
  squashed from, kept in git.
- **Measured numbers say so.** Anything derived rather than measured is marked,
  because almost nothing in here has been run on the production hardware yet
  (Q-3).
