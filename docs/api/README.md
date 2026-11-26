# The backend's APIs

This folder describes every interface that a client or an operator calls, with real requests and real answers. It is
for client developers and the UI team, and for operators who use the admin API. It also holds a Postman collection
that runs every HTTP route of the player API in order, and a small Node script that walks through the lobby. New words
are explained in the [glossary](../glossary.md).

## The interfaces

| Interface | Who calls it | Where | Reference |
|---|---|---|---|
| The player API, HTTP | the client | `https://<address>/v1/...`, through nginx to `platform`; or `http://<address>/v1/...` on port 80, without TLS ([D-115](../architecture/03-decision-log.md#d-115--a-players-build-may-connect-without-tls-and-the-site-serves-it-on-80-matches-included)) | [01 — the player API](01-http-api.md) |
| The lobby, a WebSocket | the client, between matches | `wss://<address>/lobby`, through nginx to `gateway`; or `ws://<address>/lobby` on port 80, without TLS | [02 — the lobby WebSocket](02-lobby-websocket.md) |
| The admin API, HTTP | operators | `http://127.0.0.1:9120/admin/...` on the machine itself, reached over SSH | [03 — the admin API](03-admin-api.md) |
| A match, binary over TCP | the client, during a match | the `arenaHost` and `arenaPort` of a grant. Behind the front, the client speaks TLS to port 9001 and sends the grant's `tlsName` as the TLS name ([D-82](../architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name)). A client without TLS connects to port 80 instead, sends `GET /match/<tlsName>`, and then the same frames (D-115) | [02-networking](../detailed-design/02-networking.md) |
| Metrics, in the Prometheus text format | the backend's own watch ([operations/08](../operations/08-monitoring.md)) | `GET /metrics` on each process's own loopback port | [operations/01 §7](../operations/01-deploy.md#7-installing-a-machine) |

Some words used above:

- **`<address>`** is the site's one public IP address, for example `203.0.113.10`. The site has no domain name.
- **The front** is the machine that this address reaches. Its nginx ends TLS and passes each connection to the right
  process ([glossary](../glossary.md)).
- **A grant** is what the backend gives a client for one match: the arena's address and port, a single-use ticket,
  and whether to use TLS ([01 §6](01-http-api.md#6-matchmaking-from-a-request-to-the-arena)).

Two small C# clients for Unity make these calls, and come with examples:
[`client-simple/`](../../client-simple/README.md)
([D-111](../architecture/03-decision-log.md#d-111--a-simple-client-beside-the-core-client-simple-three-small-clients-on-net-standard-20-examples-in-unitys-terms)),
and [`client-simple-no-tls/`](../../client-simple-no-tls/README.md), the same without TLS, for a backend run plain or
the site's port 80 (D-114, D-115). The full client is in [`client/`](../../client/README.md)
([detailed-design/08](../detailed-design/08-client.md)).

## From nothing to a match

A client goes through these steps. Each step names the section that describes it.

1. Register an account, or make a guest account ([01 §2](01-http-api.md#2-accounts-and-sessions)).
2. Log in. The answer is a session token ([01 §2](01-http-api.md#2-accounts-and-sessions)).
3. Open the lobby WebSocket and send that token first ([02 §3](02-lobby-websocket.md#3-authenticate-the-first-message)).
4. Join a queue, or ask for a seat in the public arena
   ([01 §6](01-http-api.md#6-matchmaking-from-a-request-to-the-arena)).
5. When the lobby asks about a match it found, accept it ([02 §5](02-lobby-websocket.md#5-a-whole-session)).
6. Take the grant that the lobby pushes, and connect to the arena with its ticket
   ([02-networking](../detailed-design/02-networking.md)).

Why each rule is as it is: [04 — platform services](../detailed-design/04-platform-services.md) and
[03 — gateway](../detailed-design/03-gateway.md).

## The Postman collection

[`backend.postman_collection.json`](backend.postman_collection.json) is in Postman's format 2.1. It holds 145 requests
in 14 folders, numbered 00 to 13. Together they are one scenario, from the health check to the end of a season, and
they call every route of the player API (D-107).

The collection makes three players (A, B and C) and a guest, with names unique to each run: each signs up at the
account service, and its access token is taken once for a session (D-117); the guest is linked to an account of its
own. Folder 13 deletes B's account at the account service, and checks that its event ended B's session here. Each request tests the
status of its answer. It also keeps, in the collection's variables, what later requests need: tokens, ids, an order,
a team, a tournament.

**To import it**: in Postman, choose Import, then the file. **Then set** the collection's variables:

| Variable | Default | Set it to |
|---|---|---|
| `baseUrl` | `http://127.0.0.1:8080` | the platform. Use the default on the machine itself. From another machine, use one of these: `https://<address>` through `dev-nginx.sh`'s nginx, trusting `work/nginx/ca/ca.pem` ([development/03 §5.0](../development/03-build-and-run.md#50-clients-on-other-machines-the-front)); `http://<address>`, the same nginx on port 80 without TLS (D-107); or `https://<the site's address>` through the front's nginx, trusting the backend's root certificate, `ca.pem` ([operations/06](../operations/06-certificates.md)). To trust a certificate in Postman: Settings, Certificates, CA certificates. With newman: `--ssl-extra-ca-certs ca.pem` |
| `accountUrl` | `http://127.0.0.1:8083` | the account service ([10](../detailed-design/10-accounts.md)): the default is `dev-account.sh`'s, on the machine itself. Through nginx, `https://<address>/account` (D-117) |
| `adminUrl` | `http://127.0.0.1:9120` | the admin API, through an SSH tunnel ([03 §1](03-admin-api.md#1-reaching-it)) |
| `adminToken` | | the admin secret: on an installed machine, the value of `BACKEND_ADMIN_TOKEN` in `/etc/backend/platform.env`, which the install made in `/etc/backend/credentials/admin-token` (D-102); on a run by hand, the contents of `work/admin-token` ([development/03 §5.1](../development/03-build-and-run.md#51-trying-each-interface)) |
| `password` | `example-password-1` | any password of 8 to 128 characters |

**Run it whole, in order** (Run collection). A folder run alone fails, because it lacks what earlier folders set.

What the collection needs, and what it does:

- **A test stack, never production.** Folder 09 sends a notice to every connected player, closes a room and suspends
  this run's player C. Folder 12 ends the current season.
- **A fresh stack for each run.** When it runs again on the same data, a few checks fail because of what the last run
  left. One is `409 season_ended`, until the worker begins the next season about a minute later. Another is a closed
  room that is no longer there. On a run by hand, start afresh as
  [development/03 §5.1](../development/03-build-and-run.md#51-trying-each-interface) says. That section also gives the
  command for a run by hand with payments and the admin API switched on.
- **Payments switched on.** Start `platform` with `BACKEND_PAYMENT_PROVIDER=simulated`. Without it, folder 03's
  payment requests answer 503 `payments_off`, and the purchases that come after them fail.
- **The admin API switched on** (`BACKEND_ADMIN_ADDR` and its secret) for folders 09 and 12, and **a live arena** for
  folder 08.
- **The account service running** (`dev-account.sh start`), with its events sent to this platform, as
  `dev-account.sh` sends them; the platform reads its keys from it (`BACKEND_ACCOUNT_JWKS_URL`, the development one by
  default).
- **At most two runs a minute.** One run makes 10 requests that count against the account service's limit of 30 a
  minute an address (sign-ups, logins and a deletion's password), and 3 against the platform's (a guest made, its
  logins) ([01 §0, Limits](01-http-api.md#limits)).
- **No whole duel.** The matcher only matches players who are connected to the lobby, and a collection cannot hold a
  WebSocket connection. So folder 08 joins a queue and leaves it. The whole duel is in `lobby-example.mjs`
  ([02 §9](02-lobby-websocket.md#9-trying-it)).

From the command line, with [newman](https://github.com/postmanlabs/newman) (Node 18 or later):

```bash
npx newman run docs/api/backend.postman_collection.json \
    --env-var baseUrl=http://127.0.0.1:8080 --env-var accountUrl=http://127.0.0.1:8083 \
    --env-var adminUrl=http://127.0.0.1:9120 --env-var adminToken="$(cat work/admin-token)"
# from another machine, through dev-nginx.sh's nginx (D-92):
#   --env-var baseUrl=https://<address> --ssl-extra-ca-certs work/nginx/ca/ca.pem
```

**The lobby is not in the collection**, because a collection file cannot hold WebSocket requests.
[`lobby-example.mjs`](lobby-example.mjs) walks through the lobby with two players
([02 §9](02-lobby-websocket.md#9-trying-it)). Postman can also open a lobby connection by hand.

## How this was checked

The examples were checked first on 2026-10-06. The stack was built from this repository's release, on the
development machine: the release's Java runtime, a MySQL 8.4 from `vendor/` with an empty database, j-redis,
`platform` (with the admin API on and payments simulated), one worker, the gateway, and one arena with four rooms.
Each process listened on a port of its own.

- **The collection**, by newman 6.2.2: 131 requests, 131 checks passed, in 15 seconds. The first run had 5 failures,
  all of them mistakes in the collection. One request went to the wrong listener. And one duel was polled to the end
  over HTTP, which can never work: the matcher drops players without a lobby connection within a second, and makes no
  match for them ([01 §6](01-http-api.md#6-matchmaking-from-a-request-to-the-arena) says so now). Both were fixed. The
  second run is the one these pages show.
- **Again on 2026-10-24**, with folder 13 added (an account deleted, D-84): 135 requests, 137 checks passed. This ran
  against `run-local.sh`'s stack, built from that day's release, with the admin API on and payments simulated.
- **The lobby**, by `lobby-example.mjs` on Node 24: every answer was as expected, a whole duel included.
- **The examples in pages 01 to 03 are those runs' requests and answers.** A few needed a player with coins, or with
  10 rated matches, and no route gives those to a new account. These were run with `curl`, after the test database
  gave the player what was needed, and they say so. A few shapes could not come from the runs at all: a board with
  rows, a tournament's match grant, and the pushes that no request triggered. These are given from the code, and say
  so.
- **Through nginx, on 2026-11-05** (D-107). The collection ran on the live drill's own stack, with the release's nginx
  in front of it, configured as `dev-nginx.sh` configures it (`headless-drill.sh FRONT=… NEWMAN=1`). It ran three
  ways: over TLS in port 443's place, in clear in port 80's place, and the plain way. Each way had 135 requests and
  137 checks, and none failed. Then the API's health under `/v1/` and a team application withdrawn were added: 137
  requests and 139 checks, and again none failed. Every player-API request reached nginx's access log. The admin
  API's requests did not, because the admin API is on loopback and does not go through nginx. After that, the C#
  client's scenarios ran through the same nginx:
  [operations/11 §10](../operations/11-phone-to-backend-routes.md#10-every-route-one-by-one).
- **With the account service, on 2026-11-26** (D-117, plan item 142 (d)). The collection ran on a run by hand from
  that day's release (`run-local.sh`, payments simulated, the admin API on), with the account service beside it
  (`dev-account.sh`) and both development databases made empty first: 145 requests, 159 checks, none failed, by
  newman 6.2.3, straight after `lobby-example.mjs`, whose every answer was as expected. A first run had one failure,
  the collection's own: folder 09's suspended login still sent a username and a password. The platform read the
  account service's key set itself, a guest was linked, and B's account deleted at the account service ended B's
  session here through its event, B's access token then refused. A password changed by `curl` ended the two sessions
  made in its second, and the new password's session worked (S-28). The new routes' examples in 01 §2 are this run's.
  Not through nginx yet: that, and the C# client, wait for plan item 142 (e).
- **Not checked here**: nginx's limits (its own 413 and 429 answers are described from its configuration), and the
  match protocol, which has its own drills.

To run it again: start the stack as [build and run §5](../development/03-build-and-run.md#5-running-it-by-hand)
starts it, with `platform` given `BACKEND_PAYMENT_PROVIDER=simulated`, `BACKEND_ADMIN_ADDR` and `BACKEND_ADMIN_TOKEN`.
Then run newman as above, and
`node docs/api/lobby-example.mjs <platform> <lobby> <admin> <secret>`.
