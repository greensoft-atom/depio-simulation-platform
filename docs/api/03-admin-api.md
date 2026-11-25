# The admin API (operators)

This page is the reference for the admin API: what an operator calls to look after the site. It is for operators,
and for developers who drive a test stack. It covers reading the live arenas and rooms, the players' figures by day,
the seasons, the alerts and the watch; and acting: a notice to everyone, a tournament made, a player kicked, banned
or suspended, a payment refunded, a room closed, a season ended, and an alert silenced.

The admin API is a second listener of `platform`. It listens on loopback only, and an operator reaches it over SSH.
Every example below is a real request and its real answer, from the Postman collection's run of 2026-10-06 against a
stack built from this repository ([README](README.md#how-this-was-checked)), unless it says otherwise.

## 1. Reaching it

- **It is off unless it is named.** Set `BACKEND_ADMIN_ADDR=127.0.0.1:9120` and the secret, `BACKEND_ADMIN_TOKEN`,
  both in the platform's settings file, `/etc/backend/platform.env`, as its example says
  ([operations/01 §2](../operations/01-deploy.md#2-conventions),
  [runbook §3a](../operations/02-runbook.md#3a-the-admin-api)). The address must be a loopback address, or the
  platform refuses to start. When the address is set and the secret is not, the platform refuses to start too.
- **The secret** may also come from a file: `BACKEND_ADMIN_TOKEN_FILE` names a file that holds it, and wins over
  `BACKEND_ADMIN_TOKEN`. A run by hand uses this way, with `work/admin-token`
  ([development/03 §5.1](../development/03-build-and-run.md#51-trying-each-interface)).
- **Over SSH**: run `ssh -L 9120:127.0.0.1:9120 <machine>`, then call `http://127.0.0.1:9120/admin/...` from the
  operator's own machine. The API speaks plain HTTP; the SSH tunnel is the encryption.
- **Authentication**: send `Authorization: Bearer <the secret>` with every call. The server compares it in constant
  time.

```http
GET /admin/arenas
Authorization: Bearer not-the-secret
```
```json
401 {"code":"unauthorised","message":"send Authorization: Bearer <the admin secret>"}
```

## 2. Rules for every call

- **Bodies** are JSON, at most 4096 bytes. The server does not check the content type. A larger body is read as an
  empty one, so the call is refused with a 400 (usually `no_reason`).
- **A change needs a reason.** Every `POST` carries `"reason"`, 1 to 200 characters, which is kept in the audit. A
  `GET`, and the `DELETE` of a silence, need none.

```http
POST /admin/players/3/kick
{}
```
```json
400 {"code":"no_reason","message":"say why, in at most 200 characters: it is audited"}
```

- **Calls are audited.** The server writes each call, its body and its outcome to the table `admin_audit` in MySQL,
  refusals and reads included. It does not write: an unknown path (404 `not_found`), a wrong method (405), a bad
  `days` (400), a 500, and a 503 for a call that did nothing. A ban's 503s are written with the ban. A notice is
  written `sent` before it goes out, so a notice that the store then failed to send is in the audit too.
- **Errors** are `{"code","message"}`:

| Status | code | When |
|---|---|---|
| 401 | `unauthorised` | the secret is missing or wrong |
| 404 | `not_found` | `no such call` |
| 405 | `method_not_allowed` | the wrong method; the `Allow` header lists the right one |
| 503 | `storage_unavailable` | `the database did not answer; nothing was done` |
| 503 | `storage_unavailable` | `the store did not answer: call again`, when the store (j-redis) fails. Every call is safe to make again. Only a notice that the store took without answering goes out twice. Until 2026-10-06 this was 500 `internal` (O-36) |
| 500 | `internal` | anything else unexpected |

## 3. Reading

### `GET /admin/arenas`

The live arenas, as an array. An arena's entry lapses 10 seconds after its last announcement.

```json
200 [{"name":"arena-1","host":"127.0.0.1","port":9021,"players":0,"maxPlayers":600,"tls":false,"rooms":1,"maxRooms":4}]
```

This comes from a run that reached the arena directly. Behind nginx (on the site, or in a run by hand since D-92), an
arena announces the site's public address, port 9001 and `"tls":true`.

### `GET /admin/rooms`

Every room of every live arena, as each arena last announced it (up to 3 seconds old). `stage` is `open` for the
public arena. A match's room is `waiting`, `playing` or `over`, and carries its `matchUid`.

```json
200 [{"arena":"arena-1","room":"room-1","players":0,"mode":"ffa","stage":"open"}]
```

### `GET /admin/stats?days=N`, `/admin/stats/features?days=N`, `/admin/stats/funnel?days=N`

These three say whether players come back, day by day (UTC days), newest first. `days` is 1 to 60, and the default
is 14. The query must be `days=N` alone. Anything else gets 400 `invalid_days` (`days is 1 to 60`). A "day N" figure
is `null` until the day N days later has ended.

**`/admin/stats`**: for each day, the players who played that day (`active`), the players who first played that day
(`newPlayers`), and how many of those new players played again 1, 7 and 30 days later (`d1`, `d7`, `d30`).

```json
200 {"days":[{"day":"2026-10-05","active":0,"newPlayers":0,"d1":null,"d7":null,"d30":null},
 {"day":"2026-10-04","active":0,"newPlayers":0,"d1":null,"d7":null,"d30":null},
 {"day":"2026-10-03","active":0,"newPlayers":0,"d1":0,"d7":null,"d30":null}]}
```

**`/admin/stats/features`**: of each day's new players, those who used each feature that day (`players`), and how
many of them came back (`d1`, `d7`, `d30`). The features are `queued`, `bought`, `boosted`, `tournament`, `friend`
and `team`.

```json
200 {"days":[{"day":"2026-10-05","newPlayers":0,"features":{
 "queued":{"players":0,"d1":null,"d7":null,"d30":null},"bought":{"players":0,"d1":null,"d7":null,"d30":null},
 "boosted":{...},"tournament":{...},"friend":{...},"team":{...}}}, ...]}
```

**`/admin/stats/funnel`**: of the accounts made each day, how many have got how far by now.

```json
200 {"days":[{"day":"2026-10-05","registered":4,"guests":0,"played":0,"returned":0,"level5":0,"rated":0,"bought":1,"paid":1},
 {"day":"2026-10-04","registered":0,"guests":0,"played":0,"returned":0,"level5":0,"rated":0,"bought":0,"paid":0}]}
```

| Field | Of the accounts made that day |
|---|---|
| `registered` | all of them, guests included |
| `guests` | those that are still guests, never upgraded |
| `played` | those that have played a match |
| `returned` | those that played again on one of the 7 days after their first day of play |
| `level5` | those at level 5 or more |
| `rated` | those that have played a rated match (duel, ranked free-for-all or team against team) |
| `bought` | those that have bought something in the shop |
| `paid` | those that have a paid order (or one paid and refunded since) |

The run registered four players and played no match. So they count in the funnel, but not as `active` or
`newPlayers`, which come from match results.

### `GET /admin/stats/guests`

```json
200 {"players":4,"guests":0,"inactive":0}
```

`players` is every account. `guests` is the accounts never upgraded. `inactive` is the guests made over 90 days ago
and not seen for 90 days. A guest not seen for 180 days is erased by retention
([D-87](../architecture/03-decision-log.md#d-87--a-guest-nobody-plays-for-180-days-is-erased-by-retention-a-deletion-is-at-once)),
so `inactive` is what the next 90 days will erase.

### `GET /admin/seasons`

The 100 newest seasons, each with when it was placed, paid and reset (`null` until then).

```json
200 {"seasons":[{"id":1,"startsAt":"2026-10-05T22:29:40.672Z","endsAt":"2026-11-01T00:00:00Z","placedAt":null,"paidAt":null,"resetAt":null}]}
```

### `GET /admin/alerts`: what the watches are raising

Every machine's firing alerts, read from the shared store
([08 §5](../operations/08-monitoring.md#5-reading-and-silencing-alerts), 04 §12). Each alert has:

- its `name`;
- its `labels`: `machine`, `job`, `instance`, `mount`, `room`, `peer` and `kind`, those that it has;
- `summary` and `runbook`: the runbook's words, and where to read them;
- `since`: when its condition first held;
- `silenced`: whether a silence covers it.

The example is given from the code.

```json
200 {"alerts":[{"name":"TargetDown","labels":{"instance":"127.0.0.1:9102","job":"worker","machine":"C"},
  "summary":"A process's metrics cannot be read: it is down, or its port is wrong",
  "runbook":"docs/operations/02-runbook.md#5-what-to-watch","since":"2026-10-24T14:02:15Z","silenced":false}]}
```

### `GET /admin/watch`: what each machine's watch last saw

Every machine's last round of its watch, newest first, read from the shared store
([08 §5](../operations/08-monitoring.md#5-reading-and-silencing-alerts), 04 §12). For each machine:

- `machine` and `at`: which machine, and when its round ran;
- `roundSeconds`: how long the round took;
- `targets`: each process it reads, and whether it is `up`;
- `peers`: each other machine, and whether its watch is `up`;
- `disks`, `memory`, `load1m` and `cores`: the machine's disks, memory, load and cores;
- `alertsFiring`: how many alerts that machine has firing.

A machine whose watch has stopped drops out of the list within a minute, and the other machines' `peers` say so. The
example is given from the code.

```json
200 {"machines":[{"machine":"C","at":"2026-10-24T14:02:15Z","roundSeconds":0.15,
  "targets":[{"job":"platform","instance":"127.0.0.1:9101","up":true},{"job":"worker","instance":"127.0.0.1:9102","up":false}],
  "peers":[{"peer":"A","up":true},{"peer":"B","up":true}],
  "disks":[{"mount":"/","freeBytes":53687091200,"sizeBytes":107374182400}],
  "memory":{"freeBytes":8589934592,"sizeBytes":68719476736},"load1m":1.5,"cores":24,"alertsFiring":1}]}
```

## 4. Acting

### `POST /admin/notice`: a line to every connected player

**Body**: `{"text", "reason"}`. `text` is 1 to 200 characters, with no control characters and no direction
characters. **Answer**: 202. The text is pushed as `evt.notice` to every lobby connection. `gateways` is how many
gateways took it.

```http
POST /admin/notice
{"text": "Maintenance at 02:00 UTC, about ten minutes.", "reason": "planned maintenance"}
```
```json
202 {"gateways":1}
```

Errors: 400 `invalid_text`, and 400 `no_reason`.

### `POST /admin/tournaments`: make a tournament

**Body**: the fields below, and `reason`. **Answer**: 200 with the new tournament's id.

| Field | Rule |
|---|---|
| `name` | 1 to 64 characters, none of them a control or direction character |
| `maxEntries` | 2 to 32 (a round robin: 2 to 8, so seven rounds at most) |
| `registrationEnds` | an ISO-8601 instant in the future |
| `startsAt` | an ISO-8601 instant, not before `registrationEnds` |
| `roundMinutes` | 1 to 60 |
| `prizes` | three whole numbers of coins, each 0 to 1 000 000 000: for the 1st, the 2nd and the 3rd places (the 3rd prize goes to each semi-final's loser) |
| `mode` | `duel` (the default) or `teams` |
| `format` | `elimination` (the default) or `round_robin` |

```http
POST /admin/tournaments
{"reason": "the weekly cup", "name": "Weekly Cup", "maxEntries": 8,
 "registrationEnds": "2026-10-06T00:30:04Z", "startsAt": "2026-10-06T00:35:04Z",
 "roundMinutes": 10, "prizes": [3000, 1500, 500], "mode": "duel", "format": "elimination"}
```
```json
200 {"id":1}
```

A broken rule gets 400 `invalid_tournament`, with a message that names the field, for example
`maxEntries from 2 to 32`. There is no call to cancel a tournament. The worker cancels one that has fewer than two
entries when registration ends.

### `POST /admin/players/{id}/kick`

**Body**: `{"reason"}`. **Answer**: 202, with `arenas`, how many arenas heard the command.

It takes the player out of their match, in every arena. It is not remembered: the player may join again at once.

```http
POST /admin/players/3/kick
{"reason": "test of the kick"}
```
```json
202 {"playerId":3,"arenas":1}
```

### `POST /admin/players/{id}/ban`, `POST /admin/players/{id}/unban`

**Body**: `{"reason", "until"?}` for a ban, `{"reason"}` for an unban. **Answer**: 200 with the player's new status
and how many sessions were ended.

- **A ban without `until`** lasts until it is lifted.
- **A ban with `until`**, an instant in the future, is a suspension. It lapses by itself at that time.

Either one ends every session of the player, pushes them `evt.session.revoked`, and takes them out of every arena.
The arenas then refuse that player's tickets for a minute. An unban sets the player `active` again; it ends nothing.

```http
POST /admin/players/3/ban
{"reason": "test of a suspension", "until": "2026-10-06T22:30:04Z"}
```
```json
200 {"playerId":3,"status":"suspended","sessionsEnded":1,"until":"2026-10-06T22:30:04Z"}
```

Meanwhile, the player's login answers `403 {"code":"banned","message":"this account cannot play"}`.

```http
POST /admin/players/3/unban
{"reason": "end of the test"}
```
```json
200 {"playerId":3,"status":"active","sessionsEnded":0}
```

`status` is `banned`, `suspended` or `active`.

| Status | code | When |
|---|---|---|
| 400 | `bad_until` | `until is an ISO-8601 instant in the future, as 2026-10-01T00:00:00Z` |
| 404 | `no_such_player` | `no account <id>` |
| 503 | `sessions_not_ended` | the ban is recorded, but the store did not answer: call again to end the sessions |
| 503 | `not_taken_out` | the ban is recorded and the sessions are ended, but the arenas could not be told: the player may play on in a match. Call again (O-36) |

### `POST /admin/payments/{orderId}/refund`, `POST /admin/players/{id}/refund-debt`

**Body**: `{"reason"}`. **Answer**: 200.

A refund takes back the order's gems, and its first-order bonus, as far as the player's balance allows. What the
player had already spent becomes a debt. While a debt is open, the player can place no order, until support clears
it.

```http
POST /admin/payments/2a0a6b2f-5ad7-47d5-8077-a72b03621e1c/refund
{"reason": "test of a refund"}
```
```json
200 {"orderId":"2a0a6b2f-5ad7-47d5-8077-a72b03621e1c","playerId":1,"taken":330,"debt":670}
```

`taken` is the gems taken back; `debt` is the gems still owed. The player's next order then answers
`409 {"code":"refund_debt","message":"a refunded purchase's gems were spent: support clears the debt before another order"}`.

`refund-debt` clears the debt. `cleared` is the gems of debt cleared.

```http
POST /admin/players/1/refund-debt
{"reason": "debt settled with support"}
```
```json
200 {"playerId":1,"cleared":670}
```

Refund errors: 404 `no_such_order`, and 409 `not_paid` (the order is pending, declined or expired, or it was
refunded already).

### `POST /admin/rooms/{arena}/{room}/close`

**Body**: `{"reason"}`. **Answer**: 202, with `heard`: 1 when the arena got the command, 0 when it was not listening.

It closes a room. The room's players are kicked. A match closed this way is cut short and not rated.

```http
POST /admin/rooms/arena-1/room-1/close
{"reason": "test of a room close"}
```
```json
202 {"heard":1}
```

An arena that is not live gets 404 `no_such_arena`. The room is not checked: the arena only logs a room it does not
know.

### `POST /admin/seasons/end`

**Body**: `{"reason"}`. **Answer**: 200 with the season and its new end.

It ends the current season now. Within the minute, the worker closes it in three steps: it records the season's
final places, pays the season's rewards, and resets it (every rating halfway back to 1 200, every rated count back to
0; [04 §7](../detailed-design/04-platform-services.md#7-leaderboards)). The next season runs to the next season
boundary.

```http
POST /admin/seasons/end
{"reason": "test of a season end"}
```
```json
200 {"season":1,"endsAt":"2026-10-05T22:30:06.068Z"}
```

Called again before the next season starts:
`409 {"code":"already_ended","message":"the season has ended; the worker is closing it"}`.

### `GET /admin/silences`, `POST /admin/silences`: an alert kept unsent

A silence keeps an alert from being sent, for 1 to 168 hours. The alert stays in `GET /admin/alerts`, marked
`silenced`.

- **`POST /admin/silences`**: body `{"alertname", "hours", "reason"}`. **Answer**: 201 with the alert's name and the
  end of the silence.
- **`GET /admin/silences`**: **Answer**: 200 with the silences in force, each with its end and its reason.

The examples are given from the code.

```json
201 {"alertname":"MysqlReplicaDown","until":"2026-10-24T16:02:15Z"}
200 {"silences":[{"alertname":"MysqlReplicaDown","until":"2026-10-24T16:02:15Z","reason":"restarting MySQL on A"}]}
```

| Status | code | When |
|---|---|---|
| 400 | `no_such_alert` | the name is not one of the watch's alerts. The names are in [08 §3](../operations/08-monitoring.md#3-the-alerts) |
| 400 | `invalid_hours` | `hours` is not a whole number from 1 to 168 |
| 400 | `no_reason` | no reason |

### `DELETE /admin/silences/{alertname}`: the silence lifted

No body. **Answer**: 200 when a silence was lifted, 404 `no_such_silence` when there was none. The examples are
given from the code.

```json
200 {"alertname":"MysqlReplicaDown","lifted":true}
404 {"code":"no_such_silence","message":"no silence for MysqlReplicaDown"}
```

## 5. What it does not do

No call lists or searches players, cancels a tournament, or rebuilds a board. A board is rebuilt by the
`backend-leaderboard-rebuild` unit. A player is named by their id, which comes from the support case or from the
database.
