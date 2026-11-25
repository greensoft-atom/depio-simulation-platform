# gateway

The gateway holds the client's lobby connection. The lobby connection is one WebSocket per player, at
`/lobby`, that stays open while the app runs. The client sends small requests on it (join a queue,
invite a friend to a party) and receives pushes: messages that other processes send to the player
without being asked. There is one gateway process on each machine, behind nginx.

What the gateway does, in order:
1. It accepts the WebSocket and checks the player's session token. The token is the one that
   `POST /v1/sessions` returned at login ([glossary: session](../../docs/glossary.md)).
2. It registers where the player can be reached, in the store key `conn:{playerId}`.
3. It forwards a fixed set of requests to `platform` over HTTP and passes the answers back.
4. It writes to the player's socket the pushes that other processes publish for that player.

The gateway makes no product decisions. It answers one thing itself, the session lookup, which is
one read from the store. Everything else a player asks is for `platform` to answer, and the
gateway passes the answer back unchanged
([03 §1](../../docs/detailed-design/03-gateway.md#1-what-it-is-not)). Match traffic never comes
this way: the client talks to an arena on a connection of its own
([D-5](../../docs/architecture/03-decision-log.md#d-5--match-traffic-never-passes-through-nginx-or-the-gateway)).

This page is for engineers who run, change or call the gateway. It covers the classes, how to
start the process, its settings, its metrics, the lobby protocol that clients speak, the store
registration, the tests, and what to look at in operation. The design is
[03-gateway](../../docs/detailed-design/03-gateway.md). The flows are drawn in
[diagrams/03](../../docs/diagrams/03-lobby-and-store.md).

## Layout

All classes are in the package `com.backend.gateway`.

| Class | What it does |
|---|---|
| `GatewayMain` | The process. It reads the arguments and `BACKEND_TRUSTED_PROXIES`, opens the store client, and starts the server and the metrics. A shutdown hook stops them. It exits 2 for a refused configuration and 1 for any other failure at start |
| `GatewayServer` | The Netty server: 1 acceptor thread, and the connection threads `GatewayMain` asks for (2). It builds each connection's pipeline (below), subscribes to `push:{gatewayId}` and `push:all`, and registers the metrics. It answers any HTTP request outside `/lobby` with 404 and closes the connection |
| `LobbyHandler` | The protocol of one connection: the `auth` handshake and its 5 s deadline, the switch over request types, forwarding to `platform`, errors, the close after 120 s of silence, and the cleanup when the connection ends |
| `FrameLimit` | The frame rate of one connection: 20 frames in any one second. It refuses the 21st with `rate_limited`, then sends a Close frame with code 1008. Both go out from the channel's tail, through the WebSocket handler, so nothing is written after the Close. It also keeps the client's address for the logs, taken at the upgrade: the last `X-Forwarded-For` entry, believed only when the peer is this machine or a proxy named in `BACKEND_TRUSTED_PROXIES` (D-82) |
| `Pushes` | The pushes of one connection while it cannot take more: a ring of 16, one `evt.resync` after an overflow, and a close after 30 s unwritable |
| `ConnectionRegistry` | Who is connected here: the local map, the `conn:` registration with its refresh and its compare-and-delete, handing pushes to connections, and closing a connection that a newer login replaced, here or through another gateway (T-60) |
| `PlatformClient` | The asynchronous JDK HTTP client that calls `platform`, and its latency histogram |
| `EdgeCertificate` | Reads when the certificate that nginx shows clients expires, for a metric |

Each connection's pipeline, in order:

`HttpServerCodec` → `HttpObjectAggregator` (16 KiB) → `IdleStateHandler` (120 s without a read) →
`FrameLimit` → `WebSocketServerProtocolHandler` (`/lobby`, with a query or a path below it) →
`WebSocketFrameAggregator` (16 KiB) → `Pushes` → `LobbyHandler` → `NotFound`.

`FrameLimit` sits before the WebSocket handler on purpose. The WebSocket handler answers pings
itself, so a limit placed after it would not count pings or the fragments of a message.

## Running

```
java -cp '<release>/lib/gateway/*' com.backend.gateway.GatewayMain \
     [bindHost] [port] [platformUrl] [storeHost] [storePort] [gatewayId]
```

Every argument is optional and takes its position. An argument that is given but empty is refused
(exit 2): systemd passes an empty argument when a variable is missing from the settings file.

| # | Argument | Default | Notes |
|---|---|---|---|
| 0 | `bindHost` | `127.0.0.1` | Loopback by default. On the site it is the machine's private address, where the front's nginx reaches it (D-82). nginx ends TLS in front of the gateway, so a lobby on every interface would be a way around TLS, in plain text |
| 1 | `port` | `8081` | A whole number, or the process refuses to start (exit 2) |
| 2 | `platformUrl` | `http://127.0.0.1:8080` | The base of the API. The paths below are added to it. It must start with `http://` or `https://`, or the process refuses to start (exit 2) |
| 3 | `storeHost` | `127.0.0.1` | The `session` store. Ignored when `BACKEND_STORE_ADDRESSES` is set |
| 4 | `storePort` | `6379` | A whole number, or exit 2 |
| 5 | `gatewayId` | `gateway-<port>` | Names this gateway's push channel, `push:{gatewayId}`, and starts the value of every registration it writes. It must be unique among gateways |

At start the process prints one line to standard output:
`gateway <id> on <host>:<port>/lobby, platform <url>, store <host>:<port>`.

**Under systemd.** `deploy/systemd/backend-gateway.service` reads `/etc/backend/gateway.env` and
passes `${GATEWAY_BIND} ${GATEWAY_PORT} ${PLATFORM_URL} ${STORE_HOST} ${STORE_PORT} %H`. The last
one, `%H`, is the host name, so the gateway id is the host name.
- `deploy/env/gateway.env.example` listens on `10.0.0.1:8090`, the machine's private address.
  The front's nginx reaches each machine's gateway there: the upstream `backend_gateway` in
  `deploy/nginx/backend-upstreams.conf` lists `10.0.0.1:8090`, `10.0.0.2:8090` and
  `10.0.0.3:8090`, and sends each new connection to the one that holds the fewest (`least_conn`).
- The unit runs a 3 GB heap with generational ZGC and `LimitNOFILE=65536`. On the one machine of
  the launch (D-93), the install guide's step 8 sets the heap to 2 GB through a drop-in (at most
  1 GB on a machine with less than 48 GB of memory).
- The unit takes the store's password from `gateway.env`, as a plain variable at the end of the
  file, which only root can read (D-102). It does not restart the process after exit 2.
- On a development machine, `dev-backend.sh` runs the gateway on 8081 with the id `gateway-1`
  (debugger port 5007 with `DEBUG=1`).
- The drill (`client/headless-drill.sh`) runs it on 8094 with the id `gateway-drill`, with platform
  on 8093, the store on 6390 and the gateway's metrics on 9198.

**Stopping.** The shutdown hook closes the metrics server and logs
`connections held at shutdown: n`. It then stops the registry, the server and the store client,
and writes out the log queue.
- It deletes no `conn:` entry. The players are reconnecting to other gateways while this one
  stops, and a delete sent now could land after their new registration. The entries expire on
  their own within 60 s.

## Configuration

### Environment

| Variable | Default | Meaning |
|---|---|---|
| `BACKEND_STORE_ADDRESSES` | unset | `host:port,host:port`: the session store's primary and its replica, in any order ([D-34](../../docs/architecture/03-decision-log.md#d-34--each-store-has-a-replica-every-process-knows-both-a-promotion-is-a-script)). When set, it replaces arguments 3 and 4. The client uses the primary with the highest epoch and follows a promotion by itself. A value not in that form: exit 2 |
| `BACKEND_STORE_PASSWORD_FILE` | unset | A file that holds the store's password. One trailing line ending is removed. It wins over the next variable. A file that is named but cannot be read, or is empty: exit 2 |
| `BACKEND_STORE_PASSWORD` | unset | The password itself, at the end of `gateway.env` (D-102). With neither variable set, the gateway logs a warning and sends no password. If the store then answers `NOAUTH`, the process exits 2 |
| `BACKEND_TRUSTED_PROXIES` | unset: loopback only | IP addresses separated by commas: the proxies whose `X-Forwarded-For` the logs believe, besides this machine. On the site, the front's nginx on the private network (D-82); the example lists `10.0.0.1,10.0.0.2,10.0.0.3`. Anything but IP literals (a host name, say) refuses the start (exit 2). Nothing is looked up in DNS. It changes only which address a log names |
| `BACKEND_METRICS_ADDR` | unset: no metrics server | `host:port` for `GET /metrics`. The example uses `127.0.0.1:9103`. A value not in that form, or a port that cannot be bound: exit 2 |
| `BACKEND_EDGE_CERTIFICATE` | unset: no certificate gauge | nginx's certificate chain, in PEM. Its earliest expiry becomes a metric. The file is read at every scrape, so a renewal shows at once. The example names `/etc/backend/tls/fullchain.pem` |

The gateway does not read `BACKEND_EVENTS_STORE`: it does not use the result queue.

**Logging.** INFO and above go to standard output, through a queue, as set in `common`'s
`logback.xml`. Override it with `-Dlogback.configurationFile=/etc/backend/logback.xml`.

### Fixed numbers

These values are constants in the code. Changing one means a new release.

| What | Value | Where |
|---|---|---|
| Time to authenticate, counted from the WebSocket handshake | 5 s | `LobbyHandler.AUTH_DEADLINE_SECONDS` |
| Silence before the gateway closes a connection | 120 s | `GatewayServer.READER_IDLE_SECONDS` |
| Largest message, and largest HTTP request | 16 KiB | `GatewayServer.MAX_FRAME_BYTES` |
| Frames per connection | 20 in any one second | `FrameLimit.MAX_FRAMES_PER_SECOND` |
| Wait for the client's Close after a refusal | 2 s | `FrameLimit.LINGER_MILLIS` |
| Pushes held for a connection that cannot take more | 16 | `Pushes.RING` |
| Time unwritable before the connection is closed | 30 s | `Pushes.UNWRITABLE_CLOSE_SECONDS` |
| What "unwritable" means | more than 64 KiB waiting to go out; writable again below 32 KiB | Netty's default water marks |
| `conn:` registration: lifetime, refresh | 60 s, refreshed every 20 s | `ConnectionRegistry.TTL_SECONDS`, `REFRESH_SECONDS` |
| A call to `platform`: connect, whole call | 2 s, 5 s | `PlatformClient`, `GatewayServer` |
| Wait for the push subscriptions at start | 5 s, then it starts anyway | `GatewayServer.listenForPushes` |

The last row follows D-94: a process waits at start for what it needs, but a store that is still
starting does not stop it. The subscriptions stay registered and are made when the store answers.

### Ports and TLS

| Socket | Direction | Notes |
|---|---|---|
| `bindHost:port` | in | Plain HTTP, upgraded to WebSocket at `/lobby`. There is no TLS here. nginx on the front ends TLS 1.3 and 1.2 on 443 for `wss://<the site's address>/lobby` and passes the connection on (`location = /lobby`, read timeout 150 s). The timeout is longer than the gateway's 120 s, so the gateway decides when a socket is dead. Since D-115 the same location also answers on 80, in clear, for `ws://<the site's address>/lobby` |
| `BACKEND_METRICS_ADDR` | in | `GET /metrics`, in the Prometheus text format 0.0.4. Loopback in the examples |
| `platformUrl` | out | HTTP with JSON bodies, and `Authorization: Bearer <session token>` |
| the store | out | The j-redis client's own connections: one for commands, one subscriber, and leased ones for `WATCH` |

Through nginx, only the exact path `/lobby` reaches the gateway, with or without a query string.
A path below it, such as `/lobby/x`, does not: nginx answers it 404 itself. A client that connects
straight to the gateway may also use a path below `/lobby`.

### Metrics

| Metric | Type | Meaning |
|---|---|---|
| `backend_gateway_connections` | gauge | Lobby connections registered here. Only authenticated connections are registered |
| `backend_gateway_authenticated_total` | counter | Successful authentications |
| `backend_gateway_errors_total{code}` | counter | Every `error` sent to a client, by code, `rate_limited` included |
| `backend_gateway_pushes_total{outcome}` | counter | Messages on this gateway's channels: `delivered`, `not_here` (the player has moved or gone), `malformed`, and `broadcast` (once per notice on `push:all`). Of the messages handed to a connection: `held` for a slow connection, and `dropped` from an overflowing one |
| `backend_gateway_slow_closed_total` | counter | Connections closed after 30 s unwritable |
| `backend_gateway_platform_seconds{route}` | histogram | Each call to `platform`, from its send to its answer or failure, by the API's path. Bounds 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5 and 5 s. A call that times out takes a little over its 5 s, so it is counted above the last finite bucket, in `+Inf` |
| `backend_gateway_unwritable_seconds{end}` | histogram | Each spell in which a connection could take no more, measured to its end: `drained`, or `closed` (by the 30 s rule or by either side). Bounds 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 20 and 30 s. A spell that the 30 s rule ends lasts a little over 30 s, so it too is counted in `+Inf` |
| `backend_gateway_store_subscribed` | gauge | 1 while the store client's subscriber connection is up. Every push arrives on that connection |
| `backend_gateway_resubscribed_total` | counter | Times the subscriptions were made again after that connection was lost |
| `backend_gateway_registry_refresh_failures_total` | counter | `conn:` registrations that the 20 s refresh could not renew. Each round with a failure is logged once. If it rises, pushes to this gateway's players will be lost when their registrations lapse |
| `backend_gateway_edge_certificate_expiry_timestamp_seconds` | gauge | Only with `BACKEND_EDGE_CERTIFICATE`: the chain's earliest expiry, in Unix seconds. It is 0 when the file cannot be read or holds no certificate, which fires every expiry alert |
| `backend_store_timeouts_total`, `_failed_fast_total`, `_reconnects_total`, `_server_errors_total`, `_connected` | counters, gauge | The store client's own ([handoff](../handoff/README.md#opening-the-store)) |
| `backend_jvm_heap_used_bytes`, `backend_jvm_threads`, `backend_jvm_gc_seconds_total`, `backend_process_uptime_seconds` | | Reported by every process ([common](../common/README.md#metrics)) |

## The lobby protocol

This section is the contract between the client and the gateway.

**Frames.** Every message is a text frame that holds one JSON object: `{"t": type, "id": n, "d": {…}}`.
- `t` is the message type. `d` holds the data.
- `id` is a whole number. The reply carries the same `id`. A request with no `id`, or with `id` 0,
  gets a reply without one. The gateway reads `id` leniently: a string of digits counts as its
  number, and any other string counts as no `id`.
- A message from the gateway without an `id` is a push.
- Binary frames are refused. A message sent in fragments is joined and read whole.

**Before `auth.ok`**, the gateway answers every message except `auth` with `not_authenticated`.

### What the client sends

| `t` | `d` | What the gateway does | Reply |
|---|---|---|---|
| `auth` | `token` | Looks the session up itself, in the store: `HGET sess:{token} playerId`. Then it writes the registration (below) | `auth.ok` `{"playerId"}`, sent only once the registration is written |
| `ping` | — | Nothing | `ping.ok` `{}` |
| `match.request` | — | `POST /v1/match-requests`, no body | `match.request.ok` |
| `queue.join` | `mode` | `POST /v1/queue` `{"mode"}` | `queue.join.ok` |
| `queue.leave` | — | `DELETE /v1/queue` | `queue.leave.ok` |
| `party.invite` | `playerId` | `POST /v1/party/invite` `{"playerId"}` | `party.invite.ok` |
| `party.accept` | `partyId` | `POST /v1/party/accept` `{"partyId"}` | `party.accept.ok` |
| `party.leave` | — | `POST /v1/party/leave` `{}` | `party.leave.ok` |
| `party.kick` | `playerId` | `POST /v1/party/kick` `{"playerId"}` | `party.kick.ok` |
| `party.say` | `phraseId` | `POST /v1/party/say` `{"phraseId"}` | `party.say.ok` |
| `match.accept` | `matchUid` | `POST /v1/queue/accept` `{"matchUid"}` | `match.accept.ok` |
| `match.decline` | `matchUid` | `POST /v1/queue/decline` `{"matchUid"}` | `match.decline.ok` |

**The gateway passes `platform`'s answer through.** Every forwarded call carries
`Authorization: Bearer <the connection's token>`, and its body carries only the one field shown.
- **A 2xx status:** the reply is `<t>.ok`, with `platform`'s body as `d`. When the body is empty or
  not JSON, `d` is `{}`.
- **Any other status:** the reply is `error`, with `platform`'s own `code` (`upstream_error` when
  its body has none) and the message `platform refused the request`. The gateway does not pass on
  `platform`'s own message. A 401 also closes the connection: the session ended while the
  connection was open.
- **No answer at all** (`platform` unreachable, or 5 s passed): the reply is `error` `internal`.
  This is not a refusal, and the client may try again.

The routes and their codes are in
[04 §4](../../docs/detailed-design/04-platform-services.md#4-matchmaking). Everything else a
client does (login, the shop, teams, friends, leaderboards) goes over HTTPS straight to `platform`
(or HTTP on the site's port 80, D-115). It never passes through the gateway.

### Error codes

| `code` | When | Then |
|---|---|---|
| `text_only` | A binary frame (no `id` in the reply) | The connection stays |
| `bad_json` | A text frame that is not JSON (no `id` in the reply) | Stays |
| `no_type` | No `t`. This includes JSON that is not an object (`[]`, `5`) and an empty frame | Stays |
| `not_authenticated` | Anything but `auth` before authentication | Stays |
| `already_authenticated` | `auth` again, after the session was found | Stays |
| `auth_in_progress` | `auth` while the lookup for the first `auth` is still running | Stays |
| `no_token` | `auth` without `d.token`, or with an empty one | Stays |
| `invalid_session` | The token names no live session. A token that does not have the form `platform` gives out (43 base64url characters) names no store key at all. It gets the same answer, without a store read ([S-20](../../docs/defects.md#5-security-and-input)) | Closed |
| `auth_timeout` | No session found within 5 s of the handshake (no `id` in the reply) | Closed |
| `internal` | The session could not be looked up, `platform` did not answer, or the registration could not be written | Closed only when the registration failed |
| `unknown_type` | Any other `t` | Stays |
| `rate_limited` | The 21st frame within one second, of any kind, WebSocket pings and fragments included (no `id` in the reply) | Close 1008, then closed (below) |
| `platform`'s own | A forwarded request that `platform` refused: `already_queued`, `in_party`, `invalid_session` and the rest | Closed on a 401 |
| `upstream_error` | `platform` refused with a body that names no code | As above |

Every error has the form `{"t": "error", "id": n, "d": {"code", "message"}}`. Each one is counted
in `backend_gateway_errors_total{code}`.

### What is pushed

The gateway relays any message published for a player, whatever its type, as long as its `t` is
text. These are the types sent today:

| `t` | From | `d` |
|---|---|---|
| `evt.match.found` | platform: matcher, sandbox | `{arenaHost, arenaPort, ticketId, tls, mode}`, and `tlsName` when the arena is behind the front: the name the client sends in its TLS handshake and checks the certificate against (D-82). A client without TLS uses the same name in `GET /match/<tlsName>` on port 80 (D-115) |
| `evt.match.ready` | platform: matcher | `{matchUid, mode, seconds}`. The client answers with `match.accept` or `match.decline` |
| `evt.queue.update` | platform: matcher, queue | `{state, mode}` |
| `evt.party.invite` | platform: parties | `{partyId, from, fromName}` |
| `evt.party.update` | platform: parties | The party, in the form `GET /v1/party` answers |
| `evt.party.said` | platform: parties | `{from, name, phraseId}` |
| `evt.team.update` | platform: teams | `{team}`: the team, or `null` ([04 §2](../../docs/detailed-design/04-platform-services.md#team-events-pushed-designed-2026-10-01-plan-item-39)) |
| `evt.friend.request`, `evt.friend.accepted` | platform: friends | `{playerId, name}` |
| `evt.inbox` | platform (friends, teams), worker (seasons, tournaments) | `{}`: something is in the inbox; fetch it |
| `evt.rewards` | worker | What a match paid ([05 §6](../../docs/detailed-design/05-worker-and-events.md#what-a-match-paid-pushed-designed-2026-10-01-plan-item-43)) |
| `evt.tournament.match` | worker: tournaments | The tournament match's grant |
| `evt.notice` | platform: admin API, on `push:all` | `{text}`, to every connection on every gateway |
| `evt.session.revoked` | platform: admin API (ban, suspension), and the deletion of the player's own account (D-84) | `{reason}`: `banned`, `suspended` or `deleted`. The gateway writes it, then closes the connection. If it cannot be written, the gateway closes the connection at once. The client does not reconnect |
| `evt.session.replaced` | a gateway | `{}`, to the older connection when the player authenticates again, on this gateway or on another one (T-60). Then that connection is closed, and the client does not reconnect |
| `evt.resync` | this gateway | `{}`. Pushes were lost (an overflow, or the subscription made again): fetch what a reconnect fetches |

A push is a prompt, not the truth. Anything the client must not miss can also be fetched
([03 §7](../../docs/detailed-design/03-gateway.md#7-what-must-not-be-lost)).

### Connection rules

1. **Connect** to `wss://<the site's address>/lobby` on 443, or `ws://<the site's address>/lobby`
   on 80 without TLS (D-115). A query string is accepted. Straight to a gateway, a path below
   `/lobby` is accepted too, and any other path gets a 404.
2. **Authenticate within 5 s** with `{"t": "auth", "id": n, "d": {"token": "<session token>"}}`.
   - The token is the one `POST /v1/sessions` returned.
   - `auth.ok` comes only after the store holds the registration. So a push sent the moment
     `auth.ok` arrives finds the player ([T-39](../../docs/defects.md#4-concurrency)).
3. **Send something at least every 30 s.** Send `ping` when there is nothing else to say. Netty
   answers WebSocket ping frames, and they count towards the frame limit.

| Event | The client gets | Then |
|---|---|---|
| 120 s without a frame | Close 1000, reason `idle` | Ended. nginx's 150 s timeout stands behind it |
| No session within 5 s | `error` `auth_timeout` | Close 1000, ended |
| Unknown or malformed token | `error` `invalid_session` | Close 1000, ended. Log in again; do not retry the token |
| `platform` answers 401 | `error` with its code | Close 1000, ended |
| The registration could not be written | `error` `internal` | Close 1000, ended. Connect again |
| The player authenticates again, on this gateway or another | `evt.session.replaced`, on the older connection | Close 1000, ended. Do not reconnect |
| A ban, a suspension, or the account deleted | `evt.session.revoked` | Close 1000, ended. Do not reconnect |
| The 21st frame in a second | `error` `rate_limited`, then Close 1008 `too many messages` | The gateway reads and drops all input until the client's Close, or for 2 s, then closes |
| 30 s unable to take more | — | Ended |
| A message over 16 KiB, or a protocol error | — | Ended |

Netty's WebSocket protocol handler writes the Close 1000, with Netty's reason text, `Bye` (seen on
the wire, 2026-10-06). It writes one on every close that passes through it (its default
`sendCloseFrame`). The `idle` close and the rate-limit refusal write their own Close frames.

**One connection per player.** A newer login closes the older connection, wherever it is.
- On the same gateway, the gateway sends `evt.session.replaced` to the older connection and closes
  it.
- On another gateway, the new registration replaces the old one in the store with `GETSET`, so
  the new gateway learns the old value. It then publishes `evt.session.replaced` on the old
  gateway's channel, with that value. The old gateway closes its connection only while its own
  registration still holds that value, so a late message never closes a newer connection there
  (T-60).
- Behind the front's nginx, two devices on one account usually land on different gateways (D-82).
  Pushes always go to the newest registration
  ([03 §4](../../docs/detailed-design/03-gateway.md#4-connection-lifecycle)).

## The registration

`conn:{playerId}` holds `{gatewayId}#{nonce}-{n}`. The nonce is random for each process, and `n`
counts this process's registrations. So every registration writes a value of its own
([T-6](../../docs/defects.md#4-concurrency)).

| When | Store commands |
|---|---|
| Authenticated | `MULTI · GETSET conn:{id} <value> · EXPIRE conn:{id} 60 · EXEC`, before `auth.ok`. When the value replaced is another gateway's, an `evt.session.replaced` naming it is published on that gateway's channel (T-60), also before `auth.ok` |
| Every 20 s, for each live connection | `MULTI · SET conn:{id} <value> NX EX 60 · EXPIRE conn:{id} 60 · EXEC`. This restores an entry the store lost and extends whatever is there. It never takes an entry back from another gateway |
| Disconnected | On the registry's own thread, never on an event loop: `WATCH`, `GET`, compare, `MULTI · DEL · EXEC`. It deletes only this connection's own value. A failure is logged, and the entry expires within 60 s |
| Shut down | Nothing. The entries expire |

A sender routes a push by the part of the value before `#`
([handoff](../handoff/README.md#store-key-families)).

## Build and test

From `backend/`, with j-redis 2.2.1 installed first (see the
[backend README](../README.md#commands)):

```bash
. ../dev-env.sh                                # vendor/'s JDK and Maven, the bundle, work/ (D-80)
mvn -pl gateway -am install                    # with handoff and common
mvn -pl gateway test -Dtest=GatewayServerTest  # one class
```

The tests need nothing outside the JVM. They run an embedded j-redis, and the JDK's HTTP server
stands in for `platform`. Both listen on loopback ports that the system chooses.

| Class | Tests | What it covers |
|---|---|---|
| `GatewayServerTest` | 28 | A real server, a store and a stand-in `platform`, driven by the JDK's WebSocket client. Auth, bad tokens, auth first, and the 5 s deadline. Every request type forwarded, refusals passed through, and `platform` down answered `internal`. `auth.ok` sent only once registered, and a refused registration closed. Pushes, the notice to everyone, revocation, and one connection per player. A slow connection's pushes held, and an overflow resynced. The subscription lost and made again, and a gateway started while the store was down. The flood, ping-flood and large-flood refusals. Fragments, paths and the latency histogram |
| `PushesTest` | 9 | The ring, the overflow to `evt.resync`, the 30 s close and its restart, revocation, and the unwritable spells timed. All on an embedded channel with a frozen clock |
| `ConnectionRegistryTest` | 9 | Moving between gateways (T-6): a late cleanup, a refresh and a shutdown leave the new registration alone. A connection's own cleanup works; a lost entry is restored; a replaced connection is told why; a failed refresh is counted. A replacement through another gateway closes the old connection there, and a late one spares a newer connection (T-60) |
| `FrameLimitTest` | 6 | One refusal per flood, the 2 s linger, the sliding window, a client at the limit's pace never refused, and the client a log names. Behind the front, the forwarded address believed from the named proxies alone (D-82) |
| `LobbyHandlerRaceTest` | 3 | Two auth frames cannot make one connection two players; a connection closed during its lookup is never registered (T-2); a connection silent for its idle limit is closed with Close `idle` |
| `EdgeCertificateTest` | 3 | The earliest expiry in a chain, a renewal seen at the next scrape, and an unreadable file read as 0 |
| `PlatformClientTest` | 2 | A call timed in seconds, and a failed call timed too |

`SlowProxy` is a test helper, not a test.

## Operational notes

- **Restarting a gateway** costs its clients a reconnect to another endpoint and nothing else. It
  holds no state that matters.
- **The store lost or handed over:**
  - Existing connections keep working: each connection holds its player id.
  - New authentications are answered `internal` until the store is back.
  - Pushes published while the subscriber was down are lost.
  - When the subscription is made again, every connection is sent `evt.resync`, and
    `backend_gateway_resubscribed_total` rises.
  - After a promotion, check that `backend_gateway_store_subscribed` is 1, and run
    `PUBSUB NUMSUB push:all` on the new primary
    ([runbook §2](../../docs/operations/02-runbook.md#a-j-redis-store-built-2026-09-29-d-34)).
- **`platform` unreachable.** Forwarded requests are answered `internal`, and the connections
  stay open.
- **Pushes going nowhere.** Senders count a push to a registered player that no gateway heard
  (`backend_platform_pushes_unheard_total`, `backend_worker_pushes_unheard_total`). On the gateway,
  `not_here` counts a stale route.
- **Log lines worth knowing:**
  - `gateway listening on host:port/lobby` and `listening for pushes on push:<id> and push:all`.
  - `the push channels … are not confirmed yet` at start, when the store did not answer in 5 s.
  - `the push channels were subscribed again; n lobby connections told to fetch`.
  - `session lookup failed`, `registering player n failed` and `platform call failed`, all WARN.
  - `could not refresh n of m connection registrations: is the store there?` and
    `could not remove the registration of player n`, both WARN.
  - `rate limit exceeded, closing …` (INFO), naming the client by the address nginx saw.
- **Capacity, as designed (not measured):** about 17 000 connections a gateway at 50 000 players,
  at about 8 KB each when idle ([03 §2](../../docs/detailed-design/03-gateway.md#2-position-and-shape)).
  At the launch on one machine (D-93), its one gateway holds every lobby connection, up to about
  5 000.

## Design documents

- [03 — Gateway](../../docs/detailed-design/03-gateway.md): the design this module implements.
- [04 — Platform services](../../docs/detailed-design/04-platform-services.md): sessions (§1), the
  routes forwarded (§4), and the admin API's ban and notice (§10).
- [Diagrams: the lobby and the store](../../docs/diagrams/03-lobby-and-store.md).
- [Operations: deploy](../../docs/operations/01-deploy.md#4-nginx) and
  [runbook](../../docs/operations/02-runbook.md).
