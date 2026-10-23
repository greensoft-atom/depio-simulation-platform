# After a code change: build, test, run, ship

What to do when the Java code changes, from your editor to the three machines: which processes a
change reaches, how to try it on a development machine, how it reaches the servers, and what more a
change to the database's schema or to the wire needs. How a unit of work is done in this repository
(design first, test first, the mutation check) is [02 §1](02-working-here.md#1-how-a-unit-of-work-is-done).

## 1. Which processes a change reaches

Each process runs the modules its release directory holds (`lib/<process>/`). A change to a module
reaches every process that holds it, and each of those needs a restart to run it.

| Module changed | Runs in | Restart |
|---|---|---|
| `common` | arena, platform, worker, gateway | all four |
| `handoff` (the store's keys, tickets, the arena directory, pushes, the result stream) | arena, platform, worker, gateway | all four |
| `sim` (the simulation, the class table) | arena, platform (which serves the class table) | arena, platform |
| `protocol` (the match wire) | arena; the C# client speaks it too | arena, and see §5 |
| `persistence` (the repositories, the migrations) | platform, worker, and `backend-leaderboard-rebuild` | platform, worker; see §4 for a migration |
| `arena`, `platform`, `worker`, `gateway` | itself | itself |
| `tools` (bots, benchmarks), `crypto-examples` | no process | nothing |
| `deploy/` (units, nginx, the env examples) | the machines' own copies, made by hand | [operations/01 §5](../operations/01-deploy.md#5-rolling-deployment), [operations/07](../operations/07-start-stop-restart.md) |

## 2. On a development machine

In a shell at the repository's root:

```bash
. ./dev-env.sh && ./dev-mysql.sh start
(cd backend && mvn -pl platform -amd install)   # the module, every module that uses it, and their tests
./run-local.sh stop                             # the release is about to be replaced under it
(cd backend && scripts/make-release.sh)         # the release that run-local.sh and the drills run
./run-local.sh start
./run-local.sh bots                             # bots play through it
```

- **`-amd`** builds the module and every module that uses it, with their tests: after changing
  `handoff`, that is `handoff`, `arena`, `platform`, `worker`, `gateway` and `tools`. `-am`, in
  [03 §2](03-build-and-run.md#2-build), builds what a module needs instead. One test class while you
  work: `(cd backend && mvn -pl platform test -Dtest=AuthServiceTest)`.
- **Stop before the release is made.** `make-release.sh` empties `backend/target/`, the release with
  it, and a run by hand runs from there. Then `start` runs the new release.
- **Try it**: each interface as [03 §5.1](03-build-and-run.md#51-trying-each-interface) says, and a
  debugger as [§6](03-build-and-run.md#6-debugging) does (`DEBUG=1 ./run-local.sh start`).
- **A change that crosses processes** (an arena and the platform, a worker and the store) is tried
  with a drill: `TMPDIR=<a scratch directory> client/headless-drill.sh <scenario>`, the scenario
  nearest the change ([03 §7](03-build-and-run.md#7-the-drills)).
- **Before committing**, everything with every test: `./build-offline.sh`.

## 3. On the servers: a new release

1. **Build it** on the build machine: `./build-offline.sh` leaves `backend/target/release/` and its
   `.tar.gz` ([03 §4](03-build-and-run.md#4-a-release)).
2. **Copy it to every machine** over the internal network, and install it beside the running one
   as [operations/01 §5](../operations/01-deploy.md#5-rolling-deployment) says: unpack it in
   `/opt`, copy any unit or nginx file that changed (then `systemctl daemon-reload`, and
   `systemctl reload backend-nginx`), and switch the symlink `/opt/backend`. Switching changes
   nothing yet: a process runs the release it started from until it is restarted.
3. **Restart what the change reaches** (§1), machine by machine, in this order: the workers, then
   the platforms, the gateways, and the arenas one at a time, each drained
   ([operations/07](../operations/07-start-stop-restart.md) has every command). A release that
   changes many modules: all of them, in that order.
4. **Check** each machine as you go: `curl http://<the machine's private address>:8080/health`, the
   journal, the metrics ([operations/01 §7](../operations/01-deploy.md)), and a registration through
   the front.
5. **To go back**: the symlink to the release before, and the same restarts. The database's schema
   stays where the new release took it (§4).

**During the restarts, both releases run at once**, for minutes: an old arena beside a new one, an
old platform reading what a new worker wrote. So what two processes pass each other (the store's
keys and fields in `handoff`, the result's envelope, the lobby's and the API's JSON) changes only by
adding: a field the old release ignores, which the new one treats as absent when an old process
did not write it. A removal or a rename waits for the next release, as a schema's does. D-82's
`tlsName` is an example: written empty where there is none, and read as absent.

## 4. A change to the database's schema

The schema is Flyway's migrations, `backend/persistence/src/main/resources/db/migration/`: today
`V1__schema.sql` and `V2__seed.sql`, the baseline
([06 §8](../detailed-design/06-persistence-mysql.md#8-migrations), D-75).

1. **A new file, the next number**: `V3__<what it does>.sql`, then `V4__…`. Never edit an applied
   one, V1 and V2 included: Flyway refuses to start a process whose applied migration's checksum
   changed.
2. **Expand, then contract.** A release only adds: a table, an index, a column that is nullable or
   has a default. The code uses it. A column or a table is dropped in a later release, once no
   running code reads it. The reason is §3: the first worker restarted applies the migration while
   the old platforms still run on it, and going back to the old release leaves the schema as it is.
3. **A backfill is never one statement over a populated table**: batches of a thousand rows by
   primary key, each its own transaction, run by a worker or a tool after the deploy (06 §8).
4. **Test it.** The database tests rebuild `backend_test` from empty with every migration, so each
   runs the new one: `(cd backend && mvn -pl persistence -amd install)`. The upgrade from the
   schema before: a run by hand. `backend_dev` is at the previous version, and the platform's start
   on the new release migrates it; its log says `Migrating schema `backend_dev` to version "3 - …"`.
5. **Deploy it** as §3, with nothing more: the first platform or worker to start on the new release
   applies it, and the others wait on Flyway's lock in the database. Before it, make sure the last
   nightly dump and its weekly proof succeeded ([operations/02 §5](../operations/02-runbook.md#5-what-to-watch)).
   A large `ALTER` on a big table is rehearsed on a copy of the data first (06 §8).

## 5. A change to the wire

- **The match connection** (`protocol`, and the arena's encoder): the arena and the C# client must
  agree byte for byte, held by golden vectors on both sides
  ([02-networking](../detailed-design/02-networking.md)). A change that an older app cannot read is
  a new protocol version, and a client release. The arena tells an older app to update.
- **The lobby's and the API's JSON**: add fields rather than change them, as in §3, and the C#
  core's tests and [docs/api](../api/README.md) follow.
- **The client**: `cd client && DOTNET_NOLOGO=1 /opt/dotnet/dotnet test`, then
  `client/unity-package.sh && client/unity-check.sh` ([03 §9](03-build-and-run.md#9-the-client)).
