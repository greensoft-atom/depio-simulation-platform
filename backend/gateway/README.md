# gateway

The client's lobby connection. There is one gateway process per machine, behind that machine's
nginx. It holds each player's lobby WebSocket at `/lobby` and authenticates it with the session
token. It registers where the player can be reached, in `conn:{playerId}`, forwards a fixed set of
requests to `platform` over HTTP, and writes the pushes that other processes publish for its
players.

It makes no product decisions. Apart from the session lookup, which is one store read, everything a
player asks is `platform`'s to answer, and the answer is passed back as it came
([03 §1](../../docs/detailed-design/03-gateway.md#1-what-it-is-not)). Match traffic never comes this
way: the client talks to an arena on a socket of its own
([D-5](../../docs/architecture/03-decision-log.md#d-5--match-traffic-never-passes-through-nginx-or-the-gateway)).

Design: [03-gateway](../../docs/detailed-design/03-gateway.md). Flows drawn:
[diagrams/03](../../docs/diagrams/03-lobby-and-store.md).

## Layout

`com.backend.gateway`:

| Class | What it does |
|---|---|
| `GatewayMain` | The process. It reads the arguments, opens the store client, and starts the server and the metrics; a shutdown hook stops them. Exit 2 for a refused configuration, 1 for any other failure |
| `GatewayServer` | The Netty server (1 acceptor thread; `GatewayMain` asks for 2 connection threads). It builds each connection's pipeline, subscribes to `push:{gatewayId}` and `push:all`, and registers the metrics. Any HTTP request outside `/lobby` is answered 404 and closed |
| `LobbyHandler` | One connection's protocol: the auth handshake and its 5 s deadline, the request switch, forwarding to `platform`, errors, the idle close, cleanup |
| `FrameLimit` | One connection's frame rate: 20 frames in any one second; the 21st is refused (`rate_limited`, then Close 1008), both written from the channel's tail, through the WebSocket handler, so nothing follows the Close. Also keeps the client's address for the logs: nginx's last `X-Forwarded-For` entry, taken at the upgrade |
| `Pushes` | One connection's pushes while it cannot take more: a ring of 16, one `evt.resync` after an overflow, and a close after 30 s unwritable |
| `ConnectionRegistry` | Who is connected here: the local map, the `conn:` registration with its refresh and its compare-and-delete, and handing pushes to connections |
| `PlatformClient` | The asynchronous JDK HTTP client to `platform`, and its latency histogram |
| `EdgeCertificate` | When the certificate nginx shows clients expires, for a metric |

Each connection's pipeline, in order:

`HttpServerCodec` → `HttpObjectAggregator` (16 KiB) → `IdleStateHandler` (120 s without a read) →
`FrameLimit` → `WebSocketServerProtocolHandler` (`/lobby`, with a query or a path below it) →
`WebSocketFrameAggregator` (16 KiB) → `Pushes` → `LobbyHandler` → `NotFound`.

## Running

```
java -cp '<release>/lib/gateway/*' com.backend.gateway.GatewayMain \
     [bindHost] [port] [platformUrl] [storeHost] [storePort] [gatewayId]
```

| # | Argument | Default | Notes |
|---|---|---|---|
| 0 | `bindHost` | `127.0.0.1` | Loopback by default: nginx terminates TLS in front of it, and a lobby on every interface would be a plaintext way round it |
| 1 | `port` | `8081` | A whole number, or the process refuses to start (exit 2) |
| 2 | `platformUrl` | `http://127.0.0.1:8080` | The API's base; the paths below are appended to it |
| 3 | `storeHost` | `127.0.0.1` | The `session` store. Ignored when `BACKEND_STORE_ADDRESSES` is set |
| 4 | `storePort` | `6379` | A whole number, or exit 2 |
| 5 | `gatewayId` | `gateway-<port>` | Names this gateway's push channel, `push:{gatewayId}`, and starts every registration's value. Must be unique among gateways |

**Under systemd.** `deploy/systemd/backend-gateway.service` passes
`${GATEWAY_BIND} ${GATEWAY_PORT} ${PLATFORM_URL} ${STORE_HOST} ${STORE_PORT} %H` from
`/etc/backend/gateway.env`, so the gateway id is the host name.
- `deploy/env/gateway.env.example` listens on `127.0.0.1:8090`, which is the `backend_gateway`
  upstream in `deploy/nginx/backend.conf`.
- The unit runs a 3 GB heap with generational ZGC and `LimitNOFILE=65536`. It reads the store
  password as a credential, and does not restart after exit 2 or 243.
- The drill (`client/headless-drill.sh`) runs it on 8094, with platform on 8093 and the store on 6390.

**Stopping.** The shutdown hook closes the metrics server and logs `connections held at shutdown: n`.
It then stops the registry, the server and the store client, and flushes the log queue.
- It deletes no `conn:` entry. The players are reconnecting elsewhere as it goes, and a delete issued
  now could land after their new registration. The entries expire within 60 s.

## Configuration

### Environment

| Variable | Default | Meaning |
|---|---|---|
| `BACKEND_STORE_ADDRESSES` | unset | `host:port,host:port`: the session store's primary and replica, in any order ([D-34](../../docs/architecture/03-decision-log.md#d-34--each-store-has-a-replica-every-process-knows-both-a-promotion-is-a-script)). Set, it replaces arguments 3 and 4; the client uses the primary with the highest epoch and follows a promotion by itself. Not `host:port`: exit 2 |
| `BACKEND_STORE_PASSWORD_FILE` | unset | A file holding the store's password, one trailing line ending removed; wins over the next. Named but unreadable or empty: exit 2. The unit sets it from `LoadCredential=store-password` |
| `BACKEND_STORE_PASSWORD` | unset | The password itself. With neither set, a warning is logged and no password is sent; a store that answers `NOAUTH` is then exit 2 |
| `BACKEND_METRICS_ADDR` | unset: no metrics server | `host:port` for `GET /metrics`; the example uses `127.0.0.1:9103`. Not `host:port`, or not bindable: exit 2 |
| `BACKEND_EDGE_CERTIFICATE` | unset: no certificate gauge | nginx's certificate chain, PEM. Its earliest expiry becomes a metric, read at every scrape, so a renewal shows at once |

The gateway does not read `BACKEND_EVENTS_STORE`: it has no use for the result queue.

**Logging.** INFO to standard output, queued, from `common`'s `logback.xml`. Override it with
`-Dlogback.configurationFile=/etc/backend/logback.xml`.

### Fixed numbers

| What | Value | Where |
|---|---|---|
| Authentication deadline, from the WebSocket handshake | 5 s | `LobbyHandler.AUTH_DEADLINE_SECONDS` |
| Silence before the gateway closes a connection | 120 s | `GatewayServer.READER_IDLE_SECONDS` |
| Largest message, and largest HTTP request | 16 KiB | `GatewayServer.MAX_FRAME_BYTES` |
| Frames per connection | 20 in any one second | `FrameLimit.MAX_FRAMES_PER_SECOND` |
| Wait for the client's Close after a refusal | 2 s | `FrameLimit.LINGER_MILLIS` |
| Pushes held for a connection that cannot take more | 16 | `Pushes.RING` |
| Unwritable before the connection is closed | 30 s | `Pushes.UNWRITABLE_CLOSE_SECONDS` |
| Unwritable means | more than 64 KiB waiting to go out; writable again below 32 KiB | Netty's default water marks |
| `conn:` registration: lifetime, refresh | 60 s, every 20 s | `ConnectionRegistry.TTL_SECONDS`, `REFRESH_SECONDS` |
| A call to `platform`: connect, whole call | 2 s, 5 s | `PlatformClient`, `GatewayServer` |
| Wait for the push subscriptions at start | 5 s, then it starts anyway | `GatewayServer.listenForPushes` |

### Ports and TLS

| Socket | Direction | Notes |
|---|---|---|
| `bindHost:port` | in | Plain HTTP upgraded to WebSocket at `/lobby`. No TLS here: nginx terminates TLS 1.3 and 1.2 for `wss://<name>/lobby` and proxies to it (`location = /lobby`, read timeout 150 s, longer than the gateway's 120 s so the gateway decides) |
| `BACKEND_METRICS_ADDR` | in | `GET /metrics`, Prometheus text 0.0.4. Loopback in the examples |
| `platformUrl` | out | HTTP/JSON, `Authorization: Bearer <session token>` |
| the store | out | The j-redis client's own connections: one for commands, one subscriber, and leased ones for `WATCH` |

### Metrics

| Metric | Type | Meaning |
|---|---|---|
| `backend_gateway_connections` | gauge | Lobby connections registered here, which means authenticated ones |
| `backend_gateway_authenticated_total` | counter | Successful authentications |
| `backend_gateway_errors_total{code}` | counter | Every `error` sent, by code, `rate_limited` included |
| `backend_gateway_pushes_total{outcome}` | counter | Messages on this gateway's channels: `delivered`, `not_here` (the player has moved or gone), `malformed`, and `broadcast` (once per notice on `push:all`). Of those handed to a connection, `held` for a slow one and `dropped` from an overflowing one |
| `backend_gateway_slow_closed_total` | counter | Connections closed after 30 s unwritable |
| `backend_gateway_platform_seconds{route}` | histogram | Each call to `platform`, from its send to its answer or failure, by the API's path. Bounds 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5 and 5 s. A call that times out takes a little over its 5 s, so it is counted above the last finite bucket, in `+Inf` |
| `backend_gateway_unwritable_seconds{end}` | histogram | Each spell a connection could take no more, to its end: `drained`, or `closed` (by the 30 s rule or by either side). Bounds 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 20 and 30 s. A spell the 30 s rule ends lasts a little over 30 s, so it too is counted in `+Inf` |
| `backend_gateway_store_subscribed` | gauge | 1 while the store client's subscriber connection is up: every push arrives on it |
| `backend_gateway_resubscribed_total` | counter | Times the subscriptions were made again after that connection was lost |
| `backend_gateway_registry_refresh_failures_total` | counter | `conn:` registrations the 20 s refresh could not renew; each round with any is logged once. Rising means pushes to this gateway's players will be lost when the registrations lapse |
| `backend_gateway_edge_certificate_expiry_timestamp_seconds` | gauge | With `BACKEND_EDGE_CERTIFICATE`: the chain's earliest expiry, Unix seconds; 0 when the file cannot be read, which fires every expiry alert |
| `backend_store_timeouts_total`, `_failed_fast_total`, `_reconnects_total`, `_server_errors_total`, `_connected` | counters, gauge | The store client's own ([handoff](../handoff/README.md#opening-the-store)) |
| `backend_jvm_heap_used_bytes`, `backend_jvm_threads`, `backend_jvm_gc_seconds_total`, `backend_process_uptime_seconds` | | Every process's ([common](../common/README.md#metrics)) |

## The lobby protocol

**Frames.** Text frames, each one JSON object: `{"t": type, "id": n, "d": {…}}`.
- `id` is a whole number that the reply echoes. A request with no `id`, or with 0, gets a reply
  without one. It is read leniently: a numeric string counts as its number, any other string as
  none.
- A message from the gateway without an `id` is a push.
- Binary frames are refused. A message sent in fragments is read whole.

**Before `auth.ok`**, every message but `auth` is answered `not_authenticated`.

### What the client sends

| `t` | `d` | What the gateway does | Reply |
|---|---|---|---|
| `auth` | `token` | Looks the session up itself: `HGET sess:{token} playerId`. Then it writes the registration (below) | `auth.ok` `{"playerId"}`, once the registration is written |
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

**What `platform` answers is passed through.** Every forwarded call carries
`Authorization: Bearer <the connection's token>` and only the one field shown.
- **2xx:** `<t>.ok`, with `platform`'s body as `d` (`{}` when the body is empty or not JSON).
- **Any other status:** `error`, with `platform`'s own `code` (`upstream_error` if its body has none)
  and the message `platform refused the request`: `platform`'s own message is not passed on.
  A 401 also closes the connection: the session died under it.
- **No answer at all** (unreachable, or 5 s gone): `error` `internal`, not a refusal.

The routes and their codes are in
[04 §4](../../docs/detailed-design/04-platform-services.md#4-matchmaking). Everything else a client
does (login, the shop, teams, friends, leaderboards) is HTTPS straight to `platform` and never
passes through here.

### Error codes

| `code` | When | Then |
|---|---|---|
| `text_only` | A binary frame | The connection stays |
| `bad_json` | A text frame that is not JSON (no `id` in the reply) | Stays |
| `no_type` | No `t`, which includes JSON that is not an object (`[]`, `5`) and an empty frame | Stays |
| `not_authenticated` | Anything but `auth` before authentication | Stays |
| `already_authenticated` | `auth` again, once the session was found | Stays |
| `auth_in_progress` | `auth` while the first one's lookup is still running | Stays |
| `no_token` | `auth` without `d.token`, or with an empty one | Stays |
| `invalid_session` | The token names no live session. A token not of the form `platform` mints (43 base64url characters) names no store key at all and is answered the same, without a store read ([S-20](../../docs/defects.md#5-security-and-input)) | Closed |
| `auth_timeout` | No session found within 5 s of the handshake (no `id` in the reply) | Closed |
| `internal` | The session could not be looked up, `platform` did not answer, or the registration could not be written | Closed only after a registration that failed |
| `unknown_type` | Any other `t` | Stays |
| `rate_limited` | The 21st frame within one second, of any kind, WebSocket pings and fragments included (no `id`) | Close 1008, then closed (below) |
| `platform`'s own | A forwarded request refused: `already_queued`, `in_party`, `invalid_session` and the rest | Closed on a 401 |
| `upstream_error` | `platform` refused with a body that names no code | As above |

Every error is `{"t": "error", "id": n, "d": {"code", "message"}}` and is counted in
`backend_gateway_errors_total{code}`.

### What is pushed

The gateway relays any message published for a player whose `t` is text, whatever its type. These
are the ones sent today:

| `t` | From | `d` |
|---|---|---|
| `evt.match.found` | platform: matcher, sandbox | `{arenaHost, arenaPort, ticketId, tls, mode}` |
| `evt.match.ready` | platform: matcher | `{matchUid, mode, seconds}`: answer with `match.accept` or `match.decline` |
| `evt.queue.update` | platform: matcher, queue | `{state, mode}` |
| `evt.party.invite` | platform: parties | `{partyId, from, fromName}` |
| `evt.party.update` | platform: parties | The party as `GET /v1/party` answers it |
| `evt.party.said` | platform: parties | `{from, name, phraseId}` |
| `evt.team.update` | platform: teams | `{team}`, the team or `null` ([04 §2](../../docs/detailed-design/04-platform-services.md#team-events-pushed-designed-2026-10-01-plan-item-39)) |
| `evt.friend.request`, `evt.friend.accepted` | platform: friends | `{playerId, name}` |
| `evt.inbox` | platform, worker | `{}`: something is in the inbox, fetch it |
| `evt.rewards` | worker | What a match paid ([05 §6](../../docs/detailed-design/05-worker-and-events.md#what-a-match-paid-pushed-designed-2026-10-01-plan-item-43)) |
| `evt.tournament.match` | worker: tournaments | The tournament match's grant |
| `evt.notice` | platform: admin API, on `push:all` | `{text}`, to every connection on every gateway |
| `evt.session.revoked` | platform: admin API (ban, suspension) | `{reason}`. Written, then the connection closed; closed at once if it cannot be written. The client does not reconnect |
| `evt.session.replaced` | this gateway | `{}`, to the older connection when the player authenticates again here. Then it is closed, and the client does not reconnect |
| `evt.resync` | this gateway | `{}`. Pushes were lost (an overflow, or the subscription made again): fetch what a reconnect fetches |

A push is a prompt, not the truth: what must not be missed can be fetched
([03 §7](../../docs/detailed-design/03-gateway.md#7-what-must-not-be-lost)).

### Connection rules

1. **Connect** to `wss://<name>/lobby`. A query string or a path below `/lobby` is accepted; any
   other path is a 404.
2. **Authenticate within 5 s** with `{"t": "auth", "id": n, "d": {"token": "<session token>"}}`.
   - The token is the one `POST /v1/sessions` returned.
   - `auth.ok` comes only once the store holds the registration, so a push sent the moment it
     arrives finds the player
     ([T-39](../../docs/defects.md#4-concurrency)).
3. **Send something at least every 30 s.** `ping` when there is nothing else to say. WebSocket ping
   frames are answered by Netty, and count towards the frame limit.

| Event | The client gets | Then |
|---|---|---|
| 120 s without a frame | Close 1000, reason `idle` | Ended. nginx's 150 s stands behind it |
| No session within 5 s | `error` `auth_timeout` | Close 1000, ended |
| Unknown or malformed token | `error` `invalid_session` | Close 1000, ended. Log in again; do not retry the token |
| `platform` answers 401 | `error` with its code | Close 1000, ended |
| The registration could not be written | `error` `internal` | Close 1000, ended. Connect again |
| The player authenticates again on this gateway | `evt.session.replaced`, on the older connection | Close 1000, ended. Do not reconnect |
| A ban or suspension | `evt.session.revoked` | Close 1000, ended. Do not reconnect |
| The 21st frame in a second | `error` `rate_limited`, then Close 1008 `too many messages` | Input read and dropped until the client's Close, or 2 s |
| 30 s unable to take more | — | Ended |
| A message over 16 KiB, or a protocol error | — | Ended |

The Close 1000 is written by Netty's WebSocket protocol handler, with Netty's reason text for it,
`Bye` (seen on the wire, 2026-10-06): it writes one on every close that passes through it (its
default `sendCloseFrame`). The `idle` close and the
rate-limit refusal write their own.

**One connection per player.** This holds within one gateway. A second login through another
gateway leaves the first connection open but receiving nothing, since pushes route to the newest
registration ([03 §4](../../docs/detailed-design/03-gateway.md#4-connection-lifecycle)).

## The registration

`conn:{playerId}` holds `{gatewayId}#{nonce}-{n}`. The nonce is random per process, and `n` counts
this process's registrations, so every registration's value is its own
([T-6](../../docs/defects.md#4-concurrency)).

| When | Store commands |
|---|---|
| Authenticated | `MULTI · SET conn:{id} <value> EX 60 · EXEC`, before `auth.ok` |
| Every 20 s, each live connection | `MULTI · SET conn:{id} <value> NX EX 60 · EXPIRE conn:{id} 60 · EXEC`. Restores a lost entry, extends whatever is there, never takes an entry back |
| Disconnected | On the registry's own thread, never an event loop: `WATCH`, `GET`, compare, `MULTI · DEL · EXEC`. Deletes only this connection's own value |
| Shut down | Nothing; the entries expire |

A sender routes by the part of the value before `#`
([handoff](../handoff/README.md#store-key-families)).

## Build and test

From `backend/`, with j-redis 2.2.1 installed first (see the
[backend README](../README.md#commands)):

```bash
export JAVA_HOME=/opt/jdk21 PATH=/opt/jdk21/bin:$PATH
/opt/maven/bin/mvn -o -pl gateway -am install                       # with handoff and common
/opt/maven/bin/mvn -o -pl gateway test -Dtest=GatewayServerTest     # one class
```

The tests need nothing outside the JVM. They run an embedded j-redis and the JDK's HTTP server
standing in for `platform`, both on loopback ports the system chooses.

| Class | Tests | What it covers |
|---|---|---|
| `GatewayServerTest` | 28 | A real server, a store and a stand-in `platform`, driven by the JDK's WebSocket client. Covers auth, bad tokens, auth-first and the 5 s deadline; every request type forwarded, refusals passed through, and `platform` down answered `internal`. Also `auth.ok` only once registered, and a refused registration closed; pushes, the notice, revocation and one connection per player; a slow connection's pushes held and resynced. Also the subscription lost and made again, and a gateway started with the store down; the flood, ping-flood and large-flood refusals; fragments, paths and the latency histogram |
| `PushesTest` | 9 | The ring, the overflow to `evt.resync`, the 30 s close and its restart, revocation, and the unwritable spells timed, on an embedded channel with a frozen clock |
| `ConnectionRegistryTest` | 7 | Moving between gateways (T-6): a late cleanup, a refresh and a shutdown leave the new registration alone; own cleanup works; a lost entry restored; a replaced connection told why; a failed refresh counted |
| `FrameLimitTest` | 5 | One refusal per flood, the 2 s linger, the sliding window, a client at the limit's pace never refused, and the client a log names |
| `LobbyHandlerRaceTest` | 2 | Two auth frames cannot make one connection two players; a connection closed during its lookup is never registered (T-2) |
| `EdgeCertificateTest` | 3 | The earliest expiry in a chain, a renewal seen at the next scrape, and an unreadable file read as 0 |
| `PlatformClientTest` | 2 | A call timed in seconds, and a failed call timed too |

`SlowProxy` is a test helper, not a test.

## Operational notes

- **Restarting a gateway** costs its clients a reconnect to another endpoint and nothing else. It
  holds no state that matters.
- **The store lost or handed over.**
  - Existing connections keep working: the player id is held on the connection.
  - New authentications answer `internal` until the store is back.
  - Pushes published while the subscriber was down are lost.
  - When the subscription is made again, every connection is sent `evt.resync` and
    `backend_gateway_resubscribed_total` rises.
  - After a promotion, check `backend_gateway_store_subscribed` at 1, and `PUBSUB NUMSUB push:all` on
    the new primary
    ([runbook §2](../../docs/operations/02-runbook.md#a-j-redis-store-built-2026-09-29-d-34)).
- **`platform` unreachable.** Forwarded requests answer `internal`, and connections stay.
- **Pushes going nowhere.** Senders count a push to a registered player that no gateway heard
  (`backend_platform_pushes_unheard_total`, `backend_worker_pushes_unheard_total`). Here,
  `not_here` counts a stale route.
- **Log lines worth knowing:**
  - `gateway listening on host:port/lobby` and `listening for pushes on push:<id> and push:all`.
  - `the push channels … are not confirmed yet` at start, when the store did not answer in 5 s.
  - `the push channels were subscribed again; n lobby connections told to fetch`.
  - `session lookup failed`, `registering player n failed` and `platform call failed`, all WARN.
  - `rate limit exceeded, closing …` (INFO).
- **Capacity, as designed (not measured):** about 17 000 connections a gateway at 50 000 players, at
  about 8 KB each when idle ([03 §2](../../docs/detailed-design/03-gateway.md#2-position-and-shape)).

## Design documents

- [03 — Gateway](../../docs/detailed-design/03-gateway.md): the design this implements.
- [04 — Platform services](../../docs/detailed-design/04-platform-services.md): sessions (§1), the
  routes forwarded (§4), the admin API's ban and notice (§10).
- [Diagrams: the lobby and the store](../../docs/diagrams/03-lobby-and-store.md).
- [Operations: deploy](../../docs/operations/01-deploy.md#4-nginx) and
  [runbook](../../docs/operations/02-runbook.md).
