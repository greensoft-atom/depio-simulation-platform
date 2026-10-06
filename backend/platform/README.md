# platform

The request/response half of the backend: one process per machine, behind that machine's nginx.
It holds everything a player does between matches: accounts, sessions and guests; the public
arena's ticket; the queue for timed matches, its confirm step and the matcher; parties and the
sandbox; teams, tournaments' registration, friends, blocks and the inbox; the score and rating
boards, seasons, achievements and daily goals; the shop, inventory, equipment, item levels,
boosts, gems for money (a simulated provider) and the season pass; and the operator's admin API.

Every handler blocks, on MySQL, on j-redis or on Argon2, so each request runs on a virtual thread
of the JDK's HTTP server. Every `platform` also runs a matcher, and only the one holding the
`mm:leader` lease matches. What is applied after a match (results, ratings, rewards, the
tournament clock, seasons' close) is `worker`'s, not this module's.

The design is [04 — Platform services](../../docs/detailed-design/04-platform-services.md); the
flows are drawn in [diagrams/04-platform](../../docs/diagrams/04-platform.md).

## Why it is built so

- **The JDK's HTTP server, on virtual threads, not Netty.** Every handler blocks, on MySQL, on
  j-redis or on Argon2, which is what virtual threads are for, and the measured bottleneck is
  Argon2: about 88 ms of CPU a password check, eight at a time, near 90 password logins a second a
  process, far below anything the HTTP layer decides. If that ever stops being true, the swap is
  contained to `PlatformHttpServer`.
- **Loopback by default.** An unauthenticated route that costs 88 ms of CPU is a
  denial-of-service lever, so the API listens where only this machine's nginx and gateway reach it.
- **Throttled here, not at nginx.** The edge sees neither the account a login names nor the other
  machines, so the per-address and per-account limits are counted in the store, shared by every
  `platform`. nginx adds a coarse outer limit, 2 a second with a burst of 30, on the four routes
  that make or open an account ([deploy/nginx/backend.conf](../deploy/nginx/backend.conf)).
- **503, not 500, when nothing is wrong with the request**: a full hasher, no arena with room, a
  store or database that does not answer. The client comes back rather than reports a fault.
- **One answer for an unknown user and a wrong password**, the same 401 and code, checked against a
  decoy hash so the two take the same time: otherwise the API is a list of which usernames exist.

## Classes, by domain

All in `com.backend.platform`.

| Domain | Class | What it does |
|---|---|---|
| start | `PlatformMain` | reads the settings, builds every part, binds, starts the matcher and the metrics and admin listeners |
| HTTP | `net/PlatformHttpServer` | the public API: routing, the bearer token, the body limit, every answer and its status, the API's metrics |
| HTTP | `net/AdminServer` | the operator's API, on its own loopback listener, behind a shared secret, every call audited |
| HTTP | `net/ApiMessages`, `net/Json` | the request and response records; one `ObjectMapper` that ignores unknown fields |
| accounts | `AuthService` | register, login, guests (made, logged in, upgraded), rename, the session behind a token |
| accounts | `PasswordHasher` | Argon2id, m=19 MiB, t=2, p=1; eight hashing at once and a line of 160 behind them |
| accounts | `LoginThrottle` | the per-address and per-account limits on password checks, fixed windows in the store |
| accounts | `DisplayName` | the rules a display name or team name must pass (RFC 8266, UTS #39) |
| asking | `AskThrottle` | hourly limits on asking others, counted in the store |
| arena | `JoinService` | a seat in the public arena: an arena picked, a ticket written |
| queue | `QueueService` | joining, leaving and answering the queue; opening a sandbox |
| queue | `MatchQueue` | the queue's records in the store (`mmq`, `mmp`, `mmc`, `mmlock`), each step a watched transaction |
| queue | `Matchmaker` | a round a second under the lease: settles the matches asked about, lines up the queue, asks, makes, calls off |
| party | `Parties` | parties in the store (`party:`, `partyOf:`, `pinv:`, `rl:say:`), versioned |
| party | `PartyService` | invite, accept, leave, kick, say, and the pushes |
| teams | `TeamService` | teams, invitations, applications, roles, renames, and `evt.team.update` |
| tournaments | `TournamentService` | listing, viewing, registering and withdrawing; a player's match grant |
| social | `FriendService` | friends, requests, blocks and presence |
| social | `InboxService` | the inbox and marking it read |
| boards | `RatingLeaderboards` | the rating boards, the board of teams and past seasons, each top kept 30 s |
| progress | `AchievementService`, `GoalService` | achievements and their progress; today's three goals |
| economy | `ShopService`, `Catalogue` | purchases and item levels; `shop.json` |
| economy | `Items`, `EquipmentService`, `Loadouts` | `items.json`; what is worn; the bonus and skin a ticket carries |
| economy | `BoostService` | activating a boost |
| revenue | `PaymentService`, `Packs` | orders for packs of gems, the simulated provider; `packs.json` |
| revenue | `PassService` | the season pass and its premium track |

## Starting it

```
java -cp '<release>/lib/platform/*' com.backend.platform.PlatformMain [bindHost] [port] [storeHost] [storePort]
```

The arguments default to `127.0.0.1 8080 127.0.0.1 6379`. More than four is the old shape that
ended with the database password: refused, exit 2. The database comes from the environment only.

In order:

1. The database settings are read (`DatabaseSettings`). A pool size out of range, or a named
   password file that cannot be read or is empty, refuses the start. With no password set, the
   development one is used and a warning logged.
2. The store is opened (`StoreClients.open`). A store that answers `NOAUTH` refuses the start; one
   that cannot be reached is not refused, and the client keeps trying.
3. The pool is opened and Flyway migrates the schema (`Database.migrate`).
4. `items.json`, `shop.json` and `packs.json` are read and checked, every offer and every item the
   season pass pays checked against `items.json`, and `BACKEND_PAYMENT_PROVIDER` read.
5. The services are built: team capacity 30, friends and blocks 100 each.
6. The settings of the metrics and admin listeners are checked.
7. The public API binds and starts.
8. The matcher starts, its lease id naming this host, port and process.
9. The metrics listener starts if `BACKEND_METRICS_ADDR` names one, then the admin listener if
   `BACKEND_ADMIN_ADDR` does.

It refuses to start (**exit 2**, which the unit does not restart) on: more than four arguments, a
port that is not a number, a database or store setting refused as above, a store asking for a
password it was not given, a content file that breaks a rule, an offer or a pass reward naming an
item `items.json` lacks, a payment provider other than `simulated`, a metrics or admin address that
is not `host:port`, an admin address that is not loopback, or an admin address named without its
secret. Anything else that fails while starting (MySQL unreachable, a wrong database password, the
API's port in use) is **exit 1**, which the unit restarts.

A stop (the shutdown hook) closes, in order: the metrics listener, the admin listener, the matcher,
the API (a second for requests in flight), the pool and the store, then flushes the log.

## Configuration

### Environment

| Variable | Default | Meaning | Read at |
|---|---|---|---|
| `BACKEND_DB_URL` | `jdbc:mysql://127.0.0.1:3306/backend_dev?useSSL=false&allowPublicKeyRetrieval=true` | the database; two hosts name a primary and its replica | `persistence/DatabaseSettings` |
| `BACKEND_DB_USER` | `backend` | | `DatabaseSettings` |
| `BACKEND_DB_PASSWORD_FILE` | none | a file holding the password; wins over the next | `DatabaseSettings`, `common/Secrets` |
| `BACKEND_DB_PASSWORD` | the development password, with a warning | | `DatabaseSettings` |
| `BACKEND_DB_POOL_SIZE` | 16 | connections, 1 to 100 | `DatabaseSettings`, `PlatformMain` |
| `BACKEND_STORE_ADDRESSES` | the command line's store host and port | `host:port`, or `host:port,host:port` for a store and its replica | `handoff/StoreClients` |
| `BACKEND_STORE_PASSWORD_FILE`, `BACKEND_STORE_PASSWORD` | none, with a warning | the store's password, by the same rules | `StoreClients` |
| `BACKEND_METRICS_ADDR` | unset: no listener | `host:port` for `GET /metrics` | `common/MetricsServer` |
| `BACKEND_ADMIN_ADDR` | unset: no admin API | `host:port`, a loopback address | `net/AdminServer` |
| `BACKEND_ADMIN_TOKEN_FILE`, `BACKEND_ADMIN_TOKEN` | required when `BACKEND_ADMIN_ADDR` is set | the admin API's shared secret; the file wins | `AdminServer` |
| `BACKEND_PAYMENT_PROVIDER` | unset: payments off | `simulated`, the only provider, which grants gems to whoever asks: development and drills only | `PaymentService` |

No system property is read by this module's code. The unit sets
`-Dsun.net.httpserver.maxIdleConnections=1000` (the JDK's server), and logging can be pointed
elsewhere with `-Dlogback.configurationFile`. The unit's `PLATFORM_BIND`, `PLATFORM_PORT`,
`STORE_HOST` and `STORE_PORT` become the four arguments
([deploy/env/platform.env.example](../deploy/env/platform.env.example),
[deploy/systemd/backend-platform.service](../deploy/systemd/backend-platform.service)).

### Listeners

| Listener | Where | Who may call it |
|---|---|---|
| public API | the arguments' `bindHost:port`; in production `127.0.0.1:8080`, behind nginx's `location /v1/` | each route's own rule: none, or a session token |
| metrics | `BACKEND_METRICS_ADDR` (the env example 127.0.0.1:9101) | anyone who can reach it; `GET /metrics` only, Prometheus text |
| admin | `BACKEND_ADMIN_ADDR` (the env example 127.0.0.1:9120; drills 9196) | `Authorization: Bearer <secret>`, compared in constant time, over SSH |

### Content files

Read once at start from the classpath (`src/main/resources`), so a release carries one version.
Each is checked strictly: an unknown field, a value out of range or an id twice stops the start.

| File | Holds | Rules |
|---|---|---|
| `items.json` | 5 equipment items, 2 boosts, 5 skins | `id` 1 to 40 of `[a-z0-9_]`; `type` `EQUIPMENT` (a `slot` of barrel, armor, core or treads, 1 to 3 `modifiers` each a `stat` and a `percent` 1 to 25), `BOOST` (`kind` xp or coins, `percent` 1 to 100, `minutes` 1 to 1 440) or `SKIN` (`skin` 1 to 255, each once) |
| `shop.json` | 12 offers: 5 equipment for coins, 2 boosts and 5 skins for gems | `sku` 1 to 64 and `itemId` 1 to 40 of `[a-z0-9_]`; `price` 1 to 1 000 000 000; `requiresLevel` 1 to 100, default 1; `availableFrom`/`availableTo` optional instants; `currency` coins (default) or gems; `stock` refused (not built); every item in `items.json` |
| `packs.json` | 5 packs: `gems_80` $0.99, `gems_500` $4.99, `gems_1100` $9.99, `gems_2400` $19.99, `gems_6500` $49.99 | `productId` 1 to 64 of `[a-z0-9_]`; `gems` and `priceCents` 1 to 100 000; priced in USD |

Other tables are code: the class and phrase tables (`sim/ClassTable`, `PhraseTable`), the modes
(`handoff/MatchMode`), the season pass, achievements, daily goals and item level costs
(`persistence/SeasonPass`, `Achievements`, `DailyGoals`, `EconomyRepository`).

## The public API

### Rules every route follows

- **Auth.** A route marked "session" needs `Authorization: Bearer <token>`, the token a login
  returned. No header, or not `Bearer` and a token: 401 `no_token`. A token that names no session
  (unknown, expired, revoked): 401 `invalid_session`.
- **Errors** are `{"code", "message"}`. The code is for a client to branch on; the message is for a
  person reading a log. A 405 carries `Allow`.
- **Bodies** are JSON, at most 4 096 bytes: past that, 413 `body_too_large`. A body that does not
  parse is treated as no body, which most routes answer 400 `invalid_body`. Unknown fields are
  ignored.
- **The database or a store failing** (an SQL error, a store timeout or lost connection, a write
  refused `READONLY` or `NOREPLICAS`) is 503 `storage_unavailable`: come back later. Anything else
  unexpected is 500 `internal`.
- **Ids in a path** are 1 to 18 digits; a longer one names no route, 404 `no_such_route` (under
  `/v1/queue/` and `/v1/party/`, 404 `not_found`).
- **Exact paths.** `/v1/accounts`, `/v1/accounts/upgrade`, `/v1/accounts/name`, `/v1/guests` and
  `/v1/sessions` answer only their own path, as nginx limits them; anything after it is 404. The
  routes without a sub-path (`/v1/match-requests`, `/v1/sandbox`, `/v1/shop`, `/v1/seasons`,
  `/v1/achievements`, `/v1/goals`, `/v1/purchases`, `/v1/boosts`, `/v1/content/*`) are matched by
  prefix, as the JDK's server matches.
- **The caller's address**, for the login limits, is the TCP peer's, or, when the peer is on this
  machine (nginx), the last entry of `X-Forwarded-For`, parsed as an IP literal and never looked up.
- **Times** are ISO-8601 instants in UTC.

### Accounts and sessions

| Method | Path | Auth | Request | Answer | Refusals |
|---|---|---|---|---|---|
| POST | `/v1/accounts` | none | `{username, password, displayName?}` | 201 `{playerId}` | 400 `invalid_body`, `invalid_username` (3 to 32 of letters, digits, `_`, `-`), `invalid_password` (8 to 128 characters), `invalid_display_name` (the message names the rule); 409 `username_taken`; 429 `too_many_attempts`; 503 `busy` |
| POST | `/v1/guests` | none | none | 201 `{playerId, guestKey, displayName}`, the name `Guest` and four digits | 429 `too_many_attempts` |
| POST | `/v1/sessions` | none | `{username, password}`, or `{guestKey}` | 200 `{token, playerId, expiresInSeconds}` | 400 `invalid_body`; 401 `invalid_credentials` (one answer for an unknown name and a wrong password); 403 `banned`; 429 `too_many_attempts`; 503 `busy` (a password login only) |
| DELETE | `/v1/sessions` | session, not checked | none | 204, whether or not the session existed | 401 `no_token` |
| POST | `/v1/accounts/upgrade` | session | `{username, password, displayName?}` | 200 `{}`: the guest is a full account, the same player, and its key stops working | 400 `invalid_body`, `invalid_username`, `invalid_password`, `invalid_display_name`; 401; 409 `username_taken`, `not_a_guest`; 429; 503 `busy` |
| PUT | `/v1/accounts/name` | session | `{displayName}` | 200 `{playerId, displayName}`, the name as stored | 400 `invalid_display_name`; 401; 429 `too_soon` (once in 30 days) |

- 429 `too_many_attempts` and 503 `busy` carry `Retry-After`; `busy`'s is one to five seconds, spread
  so the turned-away do not return together. A client allows a login 10 s.
- A registration, a login, an upgrade, a guest made and a guest's login are counted against the
  address's limit; a password login against its account's too. A refusal for load (503 `busy`) is
  answered before anything is counted.
- A session lasts a day, ±10 %: `expiresInSeconds` is the session's own. A token is 32 random
  bytes, base64url.

### The public arena, the queue and the sandbox

| Method | Path | Auth | Request | Answer | Refusals |
|---|---|---|---|---|---|
| POST | `/v1/match-requests` | session | none | 200 `{arenaHost, arenaPort, ticketId, tls}`: a seat in the public arena | 401; 503 `no_arena` |
| POST | `/v1/queue` | session | `{mode}` | 200 `{state: "queued", mode, waitedSeconds: 0}` | 400 `unknown_mode`, `party_too_big`, `party_too_small` (a team match: a party of three); 401; 403 `not_allowed` (a team match: queued by the team's leader or a vice leader); 409 `already_queued`, `in_match`, `in_party` (the party's leader queues it), `party_changed`, `queue_locked` (declined a minute ago), `not_one_team` |
| DELETE | `/v1/queue` | session | none | 200 `{state: "none"}`, whether or not queued | 401 |
| GET | `/v1/queue` | session | none | 200 the queue status (below) | 401 |
| POST | `/v1/queue/accept`, `/v1/queue/decline` | session | `{matchUid}` | 200 the queue status | 400 `invalid_body`; 401; 409 `not_confirming` (not asked about that match, or no longer) |
| POST | `/v1/sandbox` | session | none | 200 `{arenaHost, arenaPort, ticketId, tls, mode: "sandbox"}` | 401; 409 `in_party` (a member, not the leader), `already_queued`, `in_match`, `in_sandbox`; 503 `no_room` |

The queued modes are `duel`, `tvt`, `rffa`, `coop`, `teams`, `domination`, `tag` and `maze`
(`handoff/MatchMode`); `ffa` is the public arena and `sandbox` is never queued for.

**The queue status**, null fields left out:

| Field | When |
|---|---|
| `state` | `none`, `queued`, `confirming` (a match found, waiting for answers) or `matched` |
| `mode` | queued, confirming or matched |
| `waitedSeconds` | queued, confirming or matched: since the player queued; for a sandbox, since it was opened |
| `matchUid`, `secondsLeft` | confirming: the match asked about, and the seconds left to answer |
| `grant` | matched: `{arenaHost, arenaPort, ticketId, tls, mode}`, what `evt.match.found` carried |

Leaving the queue while asked is declining; leaving once matched forgets that player's own grant;
leaving a sandbox not yet joined revokes its ticket and gives back its hold. A sandbox opened by a
party's leader opens for the whole party, each member pushed `evt.match.found`.

### Parties

All POST but the first, session, each answering the caller's party as it now is (below). The
lobby's `party.*` messages are forwarded here by the gateway.

| Method | Path | Request | Refusals |
|---|---|---|---|
| GET | `/v1/party` | none | 401 |
| POST | `/v1/party/invite` | `{playerId}` | 400 `invalid_body`, `self`; 404 `not_in_lobby` (said only to a friend of the invitee); 409 `in_party` (the invitee has one), `not_leader`, `party_full`; 429 `too_soon` (60 invitations an hour) |
| POST | `/v1/party/accept` | `{partyId}` | 400 `invalid_body`; 404 `no_invitation` (none, or lapsed after 60 s); 409 `in_party`, `party_full`, `queued` (the party is queued or asked about a match) |
| POST | `/v1/party/leave` | none | 401 |
| POST | `/v1/party/kick` | `{playerId}` | 400 `invalid_body`; 404 `not_member` (not a member of a party the caller leads) |
| POST | `/v1/party/say` | `{phraseId}` | 400 `invalid_body`, `unknown_phrase`; 404 `no_party`; 429 `too_soon` (one every two seconds) |

**A party** is `{partyId, leader, members: [{playerId, name}], version}`, at most three members in
the order they joined; a leader who leaves hands it to the member who joined first. For no party: `{partyId: null, leader: 0, members: []}`; when a change
ended the caller's party, with `was` (the party it ended) and that change's `version`. The version
is 1 when a party is made and one more on each change of its members or leader (D-74).

### Boards, seasons and progress

| Method | Path | Auth | Answer | Refusals |
|---|---|---|---|---|
| GET | `/v1/leaderboards/{board}?limit=&season=` | none | 200 `{board, entries: [{rank, playerId, name, score}]}`; for `teams`, `{rank, teamId, name, score}` | 400 `invalid_season`; 404 `unknown_board`, `no_such_season`, `no_such_route` |
| GET | `/v1/leaderboards/{board}/me?season=` | session | 200 `{board, rank, score, entries}`, the caller and five rows either side | 401; 404 `not_ranked`, `not_in_team` (`teams`), `no_such_season` |
| GET | `/v1/seasons` | none | 200 `{current: {id, startsAt, endsAt} or null, past: [the same, up to 12, newest first]}` | — |
| GET | `/v1/achievements` | session | 200 `{achievements: [{id, stat, threshold, gems, progress, reached}]}` | 401 |
| GET | `/v1/goals` | session | 200 `{day, resetsAt, goals: [{id, kind, target, coins, progress, done}], setGems, setDone}` | 401 |

- Boards: `alltime`, `daily` and `weekly`, a player's best score in one stay or match, from the
  store; `duel`, `rffa` and `tvt`, a player's rating, listed after ten rated matches; `teams`, a
  team's rating, listed after ten rated team matches. Ranks are 1-based.
- `limit` defaults to 50 and is clamped to 1 to 100. A rating board's top, and the teams', is kept
  30 s; one's own place is read fresh.
- `season` names a season: a past one's final places, the current one's board as it stands. The
  score boards have no seasons (404 `no_such_season`). On `teams`, `/me` in a past season is the
  team the caller was paid for.
- Achievement stats: `kills`, `wins`, `matches`, `assists`, `bestScore`, `playtime` (seconds). Goal
  kinds: `stays`, `kills`, `wins`, `assists`, `score`, `playtime`, `rated`. The day is UTC.

### Shop, inventory, equipment and boosts

| Method | Path | Auth | Request | Answer | Refusals |
|---|---|---|---|---|---|
| GET | `/v1/shop` | none | none | 200 `{offers: [{sku, itemId, price, requiresLevel, availableTo, currency}]}`, those on sale now | — |
| GET | `/v1/inventory` | session | none | 200 `{coins, gems, items: [{itemId, qty, level}]}` | 401 |
| POST | `/v1/purchases` | session | `{sku, key}` | 200 `{result: "bought" or "already_bought", itemId, coins, held, gems}` | 400 `invalid_body`, `invalid_key`; 401; 403 `level_required`; 404 `unknown_sku`; 409 `not_available`, `insufficient_funds` |
| POST | `/v1/inventory/{itemId}/level` | session | `{key}` | 200 `{itemId, level, coins}` | 400 `invalid_key`, `not_equipment`; 401; 404 `not_held`, `no_such_route`; 409 `max_level` (5), `insufficient_funds` |
| GET | `/v1/equipment` | session | none | 200 the loadout | 401 |
| PUT | `/v1/equipment/{slot}` | session | `{itemId}` | 200 the loadout | 400 `invalid_body`, `invalid_slot`; 401; 404 `unknown_item`; 409 `wrong_slot`, `not_owned` |
| DELETE | `/v1/equipment/{slot}` | session | none | 200 the loadout | 400 `invalid_slot`; 401 |
| GET | `/v1/boosts` | session | none | 200 `{boosts: [{kind, itemId, percent, endsAt}]}` | 401 |
| POST | `/v1/boosts` | session | `{itemId, key}` | 200 `{result: "activated" or "already_activated", boosts}` | 400 `invalid_body`, `invalid_key`; 401; 404 `unknown_item` (none, or not a boost); 409 `not_owned`, `other_running` |

- **Keys.** A purchase's, an item level's and a boost's key is the client's, made once per tap and
  sent unchanged with every retry: 16 to 48 of `[a-z0-9-]`, a UUID in lower case. A key used before
  is answered as its first attempt was, whatever the retry names.
- **The loadout** is `{slots: {barrel, armor, core, treads, skin}, bonus: {stat: percent}}`, each
  slot an item id or null, the bonus only for the stats it touches. Stats are named `health_regen`,
  `max_health`, `body_damage`, `bullet_speed`, `bullet_penetration`, `bullet_damage`, `reload`,
  `movement_speed`. An item gives its percent × (3 + level) / 4, rounded down; everything worn is
  added per stat and capped at 25. An item worn but no longer held gives nothing.
- **An item's level** costs 500 coins from level 1, then twice as much each level: 500, 1 000,
  2 000 and 4 000 to reach 2, 3, 4 and 5. Only equipment has levels.
- `currency` is `coins` or `gems`; `availableTo` is null for an offer with no end.

### Payments and the season pass

| Method | Path | Auth | Request | Answer | Refusals |
|---|---|---|---|---|---|
| GET | `/v1/payments/packs` | none | none | 200 `{packs: [{productId, gems, priceCents, currency: "USD"}]}` | 503 `payments_off` |
| POST | `/v1/payments` | session | `{productId, key}` | 200 `{order}`: pending; the same order for a key already used | 400 `invalid_body`, `invalid_key` (16 to 36 of `[a-z0-9-]`); 401; 404 `unknown_product`; 409 `refund_debt`; 503 `payments_off` |
| GET | `/v1/payments/{orderId}` | session | none | 200 `{order}` | 401; 404 `no_such_order` (none, or another player's); 503 `payments_off` |
| POST | `/v1/payments/{orderId}/simulate` | session | `{outcome: "paid" or "declined"}` | 200 `{order, confirmed, gems}`: `confirmed` false when the order was no longer pending; `gems` the balance after | 400 `invalid_outcome`; 401; 404 `no_such_order`; 503 `payments_off` |
| GET | `/v1/pass` | session | none | 200 the pass (below) | 401 |
| POST | `/v1/pass/premium` | session | none | 200 `{result: "bought" or "already_bought", gems, pass}` | 401; 409 `insufficient_funds` (500 gems), `season_ended` |

- Every payment route answers 503 `payments_off` unless `BACKEND_PAYMENT_PROVIDER=simulated`. An
  `orderId` is 36 characters of `[0-9a-f-]`.
- **An order** is `{orderId, productId, gems, bonus, priceCents, currency, state, createdAt}`,
  `createdAt` to the millisecond and the same in every answer;
  `state` is `pending`, `paid`, `declined`, `refunded` or `expired`; `bonus` is the first paid
  order's extra gems, as many again, once per player.
- **The pass** is `{season, endsAt, points, tier, premium, premiumGems: 500, tierPoints: 250, tiers:
  [{tier, free: {coins, gems, itemId}, premium: {coins, gems, itemId}}]}`, forty tiers. A free tier
  pays 150 coins, every fifth 5 gems; a premium tier 15 gems, every fifth with a boost too (the xp
  boost at 5, 15, 25 and 35, the coins boost at 10, 20, 30 and 40). Premium's `coins` is always 0.
  Points are earned in `worker`.

### Content tables

| Method | Path | Auth | Answer |
|---|---|---|---|
| GET | `/v1/content/classes` | none | 200 `{version, classes}`, the class table a client draws by |
| GET | `/v1/content/phrases` | none | 200 `{version, phrases: [{id, key, text}]}` |
| GET | `/v1/content/skins` | none | 200 `{version, skins: [{skin, itemId}]}` |
| any | `/health` | none | 200 `{"status": "ok"}`, outside the metrics |

Each content table carries an `ETag`, its version, a hash of its content; an `If-None-Match` naming
it is answered 304. The class and phrase tables' versions are what the arena's Welcome names
(`contentVersion`, `phraseListVersion`).

### Teams

All need a session. **A team** is `{id, name, members: [{playerId, name, role}], rating, wins,
losses, draws}`, `role` `leader`, `vice_leader` or `member`.

| Method | Path | Request | Answer | Refusals |
|---|---|---|---|---|
| GET | `/v1/teams?name=` | none | 200 `{teams: [{id, name, members, rating}]}`, up to 20 whose name starts so | 400 `invalid_name` (empty, or over 16 characters) |
| POST | `/v1/teams` | `{name}` | 200 the team | 400 `invalid_name`; 409 `name_taken`, `in_team`, `cooling_down` (24 h after leaving one) |
| GET | `/v1/teams/{id}` | none | 200 `{id, name, members, rating}` | 404 `no_such_team` |
| POST | `/v1/teams/{id}/applications` | none | 200 `{}` | 404 `no_such_team`; 409 `in_team`, `cooling_down`, `already`, `team_full`, `too_many_applied` (5 out); 429 `too_soon` (20 an hour, `twenty applications an hour at most`) |
| DELETE | `/v1/teams/{id}/applications` | none | 200 `{}` | 404 `no_application` |
| GET | `/v1/teams/mine` | none | 200 the team | 404 `no_team` |
| DELETE | `/v1/teams/mine` | none | 200 `{}`: disbanded | 403 `not_allowed`; 404 `no_team` |
| POST | `/v1/teams/mine/leave` | none | 200 `{}` | 404 `no_team`; 409 `leader_with_members` |
| POST | `/v1/teams/mine/invites` | `{playerId}` | 200 the team | 400 `invalid_body`; 403 `not_allowed`; 404 `no_such_player`; 409 `in_team`, `too_many_invited` (20 out); 429 `too_soon` (20 an hour) |
| POST | `/v1/teams/mine/leader` | `{playerId}` | 200 the team | 400 `invalid_body`; 403 `not_allowed`; 404 `not_a_member` |
| PUT | `/v1/teams/mine/name` | `{name}` | 200 the team | 400 `invalid_name`; 403 `not_allowed`; 409 `name_taken`; 429 `too_soon` (once in 30 days) |
| GET | `/v1/teams/mine/applications` | none | 200 `{applications: [{playerId, name, expiresAt}]}` | 403 `not_allowed` (a member); 404 `no_team` |
| POST | `/v1/teams/mine/applications/{playerId}` | `{accept}` | 200 the team | 400 `invalid_body`; 403 `not_allowed`; 404 `no_application`; 409 `in_team`, `cooling_down`, `team_full` |
| DELETE | `/v1/teams/mine/members/{playerId}` | none | 200 the team | 403 `not_allowed`; 404 `not_a_member` |
| POST | `/v1/teams/mine/members/{playerId}/role` | `{role: "vice_leader" or "member"}` | 200 the team | 400 `invalid_body`, `invalid_role`; 403 `not_allowed`; 404 `not_a_member`; 409 `too_many_vices` (2) |
| GET | `/v1/team-invites` | none | 200 `{invites: [{teamId, teamName, expiresAt}]}`, the 50 newest | 401 |
| POST | `/v1/team-invites/{teamId}` | `{accept}` | 200: accepted, the team; declined, the remaining invitations `{invites}` | 400 `invalid_body`; 404 `no_invite`; 409 `in_team`, `cooling_down`, `team_full` |
| GET | `/v1/team-applications` | none | 200 `{applications: [{teamId, teamName, expiresAt}]}`, the player's own | 401 |

A team holds 30. Invitations and applications last seven days. Every change of who is in a team,
or in which role, is pushed to its members as `evt.team.update`.

### Tournaments

| Method | Path | Auth | Answer | Refusals |
|---|---|---|---|---|
| GET | `/v1/tournaments` | none | 200 `{tournaments: [view]}`, those registering, seeded or running, with their entries | — |
| GET | `/v1/tournaments/{id}` | none | 200 the view, with its bracket | 404 `no_such_tournament` |
| POST | `/v1/tournaments/{id}/entries` | session | 200 the view | 400 `party_too_small`; 401; 403 `not_allowed`; 404 `no_such_tournament`; 409 `closed`, `too_few_rated` (ten rated matches in its mode), `already` (said before `full`), `full`, `in_party`, `not_one_team` |
| DELETE | `/v1/tournaments/{id}/entries` | session | 200 the view | 401; 403 `not_allowed`; 404 `no_such_tournament`; 409 `not_registered` |
| GET | `/v1/tournaments/{id}/match` | session | 200 `{tournamentId, round, arenaHost, arenaPort, ticketId, tls, mode}`, the grant `worker` kept, for its 60 s | 401; 404 `no_match` |

**The view** is `{id, name, mode: "duel" or "teams", format: "elimination" or "round_robin", state,
maxEntries, registrationEnds, startsAt, roundMinutes, currentRound, prizes: [first, second, third],
entries, matches, standings?}`:

- `state` is `registration`, `seeded`, `running`, `finished` or `cancelled`.
- A duel's `entries` are `{playerId, name, seed}`; a teams' are `{teamId, name, seed, roster:
  [{playerId, name}]}`. The seed is 0 before seeding.
- `matches` are `{round, slot, state: "pending", "ready" or "done", playerA?, playerB?, winner?}`,
  or `teamA`, `teamB`, `winnerTeam` for teams. A draw is a match done with no winner.
- `standings`, a round robin's only, by place: `{playerId or teamId, name, points, wins, draws,
  losses}`.

A teams' tournament is entered by the team's leader or a vice leader, leading a party of three of
its members, as a team match is queued.

### Friends, blocks and the inbox

All need a session.

| Method | Path | Request | Answer | Refusals |
|---|---|---|---|---|
| GET | `/v1/friends` | none | 200 `{friends: [{playerId, name, online}], requests: [{playerId, name, expiresAt}], asked: [the same]}` | 401 |
| POST | `/v1/friends` | `{playerId}` | 200 `{state: "asked" or "friends"}` (they asked first: now friends) | 400 `invalid_body`, `yourself`; 404 `no_such_player`; 409 `already_friends`, `already_asked`, `friends_full` (100), `you_blocked`, `too_many_asked` (50 out); 429 `too_soon` (20 an hour) |
| DELETE | `/v1/friends/{playerId}` | none | 200 `{}` | 404 `not_friends` |
| DELETE | `/v1/friend-requests/{playerId}` | none | 200 `{}`: theirs declined, or one's own withdrawn | 404 `no_request` |
| GET | `/v1/blocks` | none | 200 `{blocked: [{playerId, name}]}` | 401 |
| POST | `/v1/blocks` | `{playerId}` | 200 `{}` | 400 `invalid_body`, `yourself`; 404 `no_such_player`; 409 `blocks_full` (100) |
| DELETE | `/v1/blocks/{playerId}` | none | 200 `{}` | 404 `not_blocked` |
| GET | `/v1/inbox` | none | 200 `{items: [{id, kind, ref, at, read}]}`, the 50 newest | 401 |
| POST | `/v1/inbox/read` | `{upTo}` | 200 `{}`: every item up to that id read | 400 `invalid_body`; 401 |

- `online` is whether the friend's lobby connection is registered now.
- Inbox kinds: `friend_request`, `friend_accepted`, `team_invite`, `tournament_prize`,
  `team_application`, `season_reward`. `ref` is the other player's, the team's or the tournament's
  id (an application's, the applicant's; a season reward's, the season × 10 + the board).
- A request from a player the other has blocked is answered as asked and never shown to them; a
  party or team invitation from one likewise.

## The admin API

On `BACKEND_ADMIN_ADDR`, every path under `/admin/`. Every call needs `Authorization: Bearer
<secret>`: without it, or with another, 401 `unauthorised`, and the attempt is audited.

- **Audited** in MySQL (`admin_audit`): the call, its target (cut to 128 characters), what it asked
  (cut to 1 024) and what it did, refusals included. Not audited: an unknown path (404
  `not_found`), a wrong method (405), a bad `days` (400 `invalid_days`), a 500, and a 503 for a
  call that did nothing. A ban's 503s are audited with the ban; a notice is audited `sent` before
  it goes.
- **A store failure** (j-redis) is 503 `storage_unavailable`, `the store did not answer: call
  again`, by whichever path it came (`AdminServer.storeDown`, as the player API judges one): in
  the arenas, the rooms, a close, a kick, a notice's broadcast. Each is safe to make again; only
  a notice the store took without answering goes out twice. A ban whose sessions cannot be ended
  is 503 `sessions_not_ended`, and skips the push and the kick until it is called again; one
  whose sessions ended but whose arena kick failed is 503 `not_taken_out`. Until 2026-10-06 these
  were 500 `internal` with the exception's text (O-36).
- **A reason** is required on every call that changes something: `{"reason"}`, 1 to 200
  characters, else 400 `no_reason`.
- **Bodies** are read up to 4 096 bytes.
- **The database failing** is 503 `storage_unavailable`, nothing done; anything else unexpected is
  500 `internal`.
- An unknown path is 404 `not_found`; a known path with another method is 405 with `Allow`.

| Method | Path | Body | Answer | Refusals |
|---|---|---|---|---|
| GET | `/admin/arenas` | none | 200 `[{name, host, port, players, maxPlayers, tls, rooms, maxRooms}]` | 405 |
| GET | `/admin/rooms` | none | 200 `[{arena, …each room as its arena announced it}]` | 405 |
| POST | `/admin/rooms/{arena}/{room}/close` | `{reason}` | 202 `{heard}`, the arenas that heard | 400 `no_reason`; 404 `not_found`, `no_such_arena`; 405 |
| POST | `/admin/players/{id}/ban` | `{reason, until?}` | 200 `{playerId, status: "banned" or "suspended", sessionsEnded, until?}` | 400 `no_reason`, `bad_until` (not an ISO-8601 instant, or not in the future); 404 `no_such_player`, `not_found`; 405; 503 `sessions_not_ended`, `not_taken_out` |
| POST | `/admin/players/{id}/unban` | `{reason}` | 200 `{playerId, status: "active", sessionsEnded: 0}` | 400 `no_reason`; 404 `no_such_player`; 405 |
| POST | `/admin/players/{id}/kick` | `{reason}` | 202 `{playerId, arenas}`, the arenas that heard | 400 `no_reason`; 404 `not_found`; 405 |
| POST | `/admin/players/{id}/refund-debt` | `{reason}` | 200 `{playerId, cleared}`, the gems of debt cleared | 400 `no_reason`; 405 |
| GET | `/admin/stats?days=N` | none | 200 `{days: [{day, active, newPlayers, d1, d7, d30}]}`, newest first | 400 `invalid_days` (1 to 60; 14 if absent); 405 |
| GET | `/admin/stats/features?days=N` | none | 200 `{days: [{day, newPlayers, features: {name: {players, d1, d7, d30}}}]}`, the names `queued`, `bought`, `boosted`, `tournament`, `friend`, `team` | 400 `invalid_days`; 405 |
| GET | `/admin/stats/funnel?days=N` | none | 200 `{days: [{day, registered, guests, played, returned, level5, rated, bought, paid}]}` | 400 `invalid_days`; 405 |
| GET | `/admin/stats/guests` | none | 200 `{players, guests, inactive}` | 405 |
| POST | `/admin/notice` | `{text, reason}` | 202 `{gateways}`, the gateways that heard | 400 `invalid_text` (1 to 200 characters, none a control, format or line separator), `no_reason`; 405 |
| POST | `/admin/tournaments` | `{reason, name, maxEntries, registrationEnds, startsAt, roundMinutes, prizes: [three], mode?, format?}` | 200 `{id}` | 400 `no_reason`, `invalid_tournament` (the message names the field); 405 |
| GET | `/admin/seasons` | none | 200 `{seasons: [{id, startsAt, endsAt, placedAt, paidAt, resetAt}]}`, the 100 newest | 405 |
| POST | `/admin/seasons/end` | `{reason}` | 200 `{season, endsAt}`: the current season ends now | 400 `no_reason`; 405; 409 `already_ended` |
| POST | `/admin/payments/{orderId}/refund` | `{reason}` | 200 `{orderId, playerId, taken, debt}` | 400 `no_reason`; 404 `no_such_order`, `not_found`; 405; 409 `not_paid` |

- **A ban** with `until` is a suspension until then; without, a ban for good. It sets the account's
  status, ends every session the player has, closes their lobby connection (`evt.session.revoked`)
  and takes them out of any match; every arena refuses that player's tickets for a ticket's life,
  60 s. If the sessions cannot be ended, the answer is 503 `sessions_not_ended`: the ban is
  recorded, and calling again ends them.
- **A tournament**: `name` 1 to 64 characters, none a control or format character; `maxEntries` 2
  to 32 (a round robin 2 to 8); `registrationEnds` in the future and `startsAt` no earlier;
  `roundMinutes` 1 to 60; `prizes` three whole numbers of coins, 0 to 1 000 000 000; `mode` `duel`
  (default) or `teams`; `format` `elimination` (default) or `round_robin`.
- **A refund** takes a paid order's gems back, its first-purchase bonus too, as far as the balance
  allows; the rest is a debt that refuses the player's next order (409 `refund_debt`) until
  `refund-debt` clears it. Refund and clearing are audited in their own transaction.
- **Ending a season** is for a drill, or to bring the calendar into line; `worker` closes it within
  the minute, and `GET /admin/seasons` shows how far (`placedAt`, `paidAt`, `resetAt`).
- `d1`, `d7` and `d30` are null until their day has ended.

## Pushes

Sent through `handoff/LobbyPush` to the gateway holding the player's lobby connection
(`push:{gateway}`), as `{"t": type, "d": data}`; a notice to every gateway on `push:all`. A push says
"look": what must not be lost can be fetched.

| Event | Data | To | Sent by |
|---|---|---|---|
| `evt.match.ready` | `{matchUid, mode, seconds: 10}` | every player of a match found | `Matchmaker` |
| `evt.match.found` | `{arenaHost, arenaPort, ticketId, tls, mode}` | every player of a match made or a sandbox opened | `Matchmaker`, `QueueService` |
| `evt.queue.update` | `{state: "queued" or "none", mode}` (`mode` null for none) | party members queued by the leader, and players taken out of the queue or put back | `QueueService`, `Matchmaker` |
| `evt.party.invite` | `{partyId, from, fromName}` | the invitee, if in the lobby and not blocking the inviter | `PartyService` |
| `evt.party.update` | a party, as the routes answer it | every member, and the one who left | `PartyService` |
| `evt.party.said` | `{from, name, phraseId}` | every member, the speaker too | `PartyService` |
| `evt.team.update` | `{team}`, the team or null | the members, and one no longer in it | `TeamService` |
| `evt.inbox` | `{}` | the player given an inbox item | `FriendService`, `TeamService` |
| `evt.friend.request`, `evt.friend.accepted` | `{playerId, name}` | the player asked, or accepted | `FriendService` |
| `evt.notice` | `{text}` | everyone in the lobby | `AdminServer` |
| `evt.session.revoked` | `{reason: "banned" or "suspended"}` | the player banned | `AdminServer` |

The arenas are told on `arena-admin:{name}`: `{"cmd": "close", "room"}` and `{"cmd": "kick",
"player", "ban"?}`. `evt.tournament.match` and `evt.rewards` are `worker`'s.

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
| a board's `limit` | default 50, at most 100; five rows either side of one's own | `PlatformHttpServer` |
| a rating board's top, the teams' | kept 30 s | `RatingLeaderboards` |
| a session | 86 400 s ±10 % | `handoff/SessionStore` |
| a ticket, a matched player's record, a sandbox's hold before it is joined, a tournament's grant | 60 s | `handoff/TicketStore` |
| a queued player's record | 900 s | `MatchQueue` |
| answering a match found | 10 s; a match asked about is kept 60 s | `Matchmaker`, `MatchQueue` |
| locked out after declining or not answering | 60 s | `Matchmaker` |
| the matcher | a round every second, a fixed delay between rounds; the lease 5 s | `Matchmaker` |
| the rating window | ±100, 50 wider every 10 s waited; the oldest matched among the 10 closest | `Matchmaker` |
| a watched change overtaken | tried 5 times, then 503 | `MatchQueue`, `Parties` |
| a party | 3 players; 3 600 s, renewed on each change; an invitation 60 s | `Parties` |
| teams | 30 a team, 2 vice leaders, 24 h between teams; invitations and applications 7 days; 20 invitations out, 5 applications out; 20 found by a search | `persistence/TeamRepository` |
| friends | 100 friends, 100 blocked, 50 requests out, requests last 7 days, 100 listed | `persistence/FriendRepository` |
| the inbox | 50 listed | `persistence/InboxRepository` |
| a pending order | expired by `worker` after a day | `persistence/PaymentRepository` |
| a store call for a score board (its read, a rename's write) | 3 s; every other store call the client's 2 s | `PlatformHttpServer`, j-redis client |
| MySQL | pool 16; a connection 3 s to borrow, 2 s to connect; a statement's socket 30 s; a lock wait 20 s | `persistence/Database` |

## Metrics

At `GET /metrics` on `BACKEND_METRICS_ADDR`:

| Metric | Labels | What |
|---|---|---|
| `backend_platform_responses_total` | `status` | every API response but `/health` |
| `backend_platform_request_seconds` | `route` (the API's path, not the client's) | a histogram, 5 ms to 2.5 s |
| `backend_platform_logins_total` | `outcome`: ok, invalid_credentials, banned, throttled, busy | |
| `backend_platform_purchases_total` | `outcome`: bought, already_bought and each refusal | |
| `backend_platform_hasher_line` | | requests holding or waiting for a hash; 168 is full |
| `backend_platform_open_tickets_issued_total` | | tickets for the public arena |
| `backend_platform_matches_made_total` | `mode` | |
| `backend_platform_match_tickets_issued_total` | `mode` | tickets for made matches |
| `backend_platform_queue_wait_seconds` | `mode` | a histogram, 1 s to 10 min, queued to match found |
| `backend_platform_queue_players` | `mode` | left waiting by the last round; 0 where another platform leads |
| `backend_platform_confirms_total` | `outcome`: made, declined, withdrawn, lapsed, no_room | |
| `backend_platform_pushes_unheard_total` | | pushes to a player whose gateway nobody heard |

and the process's own `backend_jvm_*`, `backend_process_uptime_seconds` and `backend_store_*`.

## Building and testing

```bash
# from backend/: platform and what it needs, with its tests
export JAVA_HOME=/opt/jdk21 PATH=/opt/jdk21/bin:$PATH
/opt/maven/bin/mvn -o -pl platform -am install
```

The tests need MySQL at `127.0.0.1:3306`, database `backend_test`, user `backend`, password
`backend-dev-password` (or `JDBC_URL`, `DB_USER`, `DB_PASSWORD`); a test that needs a store starts
an embedded j-redis. The live drill (`client/headless-drill.sh`, see the
[backend README](../README.md#commands)) drives this module through the real stack.

| Test class | Tests | What it covers |
|---|---|---|
| `net/PlatformHttpServerTest` | 80 | every route over HTTP against real MySQL and j-redis: answers, codes, the body limit, exact paths, addresses behind nginx, the store failing |
| `MatchmakerTest` | 42 | lining up every mode's sides, the confirm step's every path, races between matchers (T-16, T-32 to T-34), tournament calls, the lease, the metrics |
| `AuthServiceTest` | 15 | register, login, sessions, bans and suspensions, the decoy hash, rehashing, the upgrade's checks |
| `net/AdminServerTest` | 15 | every admin call, its audit and its refusals; starting only when named, with a secret, on loopback |
| `PartiesTest` | 11 | invitations, accepting, leaving, kicking, versions (D-74), races |
| `DisplayNameTest` | 10 | the name rules |
| `PasswordHasherTest` | 8 | Argon2id, its stored form, the line's bounds |
| `ItemsTest` | 7 | `items.json`'s rules and the release's items |
| `CatalogueTest` | 6 | `shop.json`'s rules and the release's offers |
| `LoginThrottleTest` | 5 | both limits, IPv6 per /64, the store failing |
| `PlatformToArenaTest` | 5 | register, log in, ticket and play against a real arena |
| `PacksTest` | 4 | `packs.json`'s rules and the release's packs |
| `AskThrottleTest`, `PassServiceTest`, `PaymentServiceTest` | 1 each | the hourly limit; the pass's items exist; the provider's setting |

## Where the design is

| Subject | 04 |
|---|---|
| accounts, sessions, guests, renaming | [§1](../../docs/detailed-design/04-platform-services.md#1-auth-and-sessions) |
| teams | [§2](../../docs/detailed-design/04-platform-services.md#2-teams) |
| tickets and the arena directory | [§3](../../docs/detailed-design/04-platform-services.md#3-arena-registry-rooms-and-tickets) |
| the queue, the matcher, parties, the sandbox | [§4](../../docs/detailed-design/04-platform-services.md#4-matchmaking) |
| tournaments | [§6](../../docs/detailed-design/04-platform-services.md#6-tournaments) |
| boards and seasons | [§7](../../docs/detailed-design/04-platform-services.md#7-leaderboards) |
| shop, equipment, levels, gems, payments, the pass, skins, boosts | [§8](../../docs/detailed-design/04-platform-services.md#8-economy-shop-inventory-and-equipment) |
| friends, blocks, the inbox | [§9](../../docs/detailed-design/04-platform-services.md#9-notifications) |
| the admin API | [§10](../../docs/detailed-design/04-platform-services.md#10-admin-api) |
| metrics | [§11](../../docs/detailed-design/04-platform-services.md#11-what-to-measure) |
