# Phone-to-backend routes

Every connection the Android app opens to the backend, from the phone to the process that answers it, on the site
and on a development machine; and nginx's part in full: each of its files, each setting in them and why, how the
install guide and the development scripts change them, its logs, and how it is run and checked. The ports and the
firewall are [05](05-ports-and-firewall.md)'s, the certificate [06](06-certificates.md)'s; this page draws the
routes through both.

The addresses are the examples', as in 05: the site's public address `203.0.113.10`, a phone at `198.51.100.7`. The
layout is the launch's one machine ([D-93](../architecture/03-decision-log.md#d-93--the-site-launches-on-one-machine-for-up-to-about-5-000-players)): every process on it, nginx alone reachable from outside.

## 1. The three routes

The app opens three connections, all to the site's one public address, and every one goes through nginx, which
ends their TLS ([D-82](../architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name)):

```mermaid
flowchart LR
    phone["the app, on a phone<br/>198.51.100.7"]
    subgraph site["the machine: 203.0.113.10, 443 and 9001 open"]
        p443["nginx :443<br/>ends TLS"]
        p9001["nginx :9001<br/>ends TLS, picks the arena<br/>by the name the phone gives"]
        pf["platform<br/>127.0.0.1:8080"]
        gw["gateway<br/>127.0.0.1:8090"]
        a1["arena a1<br/>127.0.0.1:9011"]
        a2["arena a2<br/>127.0.0.1:9012"]
        a3["arena a3<br/>127.0.0.1:9013"]
    end
    phone ==>|"① HTTPS /v1/…"| p443
    phone ==>|"② WSS /lobby"| p443
    phone ==>|"③ TLS, name a1.arena.internal"| p9001
    p443 -->|"/v1/: HTTP"| pf
    p443 -->|"/lobby: WebSocket"| gw
    gw -->|"its requests"| pf
    p9001 -->|"the PROXY line, then TCP"| a1
    p9001 -.->|"a2.arena.internal"| a2
    p9001 -.->|"a3.arena.internal"| a3
```

The thick arrows are TLS, from the phone to nginx; the thin ones are plain, behind nginx on `127.0.0.1`, where every
process but nginx listens. An arrow is who opens the connection; the answers come back on it.

| Route | The app dials | It checks the certificate for | nginx | Then |
|---|---|---|---|---|
| ① the API | `https://203.0.113.10/v1/…` | the address | `backend.conf`: `/v1/` to the platform, anything else 404 | the platform |
| ② the lobby | `wss://203.0.113.10/lobby` | the address | `location = /lobby`: the WebSocket's upgrade passed on | the gateway with the fewest connections, which sends the lobby's requests to the platform |
| ③ a match | TCP to `203.0.113.10:9001`, TLS giving the grant's `tlsName` | `tlsName`, `a1.arena.internal` | `backend-stream.conf`: the arena that name maps to, after the PROXY line; no name or an unknown one, closed | the arena, with a ticket that works once |

Nothing else on the machine is reachable from the internet: 80 has nothing listening and the firewall closes it, and
every other process is bound to `127.0.0.1` ([03 §6 to §8](03-install-guide.md)).

## 2. One session, in order

```mermaid
sequenceDiagram
    participant P as the app
    participant N as nginx (443, 9001)
    participant PF as platform
    participant S as session store
    participant G as gateway
    participant A as arena a1
    Note over P,N: TLS, the phone to nginx
    Note over N,A: plain, on 127.0.0.1
    P->>N: POST /v1/guests (a first launch only)
    N->>PF: passed on, X-Forwarded-For: 198.51.100.7
    PF-->>P: playerId, guestKey
    P->>N: POST /v1/sessions, the guest key
    N->>PF: passed on (2 a second an address)
    PF-->>P: token
    P->>N: WSS /lobby
    N->>G: the upgrade passed on
    P->>G: the token, within 5 s
    G->>S: is it a session?
    P->>G: join a queue, or play now
    G->>PF: the request
    PF->>S: a match found, for the player's gateway
    S-->>G: pushed
    G-->>P: evt.match.found: 203.0.113.10, 9001, a1.arena.internal, ticketId
    P->>N: TLS to :9001, giving a1.arena.internal
    N->>A: PROXY TCP4 198.51.100.7 10.0.0.1 51234 9011
    P->>A: Join(ticketId), through nginx
    A->>S: the ticket claimed, once
    A-->>P: Welcome, then 15 frames a second
```

Every message from the app crosses nginx; the diagram draws the hop only where nginx adds something. A device's
later launches skip the guest and log in with the key it keeps (`AccountKeeper`). A match found arrives on the lobby,
or answers `GET /v1/queue` or `POST /v1/match-requests`: `arenaHost` the site's address, `arenaPort` 9001, `tls`
true, the arena's `tlsName`, and a `ticketId`.

## 3. Limits and timeouts

nginx always waits longer than the process behind it, so the process decides when a connection is dead, and says
why in its own log and metrics.

| Route | The app | nginx | The process behind |
|---|---|---|---|
| ① the API | gives up after 10 s | 15 s for an answer; a body up to 4 KiB; the four sign-up and login paths 2 a second an address, 30 at once, then 429 | the platform: a body up to 4 KiB; password attempts limited precisely, nginx's limit only a flood guard in front |
| ② the lobby | a ping every 30 s, lost with no answer in 10 s; reconnects after 1 s, doubling to 30 s | closes after 150 s without a byte | the gateway: the token within 5 s; closes a lobby silent for 120 s |
| ③ a match | 5 s to dial; a ping every 10 s in every state | 5 s to reach the arena; closes after 60 s without a byte | the arena: `Join` within 10 s; drops a connection after 30 s without a frame |

## 4. nginx, file by file

The release's nginx (`/opt/backend/nginx`, 1.30.5, OpenSSL 3.5.9 compiled in, built for glibc 2.28,
[D-100](../architecture/03-decision-log.md#d-100--the-releases-nginx-is-built-on-rocky-8-glibc-228-the-floor-mysql-already-sets)) reads five files, which the release ships in `nginx-conf/`
and the install copies to `/etc/backend/nginx/`. They include each other so:

```mermaid
flowchart TD
    main["nginx.conf<br/>the workers, the logs, the limits"]
    http["http { }"]
    stream["stream { }"]
    site["backend.conf<br/>the server on 443"]
    up["backend-upstreams.conf<br/>the platforms and the gateways"]
    pfc["backend-platform.conf<br/>the platform's proxy settings"]
    st["backend-stream.conf<br/>the server on 9001, the map of arenas"]
    main --> http --> site
    site --> up
    site -->|"in every /v1 location"| pfc
    main --> stream --> st
```

The same files go on every machine, so any can be the front; only the addresses in `backend-upstreams.conf` and the
map, and the address in `server_name`, are the site's own (§4.7).

### 4.1 `nginx.conf`, the main file

| Setting | Value | Why |
|---|---|---|
| `user` | `nginx` | The workers run as `nginx`; the master, started as root by the unit, binds 443 and 9001 and reads the key |
| `worker_processes` | `auto` | One worker a core |
| `worker_rlimit_nofile` | `262144` | Every connection holds two descriptors, the phone's and the upstream's, and the front holds every player's: a lobby and a match connection each, 200 000 at 50 000 players. The unit's `LimitNOFILE` matches |
| `pid` | `/run/backend-nginx/nginx.pid` | In the unit's `RuntimeDirectory` |
| `error_log` | `/var/log/backend-nginx/error.log warn` | In the unit's `LogsDirectory` |
| `events { worker_connections }` | `16384` | Per worker, a worker a core: each connection two descriptors, inside each worker's 262 144 |
| `http { include mime.types; default_type }` | the release's `mime.types`, `application/octet-stream` | nginx's defaults; nothing here serves a file |
| `log_format backend` | `$remote_addr [$time_local] "$request" $status $body_bytes_sent $request_time $upstream_addr "$http_user_agent"` | `$upstream_addr` says which machine served each request, behind the front |
| `access_log` | `/var/log/backend-nginx/access.log backend` | |
| `sendfile`, `tcp_nopush` | `on`, `on` | nginx's usual pair |
| `keepalive_timeout` | `65s` | How long a phone's idle HTTPS connection is kept for its next call |
| `include` | `backend.conf` in `http`, `backend-stream.conf` in `stream` | The two servers |

### 4.2 `backend.conf`, the server on 443

At the top, outside the server:

| Setting | Value | Why |
|---|---|---|
| `limit_req_zone` | `$binary_remote_addr zone=backend_auth:10m rate=2r/s` | The flood guard on the sign-up and login paths, by the phone's address; 10 MB holds about 160 000 addresses |
| `limit_req_status` | `429` | What a phone over it is answered |
| `server_tokens` | `off` | No version in errors or headers |
| `include` | `backend-upstreams.conf` | The platforms and the gateways (§4.4) |

The server:

| Setting | Value | Why |
|---|---|---|
| `listen` | `443 ssl`, `[::]:443 ssl` | IPv4 and IPv6. Nothing listens on 80: it served only a public CA's challenge, and the site has no domain for one |
| `server_name` | `203.0.113.10` | The site's address, which the install puts in place of the example's; the one server on 443 either way |
| `ssl_certificate`, `ssl_certificate_key` | `/etc/backend/tls/fullchain.pem`, `/etc/backend/credentials/privkey.pem` | Installed by `install-certificate.sh`, which checks both before nginx sees them; the key lives with the other secrets, root's |
| `ssl_protocols` | `TLSv1.2 TLSv1.3` | Android before 10 has no 1.3 |
| `ssl_ciphers` | ECDHE with AES-GCM or ChaCha20-Poly1305, ECDSA and RSA | TLS 1.2 with forward secrecy and authenticated encryption alone; 1.3 has nothing else |
| `ssl_prefer_server_ciphers` | `off` | Every cipher offered is good; the phone picks what its hardware runs fastest |
| `ssl_session_cache`, `ssl_session_timeout` | `shared:backend_tls:10m`, `1h` | A phone that comes back within the hour resumes without a full handshake |
| `ssl_session_tickets` | `off` | nginx never rotates its ticket key, so a ticket would undo forward secrecy until the next restart; resumption still works from the cache |
| `client_max_body_size` | `4k` | The platform reads at most 4 KiB of a body, so nothing larger needs to reach it |
| `proxy_set_header X-Forwarded-For` | `$proxy_add_x_forwarded_for` | Appends the address nginx saw; the platform takes the last entry, from nginx alone |
| `proxy_set_header Host` | `$host` | |

Its locations, the most specific first:

| Location | Goes to | Its own settings | Why |
|---|---|---|---|
| `= /lobby` | `backend_gateway` | `proxy_http_version 1.1`; `Upgrade $http_upgrade` and `Connection "upgrade"`; `X-Forwarded-For` and `Host` again; `proxy_read_timeout` and `proxy_send_timeout` `150s` | The WebSocket's upgrade passed on. A location that sets any `proxy_set_header` inherits none from the server, so every header it needs is set in it. 150 s is longer than the gateway's 120 s, so the gateway decides when a lobby is dead |
| `= /v1/sessions`, `= /v1/accounts`, `= /v1/guests`, `= /v1/accounts/upgrade` | `backend_platform` | `limit_req zone=backend_auth burst=30 nodelay`, and `backend-platform.conf` | Logins, registrations and guests, each an Argon2 hash on the platform: 2 a second an address, 30 at once. The platform answers only these exact paths, so a slash added is no way past the limit |
| `/v1/` | `backend_platform` | `backend-platform.conf` | The rest of the API |
| `/` | none | `return 404` | Anything else, `/health` included: that is for the machine's own monitoring, on loopback |

### 4.3 `backend-platform.conf`, the platform's proxy settings

Included in every `/v1` location:

| Setting | Value | Why |
|---|---|---|
| `proxy_pass` | `http://backend_platform` | The upstream of §4.4 |
| `proxy_http_version`, `proxy_set_header Connection` | `1.1`, `""` | Keeps the connection to the platform open for the next request |
| `proxy_set_header X-Forwarded-For`, `Host` | as the server's | Set again for the reason in §4.2 |
| `proxy_read_timeout` | `15s` | A login waits for Argon2 and may queue behind 160 others; the app allows 10 s |

### 4.4 `backend-upstreams.conf`, the platforms and the gateways

As the release ships it, for three machines:

| Upstream | Servers | Settings | Why |
|---|---|---|---|
| `backend_gateway` | `10.0.0.1:8090`, `10.0.0.2:8090`, `10.0.0.3:8090` | `least_conn` | A lobby lasts the session, so each new one goes to the gateway holding the fewest |
| `backend_platform` | `10.0.0.1:8080`, `10.0.0.2:8080`, `10.0.0.3:8080` | `keepalive 32`, `keepalive_timeout 20s` | Kept below the platform's 1 000 idle connections, and dropped before its 30 s idle timeout: the other way round the platform closed a connection nginx was about to reuse, and nginx does not send a `POST` again, so a login failed with 502 |

A server that fails is passed over for 10 s (nginx's `max_fails 1` and `fail_timeout 10s`); a `POST` already sent
is not sent to another. On the launch's one machine each upstream has one server, on `127.0.0.1` (§4.7).

### 4.5 `backend-stream.conf`, the server on 9001

| Setting | Value | Why |
|---|---|---|
| `log_format backend_stream` | `$remote_addr [$time_local] "$ssl_server_name" $upstream_addr $status $bytes_sent $bytes_received $session_time` | Each match connection: the phone, the name it gave, the arena it went to, how long it lasted |
| `access_log` | `/var/log/backend-nginx/stream.log backend_stream` | |
| `map $ssl_server_name $backend_arena` | each arena's name to its machine's address and port: `a1.arena.internal 10.0.0.1:9011`, and so on; `default ""` | A name is an arena's `BACKEND_ADVERTISE_TLS_NAME`, which the certificate names too. A name not in the map, or none, maps to nothing, and nginx closes the connection |
| `listen` | `9001 ssl`, `[::]:9001 ssl` | |
| `ssl_certificate`, `ssl_certificate_key`, `ssl_protocols`, `ssl_ciphers`, `ssl_prefer_server_ciphers`, `ssl_session_timeout`, `ssl_session_tickets` | as 443's | The same certificate, naming the address and every arena |
| `ssl_session_cache` | `shared:backend_stream_tls:10m` | A zone of its own, as the stream module's must be: a phone that reconnects within the hour resumes |
| `proxy_pass` | `$backend_arena` | The arena the name mapped to |
| `proxy_protocol` | `on` | Each connection begins with one line naming the phone's address, `PROXY TCP4 198.51.100.7 10.0.0.1 51234 9011`, which an arena with a TLS name requires and its logs name ([D-105](../architecture/03-decision-log.md#d-105--an-arena-behind-the-front-knows-the-phones-address-nginxs-proxy-line-read-once)) |
| `proxy_connect_timeout` | `5s` | An arena that does not accept within 5 s: the phone's connection is closed, and it tries again from the lobby |
| `proxy_timeout` | `60s` | Longer than the arena's 30 s without a frame, so the arena decides |

### 4.6 The numbers that must agree

Change one of a pair and the other with it:

| Here | There | The rule |
|---|---|---|
| `/lobby`'s `proxy_read_timeout 150s` | the gateway closes a silent lobby at 120 s | nginx's longer |
| `backend_platform`'s `keepalive_timeout 20s` | the platform drops an idle connection at 30 s | nginx's shorter |
| `keepalive 32` | the platform keeps at most 1 000 idle connections | nginx's smaller |
| `backend-platform.conf`'s `proxy_read_timeout 15s` | the app gives up at 10 s | nginx's longer |
| `client_max_body_size 4k` | the platform reads at most 4 KiB | equal |
| `proxy_timeout 60s` | the arena drops a connection at 30 s without a frame | nginx's longer |
| `proxy_protocol on` | every arena in the map has `BACKEND_ADVERTISE_TLS_NAME` | both, or neither |
| `server_name` and the map's names | the names the certificate gives (`private-ca.sh names`, `install-certificate.sh`) | the same names |
| `worker_rlimit_nofile 262144` | the unit's `LimitNOFILE=262144` | equal |

### 4.7 On the launch's one machine

The install guide's §9 changes three things in the copies it installs, and nothing else:

```bash
sed -i "s/203\.0\.113\.10/$ADDRESS/" /etc/backend/nginx/backend.conf     # server_name: the machine's address
# backend-upstreams.conf: one platform and one gateway, on loopback
upstream backend_gateway  { least_conn; server 127.0.0.1:8090; }
upstream backend_platform { server 127.0.0.1:8080; keepalive 32; keepalive_timeout 20s; }
# backend-stream.conf's map: this machine's three arenas, on loopback
    a1.arena.internal  127.0.0.1:9011;
    a2.arena.internal  127.0.0.1:9012;
    a3.arena.internal  127.0.0.1:9013;
    default            "";
```

The certificate is then made for `$ADDRESS` and those three names, and installed ([03 §9](03-install-guide.md)).

### 4.8 Its logs

| File | What it holds | Read it for |
|---|---|---|
| `/var/log/backend-nginx/access.log` | each request on 443: the phone's address, the request, the status, its time, the platform or gateway that served it | a phone's API calls and lobby connections; 429s; 502s, a process behind not answering |
| `/var/log/backend-nginx/stream.log` | each match connection: the phone's address, the name it gave, the arena, its bytes and length | which arena a phone reached; a name not in the map (`""` or an unknown name, no arena) |
| `/var/log/backend-nginx/error.log` | nginx's own warnings and errors | a certificate it could not load, an upstream refused |

They are rotated each night at 00:05 UTC and 14 days kept (`backend-nginx-logs.timer`, `rotate-nginx-logs.sh`,
[D-85](../architecture/03-decision-log.md#d-85--nginxs-logs-rotated-by-a-timer-of-the-releases-own-and-kept-a-number-of-days)). The processes behind log the phone's address too: the platform and the gateway from
`X-Forwarded-For`, an arena from the PROXY line (`player 123 joined from 198.51.100.7:51234`).

### 4.9 Running it

`backend-nginx.service` runs it: started as root, which binds 443 and 9001 and reads the key, its workers as
`nginx`. It tests the configuration before it starts and before every reload (`nginx -t -q`), so a broken file is
refused and the running nginx goes on as it was. A reload drops no connection: the old workers finish theirs.

```bash
systemctl reload backend-nginx                  # after any change to /etc/backend/nginx/*.conf
/opt/backend/nginx/sbin/nginx -t                # the files checked, by hand, before the reload
journalctl -u backend-nginx                     # a start or a reload refused, and why
```

`install-certificate.sh` installs a certificate this way too: it checks the files, tests nginx with them, reloads,
and puts the previous ones back if nginx refuses the new ([06](06-certificates.md)). Starting and stopping with the
other units is [07](07-start-stop-restart.md)'s.

## 5. On a development machine

`dev-nginx.sh` runs the same release nginx with the same five files, rewritten for one development machine into
`work/nginx/conf/` ([D-92](../architecture/03-decision-log.md#d-92--a-script-for-each-service-of-a-run-by-hand-and-nginx-on-the-sites-ports-in-front-of-it), [development/03 §5](../development/03-build-and-run.md#5-running-it-by-hand)), on every IPv4 address. By default the routes are
the site's, over TLS; with `PLAIN=1` the same routes go without TLS
([D-101](../architecture/03-decision-log.md#d-101--a-plain-run-by-hand-goes-through-nginx-as-the-site-does-80-and-9001-without-tls)).

What its rewrite changes, both ways:

| In the release's files | Becomes |
|---|---|
| paths under `/etc/backend/`, `/var/log/backend-nginx/`, `/run/backend-nginx/` | under `work/nginx/` (`conf/`, `tls/`, `logs/`, `nginx.pid`), and the release's own `mime.types` |
| `listen [::]:…` | gone: IPv4 alone |
| `server_name 203.0.113.10` | `PUBLIC_ADDRESS`, the machine's address, found or given |
| `user nginx`, `worker_rlimit_nofile`, `worker_connections 16384` | `user nobody` when run as root (none otherwise), the limit gone, 1 024 connections |
| `backend_platform`, `backend_gateway` | their first server alone, `127.0.0.1:8080` and `127.0.0.1:8081` |
| the map | one name, `a1.arena.internal`, to `127.0.0.1:9021` |
| `http { }` | temporary files in `work/nginx/temp`, none to disk (`proxy_max_temp_file_size 0`) |

And each way's own:

| | TLS (the default) | Plain (`PLAIN=1`) |
|---|---|---|
| Port 80 | a server of its own: `return 308` to `https://` (the method and the body kept) | the 443 server, without TLS: the API and the lobby |
| Port 443 | the API and the lobby, TLS | not listened on |
| Port 9001 | TLS, the arena by its name, with the PROXY line | without TLS, `proxy_pass 127.0.0.1:9021` (no name to route by), no PROXY line: the arena has no TLS name |
| The certificate | from a root of its own made once, `work/nginx/ca/`, naming `PUBLIC_ADDRESS`, `127.0.0.1` and `a1.arena.internal` | none |
| The arena announces | `PUBLIC_ADDRESS:9001`, TLS as `a1.arena.internal` | `PUBLIC_ADDRESS:9001`, plain |

The app's settings, the three ways side by side:

| | The site | Development, TLS | Development, plain |
|---|---|---|---|
| API endpoint | `https://203.0.113.10` | `https://<PUBLIC_ADDRESS>` | `http://<PUBLIC_ADDRESS>` |
| Lobby | `wss://203.0.113.10/lobby` | `wss://<PUBLIC_ADDRESS>/lobby` | `ws://<PUBLIC_ADDRESS>/lobby` |
| Root it trusts | the site's `ca.pem` | the development root, `work/nginx/ca/ca.pem` | none |
| The app | the players' build | either | a development build alone |

The two roots are different: a build for development carries the development root, and the players' build the
site's. A players' build refuses `http://`, `ws://` and a match without TLS, and says why
([D-104](../architecture/03-decision-log.md#d-104--the-players-build-connects-encrypted-alone-https-wss-and-a-match-over-tls)). On Android the plain way may also need the development build to allow cleartext, which is
not checked here (no Unity).

## 6. What protects each route

**What is trusted.**
- One certificate, from the backend's own CA, names the address and every arena; nginx serves it on 443 and 9001.
- The app accepts a chain that ends at the root it ships, byte for byte, and nothing else (`TrustRoot`), and checks
  the name: the address on 443, the arena's name on 9001.
- TLS 1.2 and 1.3, forward secrecy and authenticated ciphers alone, no session tickets.
- Behind nginx the traffic is plain, on loopback, or on the private network with more machines.

**What fails closed.**
- Port 80: nothing listens, and the firewall closes it.
- Any path on 443 but `/v1/` and `/lobby`: 404.
- 9001 with no name, a name not in the map, or no TLS: closed.
- A connection to an arena behind the front that does not begin with nginx's PROXY line: closed, and counted
  (`backend_arena_connections_dropped_total{reason="proxy_line"}`).
- A certificate from any other root: refused by the app.
- A players' build given a plain address, or a grant without TLS: it dials nothing.

## 7. The phone's address behind nginx

Each process behind nginx knows the phone's address, from nginx alone:

| Process | From | Used for |
|---|---|---|
| platform | `X-Forwarded-For`'s last entry, from nginx on loopback or an address in `BACKEND_TRUSTED_PROXIES` | its limits on logins and registrations; its logs |
| gateway | the same, on the lobby's upgrade | its logs |
| arena | the PROXY line, on an arena with a TLS name | its logs: every join, and every line that names a connection |

An earlier entry of `X-Forwarded-For` is the phone's to write, and is never read. The arena takes the line from
whoever connects, since only nginx reaches an arena behind the front: it is bound to loopback, or to the private
network, which the firewall closes to the internet.

## 8. More machines

The phone's routes stay the same; only nginx's targets change. With a standby
([10](10-two-machines.md)), B's arenas have names of their own, `b1.arena.internal` and on, which the front's nginx
sends to B's private address. With three machines, `backend-upstreams.conf` spreads the API and the lobby over the
three platforms and gateways, and each arena's name in the map points at its own machine; the release's files are
already that layout (§4.4, §4.5).

## 9. Checking it

By hand, on the machine or from anywhere that reaches the address:

```bash
# 443: the certificate names the address and chains to the backend's root
openssl s_client -connect 203.0.113.10:443 -verify_ip 203.0.113.10 -CAfile ca.pem </dev/null | grep 'Verify return'
# the API answers through it (a guest made: a real one, which retention erases if nobody plays it)
curl --cacert ca.pem -X POST https://203.0.113.10/v1/guests
# 9001: an arena's name is served, verified against that name
openssl s_client -connect 203.0.113.10:9001 -servername a1.arena.internal -verify_hostname a1.arena.internal \
    -CAfile ca.pem </dev/null | grep 'Verify return'
# where nginx sent each match connection, and where each player joined from
tail /var/log/backend-nginx/stream.log
journalctl -u backend-arena@a1 | grep 'joined from'
```

`install-certificate.sh --check` says what each port serves and until when ([06](06-certificates.md)).

The scripts that check these routes as written:

| Script | What it proves |
|---|---|
| `check-units-el9.sh` | the units in RHEL 9's (and 8's) systemd: a registration through nginx over HTTPS, a match connection on 9001 verified as `a1.arena.internal` and passed to the arena |
| `check-ports-el9.sh` | 05 and 06 as written, in Rocky 9.5 with firewalld: two machines behind the front, the C# client playing its scenarios from a player's address through 443 and 9001, every connection to the arena its name gives, a name not in the map refused, and the arenas naming the player's address, not nginx's |
| `check-install-guide-el9.sh` | the install guide as written: a registration through nginx, every arena's name on 9001 |
| `check-two-machines-el9.sh` | matches by the C# client on B's arenas through A's nginx |
| `check-nginx-logs.sh` | the logs' rotation |
| `dev-nginx.sh test`, `dev-backend.sh test` | a run by hand, TLS or plain: nginx's configuration, its certificate, two bots registered, in the lobby and in a match through nginx |
