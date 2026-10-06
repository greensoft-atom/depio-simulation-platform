# The admin API (operators)

What an operator calls: the arenas and rooms live now, players coming back, a notice to
everyone, a tournament, a kick, a ban, a refund, the end of a season. A second listener of
`platform`, on loopback only, reached over SSH. Every example below is a real request and its
real answer, from the Postman collection's run of 2026-10-06 against a stack built from this
repository.

## 1. Reaching it

- **Off unless named.** `BACKEND_ADMIN_ADDR=127.0.0.1:9120` (a loopback address, or the platform
  will not start) and the secret in a file, `BACKEND_ADMIN_TOKEN_FILE`: the drop-in
  `backend/deploy/systemd/platform-admin.conf.example` delivers the secret
  ([operations/01 §2](../operations/01-deploy.md#2-conventions),
  [runbook §3a](../operations/02-runbook.md#3a-the-admin-api)).
- **Over SSH**: `ssh -L 9120:127.0.0.1:9120 <machine>`, then `http://127.0.0.1:9120/admin/...`
  from the operator's own machine. Plain HTTP; the tunnel is the encryption.
- **Authentication**: `Authorization: Bearer <the secret>`, every call.

```http
GET /admin/arenas
Authorization: Bearer not-the-secret
```
```json
401 {"code":"unauthorised","message":"send Authorization: Bearer <the admin secret>"}
```

## 2. Rules for every call

- Bodies are JSON (the content type is not checked), at most 4096 bytes.
- **A change needs a reason**, `"reason"`, 1 to 200 characters, kept in the audit:

```http
POST /admin/players/3/kick
{}
```
```json
400 {"code":"no_reason","message":"say why, in at most 200 characters: it is audited"}
```

- **Audited**: the call, its body and its outcome are written to `admin_audit` in MySQL,
  refusals included. Not written: an unknown path (404 `not_found`), a wrong method (405), a
  bad `days` (400), a 500, and a 503 for a call that did nothing. A ban's 503s are written with
  the ban, and a notice is written `sent` before it goes, so one the store then failed is there
  too.
- Errors are `{"code","message"}`. 404 `not_found` `no such call`; 405 `method_not_allowed`
  with `Allow`; 503 `storage_unavailable` (`the database did not answer; nothing was done`);
  503 `storage_unavailable` with `the store did not answer: call again` when the store (j-redis)
  fails: every call is safe to make again, and only a notice the store took without answering
  goes out twice (until 2026-10-06 this was 500 `internal`, O-36); 500 `internal` for anything
  else unexpected.

## 3. Reading

### `GET /admin/arenas`

The live arenas (an arena's entry lapses 10 s after its last announcement), as an array.

```json
200 [{"name":"arena-1","host":"127.0.0.1","port":9021,"players":0,"maxPlayers":600,"tls":false,"rooms":1,"maxRooms":4}]
```

### `GET /admin/rooms`

Every room of every live arena, as last announced (up to 3 s old). `stage`: `open` (the
public arena), or a match's `waiting`, `playing`, `over`, with its `matchUid`.

```json
200 [{"arena":"arena-1","room":"room-1","players":0,"mode":"ffa","stage":"open"}]
```

### `GET /admin/stats?days=N`, `/admin/stats/features?days=N`, `/admin/stats/funnel?days=N`

Whether players come back, by day (UTC), newest first, `days` 1 to 60 (default 14; else 400
`invalid_days` `days is 1 to 60`). A day-N figure is `null` until that day has ended.

- `/admin/stats`: the players who played that day (`active`), those who first played that day
  (`newPlayers`), and how many of those played again 1, 7 and 30 days on.

```json
200 {"days":[{"day":"2026-10-05","active":0,"newPlayers":0,"d1":null,"d7":null,"d30":null},
 {"day":"2026-10-04","active":0,"newPlayers":0,"d1":null,"d7":null,"d30":null},
 {"day":"2026-10-03","active":0,"newPlayers":0,"d1":0,"d7":null,"d30":null}]}
```

- `/admin/stats/features`: of a day's new players, those who used each feature that day, and
  of them who came back.

```json
200 {"days":[{"day":"2026-10-05","newPlayers":0,"features":{
 "queued":{"players":0,"d1":null,"d7":null,"d30":null},"bought":{"players":0,"d1":null,"d7":null,"d30":null},
 "boosted":{...},"tournament":{...},"friend":{...},"team":{...}}}, ...]}
```

- `/admin/stats/funnel`: of the accounts made each day, how many got how far.

```json
200 {"days":[{"day":"2026-10-05","registered":4,"guests":0,"played":0,"returned":0,"level5":0,"rated":0,"bought":1,"paid":1},
 {"day":"2026-10-04","registered":0,"guests":0,"played":0,"returned":0,"level5":0,"rated":0,"bought":0,"paid":0}]}
```

(The run registered four players and played no match: they count in the funnel, not as
`active` or `newPlayers`, which come from match results.)

### `GET /admin/stats/guests`

```json
200 {"players":4,"guests":0,"inactive":0}
```

`guests`: accounts never upgraded; `inactive`: guests made over 90 days ago, unseen for 90.

### `GET /admin/seasons`

The 100 newest, with when each was placed, paid and reset.

```json
200 {"seasons":[{"id":1,"startsAt":"2026-10-05T22:29:40.672Z","endsAt":"2026-11-01T00:00:00Z","placedAt":null,"paidAt":null,"resetAt":null}]}
```

## 4. Acting

### `POST /admin/notice`: a line to every connected player

`text` 1 to 200 characters, no control or direction characters. Pushed as `evt.notice` to
every lobby connection; `gateways` is how many gateways took it.

```http
POST /admin/notice
{"text": "Maintenance at 02:00 UTC, about ten minutes.", "reason": "planned maintenance"}
```
```json
202 {"gateways":1}
```

400 `invalid_text`; 400 `no_reason`.

### `POST /admin/tournaments`: make a tournament

| Field | Rule |
|---|---|
| `name` | 1 to 64 characters |
| `maxEntries` | 2 to 32 (a round robin: 2 to 8) |
| `registrationEnds` | an ISO-8601 instant in the future |
| `startsAt` | not before `registrationEnds` |
| `roundMinutes` | 1 to 60 |
| `prizes` | three whole numbers of coins: 1st, 2nd, 3rd (each semi-final's loser) |
| `mode` | `duel` (default) or `teams` |
| `format` | `elimination` (default) or `round_robin` |

```http
POST /admin/tournaments
{"reason": "the weekly cup", "name": "Weekly Cup", "maxEntries": 8,
 "registrationEnds": "2026-10-06T00:30:04Z", "startsAt": "2026-10-06T00:35:04Z",
 "roundMinutes": 10, "prizes": [3000, 1500, 500], "mode": "duel", "format": "elimination"}
```
```json
200 {"id":1}
```

A rule broken: 400 `invalid_tournament`, its message naming the field, for example
`maxEntries from 2 to 32`. There is no call to cancel one: the worker cancels a tournament
that has fewer than two entries when registration ends.

### `POST /admin/players/{id}/kick`

Takes the player out of their match in every arena. Not remembered: they may join again at
once.

```http
POST /admin/players/3/kick
{"reason": "test of the kick"}
```
```json
202 {"playerId":3,"arenas":1}
```

### `POST /admin/players/{id}/ban`, `POST /admin/players/{id}/unban`

A ban without `until` lasts until lifted; with `until` (an instant in the future) it is a
suspension that lapses by itself. Either ends every session of the player, pushes them
`evt.session.revoked`, and takes them out of every arena, which refuses their tickets for a
minute.

```http
POST /admin/players/3/ban
{"reason": "test of a suspension", "until": "2026-10-06T22:30:04Z"}
```
```json
200 {"playerId":3,"status":"suspended","sessionsEnded":1,"until":"2026-10-06T22:30:04Z"}
```

Their login meanwhile: `403 {"code":"banned","message":"this account cannot play"}`.

```http
POST /admin/players/3/unban
{"reason": "end of the test"}
```
```json
200 {"playerId":3,"status":"active","sessionsEnded":0}
```

| Status | code | When |
|---|---|---|
| 400 | `bad_until` | `until is an ISO-8601 instant in the future, as 2026-10-01T00:00:00Z` |
| 404 | `no_such_player` | `no account <id>` |
| 503 | `sessions_not_ended` | the ban is recorded but the store did not answer: call again to end the sessions |
| 503 | `not_taken_out` | the ban is recorded and the sessions ended, but the arenas could not be told: the player may play on in a match; call again (O-36) |

### `POST /admin/payments/{orderId}/refund`, `POST /admin/players/{id}/refund-debt`

A refund takes back the order's gems (and its first-order bonus) as far as the balance allows;
what was already spent becomes a debt, and the player can place no order until support clears
it.

```http
POST /admin/payments/2a0a6b2f-5ad7-47d5-8077-a72b03621e1c/refund
{"reason": "test of a refund"}
```
```json
200 {"orderId":"2a0a6b2f-5ad7-47d5-8077-a72b03621e1c","playerId":1,"taken":330,"debt":670}
```

The player's next order: `409 {"code":"refund_debt","message":"a refunded purchase's gems were spent: support clears the debt before another order"}`.

```http
POST /admin/players/1/refund-debt
{"reason": "debt settled with support"}
```
```json
200 {"playerId":1,"cleared":670}
```

Refund errors: 404 `no_such_order`; 409 `not_paid` (pending, declined, expired, or refunded
already).

### `POST /admin/rooms/{arena}/{room}/close`

Closes a room: its players are kicked, and a match closed so is cut short and not rated.
`heard` is whether the arena got the command (1), or was not listening (0).

```http
POST /admin/rooms/arena-1/room-1/close
{"reason": "test of a room close"}
```
```json
202 {"heard":1}
```

404 `no_such_arena` for an arena not live. The room is not checked: an unknown one is only
logged by the arena.

### `POST /admin/seasons/end`

Ends the season now; the worker places, pays and resets it within the minute.

```http
POST /admin/seasons/end
{"reason": "test of a season end"}
```
```json
200 {"season":1,"endsAt":"2026-10-05T22:30:06.068Z"}
```

Again before the next season starts:
`409 {"code":"already_ended","message":"the season has ended; the worker is closing it"}`.

## 5. What it does not do

No call lists or searches players, cancels a tournament, or rebuilds a board (that is the
`backend-leaderboard-rebuild` unit). A player is named by their id, from the support case or
the database.
