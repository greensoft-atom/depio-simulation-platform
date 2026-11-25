# platform

The platform is the request/response half of the backend. It answers the app's HTTP API and holds
everything a player does between matches. One `platform` process runs on each machine, behind the
site's nginx. The app reaches it through nginx under `/v1/`; the gateway calls it for what the lobby
connections ask; operators call its admin API over SSH; and its watch monitors the site. This page
lists the module's classes, how it starts and stops, its settings, every route of the public API and
the admin API, the pushes it sends, its limits, its metrics and its tests.

What the platform holds:

- accounts, sessions and guests, and a player's own account deletion;
- the public arena's ticket;
- the queue for timed matches, its confirm step and the matcher; parties and the sandbox;
- teams, tournament registration, friends, blocks and the inbox;
- the score and rating boards, seasons, achievements and daily goals;
- the shop, inventory, equipment, item levels, boosts, gems for money (a simulated provider) and the
  season pass;
- the operator's admin API, and the watch: this machine's monitoring and alerts (D-86).

Every handler blocks: on MySQL, on j-redis (the store) or on Argon2 (the password hash). So each
request runs on its own virtual thread of the JDK's HTTP server. Every `platform` also runs a matcher,
but only the one that holds the `mm:leader` lease makes matches. What happens after a match (results,
ratings, rewards, the tournament clock, the close of a season) is the job of `worker`, not of this
module. The [glossary](../../docs/glossary.md) defines the terms used here (grant, ticket, lease,
session, guest, party, sandbox, front, watch).

The design is [04 — Platform services](../../docs/detailed-design/04-platform-services.md), and the
flows are drawn in [diagrams/04-platform](../../docs/diagrams/04-platform.md). The API references for
client authors are [api/01 — HTTP API](../../docs/api/01-http-api.md) and
[api/03 — Admin API](../../docs/api/03-admin-api.md).

## Why it is built so

- **The JDK's HTTP server, on virtual threads, not Netty.** Every handler blocks on MySQL, on j-redis or
  on Argon2, and blocking work is what virtual threads are for. The measured bottleneck is Argon2: one
  password check costs about 88 ms of CPU, and eight run at a time. That limits a process to about 90
  password logins a second, far below any limit of the HTTP layer. If this stops being true, only
  `PlatformHttpServer` has to change.
- **Not open to the internet.** An unauthenticated route that costs 88 ms of CPU is a lever for a
  denial-of-service attack. So the API listens only where nginx and the gateway reach it. The code's
  default address is loopback (`127.0.0.1`), which the one-machine launch keeps (D-93). On three
  machines it is the machine's private address, which the firewall opens to the other machines alone,
  because the front's nginx reaches every machine's platform (D-82,
  [operations/05 §5](../../docs/operations/05-ports-and-firewall.md#5-the-firewall-on-rhel-9)).
- **Throttled here, not at nginx.** nginx sees neither the account that a login names nor the other
  machines. So the platform counts the per-address and per-account limits in the store, which every
  `platform` shares. nginx adds a coarse outer limit, 2 requests a second with a burst of 30, on the
  four routes that make or open an account ([deploy/nginx/backend.conf](../deploy/nginx/backend.conf)).
- **503, not 500, when nothing is wrong with the request.** Examples: the hasher is full, no arena has
  room, or a store or the database does not answer. A 503 tells the client to come back later rather
  than report a fault.
- **One answer for an unknown user and for a wrong password.** Both get the same 401 and code. An
  unknown user is checked against a decoy hash, so both answers take the same time. Otherwise the API
  would tell anyone which usernames exist.

## Classes, by domain

All classes are in `com.backend.platform`.

| Domain | Class | What it does |
|---|---|---|
| start | `PlatformMain` | Reads the settings, builds every part, binds the listeners, and starts the matcher, the metrics listener, the admin listener and the watch |
| HTTP | `net/PlatformHttpServer` | The public API: routing, the bearer token, the body limit, every answer and its status, the API's metrics |
| HTTP | `net/AdminServer` | The operator's API, on its own loopback listener, behind a shared secret; every call is audited |
| HTTP | `net/ApiMessages`, `net/Json` | The request and response records; one Jackson `ObjectMapper` that ignores unknown fields |
| Watch | `watch/Watch` | This machine's monitoring (04 §12, D-86). Every 15 s, on its own thread, it reads this machine's processes' metrics, its disks and memory, and the other machines' platforms; then it hands the samples to the rules and handles what fires |
| Watch | `watch/WatchSettings` | The watch's settings, read once at start; a setting it cannot use refuses the start (exit 2) |
| Watch | `watch/Rule`, `watch/Rules` | One alert of the runbook as code: what it reads, how long its condition must hold, which series fire. `Rules` holds them all: the runbook's §5 and 01 §7, each with its threshold and its wait |
| Watch | `watch/Alert` | One alert: the rule's name, the labels of the series it fired for, the rule's words, and when the condition first held |
| Watch | `watch/History` | The last hour of one series, in a ring of 241 samples: the value at a moment, a counter's rise, how often a gauge fell, a slope |
| Watch | `watch/AlertEngine` | Evaluates every rule on each round and holds each alert's state: pending (held less than its wait), firing, or resolved |
| Watch | `watch/AlertStore` | The alerts in the session store, where every machine's platform reads them (`alert:`, `silence:`, `alertsent:`, `watch:`); the claim that sends a notification once for the site |
| Watch | `watch/Notifier` | Sends what fired or resolved to the webhook as one JSON `POST`; 10 s to answer 2xx, tried twice |
| Watch | `watch/MachineProbe` | This machine as samples: each watched path's file system, the memory from `/proc/meminfo`'s `MemAvailable`, the load over the last minute and the number of cores |
| accounts | `AuthService` | Register, log in, guests (made, logged in, upgraded), rename, the session behind a token |
| accounts | `AccountDeletion` | A player's own account erased (D-84): out of the queue, the party and the team; the MySQL erasure in one transaction; off the score boards; every session ended; every device's lobby told |
| accounts | `PasswordHasher` | Argon2id, m=19 MiB, t=2, p=1; eight hash at once, and a line of 160 waits behind them |
| accounts | `LoginThrottle` | The per-address and per-account limits on password checks, as fixed windows in the store |
| accounts | `DisplayName` | The rules that a display name or team name must pass (RFC 8266, UTS #39) |
| asking | `AskThrottle` | Hourly limits on asking other players, counted in the store |
| arena | `JoinService` | A seat in the public arena: it picks an arena and writes a ticket |
| queue | `QueueService` | Joining, leaving and answering the queue; opening a sandbox |
| queue | `MatchQueue` | The queue's records in the store (`mmq`, `mmp`, `mmc`, `mmlock`); each step is a watched transaction |
| queue | `Matchmaker` | One round a second under the lease: settles the matches asked about, lines up the queue, asks the players, makes or calls off matches |
| party | `Parties` | Parties in the store (`party:`, `partyOf:`, `pinv:`, `rl:say:`), with a version |
| party | `PartyService` | Invite, accept, leave, kick, say, and the pushes |
| teams | `TeamService` | Teams, invitations, applications, roles, renames, and `evt.team.update` |
| tournaments | `TournamentService` | Listing, viewing, registering and withdrawing; a player's match grant |
| social | `FriendService` | Friends, requests, blocks and presence |
| social | `InboxService` | The inbox, and marking it read |
| boards | `RatingLeaderboards` | The rating boards, the board of teams and past seasons; each top is kept 30 s |
| progress | `AchievementService`, `GoalService` | Achievements and their progress; today's three goals |
| economy | `ShopService`, `Catalogue` | Purchases and item levels; `shop.json` |
| economy | `Items`, `EquipmentService`, `Loadouts` | `items.json`; what a player wears; the bonus and skin a ticket carries |
| economy | `BoostService` | Activating a boost |
| revenue | `PaymentService`, `Packs` | Orders for packs of gems, through the simulated provider; `packs.json` |
| revenue | `PassService` | The season pass and its premium track |

## Starting it

```
java -cp '<release>/lib/platform/*' com.backend.platform.PlatformMain [bindHost] [port] [storeHost] [storePort]
```

The arguments default to `127.0.0.1 8080 127.0.0.1 6379`. The database settings come from the
environment only. More than four arguments is the old command line, which ended with the database
password: the platform refuses it and exits with code 2.

The platform starts in this order:

1. It reads the four arguments. An empty argument, or a port that is not a whole number, refuses the
   start.
2. It reads the database settings (`DatabaseSettings`). A pool size out of range, or a password file
   that is named but cannot be read or is empty, refuses the start.
3. It reads `BACKEND_TRUSTED_PROXIES` (`common/TrustedProxies`). Anything but IP addresses refuses the
   start. If no database password is set, it uses the development password and logs a warning.
4. It opens the store (`StoreClients.open`). A store that answers `NOAUTH` (it wants a password this
   process was not given) refuses the start. A store that cannot be reached yet does not: the client
   keeps trying in the background (D-94).
5. It opens the database pool, and Flyway migrates the schema (`Database.migrate`). The pool waits up
   to a minute for its first connection, with a warning for each failed attempt (D-94). After that
   minute the start fails with exit code 1.
6. It reads and checks `items.json`, `shop.json` and `packs.json`. Every offer, and every item that
   the season pass pays, must be in `items.json`. It reads `BACKEND_PAYMENT_PROVIDER`.
7. It builds the services: a team holds 30 players, and a player may have 100 friends and 100 blocked.
8. It reads the watch's settings (`WatchSettings`).
9. It starts the metrics listener if `BACKEND_METRICS_ADDR` names an address, then the admin listener
   if `BACKEND_ADMIN_ADDR` does. A bad address is refused here, before the public API serves anything.
10. It binds and starts the public API.
11. It starts the matcher. The matcher's lease id names this host, the bound port, the process id and a
    fresh ULID (`platform@<host>:<port>#<pid>-<ULID>`), so it is unique across machines.
12. It registers the API's, the pushes' and the matcher's metrics. Then it starts the watch if
    `BACKEND_WATCH_TARGETS` names targets; if not, a log line says the watch is off.

**Exit code 2** means a setting is wrong. The unit does not restart the process, because a restart
cannot fix a setting. The platform exits 2 on:

- more than four arguments, an empty argument, or a port that is not a number;
- a database or store setting refused as above, or a store that asks for a password it was not given;
- `BACKEND_TRUSTED_PROXIES` that is not IP addresses;
- a content file that breaks a rule, or an offer or a pass reward that names an item `items.json`
  lacks;
- a payment provider other than `simulated`;
- a watch setting it cannot use (see [Environment](#environment));
- a metrics or admin address that is not `host:port` or cannot be bound, an admin address that is
  not loopback, or an admin address named without its secret.

**Exit code 1** means anything else failed while starting, and the unit restarts the process.
Examples: MySQL is unreachable or refuses the account for a whole minute, or the API's port is in use.

**A stop** (the shutdown hook) closes, in this order: the metrics listener, the admin listener, the
watch, the matcher, the API (it allows a second for requests in flight), the database pool and the
store. Then it flushes the log.

## Configuration

### Environment

| Variable | Default | Meaning | Read at |
|---|---|---|---|
| `BACKEND_DB_URL` | `jdbc:mysql://127.0.0.1:3314/backend_dev?useSSL=false&allowPublicKeyRetrieval=true`, the development MySQL (O-40) | The database. Two hosts name a primary and its replica | `persistence/DatabaseSettings` |
| `BACKEND_DB_USER` | `backend` | The database user | `DatabaseSettings` |
| `BACKEND_DB_PASSWORD_FILE` | none | A file that holds the password; it wins over `BACKEND_DB_PASSWORD` | `DatabaseSettings`, `common/Secrets` |
| `BACKEND_DB_PASSWORD` | the development password, with a warning | The password itself. In production it is a plain variable in `/etc/backend/platform.env`, a file of `root:root 0600` (D-102) | `DatabaseSettings` |
| `BACKEND_DB_POOL_SIZE` | 16 | Database connections, 1 to 100; anything else is exit 2 | `DatabaseSettings`, `PlatformMain` |
| `BACKEND_STORE_ADDRESSES` | the command line's store host and port | `host:port`, or `host:port,host:port` for a store and its replica; another shape is exit 2 | `handoff/StoreClients` |
| `BACKEND_STORE_PASSWORD_FILE`, `BACKEND_STORE_PASSWORD` | none, with a warning | The store's password, by the same rules as the database's | `StoreClients` |
| `BACKEND_TRUSTED_PROXIES` | unset: loopback alone | IP addresses, separated by commas, whose `X-Forwarded-For` is believed besides this machine's: the front's nginx, which can be any machine (D-82). Anything but IP literals is exit 2; no name is looked up in DNS | `common/TrustedProxies` |
| `BACKEND_METRICS_ADDR` | unset: no listener | `host:port` for `GET /metrics` | `common/MetricsServer` |
| `BACKEND_ADMIN_ADDR` | unset: no admin API | `host:port`, a loopback address | `net/AdminServer` |
| `BACKEND_ADMIN_TOKEN_FILE`, `BACKEND_ADMIN_TOKEN` | required when `BACKEND_ADMIN_ADDR` is set | The admin API's shared secret; the file wins | `AdminServer` |
| `BACKEND_WATCH_TARGETS` | unset: the watch is off | This machine's processes' metrics, as `job=host:port` pairs separated by commas (08 §2). A pair of another shape, or a bad port, is exit 2 | `watch/WatchSettings` |
| `BACKEND_WATCH_PEERS` | none | The other machines' platforms, as `name=host:port` pairs; the watch reads their `/health`. A pair of another shape is exit 2 | `watch/WatchSettings` |
| `BACKEND_WATCH_DISKS` | `/,/var/lib,/var/log,/opt/backend` | The paths whose file systems are watched | `watch/WatchSettings` |
| `BACKEND_WATCH_MACHINE` | the hostname | This machine's name on its alerts | `watch/WatchSettings` |
| `BACKEND_ALERT_WEBHOOK_URL`, `_FILE` | none: alerts are listed and logged only | Where alerts are `POST`ed (08 §4). A value that is not an `http` or `https` URL with a host is exit 2 | `watch/WatchSettings`, `watch/Notifier` |
| `BACKEND_ALERT_REPEAT_HOURS` | 4 | How often a firing alert is sent again, 1 to 24 hours; anything else is exit 2 | `watch/WatchSettings` |
| `BACKEND_PAYMENT_PROVIDER` | unset: payments off | `simulated` is the only provider. It grants gems to whoever asks, so it is for development and drills only. Any other value is exit 2 | `PaymentService` |

The module's code reads no system property. The unit sets two JVM options of note:

- `-Dsun.net.httpserver.maxIdleConnections=1000` for the JDK's HTTP server. It must be above nginx's
  pool of kept-alive upstream connections: at the JDK's default of 200, the server closed idle
  connections that nginx was about to reuse.
- `-Dlogback.configurationFile` can point the logging at another file.

The unit passes its `PLATFORM_BIND`, `PLATFORM_PORT`, `STORE_HOST` and `STORE_PORT` as the four
arguments ([deploy/env/platform.env.example](../deploy/env/platform.env.example),
[deploy/systemd/backend-platform.service](../deploy/systemd/backend-platform.service)).

### Listeners

| Listener | Where | Who may call it |
|---|---|---|
| public API | The arguments' `bindHost:port`. On the one-machine launch `127.0.0.1:8080`; on three machines the machine's private address (the env example's `10.0.0.1:8080`). nginx passes it every `/v1/` path, on 443 and, since D-115, on 80 in clear | Each route's own rule: no credential, or a session token |
| metrics | `BACKEND_METRICS_ADDR` (127.0.0.1:9101 in the env example) | Anyone who can reach it; `GET /metrics` only, in the Prometheus text format |
| admin | `BACKEND_ADMIN_ADDR` (127.0.0.1:9120 in the env example; 9196 in the drills) | A caller with `Authorization: Bearer <secret>`, compared in constant time, over SSH |

### Content files

The platform reads these files once at start, from the classpath (`src/main/resources`), so a
release carries one version of each. It checks each strictly: an unknown field, a value out of range or
an id that appears twice stops the start.

| File | Holds | Rules |
|---|---|---|
| `items.json` | 5 equipment items, 2 boosts, 5 skins | `id` 1 to 40 of `[a-z0-9_]`; `type` `EQUIPMENT` (a `slot` of barrel, armor, core or treads, and 1 to 3 `modifiers`, each a `stat` and a `percent` 1 to 25), `BOOST` (`kind` xp or coins, `percent` 1 to 100, `minutes` 1 to 1 440) or `SKIN` (`skin` 1 to 255, each number once) |
| `shop.json` | 12 offers: 5 equipment items for coins, 2 boosts and 5 skins for gems | `sku` 1 to 64 and `itemId` 1 to 40 of `[a-z0-9_]`; `price` 1 to 1 000 000 000; `requiresLevel` 1 to 100, default 1; `availableFrom` and `availableTo` optional instants; `currency` coins (the default) or gems; `stock` is refused (limited stock is not built); every item must be in `items.json` |
| `packs.json` | 5 packs: `gems_80` $0.99, `gems_500` $4.99, `gems_1100` $9.99, `gems_2400` $19.99, `gems_6500` $49.99 | `productId` 1 to 64 of `[a-z0-9_]`; `gems` and `priceCents` 1 to 100 000; priced in USD |

Other tables are code, not files: the class and phrase tables (`sim/ClassTable`, `PhraseTable`), the
modes (`handoff/MatchMode`), and the season pass, achievements, daily goals and item level costs
(`persistence/SeasonPass`, `Achievements`, `DailyGoals`, `EconomyRepository`).

## The public API

Every route is listed below. The full reference, with examples, is
[api/01 — HTTP API](../../docs/api/01-http-api.md).

### Rules every route follows

- **Auth.** A route marked "session" needs `Authorization: Bearer <token>`, with the token that a login
  returned. With no header, or a header that is not `Bearer` and a token, the answer is 401 `no_token`.
  A token that names no session (unknown, expired or revoked) gets 401 `invalid_session`.
- **Errors** are `{"code", "message"}`. A client branches on the code; the message is for a person
  reading a log. A 405 carries an `Allow` header.
- **Bodies** are JSON, at most 4 096 bytes; a longer body gets 413 `body_too_large`. A body that does
  not parse counts as no body, which most routes answer with 400 `invalid_body`. Unknown fields are
  ignored.
- **The database or a store failing** gets 503 `storage_unavailable`: come back later. This covers an
  SQL error, a store timeout or lost connection, and a store write refused with `READONLY` or
  `NOREPLICAS`. Anything else unexpected gets 500 `internal`.
- **Ids in a path** are 1 to 18 digits. A longer id names no route: 404 `no_such_route`. Under
  `/v1/queue/` and `/v1/party/`, an unknown sub-path gets 404 `not_found` instead.
- **Exact paths** (D-107). Every route answers its own path alone. The JDK's server matches a route by
  its prefix, so the platform checks the whole path itself: `/v1/shop/x` or `/v1/sessions/` is a 404,
  not the shop or a login. This also keeps the routes that nginx limits (`/v1/accounts`,
  `/v1/accounts/upgrade`, `/v1/guests`, `/v1/sessions`) from being reached past nginx's limit by a
  longer path. A path that no route has gets the API's JSON 404 `no_such_route`, not the server's
  HTML page. The list routes `/v1/teams`, `/v1/friends`, `/v1/blocks`, `/v1/inbox` and
  `/v1/tournaments` also accept a trailing `/`.
- **The caller's address**, for the login limits, is the TCP peer's address. When the peer is trusted
  (on this machine, or named in `BACKEND_TRUSTED_PROXIES`: the front's nginx, D-82), it is the last
  entry of `X-Forwarded-For` instead. Only the last entry counts, because nginx appends the address it
  saw and every earlier entry is whatever the client sent. The entry is parsed as an IP literal and
  never looked up in DNS; one that does not parse falls back to the peer.
- **Times** are ISO-8601 instants in UTC.

### Accounts and sessions

| Method | Path | Auth | Request | Answer | Refusals |
|---|---|---|---|---|---|
| POST | `/v1/accounts` | none | `{username, password, displayName?}` | 201 `{playerId}` | 400 `invalid_body`, `invalid_username` (3 to 32 of letters, digits, `_`, `-`), `invalid_password` (8 to 128 characters), `invalid_display_name` (the message names the rule); 409 `username_taken`; 429 `too_many_attempts`; 503 `busy` |
| DELETE | `/v1/accounts` | session | `{password}`; a guest sends none | 204: the account is erased (D-84) | 400 `invalid_body` (no password, for a registered account); 401 `no_token`, `invalid_session`; 403 `wrong_password`; 409 `leader_with_members` (hand the team to another member first); 429 `too_many_attempts`; 503 `busy` |
| POST | `/v1/guests` | none | none | 201 `{playerId, guestKey, displayName}`; the name is `Guest` and four digits | 429 `too_many_attempts` |
| POST | `/v1/sessions` | none | `{username, password}`, or `{guestKey}` | 200 `{token, playerId, expiresInSeconds}` | 400 `invalid_body`; 401 `invalid_credentials` (one answer for an unknown name and a wrong password); 403 `banned`; 429 `too_many_attempts`; 503 `busy` (a password login only) |
| DELETE | `/v1/sessions` | session, not checked | none | 204, whether or not the session existed | 401 `no_token` |
| POST | `/v1/accounts/upgrade` | session | `{username, password, displayName?}` | 200 `{}`: the guest becomes a full account, the same player, and its guest key stops working | 400 `invalid_body`, `invalid_username`, `invalid_password`, `invalid_display_name`; 401; 409 `username_taken`, `not_a_guest`; 429; 503 `busy` |
| PUT | `/v1/accounts/name` | session | `{displayName}` | 200 `{playerId, displayName}`, the name as stored | 400 `invalid_display_name`; 401; 429 `too_soon` (once in 30 days) |

- 429 `too_many_attempts` and 503 `busy` carry a `Retry-After` header. For `busy` it is one to five
  seconds, spread at random, so the clients turned away do not all return in the same second. A
  client allows a login 10 s.
- The address's limit counts a registration, a password login, an upgrade, a guest made, a guest's
  login, and a deletion that checks a password. A password login and a deletion that checks a password
  also count against the account's limit. A refusal for load (503 `busy`) is answered before anything
  is counted, so a player who retries through an overload does not use up the account's attempts.
- **Deleting one's own account** (D-84). A registered account must send its password again, so a
  session taken from a phone cannot end the account. A guest's session is all it has, so a guest sends
  no password. The platform takes the player out of the queue, the party and the team, then erases the
  account in MySQL in one transaction, removes the player from the score boards, ends every session,
  and tells every device's lobby (`evt.session.revoked`, reason `deleted`). If the store fails after
  the erasure, the same call with the same session finishes the rest, and asks for no password.
- A session lasts a day, ±10 %; `expiresInSeconds` is the session's own length. A token is 32 random
  bytes, in base64url.

### The public arena, the queue and the sandbox

| Method | Path | Auth | Request | Answer | Refusals |
|---|---|---|---|---|---|
| POST | `/v1/match-requests` | session | none | 200 `{arenaHost, arenaPort, ticketId, tls, tlsName?}`: a seat in the public arena | 401; 503 `no_arena` |
| POST | `/v1/queue` | session | `{mode}` | 200 `{state: "queued", mode, waitedSeconds: 0}` | 400 `unknown_mode`, `party_too_big`, `party_too_small` (a team match needs a party of three); 401; 403 `not_allowed` (a team match is queued by the team's leader or a vice leader); 409 `already_queued`, `in_match`, `in_party` (the party's leader queues it), `party_changed`, `queue_locked` (declined a minute ago), `not_one_team` |
| DELETE | `/v1/queue` | session | none | 200 `{state: "none"}`, whether or not the player was queued | 401 |
| GET | `/v1/queue` | session | none | 200 the queue status (below) | 401 |
| POST | `/v1/queue/accept`, `/v1/queue/decline` | session | `{matchUid}` | 200 the queue status | 400 `invalid_body`; 401; 409 `not_confirming` (not asked about that match, or no longer) |
| POST | `/v1/sandbox` | session | none | 200 `{arenaHost, arenaPort, ticketId, tls, mode: "sandbox", tlsName?}` | 401; 409 `in_party` (a member, not the leader), `already_queued`, `in_match`, `in_sandbox`; 503 `no_room` |

`tlsName` is in a grant only when the arena is behind the front (D-82). The client sends it as the TLS
name on 9001 and checks the certificate against it; a client without TLS asks nginx's 80 for
`/match/<tlsName>` instead (D-115). It is left out for an arena reached at its own host.

The queued modes are `duel`, `tvt`, `rffa`, `coop`, `teams`, `domination`, `tag` and `maze`
(`handoff/MatchMode`). `ffa` is the public arena, and nobody queues for `sandbox`.

**The queue status** leaves out fields that are null:

| Field | When |
|---|---|
| `state` | Always: `none`, `queued`, `confirming` (a match was found and waits for answers) or `matched` |
| `mode` | Queued, confirming or matched |
| `waitedSeconds` | Queued, confirming or matched: the seconds since the player queued; for a sandbox, since it was opened |
| `matchUid`, `secondsLeft` | Confirming: the match the player is asked about, and the seconds left to answer |
| `grant` | Matched: `{arenaHost, arenaPort, ticketId, tls, mode, tlsName?}`, what `evt.match.found` carried |

- Leaving the queue while asked about a match counts as declining it.
- Leaving once matched forgets that player's own grant.
- Leaving a sandbox that nobody has joined yet revokes its ticket and gives back its hold on the room.
- A sandbox opened by a party's leader opens for the whole party, and each member is pushed
  `evt.match.found`.

### Parties

`GET /v1/party` reads the party; every other party route is a POST. All need a session. Each answers
with the caller's party as it is now (below). The gateway forwards the lobby's `party.*` messages here.

| Method | Path | Request | Refusals |
|---|---|---|---|
| GET | `/v1/party` | none | 401 |
| POST | `/v1/party/invite` | `{playerId}` | 400 `invalid_body`, `self`; 404 `not_in_lobby` (said only to a friend of the invitee); 409 `in_party` (the invitee has one), `not_leader`, `party_full`; 429 `too_soon` (60 invitations an hour) |
| POST | `/v1/party/accept` | `{partyId}` | 400 `invalid_body`; 404 `no_invitation` (none, or it lapsed after 60 s); 409 `in_party`, `party_full`, `queued` (the party is queued, or asked about a match) |
| POST | `/v1/party/leave` | none | 401 |
| POST | `/v1/party/kick` | `{playerId}` | 400 `invalid_body`; 404 `not_member` (not a member of a party the caller leads) |
| POST | `/v1/party/say` | `{phraseId}` | 400 `invalid_body`, `unknown_phrase`; 404 `no_party`; 429 `too_soon` (one phrase every two seconds) |

**A party** is `{partyId, leader, members: [{playerId, name}], version}`.

- It has at most three members, in the order they joined.
- When the leader leaves, the member who joined first becomes the leader.
- With no party, the answer is `{partyId: null, leader: 0, members: []}`. When a change ended the
  caller's party, the answer adds `was` (the party that ended) and that change's `version`.
- The version is 1 when a party is made, and goes up by one on each change of its members or its
  leader (D-74).

### Boards, seasons and progress

| Method | Path | Auth | Answer | Refusals |
|---|---|---|---|---|
| GET | `/v1/leaderboards/{board}?limit=&season=` | none | 200 `{board, entries: [{rank, playerId, name, score}]}`; for `teams`, the rows are `{rank, teamId, name, score}` | 400 `invalid_season`; 404 `unknown_board`, `no_such_season`, `no_such_route` |
| GET | `/v1/leaderboards/{board}/me?season=` | session | 200 `{board, rank, score, entries}`: the caller's row and five rows on either side | 401; 404 `not_ranked`, `not_in_team` (`teams`), `no_such_season` |
| GET | `/v1/seasons` | none | 200 `{current: {id, startsAt, endsAt} or null, past: [the same, newest first]}`. The platform reads the 13 newest seasons: the one not yet placed is `current`, the rest are `past`, so `past` holds up to 12 while a season runs | — |
| GET | `/v1/achievements` | session | 200 `{achievements: [{id, stat, threshold, gems, progress, reached}]}` | 401 |
| GET | `/v1/goals` | session | 200 `{day, resetsAt, goals: [{id, kind, target, coins, progress, done}], setGems, setDone}` | 401 |

- **The boards.** `alltime`, `daily` and `weekly` hold a player's best score in one stay or match, and
  are read from the store. `duel`, `rffa` and `tvt` hold a player's rating, listed after ten rated
  matches. `teams` holds a team's rating, listed after ten rated team matches. Ranks start at 1.
- `limit` defaults to 50 and is clamped to 1 to 100. The top of a rating board, and of the teams'
  board, is kept 30 s; a player's own place is read fresh.
- `season` names a season: a past season gives its final places, the current season its board as it
  stands. The score boards have no seasons (404 `no_such_season`). On `teams`, `/me` in a past season
  is the team the caller was paid for.
- Achievement stats are `kills`, `wins`, `matches`, `assists`, `bestScore` and `playtime` (in seconds).
  Goal kinds are `stays`, `kills`, `wins`, `assists`, `score`, `playtime` and `rated`. The day is a UTC
  day.

### Shop, inventory, equipment and boosts

| Method | Path | Auth | Request | Answer | Refusals |
|---|---|---|---|---|---|
| GET | `/v1/shop` | none | none | 200 `{offers: [{sku, itemId, price, requiresLevel, availableTo, currency}]}`, the offers on sale now | — |
| GET | `/v1/inventory` | session | none | 200 `{coins, gems, items: [{itemId, qty, level}]}` | 401 |
| POST | `/v1/purchases` | session | `{sku, key}` | 200 `{result: "bought" or "already_bought", itemId, coins, held, gems}` | 400 `invalid_body`, `invalid_key`; 401; 403 `level_required`; 404 `unknown_sku`; 409 `not_available`, `insufficient_funds` |
| POST | `/v1/inventory/{itemId}/level` | session | `{key}` | 200 `{itemId, level, coins}` | 400 `invalid_key`, `not_equipment`; 401; 404 `not_held`, `no_such_route`; 409 `max_level` (5), `insufficient_funds` |
| GET | `/v1/equipment` | session | none | 200 the loadout | 401 |
| PUT | `/v1/equipment/{slot}` | session | `{itemId}` | 200 the loadout | 400 `invalid_body`, `invalid_slot`; 401; 404 `unknown_item`; 409 `wrong_slot`, `not_owned` |
| DELETE | `/v1/equipment/{slot}` | session | none | 200 the loadout | 400 `invalid_slot`; 401 |
| GET | `/v1/boosts` | session | none | 200 `{boosts: [{kind, itemId, percent, endsAt}]}` | 401 |
| POST | `/v1/boosts` | session | `{itemId, key}` | 200 `{result: "activated" or "already_activated", boosts}` | 400 `invalid_body`, `invalid_key`; 401; 404 `unknown_item` (none, or not a boost); 409 `not_owned`, `other_running` |

- **Keys.** The client makes a key once per tap, for a purchase, an item level or a boost, and sends
  the same key with every retry. A key is 16 to 48 of `[a-z0-9-]`: a UUID in lower case. A key used
  before gets the answer its first attempt got, whatever the retry names.
- **The loadout** is `{slots: {barrel, armor, core, treads, skin}, bonus: {stat: percent}}`. Each slot
  is an item id or null. The bonus lists only the stats it changes. The stats are `health_regen`,
  `max_health`, `body_damage`, `bullet_speed`, `bullet_penetration`, `bullet_damage`, `reload` and
  `movement_speed`.
- **The bonus.** An item gives its percent × (3 + level) / 4, rounded down, so level 5 gives twice
  what level 1 gives. Everything worn is added per stat, and each stat is capped at 25. An item worn
  but no longer held gives nothing.
- **An item's level.** Only equipment has levels. Level 2 costs 500 coins, and each level after costs
  twice the one before: 500, 1 000, 2 000 and 4 000 coins to reach levels 2, 3, 4 and 5.
- `currency` is `coins` or `gems`. `availableTo` is null for an offer with no end.

### Payments and the season pass

| Method | Path | Auth | Request | Answer | Refusals |
|---|---|---|---|---|---|
| GET | `/v1/payments/packs` | none | none | 200 `{packs: [{productId, gems, priceCents, currency: "USD"}]}` | 503 `payments_off` |
| POST | `/v1/payments` | session | `{productId, key}` | 200 `{order}`, pending; a key used before gets the same order | 400 `invalid_body`, `invalid_key` (16 to 36 of `[a-z0-9-]`); 401; 404 `unknown_product`; 409 `refund_debt`; 503 `payments_off` |
| GET | `/v1/payments/{orderId}` | session | none | 200 `{order}` | 401; 404 `no_such_order` (none, or another player's); 503 `payments_off` |
| POST | `/v1/payments/{orderId}/simulate` | session | `{outcome: "paid" or "declined"}` | 200 `{order, confirmed, gems}`: `confirmed` is false when the order was no longer pending; `gems` is the balance after | 400 `invalid_outcome`; 401; 404 `no_such_order`; 503 `payments_off` |
| GET | `/v1/pass` | session | none | 200 the pass (below) | 401 |
| POST | `/v1/pass/premium` | session | none | 200 `{result: "bought" or "already_bought", gems, pass}` | 401; 409 `insufficient_funds` (500 gems), `season_ended` |

- Every payment route answers 503 `payments_off` unless `BACKEND_PAYMENT_PROVIDER=simulated`. An
  `orderId` is 36 characters of `[0-9a-f-]`.
- **An order** is `{orderId, productId, gems, bonus, priceCents, currency, state, createdAt}`.
  - `createdAt` is to the millisecond, and the same in every answer.
  - `state` is `pending`, `paid`, `declined`, `refunded` or `expired`.
  - `bonus` is extra gems on a player's first paid order, as many again as the pack's; once per player.
- **The pass** is `{season, endsAt, points, tier, premium, premiumGems: 500, tierPoints: 250, tiers:
  [{tier, free: {coins, gems, itemId}, premium: {coins, gems, itemId}}]}`, with forty tiers.
  - A free tier pays 150 coins; every fifth free tier pays 5 gems instead.
  - A premium tier pays 15 gems, and every fifth one a boost too: the xp boost at tiers 5, 15, 25 and
    35, the coins boost at 10, 20, 30 and 40. Premium's `coins` is always 0.
  - `worker` awards the points.

### Content tables

| Method | Path | Auth | Answer |
|---|---|---|---|
| GET | `/v1/content/classes` | none | 200 `{version, classes}`, the class table a client draws by |
| GET | `/v1/content/phrases` | none | 200 `{version, phrases: [{id, key, text}]}` |
| GET | `/v1/content/skins` | none | 200 `{version, skins: [{skin, itemId}]}` |
| GET | `/v1/health` | none | 200 `{"status": "ok"}`, without touching the database (D-107). nginx passes it, so a probe from outside can check the API through the front. It is exact: `/v1/health/x` is a 404 and another method a 405 |
| any | `/health` | none | 200 `{"status": "ok"}`, for any method and any path below it, and not counted in the metrics. It is for the watch and the checks on the machine; nginx does not pass it |

Each content table carries an `ETag`: its version, a hash of its content. A request whose
`If-None-Match` names that ETag gets 304. The class and phrase tables' versions are the ones the
arena's Welcome message names (`contentVersion`, `phraseListVersion`).

### Teams

All team routes need a session. **A team** is `{id, name, members: [{playerId, name, role}], rating,
wins, losses, draws}`, where `role` is `leader`, `vice_leader` or `member`.

| Method | Path | Request | Answer | Refusals |
|---|---|---|---|---|
| GET | `/v1/teams?name=` | none | 200 `{teams: [{id, name, members, rating}]}`, up to 20 teams whose name starts with the text | 400 `invalid_name` (empty, or over 16 characters) |
| POST | `/v1/teams` | `{name}` | 200 the team | 400 `invalid_name`; 409 `name_taken`, `in_team`, `cooling_down` (24 h after leaving a team) |
| GET | `/v1/teams/{id}` | none | 200 `{id, name, members, rating}` | 404 `no_such_team` |
| POST | `/v1/teams/{id}/applications` | none | 200 `{}` | 404 `no_such_team`; 409 `in_team`, `cooling_down`, `already`, `team_full`, `too_many_applied` (5 out); 429 `too_soon` (20 an hour, with the message `twenty applications an hour at most`) |
| DELETE | `/v1/teams/{id}/applications` | none | 200 `{}` | 404 `no_application` |
| GET | `/v1/teams/mine` | none | 200 the team | 404 `no_team` |
| DELETE | `/v1/teams/mine` | none | 200 `{}`: the team is disbanded | 403 `not_allowed`; 404 `no_team` |
| POST | `/v1/teams/mine/leave` | none | 200 `{}` | 404 `no_team`; 409 `leader_with_members` |
| POST | `/v1/teams/mine/invites` | `{playerId}` | 200 the team | 400 `invalid_body`; 403 `not_allowed`; 404 `no_such_player`; 409 `in_team`, `too_many_invited` (20 out); 429 `too_soon` (20 an hour) |
| POST | `/v1/teams/mine/leader` | `{playerId}` | 200 the team | 400 `invalid_body`; 403 `not_allowed`; 404 `not_a_member` |
| PUT | `/v1/teams/mine/name` | `{name}` | 200 the team | 400 `invalid_name`; 403 `not_allowed`; 409 `name_taken`; 429 `too_soon` (once in 30 days) |
| GET | `/v1/teams/mine/applications` | none | 200 `{applications: [{playerId, name, expiresAt}]}` | 403 `not_allowed` (a member); 404 `no_team` |
| POST | `/v1/teams/mine/applications/{playerId}` | `{accept}` | 200 the team | 400 `invalid_body`; 403 `not_allowed`; 404 `no_application`; 409 `in_team`, `cooling_down`, `team_full` |
| DELETE | `/v1/teams/mine/members/{playerId}` | none | 200 the team | 403 `not_allowed`; 404 `not_a_member` |
| POST | `/v1/teams/mine/members/{playerId}/role` | `{role: "vice_leader" or "member"}` | 200 the team | 400 `invalid_body`, `invalid_role`; 403 `not_allowed`; 404 `not_a_member`; 409 `too_many_vices` (2) |
| GET | `/v1/team-invites` | none | 200 `{invites: [{teamId, teamName, expiresAt}]}`, the 50 newest | 401 |
| POST | `/v1/team-invites/{teamId}` | `{accept}` | 200: when accepted, the team; when declined, the invitations left, `{invites}` | 400 `invalid_body`; 404 `no_invite`; 409 `in_team`, `cooling_down`, `team_full` |
| GET | `/v1/team-applications` | none | 200 `{applications: [{teamId, teamName, expiresAt}]}`, the player's own | 401 |

A team holds 30 players. Invitations and applications last seven days. Every change of who is in a
team, or of a member's role, is pushed to its members as `evt.team.update`.

### Tournaments

| Method | Path | Auth | Answer | Refusals |
|---|---|---|---|---|
| GET | `/v1/tournaments` | none | 200 `{tournaments: [view]}`: those registering, seeded or running, with their entries | — |
| GET | `/v1/tournaments/{id}` | none | 200 the view, with its bracket | 404 `no_such_tournament` |
| POST | `/v1/tournaments/{id}/entries` | session | 200 the view | 400 `party_too_small`; 401; 403 `not_allowed`; 404 `no_such_tournament`; 409 `closed`, `too_few_rated` (ten rated matches in its mode), `already` (checked before `full`), `full`, `in_party`, `not_one_team` |
| DELETE | `/v1/tournaments/{id}/entries` | session | 200 the view | 401; 403 `not_allowed`; 404 `no_such_tournament`; 409 `not_registered` |
| GET | `/v1/tournaments/{id}/match` | session | 200 `{tournamentId, round, arenaHost, arenaPort, ticketId, tls, mode, tlsName?}`: the grant that `worker` stored, for its 60 s | 401; 404 `no_match` |

**The view** is `{id, name, mode: "duel" or "teams", format: "elimination" or "round_robin", state,
maxEntries, registrationEnds, startsAt, roundMinutes, currentRound, prizes: [first, second, third],
entries, matches, standings?}`:

- `state` is `registration`, `seeded`, `running`, `finished` or `cancelled`.
- In a duel tournament, `entries` are `{playerId, name, seed}`. In a teams tournament they are
  `{teamId, name, seed, roster: [{playerId, name}]}`. The seed is 0 before seeding.
- `matches` are `{round, slot, state: "pending", "ready" or "done", playerA?, playerB?, winner?}`. A
  teams tournament has `teamA`, `teamB` and `winnerTeam` instead. A draw is a match that is done with
  no winner.
- `standings` exist only in a round robin. They are in order of place: `{playerId or teamId, name,
  points, wins, draws, losses}`.

A teams tournament is entered by the team's leader or a vice leader, who leads a party of three of the
team's members, in the same way as a team match is queued.

### Friends, blocks and the inbox

All need a session.

| Method | Path | Request | Answer | Refusals |
|---|---|---|---|---|
| GET | `/v1/friends` | none | 200 `{friends: [{playerId, name, online}], requests: [{playerId, name, expiresAt}], asked: [the same]}` | 401 |
| POST | `/v1/friends` | `{playerId}` | 200 `{state: "asked" or "friends"}` (`friends` when the other player asked first) | 400 `invalid_body`, `yourself`; 404 `no_such_player`; 409 `already_friends`, `already_asked`, `friends_full` (100), `you_blocked`, `too_many_asked` (50 out); 429 `too_soon` (20 an hour) |
| DELETE | `/v1/friends/{playerId}` | none | 200 `{}` | 404 `not_friends` |
| DELETE | `/v1/friend-requests/{playerId}` | none | 200 `{}`: their request declined, or one's own withdrawn | 404 `no_request` |
| GET | `/v1/blocks` | none | 200 `{blocked: [{playerId, name}]}` | 401 |
| POST | `/v1/blocks` | `{playerId}` | 200 `{}` | 400 `invalid_body`, `yourself`; 404 `no_such_player`; 409 `blocks_full` (100) |
| DELETE | `/v1/blocks/{playerId}` | none | 200 `{}` | 404 `not_blocked` |
| GET | `/v1/inbox` | none | 200 `{items: [{id, kind, ref, at, read}]}`, the 50 newest | 401 |
| POST | `/v1/inbox/read` | `{upTo}` | 200 `{}`: every item up to that id is read | 400 `invalid_body`; 401 |

- `online` says whether the friend's lobby connection is registered now.
- The inbox kinds are `friend_request`, `friend_accepted`, `team_invite`, `tournament_prize`,
  `team_application` and `season_reward`. `ref` is the id of the other player, the team or the
  tournament. For an application it is the applicant's id; for a season reward it is the season × 10 +
  the board.
- A friend request from a player whom the other player has blocked gets the normal answer (`asked`),
  but the player who blocked never sees it. A party or team invitation from a blocked player is handled
  the same way.

## The admin API

The admin API serves every path under `/admin/` on `BACKEND_ADMIN_ADDR`. Operators reach it over SSH.
Every call needs `Authorization: Bearer <secret>`. A call without it, or with another secret, gets 401
`unauthorised`, and the attempt is audited. The full reference is
[api/03 — Admin API](../../docs/api/03-admin-api.md).

- **Audit.** Each call is a row in MySQL's `admin_audit`: the call, its target (cut to 128
  characters), what it asked (cut to 1 024 characters) and what it did, refusals included. Reads are
  audited too. These are not audited: an unknown path (404 `not_found`), a wrong method (405), a bad
  `days` (400 `invalid_days`), a 500, and a 503 for a call that did nothing. A ban's 503s are audited
  with the ban. A notice is audited `sent` before it goes. A refund and a debt cleared are audited in
  their own transaction.
- **A store failure** (j-redis) gets 503 `storage_unavailable`, with the message `the store did not
  answer: call again`, whichever path it came by (`AdminServer.storeDown`, judged as the player API
  judges one): reading the arenas or the rooms, a close, a kick, a notice's broadcast. Each call is
  safe to make again; only a notice that the store took without answering goes out twice.
  - A ban whose sessions cannot be ended gets 503 `sessions_not_ended`. The ban is recorded, but the
    push and the kick are skipped until the call is made again.
  - A ban whose sessions ended but whose arena kick failed gets 503 `not_taken_out`.
  - Until 2026-10-06 these were 500 `internal` with the exception's text (O-36).
- **A reason** is required on every call that changes something, except lifting a silence:
  `{"reason"}`, 1 to 200 characters. Without it the call gets 400 `no_reason`.
- **Bodies** are read up to 4 096 bytes. A longer body is not read at all, and counts as empty.
- **The database failing** gets 503 `storage_unavailable`, and nothing is done. Anything else
  unexpected gets 500 `internal`.
- An unknown path gets 404 `not_found`. A known path with another method gets 405 with `Allow`.

| Method | Path | Body | Answer | Refusals |
|---|---|---|---|---|
| GET | `/admin/arenas` | none | 200 `[{name, host, port, players, maxPlayers, tls, rooms, maxRooms}]` | 405 |
| GET | `/admin/rooms` | none | 200 `[{arena, …each room as its arena announced it}]` | 405 |
| POST | `/admin/rooms/{arena}/{room}/close` | `{reason}` | 202 `{heard}`, the number of arenas that heard | 400 `no_reason`; 404 `not_found`, `no_such_arena`; 405 |
| POST | `/admin/players/{id}/ban` | `{reason, until?}` | 200 `{playerId, status: "banned" or "suspended", sessionsEnded, until?}` | 400 `no_reason`, `bad_until` (not an ISO-8601 instant, or not in the future); 404 `no_such_player`, `not_found`; 405; 503 `sessions_not_ended`, `not_taken_out` |
| POST | `/admin/players/{id}/unban` | `{reason}` | 200 `{playerId, status: "active", sessionsEnded: 0}` | 400 `no_reason`; 404 `no_such_player`; 405 |
| POST | `/admin/players/{id}/kick` | `{reason}` | 202 `{playerId, arenas}`, the number of arenas that heard | 400 `no_reason`; 404 `not_found`; 405 |
| POST | `/admin/players/{id}/refund-debt` | `{reason}` | 200 `{playerId, cleared}`, the gems of debt cleared | 400 `no_reason`; 405 |
| GET | `/admin/stats?days=N` | none | 200 `{days: [{day, active, newPlayers, d1, d7, d30}]}`, newest first | 400 `invalid_days` (1 to 60; 14 if absent); 405 |
| GET | `/admin/stats/features?days=N` | none | 200 `{days: [{day, newPlayers, features: {name: {players, d1, d7, d30}}}]}`; the names are `queued`, `bought`, `boosted`, `tournament`, `friend`, `team` | 400 `invalid_days`; 405 |
| GET | `/admin/stats/funnel?days=N` | none | 200 `{days: [{day, registered, guests, played, returned, level5, rated, bought, paid}]}` | 400 `invalid_days`; 405 |
| GET | `/admin/stats/guests` | none | 200 `{players, guests, inactive}`: all players, the guests never upgraded, and those guests idle 90 days | 405 |
| POST | `/admin/notice` | `{text, reason}` | 202 `{gateways}`, the number of gateways that heard | 400 `invalid_text` (1 to 200 characters, none a control, format or line separator character), `no_reason`; 405 |
| POST | `/admin/tournaments` | `{reason, name, maxEntries, registrationEnds, startsAt, roundMinutes, prizes: [three], mode?, format?}` | 200 `{id}` | 400 `no_reason`, `invalid_tournament` (the message names the field); 405 |
| GET | `/admin/seasons` | none | 200 `{seasons: [{id, startsAt, endsAt, placedAt, paidAt, resetAt}]}`, the 100 newest | 405 |
| POST | `/admin/seasons/end` | `{reason}` | 200 `{season, endsAt}`: the current season ends now | 400 `no_reason`; 405; 409 `already_ended` |
| POST | `/admin/payments/{orderId}/refund` | `{reason}` | 200 `{orderId, playerId, taken, debt}` | 400 `no_reason`; 404 `no_such_order`, `not_found`; 405; 409 `not_paid` |
| GET | `/admin/alerts` | none | 200 `{alerts: [{name, labels, summary, runbook, since, silenced}]}`: every machine's firing alerts, from the store (04 §12) | 405 |
| GET | `/admin/watch` | none | 200 `{machines: [{machine, at, roundSeconds, targets: [{job, instance, up}], peers: [{peer, up}], disks: [{mount, freeBytes, sizeBytes}], memory: {freeBytes, sizeBytes}, load1m, cores, alertsFiring}]}`: what each machine's watch last saw, newest first | 405 |
| GET | `/admin/silences` | none | 200 `{silences: [{alertname, until, reason}]}` | 405 |
| POST | `/admin/silences` | `{alertname, hours, reason}` | 201 `{alertname, until}` | 400 `no_such_alert` (not one of the watch's alerts), `invalid_hours` (a whole number, 1 to 168), `no_reason`; 405 |
| DELETE | `/admin/silences/{alertname}` | none | 200 `{alertname, lifted: true}` | 404 `no_such_silence`; 405 |

- **A ban** with `until` is a suspension until that instant; without `until` it is a ban for good. A
  ban sets the account's status, ends every session the player has, and closes their lobby connection
  (`evt.session.revoked`). It also takes them out of any match, and every arena refuses that player's
  tickets for a ticket's life, 60 s. If the sessions cannot be ended, the answer is 503
  `sessions_not_ended`: the ban is recorded, and calling again ends them.
- **A tournament** has these rules:
  - `name`: 1 to 64 characters, none a control or format character;
  - `maxEntries`: 2 to 32 (a round robin 2 to 8);
  - `registrationEnds` in the future, and `startsAt` no earlier than it;
  - `roundMinutes`: 1 to 60;
  - `prizes`: three whole numbers of coins, 0 to 1 000 000 000;
  - `mode`: `duel` (the default) or `teams`; `format`: `elimination` (the default) or `round_robin`.
- **A refund** takes a paid order's gems back, its first-purchase bonus too, as far as the balance
  allows. The rest becomes a debt. The debt refuses the player's next order (409 `refund_debt`) until
  `refund-debt` clears it.
- **Ending a season** is for a drill, or to bring the calendar into line. `worker` closes the season
  within the minute, and `GET /admin/seasons` shows how far it has got (`placedAt`, `paidAt`,
  `resetAt`).
- **A silence** stops an alert's notifications for 1 to 168 hours; the alert is still listed, with
  `silenced: true`. [operations/08 §5](../../docs/operations/08-monitoring.md#5-reading-and-silencing-alerts)
  explains reading and silencing alerts.
- `d1`, `d7` and `d30` are null until their day has ended.

## Pushes

A push is a lobby message that the server sends without being asked. The platform sends each one
through `handoff/LobbyPush` to the gateway that holds the player's lobby connection (the store
channel `push:{gateway}`), as `{"t": type, "d": data}`. A notice goes to every gateway on `push:all`.
A push only says "look": anything that must not be lost can also be fetched with a route.

| Event | Data | To | Sent by |
|---|---|---|---|
| `evt.match.ready` | `{matchUid, mode, seconds: 10}` | every player of a match found | `Matchmaker` |
| `evt.match.found` | `{arenaHost, arenaPort, ticketId, tls, mode, tlsName?}` | every player of a match made or a sandbox opened | `Matchmaker`, `QueueService` |
| `evt.queue.update` | `{state: "queued" or "none", mode}` (`mode` is null for none) | party members queued by the leader, and players taken out of the queue or put back | `QueueService`, `Matchmaker` |
| `evt.party.invite` | `{partyId, from, fromName}` | the invitee, if in the lobby and not blocking the inviter | `PartyService` |
| `evt.party.update` | a party, as the routes answer it | every member, and the one who left | `PartyService` |
| `evt.party.said` | `{from, name, phraseId}` | every member, the speaker too | `PartyService` |
| `evt.team.update` | `{team}`, the team or null | the members, and a player no longer in it | `TeamService` |
| `evt.inbox` | `{}` | the player given an inbox item | `FriendService`, `TeamService` |
| `evt.friend.request`, `evt.friend.accepted` | `{playerId, name}` | the player asked, or the player whose request was accepted | `FriendService` |
| `evt.notice` | `{text}` | everyone in the lobby | `AdminServer` |
| `evt.session.revoked` | `{reason: "banned", "suspended" or "deleted"}` | the player banned or suspended; every device of a player who erased their own account | `AdminServer`, `AccountDeletion` |

The platform tells the arenas on the store channel `arena-admin:{name}`: `{"cmd": "close", "room"}` and
`{"cmd": "kick", "player", "ban"?}`. `evt.tournament.match` and `evt.rewards` are sent by `worker`.

## Limits, throttles and timeouts

| What | Value | Where |
|---|---|---|
| password checks, per address (IPv6 per /64) | 30 a minute | `LoginThrottle` |
| password checks, per account | 10 a quarter hour | `LoginThrottle` |
| the hasher | 8 hashing, 160 waiting; then 503 `busy`, `Retry-After` 1 to 5 s | `PasswordHasher` |
| friend requests, team invitations, team applications | 20 an hour each, per player | `AskThrottle` |
| party invitations | 60 an hour, per player | `AskThrottle`, asked by `PartyService.invite` |
| a phrase to the party | one every 2 s | `Parties` |
| renaming, a player or a team | once in 30 days | `persistence` |
| request body | 4 096 bytes (both APIs) | `PlatformHttpServer`, `AdminServer` |
| a board's `limit` | default 50, at most 100; five rows on either side of one's own | `PlatformHttpServer` |
| a rating board's top, and the teams' | kept 30 s | `RatingLeaderboards` |
| a session | 86 400 s ±10 % | `handoff/SessionStore` |
| a ticket, a matched player's record, a sandbox's hold before it is joined, a tournament's grant | 60 s | `handoff/TicketStore` |
| a queued player's record | 900 s | `MatchQueue` |
| answering a match found | 10 s; a match asked about is kept 60 s | `Matchmaker`, `MatchQueue` |
| locked out after declining or not answering | 60 s | `Matchmaker` |
| the matcher | a round every second, with a fixed delay between rounds; the lease lasts 5 s | `Matchmaker` |
| the rating window | ±100, 50 wider every 10 s waited; the oldest player is matched among the 10 closest | `Matchmaker` |
| a watched change overtaken by another | tried 5 times, then 503 | `MatchQueue`, `Parties` |
| a party | 3 players; 3 600 s, renewed on each change; an invitation 60 s | `Parties` |
| teams | 30 a team, 2 vice leaders, 24 h between teams; invitations and applications 7 days; 20 invitations out, 5 applications out; 20 found by a search | `persistence/TeamRepository` |
| friends | 100 friends, 100 blocked, 50 requests out, requests last 7 days, 100 listed | `persistence/FriendRepository` |
| the inbox | 50 listed | `persistence/InboxRepository` |
| a pending order | expired by `worker` after a day | `persistence/PaymentRepository` |
| a store call for a score board (its read, a rename's write) | 3 s; every other store call has the client's 2 s | `PlatformHttpServer`, j-redis client |
| MySQL | pool 16; a connection 3 s to borrow, 2 s to connect; a statement's socket 30 s; a lock wait 20 s; at start, up to 60 s for the first connection (D-94) | `persistence/Database` |
| the watch | a round every 15 s; each read of a target or a peer 2 s; the webhook 10 s, tried twice; a firing alert sent again every 4 h by default | `watch/Watch`, `watch/Notifier`, `watch/WatchSettings` |
| a silence | 1 to 168 hours | `AdminServer` |

## Metrics

The metrics listener serves these at `GET /metrics` on `BACKEND_METRICS_ADDR`:

| Metric | Labels | What |
|---|---|---|
| `backend_platform_responses_total` | `status` | Every API response but those of `/health` |
| `backend_platform_request_seconds` | `route` (the route's own prefix, such as `/v1/party`, never the path the client sent) | A histogram of the time to answer, 5 ms to 2.5 s |
| `backend_platform_logins_total` | `outcome`: ok, invalid_credentials, banned, throttled, busy | Logins, by what became of them |
| `backend_platform_purchases_total` | `outcome`: bought, already_bought and each refusal (insufficient_funds, not_available, level_required, unknown_sku, invalid_key, no_session) | Purchases, by what became of them |
| `backend_platform_hasher_line` | | Requests holding or waiting for a password hash; 168 is full |
| `backend_platform_open_tickets_issued_total` | | Tickets issued for the public arena |
| `backend_platform_matches_made_total` | `mode` | Matches the matcher made |
| `backend_platform_match_tickets_issued_total` | `mode` | Tickets issued for made matches |
| `backend_platform_queue_wait_seconds` | `mode` | A histogram, 1 s to 10 min, from queueing to a match found |
| `backend_platform_queue_players` | `mode` | Players left waiting by this platform's last round; 0 where another platform leads |
| `backend_platform_confirms_total` | `outcome`: made, declined, withdrawn, lapsed, no_room | How each match found ended its confirm step |
| `backend_platform_pushes_unheard_total` | | Pushes to a player whose gateway nobody heard |
| `backend_watch_scrapes_total` | `outcome`: ok, failed | The watch's reads of a target's metrics; only while the watch is on |
| `backend_watch_notifications_total` | `outcome`: sent, failed, duplicate, silenced | Notifications: sent; failed (the webhook did not take it); duplicate (another machine sent it); silenced |
| `backend_watch_alerts_firing` | `machine` | Alerts firing now, raised by this machine's watch |
| `backend_watch_round_seconds` | | How long the watch's last round took |

The process also reports its own `backend_jvm_*`, `backend_process_uptime_seconds` and
`backend_store_*` metrics (`common/Metrics`, `handoff/StoreClients`).

## Building and testing

```bash
# from backend/: platform and what it needs, with its tests
. ../dev-env.sh  # vendor/'s JDK and Maven, the bundle, work/ (D-80)
mvn -pl platform -am install
```

The tests need a MySQL. They use the development one (`./dev-mysql.sh start`, 127.0.0.1:3314), with
database `backend_test`, user `backend` and password `backend-dev-password`. `dev-env.sh` names it in
`JDBC_URL`, and a test with no `JDBC_URL` uses the same default. `JDBC_URL`, `DB_USER` and
`DB_PASSWORD` point the tests at another server. A test that needs a store starts an embedded j-redis.
The live drill (`client/headless-drill.sh`; see the [backend README](../README.md#commands)) drives
this module through the real stack.

| Test class | Tests | What it covers |
|---|---|---|
| `net/PlatformHttpServerTest` | 89 | Every route over HTTP, against a real MySQL and j-redis: answers, codes, the body limit; exact paths (a route answers its own path alone, and a path no route has gets a JSON 404, D-107); the API's health under `/v1/`; addresses behind nginx, and the forwarded addresses believed from the front's nginx (D-82); a grant's TLS name; the store failing; an account deleted, a guest's too, one finished after a store failure, and its password guesses counted as logins (D-84) |
| `MatchmakerTest` | 43 | Lining up every mode's sides, every path of the confirm step, races between matchers (T-16, T-32 to T-34), tournament calls, the lease, the metrics; a made match's grant and push carrying the arena's TLS name (D-82) |
| `net/AdminServerTest` | 24 | Every admin call, its audit and its refusals; the listener starts only when named, with a secret, on loopback; alerts listed from the store with their silences; a silence made, listed and lifted, each audited, and its refusals (04 §12); what each machine's watch last saw, listed (item 102) |
| `watch/RulesTest` | 15 | The runbook's §5 as rules: each fires at its threshold and after its wait, fed rounds 15 s apart, and not before; resolution; every rule named and documented |
| `watch/WatchTest` | 7 | The watch against scripted targets and a real j-redis: a round read, a rule fired with its labels, the alert stored, sent and resolved; a target down and a peer unreachable, raised by two machines and sent once; silences and the repeat; a dead webhook tried again; no webhook, and the machine's own samples; each machine's view written for a minute and listed newest first; four slow targets read in parallel (item 102) |
| `watch/WatchSettingsTest` | 4 | Every setting read, the defaults, the webhook from a file, and each refusal |
| `AuthServiceTest` | 15 | Register, login, sessions, bans and suspensions, the decoy hash, rehashing, the upgrade's checks |
| `PartiesTest` | 11 | Invitations, accepting, leaving, kicking, versions (D-74), races |
| `DisplayNameTest` | 10 | The name rules |
| `PasswordHasherTest` | 9 | Argon2id, its stored form, the bounds of the hasher's line, a hash made under other parameters needing redoing |
| `PlatformToArenaTest` | 7 | Register, log in, get a ticket and play against a real arena; a seat promised with its ticket and dropped once counted (D-79); a burst spread over the arenas (T-59); a ticket used once; no session; no arena; an arena that stops announcing |
| `ItemsTest` | 7 | `items.json`'s rules and the release's items |
| `CatalogueTest` | 6 | `shop.json`'s rules and the release's offers |
| `LoginThrottleTest` | 5 | Both limits, IPv6 per /64, keys that expire, the store failing (the attempt is refused) |
| `PacksTest` | 4 | `packs.json`'s rules and the release's packs |
| `AskThrottleTest`, `PassServiceTest`, `PaymentServiceTest` | 2, 1, 1 | The hourly limits (twenty asks an hour for each kind, sixty party invitations); every item the pass names exists; the provider's setting |

## Where the design is

| Subject | 04 |
|---|---|
| accounts, sessions, guests, renaming, deleting one's account | [§1](../../docs/detailed-design/04-platform-services.md#1-auth-and-sessions) |
| teams | [§2](../../docs/detailed-design/04-platform-services.md#2-teams) |
| tickets and the arena directory | [§3](../../docs/detailed-design/04-platform-services.md#3-arena-registry-rooms-and-tickets) |
| the queue, the matcher, parties, the sandbox | [§4](../../docs/detailed-design/04-platform-services.md#4-matchmaking) |
| tournaments | [§6](../../docs/detailed-design/04-platform-services.md#6-tournaments) |
| boards and seasons | [§7](../../docs/detailed-design/04-platform-services.md#7-leaderboards) |
| shop, equipment, levels, gems, payments, the pass, skins, boosts | [§8](../../docs/detailed-design/04-platform-services.md#8-economy-shop-inventory-and-equipment) |
| friends, blocks, the inbox | [§9](../../docs/detailed-design/04-platform-services.md#9-notifications) |
| the admin API | [§10](../../docs/detailed-design/04-platform-services.md#10-admin-api) |
| metrics | [§11](../../docs/detailed-design/04-platform-services.md#11-what-to-measure) |
| the watch: monitoring and alerts | [§12](../../docs/detailed-design/04-platform-services.md#12-the-watch-monitoring-and-alerts-designed-2026-10-24-plan-item-100) |
