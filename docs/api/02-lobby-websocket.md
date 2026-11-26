# The lobby WebSocket

This page is the reference for the lobby: a player's live connection between matches. It is for client developers and
the UI team. The process `gateway` holds the connection. Over it, the backend pushes what happens to the player: a
match found, a party's changes, a friend's request, an operator's notice. The client also sends a few requests over
it (the queue, the party, accepting a match), and the gateway forwards them to `platform`. New words are explained in
the [glossary](../glossary.md).

Every frame shown is real. It comes from [`lobby-example.mjs`](lobby-example.mjs), run on 2026-10-06 against a stack
built from this repository, with two players: Bob (player 6) and Cy (player 7). The script's full use is §9. In the
examples, `->` is a frame the client sent, `<-` a frame it received, and `==` the connection's close.

The match itself is not here. A match is a TCP connection to an arena, with its own binary protocol
([02-networking](../detailed-design/02-networking.md)).

## 1. Where

| Where | URL |
|---|---|
| Production, through the front's nginx, with TLS | `wss://<the site's address>/lobby`, for example `wss://203.0.113.10/lobby`. The client trusts the backend's root certificate ([operations/06](../operations/06-certificates.md)) |
| Production without TLS, for a client without TLS ([D-115](../architecture/03-decision-log.md#d-115--a-players-build-may-connect-without-tls-and-the-site-serves-it-on-80-matches-included)) | `ws://<the site's address>/lobby`, on port 80. Anyone on the way can read what crosses it, the session token included |
| One machine, straight to `gateway` | `ws://127.0.0.1:8081/lobby`. The port is `GATEWAY_PORT`: 8081 by default, 8090 under systemd; the examples ran on 8181 |
| A run by hand, through `dev-nginx.sh`'s nginx ([D-92](../architecture/03-decision-log.md#d-92--a-script-for-each-service-of-a-run-by-hand-and-nginx-on-the-sites-ports-in-front-of-it)) | `wss://<address>/lobby`, trusting `work/nginx/ca/ca.pem`; or `ws://<address>/lobby` without TLS, on port 80 ([D-107](../architecture/03-decision-log.md#d-107--developments-80-serves-the-api-and-the-lobby-itself-the-apis-health-under-v1-every-route-checked-through-nginx)) |

The gateway uses no subprotocol and no compression. It accepts a query string on `/lobby` and ignores it. Reached
directly, it also accepts a path below `/lobby` (`/lobby/x`); nginx passes `/lobby` alone. A request that is not a
WebSocket upgrade on `/lobby` gets an empty 404, and the connection is closed. The gateway has no `/health`.

## 2. Frames

Every frame is a text frame that holds one JSON object, of 16 KiB at most.

| Direction | Form |
|---|---|
| Client to gateway | `{"t": "<type>", "id": <number>, "d": {...}}`. `d` is optional. `id` is optional: a whole number that the reply echoes. An `id` of 0, or one that is not a number, counts as no `id` |
| A reply | `{"t": "<type>.ok", "id": <the same>, "d": {...}}`. There is no `id` when the request had none |
| An error | `{"t": "error", "id": <the same>, "d": {"code": "...", "message": "..."}}` |
| A push | `{"t": "evt.<...>", "d": {...}}`. A push never has an `id` |

Replies and pushes share the socket, and they may arrive in either order. So match a reply to its request by `id`, and
handle each push by its `t`. Give each request its own `id`, from 1 up.

## 3. Authenticate: the first message

The first message must be `auth`, with the session token from `POST /v1/sessions` ([01](01-http-api.md) §2). Send it
within **5 seconds** of connecting. There is no other way to authenticate: not by a header, and not by the query.

```
Bob -> {"t":"auth","id":2,"d":{"token":"GNyys0DsEmO7B5rbn6DM1iAF09VKhE8oOm3E2mfPaz0"}}
Bob <- {"t":"auth.ok","id":2,"d":{"playerId":6}}
```

Before `auth.ok`, every other message is refused, and the connection stays open:

```
Bob -> {"t":"queue.join","id":1,"d":{"mode":"duel"}}
Bob <- {"t":"error","id":1,"d":{"code":"not_authenticated","message":"send auth first"}}
```

A token that names no live session closes the connection. Log in again; do not retry the same token.

```
Dee -> {"t":"auth","id":1,"d":{"token":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}}
Dee <- {"t":"error","id":1,"d":{"code":"invalid_session","message":"log in again"}}
Dee == closed: 1000 Bye
```

**One connection a player.** When the same player authenticates again, the new connection takes over. The older
connection is told so with `evt.session.replaced`, and closed. Do not reconnect it.

```
Cy2 -> {"t":"auth","id":1,"d":{"token":"an5AI7pQGV6imvkuAFjJyRRCRrps_mTSO3sKTl6wtNU"}}
Cy  <- {"t":"evt.session.replaced","d":{}}
Cy2 <- {"t":"auth.ok","id":1,"d":{"playerId":7}}
Cy  == closed: 1000 Bye
```

The gateway does not check the session again on an open connection. `platform` checks it on every call the gateway
forwards, and a 401 there closes the connection.

## 4. What a client sends

| `t` | `d` | What it does | The reply's `d` |
|---|---|---|---|
| `auth` | `{"token"}` | authenticates (§3) | `{"playerId"}` |
| `ping` | none | the heartbeat: send something at least every 30 seconds | `{}` |
| `match.request` | none | asks for a seat in the public arena, as `POST /v1/match-requests` | the grant |
| `queue.join` | `{"mode"}` | joins a queue, as `POST /v1/queue` | `{"state":"queued",...}` |
| `queue.leave` | none | leaves the queue, as `DELETE /v1/queue` | `{"state":"none"}` |
| `match.accept` | `{"matchUid"}` | accepts a match found, as `POST /v1/queue/accept` | the queue state |
| `match.decline` | `{"matchUid"}` | declines it, as `POST /v1/queue/decline` | the queue state |
| `party.invite` | `{"playerId"}` | as `POST /v1/party/invite` | the party |
| `party.accept` | `{"partyId"}` | as `POST /v1/party/accept` | the party |
| `party.leave` | none | as `POST /v1/party/leave` | the party (the ended form) |
| `party.kick` | `{"playerId"}` | as `POST /v1/party/kick` | the party |
| `party.say` | `{"phraseId"}` | as `POST /v1/party/say` | the party |

The fields, the answers and the refusals are those of the HTTP routes ([01](01-http-api.md) §6, §7): the reply's `d`
is the HTTP route's answer. A refusal keeps the platform's `code`. Its message is always
`platform refused the request`:

```
Bob -> {"t":"party.say","id":6,"d":{"phraseId":99}}
Bob <- {"t":"error","id":6,"d":{"code":"unknown_phrase","message":"platform refused the request"}}
```

Everything else (the shop, friends, teams, a sandbox and the rest) is over HTTP only.

## 5. A whole session

These examples come from runs that reached the arena directly (`127.0.0.1:9021`, no TLS). Since
[D-92](../architecture/03-decision-log.md#d-92--a-script-for-each-service-of-a-run-by-hand-and-nginx-on-the-sites-ports-in-front-of-it),
a run by hand has nginx in front of its arena, as the site does. Every grant then names `<address>`, port 9001,
`"tls":true` and `"tlsName":"a1.arena.internal"`.

**A ping:**

```
Bob -> {"t":"ping","id":3}
Bob <- {"t":"ping.ok","id":3,"d":{}}
```

**A party.** Bob invites Cy. Cy is pushed the invitation, and accepts. Both are pushed the new party. Bob says a
phrase. Then Bob leaves, which ends a party of two.

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

Keep the party with the highest `version` seen. A reply and a push about the same change can come in either order.

**A seat in the public arena:**

```
Bob -> {"t":"match.request","id":10}
Bob <- {"t":"match.request.ok","id":10,"d":{"arenaHost":"127.0.0.1","arenaPort":9021,"ticketId":"FOtirryJy3sDqQntfNTLlg","tls":false}}
```

**A duel**, from the queue to the arena. Both players join the queue. Within a second, the matcher asks both about one
match; `seconds` is the time they have to answer. Both accept. Each is then pushed a ticket of their own.

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

Each player now connects to the arena (with TLS if `tls` is true) and sends `Join` with their ticket, within 60
seconds ([01](01-http-api.md) §6 says how). A client that missed the push finds the grant in `GET /v1/queue`. Here the
players left instead, which drops the grants:

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

A push is delivered at most once, and only to a player who is connected. So anything that matters can also be
fetched over HTTP. Fetching is the answer to a missed push, and to `evt.resync`. The run saw the match, party, notice
and replaced pushes (§3, §5). The other pushes are given as the code writes them.

| `t` | `d` | When | What to fetch instead |
|---|---|---|---|
| `evt.match.ready` | `{"matchUid","mode","seconds":10}` | the matcher asks about a match: answer within `seconds` | `GET /v1/queue` (`confirming`) |
| `evt.match.found` | `{"arenaHost","arenaPort","ticketId","tls","mode"}`, and `"tlsName"` behind the front ([01 §6](01-http-api.md#6-matchmaking-from-a-request-to-the-arena)) | every player accepted, or a sandbox opened: this is the grant | `GET /v1/queue` (`matched`) |
| `evt.queue.update` | `{"state":"queued","mode"}` or `{"state":"none","mode":null}` | the party's leader queued the party or took it out; a match was called off; the player was dropped from the queue | `GET /v1/queue` |
| `evt.party.invite` | `{"partyId","from","fromName"}` | an invitation to a party (60 seconds to accept) | |
| `evt.party.update` | the party, or its ended form | a member joined, left or was kicked | `GET /v1/party` |
| `evt.party.said` | `{"from","name","phraseId"}` | a member said a phrase | |
| `evt.team.update` | `{"team": <the team>}`, or `{"team": null}` | the team changed; `null` to a player who is out of it | `GET /v1/teams/mine` |
| `evt.friend.request` | `{"playerId","name"}` | someone asked to be a friend | `GET /v1/friends` |
| `evt.friend.accepted` | `{"playerId","name"}` | a request was accepted | `GET /v1/friends` |
| `evt.inbox` | `{}` | a new inbox item: fetch it | `GET /v1/inbox` |
| `evt.rewards` | `{"matchUid","mode","placement","coins","xp","ratingDelta","gems","achievements":[],"goals":[],"goalCoins","pass":{"points","tier","coins","gems","items":[]}}` | the worker paid a match's result | `GET /v1/inventory` |
| `evt.tournament.match` | `{"tournamentId","round","arenaHost","arenaPort","ticketId","tls","mode"}`, and `"tlsName"` behind the front | a tournament's match is ready | `GET /v1/tournaments/{id}/match` |
| `evt.notice` | `{"text"}` | an operator's notice, to everyone | |
| `evt.session.revoked` | `{"reason":"banned"}`, `"suspended"`, `"deleted"` or `"signed_out"` | an operator banned or suspended the account; its player deleted it, here as a guest or at the account service (D-84, D-117); or its password was changed at the account service (`signed_out`: on the device that changed it, connect again with the new session; elsewhere, log in again). The connection closes: do not reconnect with that session | |
| `evt.session.replaced` | `{}` | the player connected again, through this gateway or another. This connection closes: do not reconnect | |
| `evt.resync` | `{}` | pushes were lost (the connection was slow, or the gateway's link to the store came back). Fetch the queue, the party and a tournament match again | |

In `evt.rewards`: `achievements` lists each achievement reached, by id, and its gems are in `gems`. `goals` lists each
daily goal met, and `goalCoins` is their coins, apart from the match's own `coins`. `pass` is the season pass's
points, the tier after, and what the tiers reached paid.

## 7. Errors from the gateway itself

| `code` | When | The connection |
|---|---|---|
| `not_authenticated` | any message before `auth.ok` | stays open |
| `already_authenticated` | `auth` again: `{"code":"already_authenticated","message":"this connection is already a player"}` | stays open |
| `auth_in_progress` | `auth` while the first one is being checked | stays open |
| `no_token` | `auth` without `d.token` | stays open |
| `invalid_session` | the token names no session | **closed** |
| `auth_timeout` | no `auth.ok` 5 seconds after connecting | **closed** |
| `unknown_type` | `{"code":"unknown_type","message":"no such message: dance"}` | stays open |
| `bad_json` | a frame that is not JSON: `{"t":"error","d":{"code":"bad_json","message":"that was not a JSON object"}}`. It has no `id`, as none could be read | stays open |
| `no_type` | JSON without `t` (an array, a number...) | stays open |
| `text_only` | a binary frame | stays open |
| `rate_limited` | more than 20 frames in a second | **closed**, with Close code 1008 |
| `internal` | `platform` or the store did not answer: try again | stays open (closed if the registration itself failed) |
| the platform's code | a forwarded call was refused; a 401 also closes the connection | stays open |

## 8. Limits, heartbeat, closing

| Rule | Value |
|---|---|
| Authenticate | within 5 seconds of connecting |
| Heartbeat | send something (`ping`) at least every 30 seconds. **120 seconds** without a frame from the client closes the connection, with Close code 1000 and the reason `idle` |
| Rate | 20 frames in any second, of every kind. The 21st gets `rate_limited`, then Close **1008** `too many messages` |
| Size | 16 KiB for a frame and for a whole message. Over that, the connection closes (with Close code 1009, by Netty's code; not observed here) |
| A slow reader | while the socket is full, the gateway holds up to 16 pushes. Past that, one `evt.resync` replaces them. When the socket stays full for 30 seconds, the gateway closes it |

The gateway's own closes (an invalid session, a connection replaced or revoked, an authentication timeout) are Close
**1000**, with the reason `Bye`. They come after the `error` or the push that says why (seen in §3).

**After a close the client did not ask for**, reconnect with a backoff, authenticate again, and fetch what may have
been missed (§6). There are two exceptions: after `evt.session.replaced` or `evt.session.revoked`, do not reconnect
with that session. After `signed_out` on the device that changed its password, connect with the session its new access
token gave (D-117).

## 9. Trying it

**The walkthrough script** needs Node 22 or later, and nothing to install. It registers two players, then runs
everything in §3 and §5 and checks each answer. It exits with 1 if any answer differs.

```bash
node docs/api/lobby-example.mjs http://127.0.0.1:8080 ws://127.0.0.1:8081/lobby
NODE_EXTRA_CA_CERTS=ca.pem node docs/api/lobby-example.mjs https://203.0.113.10 wss://203.0.113.10/lobby
NODE_EXTRA_CA_CERTS=work/nginx/ca/ca.pem node docs/api/lobby-example.mjs https://<address> wss://<address>/lobby   # a run by hand, through nginx
# with an operator's notice too: the admin API's URL and secret
node docs/api/lobby-example.mjs http://127.0.0.1:8080 ws://127.0.0.1:8081/lobby http://127.0.0.1:9120 "$(cat admin-token)"
```

It prints every frame, `->` for a frame sent and `<-` for a frame received, and it ends with
`--- every answer as expected`.

**In Postman**: a collection exported as a file cannot hold WebSocket requests, so make one by hand. Choose New, then
WebSocket. Set the URL to `ws://127.0.0.1:8081/lobby`, and Connect. Send
`{"t":"auth","id":1,"d":{"token":"<a token from Log in A in the collection>"}}`, then any message of §4, for example
`{"t":"queue.join","id":2,"d":{"mode":"duel"}}`. To see a party or a duel through, a second connection is needed, as
another player.
