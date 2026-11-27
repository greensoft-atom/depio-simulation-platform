# The player API (HTTP)

This page is the reference for the HTTP API that the app calls. It is for client developers and the UI team. The
process `platform` serves it under `/v1/`, over HTTPS, or over plain HTTP on port 80
([D-115](../architecture/03-decision-log.md#d-115--a-players-build-may-connect-without-tls-and-the-site-serves-it-on-80-matches-included)).
It covers accounts and sessions, content, the economy, progress and boards, matchmaking, the party, the social layer,
teams and tournaments. The lobby WebSocket is on page [02](02-lobby-websocket.md), and the operators' API on page
[03](03-admin-api.md). New words are explained in the [glossary](../glossary.md).

Every example below is a real request and its real answer, from the Postman collection's run of 2026-10-06 against a
stack built from this repository ([README, "How this was checked"](README.md#how-this-was-checked)). An example that
comes from somewhere else says so. In the examples, `1eOf0B5d...` stands for a whole token.

## Every route at a glance

"Bearer" means the route needs a session token (§0, Requests). "Public" means it needs none.

| Method and path | Auth | What it does | Section |
|---|---|---|---|
| `GET /v1/health` | public | says whether the API answers | §1 |
| `POST /v1/sessions` | public | logs in, with the account service's access token or a guest key | §2 |
| `DELETE /v1/sessions` | Bearer | logs out | §2 |
| `POST /v1/guests` | public | makes a guest account | §2 |
| `POST /v1/accounts/link` | Bearer | makes a guest an account's player | §2 |
| `PUT /v1/accounts/name` | Bearer | changes the display name | §2 |
| `DELETE /v1/accounts` | Bearer | deletes a guest | §2 |
| `GET /v1/content/classes` | public | the tank classes | §3 |
| `GET /v1/content/phrases` | public | the quick-chat phrases | §3 |
| `GET /v1/content/skins` | public | which item draws which skin | §3 |
| `GET /v1/shop` | public | the offers on sale | §4 |
| `GET /v1/inventory` | Bearer | balances and items held | §4 |
| `POST /v1/purchases` | Bearer | buys an offer | §4 |
| `POST /v1/inventory/{itemId}/level` | Bearer | raises an item one level | §4 |
| `GET /v1/equipment` | Bearer | what is worn | §4 |
| `PUT /v1/equipment/{slot}` | Bearer | wears an item in a slot | §4 |
| `DELETE /v1/equipment/{slot}` | Bearer | empties a slot | §4 |
| `GET /v1/boosts` | Bearer | the boosts running | §4 |
| `POST /v1/boosts` | Bearer | starts a boost | §4 |
| `GET /v1/payments/packs` | public | the gem packs on sale | §4 |
| `POST /v1/payments` | Bearer | places an order for gems | §4 |
| `GET /v1/payments/{orderId}` | Bearer | reads an order | §4 |
| `POST /v1/payments/{orderId}/simulate` | Bearer | gives the simulated provider's verdict | §4 |
| `GET /v1/pass` | Bearer | the season pass | §4 |
| `POST /v1/pass/premium` | Bearer | buys the pass's premium track | §4 |
| `GET /v1/achievements` | Bearer | every achievement, with progress | §5 |
| `GET /v1/goals` | Bearer | today's three goals | §5 |
| `GET /v1/seasons` | public | the season under way, and past ones | §5 |
| `GET /v1/leaderboards/{board}` | public | the top of a board | §5 |
| `GET /v1/leaderboards/{board}/me` | Bearer | the player's place on a board | §5 |
| `POST /v1/match-requests` | Bearer | a seat in the public arena | §6 |
| `POST /v1/queue` | Bearer | joins a queue | §6 |
| `GET /v1/queue` | Bearer | where the player stands in the queue | §6 |
| `POST /v1/queue/accept` | Bearer | accepts a match found | §6 |
| `POST /v1/queue/decline` | Bearer | declines a match found | §6 |
| `DELETE /v1/queue` | Bearer | leaves the queue | §6 |
| `POST /v1/sandbox` | Bearer | opens a private room | §6 |
| `GET /v1/party` | Bearer | the player's party | §7 |
| `POST /v1/party/invite` | Bearer | invites a player | §7 |
| `POST /v1/party/accept` | Bearer | accepts an invitation | §7 |
| `POST /v1/party/say` | Bearer | says a phrase to the party | §7 |
| `POST /v1/party/kick` | Bearer | removes a member | §7 |
| `POST /v1/party/leave` | Bearer | leaves the party | §7 |
| `POST /v1/friends` | Bearer | asks to be friends, or accepts | §8 |
| `GET /v1/friends` | Bearer | friends and requests | §8 |
| `DELETE /v1/friends/{playerId}` | Bearer | ends a friendship | §8 |
| `DELETE /v1/friend-requests/{playerId}` | Bearer | declines or withdraws a request | §8 |
| `GET /v1/blocks` | Bearer | the players blocked | §8 |
| `POST /v1/blocks` | Bearer | blocks a player | §8 |
| `DELETE /v1/blocks/{playerId}` | Bearer | unblocks a player | §8 |
| `GET /v1/inbox` | Bearer | the inbox | §8 |
| `POST /v1/inbox/read` | Bearer | marks inbox items read | §8 |
| `POST /v1/teams` | Bearer | makes a team | §9 |
| `GET /v1/teams?name=<prefix>` | Bearer | searches teams by name | §9 |
| `GET /v1/teams/{teamId}` | Bearer | one team, as anyone sees it | §9 |
| `GET /v1/teams/mine` | Bearer | the player's own team | §9 |
| `DELETE /v1/teams/mine` | Bearer | disbands the team | §9 |
| `POST /v1/teams/mine/invites` | Bearer | invites a player to the team | §9 |
| `GET /v1/team-invites` | Bearer | the player's team invitations | §9 |
| `POST /v1/team-invites/{teamId}` | Bearer | answers an invitation | §9 |
| `POST /v1/teams/{teamId}/applications` | Bearer | applies to a team | §9 |
| `DELETE /v1/teams/{teamId}/applications` | Bearer | withdraws an application | §9 |
| `GET /v1/team-applications` | Bearer | the player's own applications | §9 |
| `GET /v1/teams/mine/applications` | Bearer | the applications to the player's team | §9 |
| `POST /v1/teams/mine/applications/{playerId}` | Bearer | answers an application | §9 |
| `POST /v1/teams/mine/members/{playerId}/role` | Bearer | changes a member's role | §9 |
| `PUT /v1/teams/mine/name` | Bearer | renames the team | §9 |
| `POST /v1/teams/mine/leader` | Bearer | hands the team to another member | §9 |
| `DELETE /v1/teams/mine/members/{playerId}` | Bearer | removes a member | §9 |
| `POST /v1/teams/mine/leave` | Bearer | leaves the team | §9 |
| `GET /v1/tournaments` | public | the open tournaments | §10 |
| `GET /v1/tournaments/{id}` | public | one tournament, with its matches | §10 |
| `POST /v1/tournaments/{id}/entries` | Bearer | registers for a tournament | §10 |
| `DELETE /v1/tournaments/{id}/entries` | Bearer | withdraws from it | §10 |
| `GET /v1/tournaments/{id}/match` | Bearer | the grant for the player's tournament match | §10 |

## 0. Basics

### Where

| Where | Base URL |
|---|---|
| Production, through the front's nginx, with TLS | `https://<the site's address>/v1/...`, for example `https://203.0.113.10/v1/shop`. The certificate comes from the backend's own certificate authority (CA), so a client trusts that CA's root certificate, `ca.pem` (`curl --cacert ca.pem`; [operations/06](../operations/06-certificates.md)) |
| Production without TLS, for a client without TLS ([D-115](../architecture/03-decision-log.md#d-115--a-players-build-may-connect-without-tls-and-the-site-serves-it-on-80-matches-included)) | `http://<the site's address>/v1/...`: the same routes, on port 80. Anyone on the way can read what crosses port 80, passwords and tokens included |
| One machine, straight to `platform` (tests, and the examples below) | `http://127.0.0.1:8080/v1/...` (the port is `PLATFORM_PORT`; the examples ran on 8180) |
| A run by hand, through `dev-nginx.sh`'s nginx ([D-92](../architecture/03-decision-log.md#d-92--a-script-for-each-service-of-a-run-by-hand-and-nginx-on-the-sites-ports-in-front-of-it)) | `https://<address>/v1/...`, trusting `work/nginx/ca/ca.pem` (`curl --cacert work/nginx/ca/ca.pem`; [development/03 §5.0](../development/03-build-and-run.md#50-clients-on-other-machines-the-front)); or `http://<address>/v1/...` without TLS, on port 80 ([D-107](../architecture/03-decision-log.md#d-107--developments-80-serves-the-api-and-the-lobby-itself-the-apis-health-under-v1-every-route-checked-through-nginx)) |

**What nginx passes.** nginx passes `/v1/...` to `platform` and `/lobby` to `gateway`. It also passes
`/match/<name>` to an arena, for a match without TLS (§6). Any other path gets nginx's own 404, which is not JSON. So
the API's health check is `/v1/health`, and the platform's own `/health` cannot be reached from outside (D-107).
`/v1/account-events` is not passed either: it is the account service's, which posts its events to the platform on
the private network (D-117). The account service's own API is under `/account/v1/`
([10 §3.2](../detailed-design/10-accounts.md#32-the-routes)).

**What nginx limits.**

- **The four paths that open a session or an account**: `/v1/sessions`, `/v1/accounts`, `/v1/guests` and
  `/v1/accounts/link`. The limit covers every method on these paths, so a logout (`DELETE /v1/sessions`) and an
  account deletion (`DELETE /v1/accounts`) count too. One address may send 2 requests a second to them, with a burst
  of 30. Over that, nginx answers its own 429, which is not JSON.
- **Body size**: 4 KB at most. A larger body gets nginx's own 413, which is not JSON.

### Requests

- **Bodies** are JSON, in UTF-8. The server does not check `Content-Type`. A body may be **4096 bytes** at most.
  Through nginx, a larger body on any route gets nginx's own 413 (not JSON). Sent straight to `platform`, a larger
  body gets **413** `body_too_large` on the routes that read a body.
- **Unknown fields are ignored.** A value of another JSON type is converted when that is possible, as Jackson (the
  server's JSON library) does by default: `"2"` and `2.7` become the number 2, `123` becomes the text `"123"`, and `1`
  becomes `true`. The server treats three kinds of body as no body at all: an empty body, a body that is not JSON, and
  a body with a value that cannot be converted (`"x"` for a number). The route then answers its own 400: `invalid_body`,
  or the code for the field that is missing (for example `invalid_key`).
- **Authentication.** Send `Authorization: Bearer <token>`, with the token that `POST /v1/sessions` gave. A route
  marked "Bearer" answers **401** `no_token` when the header is missing. It answers **401** `invalid_session` when the
  token names no live session: the session is unknown, has expired or was logged out, or its player is banned.
- **Idempotency keys.** Four actions carry a `key` that the client chooses: a purchase, a payment order, a boost and an
  item's level. Send the same key on every retry of one action. A lowercase UUID fits. A key is 16 to 48 characters
  of `a-z`, `0-9` and `-`; for a payment order, 16 to 36. When a key was used before, the server answers the first
  result again and takes nothing more.
- **Ids in paths** are 1 to 18 digits. A longer id names no route (404).

### Responses

- Answers are JSON (`Content-Type: application/json`). Field names are in camelCase.
- A field with no value is `null`. A few answers leave such a field out instead: the queue's answers (§6), the
  boosts' answers (§4), a grant's `tlsName` when there is none (§6), and the keys of a tournament match that are not
  known yet (§10).
- Times are ISO-8601, in UTC: `2026-10-12T22:29:57.845Z`. The fraction is left out when it is zero.
- A 204 has no body. A 304 has none either.
- **Errors** are `{"code": "...", "message": "..."}`. Branch on `code`. The `message` is for people: show it or log
  it, but do not branch on it.

```json
{"code":"invalid_session","message":"log in again"}
```

Any route can also answer:

| Status | code | When |
|---|---|---|
| 404 | `no_such_route` | no route has the path (see below) |
| 405 | `method_not_allowed` | the wrong method; the `Allow` header lists the right ones (`Allow: POST`) |
| 413 | `body_too_large` | a body over 4096 bytes: `{"code":"body_too_large","message":"at most 4096 bytes"}` |
| 503 | `storage_unavailable` | MySQL or the store did not answer: try again shortly |
| 500 | `internal` | a fault in the server: report it |

A path that no route has (`/v1/nothing`) is a JSON 404 `no_such_route`. So is a route's path with more after it
(`/v1/shop/x`, `/v1/sessions/x`). Under `/v1/queue/` and `/v1/party/` the code is `not_found` instead. Before D-107
these paths got the server's own HTML page, and some of them reached the route itself. On such a path, a wrong method
may get the 405 first. From the code, the answer to `/v1/nothing`:

```json
404 {"code":"no_such_route","message":"no route at /v1/nothing: docs/api/01-http-api.md"}
```

### Limits

| What is counted | Limit | The answer over the limit |
|---|---|---|
| Guests made, and guests' logins | 30 a minute from one address, both together | 429 `too_many_attempts`, message `try again in <N> s`, header `Retry-After: <N>` |
| Friend requests; team invitations; team applications | 20 an hour of each, per player | 429 `too_soon`, without `Retry-After` |
| Party invitations | 60 an hour per player | 429 `too_soon` |
| Party phrases | one every 2 seconds | 429 `too_soon` |
| A display name; a team's name | one change in 30 days (a new account's first change is allowed at once) | 429 `too_soon` |

A login by an access token is not counted here: the account service limits the sign-ups, logins and password
changes that make one, per address and per username, and answers `busy` when its password hasher is full
([10 §3.3](../detailed-design/10-accounts.md#33-passwords-and-limits)).

## 1. Health

### `GET /v1/health`, `GET /health`

No authentication. The answer only says that the process answers. It does not check the database.

- **`/v1/health`** is the API's. It goes through nginx like every `/v1/` path, so the app, Postman or a probe outside
  can call it (D-107). It answers `GET` alone (another method gets a 405), and only at that exact path
  (`/v1/health/x` gets a 404).
- **`/health`** is the process's own, for the machine's monitoring and the watch. It answers any method, and any path
  below it. nginx does not pass it.

```http
GET /v1/health
```
```json
200 {"status":"ok"}
```

## 2. Accounts and sessions

**An account is the account service's** since
[D-117](../architecture/03-decision-log.md#d-117--accounts-in-an-account-service-of-their-own-which-issues-signed-tokens-the-backend-takes-once-for-a-session).
The app signs up and logs in there, under `/account/v1/`, with a username and a password, and is given an access
token, good for 15 minutes, and a refresh token, which the app keeps
([10 §3.2](../detailed-design/10-accounts.md#32-the-routes)). It sends the access token here once, to
`POST /v1/sessions`, and uses the session it gets from then on, as before. When the session ends, it refreshes at the
account service and sends the new access token. A guest plays without an account, and may link one later.

The examples of this section's new routes are the collection's run of 2026-11-26, against a run by hand with the
account service beside it (`dev-account.sh`, [README, "How this was checked"](README.md#how-this-was-checked)); an
access token is shortened to `eyJraWQiOiJ...`.

The rule for a display name, which a rename and a first login's name follow:

| Field | Rule |
|---|---|
| `displayName` | At most 16 characters: letters (in any script, but Latin, Cyrillic and Greek letters are not mixed), digits, spaces and `_ - .`, with at least one letter or digit. Not a reserved word (`admin`, `mod`, `support`, `staff`, `system`...) |

A refused display name gets 400 `invalid_display_name`, with the reason as its message. For example:
`letters, digits, spaces, _ - and . only`, or `that name is reserved`.

### `POST /v1/sessions`: log in

**Auth**: public. **Body**: either `{"accessToken"}`, the account service's, or `{"guestKey"}` for a guest. When the
body has a `guestKey`, the server logs in by the key and ignores any access token. **Answer**: 200 with a session.

```http
POST /v1/sessions
{"accessToken": "eyJraWQiOiJ..."}
```
```json
200 {"token":"tjILLelyR1twv9sJjcmCzQ3y2WKflc83FC-LDhrhEy0","playerId":3,"expiresInSeconds":93703,"newPlayer":true}
```

| Field | Meaning |
|---|---|
| `token` | 43 characters. Send it as `Authorization: Bearer <token>`, and in the lobby's `auth` message |
| `playerId` | the player's id |
| `expiresInSeconds` | how long the session lasts: about a day, 86 400 seconds ± 10 %. It is fixed: using the session does not extend it. Log in again before it ends |
| `newPlayer` | `true` when this login made the player: the account's first login here. The player is named from the account's username by the display name's rule, or `Player` and four digits when the rule refuses it (`admin`); it can change the name at once (`PUT /v1/accounts/name`). Always `false` for a guest |

Every login makes a new session. Older sessions stay valid. An access token is good for 15 minutes: send one fresh
from the account service, not one kept.

| Status | code | When |
|---|---|---|
| 400 | `invalid_body` | `send {"accessToken"}, the account service's, or {"guestKey"}` |
| 401 | `invalid_token` | `the access token is not one this backend takes: refresh it at the account service, or log in there again`: the same answer for every cause: expired, forged, meant for another backend, or of an account since deleted |
| 401 | `invalid_credentials` | a guest key: `no guest has that key` |
| 403 | `banned` | `this account cannot play`: banned, or suspended until a time not yet passed |
| 429 | `too_many_attempts` | a guest's login: see §0, Limits |
| 503 | `keys_unavailable` | the account service's keys cannot be had, so no token can be checked: `try again shortly` |

### `DELETE /v1/sessions`: log out

**Auth**: Bearer. **Body**: none. **Answer**: **204** with no body. It ends the session of the token sent, and answers
the same whether or not that session was still live.

```http
DELETE /v1/sessions
Authorization: Bearer 1eOf0B5d4yuUqtrMjtXfpdmXlBEsmwTcxQviWTazrZM
```
```
204
```

The same token afterwards: `401 {"code":"invalid_session","message":"log in again"}`.

### `POST /v1/guests`: a guest account

**Auth**: public. **Body**: none. **Answer**: 201 with the guest's id, its key and its display name.

A guest account has no password. It can play at once, and it logs in by its key. Its display name is `Guest` and four
digits.

```http
POST /v1/guests
```
```json
201 {"playerId":5,"guestKey":"ufLTWOrWaP5xzCWrL0GEMk420bBcuUsbMUwLiijmQUc","displayName":"Guest8223"}
```

Then `POST /v1/sessions {"guestKey": "ufLTWOrW..."}` gives a token, as above. Keep the key on the device: the key is
the account, and a lost key is a lost account. A guest nobody plays for 180 days is erased, with its progress
([D-87](../architecture/03-decision-log.md#d-87--a-guest-nobody-plays-for-180-days-is-erased-by-retention-a-deletion-is-at-once)).
Linking the guest to an account keeps it.

The only error of its own is 429 `too_many_attempts` (§0, Limits).

### `POST /v1/accounts/link`: a guest becomes an account's player

**Auth**: Bearer, the guest's session. **Body**: `{"accessToken"}`, of the account the player signed up for or logged
in to at the account service. **Answer**: 200 `{"playerId"}`.

It is the same player, with its progress and its display name kept, and from now on it is the account's. The guest key
stops working: the player logs in by the account.

```http
POST /v1/accounts/link
Authorization: Bearer ow_z04JdPf6_qF6YrtyrYoVV2MEwp5jq9Ext6WUa3fE
{"accessToken": "eyJraWQiOiJ..."}
```
```json
200 {"playerId":6}
```

| Status | code | When |
|---|---|---|
| 400 | `invalid_body` | `send {"accessToken"}, the account service's` |
| 401 | `no_token`, `invalid_session` | no live session |
| 401 | `invalid_token` | the access token is not one this backend takes, as at a login |
| 409 | `not_a_guest` | `this player is an account's already` |
| 409 | `account_in_use` | `another player is this account's`: the account logged in here before, and has a player of its own |
| 503 | `keys_unavailable` | as at a login |

### `PUT /v1/accounts/name`: change the display name

**Auth**: Bearer. **Body**: `{"displayName"}`. **Answer**: 200 with the name stored.

A player may change the name once in 30 days. A new account's first change is allowed at once.

```http
PUT /v1/accounts/name
Authorization: Bearer 1eOf0B5d...
{"displayName": "Ada Lovelace"}
```
```json
200 {"playerId":1,"displayName":"Ada Lovelace"}
```

The name in the answer is the one stored, after normalisation: runs of spaces collapsed to one, and the text in
Unicode's NFKC form. The boards show the new name at once. If the store does not answer, the name is changed all the
same, and the boards take it at the player's next result (D-60).

| Status | code | When |
|---|---|---|
| 400 | `invalid_display_name` | a rule above is broken; also when `displayName` is missing (`must contain a letter or a digit`) |
| 401 | `no_token`, `invalid_session` | no live session |
| 429 | `too_soon` | `{"code":"too_soon","message":"a display name changes once in 30 days"}` |

### `DELETE /v1/accounts`: delete a guest

**Auth**: Bearer, a guest's session. **Body**: none. **Answer**: **204** with no body. There is no undo.

An account's player is deleted at the account service instead, with the account's password: this route answers it 409
`delete_at_account_service`. The account service tells the backend, and the player is erased here as below, within
seconds, out of its team whatever its role.

The server erases the guest:

- its guest key and display name (others who still see the player see `Deleted player`);
- its friends, blocks, inbox, team invitations and team applications;
- its places on the boards;
- every session. Each device's lobby connection is told `evt.session.revoked` `{"reason":"deleted"}`.

Matches, tournament brackets, the ledger and payment orders stay, under the bare player id
([D-84](../architecture/03-decision-log.md#d-84--a-player-deletes-their-own-account-erased-in-place-what-others-history-needs-kept-under-a-bare-id)).

```http
DELETE /v1/accounts
Authorization: Bearer <the guest's token>
```
```json
204
```

| Status | `code` | When |
|---|---|---|
| 401 | `no_token`, `invalid_session` | no live session |
| 409 | `leader_with_members` | the player leads a team that has other members: hand the team to another member first |
| 409 | `delete_at_account_service` | `this player's account is the account service's: deleting it there erases the player here` |
| 503 | `storage_unavailable` | try again: the same call, with the same session, finishes the deletion |

**A password changed at the account service** ends the player's sessions made before the change; those made after it,
with a new access token, stay. The player's lobby connection is told `evt.session.revoked` `{"reason":"signed_out"}`
and closes when it was on a session that ended; one on a session made after the change stays, told nothing (T-71). On
the device that changed the password, open the lobby again with the session the new access token gave; on any other,
log in again.

## 3. Content

These routes are public and answer `GET` only. They give what the client draws with.

Each answer carries the header `ETag: "<version>"`. Send that value back as `If-None-Match: "<version>"`. When the
content has not changed, the answer is **304** with no body. The `version` is also in the body. The versions of the
classes and of the phrases are the ones the arena's `Welcome` message names (`contentVersion` and
`phraseListVersion`), so a client knows when to fetch them again.

### `GET /v1/content/classes`

The 52 tank classes, with their barrels. The answer is about 32 KB.

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

| Field | Meaning |
|---|---|
| `opensAt` | the level from which a player can choose the class |
| `parents` | the classes it grows from |
| `caps` | the upgrade caps of the eight stats |
| a barrel's `kind` | 0 bullet, 1 trap, 2 drone, 3 minion, 4 rocket, 5 skimmer, 6 convert |
| a barrel's `lifetime` | in ticks |

### `GET /v1/content/phrases`

The quick-chat phrases that a party or a match may send, by `id`.

```json
200 ETag: "3565542594"
{"version":3565542594,"phrases":[{"id":1,"key":"hello","text":"Hello!"},{"id":2,"key":"good_luck","text":"Good luck!"},
 {"id":3,"key":"thanks","text":"Thanks!"}, ... ,{"id":16,"key":"bye","text":"Bye!"}]}
```

### `GET /v1/content/skins`

Which item draws which skin number. A match's snapshot carries the skin number.

```json
200 ETag: "3295025236"
{"version":3295025236,"skins":[{"skin":1,"itemId":"skin_crimson"},{"skin":2,"itemId":"skin_azure"},
 {"skin":3,"itemId":"skin_jade"},{"skin":4,"itemId":"skin_gold"},{"skin":5,"itemId":"skin_carbon"}]}
```

The same request with the version the client holds:

```http
GET /v1/content/skins
If-None-Match: "3295025236"
```
```
304 ETag: "3295025236"
```

## 4. The economy

There are two currencies:

- **Coins** are earned by playing: matches, daily goals, the pass's free tiers and tournament prizes. No route grants
  them.
- **Gems** are bought with money (simulated for now, see "Payments" below), and earned by achievements and the pass.

A new account has neither.

### `GET /v1/shop`

**Auth**: public. **Answer**: 200 with the offers on sale now, as the database holds them this moment: an operator's
edit is listed at once (D-119).

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

| Field | Meaning |
|---|---|
| `sku` | the offer's id, sent to buy it |
| `itemId` | the item it gives |
| `price` | in the offer's `currency` |
| `requiresLevel` | the player's level needed to buy it |
| `availableTo` | the end of a limited offer, or `null` |
| `currency` | `coins` or `gems` |

There are three kinds of item. **Equipment** is worn in a slot (`barrel`, `armor`, `core`, `treads`). **Skins** are
worn in the slot `skin`. **Boosts** give an hour of double experience (XP) or double coins.

### `GET /v1/inventory`

**Auth**: Bearer. **Answer**: 200 with the balances and the items held (those with a quantity above 0), by item id.

```json
200 {"coins":0,"gems":330,"items":[{"itemId":"skin_crimson","qty":1,"level":1}]}
```

### `POST /v1/purchases`: buy an offer

**Auth**: Bearer. **Body**: `{"sku", "itemId", "price", "currency", "key"}`: the offer as `GET /v1/shop` listed it,
and the tap's key. Use the same `key` for every retry of this purchase. **Answer**: 200 with the result.

```http
POST /v1/purchases
Authorization: Bearer 1eOf0B5d...
{"sku": "skin_crimson", "itemId": "skin_crimson", "price": 150, "currency": "gems", "key": "cc7adaf3-712f-4845-8aa9-70185189e8bc"}
```
```json
200 {"result":"bought","itemId":"skin_crimson","coins":0,"held":1,"gems":850}
```

The same request again takes nothing, and tells the first result:

```json
200 {"result":"already_bought","itemId":"skin_crimson","coins":0,"held":1,"gems":850}
```

| Field | Meaning |
|---|---|
| `result` | `bought`, or `already_bought` when the key had bought already; `itemId` is then what it bought |
| `coins`, `gems` | the balances after the purchase |
| `held` | how many of the item the player has now |

A coin offer, from a player who had coins (given in the test database, as no route gives coins):

```json
200 {"result":"bought","itemId":"treads_light","coins":3800,"held":1,"gems":0}
```

| Status | code | When |
|---|---|---|
| 400 | `invalid_body` | the `sku`, the `key`, and the offer's `itemId`, `price` and `currency` (`coins` or `gems`) are required |
| 400 | `invalid_key` | the key is not 16 to 48 characters of `a-z 0-9 -` |
| 401 | `no_token`, `invalid_session` | no live session |
| 403 | `level_required` | `reach level <N>` |
| 404 | `unknown_sku` | `no such offer` |
| 409 | `not_available` | not on sale now |
| 409 | `insufficient_funds` | `not enough of the offer's currency, coins or gems` |
| 409 | `already_owned` | the player holds the item already: equipment and skins are held once, boosts as many as bought (D-53) |
| 409 | `offer_changed` | the offer's item, price or currency is not what the body names: it was edited since it was listed. List the shop again; nothing was taken (D-119) |

Two refusals from a run by hand on 2026-11-27, a guest given 600 gems as a fixture: the gold skin bought, then bought
again with a new key; and the hour's boost sent at a price it no longer has.

```json
409 {"code":"already_owned","message":"held already: equipment and skins are held once (D-53)"}
409 {"code":"offer_changed","message":"the offer has changed since it was listed: list the shop again"}
```

### `GET /v1/purchases`: what the player bought

**Auth**: Bearer. **Query**: `before`, optional: the `next` of the page before. **Answer**: 200 with the player's
purchases, newest first, 50 a page (plan item 145). The same run, after the skin, the boost at its price, and the light
treads for coins:

```json
200 {"purchases":[
  {"id":300,"kind":"item","itemId":"treads_light","price":1200,"currency":"coins","at":"2026-11-27T09:47:44.188Z"},
  {"id":299,"kind":"item","itemId":"boost_xp_hour","price":20,"currency":"gems","at":"2026-11-27T09:47:44.047Z"},
  {"id":298,"kind":"item","itemId":"skin_gold","price":400,"currency":"gems","at":"2026-11-27T09:47:43.753Z"}],
  "next":null}
```

| Field | Meaning |
|---|---|
| `id` | the purchase's row in the ledger; pass the last one's as `before` for the page after |
| `kind` | `item`, a shop purchase, with its `itemId`; or `pass`, a season's premium pass, with its `season` |
| `price`, `currency` | what it cost, in `coins` or `gems` |
| `at` | when it was bought, UTC |
| `next` | the `before` of the next page, or `null` on the last |

| Status | code | When |
|---|---|---|
| 400 | `invalid_query` | `before is a purchase's id, from a page's next` |
| 401 | `no_token`, `invalid_session` | no live session |

### `POST /v1/inventory/{itemId}/level`: raise an item a level

**Auth**: Bearer. **Body**: `{"key"}`. **Answer**: 200 with the item's new level and the coins left.

Only equipment has levels, from 1 to 5. Each level raises the item's bonus, and is paid in coins: level 1 to 2 costs
500, 2 to 3 costs 1000, 3 to 4 costs 2000, and 4 to 5 costs 4000. A key used before answers the first result again,
in the same form.

```http
POST /v1/inventory/treads_light/level
{"key": "7d2b9c4e-1a3f-4b8e-9c0d-2e4f6a8b0c13"}
```
```json
200 {"itemId":"treads_light","level":2,"coins":3300}
```

| Status | code | When |
|---|---|---|
| 400 | `invalid_key` | as for a purchase; also when the key is missing |
| 400 | `not_equipment` | `only an item that is worn has levels` |
| 401 | `no_token`, `invalid_session` | no live session |
| 404 | `not_held` | `{"code":"not_held","message":"the player holds none"}` |
| 409 | `max_level` | `level 5 is the top` |
| 409 | `insufficient_funds` | `not enough coins for the next level` |

### `GET /v1/equipment`, `PUT /v1/equipment/{slot}`, `DELETE /v1/equipment/{slot}`

**Auth**: Bearer. These routes show and change what the player wears: one item in each slot.

- `GET /v1/equipment`: no body.
- `PUT /v1/equipment/{slot}`: body `{"itemId"}`, an item the player holds; it is worn in that slot.
- `DELETE /v1/equipment/{slot}`: no body; it empties the slot.

`{slot}` is one of `barrel`, `armor`, `core`, `treads` and `skin`. Every answer is 200 with the whole loadout: the
five slots, and the stat bonuses that the worn items give. A bonus is a percent. One stat's bonus is at most 25, from
one item or from all of them together. Only stats with a bonus are listed, by name: `health_regen`, `max_health`,
`body_damage`, `bullet_speed`, `bullet_penetration`, `bullet_damage`, `reload` and `movement_speed`. The bonuses go
into the ticket of the player's next match.

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
| 401 | `no_token`, `invalid_session` | no live session |
| 404 | `unknown_item` | `no such item` |
| 409 | `wrong_slot` | the item is worn in another slot, or it is a boost, which is not worn |
| 409 | `not_owned` | `{"code":"not_owned","message":"buy it first"}` |

### `GET /v1/boosts`, `POST /v1/boosts`

**Auth**: Bearer.

- `GET /v1/boosts`: no body. The answer is `{"boosts":[...]}`, the boosts running now, without a `result`.
- `POST /v1/boosts`: body `{"itemId", "key"}`. It uses up one boost item that the player holds. The boost runs for an
  hour from now, or one more hour when the same boost is already running.

```http
POST /v1/boosts
{"itemId": "boost_xp_hour", "key": "22e29d29-034d-4fdd-885c-c3f6e0d289d2"}
```
```json
200 {"result":"activated","boosts":[{"kind":"xp","itemId":"boost_xp_hour","percent":100,"endsAt":"2026-10-05T23:29:55.706Z"}]}
```

A boost's `kind` is `xp` or `coins`. The same key again answers `already_activated`.

| Status | code | When |
|---|---|---|
| 400 | `invalid_body` | `itemId and key are required` |
| 400 | `invalid_key` | as for a purchase |
| 401 | `no_token`, `invalid_session` | no live session |
| 404 | `unknown_item` | `no such boost` |
| 409 | `not_owned` | the player holds none of that boost: `buy it first` |
| 409 | `other_running` | a different boost of that kind runs: wait until it ends |

### Payments: gems for money (simulated)

Payments run only when `platform` is started with `BACKEND_PAYMENT_PROVIDER=simulated`. Otherwise every payment route
answers **503** `payments_off` (`nothing is sold here: no payment provider is named`). No real payment provider is
connected yet (Q-52). The flow is: the client places an order, and then a call gives the simulated provider's
verdict.

**`GET /v1/payments/packs`**: public. The gem packs on sale.

```json
200 {"packs":[{"productId":"gems_80","gems":80,"priceCents":99,"currency":"USD"},
 {"productId":"gems_500","gems":500,"priceCents":499,"currency":"USD"},
 {"productId":"gems_1100","gems":1100,"priceCents":999,"currency":"USD"},
 {"productId":"gems_2400","gems":2400,"priceCents":1999,"currency":"USD"},
 {"productId":"gems_6500","gems":6500,"priceCents":4999,"currency":"USD"}]}
```

**`POST /v1/payments`**: Bearer. Body `{"productId", "key"}`, with a key of 16 to 36 characters. It places an order.

```http
POST /v1/payments
{"productId": "gems_500", "key": "417a7aa4-35b1-4bf1-a86b-e882322d65a6"}
```
```json
200 {"order":{"orderId":"2a0a6b2f-5ad7-47d5-8077-a72b03621e1c","productId":"gems_500","gems":500,"bonus":0,
 "priceCents":499,"currency":"USD","state":"pending","createdAt":"2026-10-05T22:29:54.500Z"}}
```

| An order's field | Meaning |
|---|---|
| `orderId` | the order's UUID |
| `productId`, `gems`, `priceCents`, `currency` | the pack ordered |
| `bonus` | the extra gems that the player's first paid order gives (as many again). It is 0 until the order is paid, and 0 for later orders |
| `state` | `pending`, `paid`, `declined`, `refunded` or `expired` |
| `createdAt` | when the order was placed; the same in every answer about the order |

**`GET /v1/payments/{orderId}`**: Bearer. The answer is `{"order": ...}`, for the player's own orders only (another's
gets 404 `no_such_order`). `orderId` is the order's UUID as it was answered: 36 characters of `0-9 a-f -`. Anything
else names no route (404 `no_such_route`).

**`POST /v1/payments/{orderId}/simulate`**: Bearer. Body `{"outcome": "paid" | "declined"}`: what a real provider
would report.

```http
POST /v1/payments/2a0a6b2f-5ad7-47d5-8077-a72b03621e1c/simulate
{"outcome": "paid"}
```
```json
200 {"order":{"orderId":"2a0a6b2f-...","productId":"gems_500","gems":500,"bonus":500,"priceCents":499,
 "currency":"USD","state":"paid","createdAt":"2026-10-05T22:29:54.500Z"},"confirmed":true,"gems":1000}
```

A player's first paid order is doubled (`bonus`). `gems` is the player's balance after. `confirmed` is false when the
order was no longer pending, and then nothing changes:

```json
200 {"order":{..., "state":"paid"},"confirmed":false,"gems":1000}
```

Until 2026-10-06, a read after the order was placed could show `createdAt` one millisecond off, `.501` here (P-55).

| Status | code | When |
|---|---|---|
| 503 | `payments_off` | no payment provider is named |
| 400 | `invalid_body` | `productId and key are required` |
| 400 | `invalid_key` | the key is not 16 to 36 characters of `a-z 0-9 -` |
| 400 | `invalid_outcome` | the outcome is not `paid` or `declined` |
| 401 | `no_token`, `invalid_session` | no live session |
| 404 | `unknown_product` | `no such pack` |
| 404 | `no_such_order` | not an order of the player's |
| 409 | `refund_debt` | a refunded order's gems were spent; support clears the debt first ([03](03-admin-api.md)) |

### The season pass: `GET /v1/pass`, `POST /v1/pass/premium`

**Auth**: Bearer. The pass has 40 tiers in each season, 250 points each. Points come from match results and daily
goals. Each tier has a free reward and a premium reward.

`GET /v1/pass` answers the tiers, and the player's points, tier, and whether the player has premium:

```json
200 {"season":1,"endsAt":"2026-11-01T00:00:00Z","points":0,"tier":0,"premium":false,"premiumGems":500,"tierPoints":250,
 "tiers":[{"tier":1,"free":{"coins":150,"gems":0,"itemId":null},"premium":{"coins":0,"gems":15,"itemId":null}},
          ...
          {"tier":5,"free":{"coins":0,"gems":5,"itemId":null},"premium":{"coins":0,"gems":15,"itemId":"boost_xp_hour"}},
          ... 40 tiers]}
```

| Field | Meaning |
|---|---|
| `season`, `endsAt` | the season, and when it ends |
| `points`, `tier` | the player's points, and the tier they reach |
| `premium` | whether the player bought the premium track |
| `premiumGems` | what premium costs, in gems |
| `tierPoints` | the points in each tier |
| `tiers` | each tier's `free` and `premium` reward: coins, gems, and an item or `null` |

`POST /v1/pass/premium` has no body. It buys premium for 500 gems, and pays at once the premium rewards of the tiers
already reached. `gems` is the balance after.

```json
200 {"result":"bought","gems":330,"pass":{"season":1, ..., "premium":true, ...}}
```

Again in the same season, `result` is `already_bought`.

| Status | code | When |
|---|---|---|
| 401 | `no_token`, `invalid_session` | no live session |
| 409 | `insufficient_funds` | `premium is 500 gems` |
| 409 | `season_ended` | `the season has ended and is being closed` |

## 5. Progress, seasons and boards

### `GET /v1/achievements`

**Auth**: Bearer. **Answer**: all 17 achievements, with the player's progress.

`gems` is the reward. The worker pays it when a match result takes the progress to the threshold. Play time is in
seconds.

```json
200 {"achievements":[{"id":"kills_100","stat":"kills","threshold":100,"gems":10,"progress":0,"reached":false},
 {"id":"kills_1000","stat":"kills","threshold":1000,"gems":20,"progress":0,"reached":false}, ...]}
```

`stat` is one of `kills`, `wins`, `matches`, `assists`, `bestScore` and `playtime`.

### `GET /v1/goals`

**Auth**: Bearer. **Answer**: today's three goals. A day is a UTC day.

Each goal pays coins when it is done. When all three are done, the player gets `setGems` more.

```json
200 {"day":"2026-10-05","resetsAt":"2026-10-06T00:00:00Z",
 "goals":[{"id":"assists_5","kind":"assists","target":5,"coins":100,"progress":0,"done":false},
          {"id":"kills_10","kind":"kills","target":10,"coins":100,"progress":0,"done":false},
          {"id":"score_15000","kind":"score","target":15000,"coins":200,"progress":0,"done":false}],
 "setGems":3,"setDone":false}
```

### `GET /v1/seasons`

**Auth**: public. **Answer**: the season under way (`current`), and the past ones (`past`), newest first, at most
12. `current` is `null` when no season is under way. The rating boards are kept per season.

```json
200 {"current":{"id":1,"startsAt":"2026-10-05T22:29:40.672Z","endsAt":"2026-11-01T00:00:00Z"},"past":[]}
```

### `GET /v1/leaderboards/{board}` and `GET /v1/leaderboards/{board}/me`

| `board` | Measures | Kept |
|---|---|---|
| `alltime`, `daily`, `weekly` | the best score in one match | for ever; today (UTC); this ISO week |
| `duel`, `rffa`, `tvt` | the rating in that mode. A player is listed after 10 rated matches in it | per season |
| `teams` | the team's rating. A team is listed after 10 rated team matches | per season |

**The top of a board**: public. `?limit=` is 1 to 100; the default is 50. A number outside that range is brought
inside it, and anything else gives the default. The rating boards and `teams` also take `?season=<id>` for a past
season; the default is the current one. `season` works the same at the top and at `/me`.

```http
GET /v1/leaderboards/alltime?limit=10
```
```json
200 {"board":"alltime","entries":[{"rank":1,"playerId":42,"name":"Ada","score":5120}, ...]}
```

A row is `{"rank", "playerId", "name", "score"}`. `rank` starts at 1. On a rating board, `score` is the rating. The
`teams` board lists `{"rank", "teamId", "name", "score"}`.

**`/me`**: Bearer. The player's place (or the player's team's, on `teams`), and the rows around it: five on each
side.

```json
200 {"board":"daily","rank":17,"score":4100,"entries":[ ...up to 11 rows... ]}
```

These two examples show the shape only. The run's stack had played no match, so its boards answered
`{"board":"alltime","entries":[]}`, and `/me` answered the 404 `not_ranked` below.

| Status | code | When |
|---|---|---|
| 400 | `invalid_season` | `season` is not a number |
| 401 | `no_token`, `invalid_session` | `/me` without a live session |
| 404 | `unknown_board` | `{"code":"unknown_board","message":"no board called monthly"}` |
| 404 | `not_ranked` | not on that board yet: `{"code":"not_ranked","message":"no score on this board yet"}` |
| 404 | `no_such_season` | an unknown season, or `season` given for a score board |
| 404 | `not_in_team` | `teams/me` for a player in no team |

## 6. Matchmaking: from a request to the arena

Every way into a match ends in a **grant**. The client then connects to the arena and sends `Join` with the grant's
ticket. From there on it speaks the arena's own binary protocol
([02-networking](../detailed-design/02-networking.md) §3).

| A grant's field | Meaning |
|---|---|
| `arenaHost`, `arenaPort` | where to connect |
| `ticketId` | a ticket for one join, 22 characters, valid for 60 seconds |
| `tls` | whether to speak TLS |
| `tlsName` | the arena's name, behind the site's front (see below). Left out when there is none |
| `mode` | the match's mode. Not in the answer to `POST /v1/match-requests`, a seat in the public arena |

```
200 {"arenaHost":"203.0.113.10","arenaPort":9001,"ticketId":"<22 characters>","tls":true,"tlsName":"b1.arena.internal"}
```

**The site's grants.** Behind the site's front
([D-82](../architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name)),
every grant has a `tlsName`. `arenaHost` is the site's public address, and `arenaPort` is 9001, where the front's
nginx listens. There are two ways to reach the arena:

- **With TLS** (the full client and `client-simple`). Connect to `arenaHost:arenaPort`. In the TLS handshake, send
  `tlsName` as the server name, and check the certificate against `tlsName` and the backend's root. nginx ends TLS and
  passes the connection to the arena with that name.
- **Without TLS, through port 80** (`client-simple-no-tls`,
  [D-115](../architecture/03-decision-log.md#d-115--a-players-build-may-connect-without-tls-and-the-site-serves-it-on-80-matches-included)).
  Connect to the API's own address on port 80, not to `arenaPort`. Send `GET /match/<tlsName> HTTP/1.1`, with the
  headers `Host`, `Connection: Upgrade` and `Upgrade: backend-match`. Read the answer to its blank line. Once the
  answer is `101`, play as on 9001 ([02-networking §1](../detailed-design/02-networking.md#1-transport)). nginx
  answers 404 to a name it does not know.

The request and the answer on port 80, as `client-simple-no-tls` and the arena write them:

```http
GET /match/b1.arena.internal HTTP/1.1
Host: 203.0.113.10
Connection: Upgrade
Upgrade: backend-match

HTTP/1.1 101 Switching Protocols
Connection: Upgrade
Upgrade: backend-match
```

**A grant without `tlsName`** comes from a drill, or from an arena that the client reaches directly. The client
connects to `arenaHost:arenaPort`, and with `tls`, checks the certificate against `arenaHost`, as before D-82.

The examples below come from runs that reached the arena directly (`127.0.0.1:9021`, no TLS). Since
[D-92](../architecture/03-decision-log.md#d-92--a-script-for-each-service-of-a-run-by-hand-and-nginx-on-the-sites-ports-in-front-of-it),
a run by hand has nginx in front of its arena, as the site does. Every grant then names `<address>`, port 9001,
`"tls":true` and `"tlsName":"a1.arena.internal"`.

**The modes**, as `mode` names them:

| `mode` | Players | Sides | Rated | How a player gets in |
|---|---|---|---|---|
| `ffa` | open | each for themselves | no | the public arena, by `POST /v1/match-requests` |
| `duel` | 2 | 1 a side | yes | the queue |
| `rffa` | 8 | each for themselves (ranked free-for-all) | yes | the queue |
| `maze` | 8 | each for themselves | no | the queue |
| `tvt` | 6 | 3 a side (team against team) | yes | the queue; a party of up to 3 queues together |
| `coop` | 3 | one team of 3 against the arena's own tanks | no | the queue; a party of up to 3 queues together |
| `domination` | 6 | 3 a side | no | the queue; a party of up to 3 queues together |
| `tag` | 6 | 3 a side | no | the queue; a party of up to 3 queues together |
| `teams` | 6 | 3 a side, each side a party of 3 from one team | the teams are rated | the queue, by the team's leader or a vice leader |
| `sandbox` | the player, or a party of up to 3 | each for themselves | no | a private room, by `POST /v1/sandbox` |

**The matcher matches only players connected to the lobby WebSocket.** It works in rounds of about a second. In each
round it drops anyone queued without a lobby connection, and their state becomes `none`. When it finds a match, it
asks each player to confirm within 10 seconds, with the push `evt.match.ready`. Once all have accepted, it pushes each
player their grant, `evt.match.found`. So a client joins a queue and accepts the match over the lobby
([02](02-lobby-websocket.md) §5 shows a whole duel). The HTTP routes below give the same answers. `GET /v1/queue`
gives the grant to a client that missed the push.

### `POST /v1/match-requests`: a seat in the public arena

**Auth**: Bearer. **Body**: none. **Answer**: 200 with a grant.

The server chooses the live arena with the most free places. It counts as taken the places promised to players sent
there in the last minute, whom the arena has not counted yet (D-79).

```json
200 {"arenaHost":"127.0.0.1","arenaPort":9021,"ticketId":"1bvMvhOfHVcHga1gZV0yuA","tls":false}
```

| Status | code | When |
|---|---|---|
| 401 | `no_token`, `invalid_session` | no live session |
| 503 | `no_arena` | no arena has room. Each ticket given holds its place for up to a minute: until the arena counts its player, or the ticket lapses |

### `POST /v1/queue`: join a queue

**Auth**: Bearer. **Body**: `{"mode"}`. **Answer**: 200 with the queue state. A party queues whole, by its leader.

```http
POST /v1/queue
{"mode": "duel"}
```
```json
200 {"state":"queued","mode":"duel","waitedSeconds":0}
```

| Status | code | When |
|---|---|---|
| 400 | `unknown_mode` | `{"code":"unknown_mode","message":"no queue for chess"}`. Also for `ffa` and `sandbox`, which have no queue, and for a body without `mode` (`no queue for null`) |
| 400 | `party_too_big`, `party_too_small` | the party does not fit the mode |
| 401 | `no_token`, `invalid_session` | no live session |
| 403 | `not_allowed` | `teams`: the caller is a plain member of the team, not its leader or a vice leader |
| 409 | `already_queued` | `{"code":"already_queued","message":"already in a queue"}` |
| 409 | `in_match` | a grant or a tournament match is waiting: call `GET /v1/queue` |
| 409 | `in_party` | the caller is a member of a party, not its leader |
| 409 | `not_one_team` | `teams`: the party's members are not all of the caller's team |
| 409 | `queue_locked` | the player declined a match less than a minute ago |
| 409 | `party_changed` | the party changed meanwhile: try again |

### `GET /v1/queue`: where the player stands

**Auth**: Bearer. **Answer**: 200 with the queue state. Fields that do not apply are left out.

| `state` | Meaning |
|---|---|
| `none` | not queued |
| `queued` | waiting in a queue; `mode` and `waitedSeconds` say which, and for how long |
| `confirming` | asked about a match: `matchUid` names it, and `secondsLeft` is the time left to answer |
| `matched` | a match was made: `grant` is where to go |

```json
200 {"state":"none"}
200 {"state":"queued","mode":"duel","waitedSeconds":0}
200 {"state":"confirming","mode":"duel","waitedSeconds":0,"matchUid":"01M4730R905V76D3SE5WW3Z89K","secondsLeft":10}
200 {"state":"matched","mode":"sandbox","waitedSeconds":0,
     "grant":{"arenaHost":"127.0.0.1","arenaPort":9021,"ticketId":"kLHvS2RTU-iGqyIn82E8wg","tls":false,"mode":"sandbox"}}
```

The `confirming` line comes from the lobby run ([02](02-lobby-websocket.md) §5).

### `POST /v1/queue/accept`, `POST /v1/queue/decline`

**Auth**: Bearer. **Body**: `{"matchUid"}`, the match the player was asked about. **Answer**: 200 with the player's
state, as `GET /v1/queue` gives it.

A decline calls the match off for every player. So does silence for 10 seconds. The player who declined is then kept
out of the queues for a minute.

```http
POST /v1/queue/accept
{"matchUid": "01M4730R905V76D3SE5WW3Z89K"}
```
```json
200 {"state":"confirming","mode":"duel","waitedSeconds":0,"matchUid":"01M4730R905V76D3SE5WW3Z89K","secondsLeft":10}
```

| Status | code | When |
|---|---|---|
| 400 | `invalid_body` | the body has no `matchUid` |
| 401 | `no_token`, `invalid_session` | no live session |
| 409 | `not_confirming` | `not asked about that match, or no longer` |

### `DELETE /v1/queue`: leave

**Auth**: Bearer. **Body**: none. **Answer**: always `200 {"state":"none"}`, whether or not the player was queued.

It takes the player out of the queue; a party's leader takes the whole party out. It also drops a grant that was not
used, and gives back a sandbox that was not joined.

### `POST /v1/sandbox`: a private room

**Auth**: Bearer. **Body**: none. **Answer**: 200 with the caller's grant.

It opens a room of the player's own for 20 minutes. When a party's leader calls it, the room is the party's, and each
member is pushed their grant (`evt.match.found`).

```json
200 {"arenaHost":"127.0.0.1","arenaPort":9021,"ticketId":"kLHvS2RTU-iGqyIn82E8wg","tls":false,"mode":"sandbox"}
```

| Status | code | When |
|---|---|---|
| 401 | `no_token`, `invalid_session` | no live session |
| 409 | `in_sandbox` | a sandbox is held already: one at a time, until it ends or is left |
| 409 | `already_queued` | leave the queue first |
| 409 | `in_match` | a match is waiting: `GET /v1/queue` |
| 409 | `in_party` | a member of a party: the party's leader opens it |
| 503 | `no_room` | no arena has a room free now |

## 7. The party

A party is up to three players who queue together. The leader's first invitation makes the party. An invitation lasts
60 seconds. All party routes are Bearer, and every answer is 200 with the caller's party as it is now:

```json
{"partyId":"01M47307VY81WJQR5VH8XBVJ98","leader":1,"members":[{"playerId":1,"name":"Ada Lovelace"},{"playerId":2,"name":"Bob"}],"version":2}
```

In no party, the answer is `{"partyId":null,"leader":0,"members":[]}`. A change that took the caller out (a leave, or
a kick that ended the party) answers the ended form, with the party it was:
`{"partyId":null,"leader":0,"members":[],"was":"01M47307VY81WJQR5VH8XBVJ98","version":3}`.

`version` grows with each change. Members are told each change by the lobby push `evt.party.update`. When a reply and
a push disagree, the higher `version` wins.

| Route | Body | Answer |
|---|---|---|
| `GET /v1/party` | none | the party, for example `{"partyId":null,"leader":0,"members":[]}` |
| `POST /v1/party/invite` | `{"playerId": 2}` | `{"partyId":"01M47307VY81WJQR5VH8XBVJ98","leader":1,"members":[{"playerId":1,"name":"Ada Lovelace"}],"version":1}`. The invitee is pushed `evt.party.invite` |
| `POST /v1/party/accept` | `{"partyId": "01M47307VY81WJQR5VH8XBVJ98"}` | the party with both players, `version` 2 |
| `POST /v1/party/say` | `{"phraseId": 1}` | the party. Every member is pushed `evt.party.said` |
| `POST /v1/party/kick` | `{"playerId": 2}` | the party after the kick, or the ended form when one player is left |
| `POST /v1/party/leave` | none | the ended form, or the no-party form; always 200 |

| Status | code | When |
|---|---|---|
| 400 | `invalid_body` | the field the action needs is missing |
| 400 | `self` | the player invited themself |
| 400 | `unknown_phrase` | the phrase is not in `GET /v1/content/phrases` |
| 401 | `no_token`, `invalid_session` | no live session |
| 404 | `not_in_lobby` | the invitee is a friend who is not connected to the lobby |
| 404 | `no_invitation` | `no invitation to that party, or it has lapsed` |
| 404 | `not_member` | a kick of a player who is not a member of a party the caller leads; a member who is not the leader gets this too |
| 404 | `no_party` | the caller is in no party |
| 409 | `in_party` | the invitee is in a party already |
| 409 | `not_leader` | only the leader invites |
| 409 | `party_full` | a party holds three at most |
| 409 | `queued` | the party is queued: it takes no one in until it is out of the queue |
| 429 | `too_soon` | `{"code":"too_soon","message":"one phrase every two seconds"}`; or `sixty invitations an hour at most` |

## 8. Friends, blocks and the inbox

All these routes are Bearer. A friendship is asked for, and it is made when the other player asks back. A request
lasts 7 days. A player has at most 100 friends, 100 players blocked and 50 requests out. Answers without data are
`200 {}`.

### `POST /v1/friends`: ask, or accept by asking back

**Body**: `{"playerId"}`. **Answer**: 200 with the state of the friendship.

```http
POST /v1/friends
Authorization: Bearer <A's token>
{"playerId": 2}
```
```json
200 {"state":"asked"}
```

When B asks A back, they become friends: `200 {"state":"friends"}`. The player asked is pushed
`evt.friend.request` (then `evt.friend.accepted`), and `evt.inbox`. A request to someone who has blocked the caller
answers the same `asked`, and is never shown to them.

### `GET /v1/friends`

**Answer**: 200 with three lists. `friends` are the player's friends, each with whether they are connected to the
lobby now (`online`). `requests` are the requests to the player. `asked` are the player's own requests out.

```json
200 {"friends":[],"requests":[{"playerId":1,"name":"Ada Lovelace","expiresAt":"2026-10-12T22:29:57.845Z"}],"asked":[]}
200 {"friends":[{"playerId":2,"name":"Bob","online":false}],"requests":[],"asked":[]}
```

### `DELETE /v1/friends/{playerId}`, `DELETE /v1/friend-requests/{playerId}`

The first ends a friendship. The second declines a request to the player, or withdraws the player's own. Each answers
`200 {}`. Sent again: 404 `not_friends`, or 404 `no_request`
(`{"code":"no_request","message":"no request between you"}`).

### `GET /v1/blocks`, `POST /v1/blocks`, `DELETE /v1/blocks/{playerId}`

To block a player, send `POST /v1/blocks` with the body `{"playerId"}`. A block ends a friendship and any request
between the two. From then on, the blocked player's friend requests, team invitations and team applications to the
blocker are dropped unseen. `GET /v1/blocks` answers the list:

```json
200 {"blocked":[{"playerId":1,"name":"Ada Lovelace"}]}
```

Blocking a player twice answers 200. Unblocking a player who is not blocked answers 404 `not_blocked`.

Errors of the friend and block routes:

| Status | code | When |
|---|---|---|
| 400 | `invalid_body` | `playerId is required` |
| 400 | `yourself` | `{"code":"yourself","message":"not yourself"}` |
| 401 | `no_token`, `invalid_session` | no live session |
| 404 | `no_such_player` | `no such player` |
| 404 | `not_friends`, `no_request`, `not_blocked` | as above |
| 409 | `already_friends`, `already_asked` | `{"code":"already_asked","message":"asked already"}` |
| 409 | `you_blocked` | the caller has blocked them: unblock them first |
| 409 | `friends_full`, `blocks_full`, `too_many_asked` | the limits above |
| 429 | `too_soon` | `twenty requests an hour at most` |

### `GET /v1/inbox`, `POST /v1/inbox/read`

The inbox tells the player what happened while they looked elsewhere. `GET /v1/inbox` answers the newest 50 items;
items are kept 30 days. `ref` is what the item is about.

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

`POST /v1/inbox/read` with the body `{"upTo": 1}` marks read every item up to that id. It answers `200 {}`, or 400
`invalid_body` when `upTo` is missing.

## 9. Teams

All team routes are Bearer. A team has a leader, up to two vice leaders, and members: 30 players at most. A team's
name follows the display-name rules (§2), and is unique whatever its case or accents. **Leaving a team, being removed
from one, or disbanding one starts a day's cooldown** before the player may join or make another team.

The team, as its members see it:

```json
{"id":1,"name":"Tmuvtoo4q","members":[{"playerId":1,"name":"Ada Lovelace","role":"leader"},
 {"playerId":2,"name":"Bob","role":"vice_leader"},{"playerId":3,"name":"cy_muvtoo4q","role":"member"}],
 "rating":1200,"wins":0,"losses":0,"draws":0}
```

`role` is `leader`, `vice_leader` or `member`. `rating`, `wins`, `losses` and `draws` are the team's record in team
matches. As anyone else sees the team, `members` is a count: `{"id":1,"name":"Tmuvtoo4q","members":2,"rating":1200}`.

In the table, "Who" says which players may call the route.

| Route | Who | Body | Answer |
|---|---|---|---|
| `POST /v1/teams` | a player in no team | `{"name": "Tmuvtoo4q"}` | the team, with the caller as its leader |
| `GET /v1/teams?name=Tmuv` | any player | none | `{"teams":[{"id":1,"name":"Tmuvtoo4q","members":2,"rating":1200}]}`: teams whose name starts with the text, 20 at most |
| `GET /v1/teams/{teamId}` | any player | none | the team as anyone sees it |
| `GET /v1/teams/mine` | a member | none | the team; 404 `no_team` for a player in none |
| `POST /v1/teams/mine/invites` | the leader, a vice leader | `{"playerId": 2}` | the team. The invitee gets an inbox item |
| `GET /v1/team-invites` | any player | none | `{"invites":[{"teamId":1,"teamName":"Tmuvtoo4q","expiresAt":"2026-10-12T22:29:59.505Z"}]}` |
| `POST /v1/team-invites/{teamId}` | the player invited | `{"accept": true}` | the team joined. With `false`: the invitations left |
| `POST /v1/teams/{teamId}/applications` | a player in no team | none | `{}`. The leader and the vice leaders get an inbox item |
| `DELETE /v1/teams/{teamId}/applications` | the applicant | none | `{}`: the application is withdrawn |
| `GET /v1/team-applications` | any player | none | the caller's own: `{"applications":[{"teamId":1,"teamName":"Tmuvtoo4q","expiresAt":"2026-10-12T22:29:59.954Z"}]}` |
| `GET /v1/teams/mine/applications` | the leader, a vice leader | none | `{"applications":[{"playerId":3,"name":"cy_muvtoo4q","expiresAt":"2026-10-12T22:29:59.954Z"}]}` |
| `POST /v1/teams/mine/applications/{playerId}` | the leader, a vice leader | `{"accept": true}` | the team, with the applicant in it (or still out, when declined) |
| `POST /v1/teams/mine/members/{playerId}/role` | the leader | `{"role": "vice_leader"}` (or `member`) | the team |
| `PUT /v1/teams/mine/name` | the leader | `{"name": "Rmuvtoo4q"}` | the team. Once in 30 days |
| `POST /v1/teams/mine/leader` | the leader | `{"playerId": 2}` | the team: the target leads now, and the old leader is a member |
| `DELETE /v1/teams/mine/members/{playerId}` | the leader; a vice leader for plain members | none | the team without them |
| `POST /v1/teams/mine/leave` | a member | none | `{}`. A leader alone in the team deletes it |
| `DELETE /v1/teams/mine` | the leader | none | `{}`: the team is disbanded, and every member starts the cooldown |

Invitations and applications last 7 days. A player may have 5 applications out at once, and a team 20 invitations.

Members are pushed `evt.team.update` with the team when someone joins or leaves, when a role or the leader changes,
and when the team is renamed or disbanded. A player who is out of the team is pushed `{"team":null}`. An invitation
or an application is told by an inbox item instead.

```http
POST /v1/teams/mine/leave
Authorization: Bearer <A's token, A leading Bob and Cy>
```
```json
409 {"code":"leader_with_members","message":"hand the team over first"}
```

| Status | code | message |
|---|---|---|
| 400 | `invalid_name` | `a team is named as a player is`: any rule is broken; also a search with no `name` |
| 400 | `invalid_body` | `playerId is required`, `accept is required: true or false`, `role is required: vice_leader or member` |
| 400 | `invalid_role` | `vice_leader or member` |
| 401 | `no_token`, `invalid_session` | no live session |
| 403 | `not_allowed` | `not for this role` |
| 404 | `no_team`, `no_such_team`, `no_such_player`, `not_a_member`, `no_invite`, `no_application` | Every `/mine` route answers `no_team` to a player in no team. So does `GET /v1/teams/mine/applications`, since 2026-10-06 (it was 403, P-58) |
| 409 | `in_team` | `one team a player` |
| 409 | `cooling_down` | `a day after leaving a team before another` |
| 409 | `name_taken` | `however it is cased or accented` |
| 409 | `team_full`, `too_many_vices`, `too_many_invited`, `too_many_applied`, `already` | the limits above; `already`: one application to a team at a time, and a declined one until it lapses |
| 409 | `leader_with_members` | `hand the team over first` |
| 429 | `too_soon` | `twenty invitations an hour at most`; `twenty applications an hour at most` (counted apart from invitations; until 2026-10-06 it gave the invitations' message, P-56); `a team's name changes once in 30 days` |

## 10. Tournaments

An operator makes a tournament ([03](03-admin-api.md)). A tournament is of duels or of teams, by elimination or by
round robin (every entry plays every other). When registration ends, the worker seeds the tournament. Then it runs
the rounds and pays the prizes.

### `GET /v1/tournaments`, `GET /v1/tournaments/{id}`

**Auth**: public.

- `GET /v1/tournaments` answers the open tournaments (in `registration`, `seeded` or `running`) as
  `{"tournaments":[...]}`. Each is in the form below, with `matches` empty.
- `GET /v1/tournaments/{id}` answers one tournament in any state, with its matches. An unknown id gets 404
  `no_such_tournament`.

```json
200 {"id":1,"name":"Weekly Cup","mode":"duel","format":"elimination","state":"registration","maxEntries":8,
 "registrationEnds":"2026-10-06T00:30:04Z","startsAt":"2026-10-06T00:35:04Z","roundMinutes":10,"currentRound":0,
 "prizes":[3000,1500,500],"entries":[{"playerId":2,"name":"Bob","seed":0}],"matches":[]}
```

| Field | Meaning |
|---|---|
| `mode` | `duel` or `teams` |
| `format` | `elimination` or `round_robin` |
| `state` | `registration`, `seeded`, `running`, `finished` or `cancelled` |
| `prizes` | coins for the 1st, 2nd and 3rd places |
| `entries` | in a duel tournament `{"playerId","name","seed"}`; in a teams tournament `{"teamId","name","seed","roster":[{"playerId","name"}]}` |
| `matches` | see below |
| `standings` | a round robin only: `{"playerId" (or "teamId"),"name","points","wins","draws","losses"}` for each entry, by place |

A match is `{"round":1,"slot":0,"state":"ready","playerA":7,"playerB":8,"winner":7}`. Its `state` is `pending`,
`ready` or `done`. In a teams tournament the keys are `teamA`, `teamB` and `winnerTeam`. A key is left out while it is
not known.

### `POST /v1/tournaments/{id}/entries`, `DELETE /v1/tournaments/{id}/entries`

**Auth**: Bearer. **Body**: none. **Answer**: 200 with the tournament. `POST` registers, and `DELETE` withdraws, while
registration is open.

A player needs **10 rated matches** in the tournament's mode. A team enters as a party of three players of one team;
its leader or a vice leader registers it, and the three are its roster.

The run's new player:

```json
409 {"code":"too_few_rated","message":"ten rated matches in its mode enter a tournament"}
```

A player with 10 rated duels (given in the test database) gets `200` and the tournament, with the player in `entries`,
as above. Registering again: `409 {"code":"already","message":"registered already"}`. Withdrawing: `200` and the
tournament without the player. Withdrawing while not registered:
`409 {"code":"not_registered","message":"not registered, or registration is over"}`.

| Status | code | When |
|---|---|---|
| 400 | `party_too_small` | teams: a team enters as a party of three |
| 401 | `no_token`, `invalid_session` | no live session |
| 403 | `not_allowed` | teams: the team's leader or a vice leader acts for it |
| 404 | `no_such_tournament` | for a withdrawal as for a registration (until 2026-10-06 a `DELETE` answered 409 `not_registered`, P-57) |
| 409 | `closed` | `registration is over` |
| 409 | `full` | no entries left |
| 409 | `too_few_rated` | fewer than ten rated matches in the mode |
| 409 | `already` | registered already. A player or team already entered is told `already`, even when the tournament is full (P-59) |
| 409 | `not_registered` | the withdrawal of a player who is not registered |
| 409 | `in_party` | teams: a member, not the party's leader |
| 409 | `not_one_team` | teams: the party's members are not all of one team |

### `GET /v1/tournaments/{id}/match`

**Auth**: Bearer. **Answer**: 200 with the player's grant for a round's match, while the match waits for them (60
seconds). The same grant is pushed as `evt.tournament.match`.

```json
200 {"tournamentId":1,"round":1,"arenaHost":"203.0.113.10","arenaPort":9001,"ticketId":"<22 characters>","tls":true,"mode":"duel","tlsName":"a1.arena.internal"}
404 {"code":"no_match","message":"no match waiting for you in it"}
```

The 200 is the shape that the worker writes; the run had no round under way.
