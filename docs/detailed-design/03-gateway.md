# 03 — Gateway

The client's connection to everything that is not a match. It terminates the
lobby connection, authenticates it, forwards requests to `platform`, and
delivers pushes back.

It is deliberately thin: **the gateway holds connections and makes no product
decisions.** Every rule about what a player may do lives in `platform`, so the
gateway can be restarted, scaled or replaced without touching product rules.

## 1. What it is not

**Match traffic never passes through the gateway**
([D-5](../architecture/03-decision-log.md#d-5--match-traffic-never-passes-through-nginx-or-the-gateway)).
The client connects to an arena directly, on a different socket, with a
different protocol ([02-networking](02-networking.md)). At 50 000 players that
traffic is ~1.1 Gbit/s; proxying it would double internal network load and add
a hop to the one path that cannot afford it.

A player therefore has **two sockets** while in a match: the lobby connection
to a gateway, and the match connection to an arena. They fail independently,
which is a feature — losing the lobby connection does not interrupt play.

## 2. Position and shape

```
client ──WSS /lobby──► nginx ──WS──► gateway ──► platform (HTTP): match requests
                                        │
                                        └──► j-redis: session lookup, push channel
client ──HTTPS /v1/──► nginx ──HTTP──► platform: login, registration, leaderboards, the shop
```

nginx terminates TLS and load-balances across the gateways on that machine.
One gateway per machine, three in total, all three advertised to the client,
which retries the next on failure
([D-13](../architecture/03-decision-log.md#d-13--the-client-holds-an-endpoint-list-no-vip)).
Each machine is one name, `a.<domain>` for machine A, and its certificate
names it ([operations/01 §11](../operations/01-deploy.md#11-the-certificate)).
**The client uses `https://` and `wss://` only**, verifies against the device's
trust store, pins nothing, and on a certificate error tries the next endpoint
and then shows an error, never a plaintext fallback. Port 80 refuses
everything but the CA's challenge, so a client configured with `http://` fails
at once rather than sending passwords in the clear.

| Property | Value |
|---|---|
| Connections per instance | ~17 000 at 50 000 CCU across three |
| Memory | ~8 KB per idle connection → ~140 MB, inside the 3 GB heap |
| Threads | Netty: 1 boss and 2 worker event loops; `platform`'s replies arrive on the HTTP client's threads and are handed back to the connection's event loop |
| State | The connection table, and nothing else that matters |

The gateway is **stateless in the sense that matters**: losing one costs its
clients a reconnect, not data. That is why it is the component with no
failover procedure.

## 3. Lobby protocol

JSON over WebSocket, in contrast to the hand-packed binary of the match
protocol. The reasoning is the inverse of §4 in
[02-networking](02-networking.md#4-the-snapshot): this traffic is low volume
and changes constantly, so legibility and painless evolution beat bytes.

```json
{ "t": "party.invite", "id": 42, "d": { "playerId": 4711 } }
{ "t": "party.invite.ok", "id": 42, "d": { "partyId": "…", "leader": 1, "members": [ … ], "version": 1 } }
{ "t": "evt.match.found", "d": { "arenaHost": "…", "arenaPort": 9001, "ticketId": "…", "tls": true, "mode": "duel" } }
```

`id` correlates a request with its reply. Messages without `id` are server
pushes. Unknown `t` values are answered with an error rather than dropped, so a
newer client talking to an older server gets a clear failure instead of a
timeout. Every message, reply, error code and push as built is listed in
[the gateway's README](../../backend/gateway/README.md#the-lobby-protocol), and
the flows are drawn in [diagrams/03](../diagrams/03-lobby-and-store.md).

**A client sends something at least every 30 s**; `{"t":"ping"}`, answered with
`ping.ok`, when it has nothing else to say. A connection silent for 120 s is
closed with a WebSocket close frame, reason `idle`, so that a gone phone stops
holding a socket; nginx's 150 s timeout stands behind it. This was not in the
contract until 2026-09-26, and nothing closed a silent socket
([P-20](../defects.md#2-protocol--the-client-contract)).

**Implemented** (`gateway/GatewayServer`, `LobbyHandler`, `ConnectionRegistry`,
`PlatformClient`): the WebSocket endpoint at `/lobby`, the auth handshake with
its five-second deadline, `match.request` forwarded to `platform`, the
connection registry, per-connection rate limiting, and one connection per
player. Verified with the JDK's WebSocket client across four processes —
register and log in over HTTP, then authenticate and get a ticket for a live
arena over the socket.

**`queue.join` and `queue.leave`** are forwarded to `platform` as
`match.request` is ([04 §4](04-platform-services.md#4-matchmaking)), and so are
**`party.invite`, `party.accept`, `party.leave` and `party.kick`** (built
2026-09-29), each with only the field it takes: `playerId` or `partyId`;
**`party.say`** with its `phraseId` (built 2026-09-29,
[01 §9](01-arena.md#phrases-designed-2026-09-29-plan-item-8)); and
**`match.accept` and `match.decline`** with the `matchUid` of an
`evt.match.ready`, to `POST /v1/queue/accept` or `/decline`.

**Pushes are built (2026-09-27)**, with matchmaking as their first producer
(§5; [04 §4](04-platform-services.md#4-matchmaking)). A team's changes are
pushed since 2026-10-01, `evt.team.update`
([04 §2](04-platform-services.md#team-events-pushed-designed-2026-10-01-plan-item-39));
a team's own actions (invitations, applications, roles) are HTTPS calls to
`platform`, not lobby messages.
A push has no `id`: it answers nothing.

## 4. Connection lifecycle

```
1. connect          nginx → gateway, WebSocket upgrade
2. auth             client sends { "t": "auth", "d": { "token": "…" } } within 5 s
                    gateway: HGET sess:{token} playerId   (j-redis)
                    invalid or expired → close with a typed reason, no retry loop
                    not 43 base64url characters → the same, and no HGET (S-20)
3. register         SET conn:{playerId} {gatewayId}#{nonce} EX 60
                    refreshed every 20 s with SET NX + EXPIRE: never overwritten
                    gateway adds playerId → Channel to its local table
                    auth.ok once the SET is acknowledged; if it fails, internal
                    and the connection closed (T-39)
4. serve            requests forwarded to platform; pushes delivered (§5)
5. disconnect       drop from the table; DEL conn:{playerId} only while it still
                    holds this connection's own value (WATCH, compare, MULTI/DEL)
```

**"If still ours" is decided by the value, not by the gateway.** A player moving
between gateways (Wi-Fi to mobile data) registers at the new one while the old
one still holds a socket it will only find dead later. Written as the bare
gateway id and deleted unconditionally, the old gateway undid the move three
ways: its late cleanup deleted the new entry, its refresh re-wrote the entry for
the socket it still believed in, and its shutdown deleted everything it had held
([T-6](../defects.md#4-concurrency)). Now each registration's value is its own,
cleanup deletes only that value, refresh cannot take an entry back, and shutdown
deletes nothing and lets entries expire. An auth that is still being looked up
when the connection closes is never registered ([T-2](../defects.md#4-concurrency)).

**`auth.ok` means the player can be reached** (designed 2026-10-02, plan item
60 (c)). The gateway answered it as it sent the registration, so a push sent the
moment it arrived could look the registration up first, find none, and go
nowhere ([T-39](../defects.md#4-concurrency)). It answers once the store has
acknowledged the write: one round trip to the store, once a connection. A write that fails is answered `internal` and the connection
closed, as a lost connection is: the client connects again, rather than sit in a
lobby nothing can reach.

**The five-second auth deadline matters.** An unauthenticated connection costs
memory and a file descriptor and can be opened by anyone, so it must not be
able to linger. Connections that fail auth are closed with a reason code the
client can act on — a bad token means log in again, not reconnect.

**A token is checked for its form before it names a key** (2026-10-04,
[S-20](../defects.md#5-security-and-input)). `SessionStore` answers "nobody" for
anything but the 43 base64url characters it mints, without a store read, in the
lookup and in a logout alike. Before, a token of `of:42` named `sess:of:42`,
player 42's index of sessions (04 §10): here a lookup of it could fail as
`internal`, and at `platform` a logout with it deleted the index, so a later ban
found no session to end. It is now `invalid_session`, as any unknown token is.

**One lobby connection per player.** A second login publishes a
`evt.session.replaced` to the first and closes it. A push of
`evt.session.revoked`, the admin API's ban (04 §10), is written and then closed the same way. **A client that receives it
does not reconnect**: it was replaced on purpose, and reconnecting would replace
the other device in turn ([P-19](../defects.md#2-protocol--the-client-contract)).
As built this holds within one gateway; a second login through another gateway
leaves the first connection open, receiving nothing, since pushes route to the
newest registration. Enforcing this at the
gateway rather than in `platform` keeps the invariant next to the thing that
can actually see both connections.

## 5. Push routing

`platform` and `worker` need to reach a specific player: a reward landed, an
invite arrived, a match is ready.

The naive design gives every player a j-redis pub/sub channel and has gateways
subscribe per connected player — 50 000 subscriptions, and a resubscribe storm
whenever a gateway restarts.

Instead, **the routing is a lookup plus three channels**:

```
platform:   GET conn:{playerId}          → "gw-2#k3v9q-812"; route by the part before #
            PUBLISH push:gw-2 {"to": playerId, "msg": {"t": "evt.…", "d": {…}}}
gateway-2:  receives, looks up its local table, writes to that Channel
```

Each gateway subscribes to its own channel, `push:{gatewayId}`, when it
starts, and to `push:all` for a notice to everyone (04 §10); the store client
subscribes again after a reconnect, and what was published meanwhile is lost,
which a push may be (below, for what the lobby is told then). **Built** as
`handoff/LobbyPush` for the sender and `GatewayServer`/`ConnectionRegistry` for
the gateway, and tested from a second store client to a real socket. The cost is one `GET`
per push, which is a sub-millisecond operation on a store doing 100 000+ ops/s,
and pushes are not a hot path.

**A stale route is harmless.** If the player reconnected to another gateway,
the old one no longer has the connection and drops the message. Anything the
client must not miss is not delivered this way — it is persisted and fetched on
reconnect ([§7](#7-what-must-not-be-lost)).

### When the subscription is lost (designed 2026-10-01, plan item 50)

The second audit's O-6, O-7 and O-8 ([defects §6](../defects.md#6-operations)):
every push rides on each gateway's subscription, and a subscription could be left
on a store that was no longer the primary, or never made at all.

- **Following the primary is the store client's**, j-redis 2.2.1 (its D-39): a
  server made a replica closes its subscribers' connections, and the client pings
  a subscriber's connection every 5 s, so one whose machine was lost is noticed
  within about 11 s; each then looks for the primary and subscribes again. The
  arena's `arena-admin:` subscription is the same client's, and follows too. And
  what one connection finds, the client's others follow at once (its D-40): the
  connection that writes a player's registration, told nothing by the handover,
  had lost its first writes to `-READONLY` (O-11).
- **Both channels are subscribed before either is waited for**, so `push:all` is
  remembered, and made again on reconnect, whether or not the store answered at
  start (O-7).
- **After the subscription comes back, every connection held here is sent
  `evt.resync`**, through its `Pushes` as after an overflow: what was published
  meanwhile is lost, and the client fetches what it fetches after its own
  reconnect (O-8).
- **Measured.** `backend_gateway_store_subscribed`, 1 while the subscriber's
  connection is up; `backend_gateway_resubscribed_total`; and at each sender,
  `backend_platform_pushes_unheard_total` and `backend_worker_pushes_unheard_total`:
  a push to a player registered on a gateway whose channel nobody heard, which is
  what O-6 looked like from outside. The runbook's promotion checks the first.
- **Drilled.** `FAILOVER=demote` hands the store over as the runbook's planned
  handover does, the old primary demoted rather than killed, and plays the
  scenarios again; a duel needs `evt.match.ready`, the push that was lost.
  Before the fix no subscription was on the new primary and the notice was never
  heard; after it, the gateway's two channels and the arena's were, and both
  scenarios passed (2026-10-01).

**Built 2026-10-01**, both halves: j-redis 2.2.1 (`ClientFailoverTest`: a
subscriber and a blocked reader follow a demotion, a subscriber leaves a primary
that falls silent, and pings nothing while it has no connection; the client's
other connections follow what one of them found, and an idle lease is not lent),
and the gateway (`GatewayServerTest`: the lobby told `evt.resync` after the
subscriber's connection is killed; a gateway started with the store down hears
`push:all` once it is up), `LobbyPushTest` for the unheard count.

## 6. Rate limiting and abuse

Two independent limits, because they stop different things:

| Limit | Scope | Purpose |
|---|---|---|
| 20 frames in any one-second window, of every kind; the 21st ends the connection: `rate_limited`, then a Close frame (1008) | per connection | A malfunctioning or modified client |
| 30 attempts/min · 10 attempts/15 min | per source address · per account | Password guessing, and one address spending the Argon2 budget |

The frame limit is kept in memory (the design said a token bucket with a burst
of 40; what is built is stricter): the times of a connection's last 20 frames,
so it holds over any one-second window, not only over fixed ones, which let 40
through across a boundary. It counts every frame, pings, binary frames and the
fragments of a message included; counting whole text messages only, as it
first did, left the rest unlimited. **It closes as the protocol does**
(2026-09-29, [P-31](../defects.md#2-protocol--the-client-contract)): the
refusal, then a WebSocket Close frame, 1008, then the connection is kept, its
input read and dropped, until the client's own Close or for two seconds. Closed
at once with the flood unread, the socket could end in a reset, and to a
WebSocket client a bare end of stream is an error: one dropped the messages it
had not handed on, the refusal among them. The gateway's other closes (the
authentication deadline, an invalid session, a replaced or revoked one, a 401
from `platform`) pass through Netty's WebSocket protocol handler, which writes a
Close frame of its own, 1000 with the reason `Bye`, before it ends the socket (its
default, `sendCloseFrame`); none of them follows a flood, and none has been seen
to lose its message. It is per gateway, and deliberately
**not** shared state: a distributed rate limiter would add a round trip to every
message to defend against an attacker who can already open connections to all
three gateways anyway.

**The login limits are not the gateway's.** This section first put them here,
at ~5/min per account and per address. As built, login and registration are
HTTP calls to `platform` and never pass through the gateway, whose lobby
socket authenticates with the resulting token; so the limits live in `platform`
([04 §1](04-platform-services.md#1-auth-and-sessions)), in front of Argon2,
counted in j-redis so they hold across all three machines. 5/min per address
was also too tight for mobile: carriers put many subscribers behind one public
IPv4 address.

Beyond the limit, the connection is closed rather than throttled. Throttling a
client that is misbehaving leaves it connected and still costing resources.

Other edge concerns stay at nginx, which is better at them: TLS, request size
caps, slowloris timeouts, and IP-level blocking.

## 7. What must not be lost

Pushes are best-effort by design. Anything that matters is **persisted first
and pushed second**:

| Event | Persisted | Push is |
|---|---|---|
| Reward granted | MySQL ledger + inventory | a notification, not the delivery |
| Team invite | MySQL `team_invite` row | a prompt to refetch |
| Match found | j-redis ticket, TTL 60 s | the fast path; the client also polls once on reconnect |
| Party changed | j-redis `party:`, TTL 1 h | a notification; `GET /v1/party` is the truth |
| Party invitation | j-redis `pinv:`, TTL 60 s | the only notice: an invitation missed is asked for again |
| Match found, to answer | j-redis `mmc:` and each `mmp`, 10 s to answer | a prompt; `GET /v1/queue` says `confirming`, with the match and the time left |

So the rule is: **a push tells the client to look, it does not carry the
truth.** A client that reconnects after missing pushes fetches its state and
converges. This is what lets the gateway be stateless and the routing be
best-effort.

## 8. Backpressure and slow clients

It was left for when pushes existed, which they have since 2026-09-27
(`evt.match.found` and the rest): until plan item 35 a push was written as it
came, whatever the connection had not yet sent, so a client that stopped reading
grew the gateway's memory by every push sent it.

Per connection, before writing: if `channel.isWritable()` is false, queue the
push in a small per-connection ring (16 entries). On overflow, drop the oldest
and set a `resync` flag; when the connection drains, send a single
`evt.resync` telling the client to refetch rather than replaying a backlog it
no longer needs.

A connection unwritable for 30 seconds is closed. The mobile client is already
built to reconnect, and holding a dead connection open helps nobody.

**As designed for building (2026-10-01, plan item 35).** Unwritable is Netty's:
more than 64 KiB waiting to go out, past what the socket took. A handler of its
own on each connection, `Pushes`, holds the ring and the clock; a push reaches
it on the connection's own event loop, so nothing about the ring is shared
between threads.

- Held pushes go out in order, flushed together, when the connection drains.
  After an overflow the whole backlog is dropped and only `evt.resync` goes:
  some of it is gone, so the client must fetch the truth anyway, and the rest
  would only be read and thrown away.
- `evt.session.revoked` is never held: written and then closed, or, with the
  connection unwritable, closed at once. The session is already revoked; the
  client finds that out when it next authenticates.
- Replies to the client's own requests are not held: it is waiting for them,
  and its requests are rate-limited (§6). The 30 s close bounds them too.
- The 30 s run from the moment the connection became unwritable, and start
  again each time it does; draining in time cancels the close.
- Counted: `backend_gateway_pushes_total{outcome}` gains `held` and `dropped`,
  and `backend_gateway_slow_closed_total` the connections closed for 30 s
  unwritable. The duration histogram of §10 waited for a measured need; it was
  built with plan item 48, to give the 30 s one.

**The client**: `evt.resync` reaches the app as any push does (`OnPush`), which
fetches what it fetches after a lobby reconnect: the queue, the party, a
tournament match (08 §5).

**A notice for everyone** (04 §10) arrives on `push:all`, which every gateway subscribes to beside its own channel, and goes to each connection through the same `Pushes`.

**Built 2026-10-01** (`gateway/Pushes`, one per connection, between the frame
aggregator and `LobbyHandler`; `ConnectionRegistry.deliver` hands each push to
it on the connection's loop). Tested with a connection made unwritable by hand,
as Netty marks one past its high-water mark: in a unit test with a frozen
clock, and through a running gateway, where seventeen pushes held came out as
one `evt.resync` and the next push went straight through.

## 9. Failure behaviour

| Failure | Effect | Recovery |
|---|---|---|
| Gateway process dies | Its connections drop | Clients retry the next endpoint; systemd restarts it |
| `platform` unreachable | Requests fail with a typed error | Gateway stays up and keeps connections; it does not cascade the failure into a disconnect storm |
| j-redis `session` unreachable | New authentications fail | **Existing connections keep working** — the token was validated once and the playerId is held on the connection |

That last row is deliberate. Re-validating the session on every request would
make a store blip an instant mass disconnection. The cost is that a ban takes
effect on the player's next connect rather than instantly, which is why
`platform` is to publish a `kick` push for immediate enforcement. **Built**
otherwise (2026-09-29, [04 §10](04-platform-services.md#10-admin-api)): a ban
ends every session the player has and their lobby connection, with
`evt.session.revoked`, and an operator's kick takes them out of every arena.

## 10. What to measure

| Metric | Why |
|---|---|
| Connections per instance, and the spread across three | Uneven balance means nginx or the client endpoint list is misbehaving |
| Auth failures by reason | A spike in bad tokens is either a bug or an attack |
| Push route misses (`conn:` lookup empty or stale) | High rates mean the presence TTL is too short |
| Rate-limit closures per minute | Client bug or abuse |
| Request latency to `platform`, p50/p99 | The gateway's own overhead should be invisible next to it. **Designed 2026-10-01** (plan item 46): `backend_gateway_platform_seconds{route}`, a histogram of each call `PlatformClient` makes, from its send to its answer or its failure, by the API's path, a handful fixed in the gateway's code; a call that times out takes a little over its 5 s, so it is counted above the last finite bucket, in `+Inf`, and its failure is logged as before. **Built 2026-10-01**: observed as the call completes, answered or failed, so neither path can miss it |
| Unwritable-connection duration histogram | Mobile network quality, and whether the 30 s cut-off is right. **Designed 2026-10-01** (plan item 48): `backend_gateway_unwritable_seconds{end}`, each spell a lobby connection could take no more (§8), from its becoming unwritable to its end: `drained`, taking more again, or `closed`, closed while still unwritable, by the 30 s rule or by either side. Bounds 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 20 and 30 s; a spell the 30 s rule closes lasts a little over 30 s, so it is counted above the last finite bound, in `+Inf`, and the closed spells at the cut-off are `_count` less the `le="30"` bucket. Timed in `Pushes`, which keeps the 30 s already, by its event loop's clock. A connection that never backs up is never counted: the spells against `backend_gateway_connections` say how many do. Drained spells crowding below 30 s say the cut-off is too short; closed ones at 30 s with few drained near it, that it is not. **Built 2026-10-01** |

**Built** (`GatewayServer.registerMetrics`, and the edge certificate in
`GatewayMain`): `backend_gateway_connections`, `backend_gateway_authenticated_total`,
`backend_gateway_errors_total{code}` (which counts `rate_limited` closures,
among the rest), `backend_gateway_pushes_total{outcome}` (`delivered`,
`not_here` for a player who has moved or gone, `malformed`, `broadcast` for a
notice to every lobby; and `held` and `dropped` for a slow connection, §8),
`backend_gateway_slow_closed_total`, `backend_gateway_platform_seconds{route}`,
`backend_gateway_unwritable_seconds{end}`,
`backend_gateway_store_subscribed`, `backend_gateway_resubscribed_total` (§5)
and `backend_gateway_edge_certificate_expiry_timestamp_seconds`.
Auth failures show as `errors_total{code="invalid_session"}`. Push route misses
are counted here when the route was stale (`not_here`); a lookup that found no
registration at all is the sender's to count, and so is a push to a registered
player that no gateway heard (`backend_platform_pushes_unheard_total`,
`backend_worker_pushes_unheard_total`, §5).
