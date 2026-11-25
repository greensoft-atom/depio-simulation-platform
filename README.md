# backend

This repository holds a self-hosted backend for a realtime multiplayer arena (diep.io-style), played from a Unity
app on Android (C-10), and the C# client that speaks to it. This page is the front door: what the parts are, where
they are in the repository, what state they are in, and how to build and run them. The design documents start at
[docs/README.md](docs/README.md).

**In short:**
- **The server** is Java 21 and MySQL 8.4, with a Redis-compatible store of its own,
  [j-redis](j-redis-service/README.md).
- **The client** is C#: an engine-free core, for Unity 2019.4 and Visual Studio 2019 first (D-108), on .NET Framework
  4.5 alone (D-116).
- **The size**: the site launches on **one machine, for up to about 5 000 concurrent players** (D-93). The design
  grows to 50 000 on three machines.
- **The way in**: one public IP address, with no domain. nginx on the front machine takes the players' connections:
  the API and the lobby on 443 and matches on 9001, over TLS, and all three in clear on 80 (D-82, D-115).

## Components

| Component         | What it is                                                                                                                                                                                                   | Speaks                                                                                                                                                  |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `arena`           | Runs the match simulation: several rooms a process, each room on its own thread at 25 ticks a second, sending each player snapshots at 15 a second. Never touches MySQL.                                     | Raw TCP to clients, through the front's nginx: over TLS on 9001, which nginx ends and passes on by the arena's TLS name, or in clear through 80's `/match/<name>`. It listens on a private port, 9011 in the example settings. Also the `session` store, for tickets and its directory entry, and the `events` store, for results |
| `gateway`         | Holds each player's lobby connection and forwards to `platform`; delivers pushes. Makes no product decisions.                                                                                                | WebSocket at `/lobby`, behind nginx (8090 on its machine's private address in the example settings); HTTP to `platform`; the store's pub/sub            |
| `platform`        | Everything between matches: accounts and guests, sessions, tickets, the matcher, parties, teams, tournaments, boards and seasons, the shop, items, payments, the social layer, and the operator's admin API. Its watch monitors the site (D-86). | HTTP `/v1/` behind nginx (8080 on its machine's private address in the example settings), which players reach over TLS on 443 or in clear on 80; the admin API on a loopback listener of its own (9120); MySQL; the `session` store |
| `worker`          | Applies match results to MySQL exactly once and pays them, runs the tournament clock, closes seasons, keeps retention, reconciles the ledger, and watches the replicas and the backups.                      | The `events` store's result stream, MySQL, the `session` store                                                                                          |
| j-redis `session` | Sessions, tickets, the arena directory, queues, parties, leases, the score boards, pub/sub.                                                                                                                  | RESP2, 6379; on three machines a replica on another machine, at launch none (D-93)                                                                      |
| j-redis `events`  | The result stream `s:match-result`, read by the group `rewards`.                                                                                                                                             | RESP2, 6380 in the example settings; on three machines a replica on another machine, at launch none                                                     |
| MySQL 8.4 LTS     | The system of record: accounts, progression, the economy and its ledger, teams, tournaments, history.                                                                                                        | 3306, TLS; on three machines a GTID replica on another machine, at launch none; with a standby machine, the replica there (D-96)                        |
| nginx             | The front: it ends TLS for the API and the lobby on 443 and for matches on 9001, passing each match to its arena by name; and it serves all three in clear on 80, for a client without TLS (D-82, D-115).    | 80, 443 and 9001                                                                                                                                        |
| Client core       | `Backend.Client.Core`, .NET Framework 4.5 and C# 7.3, for Unity 2019.4 (at its .NET 4.x level) and Visual Studio 2019 first (D-108, D-116): the wire, the world, the match connection, the lobby, the API, the own tank's prediction, and what a frame draws. | All of the above, as a player does                                                                                                                      |
| Headless driver   | The core driven without Unity: the live drill's player, run here on Unity 2019's Mono, in clear (D-116).                                                                                                     | Against a running stack                                                                                                                                 |
| Unity package     | `com.backend.client`: thin scripts over the core. Compiled by Unity 2019.4's own compiler against its assemblies, and against stubs; not yet run in the editor.                                              | Through the core                                                                                                                                        |
| Simple client     | `client-simple/`: the API, the lobby and a match in seven small C# files for a UI team, with examples (D-111, D-112). Run on Unity 2019.4's Mono against the stack; not yet in the editor.                   | HTTP, WebSocket, TCP                                                                                                                                    |
| Simple client, no TLS | `client-simple-no-tls/`: the same without TLS (`http://`, `ws://` and plain matches), for a backend run plain (D-114) and for the site's 80 (D-115).                                                         | HTTP, WebSocket, TCP                                                                                                                                    |

**Every port above is a setting**; the example settings are in `backend/deploy/env/`. Each process serves its
metrics in the Prometheus text format, on a loopback address of its own (`BACKEND_METRICS_ADDR`). The platform's
own watch reads them; no Prometheus server runs (D-86). The machine layout, the protocols and the failover paths
are drawn in [docs/diagrams/01-system.md](docs/diagrams/01-system.md).

## Repository layout

| Path                                            | What it holds                                                                                                                                                                                                                                                                            |
| ----------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [`backend/`](backend/README.md)                 | The server: a Maven build of eleven modules (`common`, `protocol`, `sim`, `arena`, `handoff`, `persistence`, `platform`, `worker`, `gateway`, `tools`, `crypto-examples`), the release script, the operations scripts (`scripts/`), and the systemd units, nginx configuration and example settings (`deploy/`) |
| [`client/`](client/README.md)                   | The client: the core (`Core/`), its tests (`Core.Tests/`), the headless driver (`Headless/`), the Unity package (`Unity/com.backend.client/`), the stubs that compile it (`UnityCheck/`), the core on Unity 2019's Mono (`UnityRuntimeCheck/`), the one solution (`Backend.Client.sln`, VS 2019's), the core's tests run on Unity's Mono (`core-tests.sh`), the checks (`unity-check.sh`, `unity2019-check.sh`, `vs2019-check.sh`), Unity's tools for them (`unity-env.sh`), and the live drill (`headless-drill.sh`) |
| [`client-simple/`](client-simple/README.md)     | A simple client for a UI team to read and paste into Unity: the API over `HttpClient`, the lobby, a match, three example scripts, and its check (`check.sh`)                                                                                                                             |
| [`client-simple-no-tls/`](client-simple-no-tls/README.md) | The simple client without TLS, for a backend run plain and the site's 80: `http://`, `ws://`, plain matches; checked by client-simple's check                                                                                                                                            |
| [`docs/`](docs/README.md)                       | Requirements, architecture and the decision log, detailed designs, development and operations guides, the API, the plan, the defect register, the glossary, research                                                                                                                     |
| [`j-redis-service/`](j-redis-service/README.md) | The data store: server, client library, CLI and tools; a project of its own, with its own documents                                                                                                                                                                                      |
| [`protocol-spike/`](protocol-spike/README.md)   | The golden vectors, written by an independent codec, that bind the server's wire to the client's                                                                                                                                                                                         |
| [`java21-offline/`](java21-offline/README.md)   | Every third-party library the build needs, for a machine with no network                                                                                                                                                                                                                 |
| [`vendor/`](vendor/VERSIONS.md)                 | What the backend is built with and runs on, committed ready to use: the JDK (Temurin 21), Maven 3.9, MySQL 8.4 LTS, nginx 1.30 compiled for glibc 2.28 (RHEL 8 and 9) and the sources it is compiled from, and the scripts that refresh them (D-77)                                      |
| [`build-offline.sh`](build-offline.sh)          | Builds j-redis, the backend with every test, and the release, from the repository alone and with no network: `vendor/`'s JDK and Maven, `java21-offline` as the only repository, the tests on the development MySQL                                                                      |
| [`dev-env.sh`](dev-env.sh), [`dev-mysql.sh`](dev-mysql.sh), [`dev-jredis.sh`](dev-jredis.sh), [`dev-backend.sh`](dev-backend.sh), [`dev-nginx.sh`](dev-nginx.sh), [`run-local.sh`](run-local.sh) | `dev-env.sh` is the environment every build and run uses (sourced). The others are one script per service, each with `start`, `stop`, `restart`, `status` and `test` (D-80, D-92): the development MySQL (`vendor/`'s 8.4 on 127.0.0.1:3314); j-redis (and its `build`); the backend from the release (and its `build` and `bots`), with a debugger if asked; and the release's nginx in front, for clients on other machines, on 80, 443 and 9001, or with `PLAIN=1` on 80 and 9001 without TLS (D-101). `run-local.sh` runs the four in order |
| `work/`                                         | Not in the repository (ignored): everything a build, a test or a run writes, Maven's local repository and the development MySQL among it. Deleting it costs the next build's copying, the development MySQL's data, and nginx's root, `work/nginx/ca`: every client and app build given the old `ca.pem` must then be given the new one |
| [`.vscode/`](.vscode/)                          | VS Code's settings for the Java extensions, the extensions it recommends, and the debugger's configurations ([05 — VS Code](docs/development/05-vscode.md))                                                                                                                              |
| `jdk21/`, `vscode/`                             | Not in the repository since 2026-10-19 (ignored), kept outside git by the owner: a JDK 21 RPM and `.tar.gz` with the Java 21.0.12 API documentation, and VS Code 1.140.0's RPM. No script uses them: the build's JDK is `vendor/jdk-21`. The history still holds them (commits 8d6be59 and c45b538) |
| `*.tar.gz` at the root                          | Packed copies made by hand on the development machine, for carrying a directory elsewhere. `.gitignore` does not ignore them, so leave them out of commits. The only `.tar.gz` files in the repository are `vendor/src/`'s four source archives                                          |
| [`CLAUDE.md`](CLAUDE.md)                        | The standing instructions for work in this folder, with every command                                                                                                                                                                                                                    |

## Status

As of 2026-11-25, [plan](docs/plan.md) items 1 to 139 are done. Item 7 stays open by design: what is left of it
waits on Q-3's hardware. Item 140, the client on .NET Framework 4.5 alone (D-116), is under way.

- **Tests.** j-redis 2.2.2 passes its 229 tests (2026-11-25). The backend's sources declare 1 150 tests (2026-11-25); the last
  whole run's count was 1 064 (plan item 86, 2026-10-06). Each module's README holds its own count. The client core's 196 tests pass for
  .NET Framework 4.5 on Unity 2019's Mono (`client/core-tests.sh`, plan item 140). Nothing here runs .NET 8 since D-116.
- **Live.** The live drill has 45 scenarios, all in clear since D-116: Unity's Mono, which runs the client here,
  speaks TLS 1.0 alone, so `untrusted`, `TLS=1` and `FRONT=https` are refused. At plan item 78, all 44 scenarios
  of then passed against one release, in clear and over TLS (the TLS ones over TLS), and a two-hour soak of 300 bots
  held flat. Since D-107 the drill also runs the client through nginx, since D-116 by its 80 or the plain way.
- **Not done.** Nothing has run on the production hardware, and nothing has launched.

**What is open is the owner's** ([open questions](docs/requirements/01-scope-and-nfrs.md#7-open-questions)):
- the production machines' measurements (Q-1 to Q-4);
- a real payment provider (Q-52);
- held by the owner until they bring them back: where the backups' copy off the site goes (Q-53), results past
  20 000 players (Q-58), the app's updates (Q-59), where alerts go (Q-60), and the launch's machine (Q-62);
- put off by the owner: opening the Unity package in the editor (Q-54) and an admin panel (Q-55);
- whether a host may be RHEL 8 (Q-63; the owner leans no, and RHEL 9 is the host).

The certificate is settled: no domain, and a CA of the backend's own (D-82).

## Guides

- **After changing the code**: which processes a change reaches, trying it on a development machine, shipping it to
  the servers, and a change to the schema or the wire:
  [docs/development/07-after-a-code-change.md](docs/development/07-after-a-code-change.md).
- **Build, test, run and debug** the backend from a clone, offline, with everything inside its folder:
  [docs/development/03-build-and-run.md](docs/development/03-build-and-run.md).
- **VS Code**: the Java extensions installed offline, and the committed settings:
  [docs/development/05-vscode.md](docs/development/05-vscode.md).
- **Install it on a RHEL 9 server**, from a clone, step by step:
  [docs/operations/03-install-guide.md](docs/operations/03-install-guide.md).
- **Ports and the firewall**: the site behind one public address with nginx in front, every port on each machine,
  who connects to it, and each machine's firewall on RHEL 9:
  [docs/operations/05-ports-and-firewall.md](docs/operations/05-ports-and-firewall.md).
- **The phone's routes to the backend**, end to end, and nginx file by file:
  [docs/operations/11-phone-to-backend-routes.md](docs/operations/11-phone-to-backend-routes.md).
- **Start, stop and restart each service**, on a server and on a development machine: nginx reloaded after a
  change, MySQL alone, an arena drained:
  [docs/operations/07-start-stop-restart.md](docs/operations/07-start-stop-restart.md).
- **Certificates**, with no domain: the backend's own CA, the site's certificate, installing and renewing it, and
  the app trusting it: [docs/operations/06-certificates.md](docs/operations/06-certificates.md).
- **Monitoring**: the watch in every platform, its alerts, and silencing them:
  [docs/operations/08-monitoring.md](docs/operations/08-monitoring.md).
- **A second machine** as the first one's standby:
  [docs/operations/10-two-machines.md](docs/operations/10-two-machines.md).
- **The JDK, Maven, MySQL and nginx** that the repository carries, and how to move their versions:
  [vendor/README.md](vendor/README.md).
- **Cryptography**: what Java 21 and BouncyCastle give, the rules, and tested examples of signing, encryption,
  hashing, X.509 and TLS: [docs/development/04-crypto.md](docs/development/04-crypto.md).
- **The APIs**: every HTTP route, the lobby WebSocket and the admin API, with real examples, and a Postman
  collection that runs them all: [docs/api/](docs/api/README.md).
- **Scaling and performance**: how to add capacity to each part, and what each costs, as measured:
  [docs/operations/04-scaling-and-performance.md](docs/operations/04-scaling-and-performance.md).

## Developer quickstart

This works from a clone, on Linux x86-64, with no network and nothing installed. The machine's own Java and MySQL
are neither used nor changed, and everything written goes in `work/`
([build, test, run and debug](docs/development/03-build-and-run.md)):

```bash
./build-offline.sh            # j-redis, the backend with every test, the release; SKIP_TESTS=1 without tests
./run-local.sh start          # the MySQL, j-redis, the backend from the release, nginx on 80, 443, 9001 (as root)
./dev-backend.sh test         # two bots through nginx; each script has a test; then ./run-local.sh stop
# not root: export NGINX_HTTP_PORT=8088 NGINX_HTTPS_PORT=8443 NGINX_MATCH_PORT=9443 first (docs/development/03 §5.0)
# or plain, nginx without TLS: export PLAIN=1 first, then http://<address>/v1/, ws://<address>/lobby, matches <address>:9001 (D-101)
. ./dev-env.sh                # then mvn and java in this shell: vendor/'s, offline
```

What you get, by default:
- the API at `https://<address>/v1/` and the lobby at `wss://<address>/lobby`, both also in clear on 80;
- matches over TLS on `<address>:9001`, or in clear through 80's `/match/a1.arena.internal` (D-115);
- clients trust `work/nginx/ca/ca.pem`, the run's own root certificate, and nothing else.

`<address>` is this machine's first IPv4 address, unless `PUBLIC_ADDRESS` names another.

**More:** with VS Code, see [05 — VS Code](docs/development/05-vscode.md). The client and the drills need Unity
2019.4's Linux editor in `work/unity`, which the repository does not carry: `client/unity-env.sh` fetches it once
(1.8 GB). Its compiler builds the client for .NET Framework 4.5, and its Mono runs it; no .NET is needed (D-116,
[client/README.md](client/README.md)). The development VM's own tools
under `/opt` are listed in [working here §3](docs/development/02-working-here.md#3-the-development-machine), and are
not needed. Every command, the benchmarks among them, is in [`CLAUDE.md`](CLAUDE.md#commands).

**The database.** The schema is two Flyway migrations:
`backend/persistence/src/main/resources/db/migration/V1__schema.sql` (every table) and `V2__seed.sql` (the rows a
first launch needs). They were squashed on 2026-10-04 from the 34 steps the schema grew by
([D-75](docs/architecture/03-decision-log.md#d-75--the-migrations-are-squashed-into-one-baseline-before-the-first-launch)).
`platform` and `worker` apply pending migrations at every start. From V3 on, migrations are forward-only: an
applied migration is never edited.

## Where to read next

- [docs/README.md](docs/README.md): the documents, and the order to read them in.
- [docs/diagrams/README.md](docs/diagrams/README.md): every diagram.
- Each backend module's own README:
  [common](backend/common/README.md), [protocol](backend/protocol/README.md),
  [sim](backend/sim/README.md), [arena](backend/arena/README.md),
  [handoff](backend/handoff/README.md), [persistence](backend/persistence/README.md),
  [platform](backend/platform/README.md), [worker](backend/worker/README.md),
  [gateway](backend/gateway/README.md), [tools](backend/tools/README.md),
  [crypto-examples](backend/crypto-examples/README.md),
  [scripts](backend/scripts/README.md), [deploy](backend/deploy/README.md).
- [client/README.md](client/README.md), and its projects:
  [Core](client/Core/), [Core.Tests](client/Core.Tests/),
  [Headless](client/Headless/), [UnityCheck](client/UnityCheck/) and the
  [Unity package](client/Unity/com.backend.client/README.md).
- [client-simple/README.md](client-simple/README.md): the simple client, for a UI team; and
  [client-simple-no-tls/README.md](client-simple-no-tls/README.md), the same without TLS.
- [j-redis-service/README.md](j-redis-service/README.md): the store.
