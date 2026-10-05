# The backend's APIs

Everything a client or an operator calls, with real requests and answers, and a Postman
collection that runs all of the HTTP ones in order.

| Interface | Who calls it | Where | Reference |
|---|---|---|---|
| The player API, HTTP | the client | `https://<name>/v1/...` (nginx to `platform`) | [01 — the player API](01-http-api.md) |
| The lobby, WebSocket | the client, between matches | `wss://<name>/lobby` (nginx to `gateway`) | [02 — the lobby WebSocket](02-lobby-websocket.md) |
| The admin API, HTTP | operators | `http://127.0.0.1:9120/admin/...` on the machine, over SSH | [03 — the admin API](03-admin-api.md) |
| A match, binary over TCP | the client, in a match | `<arenaHost>:<arenaPort>`, from a grant | [02-networking](../detailed-design/02-networking.md) |
| Metrics, Prometheus text | monitoring | `GET /metrics` on each process's loopback port | [operations/01 §7](../operations/01-deploy.md#7-installing-a-machine) |

How a client goes from nothing to a match: register or make a guest, log in (a token), open
the lobby with that token, queue or ask for a seat, accept the match it is asked about, and
take the grant to the arena ([01](01-http-api.md) §6, [02](02-lobby-websocket.md) §5).

Why each rule is as it is: [04 — platform services](../detailed-design/04-platform-services.md),
[03 — gateway](../detailed-design/03-gateway.md).

## The Postman collection

[`backend.postman_collection.json`](backend.postman_collection.json) (Postman's format 2.1):
131 requests in 13 folders, one scenario from the health check to the end of a season. Three
players (A, B, C) and a guest are made, with names unique to the run; each request tests its
status and keeps in the collection's variables what later ones need (tokens, ids, an order,
a team, a tournament).

**Import**: Postman, Import, the file. **Set** the collection's variables:

| Variable | Default | Set it to |
|---|---|---|
| `baseUrl` | `http://127.0.0.1:8080` | the platform, or `https://<name>` through nginx |
| `adminUrl` | `http://127.0.0.1:9120` | the admin API, through an SSH tunnel |
| `adminToken` | | the admin secret (`/etc/backend/credentials/admin-token`) |
| `password` | `example-password-1` | any, 8 to 128 characters |

**Run** it whole, in order (Run collection): a folder alone lacks what earlier ones set.

What it needs and does:

- **A test stack, never production.** Folder 09 sends a notice to every connected player,
  closes a room and suspends this run's player C; folder 12 ends the current season.
- **Payments switched on**: `platform` with `BACKEND_PAYMENT_PROVIDER=simulated`, or folder 03's
  payments answer 503 `payments_off` and the purchases after them fail.
- **The admin API on** (`BACKEND_ADMIN_ADDR` and its secret) for folders 09 and 12, and **a live
  arena** for folder 08.
- **At most twice a minute**: a run makes 13 logins and registrations, and an address may make
  30 a minute.
- Matching a duel needs players connected to the lobby, which a collection cannot be: folder 08
  queues and leaves, and the whole duel is in `lobby-example.mjs` ([02](02-lobby-websocket.md) §9).

From the command line, with [newman](https://github.com/postmanlabs/newman) (Node 18 or later):

```bash
npx newman run docs/api/backend.postman_collection.json \
    --env-var baseUrl=http://127.0.0.1:8080 --env-var adminUrl=http://127.0.0.1:9120 \
    --env-var adminToken="$(cat admin-token)"
```

**The lobby** is not in the collection: a collection file cannot hold WebSocket requests.
[`lobby-example.mjs`](lobby-example.mjs) walks through it with two players
([02](02-lobby-websocket.md) §9), and Postman can open one by hand.

## How this was checked

On 2026-10-06, against a stack made from this repository's release on the development
machine: the release's runtime, a MySQL 8.4 from `vendor/` with an empty database, j-redis,
`platform` (admin API on, payments simulated), a worker, the gateway and one arena with four
rooms, each on a port of its own.

- **The collection**, by newman 6.2.2: 131 requests, 131 checks passed, in 15 seconds. Its first
  run had 5 failures, all the collection's: a request sent to the wrong listener, and a duel
  polled to the end over HTTP, which the matcher never makes for players without a lobby
  connection (it drops them within a second; 01 §6 says so now). Both fixed; the second run is
  the one shown in the references.
- **The lobby**, by `lobby-example.mjs` on Node 24: every answer as expected, a whole duel
  included.
- **The examples in 01-03 are those runs' requests and answers.** A few needed a player with
  coins or 10 rated matches, which no route gives a new account; they were run with `curl`
  after the test database gave them, and say so. A few shapes the runs could not produce (a
  board with rows, a tournament's match grant, the pushes no request triggered) are given from
  the code, and say so.
- **Not checked**: anything through nginx (its TLS, its limits and its own 413 and 429 are
  described from its configuration), and the match protocol (it has its own drills).

To run it again: the stack as [build and run §5](../development/03-build-and-run.md#5-running-it-by-hand)
starts it, the platform with `BACKEND_PAYMENT_PROVIDER=simulated`, `BACKEND_ADMIN_ADDR` and
`BACKEND_ADMIN_TOKEN`; then newman as above, and
`node docs/api/lobby-example.mjs <platform> <lobby> <admin> <secret>`.
