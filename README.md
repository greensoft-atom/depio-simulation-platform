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
| [`build-offline.sh`](build-offline.sh)          | Builds j-redis, the backend with every test and the release from the repository alone, with no network: `vendor/`'s JDK and Maven, `java21-offline` as the only repository, the tests on the development MySQL |
| [`dev-env.sh`](dev-env.sh), [`dev-mysql.sh`](dev-mysql.sh), [`run-local.sh`](run-local.sh) | The environment every build and run uses (sourced), the development MySQL (`vendor/`'s 8.4 on 127.0.0.1:3314), and the whole backend run from the release, with a debugger if asked (D-80) |
| `work/`                                         | Not in the repository (ignored): all a build, a test or a run writes, Maven's local repository and the development MySQL among it; deleting it costs only the next build's copying |
| [`.vscode/`](.vscode/)                          | VS Code's settings for the Java extensions, and the debugger's configurations ([05 — VS Code](docs/development/05-vscode.md)) |
| `jdk21/`, `vscode/`                             | Not in the repository since 2026-10-19 (ignored), kept outside git by the owner: a JDK 21 RPM and `.tar.gz` with the Java 21.0.12 API documentation, and VS Code 1.140.0's RPM. Used by no script: the build's JDK is `vendor/jdk-21`. The history still holds them (commits 8d6be59 and c45b538) |
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

- **Build, test, run and debug** the backend from a clone, offline, everything
  inside its folder: [docs/development/03-build-and-run.md](docs/development/03-build-and-run.md).
- **VS Code**: the Java extensions installed offline, and the committed settings:
  [docs/development/05-vscode.md](docs/development/05-vscode.md).
- **Install it on a RHEL 9 server**, from a clone, step by step:
  [docs/operations/03-install-guide.md](docs/operations/03-install-guide.md).
- **Ports and the firewall**: the site behind one public address and nginx in front, every
  port on each machine, who connects to it, and each machine's firewall on RHEL 9:
  [docs/operations/05-ports-and-firewall.md](docs/operations/05-ports-and-firewall.md).
- **Start, stop and restart each service**, on a server and on a development machine: nginx
  reloaded after a change, MySQL alone, an arena drained:
  [docs/operations/07-start-stop-restart.md](docs/operations/07-start-stop-restart.md).
- **Certificates**, with no domain: the backend's own CA, the site's certificate, installing and
  renewing it, and the app trusting it: [docs/operations/06-certificates.md](docs/operations/06-certificates.md).
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

From a clone, on Linux x86-64, with no network and nothing installed. The machine's
own Java and MySQL are neither used nor changed, and everything written goes in
`work/` ([build, test, run and debug](docs/development/03-build-and-run.md)):

```bash
./build-offline.sh            # j-redis, the backend with every test, the release; SKIP_TESTS=1 without tests
./run-local.sh start          # the store, the platform, a worker, the gateway, an arena, from the release
./run-local.sh bots           # twenty bots play through it; then ./run-local.sh stop
. ./dev-env.sh                # then mvn and java in this shell: vendor/'s, offline
```

With VS Code: [05 — VS Code](docs/development/05-vscode.md). The client and the
drills need the .NET 8 SDK, which the repository does not carry
([client/README.md](client/README.md)). The development VM's own tools under
`/opt` are listed in [working here §3](docs/development/02-working-here.md#3-the-development-machine)
and are not needed. Every command, the benchmarks among them, is in
[`CLAUDE.md`](CLAUDE.md#commands).

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
