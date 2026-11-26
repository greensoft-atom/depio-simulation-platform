# 03 — Gateway

This page describes the `gateway` process: the server that holds each player's **lobby connection**. It is for
engineers who work on the gateway, on `platform`, or on the client's lobby code. It covers where the gateway sits,
the lobby protocol, a connection from start to end, how pushes reach a player, the limits it enforces, what it does
when other parts fail, and what it measures. Words used here are defined in the [glossary](../glossary.md).

The lobby connection is the client's connection for everything that is not a match. The gateway ends that
connection, checks who the player is, forwards the player's requests to `platform`, and delivers **pushes**
(messages the server sends without being asked) back to the player.

The gateway is deliberately thin: **it holds connections and makes no product decisions.** Every rule about what a
player may do lives in `platform`. So the gateway can be restarted, scaled or replaced without touching any product
rule.

## 1. What it is not

**Match traffic never passes through the gateway**
([D-5](../architecture/03-decision-log.md#d-5--match-traffic-never-passes-through-nginx-or-the-gateway)).
The client connects to an arena on a different socket, with a different protocol
([02-networking](02-networking.md)). On the site, that socket goes through the front's nginx in one of two ways:

| Way | How nginx finds the arena | Decided in |
|---|---|---|
| Over TLS, on port 9001 | nginx ends the TLS and hands the bytes, unread, to the arena that the grant's `tlsName` names | [D-82](../architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name) |
| In clear, on port 80 | the client asks for `GET /match/<tlsName>`; nginx passes the connection to that arena, as it passes the lobby's WebSocket | [D-115](../architecture/03-decision-log.md#d-115--a-players-build-may-connect-without-tls-and-the-site-serves-it-on-80-matches-included) |

Neither way goes through a gateway. At 50 000 players, match traffic is about 1.1 Gbit/s. The hop through the front's
nginx is the cost D-82 accepted. A gateway would add a second hop, and a component of ours, to the one path that cannot
afford it.

So a player has **two sockets** while in a match: the lobby connection to a gateway, and the match connection to an
arena. They fail independently, and that is a feature: losing the lobby connection does not interrupt play.

## 2. Position and shape

```
client ──WSS /lobby on 443, or WS /lobby on 80──► nginx ──WS──► gateway ──HTTP──► platform: match, queue, party
                                                                  │
                                                                  └──► j-redis: session lookup, push channel
client ──HTTPS /v1/ on 443, or HTTP /v1/ on 80──► nginx ──HTTP──► platform: login, registration, boards, the shop
```

**nginx on the front** (the one machine the site's public address reaches; see the
[glossary](../glossary.md#1-processes-and-places)) takes every lobby connection and passes it to a gateway on the
private network ([D-82](../architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name)):

- **On 443** nginx ends the TLS. **On 80** it serves the same locations, limits and upstreams in clear, for a client
  without TLS ([D-115](../architecture/03-decision-log.md#d-115--a-players-build-may-connect-without-tls-and-the-site-serves-it-on-80-matches-included)).
- It spreads the connections over the gateways with `least_conn`. A gateway that fails is passed over for 10 s
  (nginx's default `fail_timeout`; the upstream is in `deploy/nginx/backend-upstreams.conf`).
- It adds the phone's address to `X-Forwarded-For`. The gateway believes that header only from nginx: a peer on its
  own machine, or an address in `BACKEND_TRUSTED_PROXIES`. It uses the address to name the client in its logs.

**How many gateways.** The design for 50 000 players has one gateway per machine, three in total. **The site
launches on one machine** ([D-93](../architecture/03-decision-log.md#d-93--the-site-launches-on-one-machine-for-up-to-about-5-000-players)):
one gateway, on port 8090, behind that machine's own nginx. In both cases the client holds one endpoint, the site's
one public address, and reconnects to it
([D-13](../architecture/03-decision-log.md#d-13--the-client-holds-an-endpoint-list-no-vip), superseded in part by
D-82).

**Addresses and certificates.** The site has no domain. Its certificate comes from the backend's own certificate
authority (CA) and names the public address
([operations/01 §11](../operations/01-deploy.md#11-the-certificate), [06](../operations/06-certificates.md)).

**A client chooses TLS or clear by the addresses it is given**
([D-115](../architecture/03-decision-log.md#d-115--a-players-build-may-connect-without-tls-and-the-site-serves-it-on-80-matches-included),
which supersedes D-104's rule that a players' build connects encrypted alone):

- Given `https://`, `wss://` and the backend's root, the client uses TLS. It verifies the certificate against that
  root alone (`TrustRoot`) and pins nothing else. On a certificate error it shows an error. It never falls back to
  plaintext.
- Given `http://` and `ws://`, the client connects in clear, on port 80. Everything it sends then crosses the internet
  readable by anyone on the way: the password, the session token and the join tickets. This is the owner's choice
  ([Q-64](../requirements/01-scope-and-nfrs.md#7-open-questions)).

| Property | Value |
|---|---|
| Path | `/lobby`, also with a query or a path below it; nginx passes only `/lobby` itself (`location = /lobby`), with any query. Any other HTTP request to the gateway is answered 404 and closed |
| Largest message | 16 KiB; a bigger frame is refused |
| Connections per instance | about 17 000 at 50 000 players across three gateways; up to about 5 000 on the one launch machine |
| Memory | about 8 KB per idle connection, so about 140 MB for 17 000; the heap is 3 GB on three machines (`-Xms3g -Xmx3g`), 2 GB on the one launch machine (D-93) |
| Threads | Netty: 1 boss event loop and 2 worker event loops. `platform`'s replies arrive on the HTTP client's threads and are handed back to the connection's event loop |
| Identity | its gateway id, by which pushes are routed (§5): the machine's host name on the site |
| State | the connection table, and nothing else that matters |

The gateway is **stateless in the sense that matters**: losing one costs its clients a reconnect, not data. That is
why it is the component with no failover procedure.

## 3. Lobby protocol

The lobby speaks **JSON over WebSocket**. The match protocol, by contrast, is hand-packed binary. The reasons are the
reverse of those in [02-networking §4](02-networking.md#4-the-snapshot): lobby traffic is low in volume and changes
often, so messages that are easy to read and easy to extend are worth more than saved bytes.

```json
{ "t": "party.invite", "id": 42, "d": { "playerId": 4711 } }
{ "t": "party.invite.ok", "id": 42, "d": { "partyId": "…", "leader": 1, "members": [ … ], "version": 1 } }
{ "t": "evt.match.found", "d": { "arenaHost": "…", "arenaPort": 9001, "ticketId": "…", "tls": true, "tlsName": "a1.arena.internal", "mode": "duel" } }
```

| Field | Meaning |
|---|---|
| `t` | the message type |
| `id` | set by the client on a request; the reply carries the same `id`. A message without `id` is a push |
| `d` | the message's data |

An unknown `t` is answered with an error, not dropped. So a newer client talking to an older server gets a clear
failure instead of a timeout. Every message, reply, error code and push as built is listed in
[the gateway's README](../../backend/gateway/README.md#the-lobby-protocol). The flows are drawn in
[diagrams/03](../diagrams/03-lobby-and-store.md).

**A client sends something at least every 30 s.** When it has nothing else to say, it sends `{"t":"ping"}`, and the
gateway answers `ping.ok`. The gateway closes a connection that is silent for 120 s, with a WebSocket Close frame
whose reason is `idle`, so a phone that has gone stops holding a socket. nginx's 150 s timeout stands behind it. This
rule was not in the contract until 2026-09-26; before that, nothing closed a silent socket
([P-20](../defects.md#2-protocol--the-client-contract)).

**What is built** (`gateway/GatewayServer`, `LobbyHandler`, `ConnectionRegistry`, `PlatformClient`):

- the WebSocket endpoint at `/lobby`;
- the auth handshake, with its five-second deadline (§4);
- `match.request`, forwarded to `platform`;
- the connection registry, per-connection rate limiting (§6), and one connection per player (§4).

It was verified with the JDK's WebSocket client across four processes: register and log in over HTTP, then
authenticate over the socket and get a ticket for a live arena.

**The gateway forwards these lobby messages to `platform`.** Each sends only the field it takes, not the rest of `d`:

| Lobby message | Field sent | Forwarded to | Built |
|---|---|---|---|
| `match.request` | none | `POST /v1/match-requests` | with the gateway itself |
| `queue.join` | `mode` | `POST /v1/queue` ([04 §4](04-platform-services.md#4-matchmaking)) | 2026-09-27 |
| `queue.leave` | none | `DELETE /v1/queue` | 2026-09-27 |
| `party.invite`, `party.kick` | `playerId` | `POST /v1/party/invite`, `/v1/party/kick` | 2026-09-29 |
| `party.accept` | `partyId` | `POST /v1/party/accept` | 2026-09-29 |
| `party.leave` | none | `POST /v1/party/leave` | 2026-09-29 |
| `party.say` | `phraseId` ([01 §9](01-arena.md#phrases-designed-2026-09-29-plan-item-8)) | `POST /v1/party/say` | 2026-09-29 |
| `match.accept`, `match.decline` | `matchUid`, from an `evt.match.ready` | `POST /v1/queue/accept`, `/decline` | 2026-09-29 |

**Pushes are built (2026-09-27).** Matchmaking was their first producer (§5;
[04 §4](04-platform-services.md#4-matchmaking)). Since 2026-10-01 a team's changes are pushed too, as
`evt.team.update` ([04 §2](04-platform-services.md#team-events-pushed-designed-2026-10-01-plan-item-39)). A team's
own actions (invitations, applications, roles) are HTTPS calls to `platform`, not lobby messages. A push has no
`id`, because it answers nothing.

## 4. Connection lifecycle

```
1. connect          nginx → gateway, WebSocket upgrade
2. auth             client sends { "t": "auth", "d": { "token": "…" } } within 5 s
                    gateway: HGET sess:{token} playerId   (j-redis)
                    invalid or expired → close with a typed reason, no retry loop
                    not 43 base64url characters → the same, and no HGET (S-20)
3. register         MULTI GETSET conn:{playerId} {gatewayId}#{nonce}-{n}, EXPIRE 60, EXEC
                    (the value replaced: another gateway's is told, T-60)
                    refreshed every 20 s with SET NX + EXPIRE: never overwritten
                    gateway adds playerId → Channel to its local table
                    auth.ok once the SET is acknowledged; if it fails, internal
                    and the connection closed (T-39)
4. serve            requests forwarded to platform; pushes delivered (§5)
5. disconnect       drop from the table; DEL conn:{playerId} only while it still
                    holds this connection's own value (WATCH, compare, MULTI/DEL)
```

The key `conn:{playerId}` is the player's **registration**: it says which gateway holds the player's lobby
connection. Its value is the gateway id, then `#`, then a value unique to this connection.

**"Is the entry still ours?" is decided by the value, not by the gateway.** A player who moves between networks (Wi-Fi
to mobile data) registers at a new gateway. The old gateway still holds a socket that it will only later find dead.
When the value was the bare gateway id and was deleted without a check, the old gateway undid the move in three ways
([T-6](../defects.md#4-concurrency)):

1. its late cleanup deleted the new entry;
2. its refresh wrote the entry again for the socket it still believed in;
3. its shutdown deleted every entry it had held.

Now each registration's value is its own. Cleanup deletes only that value. A refresh cannot take an entry back. A
shutdown deletes nothing and lets the entries expire. An auth that is still being looked up when the connection
closes is never registered ([T-2](../defects.md#4-concurrency)).

**`auth.ok` means the player can be reached** (designed 2026-10-02, plan item 60 (c)). The gateway used to answer
`auth.ok` at the same moment it sent the registration. A push sent the moment `auth.ok` arrived could then look up
the registration first, find none, and go nowhere ([T-39](../defects.md#4-concurrency)). Now the gateway answers
`auth.ok` only after the store has acknowledged the write. That costs one round trip to the store, once per
connection. If the write fails, the gateway answers `internal` and closes the connection, as if the connection had
been lost. The client connects again, rather than sit in a lobby that nothing can reach.

**The five-second auth deadline matters.** Anyone can open a connection, and an unauthenticated connection costs
memory and a file descriptor, so it must not linger. A connection that fails auth is closed with a reason code the
client can act on: a bad token means "log in again", not "reconnect".

**A token's form is checked before it names a key** (2026-10-04,
[S-20](../defects.md#5-security-and-input)). `SessionStore` mints tokens of exactly 43 base64url characters. For
anything else it answers "nobody" without reading the store, both in a lookup and in a logout. Before this fix, a
token of `of:42` named the key `sess:of:42`, which is player 42's index of sessions (04 §10). Here, at the gateway,
a lookup of it could fail as `internal`. At `platform`, a logout with it deleted the index, so a later ban found no
session to end. Now such a token is `invalid_session`, as any unknown token is.

**One lobby connection per player.** When the player logs in a second time, the gateway publishes
`evt.session.replaced` to the first connection and closes it. A ban from the admin API (04 §10) does the same with
`evt.session.revoked`: the push is written, then the connection is closed. **A revocation closes only a connection
whose own session has ended** (T-71, designed 2026-11-26): every sender ends the sessions in the store before it
pushes, so the gateway looks the connection's session up first. A ban or a deletion ended every session, and the
connection closes; a password changed at the account service ended those made before it (10 §4.3), and a connection
on a later one, the device that changed it come back, stays, the push dropped. A lookup that fails closes it, as the
push asks. **A client that receives either push does not reconnect.** It was replaced on purpose, and reconnecting would replace the other device in turn
([P-19](../defects.md#2-protocol--the-client-contract)).

**The rule holds across gateways too** (designed 2026-10-19, [T-60](../defects.md#4-concurrency)). Behind the
front's nginx
([D-82](../architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name)),
lobby connections are spread over every machine's gateway, so two devices on one account usually land on different
gateways. It works like this:

1. The second gateway writes its registration with `GETSET`, so it learns which registration it replaced.
2. When that one names another gateway, the second gateway publishes `evt.session.replaced` on that gateway's
   channel, with the replaced registration's value.
3. The other gateway closes its connection, and tells the client why, only while its own registration still holds
   that value. A newer connection to it, which displaced the replaced one in the meantime, is not closed by a message
   about an older one.

Before this, a second login through another gateway left the first connection open but receiving nothing, since
pushes go to the newest registration. The rule is enforced at the gateway, not in `platform`, because the gateway is
the component that can actually see both connections.

## 5. Push routing

`platform` and `worker` need to reach one specific player: a reward landed, an invitation arrived, a match is ready.

The naive design gives every player a j-redis pub/sub channel, and has each gateway subscribe once per connected
player. That is 50 000 subscriptions, and a storm of new subscriptions whenever a gateway restarts.

Instead, **the routing is one lookup plus a few channels**: one channel per gateway, and one for everyone.

```
platform:   GET conn:{playerId}          → "gw-2#k3v9q-812"; route by the part before #
            PUBLISH push:gw-2 {"to": playerId, "msg": {"t": "evt.…", "d": {…}}}
gateway-2:  receives, looks up its local table, writes to that Channel
```

- When a gateway starts, it subscribes to its own channel, `push:{gatewayId}`, and to `push:all`, which carries a
  notice to everyone (04 §10).
- After a reconnect, the store client subscribes again. Whatever was published in between is lost. A push may be lost;
  the next subsection says what the lobby is told then.
- **Built** as `handoff/LobbyPush` for the sender, and `GatewayServer` and `ConnectionRegistry` for the gateway. It
  was tested from a second store client through to a real socket.
- The cost is one `GET` per push. That is a sub-millisecond operation on a store that does 100 000 or more operations
  a second, and pushes are not a hot path.

**A stale route is harmless.** If the player has reconnected to another gateway, the old gateway no longer has the
connection and drops the message. Anything the client must not miss is not delivered this way: it is stored first
and fetched on reconnect ([§7](#7-what-must-not-be-lost)).

### When the subscription is lost (designed 2026-10-01, plan item 50)

The second audit found three defects, O-6, O-7 and O-8 ([defects §6](../defects.md#6-operations)). Every push
depends on each gateway's subscription. A subscription could be left on a store that was no longer the primary, or
never made at all. The fixes:

- **The store client follows the primary** (j-redis 2.2.1, its decision D-39). A server that is made a replica closes
  its subscribers' connections. The client pings each subscriber connection every 5 s, so it notices a lost machine
  within about 11 s. Each connection then looks for the primary and subscribes again. The arena's `arena-admin:`
  subscription uses the same client, and follows too. What one connection finds, the client's other connections
  follow at once (its D-40). This matters for the connection that writes a player's registration: the handover tells
  it nothing, and it had lost its first writes to `-READONLY` (O-11).
- **Both channels are subscribed before either is waited for.** So `push:all` is remembered, and made again on
  reconnect, whether or not the store answered at start (O-7).
- **After the subscription comes back, every connection on this gateway is sent `evt.resync`**, through its `Pushes`
  handler, as after an overflow (§8). What was published in between is lost, so the client fetches what it fetches
  after its own reconnect (O-8).
- **Measured** by these metrics:

  | Metric | Where | Meaning |
  |---|---|---|
  | `backend_gateway_store_subscribed` | gateway | 1 while the subscriber's connection is up. The runbook's promotion checks it |
  | `backend_gateway_resubscribed_total` | gateway | times the channels were subscribed again |
  | `backend_platform_pushes_unheard_total`, `backend_worker_pushes_unheard_total` | each sender | pushes to a player registered on a gateway whose channel nobody heard: what O-6 looked like from outside |

- **Drilled.** `FAILOVER=demote` hands the store over as the runbook's planned handover does: the old primary is
  demoted, not killed. Then the drill plays the scenarios again. A duel needs `evt.match.ready`, the push that was
  lost. Before the fix, no subscription was on the new primary and the notice was never heard. After it, the
  gateway's two channels and the arena's channel were on the new primary, and both scenarios passed (2026-10-01).

**Built 2026-10-01**, both halves:

- j-redis 2.2.1, tested by `ClientFailoverTest`: a subscriber and a blocked reader follow a demotion; a subscriber
  leaves a primary that falls silent, and pings nothing while it has no connection; the client's other connections
  follow what one of them found; an idle lease is not lent.
- The gateway, tested by `GatewayServerTest`: the lobby is told `evt.resync` after the subscriber's connection is
  killed; a gateway started with the store down hears `push:all` once the store is up. `LobbyPushTest` tests the
  unheard count.

## 6. Rate limiting and abuse

There are two independent limits, because they stop different things:

| Limit | Scope | Purpose |
|---|---|---|
| 20 frames in any one-second window, of every kind. The 21st ends the connection: `rate_limited`, then a Close frame (1008) | per connection | stops a malfunctioning or modified client |
| 30 attempts a minute · 10 attempts a quarter hour | per source address · per account | stops password guessing, and one address spending the Argon2 budget. Enforced in `platform`, not here (below) |

**How the frame limit works.** It is kept in memory: the times of the connection's last 20 frames. So it holds over
any one-second window, not only over fixed windows, which let 40 frames through across a boundary. (The design said
a token bucket with a burst of 40; what is built is stricter.) It counts every frame: pings, binary frames and the
fragments of a message included. At first it counted whole text messages only, which left everything else
unlimited.

**It closes as the WebSocket protocol does** (2026-09-29,
[P-31](../defects.md#2-protocol--the-client-contract)):

1. the gateway sends the refusal, `rate_limited`;
2. then a WebSocket Close frame, code 1008;
3. then it keeps the connection, reading and dropping its input, until the client's own Close, or for two seconds.

When the socket was closed at once, with the flood unread, it could end in a reset. To a WebSocket client a bare end
of stream is an error: one client dropped the messages it had not yet handed on, the refusal among them.

The gateway's other closes (the auth deadline, an invalid session, a replaced or revoked session, a 401 from
`platform`) pass through Netty's WebSocket protocol handler. That handler writes a Close frame of its own, 1000 with
the reason `Bye`, before it ends the socket (its default, `sendCloseFrame`). None of these closes follows a flood,
and none has been seen to lose its message.

**The frame limit is per gateway, and deliberately not shared state.** A distributed rate limiter would add a round
trip to every message, to defend against an attacker who can already open connections to all three gateways.

**The login limits are not the gateway's.** This section first put them here, at about 5 a minute per account and
per address. As built, login and registration are HTTP calls to `platform`. They never pass through the gateway,
whose lobby socket authenticates with the token they return. So the limits live in `platform`
([04 §1](04-platform-services.md#1-auth-and-sessions)), in front of Argon2, counted in j-redis so they hold across
all three machines. 5 a minute per address was also too tight for mobile: carriers put many subscribers behind one
public IPv4 address.

**Beyond a limit, the connection is closed, not throttled.** A throttled client that misbehaves stays connected and
keeps costing resources.

**Other edge concerns stay at nginx**, which is better at them: TLS, request size caps, slowloris timeouts, and
blocking by IP address.

## 7. What must not be lost

Pushes are best-effort by design. Anything that matters is **stored first and pushed second**:

| Event | Stored | The push is |
|---|---|---|
| Reward granted | MySQL ledger and inventory | a notification, not the delivery |
| Team invitation | a MySQL `team_invite` row | a prompt to fetch again |
| Match found | a j-redis ticket, TTL 60 s | the fast path; the client also asks once on reconnect |
| Party changed | j-redis `party:`, TTL 1 h | a notification; `GET /v1/party` is the truth |
| Party invitation | j-redis `pinv:`, TTL 60 s | the only notice: an invitation that is missed is asked for again |
| Match found, to answer | j-redis `mmc:` and each `mmp`, 10 s to answer | a prompt; `GET /v1/queue` says `confirming`, with the match and the time left |

So the rule is: **a push tells the client to look; it does not carry the truth.** A client that reconnects after
missing pushes fetches its state and converges. This is what lets the gateway be stateless and the routing be
best-effort.

## 8. Backpressure and slow clients

A client may stop reading its socket. This section is about what the gateway does with the pushes it cannot send.

The problem was left for when pushes existed. They have existed since 2026-09-27 (`evt.match.found` and the rest).
Until plan item 35, a push was written as it came, whatever the connection had not yet sent. A client that stopped
reading grew the gateway's memory by every push sent to it.

**The rule.** Before writing a push to a connection:

1. If `channel.isWritable()` is false, the push is held in a small ring for that connection, 16 entries.
2. On overflow, the oldest is dropped and a `resync` flag is set.
3. When the connection drains, a single `evt.resync` goes, telling the client to fetch again, rather than a replay of
   a backlog it no longer needs.

A connection that stays unwritable for 30 seconds is closed. The mobile client is already built to reconnect, and
holding a dead connection open helps nobody.

**As designed for building (2026-10-01, plan item 35).** "Unwritable" is Netty's state: more than 64 KiB waiting to
go out, beyond what the socket took. A handler of its own on each connection, `Pushes`, holds the ring and the clock.
A push reaches it on the connection's own event loop, so nothing about the ring is shared between threads.

- Held pushes go out in order, flushed together, when the connection drains. After an overflow the whole backlog is
  dropped and only `evt.resync` goes. Some of it is gone, so the client must fetch the truth anyway, and the rest
  would only be read and thrown away.
- `evt.session.revoked` is never held. It is written and then the connection is closed; if the connection is
  unwritable, it is closed at once. The session is already revoked; the client finds out when it next authenticates.
- Replies to the client's own requests are not held. The client is waiting for them, and its requests are
  rate-limited (§6). The 30 s close bounds them too.
- The 30 s run from the moment the connection became unwritable, and start again each time it does. Draining in time
  cancels the close.
- Counted: `backend_gateway_pushes_total{outcome}` gains `held` and `dropped`, and
  `backend_gateway_slow_closed_total` counts connections closed after 30 s unwritable. The duration histogram of §10
  waited for a measured need; it was built with plan item 48, to judge the 30 s.

**The client.** `evt.resync` reaches the app as any push does (`OnPush`). The app then fetches what it fetches after
a lobby reconnect: the queue, the party, a tournament match (08 §5).

**A notice for everyone** (04 §10) arrives on `push:all`, which every gateway subscribes to beside its own channel.
It goes to each connection through the same `Pushes` handler.

**Built 2026-10-01** (`gateway/Pushes`, one per connection, between the frame aggregator and `LobbyHandler`;
`ConnectionRegistry.deliver` hands each push to it on the connection's loop). Tested with a connection made
unwritable by hand, as Netty marks one past its high-water mark: in a unit test with a frozen clock, and through a
running gateway, where seventeen held pushes came out as one `evt.resync` and the next push went straight through.

## 9. Failure behaviour

| Failure | Effect | Recovery |
|---|---|---|
| Gateway process dies | its connections drop | clients reconnect to the site's address. With three gateways, the front's nginx passes over the dead one for 10 s and `least_conn` places them on another. systemd restarts it (the only way back on the one launch machine) |
| `platform` unreachable | requests fail with a typed error | the gateway stays up and keeps its connections; it does not turn the failure into a storm of disconnections |
| j-redis `session` unreachable | new authentications fail | **existing connections keep working**: the token was checked once, and the player id is held on the connection |

That last row is deliberate. Checking the session again on every request would turn a short store outage into an
instant mass disconnection. The cost is that a ban would take effect only at the player's next connect. So the design
had `platform` publish a `kick` push for immediate enforcement. **Built otherwise** (2026-09-29,
[04 §10](04-platform-services.md#10-admin-api)): a ban ends every session the player has and their lobby connection,
with `evt.session.revoked`, and an operator's kick takes them out of every arena.

## 10. What to measure

| Metric | Why |
|---|---|
| Connections per instance, and the spread across three | uneven balance means the front's `least_conn` is misbehaving |
| Auth failures by reason | a spike in bad tokens is either a bug or an attack |
| Push route misses (`conn:` lookup empty or stale) | high rates mean the presence TTL is too short |
| Rate-limit closures per minute | a client bug, or abuse |
| Request latency to `platform`, p50/p99 | the gateway's own overhead should be invisible next to it. Details below |
| Unwritable-connection duration histogram | mobile network quality, and whether the 30 s cut-off is right. Details below |

**Request latency to `platform`** (designed 2026-10-01, plan item 46; **built 2026-10-01**):
`backend_gateway_platform_seconds{route}`.

- It is a histogram of each call `PlatformClient` makes, from its send to its answer or its failure.
- `route` is the API's path, one of a handful fixed in the gateway's code.
- A call that times out takes a little over its 5 s, so it is counted above the last finite bucket, in `+Inf`. Its
  failure is logged as before.
- It is observed as the call completes, answered or failed, so neither path can miss it.

**Unwritable-connection duration** (designed 2026-10-01, plan item 48; **built 2026-10-01**):
`backend_gateway_unwritable_seconds{end}`.

- Each sample is one spell during which a lobby connection could take no more data (§8), from the moment it became
  unwritable to the spell's end.
- `end` is `drained` (it took data again) or `closed` (it was closed while still unwritable, by the 30 s rule or by
  either side).
- Bounds: 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 20 and 30 s. A spell that the 30 s rule closes lasts a little over 30 s, so
  it is counted above the last finite bound, in `+Inf`. The closed spells at the cut-off are `_count` minus the
  `le="30"` bucket.
- It is timed in `Pushes`, which already keeps the 30 s, by its event loop's clock.
- A connection that never backs up is never counted. Compare the spells with `backend_gateway_connections` to see how
  many do.
- How to read it: drained spells crowding just below 30 s say the cut-off is too short. Closed spells at 30 s, with
  few drained spells near it, say it is not.

**Built** (`GatewayServer.registerMetrics`, and the edge certificate in `GatewayMain`):

| Metric | What it counts |
|---|---|
| `backend_gateway_connections` | connections held now |
| `backend_gateway_authenticated_total` | connections authenticated |
| `backend_gateway_errors_total{code}` | errors by code, `rate_limited` closures among them. Auth failures show as `code="invalid_session"` |
| `backend_gateway_pushes_total{outcome}` | `delivered`; `not_here` for a player who has moved or gone; `malformed`; `broadcast` for a notice to every lobby; `held` and `dropped` for a slow connection (§8) |
| `backend_gateway_slow_closed_total` | connections closed after 30 s unwritable (§8) |
| `backend_gateway_platform_seconds{route}` | the latency histogram above |
| `backend_gateway_unwritable_seconds{end}` | the duration histogram above |
| `backend_gateway_store_subscribed`, `backend_gateway_resubscribed_total` | the push subscription (§5) |
| `backend_gateway_registry_refresh_failures_total` | registrations that a 20 s refresh could not renew |
| `backend_gateway_edge_certificate_expiry_timestamp_seconds` | when the certificate this machine's nginx shows clients expires, read from the file named by `BACKEND_EDGE_CERTIFICATE`; 0 if it cannot be read |

**Push route misses** are counted here when the route was stale (`not_here`). A lookup that found no registration at
all is the sender's to count. So is a push to a registered player that no gateway heard
(`backend_platform_pushes_unheard_total`, `backend_worker_pushes_unheard_total`, §5).
