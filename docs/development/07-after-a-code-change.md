# After a code change: build, test, run, ship

This page is for a developer who has changed the Java code and wants to see the change working, first on a
development machine and then on the servers. It covers:

- which processes a change to each module reaches (§1);
- how to build, release and try the change on a development machine (§2);
- how to ship a new release to the servers and restart in order (§3);
- what more a change to the database's schema needs (§4);
- what more a change to the wire needs: the match protocol, the lobby's and the API's JSON, and the three clients (§5).

How a unit of work is done in this repository (design first, test first, the mutation check) is in
[02 §1](02-working-here.md#1-how-a-unit-of-work-is-done). The commands to build and run are explained in
[03](03-build-and-run.md).

## 1. Which processes a change reaches

A release has one directory of jars for each process type, `lib/<process>/`. A process runs only the modules in its
own directory. So a change to a module reaches every process whose directory holds that module, and each of those
processes must be restarted to run the new code.

| Module changed | Runs in | Restart |
|---|---|---|
| `common` | arena, platform, worker, gateway | all four |
| `handoff` (the store's keys, tickets, the arena directory, pushes, the result stream) | arena, platform, worker, gateway | all four |
| `sim` (the simulation, the class table) | arena, and platform (which serves the class table) | arena, platform |
| `protocol` (the match wire) | arena; the C# client speaks it too | arena, and see §5 |
| `persistence` (the repositories, the migrations) | platform, worker, and the `backend-leaderboard-rebuild` service (which runs from the worker's jars) | platform, worker; for a migration see §4 |
| `arena`, `platform`, `worker`, `gateway` | that process alone | that process |
| `tools` (bots, benchmarks), `crypto-examples` | no process: neither is in a release | nothing |
| `deploy/` (the systemd units, the nginx files, the example settings) | each machine's own copy, which an operator changes by hand | [operations/01 §5](../operations/01-deploy.md#5-rolling-deployment), [operations/07](../operations/07-start-stop-restart.md) |

## 2. On a development machine

Run these commands in a shell at the repository's root, in this order:

```bash
. ./dev-env.sh && ./dev-mysql.sh start
(cd backend && mvn -pl platform -amd install)   # the module, every module that uses it, and their tests
./run-local.sh stop                             # the release is about to be replaced under it
(cd backend && scripts/make-release.sh)         # the release dev-backend.sh, dev-nginx.sh and the drills run
./run-local.sh start
./dev-backend.sh test                           # two bots through nginx; ./dev-backend.sh bots for more
```

What each step does, and why:

1. **`. ./dev-env.sh`** sets up the shell: the repository's JDK and Maven, and Maven offline on the bundled
   libraries ([06 §3](06-questions-and-answers.md#3-what-does-dev-envsh-do-line-by-line)). `./dev-mysql.sh start`
   starts the development MySQL, which the database tests need.
2. **`mvn -pl platform -amd install`** builds the module you name (here `platform`) and every module that uses it,
   with their tests. For example, after a change to `handoff` it builds `handoff`, `arena`, `platform`, `worker`,
   `gateway` and `tools`. The `-am` of [03 §2](03-build-and-run.md#2-build) is the other direction: it builds the
   modules a module needs. While you work, run one test class alone:
   `(cd backend && mvn -pl platform test -Dtest=AuthServiceTest)`.
3. **Stop the run by hand before you make the release.** `make-release.sh` empties `backend/target/`, and the
   release that a run by hand is running from is inside it.
4. **`make-release.sh`** makes the new release in `backend/target/release/`. Then `./run-local.sh start` starts
   every service again, on the new release.
5. **Try it.** Use each interface as [03 §5.1](03-build-and-run.md#51-trying-each-interface) shows, and a debugger
   as [03 §6](03-build-and-run.md#6-debugging) shows (`DEBUG=1 ./run-local.sh start`).

Two more steps, when they apply:

- **A change that crosses processes** (an arena and the platform, or a worker and the store) is tried with a live
  drill: `TMPDIR=<a scratch directory> client/headless-drill.sh <scenario>`. Choose the scenario nearest the
  change ([03 §7](03-build-and-run.md#7-the-drills)).
- **Before you commit**, build everything with every test: `./build-offline.sh`.

## 3. On the servers: a new release

The operator does these steps. Each command is in the operations guides named below.

1. **Build the release** on the build machine: `./build-offline.sh`. It leaves `backend/target/release/` and its
   `.tar.gz` ([03 §4](03-build-and-run.md#4-a-release)).
2. **Copy it to every machine** over the internal network, and install it beside the running release, as
   [operations/01 §5](../operations/01-deploy.md#5-rolling-deployment) says:
   1. Unpack it in `/opt`.
   2. Copy any systemd unit that changed from the release's `systemd/`. Merge any nginx file that changed by hand
      into `/etc/backend/nginx/`, keeping this site's `server_name`, upstreams and list of arenas, which a copy
      would overwrite.
   3. Run `systemctl daemon-reload`, and `systemctl reload backend-nginx`, which tests nginx's configuration first.
   4. Switch the symbolic link `/opt/backend` to the new release.

   Switching the link changes nothing yet: a process runs the release it started from until it is restarted.
3. **Restart what the change reaches** (§1), machine by machine, in this order: the workers, then the platforms,
   then the gateways, then the arenas one at a time, each one drained first.
   [operations/07](../operations/07-start-stop-restart.md) has every command. When a release changes many
   modules, restart all of them, in that order. MySQL and the stores are restarted last, in a maintenance window,
   and only when their own version changed.
4. **Check each machine as you go**: `curl http://<the machine's private address>:8080/health`, the journal, the
   metrics ([operations/01 §7](../operations/01-deploy.md)), and a registration through the front.
5. **To go back**, point the link at the release before, and do the same restarts. The database's schema stays
   where the new release took it (§4).

**During the restarts, both releases run at the same time**, for minutes: an old arena beside a new one, an old
platform reading what a new worker wrote. So whatever two processes pass to each other changes only by adding.
That covers the store's keys and fields in `handoff`, the result's envelope, and the lobby's and the API's JSON.
Add a field that the old release ignores. The new release treats the field as absent when an old process did not
write it. A removal or a rename waits for the next release, as a schema's does (§4). D-82's `tlsName` is an
example: it is written empty where there is none, and an empty one is read as absent.

## 4. A change to the database's schema

The schema is a set of Flyway migrations in `backend/persistence/src/main/resources/db/migration/`. Today there
are two, `V1__schema.sql` and `V2__seed.sql`: the baseline
([06 §8](../detailed-design/06-persistence-mysql.md#8-migrations), D-75). Flyway is the library that applies
them, in number order, and records each one in the database.

1. **Add a new file with the next number**: `V3__<what it does>.sql`, then `V4__…`. Never edit a migration that
   has been applied, V1 and V2 included. Flyway refuses to start a process when an applied migration's checksum
   has changed.
2. **Expand, then contract.** A release only adds: a table, an index, or a column that is nullable or has a
   default. The code then uses it. A column or a table is dropped in a later release, once no running code reads
   it. The reason is §3: the first worker restarted applies the migration while the old platforms still run on the
   schema, and going back to the old release leaves the schema as it is.
3. **Never fill a populated table in one statement.** A backfill runs in batches of a thousand rows by primary key,
   each batch its own transaction, run by a worker or a tool after the deploy (06 §8).
4. **Test it.**
   - The database tests rebuild `backend_test` from empty with every migration, so each test run applies the new
     one: `(cd backend && mvn -pl persistence -amd install)`.
   - The upgrade from the schema before is tried with a run by hand. `backend_dev` is at the previous version, and
     the platform migrates it as it starts on the new release. Its log says
     ``Migrating schema `backend_dev` to version "3 - …"``.
5. **Deploy it** as §3 says, with nothing more. The first platform or worker to start on the new release applies
   the migration; the others wait on Flyway's lock in the database.
   - Before the deploy, make sure the last nightly dump and its weekly proof succeeded
     ([operations/02 §5](../operations/02-runbook.md#5-what-to-watch)).
   - Rehearse a large `ALTER` of a big table on a copy of the data first (06 §8).

## 5. A change to the wire

- **The match connection** (`protocol`, and the arena's encoder): the arena and the C# client must agree byte for
  byte. Golden vectors on both sides hold them to it
  ([02-networking](../detailed-design/02-networking.md)). A change that an older app cannot read needs a new
  protocol version and a new client release. The arena then tells an older app to update, with a kick.
- **The lobby's and the API's JSON**: add fields rather than change them, as §3 says. Update the C# core's tests
  and [docs/api](../api/README.md) with the change.
- **The client**: run the core's tests, then the Unity checks
  ([03 §9](03-build-and-run.md#9-the-client)):
  1. `client/core-tests.sh` (the core's tests, for .NET Framework 4.5 on Unity's Mono, D-116);
  2. `client/unity-package.sh && client/unity-check.sh`;
  3. `client/unity2019-check.sh`.
- **The simple clients** take the same change (D-111, D-114). A protocol change is made in all three clients: the
  full client, `client-simple/` and `client-simple-no-tls/`.
  - Change `client-simple/`'s messages or calls, and `client-simple-no-tls/`'s. Its `ArenaMessages.cs` and
    `Json.cs` are `client-simple`'s byte for byte, and its check holds them to that.
  - Then run `client-simple/check.sh`, `client-simple-no-tls/check.sh`, and the live drill
    `SIMPLE=1 client/headless-drill.sh play`, once more with `FRONT=plain`.
