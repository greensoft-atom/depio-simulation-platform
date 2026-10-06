# backend

A self-hosted backend for simulation engine, played from a Unity app on iOS and Android, and the client's
engine-free core that speaks to it. Java 21 and MySQL on the server, its own
Redis-compatible store ([j-redis](j-redis-service/README.md)), C# on the
client. Sized for 50 000 concurrent players on three machines; the launch
target is 5 000–10 000 on two.

The design documents start at [docs/README.md](docs/README.md).

## Components

| Component         | What it is                                                                                                                                                                                                   | Speaks                                                                                                                                                  |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `arena`           | Runs the match simulation, several rooms a process, each on its own thread at 25 ticks a second, and sends each player snapshots at 15 a second. Never touches MySQL.                                        | Raw TCP with TLS to clients, port 9001 in the example settings; the `session` store for tickets and its directory entry; the `events` store for results |
| `gateway`         | Holds each player's lobby connection and forwards to `platform`; delivers pushes. Makes no product decisions.                                                                                                | WebSocket at `/lobby` behind nginx (8090 on loopback); HTTP to `platform`; the store's pub/sub                                                          |
| `platform`        | Everything between matches: accounts and guests, sessions, tickets, the matcher, parties, teams, tournaments, boards and seasons, the shop, items, payments, the social layer, and the operator's admin API. | HTTPS `/v1/` behind nginx (8080 on loopback); the admin API on its own loopback listener (9120); MySQL; the `session` store                             |
| `worker`          | Applies match results to MySQL exactly once and pays them, runs the tournament clock, closes seasons, keeps retention, reconciles the ledger, watches the replicas and the backups.                          | The `events` store's result stream, MySQL, the `session` store                                                                                          |
| j-redis `session` | Sessions, tickets, the arena directory, queues, parties, leases, the score boards, pub/sub.                                                                                                                  | RESP2, 6379; a replica on another machine                                                                                                               |
| j-redis `events`  | The result stream `s:match-result`, read by the group `rewards`.                                                                                                                                             | RESP2, 6380 in the example settings; a replica on another machine                                                                                       |
| MySQL 8.4 LTS     | The system of record: accounts, progression, economy and its ledger, teams, tournaments, history.                                                                                                            | 3306, TLS; a GTID replica on another machine                                                                                                            |
| nginx             | TLS for the lobby and the API. Never carries match traffic.                                                                                                                                                  | 443; 80 only for the CA's challenge                                                                                                                     |
| Client core       | `Backend.Client.Core`, .NET Standard 2.1: the wire, the world, the match connection, the lobby, the API, the own tank's prediction, what a frame draws.                                                      | All of the above, as a player does                                                                                                                      |
| Headless driver   | The core driven without Unity: the live drill's player.                                                                                                                                                      | Against a running stack                                                                                                                                 |
| Unity package     | `com.backend.client`: thin scripts over the core. Compiled against stubs of the Unity API; not yet run in Unity.                                                                                             | Through the core                                                                                                                                        |

Every port above is a setting; the examples are in `backend/deploy/env/`. Each
process serves Prometheus metrics on a loopback address of its own. The
machine layout, the protocols and the failover paths are drawn in
[docs/diagrams/01-system.md](docs/diagrams/01-system.md).

## Repository layout

| Path                                            | What it holds                                                                                                                                                                                                                                                                            |
| ----------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [`backend/`](backend/README.md)                 | The server: a Maven build of ten modules (`common`, `protocol`, `sim`, `arena`, `handoff`, `persistence`, `platform`, `worker`, `gateway`, `tools`), the release script, the operations scripts (`scripts/`) and the systemd units, nginx configuration and example settings (`deploy/`) |
| [`client/`](client/README.md)                   | The client: the core (`Core/`), its tests (`Core.Tests/`), the headless driver (`Headless/`), the Unity package (`Unity/com.backend.client/`), the stubs that compile it (`UnityCheck/`), and the live drill (`headless-drill.sh`)                                                       |
| [`docs/`](docs/README.md)                       | Requirements, architecture and the decision log, detailed designs, operations, the plan, the defect register, the glossary, research                                                                                                                                                     |
| [`j-redis-service/`](j-redis-service/README.md) | The data store: server, client library, CLI and tools, a project of its own with its own documents                                                                                                                                                                                       |
| [`protocol-spike/`](protocol-spike/README.md)   | The golden vectors, written by an independent codec, that bind the server's wire to the client's                                                                                                                                                                                         |
| [`java21-offline/`](java21-offline/README.md)   | Every third-party library the build needs, for a machine with no network                                                                                                                                                                                                                 |
| [`vendor/`](vendor/VERSIONS.md)                 | What the backend is built with and runs on, committed ready to use: the JDK (Temurin 21), Maven 3.9, MySQL 8.4 LTS, nginx 1.30 compiled for RHEL 9 and the sources it is compiled from, and the scripts that refresh them (D-77)                                                         |
| [`build-offline.sh`](build-offline.sh)          | Builds j-redis, the backend and the release from the repository alone, with no network: `vendor/`'s JDK and Maven, `java21-offline` as the only repository                                                                                                                               |
| `*.tar.gz`                                      | Not in the repository (ignored): packed copies made by hand on this machine, for carrying a directory elsewhere                                                                                                                                                                          |
| [`CLAUDE.md`](CLAUDE.md)                        | The standing instructions for work in this folder, with every command                                                                                                                                                                                                                    |

## Status

As of 2026-10-05, [plan](docs/plan.md) items 1 to 79 are done: 1 020 backend
tests and 169 client tests pass, all 44 drill scenarios pass against one
release (and the TLS ones over TLS), and a two-hour soak of 300 bots holds flat. Nothing has
run on the production hardware, and nothing has launched. What is open is the
owner's: opening the Unity package in the editor, the production machines'
measurements (Q-1 to Q-4), a real payment provider (Q-52), where the backups'
copy off the site goes (Q-53), and the certificate's domain and CA
([open questions](docs/requirements/01-scope-and-nfrs.md#7-open-questions)).

## Guides

- **Build, test and run** the backend from a clone, with what the repository
  carries: [docs/development/03-build-and-run.md](docs/development/03-build-and-run.md).
- **Install it on a RHEL 9 server**, from a clone, step by step:
  [docs/operations/03-install-guide.md](docs/operations/03-install-guide.md).
- **The JDK, Maven, MySQL and nginx** the repository carries, and how to move
  their versions: [vendor/README.md](vendor/README.md).
- **Cryptography**: what Java 21 and BouncyCastle give, the rules, and tested
  examples of signing, encryption, hashing, X.509 and TLS:
  [docs/development/04-crypto.md](docs/development/04-crypto.md).
- **The APIs**: every HTTP route, the lobby WebSocket and the admin API, with
  real examples, and a Postman collection that runs them all:
  [docs/api/](docs/api/README.md).
- **Scaling and performance**: how to add capacity to each part, and what each
  costs as measured: [docs/operations/04-scaling-and-performance.md](docs/operations/04-scaling-and-performance.md).

## Developer quickstart

What the development machine has, none of it on the `PATH`:

| Tool                       | Where                                                                                                         |
| -------------------------- | ------------------------------------------------------------------------------------------------------------- |
| JDK 21 (Temurin 21.0.12.1) | `/opt/jdk21`                                                                                                  |
| Maven 3.9.16, offline      | `/opt/maven`; the local repository holds the bundle in [`java21-offline`](java21-offline/README.md)           |
| MySQL 8.0                  | `127.0.0.1:3306`, user `backend`, password `backend-dev-password`, databases `backend_dev` and `backend_test` |
| .NET 8 SDK                 | `/opt/dotnet`                                                                                                 |

The same JDK and Maven versions are committed in `vendor/jdk-21` and `vendor/maven-3.9`:
`build-offline.sh` builds with them and nothing else from the machine.

j-redis is ours and not in the offline bundle: install it into the local
repository from source before the first backend build.

```bash
export JAVA_HOME=/opt/jdk21 PATH=/opt/jdk21/bin:$PATH

# j-redis, once
cd j-redis-service && /opt/maven/bin/mvn -o install -DskipTests && cd ..

# The backend: build and test everything (the persistence tests need the MySQL above)
cd backend && /opt/maven/bin/mvn -o install && cd ..

# A release, which the drills run from
cd backend && MVN="/opt/maven/bin/mvn -Daether.enhancedLocalRepository.trackingFilename=_none" scripts/make-release.sh && cd ..

# Or all three from the repository alone, with no network (SKIP_TESTS=1 to skip the tests)
./build-offline.sh

# The client core's tests (read "Total tests:", not only the verdict)
cd client && DOTNET_NOLOGO=1 /opt/dotnet/dotnet test --logger "console;verbosity=normal" && cd ..

# The Unity package: the core built into it, then its scripts compiled against stubs (nothing run)
client/unity-package.sh && client/unity-check.sh

# The live drill: the whole stack from the release, the headless client against it
TMPDIR=<scratch directory> client/headless-drill.sh [scenario ...]
TLS=1 TMPDIR=<scratch directory> client/headless-drill.sh play untrusted

# The backups' drill, on private MySQL servers seeded from backend_dev
TMPDIR=<scratch directory> backend/scripts/backup-drill.sh
```

Every command, the benchmarks among them, is in [`CLAUDE.md`](CLAUDE.md#commands).
The drills use their own ports so as not to meet anything else on the machine.

**The database.** The schema is two Flyway migrations,
`backend/persistence/src/main/resources/db/migration/V1__schema.sql` (every
table) and `V2__seed.sql` (the rows a first launch needs), squashed on
2026-10-04 from the 34 steps the schema grew by
([D-75](docs/architecture/03-decision-log.md#d-75--the-migrations-are-squashed-into-one-baseline-before-the-first-launch)).
`platform` and `worker` apply pending migrations at every start. From V3 on,
migrations are forward-only: an applied one is never edited.

## Where to read next

- [docs/README.md](docs/README.md): the documents, and the order to read them in.
- [docs/diagrams/README.md](docs/diagrams/README.md): every diagram.
- Each backend module's own README:
  [common](backend/common/README.md), [protocol](backend/protocol/README.md),
  [sim](backend/sim/README.md), [arena](backend/arena/README.md),
  [handoff](backend/handoff/README.md), [persistence](backend/persistence/README.md),
  [platform](backend/platform/README.md), [worker](backend/worker/README.md),
  [gateway](backend/gateway/README.md), [tools](backend/tools/README.md),
  [scripts](backend/scripts/README.md), [deploy](backend/deploy/README.md).
- [client/README.md](client/README.md), and its projects:
  [Core](client/Core/), [Core.Tests](client/Core.Tests/),
  [Headless](client/Headless/), [UnityCheck](client/UnityCheck/) and the
  [Unity package](client/Unity/com.backend.client/README.md).
- [j-redis-service/README.md](j-redis-service/README.md): the store.
