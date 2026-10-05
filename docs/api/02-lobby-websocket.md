# The lobby WebSocket

A player's live connection between matches, held by `gateway`: what the platform pushes (a
match found, a party's changes, a friend's request, an operator's notice), and the lobby's own
requests (queue, party, accept a match), which the gateway forwards to the platform. Every
frame shown is real, from [`lobby-example.mjs`](lobby-example.mjs) run on 2026-10-06 against a
stack built from this repository: two players, Bob (6) and Cy (7). Its full output is §9.

The match itself is not here: it is a TCP connection to the arena, with its own binary
protocol ([02-networking](../detailed-design/02-networking.md)).

## 1. Where

| Where | URL |
|---|---|
| Production, through nginx (TLS) | `wss://<name>/lobby`, for example `wss://a.example.com/lobby` |
| One machine, straight to `gateway` | `ws://127.0.0.1:8081/lobby` (`GATEWAY_PORT`: 8090 under systemd; the examples ran on 8181) |

No subprotocol, no compression; a query string is accepted and ignored. Anything that is not
a WebSocket upgrade on `/lobby` is an empty 404, and the gateway has no `/health`.

## 2. Frames

Text frames, one JSON object each, at most 16 KiB.

| Direction | Form |
|---|---|
| Client to gateway | `{"t": "<type>", "id": <number>, "d": {...}}`: `id` optional (it is echoed), `d` optional |
| A reply | `{"t": "<type>.ok", "id": <the same>, "d": {...}}` (no `id` when the request had none) |
| An error | `{"t": "error", "id": <the same>, "d": {"code": "...", "message": "..."}}` |
| A push | `{"t": "evt.<...>", "d": {...}}`, never an `id` |

Replies and pushes share the socket and may arrive in either order: match a reply by its
`id`, and treat each push by its `t`.

## 3. Authenticate: the first message

The session token from `POST /v1/sessions` ([01](01-http-api.md) §2), sent as the first
message within **5 seconds** of connecting. There is no other way (no header, no query).

```
Bob -> {"t":"auth","id":2,"d":{"token":"GNyys0DsEmO7B5rbn6DM1iAF09VKhE8oOm3E2mfPaz0"}}
Bob <- {"t":"auth.ok","id":2,"d":{"playerId":6}}
```

Before it, anything else is refused and the connection stays:

```
Bob -> {"t":"queue.join","id":1,"d":{"mode":"duel"}}
Bob <- {"t":"error","id":1,"d":{"code":"not_authenticated","message":"send auth first"}}
```

A token that is no live session closes the connection: log in again, do not retry it.

```
Dee -> {"t":"auth","id":1,"d":{"token":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}}
Dee <- {"t":"error","id":1,"d":{"code":"invalid_session","message":"log in again"}}
Dee == closed: 1000 Bye
```

One connection a player: the same player authenticating again takes over, and the older
connection is told so and closed. Do not reconnect it.

```
Cy2 -> {"t":"auth","id":1,"d":{"token":"an5AI7pQGV6imvkuAFjJyRRCRrps_mTSO3sKTl6wtNU"}}
Cy  <- {"t":"evt.session.replaced","d":{}}
Cy2 <- {"t":"auth.ok","id":1,"d":{"playerId":7}}
Cy  == closed: 1000 Bye
```

The session is not checked again on the connection; the platform checks it on every
forwarded call, and a 401 there closes the connection.

## 4. What a client sends

| `t` | `d` | Does | Reply `d`: as the HTTP route answers |
|---|---|---|---|
| `auth` | `{"token"}` | §3 | `{"playerId"}` |
| `ping` | | the heartbeat: send something at least every 30 s | `{}` |
| `match.request` | | a seat in the public arena (`POST /v1/match-requests`) | the grant |
| `queue.join` | `{"mode"}` | `POST /v1/queue` | `{"state":"queued",...}` |
| `queue.leave` | | `DELETE /v1/queue` | `{"state":"none"}` |
| `match.accept` | `{"matchUid"}` | `POST /v1/queue/accept` | the queue state |
| `match.decline` | `{"matchUid"}` | `POST /v1/queue/decline` | the queue state |
| `party.invite` | `{"playerId"}` | `POST /v1/party/invite` | the party |
| `party.accept` | `{"partyId"}` | `POST /v1/party/accept` | the party |
| `party.leave` | | `POST /v1/party/leave` | the party (ended) |
| `party.kick` | `{"playerId"}` | `POST /v1/party/kick` | the party |
| `party.say` | `{"phraseId"}` | `POST /v1/party/say` | the party |

The fields and refusals are those of the HTTP routes ([01](01-http-api.md) §6, §7). A refusal
keeps the platform's `code`; its message is always `platform refused the request`:

```
Bob -> {"t":"party.say","id":6,"d":{"phraseId":99}}
Bob <- {"t":"error","id":6,"d":{"code":"unknown_phrase","message":"platform refused the request"}}
```

Everything else (the shop, friends, teams, a sandbox...) is HTTP only.

## 5. A whole session

**A ping:**

```
Bob -> {"t":"ping","id":3}
Bob <- {"t":"ping.ok","id":3,"d":{}}
```

**A party.** Bob invites Cy; Cy is pushed the invitation and accepts; both are pushed the new
party; Bob says a phrase; Bob leaves, which ends a party of two.

```
Bob -> {"t":"party.invite","id":7,"d":{"playerId":7}}
Cy  <- {"t":"evt.party.invite","d":{"partyId":"01M4730Q6MXDJ1S7285B7NWGCM","from":6,"fromName":"Bob"}}
Bob <- {"t":"party.invite.ok","id":7,"d":{"partyId":"01M4730Q6MXDJ1S7285B7NWGCM","leader":6,"members":[{"playerId":6,"name":"Bob"}],"version":1}}
Cy  -> {"t":"party.accept","id":2,"d":{"partyId":"01M4730Q6MXDJ1S7285B7NWGCM"}}
Bob <- {"t":"evt.party.update","d":{"partyId":"01M4730Q6MXDJ1S7285B7NWGCM","leader":6,"members":[{"playerId":6,"name":"Bob"},{"playerId":7,"name":"Cy"}],"version":2}}
Cy  <- {"t":"evt.party.update","d":{"partyId":"01M4730Q6MXDJ1S7285B7NWGCM","leader":6,"members":[{"playerId":6,"name":"Bob"},{"playerId":7,"name":"Cy"}],"version":2}}
Cy  <- {"t":"party.accept.ok","id":2,"d":{"partyId":"01M4730Q6MXDJ1S7285B7NWGCM","leader":6,"members":[{"playerId":6,"name":"Bob"},{"playerId":7,"name":"Cy"}],"version":2}}
Bob -> {"t":"party.say","id":8,"d":{"phraseId":3}}
Cy  <- {"t":"evt.party.said","d":{"from":6,"name":"Bob","phraseId":3}}
Bob <- {"t":"evt.party.said","d":{"from":6,"name":"Bob","phraseId":3}}
Bob <- {"t":"party.say.ok","id":8,"d":{"partyId":"01M4730Q6MXDJ1S7285B7NWGCM","leader":6,"members":[{"playerId":6,"name":"Bob"},{"playerId":7,"name":"Cy"}],"version":2}}
Bob -> {"t":"party.leave","id":9}
Cy  <- {"t":"evt.party.update","d":{"partyId":null,"leader":0,"members":[],"was":"01M4730Q6MXDJ1S7285B7NWGCM","version":3}}
Bob <- {"t":"evt.party.update","d":{"partyId":null,"leader":0,"members":[],"was":"01M4730Q6MXDJ1S7285B7NWGCM","version":3}}
Bob <- {"t":"party.leave.ok","id":9,"d":{"partyId":null,"leader":0,"members":[],"was":"01M4730Q6MXDJ1S7285B7NWGCM","version":3}}
```

Keep the party with the highest `version` seen: a reply and a push of the same change can come
in either order.

**A seat in the public arena:**

```
Bob -> {"t":"match.request","id":10}
Bob <- {"t":"match.request.ok","id":10,"d":{"arenaHost":"127.0.0.1","arenaPort":9021,"ticketId":"FOtirryJy3sDqQntfNTLlg","tls":false}}
```

**A duel**, from the queue to the arena. Both queue; within a second the matcher asks both
about one match (`seconds` to answer); both accept; each is pushed its own ticket.

```
Bob -> {"t":"queue.join","id":11,"d":{"mode":"duel"}}
Bob <- {"t":"queue.join.ok","id":11,"d":{"state":"queued","mode":"duel","waitedSeconds":0}}
Cy  -> {"t":"queue.join","id":3,"d":{"mode":"duel"}}
Cy  <- {"t":"queue.join.ok","id":3,"d":{"state":"queued","mode":"duel","waitedSeconds":0}}
Bob <- {"t":"evt.match.ready","d":{"matchUid":"01M4730R905V76D3SE5WW3Z89K","mode":"duel","seconds":10}}
Cy  <- {"t":"evt.match.ready","d":{"matchUid":"01M4730R905V76D3SE5WW3Z89K","mode":"duel","seconds":10}}
Bob -> {"t":"match.accept","id":12,"d":{"matchUid":"01M4730R905V76D3SE5WW3Z89K"}}
Bob <- {"t":"match.accept.ok","id":12,"d":{"state":"confirming","mode":"duel","waitedSeconds":0,"matchUid":"01M4730R905V76D3SE5WW3Z89K","secondsLeft":10}}
Cy  -> {"t":"match.accept","id":4,"d":{"matchUid":"01M4730R905V76D3SE5WW3Z89K"}}
Cy  <- {"t":"match.accept.ok","id":4,"d":{"state":"confirming","mode":"duel","waitedSeconds":0,"matchUid":"01M4730R905V76D3SE5WW3Z89K","secondsLeft":10}}
Bob <- {"t":"evt.match.found","d":{"arenaHost":"127.0.0.1","arenaPort":9021,"ticketId":"afJoJW6NgLV6uaIw_XjwdA","tls":false,"mode":"duel"}}
Cy  <- {"t":"evt.match.found","d":{"arenaHost":"127.0.0.1","arenaPort":9021,"ticketId":"5u1-XrzDThcE-ITT43ulKQ","tls":false,"mode":"duel"}}
```

Each now opens TCP to the arena (TLS if `tls`) and sends `Join` with its ticket within 60
seconds. A client that missed the push finds the grant in `GET /v1/queue`. Here they left
instead, which drops the grants:

```
Bob -> {"t":"queue.leave","id":13}
Bob <- {"t":"queue.leave.ok","id":13,"d":{"state":"none"}}
```

**An operator's notice** reaches every connection:

```
Cy  <- {"t":"evt.notice","d":{"text":"Maintenance at 02:00 UTC, about ten minutes."}}
Bob <- {"t":"evt.notice","d":{"text":"Maintenance at 02:00 UTC, about ten minutes."}}
```

## 6. Pushes

Delivered at most once, to a connected player only: anything that matters can also be
fetched over HTTP, which is the answer to a missed push and to `evt.resync`. The run saw the
match, party, notice and replaced pushes (§3, §5); the others are given as the code writes
them.

| `t` | `d` | When | Fetch instead |
|---|---|---|---|
| `evt.match.ready` | `{"matchUid","mode","seconds":10}` | the matcher asks about a match: answer within `seconds` | `GET /v1/queue` (`confirming`) |
| `evt.match.found` | `{"arenaHost","arenaPort","ticketId","tls","mode"}` | all accepted, or a sandbox opened: the grant | `GET /v1/queue` (`matched`) |
| `evt.queue.update` | `{"state":"queued","mode"}` or `{"state":"none","mode":null}` | the party's leader queued it or took it out; a match called off; dropped from the queue | `GET /v1/queue` |
| `evt.party.invite` | `{"partyId","from","fromName"}` | invited to a party (60 s to accept) | |
| `evt.party.update` | the party, or its ended form | a member joined, left or was kicked | `GET /v1/party` |
| `evt.party.said` | `{"from","name","phraseId"}` | a member said a phrase | |
| `evt.team.update` | `{"team": <the team> or null}` | the team changed; `null` to one who is out of it | `GET /v1/teams/mine` |
| `evt.friend.request` | `{"playerId","name"}` | someone asked to be a friend | `GET /v1/friends` |
| `evt.friend.accepted` | `{"playerId","name"}` | a request was accepted | `GET /v1/friends` |
| `evt.inbox` | `{}` | a new inbox item: fetch it | `GET /v1/inbox` |
| `evt.rewards` | `{"matchUid","mode","placement","coins","xp","ratingDelta","gems","achievements":[],"goals":[],"goalCoins","pass":{"points","tier","coins","gems","items":[]}}` | a match's result was paid | `GET /v1/inventory` |
| `evt.tournament.match` | `{"tournamentId","round","arenaHost","arenaPort","ticketId","tls","mode"}` | a tournament's match is ready | `GET /v1/tournaments/{id}/match` |
| `evt.notice` | `{"text"}` | an operator's notice, to everyone | |
| `evt.session.revoked` | `{"reason":"banned"}` or `"suspended"` | the account was banned: the connection closes; do not reconnect | |
| `evt.session.replaced` | `{}` | the player connected elsewhere on this gateway: this connection closes; do not reconnect | |
| `evt.resync` | `{}` | pushes were lost (a slow connection, or the gateway's link to the store came back): fetch the queue, the party and a tournament match again | |

## 7. Errors from the gateway itself

| `code` | When | The connection |
|---|---|---|
| `not_authenticated` | anything before `auth.ok` | stays |
| `already_authenticated` | `auth` again: `{"code":"already_authenticated","message":"this connection is already a player"}` | stays |
| `auth_in_progress` | `auth` while the first is being checked | stays |
| `no_token` | `auth` without `d.token` | stays |
| `invalid_session` | the token names no session | **closed** |
| `auth_timeout` | no `auth.ok` 5 s after connecting | **closed** |
| `unknown_type` | `{"code":"unknown_type","message":"no such message: dance"}` | stays |
| `bad_json` | a frame that is not JSON: `{"t":"error","d":{"code":"bad_json","message":"that was not a JSON object"}}` (no `id`: none could be read) | stays |
| `no_type` | JSON without `t` (an array, a number...) | stays |
| `text_only` | a binary frame | stays |
| `rate_limited` | more than 20 frames in a second | **closed**, 1008 |
| `internal` | the platform or the store did not answer: try again | stays (closed if the registration itself failed) |
| the platform's code | a forwarded call refused; a 401 also closes | stays |

## 8. Limits, heartbeat, closing

| Rule | Value |
|---|---|
| Authenticate | within 5 s of connecting |
| Heartbeat | send something (`ping`) at least every 30 s; **120 s** without a frame closes the connection, 1000 `idle` |
| Rate | 20 frames in any second, of every kind; the 21st: `rate_limited`, then Close **1008** `too many messages` |
| Size | 16 KiB a frame and a message; over it, the connection closes (Close 1009 by Netty's code; not observed here) |
| A slow reader | pushes held 16 deep while the socket is full; past that, one `evt.resync` replaces them; 30 s full closes it |

The gateway's own closes (invalid session, replaced, revoked, auth timeout) are Close **1000**
with reason `Bye`, after the `error` or the push that says why (seen in §3). After a close
that was not asked for, reconnect with a backoff, authenticate again, and fetch what may have
been missed (§6), except after `evt.session.replaced` or `evt.session.revoked`.

## 9. Trying it

**The walkthrough script**, Node 22 or later, nothing to install. It registers two players,
then runs everything in §3 and §5 and checks each answer; exit 1 if any differs.

```bash
node docs/api/lobby-example.mjs http://127.0.0.1:8080 ws://127.0.0.1:8081/lobby
node docs/api/lobby-example.mjs https://a.example.com wss://a.example.com/lobby
# with an operator's notice too: the admin API's URL and secret
node docs/api/lobby-example.mjs http://127.0.0.1:8080 ws://127.0.0.1:8081/lobby http://127.0.0.1:9120 "$(cat admin-token)"
```

It prints every frame, `->` sent and `<-` received, and ends `--- every answer as expected`.

**In Postman**: a collection exported as a file cannot hold WebSocket requests, so make one by
hand. New, WebSocket; URL `ws://127.0.0.1:8081/lobby`; Connect; send
`{"t":"auth","id":1,"d":{"token":"<a token from Log in A in the collection>"}}`, then any
message of §4, for example `{"t":"queue.join","id":2,"d":{"mode":"duel"}}`. A second
connection, as another player, is needed to see a party or a duel through.
