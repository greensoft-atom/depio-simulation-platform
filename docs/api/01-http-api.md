# The player API (HTTP)

What a client calls over HTTPS: accounts and sessions, content, the economy, progress and
boards, matchmaking, the party, the social layer, teams and tournaments. Served by `platform`
under `/v1/`; the lobby WebSocket is [02](02-lobby-websocket.md), the operator's API
[03](03-admin-api.md). Every example below is a real request and its real answer, from the
Postman collection's run of 2026-10-06 against a stack built from this repository (README,
"How this was checked"), unless it says otherwise.

## 0. Basics

### Where

| Where | Base URL |
|---|---|
| Production, through nginx (TLS) | `https://<name>/v1/...`, for example `https://a.example.com/v1/shop` |
| One machine, straight to `platform` (tests, the examples below) | `http://127.0.0.1:8080/v1/...` (`PLATFORM_PORT`; the examples ran on 8180) |

nginx proxies `/v1/` and `/lobby` only, so `/health` is not reachable from outside. It also
limits the four login and registration `POST`s to 2 a second per address (burst 30), answering
its own 429 (not JSON) over that, and takes bodies of 4 KB at most (its own 413, not JSON).

### Requests

- **JSON** bodies, UTF-8; `Content-Type` is not checked. At most **4096 bytes**, or **413**
  `body_too_large`.
- Unknown fields are ignored. A body that is empty, not JSON, or has a value of the wrong
  type counts as no body, and the route answers its own 400 (`invalid_body` or the field's
  code).
- **Authentication**: `Authorization: Bearer <token>`, the token from `POST /v1/sessions`.
  Routes marked "Bearer" below answer **401** `no_token` without it and **401**
  `invalid_session` when it names no live session (unknown, expired, logged out, or the
  player banned).
- **Idempotency keys**: a purchase, an order, a boost and an item level carry a `key` chosen by
  the client, the same on every retry of one action: a lowercase UUID fits (16 to 48 of
  `a-z 0-9 -`; 16 to 36 for an order). A key used before answers the first result again and
  takes nothing.
- **Ids** in paths are 1 to 18 digits; anything longer names no route (404).

### Responses

- JSON (`Content-Type: application/json`), field names in camelCase. A field with no value is
  `null`, except in the queue's and the boosts' answers, which leave it out.
- Times are ISO-8601 in UTC: `2026-10-12T22:29:57.845Z` (no fraction when it is zero).
- A 204 has no body; a 304 has none either.
- **Errors** are `{"code": "...", "message": "..."}`: branch on `code`, show or log
  `message`.

```json
{"code":"invalid_session","message":"log in again"}
```

Any route can also answer:

| Status | code | When |
|---|---|---|
| 405 | `method_not_allowed` | wrong method; the `Allow` header lists the right ones (`Allow: POST`) |
| 413 | `body_too_large` | a body over 4096 bytes: `{"code":"body_too_large","message":"at most 4096 bytes"}` |
| 503 | `storage_unavailable` | MySQL or the store did not answer: try again shortly |
| 500 | `internal` | a fault: report it |

A path no route knows gets the server's own 404 page (HTML), not JSON.

### Limits

| What | Limit | Answer over it |
|---|---|---|
| Registrations, logins (password and guest key), guests made, upgrades | 30 a minute per address, all together | 429 `too_many_attempts` `try again in <N> s`, header `Retry-After: <N>` |
| Password logins naming one username | 10 in 15 minutes | the same |
| Password hashing busy (168 at once) | | 503 `busy`, `Retry-After: 1..5` |
| Friend requests, team invitations, team applications | 20 an hour each, per player | 429 `too_soon` (no `Retry-After`) |
| Party invitations | 60 an hour per player | 429 `too_soon` |
| Party phrases | one every 2 s | 429 `too_soon` |
| Display name, team name | one change in 30 days (the first at once) | 429 `too_soon` |

## 1. Health

### `GET /health`

No auth. Checks nothing but that the process answers; for the machine's own monitoring.

```http
GET /health
```
```json
200 {"status":"ok"}
```

## 2. Accounts and sessions

Rules, for registration and the upgrade:

| Field | Rule |
|---|---|
| `username` | 3 to 32 of `a-z A-Z 0-9 _ -`; unique however it is cased |
| `password` | 8 to 128 characters |
| `displayName` | optional (the username's first 16 characters when absent); at most 16 letters (any script, but Latin, Cyrillic and Greek not mixed), digits, spaces and `_ - .`, with a letter or a digit; not a reserved word (`admin`, `mod`, `support`, `staff`, `system`...) |

A refused display name answers 400 `invalid_display_name` with the reason as its message, for
example `letters, digits, spaces, _ - and . only` or `that name is reserved`.

### `POST /v1/accounts`: register

No auth. Body `{"username", "password", "displayName"?}`. Does not log in.

```http
POST /v1/accounts
{"username": "ada_muvtoo4q", "password": "example-password-1", "displayName": "Ada"}
```
```json
201 {"playerId":1}
```

| Status | code | When |
|---|---|---|
| 400 | `invalid_body` | username or password missing |
| 400 | `invalid_username`, `invalid_password`, `invalid_display_name` | a rule above |
| 409 | `username_taken` | `{"code":"username_taken","message":"that name is in use"}` |
| 429 | `too_many_attempts` | §0, limits |

A new account has level 1, 0 coins and 0 gems.

### `POST /v1/sessions`: log in

No auth. Either `{"username", "password"}` or, for a guest, `{"guestKey"}`.

```http
POST /v1/sessions
{"username": "ada_muvtoo4q", "password": "example-password-1"}
```
```json
200 {"token":"1eOf0B5d4yuUqtrMjtXfpdmXlBEsmwTcxQviWTazrZM","playerId":1,"expiresInSeconds":90310}
```

- `token`: 43 characters; send it as `Authorization: Bearer <token>` and in the lobby's `auth`.
- `expiresInSeconds`: about a day (86 400 ± 10 %), fixed: using the session does not extend
  it. Log in again before it ends. Every login makes a new session; the old ones stay valid.

| Status | code | When |
|---|---|---|
| 400 | `invalid_body` | `username and password are required` |
| 401 | `invalid_credentials` | `username or password is wrong` (one answer for every cause); for a key, `no guest has that key` |
| 403 | `banned` | `this account cannot play`: banned, or suspended until a time not yet passed |
| 429, 503 | `too_many_attempts`, `busy` | §0, limits |

### `DELETE /v1/sessions`: log out

Bearer. Ends the session of the token sent; **204** with no body, whether or not it was live.

```http
DELETE /v1/sessions
Authorization: Bearer 1eOf0B5d4yuUqtrMjtXfpdmXlBEsmwTcxQviWTazrZM
```
```
204
```

The same token afterwards: `401 {"code":"invalid_session","message":"log in again"}`.

### `POST /v1/guests`: a guest account

No auth, no body. An account with no password, played at once; it logs in by its key.

```http
POST /v1/guests
```
```json
201 {"playerId":5,"guestKey":"ufLTWOrWaP5xzCWrL0GEMk420bBcuUsbMUwLiijmQUc","displayName":"Guest8223"}
```

Then `POST /v1/sessions {"guestKey": "ufLTWOrW..."}` gives a token as above. Keep the key on
the device: it is the account.

### `POST /v1/accounts/upgrade`: a guest becomes an account

Bearer (the guest's). Body `{"username", "password", "displayName"?}`: the same player, its
progress kept, now with a username and password; the guest key stops working.

```http
POST /v1/accounts/upgrade
Authorization: Bearer aEdwwBm-3sSnB16EK6x2LZWm0N7xQUEVXf9GPVETiRw
{"username": "gus_muvtoo4q", "password": "example-password-1", "displayName": "Gus"}
```
```json
200 {}
```

Errors: those of registration, plus 409 `not_a_guest` (`this account has a username already`).

### `PUT /v1/accounts/name`: change the display name

Bearer. Body `{"displayName"}`. Once in 30 days (a new account's first change is at once).

```http
PUT /v1/accounts/name
Authorization: Bearer 1eOf0B5d...
{"displayName": "Ada Lovelace"}
```
```json
200 {"playerId":1,"displayName":"Ada Lovelace"}
```

The name answered is the one stored, normalised (spaces collapsed, NFKC). Again within 30 days:
`429 {"code":"too_soon","message":"a display name changes once in 30 days"}`; a refused name:
400 `invalid_display_name`.

## 3. Content

Public, no auth, `GET` only: what the client draws by. Each answer carries `ETag: "<version>"`;
sent back as `If-None-Match: "<version>"`, it is **304** with no body when unchanged. The
`version` is also in the body, and the classes' and phrases' versions are the ones the arena's
`Welcome` names, so a client knows when to fetch again.

### `GET /v1/content/classes`

The 52 tank classes, with their barrels (32 KB).

```json
200 ETag: "3866033193"
{"version":3866033193,"classes":[
  {"id":0,"name":"Basic","opensAt":1,"parents":[],"fov":1.0,"reload":1.0,"maxDrones":0,
   "caps":[7,7,7,7,7,7,7,7],"bodyDamage":1.0,"hidesAfter":0,"bodySize":1.0,"zoom":0.0,
   "barrels":[{"angle":0.0,"side":0.0,"delay":0.0,"speed":1.0,"damage":1.0,"penetration":1.0,
               "lifetime":75,"spread":0.0,"size":1.0,"recoil":0.0,"kind":0,"arc":0.0}]},
  {"id":1,"name":"Twin","opensAt":15,"parents":[0], ...},
  ...]}
```

`opensAt` is the level a class can be chosen from, `parents` the classes it grows from, `caps`
the eight stats' upgrade caps. A barrel's `kind`: 0 bullet, 1 trap, 2 drone, 3 minion,
4 rocket, 5 skimmer, 6 convert; `lifetime` in ticks.

### `GET /v1/content/phrases`

The quick-chat phrases a party or a match may send, by `id`.

```json
200 ETag: "3565542594"
{"version":3565542594,"phrases":[{"id":1,"key":"hello","text":"Hello!"},{"id":2,"key":"good_luck","text":"Good luck!"},
 {"id":3,"key":"thanks","text":"Thanks!"}, ... ,{"id":16,"key":"bye","text":"Bye!"}]}
```

### `GET /v1/content/skins`

Which item draws which skin number (the number a match's snapshot carries).

```json
200 ETag: "3295025236"
{"version":3295025236,"skins":[{"skin":1,"itemId":"skin_crimson"},{"skin":2,"itemId":"skin_azure"},
 {"skin":3,"itemId":"skin_jade"},{"skin":4,"itemId":"skin_gold"},{"skin":5,"itemId":"skin_carbon"}]}
```

```http
GET /v1/content/skins
If-None-Match: "3295025236"
```
```
304 ETag: "3295025236"
```

## 4. The economy

Two currencies: **coins**, earned by playing (matches, daily goals, the pass's free tiers,
tournament prizes; no route grants them), and **gems**, bought with money (simulated for now,
§4.7) and earned by achievements and the pass. A new account has neither.

### `GET /v1/shop`

Public. The offers on sale now.

```json
200 {"offers":[
  {"sku":"treads_light","itemId":"treads_light","price":1200,"requiresLevel":1,"availableTo":null,"currency":"coins"},
  {"sku":"barrel_steel","itemId":"barrel_steel","price":1500,"requiresLevel":1,"availableTo":null,"currency":"coins"},
  {"sku":"armor_plate","itemId":"armor_plate","price":1500,"requiresLevel":1,"availableTo":null,"currency":"coins"},
  {"sku":"barrel_rifled","itemId":"barrel_rifled","price":2000,"requiresLevel":5,"availableTo":null,"currency":"coins"},
  {"sku":"core_capacitor","itemId":"core_capacitor","price":2000,"requiresLevel":5,"availableTo":null,"currency":"coins"},
  {"sku":"boost_xp_hour","itemId":"boost_xp_hour","price":20,"requiresLevel":1,"availableTo":null,"currency":"gems"},
  {"sku":"boost_coins_hour","itemId":"boost_coins_hour","price":20,"requiresLevel":1,"availableTo":null,"currency":"gems"},
  {"sku":"skin_crimson","itemId":"skin_crimson","price":150,"requiresLevel":1,"availableTo":null,"currency":"gems"},
  ... skin_azure, skin_jade 150; skin_gold, skin_carbon 400 gems]}
```

`availableTo` is the end of a limited offer, or `null`. Items: equipment worn in a slot
(`barrel`, `armor`, `core`, `treads`), skins (slot `skin`), and boosts (an hour of double XP
or coins).

### `GET /v1/inventory`

Bearer. Balances and the items held (quantity above 0), by item id.

```json
200 {"coins":0,"gems":330,"items":[{"itemId":"skin_crimson","qty":1,"level":1}]}
```

### `POST /v1/purchases`: buy an offer

Bearer. Body `{"sku", "key"}`, the key the same for every retry of this purchase.

```http
POST /v1/purchases
Authorization: Bearer 1eOf0B5d...
{"sku": "skin_crimson", "key": "cc7adaf3-712f-4845-8aa9-70185189e8bc"}
```
```json
200 {"result":"bought","itemId":"skin_crimson","coins":0,"held":1,"gems":850}
```

The same request again: nothing is taken, the first result is told.

```json
200 {"result":"already_bought","itemId":"skin_crimson","coins":0,"held":1,"gems":850}
```

`coins` and `gems` are the balances after; `held` how many of the item the player has now. A
coin offer, from a player who had coins (given in the test database, as no route gives them):

```json
200 {"result":"bought","itemId":"treads_light","coins":3800,"held":1,"gems":0}
```

| Status | code | When |
|---|---|---|
| 400 | `invalid_body` | `sku and key are required` |
| 400 | `invalid_key` | not 16 to 48 of `a-z 0-9 -` |
| 404 | `unknown_sku` | `no such offer` |
| 409 | `not_available` | not on sale now |
| 403 | `level_required` | `reach level <N>` |
| 409 | `insufficient_funds` | `not enough of the offer's currency, coins or gems` |

### `POST /v1/inventory/{itemId}/level`: raise an item a level

Bearer. Body `{"key"}`. Equipment only, levels 1 to 5, paid in coins: 500, 1000, 2000, 4000. A
level raises the item's bonus.

```http
POST /v1/inventory/treads_light/level
{"key": "7d2b9c4e-1a3f-4b8e-9c0d-2e4f6a8b0c13"}
```
```json
200 {"itemId":"treads_light","level":2,"coins":3300}
```

| Status | code | When |
|---|---|---|
| 400 | `invalid_key` | as for a purchase |
| 400 | `not_equipment` | `only an item that is worn has levels` |
| 404 | `not_held` | `{"code":"not_held","message":"the player holds none"}` |
| 409 | `max_level` | `level 5 is the top` |
| 409 | `insufficient_funds` | `not enough coins for the next level` |

### `GET /v1/equipment`, `PUT /v1/equipment/{slot}`, `DELETE /v1/equipment/{slot}`

Bearer. What is worn: one item a slot. `PUT` body `{"itemId"}`; `DELETE` empties the slot.
Every answer is the whole loadout: the five slots, and the stat bonuses they give (percent,
each stat at most 25), which go into the next match's ticket.

```http
PUT /v1/equipment/skin
{"itemId": "skin_crimson"}
```
```json
200 {"slots":{"barrel":null,"armor":null,"core":null,"treads":null,"skin":"skin_crimson"},"bonus":{}}
```

With level-2 treads worn:

```json
200 {"slots":{"barrel":null,"armor":null,"core":null,"treads":"treads_light","skin":null},"bonus":{"movement_speed":10}}
```

| Status | code | When |
|---|---|---|
| 400 | `invalid_body` | `itemId is required` |
| 400 | `invalid_slot` | `one of barrel, armor, core, treads, skin` |
| 404 | `unknown_item` | `no such item` |
| 409 | `wrong_slot` | worn in another slot, or a boost |
| 409 | `not_owned` | `{"code":"not_owned","message":"buy it first"}` |

### `GET /v1/boosts`, `POST /v1/boosts`

Bearer. The boosts running now; `POST {"itemId", "key"}` uses one held boost item: an hour
from now, or an hour more if the same boost is running.

```http
POST /v1/boosts
{"itemId": "boost_xp_hour", "key": "22e29d29-034d-4fdd-885c-c3f6e0d289d2"}
```
```json
200 {"result":"activated","boosts":[{"kind":"xp","itemId":"boost_xp_hour","percent":100,"endsAt":"2026-10-05T23:29:55.706Z"}]}
```

`GET` answers `{"boosts":[...]}` (no `result`). The same key again is `already_activated`.
Errors: 400 `invalid_body`, `invalid_key`; 404 `unknown_item` (`no such boost`); 409
`not_owned`; 409 `other_running` (another boost of that kind runs).

### Payments: gems for money (simulated)

Runs only when `platform` is started with `BACKEND_PAYMENT_PROVIDER=simulated`; otherwise every
`/v1/payments` route answers **503** `payments_off`. No real provider is integrated yet (Q-52):
an order is placed, and the simulated provider's verdict is given by a call.

**`GET /v1/payments/packs`**: public.

```json
200 {"packs":[{"productId":"gems_80","gems":80,"priceCents":99,"currency":"USD"},
 {"productId":"gems_500","gems":500,"priceCents":499,"currency":"USD"},
 {"productId":"gems_1100","gems":1100,"priceCents":999,"currency":"USD"},
 {"productId":"gems_2400","gems":2400,"priceCents":1999,"currency":"USD"},
 {"productId":"gems_6500","gems":6500,"priceCents":4999,"currency":"USD"}]}
```

**`POST /v1/payments`**: Bearer. Body `{"productId", "key"}` (key 16 to 36 characters).

```http
POST /v1/payments
{"productId": "gems_500", "key": "417a7aa4-35b1-4bf1-a86b-e882322d65a6"}
```
```json
200 {"order":{"orderId":"2a0a6b2f-5ad7-47d5-8077-a72b03621e1c","productId":"gems_500","gems":500,"bonus":0,
 "priceCents":499,"currency":"USD","state":"pending","createdAt":"2026-10-05T22:29:54.500Z"}}
```

**`GET /v1/payments/{orderId}`**: Bearer. The order, the player's own only (else 404
`no_such_order`).

**`POST /v1/payments/{orderId}/simulate`**: Bearer. Body `{"outcome": "paid" | "declined"}`:
what a provider would report.

```http
POST /v1/payments/2a0a6b2f-5ad7-47d5-8077-a72b03621e1c/simulate
{"outcome": "paid"}
```
```json
200 {"order":{"orderId":"2a0a6b2f-...","productId":"gems_500","gems":500,"bonus":500,"priceCents":499,
 "currency":"USD","state":"paid","createdAt":"2026-10-05T22:29:54.501Z"},"confirmed":true,"gems":1000}
```

A player's first paid order is doubled (`bonus`). `confirmed` is false when the order was no
longer pending, and then nothing changes:

```json
200 {"order":{..., "state":"paid"},"confirmed":false,"gems":1000}
```

| Status | code | When |
|---|---|---|
| 503 | `payments_off` | no provider named |
| 400 | `invalid_body`, `invalid_key`, `invalid_outcome` | as named |
| 404 | `unknown_product`, `no_such_order` | |
| 409 | `refund_debt` | a refunded order's gems were spent; support clears the debt first ([03](03-admin-api.md)) |

### The season pass: `GET /v1/pass`, `POST /v1/pass/premium`

Bearer. The season's 40 tiers, 250 points each (points from match results and daily goals),
each with a free and a premium reward; the player's points, tier and whether premium.

```json
200 {"season":1,"endsAt":"2026-11-01T00:00:00Z","points":0,"tier":0,"premium":false,"premiumGems":500,"tierPoints":250,
 "tiers":[{"tier":1,"free":{"coins":150,"gems":0,"itemId":null},"premium":{"coins":0,"gems":15,"itemId":null}},
          ...
          {"tier":5,"free":{"coins":0,"gems":5,"itemId":null},"premium":{"coins":0,"gems":15,"itemId":"boost_xp_hour"}},
          ... 40 tiers]}
```

`POST /v1/pass/premium` (no body) buys premium for 500 gems and pays at once the premium
tiers already reached:

```json
200 {"result":"bought","gems":330,"pass":{"season":1, ..., "premium":true, ...}}
```

Again in the same season: `already_bought`. Errors: 409 `insufficient_funds`
(`premium is 500 gems`), 409 `season_ended`.

## 5. Progress, seasons and boards

### `GET /v1/achievements`

Bearer. All 17, with the player's progress (play time in seconds); `gems` is the reward, paid
by the worker when a result reaches the threshold.

```json
200 {"achievements":[{"id":"kills_100","stat":"kills","threshold":100,"gems":10,"progress":0,"reached":false},
 {"id":"kills_1000","stat":"kills","threshold":1000,"gems":20,"progress":0,"reached":false}, ...]}
```

`stat` is one of `kills`, `wins`, `matches`, `assists`, `bestScore`, `playtime`.

### `GET /v1/goals`

Bearer. Today's three goals (UTC day), each paying coins; all three done pay `setGems` more.

```json
200 {"day":"2026-10-05","resetsAt":"2026-10-06T00:00:00Z",
 "goals":[{"id":"assists_5","kind":"assists","target":5,"coins":100,"progress":0,"done":false},
          {"id":"kills_10","kind":"kills","target":10,"coins":100,"progress":0,"done":false},
          {"id":"score_15000","kind":"score","target":15000,"coins":200,"progress":0,"done":false}],
 "setGems":3,"setDone":false}
```

### `GET /v1/seasons`

Public. The season under way, and the past ones (newest first, at most 12). Rating boards are
kept per season.

```json
200 {"current":{"id":1,"startsAt":"2026-10-05T22:29:40.672Z","endsAt":"2026-11-01T00:00:00Z"},"past":[]}
```

### `GET /v1/leaderboards/{board}` and `GET /v1/leaderboards/{board}/me`

| `board` | Measures | Kept |
|---|---|---|
| `alltime`, `daily`, `weekly` | the best score in one match | ever; today (UTC); this ISO week |
| `duel`, `rffa`, `tvt` | the rating in that mode; a player is listed after 10 rated matches in it | per season |
| `teams` | the team's rating; listed after 10 rated team matches | per season |

The top: public, `?limit=` 1 to 100 (default 50); the rating boards and `teams` take
`?season=<id>` for a past season (default: the current one).

```http
GET /v1/leaderboards/alltime?limit=10
```
```json
200 {"board":"alltime","entries":[{"rank":1,"playerId":42,"name":"Ada","score":5120}, ...]}
```

`teams` lists `{"rank","teamId","name","score"}`.

`/me`: Bearer. The player's place and the rows around it, five each side.

```json
200 {"board":"daily","rank":17,"score":4100,"entries":[ ...up to 11 rows... ]}
```

These two show the shape only: the run's stack had played no match, so its boards answered
`{"board":"alltime","entries":[]}` and `/me` the 404 below.

| Status | code | When |
|---|---|---|
| 404 | `unknown_board` | `{"code":"unknown_board","message":"no board called monthly"}` |
| 404 | `not_ranked` | not on that board yet: `{"code":"not_ranked","message":"no score on this board yet"}` |
| 404 | `no_such_season` | an unknown season, or `season` given for a score board |
| 404 | `not_in_team` | `teams/me` in no team |
| 400 | `invalid_season` | `season` not a number |

## 6. Matchmaking: from a request to the arena

Every way into a match ends in a **grant**: the arena's address, a **ticket** for one join,
valid 60 seconds, and whether to use TLS. The client then opens TCP (TLS if `tls`) to
`arenaHost:arenaPort` and sends `Join` with the ticket: the arena's own binary protocol,
[02-networking](../detailed-design/02-networking.md) §3.

The modes: `ffa` (the public arena, by a match request), `duel`, `rffa` (ranked free-for-all),
`maze` (1 a side); `tvt`, `coop`, `domination`, `tag` (3 a side, a party of up to 3 queues
together); `teams` (a party of 3 from one team); `sandbox` (a private room, by its own route).

**The matcher matches only players connected to the lobby WebSocket.** Each round, about a
second, it drops anyone queued without a connection (their state becomes `none`). It asks each
player of a match it finds to confirm within 10 seconds (pushed `evt.match.ready`); once all
accept, it pushes each their grant (`evt.match.found`). So a queue is joined and the match
accepted over the lobby ([02](02-lobby-websocket.md) §5 shows a whole duel); the HTTP routes
below give the same answers, and `GET /v1/queue` the grant to a client that missed the push.

### `POST /v1/match-requests`: a seat in the public arena

Bearer, no body. The live arena with the most free places, less those promised to players sent
there in the last minute and not yet counted by it (D-79).

```json
200 {"arenaHost":"127.0.0.1","arenaPort":9021,"ticketId":"1bvMvhOfHVcHga1gZV0yuA","tls":false}
```

503 `no_arena` when no arena has room: each ticket given holds its place for up to a minute,
until its player is counted by the arena or the ticket lapses.

### `POST /v1/queue`: join a queue

Bearer. Body `{"mode"}`. A party queues whole, by its leader.

```http
POST /v1/queue
{"mode": "duel"}
```
```json
200 {"state":"queued","mode":"duel","waitedSeconds":0}
```

| Status | code | When |
|---|---|---|
| 400 | `unknown_mode` | `{"code":"unknown_mode","message":"no queue for chess"}`; `ffa` and `sandbox` too |
| 409 | `already_queued` | `{"code":"already_queued","message":"already in a queue"}` |
| 409 | `in_match` | a grant or a tournament match is waiting: `GET /v1/queue` |
| 409 | `in_party` | a member is not the party's leader |
| 400 | `party_too_big`, `party_too_small` | the party does not fit the mode |
| 409 | `not_one_team`; 403 `not_allowed` | `teams`: the party is not of one team, or the caller is a plain member |
| 409 | `queue_locked` | a match was declined less than a minute ago |
| 409 | `party_changed` | the party changed meanwhile: try again |

### `GET /v1/queue`: where the player stands

Bearer. `state` is `none`, `queued`, `confirming` (asked about `matchUid`, `secondsLeft` to
answer) or `matched` (with the `grant`). Fields that do not apply are left out.

```json
200 {"state":"none"}
200 {"state":"queued","mode":"duel","waitedSeconds":0}
200 {"state":"confirming","mode":"duel","waitedSeconds":0,"matchUid":"01M4730R905V76D3SE5WW3Z89K","secondsLeft":10}
200 {"state":"matched","mode":"sandbox","waitedSeconds":0,
     "grant":{"arenaHost":"127.0.0.1","arenaPort":9021,"ticketId":"kLHvS2RTU-iGqyIn82E8wg","tls":false,"mode":"sandbox"}}
```

(The `confirming` line is from the lobby run, [02](02-lobby-websocket.md) §5.)

### `POST /v1/queue/accept`, `POST /v1/queue/decline`

Bearer. Body `{"matchUid"}`, the match asked about. Answers the player's state as `GET` does;
a decline (or silence for 10 s) calls the match off for all and locks the decliner out of the
queues for a minute.

```http
POST /v1/queue/accept
{"matchUid": "01M4730R905V76D3SE5WW3Z89K"}
```
```json
200 {"state":"confirming","mode":"duel","waitedSeconds":0,"matchUid":"01M4730R905V76D3SE5WW3Z89K","secondsLeft":10}
```

409 `not_confirming` (`not asked about that match, or no longer`); 400 `invalid_body` without
`matchUid`.

### `DELETE /v1/queue`: leave

Bearer. Leaves the queue (a party's leader takes the party out), drops a grant not used, and
gives back a sandbox not joined. Always `200 {"state":"none"}`.

### `POST /v1/sandbox`: a private room

Bearer, no body. A room of one's own (or the party's, by its leader) for 20 minutes; the
caller's grant, also pushed to each member.

```json
200 {"arenaHost":"127.0.0.1","arenaPort":9021,"ticketId":"kLHvS2RTU-iGqyIn82E8wg","tls":false,"mode":"sandbox"}
```

409 `in_sandbox` (one at a time), `already_queued`, `in_match`, `in_party`; 503 `no_room`.

## 7. The party

Up to three players who queue together. The leader's first invitation makes the party; an
invitation lasts 60 seconds. Every answer is the caller's party as it now is:

```json
{"partyId":"01M47307VY81WJQR5VH8XBVJ98","leader":1,"members":[{"playerId":1,"name":"Ada Lovelace"},{"playerId":2,"name":"Bob"}],"version":2}
```

In no party: `{"partyId":null,"leader":0,"members":[]}`. A change that took the caller out (a
leave, a kick that ended it) answers the ended form, with the party it was:
`{"partyId":null,"leader":0,"members":[],"was":"01M47307VY81WJQR5VH8XBVJ98","version":3}`.
`version` grows with each change; members are told each change as `evt.party.update` on the
lobby, and the newest version wins.

| Route | Body | Example |
|---|---|---|
| `GET /v1/party` | | `{"partyId":null,"leader":0,"members":[]}` |
| `POST /v1/party/invite` | `{"playerId": 2}` | `{"partyId":"01M47307VY81WJQR5VH8XBVJ98","leader":1,"members":[{"playerId":1,"name":"Ada Lovelace"}],"version":1}`; the invitee is pushed `evt.party.invite` |
| `POST /v1/party/accept` | `{"partyId": "01M47307VY81WJQR5VH8XBVJ98"}` | the party with both, `version` 2 |
| `POST /v1/party/say` | `{"phraseId": 1}` | the party; every member is pushed `evt.party.said` |
| `POST /v1/party/kick` | `{"playerId": 2}` | the party after, or the ended form when one is left |
| `POST /v1/party/leave` | | the ended form, or the no-party form; always 200 |

All Bearer. Errors:

| Status | code | When |
|---|---|---|
| 400 | `invalid_body` | the field the action needs is missing |
| 400 | `self` | inviting oneself |
| 409 | `in_party`, `not_leader`, `party_full` | the invitee is in a party; only the leader invites; three at most |
| 404 | `not_in_lobby` | the invitee is a friend who is not connected |
| 404 | `no_invitation` | `no invitation to that party, or it has lapsed` |
| 409 | `queued` | the party is queued: it takes no one in until it is out |
| 404 | `not_member`, `no_party` | |
| 400 | `unknown_phrase` | not in `GET /v1/content/phrases` |
| 429 | `too_soon` | `{"code":"too_soon","message":"one phrase every two seconds"}`; 60 invitations an hour |

## 8. Friends, blocks and the inbox

All Bearer. A friendship is asked, and made when the other asks back; a request lasts 7 days.
At most 100 friends, 100 blocked, 50 requests out. Answers without data are `200 {}`.

### `POST /v1/friends`: ask, or accept by asking back

Body `{"playerId"}`.

```http
POST /v1/friends
Authorization: Bearer <A's token>
{"playerId": 2}
```
```json
200 {"state":"asked"}
```

B asking A back makes them friends: `200 {"state":"friends"}`. The one asked is pushed
`evt.friend.request` (then `evt.friend.accepted`) and `evt.inbox`. A request to someone who
has blocked the caller answers the same `asked` and is never shown to them.

### `GET /v1/friends`

Friends (with whether they are connected to the lobby now), the requests to the player, and
the player's own requests out.

```json
200 {"friends":[],"requests":[{"playerId":1,"name":"Ada Lovelace","expiresAt":"2026-10-12T22:29:57.845Z"}],"asked":[]}
200 {"friends":[{"playerId":2,"name":"Bob","online":false}],"requests":[],"asked":[]}
```

### `DELETE /v1/friends/{playerId}`, `DELETE /v1/friend-requests/{playerId}`

End a friendship; decline a request (or withdraw one's own). `200 {}`; again: 404
`not_friends`, 404 `no_request` (`{"code":"no_request","message":"no request between you"}`).

### `GET /v1/blocks`, `POST /v1/blocks`, `DELETE /v1/blocks/{playerId}`

Block (body `{"playerId"}`): ends a friendship and any request between the two; the blocked
player's requests, team invitations and applications to the blocker are dropped unseen.

```json
200 {"blocked":[{"playerId":1,"name":"Ada Lovelace"}]}
```

Blocking twice is 200; unblocking one not blocked is 404 `not_blocked`.

Friend and block errors:

| Status | code | When |
|---|---|---|
| 400 | `invalid_body` | `playerId is required` |
| 400 | `yourself` | `{"code":"yourself","message":"not yourself"}` |
| 404 | `no_such_player` | `no such player` |
| 409 | `already_friends`, `already_asked` | `{"code":"already_asked","message":"asked already"}` |
| 409 | `you_blocked` | unblock them first |
| 409 | `friends_full`, `blocks_full`, `too_many_asked` | the limits above |
| 429 | `too_soon` | `twenty requests an hour at most` |

### `GET /v1/inbox`, `POST /v1/inbox/read`

The newest 50 items (kept 30 days): what happened while the player looked elsewhere. `ref` is
what the item is about.

```json
200 {"items":[{"id":1,"kind":"friend_request","ref":1,"at":"2026-10-05T22:29:57.845Z","read":false}]}
```

| `kind` | `ref` |
|---|---|
| `friend_request`, `friend_accepted` | the other player |
| `team_invite` | the team |
| `team_application` | the applicant |
| `tournament_prize` | the tournament |
| `season_reward` | the season × 10 + the board (1 duel, 2 tvt, 3 rffa, 5 teams) |

`POST /v1/inbox/read {"upTo": 1}` marks read every item up to that id: `200 {}`
(400 `invalid_body` without `upTo`).

## 9. Teams

All Bearer. A team has a leader, up to two vice leaders and members, 30 at most. A team's
name follows the display-name rules and is unique however it is cased or accented. **Leaving,
being removed or disbanding starts a day's cooldown** before joining or making another.

The team, as its members see it:

```json
{"id":1,"name":"Tmuvtoo4q","members":[{"playerId":1,"name":"Ada Lovelace","role":"leader"},
 {"playerId":2,"name":"Bob","role":"vice_leader"},{"playerId":3,"name":"cy_muvtoo4q","role":"member"}],
 "rating":1200,"wins":0,"losses":0,"draws":0}
```

As anyone sees it (`members` is then a count): `{"id":1,"name":"Tmuvtoo4q","members":2,"rating":1200}`.

| Route | Who | Body | Answer |
|---|---|---|---|
| `POST /v1/teams` | anyone in no team | `{"name": "Tmuvtoo4q"}` | the team, the caller its leader |
| `GET /v1/teams?name=Tmuv` | anyone | | `{"teams":[{"id":1,"name":"Tmuvtoo4q","members":2,"rating":1200}]}`, by name prefix, 20 at most |
| `GET /v1/teams/{teamId}` | anyone | | the public view |
| `GET /v1/teams/mine` | a member | | the team; 404 `no_team` in none |
| `POST /v1/teams/mine/invites` | leader, vice | `{"playerId": 2}` | the team; the invitee gets an inbox item |
| `GET /v1/team-invites` | anyone | | `{"invites":[{"teamId":1,"teamName":"Tmuvtoo4q","expiresAt":"2026-10-12T22:29:59.505Z"}]}` |
| `POST /v1/team-invites/{teamId}` | the invited | `{"accept": true}` | the team joined; with `false`, the invitations left |
| `POST /v1/teams/{teamId}/applications` | anyone in no team | | `{}`; the leader and vices get an inbox item |
| `DELETE /v1/teams/{teamId}/applications` | the applicant | | `{}`: withdrawn |
| `GET /v1/team-applications` | anyone | | the caller's: `{"applications":[{"teamId":1,"teamName":"Tmuvtoo4q","expiresAt":"2026-10-12T22:29:59.954Z"}]}` |
| `GET /v1/teams/mine/applications` | leader, vice | | `{"applications":[{"playerId":3,"name":"cy_muvtoo4q","expiresAt":"2026-10-12T22:29:59.954Z"}]}` |
| `POST /v1/teams/mine/applications/{playerId}` | leader, vice | `{"accept": true}` | the team, the applicant in it (or still out, declined) |
| `POST /v1/teams/mine/members/{playerId}/role` | leader | `{"role": "vice_leader"}` (or `member`) | the team |
| `PUT /v1/teams/mine/name` | leader | `{"name": "Rmuvtoo4q"}` | the team; once in 30 days |
| `POST /v1/teams/mine/leader` | leader | `{"playerId": 2}` | the team: the target leads, the old leader is a member |
| `DELETE /v1/teams/mine/members/{playerId}` | leader; vice for members | | the team without them |
| `POST /v1/teams/mine/leave` | a member | | `{}`; a leader alone deletes the team |
| `DELETE /v1/teams/mine` | leader | | `{}`: disbanded, every member cooling down |

Invitations and applications last 7 days; a player may have 5 applications out, a team 20
invitations. Members are pushed `evt.team.update` with the team (or `{"team":null}` to one who
left) when someone joins or leaves, a role or the leader changes, or the team is renamed or
disbanded; an invitation or an application is told by an inbox item instead.

```http
POST /v1/teams/mine/leave
Authorization: Bearer <A's token, A leading Bob and Cy>
```
```json
409 {"code":"leader_with_members","message":"hand the team over first"}
```

| Status | code | message |
|---|---|---|
| 400 | `invalid_name` | `a team is named as a player is` (any rule broken; also a search with no `name`) |
| 400 | `invalid_body` | `playerId is required`, `accept is required: true or false`, `role is required: vice_leader or member` |
| 400 | `invalid_role` | `vice_leader or member` |
| 403 | `not_allowed` | `not for this role` |
| 404 | `no_team`, `no_such_team`, `no_such_player`, `not_a_member`, `no_invite`, `no_application` | |
| 409 | `in_team` | `one team a player` |
| 409 | `cooling_down` | `a day after leaving a team before another` |
| 409 | `name_taken` | `however it is cased or accented` |
| 409 | `team_full`, `too_many_vices`, `too_many_invited`, `too_many_applied`, `already` | the limits above |
| 409 | `leader_with_members` | `hand the team over first` |
| 429 | `too_soon` | 20 invitations or applications an hour; a rename within 30 days |

## 10. Tournaments

Made by an operator ([03](03-admin-api.md)); duels or teams, by elimination or round robin.
The worker seeds a tournament when registration ends, runs its rounds and pays its prizes.

### `GET /v1/tournaments`, `GET /v1/tournaments/{id}`

Public. The open ones (registration, seeded, running); one by its id in any state, with its
matches.

```json
200 {"id":1,"name":"Weekly Cup","mode":"duel","format":"elimination","state":"registration","maxEntries":8,
 "registrationEnds":"2026-10-06T00:30:04Z","startsAt":"2026-10-06T00:35:04Z","roundMinutes":10,"currentRound":0,
 "prizes":[3000,1500,500],"entries":[{"playerId":2,"name":"Bob","seed":0}],"matches":[]}
```

`state`: `registration`, `seeded`, `running`, `finished`, `cancelled`. A match:
`{"round":1,"slot":0,"state":"ready","playerA":7,"playerB":8,"winner":7}` (`teamA`, `teamB`,
`winnerTeam` in a teams tournament; a key absent while unknown). A round robin adds
`standings`.

### `POST /v1/tournaments/{id}/entries`, `DELETE /v1/tournaments/{id}/entries`

Bearer, no body. Register or withdraw while registration is open. A player needs **10 rated
matches** in the mode; a team enters as a party of three of one team, registered by its leader
or a vice leader. Answers the tournament.

The run's new player:

```json
409 {"code":"too_few_rated","message":"ten rated matches in its mode enter a tournament"}
```

A player with 10 rated duels (given in the test database): `200` the tournament with them in
`entries`, as above. Again: `409 {"code":"already","message":"registered already"}`.
Withdrawing: `200` the tournament without them; not registered:
`409 {"code":"not_registered","message":"not registered, or registration is over"}`.

Other errors: 404 `no_such_tournament`; 409 `closed` (`registration is over`), `full`;
teams: 409 `in_party`, 400 `party_too_small`, 409 `not_one_team`, 403 `not_allowed`.

### `GET /v1/tournaments/{id}/match`

Bearer. The player's grant for a round's match, while it waits (60 seconds); also pushed as
`evt.tournament.match`.

```json
200 {"tournamentId":1,"round":1,"arenaHost":"a.example.com","arenaPort":9001,"ticketId":"<22 characters>","tls":true,"mode":"duel"}
404 {"code":"no_match","message":"no match waiting for you in it"}
```

(The 200 is the shape the worker writes; the run had no round under way.)
