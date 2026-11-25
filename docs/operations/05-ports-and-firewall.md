# Ports and the firewall

This page lists every port the backend uses in production, for operators who install the machines and set up their
firewalls. For each port it says what listens on it, on which address, who connects to it and from where, and which
setting names it. It covers the three-machine layout (A, B and C), the launch's one machine and the two-machine
standby. Then it gives the firewall's commands for RHEL 9's firewalld on each machine, how to check the result, and
what to change together when a port changes. The ports of development and of the drills are listed apart, at the end
(§8), so that they are not mistaken for production's. The certificate the front serves is
[06](06-certificates.md)'s, and the routes from the phone through nginx, file by file, are
[11](11-phone-to-backend-routes.md)'s. Words such as front, arena, TLS name and `backend` zone are in the
[glossary](../glossary.md).

**The site has one public address**: an IP address, with no domain. The provider forwards it to one machine, the
front ([D-82](../architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name),
[C-9](../requirements/01-scope-and-nfrs.md#3-constraints)). Players reach three ports there:
- **443**, the API and the lobby, over TLS;
- **9001**, match traffic, over TLS;
- **80**, the API, the lobby and match traffic in clear, for a client without TLS
  ([D-115](../architecture/03-decision-log.md#d-115--a-players-build-may-connect-without-tls-and-the-site-serves-it-on-80-matches-included)).

On 443 and 9001 the front's nginx ends TLS (it decrypts the connection). On all three ports it then passes the
request or the connection to a process behind it: on the same machine over loopback, or on another machine over the
private network. The other machines have no public address. Operators come in by SSH, from the internal network
alone.

**How this page was made.** Every port here was read from the file that sets it (the units, the example settings,
the nginx and MySQL configurations), and from the code where the code picks its address. The listeners and the
firewall were then run as this page gives them; §9 says how, and what was not run.

The addresses are the examples':
- the site's public address, `203.0.113.10`;
- the private network: A `10.0.0.1`, B `10.0.0.2`, C `10.0.0.3`;
- the internal network operators come from, `10.0.0.0/24`.

Put your own addresses in their place. The front is C in the examples, but any machine can be the front, because all
three are set up the same way.

## 1. Three kinds of port

| Kind | Who reaches it | Bound to | The firewall |
|---|---|---|---|
| **Public** | players' devices, through the provider's forwarding to the front | every address | opened to everyone (the `public` zone, §5) |
| **Private** | the other two machines, and nothing else | the machine's private address, or that and `127.0.0.1` (the stores) | opened to the other two machines' addresses alone (the `backend` zone, §5) |
| **Loopback** | this machine's own processes; an operator over SSH | `127.0.0.1` | nothing to open: no other machine can reach a loopback address, whatever the firewall says |

**Exactly what the internet reaches** on the front, after §5's commands:

| Port | What |
|---|---|
| 443/tcp | nginx: HTTPS, the API (`/v1/`) and the lobby (`/lobby`) |
| 9001/tcp | nginx's `stream` module: match traffic. nginx ends its TLS there and passes each connection to its arena |
| 80/tcp | nginx in clear, for a client without TLS: the API and the lobby as on 443, and matches, each asking for its arena by name (`/match/<name>`) ([D-115](../architecture/03-decision-log.md#d-115--a-players-build-may-connect-without-tls-and-the-site-serves-it-on-80-matches-included)) |

Nothing else is reachable, over IPv4 or IPv6:
- **22**, SSH, is open to the internal network alone.
- **9090**, cockpit, which RHEL's default zone allows, is closed.
- `dhcpv6-client`, the default zone's third service, takes DHCPv6 replies on UDP 546 from link-local addresses
  alone, which no other network can send.
- ICMP stays allowed, so a machine answers ping. ICMP has no ports.

**The provider forwards 80 to 80, 443 to 443 and 9001 to 9001** on the front. The numbers must not change on the
way: the arenas announce 9001, and players dial it, so a forwarding that changed the number would send them to the
wrong port. When the front changes in a failover, the provider forwards the same three ports to another machine.

## 2. Every port in production

### On every machine: A, B and C

Every machine runs nginx, so that any machine can be the front. Only the front's nginx is reached from outside.

| Port | What listens | Bound to | Who connects | Set in |
|---|---|---|---|---|
| **443**/tcp | nginx (`backend-nginx`): HTTPS, ending TLS. `/v1/` goes to the platforms, `/lobby` to the gateways, of all three machines | every address, IPv4 and IPv6 | players | `listen 443 ssl` in `/etc/backend/nginx/backend.conf`; the upstreams in `backend-upstreams.conf` |
| **9001**/tcp | nginx's `stream`: ends match traffic's TLS, and passes each connection to the arena its handshake named (the TLS name, sent as SNI) | every address, IPv4 and IPv6 | players | `listen 9001 ssl` and its map in `/etc/backend/nginx/backend-stream.conf`, which reads the list of arenas, `backend-arenas.conf` |
| **80**/tcp | nginx in clear (D-115): 443's server, with the same locations, and `/match/<name>` to the arena by the same list | every address, IPv4 and IPv6 | players without TLS | `listen 80` in `backend.conf` |
| **22**/tcp | sshd, the system's own | every address | operators, from the internal network alone (§5) | the system |
| 8080 | the platform (`backend-platform`): the API, plain HTTP | the machine's private address | the front's nginx; this machine's gateway | `PLATFORM_BIND`, `PLATFORM_PORT` in `platform.env`; `PLATFORM_URL` in `gateway.env`; `backend-upstreams.conf` |
| 8090 | the gateway (`backend-gateway`): the lobby, plain WebSocket | the machine's private address | the front's nginx | `GATEWAY_BIND`, `GATEWAY_PORT` in `gateway.env`; `backend-upstreams.conf` |
| 9011, 9012, and 9013 on B | the arenas (`backend-arena@<name>`): match traffic, plain TCP behind nginx. One port each, §3 | the machine's private address | the front's nginx | `ARENA_PORT`, `ARENA_BIND` in `arena-<name>.env`; `backend-arenas.conf` |
| 9120 | the platform's admin API, only when it is named (unset, there is none) | loopback, enforced: given any other address, the platform refuses to start (exit 2) | an operator on the machine, or over SSH: `ssh -L 9120:127.0.0.1:9120 <machine>` ([api/03](../api/03-admin-api.md)) | `BACKEND_ADMIN_ADDR` in `platform.env`, with its secret, `BACKEND_ADMIN_TOKEN`, in the same file (D-102) |
| 9101 | the platform's metrics, `GET /metrics` | `127.0.0.1` | this machine's platform's watch ([08](08-monitoring.md)) | `BACKEND_METRICS_ADDR` in `platform.env` |
| 9103 | the gateway's metrics | `127.0.0.1` | the watch | `BACKEND_METRICS_ADDR` in `gateway.env` |
| 9110, 9111, and 9113 on B | the arenas' metrics, one port per arena (§3) | `127.0.0.1` | the watch | `BACKEND_METRICS_ADDR` in `arena-<name>.env` |

The gateway's default port in the code is 8081. The shipped settings give it 8090, and nginx's upstreams name 8090.
So a gateway started by hand without `GATEWAY_PORT` would not be the one nginx sends the lobby to.

### On some machines only

| Port | What listens | Machine | Bound to | Who connects | Set in |
|---|---|---|---|---|---|
| **6379**/tcp | j-redis `session`: sessions, tickets, the arena directory, leaderboards, the channels of the pushes and the commands. The primary on A, its replica on B | A, B | `127.0.0.1` and the machine's private address (01 §3) | every platform, gateway and arena on A, B and C, and the workers on C. Each is given both addresses, and asks each which one is the primary (D-34). On A, also B's replica, which replicates from it. The store's scripts, on its own machine by `127.0.0.1`, and a promotion from B | `port` and `bind` in `/etc/backend/store-session.conf` |
| **6380**/tcp | j-redis `events`: the stream of match results (D-7). The primary on B, its replica on C | B, C | `127.0.0.1` and the private address | the arenas on A, B and C, and the workers on C (both addresses each). On B, also C's replica, which replicates from it; a promotion from C | `port` and `bind` in `/etc/backend/store-events.conf` |
| **3306**/tcp | MySQL (`backend-mysql`), TLS required. The primary on A, its replica on C | A, C | the private address alone, not `127.0.0.1` (`bind-address`) | every platform on A, B and C, and the workers on C. Each is given both hosts, and asks each which one is writable (06 §10). On C: the replica (its replication, and the clone that made it), the binlog stream, the nightly dump and the proofs, all reading A's | `bind-address` in `/etc/backend/mysql/backend.cnf`; no `port` line, so MySQL's default, 3306 |
| 9102, 9112 | the workers' metrics, `backend-worker@c1` and `@c2` | C | `127.0.0.1` | the watch | `BACKEND_METRICS_ADDR` in `/etc/backend/worker-<id>.env` |
| 3309 | the scratch MySQL server that the weekly and monthly proofs restore onto (01 §10) | C | `127.0.0.1` | the proofs, on C | `port` and `bind-address` in `/etc/backend/scratch-my.cnf` |

On the database's machine, root uses MySQL through its socket, `/run/backend-mysql/mysqld.sock`, which is a file and
not a port. A process on A reaches MySQL at `10.0.0.1`, A's own private address, and never at `127.0.0.1`, where
nothing listens.

### On the launch's one machine

On the launch's one machine every port of both tables is on that machine
([D-93](../architecture/03-decision-log.md#d-93--the-site-launches-on-one-machine-for-up-to-about-5-000-players)):

- three arenas, on 9011, 9012 and 9013, with their metrics on 9110, 9111 and 9113;
- two workers, with their metrics on 9102 and 9112;
- everything except nginx's 80, 443 and 9001, and SSH, on `127.0.0.1` alone. That includes MySQL and both stores,
  which no other machine needs to reach.

So its firewall opens 80, 443 and 9001, and SSH to the internal network, and nothing private
([03 §12](03-install-guide.md#12-open-the-firewall)).

### On two machines, the second the first's standby

This is the one machine's layout, with one change: MySQL (3306) and both stores (6379, 6380) also listen on the
machine's private address, on A and on B
([D-96](../architecture/03-decision-log.md#d-96--a-second-machine-is-the-first-ones-standby-the-replicas-and-a-front-ready-to-start)).
B's replicas reach A's primaries there, and after a promotion A's processes reach B's. Each machine's firewall opens
those three ports to the other machine's address alone, and nothing else private
([10 §8](10-two-machines.md#8-the-firewall)). B's other ports are A's ports; they listen only once B is started in
A's place.

With B's own arenas
([D-97](../architecture/03-decision-log.md#d-97--arenas-on-the-standby-three-of-its-own-behind-as-nginx-watched-from-a),
[10 §10](10-two-machines.md#10-arenas-on-b)): `b1` to `b3` listen on B's private address, on 9011 to 9013, with their
metrics there too, on 9110, 9111 and 9113. All of them are opened to A alone: A's nginx passes them their matches,
and A's watch reads their metrics. Nothing of A's opens for them: they reach A's stores on the ports B already
reaches.

### What does not listen, and is sometimes looked for

- **33060**, MySQL's X Protocol: `mysqlx = OFF` in both MySQL configurations. If it were on, it would listen on every
  address, whatever `bind-address` says.
- **33062**, MySQL's administrative port: not configured (`admin_address` is not set).
- **No debugger and no JMX port**: the units start Java with neither. The debugger ports of §8 are for development
  alone.
- **No UDP port** of the backend's: match traffic is TCP.
- **One port per store**: a j-redis replica replicates over its primary's port, and MySQL's replication and clone use
  3306 too.
- **The worker serves nothing but its metrics.** It, the binlog stream, the dumps, the proofs and the copy off the
  site only open connections; they listen on nothing.

## 3. The arenas: their ports and their names

Every player dials the site's 9001. The front's nginx tells the arenas apart by the name the client's TLS handshake
gives (SNI), which the platform handed the client with its grant (`tlsName`). A client without TLS sends the same
name in its request on 80, `GET /match/<name>` (D-115). Each arena announces:
- the site's address, `ADVERTISE_HOST=203.0.113.10`;
- the site's port, `BACKEND_ADVERTISE_PORT=9001`;
- its name, `BACKEND_ADVERTISE_TLS_NAME`.

Each arena listens on a private port of its own, since 9001 is nginx's on every machine:

| Arena | Its name (`BACKEND_ADVERTISE_TLS_NAME`) | Listens on (`ARENA_BIND`:`ARENA_PORT`) | Its metrics |
|---|---|---|---|
| `a1`, `a2` | `a1.arena.internal`, `a2.arena.internal` | `10.0.0.1:9011`, `10.0.0.1:9012` | `127.0.0.1:9110`, `9111` |
| `b1`, `b2`, `b3` | `b1.arena.internal` … `b3.arena.internal` | `10.0.0.2:9011` … `10.0.0.2:9013` | `9110`, `9111`, `9113` |
| `c1`, `c2` | `c1.arena.internal`, `c2.arena.internal` | `10.0.0.3:9011`, `10.0.0.3:9012` | `9110`, `9111` |

On the launch's one machine the arenas are `a1` to `a3`, on `127.0.0.1:9011` to `9013` (03 §8).

- **A name is a label, not a DNS name.** No DNS server holds it: a player dials the address and only sends the name.
  `.internal` is the suffix reserved for such private use.
- **Three places name an arena, and they must agree**:
  - its `BACKEND_ADVERTISE_TLS_NAME`;
  - its line in `backend-arenas.conf`, which maps the name to its machine's private address and its `ARENA_PORT`.
    It is the same file on every machine, and both of nginx's maps read it: 9001's and 80's;
  - the site's certificate, which names every arena ([06](06-certificates.md)).

  If a name is missing from the list, nginx closes the connection on 9001 and answers 404 on 80. If it is missing
  from the certificate, a client with TLS refuses the connection.
- **9112 is skipped** for an arena's metrics, because it is the second worker's port on C: so a third arena on C
  cannot take it. When two processes ask for one port, the second one stops with exit 2, which systemd does not
  restart.
- **No process connects to an arena** except the front's nginx. The platform's commands reach an arena through the
  session store.
- **Without the front**, on a machine alone or in a drill, an arena may end TLS itself, with a keystore and no TLS
  name (01 §8). That is not the site's layout.

## 4. Who connects to whom

### Inside a machine

- The gateway connects to its own machine's platform, on that machine's private address.
- The front's nginx connects to its own machine's platform, gateway and arenas the same way, by the private address.
  That is loopback traffic, which the firewall lets through.
- The platform's watch connects to the metrics ports ([08](08-monitoring.md)). An operator on the machine, or through
  an SSH tunnel, connects to 9120.
- The store's backup and its restore drill (`backup-store.sh`, `store-restore-drill.sh`) connect to `127.0.0.1` on
  the store's port. The proofs on C connect to `127.0.0.1:3309`.
- The promotions (`promote-store.sh`, `promote-mysql.sh`) run on the replica's machine. They also reach the old
  primary, on its own machine (the table below).

### Between the machines: the private network

| From | To | Port | Why |
|---|---|---|---|
| the front's nginx | A, B and C | 8080, 8090 | the API and the lobby, passed on; a machine that fails is passed over |
| the front's nginx | each arena's machine | 9011 to 9013 | match traffic, by the arena's name |
| each platform's watch | the other two machines | 8080 | `GET /health`, so that the two others see a machine lost ([08](08-monitoring.md), D-86); on a port already open |
| A, B, C: platform, gateway, arenas; C: workers | A and B | 6379 | the `session` store, its primary and its replica |
| A, B, C: arenas; C: workers | B and C | 6380 | the `events` store, its primary and its replica |
| A, B, C: platform; C: workers | A and C | 3306 | MySQL, its primary and its replica |
| B: the `session` replica | A | 6379 | replication |
| C: the `events` replica | B | 6380 | replication |
| C: the MySQL replica, the binlog stream, the dump, the proofs | A | 3306 | replication, the clone, the copies off the database's machine (01 §10) |
| B: `promote-store.sh` for `session`; C: for `events`, and `promote-mysql.sh` | A and B | 6379 and 3306 on A, 6380 on B | a promotion asks the old primary whether it still answers, and fences it with `--demote-old` (runbook §2) |

After a failover the roles move to another machine, and the ports stay where they are. For example, if C's MySQL is
promoted, A's rebuilt server clones and replicates from C's 3306 (runbook §2). If the front changes, the provider
forwards to another machine, whose nginx already has everything. §5's firewall allows both, because each machine
opens its ports to both of the others.

### From the internet

Players connect to the front's 443 and 9001, or to 80 without TLS, and nothing else. The app opens three
connections, each to the site's one public address, and each through nginx: the API, the lobby and a match. A client
with TLS uses 443 and 9001, where nginx ends TLS. A client without TLS uses 80 for all three
([D-115](../architecture/03-decision-log.md#d-115--a-players-build-may-connect-without-tls-and-the-site-serves-it-on-80-matches-included)).
On the launch's one machine:

```
 the app                                 the front (one public address, 203.0.113.10)
 ① API    https://203.0.113.10/v1/...  ─▶ :443  nginx, TLS ended ──▶ platform  127.0.0.1:8080
 ② lobby  wss://203.0.113.10/lobby     ─▶ :443  nginx, TLS ended ──▶ gateway   127.0.0.1:8090 ──▶ platform
 ③ match  TLS to 203.0.113.10:9001     ─▶ :9001 nginx, TLS ended ──▶ arena a1  127.0.0.1:9011
          (name given: a1.arena.internal)       the arena by that name        a2 :9012, a3 :9013
 in clear: ① http://…/v1/  ② ws://…/lobby  ─▶ :80 nginx, as 443's
           ③ GET /match/a1.arena.internal  ─▶ :80 nginx, the arena by that name ──▶ arena a1, which answers 101
```

| Route | The app dials | Its TLS checks | nginx | Then | Limits and waits |
|---|---|---|---|---|---|
| ① the API | `https://203.0.113.10/v1/…` | the address, in the certificate | `backend.conf`: `/v1/` to the platforms, anything else 404 | the platform (`backend_platform`) | a body of 4 KiB at most, in nginx and in the platform; `POST` to `/v1/sessions`, `/v1/accounts`, `/v1/guests` and `/v1/accounts/upgrade`: 2 a second per address, a burst of 30, then 429; the app waits 10 s for an answer, nginx 15 s |
| ② the lobby | `wss://203.0.113.10/lobby` | the address | `location = /lobby`, the WebSocket's upgrade passed on | the gateway with the fewest connections (`backend_gateway`), which calls its machine's platform | the session token within 5 s; a ping every 30 s; the gateway closes a lobby silent for 120 s, nginx at 150 s |
| ③ a match | TCP to `203.0.113.10:9001`, the TLS handshake giving the grant's `tlsName` | `tlsName` (`a1.arena.internal`), in the certificate | `backend-stream.conf`: the arena the name maps to; a name not in the list, or no name, closed | the arena, plaintext, with the grant's single-use ticket | 5 s to dial; the arena drops a connection after 30 s without a frame, nginx at 60 s |
| ①②③ in clear | `http://203.0.113.10/v1/…`, `ws://203.0.113.10/lobby`, and TCP to `203.0.113.10:80` with `GET /match/<tlsName>` | nothing: no TLS | the same server on 80; `/match/` to the arena the name maps to, the request passed on, 404 for a name not in the list | as above; the arena answers the request with 101, then takes the ticket | as above; the arena reads 1 KiB of request at most |

**The order of a session.**
1. On the app's first launch, it makes a guest (`POST /v1/guests`).
2. It logs in (`POST /v1/sessions`), which gives it a session token. Every other call takes that token.
3. It opens the lobby with the token.
4. A match found arrives on the lobby (`evt.match.found`), or as the answer to `GET /v1/queue` or
   `POST /v1/match-requests`. It holds `arenaHost`, the site's address; `arenaPort`, 9001; `tls`, true; the arena's
   `tlsName`; and a `ticketId`.
5. The app dials that address and port, gives the name, and joins with the ticket.

**What is trusted.** One certificate, from the backend's own CA, names the address and every arena
([06](06-certificates.md)). nginx serves it on 443 and 9001, with TLS 1.2 and 1.3, forward secrecy and authenticated
ciphers alone, and no session tickets. The app trusts the root it ships, and nothing else (`TrustRoot`), and it checks
the name. Behind nginx the traffic is plaintext: on loopback, or on the private network with three machines. A build
connects the way its settings say, encrypted or not; a players' build may connect either way
([D-115](../architecture/03-decision-log.md#d-115--a-players-build-may-connect-without-tls-and-the-site-serves-it-on-80-matches-included)).

**What travels in clear.** On 80, everything a client without TLS sends: its password, its session token and its
join tickets. Anyone on the way can read them, and use them. The owner chose to serve it (Q-64).

**What fails closed.** Any other path on 443 or 80 is answered 404, and so is `/match/` with a name not in the list.
On 9001, a connection without a mapped name, or without TLS, is closed. A certificate from another root is refused
by the app.

**The phone's address behind nginx.**
- The platform and the gateway read it from `X-Forwarded-For`, and only when nginx sent it: from loopback, or from an
  address in `BACKEND_TRUSTED_PROXIES`. The platform's limits on logins go by that address.
- An arena on 9001 reads it from the line nginx begins each match connection with, the PROXY protocol's
  (`proxy_protocol on`,
  [D-105](../architecture/03-decision-log.md#d-105--an-arena-behind-the-front-knows-the-phones-address-nginxs-proxy-line-read-once)),
  and names it where it logs a player: `player 123 joined from 198.51.100.7:51234`.
- For a match through 80, the arena takes it from the `X-Forwarded-For` of nginx's request, without the port.

Nothing an arena decides depends on an address.

**More machines.** The routes stay the same; only nginx's targets change. On two machines, B's arenas have names of
their own, which A's nginx sends to B's private address ([10](10-two-machines.md)). On three, the API and the lobby
are spread over the three platforms and gateways, and each arena's name points at its machine
(`backend-upstreams.conf`, `backend-arenas.conf`).

**A run by hand** on a development machine keeps the same shape
([development/03 §5](../development/03-build-and-run.md#5-running-it-by-hand)):
- by default, the same three routes over TLS, with a root of its own (`work/nginx/ca/ca.pem`), and 80 in clear as on
  the site; behind nginx the platform on 8080, the gateway on 8081 and the arena on 9021;
- with `PLAIN=1`, the API and the lobby on 80 as `http://` and `ws://`, and 9001 without TLS, to its one arena
  (D-101).

### Out to the internet

None of the processes needs the internet. The copy off the site goes out from C by SSH, to a host not chosen yet
(Q-53, 01 §10). firewalld filters what comes in, and lets out what goes out, with its replies, so it needs no rule
for it.

## 5. The firewall on RHEL 9

**What RHEL 9 has already.** firewalld is installed and running, unless the installation turned it off:
`firewall-cmd --state` says `running`. Its default zone is `public`, which allows `ssh`, `dhcpv6-client` and
`cockpit`. Every network interface that is not given a zone is in it. So, untouched, players reach SSH, and cockpit's
9090 where cockpit runs, and nothing of the backend's; and the other two machines reach neither store, nor MySQL
(seen in §9).

**What these commands do.** They set up two zones on each machine:
- **`public`**, for players and anyone not named below: 80, 443 and 9001, the same on every machine, since any
  machine may be the front. 80 is there for the clients without TLS (D-115). Cockpit and SSH are taken out of it, and
  SSH comes back for the internal network alone, by a rule for its addresses.
- **`backend`**, a new zone. Its sources are the other two machines' private addresses, and it opens the ports they
  reach on this machine, and SSH. firewalld decides a connection from those addresses by this zone alone, not by
  `public`, which is why SSH is repeated here. A machine on the private network at any other address falls to
  `public`, and is refused the stores, MySQL, the platforms and the arenas, as the internet is (§9).

The rules are by address, not by network interface, so they hold whichever interface the private network is on.
`--permanent` writes them to `/etc/firewalld/zones/`, and `--reload` applies them all at once. A rule added without
`--permanent` would be gone at the next reload or boot.

**Run them from where SSH stays open**: the console, one of the other two machines, or the internal network. A
session from anywhere else is not cut, since firewalld's `--reload` keeps the connections already made (§9 saw one
go on). But it is the last one: once it ends, SSH from there is refused. `echo "${SSH_CONNECTION%% *}"` shows where
the current session comes from.

**Machine A**: the `session` store's primary, MySQL's primary, arenas `a1` and `a2`.

<!-- firewall A -->
```bash
# The public side, the same on every machine since any may be the front: 80, 443 and 9001 alone
firewall-cmd --permanent --zone=public --add-service=https --add-service=http
firewall-cmd --permanent --zone=public --add-port=9001/tcp
firewall-cmd --permanent --zone=public --remove-service=cockpit --remove-service=ssh
# SSH from the internal network alone (10.0.0.0/24 stands for it)
firewall-cmd --permanent --zone=public --add-rich-rule='rule family="ipv4" source address="10.0.0.0/24" service name="ssh" accept'
# B and C, by their private addresses: SSH, the session store, MySQL, and for the front's nginx
# the platform, the gateway and this machine's arenas
firewall-cmd --permanent --new-zone=backend
firewall-cmd --permanent --zone=backend --add-source=10.0.0.2 --add-source=10.0.0.3
firewall-cmd --permanent --zone=backend --add-service=ssh
firewall-cmd --permanent --zone=backend --add-port=6379/tcp --add-port=3306/tcp --add-port=8080/tcp --add-port=8090/tcp --add-port=9011-9012/tcp
firewall-cmd --reload
```
<!-- /firewall -->

**Machine B**: the `session` store's replica, the `events` store's primary, arenas `b1` to `b3`.

<!-- firewall B -->
```bash
# The public side, the same on every machine since any may be the front: 80, 443 and 9001 alone
firewall-cmd --permanent --zone=public --add-service=https --add-service=http
firewall-cmd --permanent --zone=public --add-port=9001/tcp
firewall-cmd --permanent --zone=public --remove-service=cockpit --remove-service=ssh
# SSH from the internal network alone (10.0.0.0/24 stands for it)
firewall-cmd --permanent --zone=public --add-rich-rule='rule family="ipv4" source address="10.0.0.0/24" service name="ssh" accept'
# A and C, by their private addresses: SSH, both stores, and for the front's nginx the platform,
# the gateway and this machine's arenas
firewall-cmd --permanent --new-zone=backend
firewall-cmd --permanent --zone=backend --add-source=10.0.0.1 --add-source=10.0.0.3
firewall-cmd --permanent --zone=backend --add-service=ssh
firewall-cmd --permanent --zone=backend --add-port=6379/tcp --add-port=6380/tcp --add-port=8080/tcp --add-port=8090/tcp --add-port=9011-9013/tcp
firewall-cmd --reload
```
<!-- /firewall -->

**Machine C**, the front in the examples: the `events` store's replica, MySQL's replica, arenas `c1` and `c2`. The
workers, the backups and the scratch server open nothing: they only connect, or listen on loopback.

<!-- firewall C -->
```bash
# The public side, the same on every machine since any may be the front: 80, 443 and 9001 alone
firewall-cmd --permanent --zone=public --add-service=https --add-service=http
firewall-cmd --permanent --zone=public --add-port=9001/tcp
firewall-cmd --permanent --zone=public --remove-service=cockpit --remove-service=ssh
# SSH from the internal network alone (10.0.0.0/24 stands for it)
firewall-cmd --permanent --zone=public --add-rich-rule='rule family="ipv4" source address="10.0.0.0/24" service name="ssh" accept'
# A and B, by their private addresses: SSH, the events store, MySQL, and for the front's nginx
# the platform, the gateway and this machine's arenas
firewall-cmd --permanent --new-zone=backend
firewall-cmd --permanent --zone=backend --add-source=10.0.0.1 --add-source=10.0.0.2
firewall-cmd --permanent --zone=backend --add-service=ssh
firewall-cmd --permanent --zone=backend --add-port=6380/tcp --add-port=3306/tcp --add-port=8080/tcp --add-port=8090/tcp --add-port=9011-9012/tcp
firewall-cmd --reload
```
<!-- /firewall -->

**What you will see.** Each command prints `success`. Services and ports are in separate commands on purpose:
firewalld takes several of one option in a command, but refuses `--add-service` and `--add-port` together
(`argument --add-port: not allowed with argument --add-service`), and then applies none of that command. Run a second
time, `--new-zone` says `Error: NAME_CONFLICT: new_zone(): 'backend'`, and the others say
`Warning: ALREADY_ENABLED: …` or `NOT_ENABLED: …` and then `success`: they change nothing.

**An operator on IPv6** needs the SSH rule again, with `family="ipv6"` and that network's addresses.

**Later changes:**
- **An arena added** on a machine: open its private port in that machine's `backend` zone,
  `firewall-cmd --permanent --zone=backend --add-port=9013/tcp && firewall-cmd --reload`. Nothing public changes:
  players reach it through 9001, or 80, by its name (§3, 04 §2.4).
- **A machine added** to the private network, `10.0.0.4` for example: add it as a source on each machine,
  `firewall-cmd --permanent --zone=backend --add-source=10.0.0.4 && firewall-cmd --reload`. Its own `backend` zone
  holds the other machines' addresses.
- **A machine replaced** by one with a new address: remove its old address in the same way, with `--remove-source`.
- **A provider's firewall** in front of the machines (a cloud's security group, for example) needs the same rules:
  80, 443 and 9001 from anywhere to the front, SSH from the internal network, and the private ports from the three
  machines' addresses alone.

## 6. Checking

**What listens**, on the machine (`-p` names the process; run it as root):

```console
ss -ltnp
```

On A, with the admin API named, the backend's lines are these, as the check of §9 printed them (sshd's 22, the
system's, is left out):

```
0.0.0.0:443                nginx
[::]:443                   nginx
0.0.0.0:9001               nginx    match traffic's TLS
[::]:9001                  nginx
10.0.0.1:3306              mysqld
10.0.0.1:6379              java     the session store
127.0.0.1:6379             java     the session store
[::ffff:10.0.0.1]:8080     java     the platform
[::ffff:10.0.0.1]:8090     java     the gateway
[::ffff:10.0.0.1]:9011     java     arena a1
[::ffff:127.0.0.1]:9101    java     the platform's metrics
[::ffff:127.0.0.1]:9103    java     the gateway's metrics
[::ffff:127.0.0.1]:9110    java     a1's metrics
[::ffff:127.0.0.1]:9120    java     the admin API
```

Since D-115, nginx also listens on 80, on every address: `0.0.0.0:80` and `[::]:80`, both `nginx`. That listing
predates it.

- **`[::ffff:10.0.0.1]` is `10.0.0.1`** on a Java socket, which is IPv6: an IPv4 address on an IPv6 socket, reached
  over IPv4 alone. Search for `:8080`, not for `10.0.0.1:8080`, which does not match it. The store shows plain IPv4,
  because its unit sets `-Djava.net.preferIPv4Stack=true`.
- **Every JVM shows as `java`**: `ss -ltnp` gives its process id too, and `ps -o args= -p <pid>` its main class.

On B the stores' lines are 6379 and 6380, and its arenas' 9011 to 9013, and there is no MySQL. On C: 6380, MySQL's
3306 on `10.0.0.3`, the workers' 9102 and 9112, and the scratch server's `127.0.0.1:3309`.

A port on the wrong address is the setting's fault, and the firewall cannot mend it. A store on `127.0.0.1` alone
cannot be reached by the other machines, and a platform on `0.0.0.0` is open to anyone the firewall lets in.

**What the firewall allows**:

```console
firewall-cmd --get-active-zones           # backend: its sources; public: the interfaces
firewall-cmd --zone=backend --list-all
firewall-cmd --zone=public --list-all
```

**From another machine**, with bash alone (no tool to install):

```console
timeout 3 bash -c '</dev/tcp/10.0.0.1/6379' && echo open || echo closed
```

From B and C, A's 6379, 3306, 8080, 8090 and its arenas' ports are `open`. From outside, the front's 80, 443 and
9001 are `open`, and 22, 6379, 3306, 8080, 9011 and 9120 are not.

**Where nginx sent each request and connection**, on the front:

```console
tail /var/log/backend-nginx/access.log    # each request, and its machine: $upstream_addr
tail /var/log/backend-nginx/stream.log    # each match connection: the name asked for, the arena given
```

`access.log` holds the requests on 443 and on 80, a match through 80 among them; `stream.log` the connections on
9001. An `$upstream_addr` of two addresses, `10.0.0.2:8080, 10.0.0.1:8080`, is a machine passed over: nginx tried the
first, which failed, and was served by the second.

**Reading a failure** (§9 saw the first three):

| The message | Means |
|---|---|
| `No route to host` over IPv4, `Permission denied` over IPv6, at once | the firewall refused it: the port is not opened to this address |
| `Connection refused`, at once | it reached the machine, and nothing listens on that address and port: the process is down, or bound to another address (`127.0.0.1`, say) |
| nothing, until the timeout | dropped on the way: a firewall in between that drops rather than refuses, such as a provider's, or the wrong address |

## 7. Changing a port

Each port is named in more than one place. Change all of them together, or the parts stop finding each other. The
"Then" column says what to restart or reload afterwards.

| Port | Change together | Then |
|---|---|---|
| The platform's, 8080 | `PLATFORM_PORT` in `platform.env`; `PLATFORM_URL` in `gateway.env`; the three `server …:8080` lines in `backend-upstreams.conf`, on every machine; this machine's entry in `BACKEND_WATCH_PEERS` in the other two machines' `platform.env` ([08 §2](08-monitoring.md)); the `backend` zone's port on every machine | restart the platform and the gateway, then the other two machines' platforms; reload nginx |
| The gateway's, 8090 | `GATEWAY_PORT` in `gateway.env`; the three `server …:8090` lines in `backend-upstreams.conf`, on every machine; the `backend` zone's port | restart the gateway; reload nginx |
| An arena's, 9011 to 9013 | its `ARENA_PORT`; its line in `backend-arenas.conf`, on every machine; the `backend` zone's port on its machine | restart that arena (it drains first: 01 §5); reload nginx |
| An arena's name | its `BACKEND_ADVERTISE_TLS_NAME`; its line in `backend-arenas.conf`, on every machine; the site's certificate, issued again with it ([06](06-certificates.md)) | install the certificate; restart that arena; reload nginx |
| A metrics port | its `BACKEND_METRICS_ADDR` alone, unique on the machine; and `BACKEND_WATCH_TARGETS` in `platform.env`, whose watch reads it ([08](08-monitoring.md)), with a platform restart | restart that process |
| The admin API's | `BACKEND_ADMIN_ADDR`, loopback only; and the port in the SSH tunnel | restart the platform |
| A store's, 6379 or 6380 | `port` in that store's configuration on both machines, primary and replica; the replica's `replicaof`; on every machine, `STORE_PORT` and `BACKEND_STORE_ADDRESSES` (6379) or `BACKEND_EVENTS_STORE` (6380) in every `.env` file that has them; the `backend` zone's port on both store machines; the port argument of the store's scripts (`backup-store.sh`'s third, the restore drill's second, the old primary's `host:port` given to `promote-store.sh`) | restart the stores, then every process |
| MySQL's, 3306 | a `port` line in `backend.cnf`, on A and C; every `BACKEND_DB_URL`; a `port=` line in the backups' option files (`mysql-backup.cnf`, `mysql-binlog.cnf`); the replica's `SOURCE_PORT` and `clone_valid_donor_list`; the old primary's `host:port` given to `promote-mysql.sh`; the `backend` zone's port on A and C | restart MySQL, then the platforms and the workers |
| The public 9001 | `listen 9001` and `listen [::]:9001` in `backend-stream.conf`; every arena's `BACKEND_ADVERTISE_PORT`; `install-certificate.sh`'s `STREAM_PORT`; the `public` zone's port; the provider's forwarding | reload nginx; restart the arenas |
| The public 443 | `listen 443` and `listen [::]:443` in `backend.conf`; the app's endpoints (`https://203.0.113.10:<port>`), a client release; `install-certificate.sh`'s `EDGE_PORT`; the `public` zone (its `https` service becomes an `--add-port`); the provider's forwarding | reload nginx |
| The public 80 | `listen 80` and `listen [::]:80` in `backend.conf`; the endpoints of an app without TLS (`http://203.0.113.10:<port>`), which its matches go through too, a client release; the `public` zone (its `http` service becomes an `--add-port`); the provider's forwarding | reload nginx |

## 8. Ports that are not production

These ports are on a developer's machine, not on A, B or C. All of them are bound to `127.0.0.1`, except nginx's.

| Port | What | Started by |
|---|---|---|
| 3314 | the development MySQL (`backend_test`, `backend_dev`), `vendor/`'s 8.4 | `dev-mysql.sh`, and `build-offline.sh` for the tests (D-80) |
| 6380, 8080, 8081, 9021 | the store, the platform, the gateway, the arena | `dev-jredis.sh`, `dev-backend.sh`, or `run-local.sh start` ([development/03](../development/03-build-and-run.md), D-92) |
| 80, 443, 9001, every IPv4 address | the release's nginx in front of them, as on the site: the API and the lobby on 80 in clear and on 443 over TLS, matches on 9001 and on 80 by name. With `PLAIN=1`, 80 and 9001 alone, without TLS: the API and the lobby on 80, matches on 9001 (D-101) | `dev-nginx.sh`, as root |
| 5005 to 5009 | the debugger, one port per process | `DEBUG=1 ./run-local.sh start` ([development/05](../development/05-vscode.md)) |
| 6390, 6392, 8093, 8094, 9011, 9195 to 9199, 3307, 3308 | the live drill's processes, MySQL servers and metrics | `client/headless-drill.sh` |
| 9193, 9194 | with `IDLEGUEST=1`, a second worker's metrics; with `WATCH=1`, the drill's receiver of alerts | `client/headless-drill.sh` |
| 8095, 8096, 9095, every IPv4 address | with `FRONT=https`, `http` or `plain`, the drill's own nginx, as `dev-nginx.sh` sets it up: 8095 for 80, 8096 for 443, 9095 for 9001 (D-107) | `client/headless-drill.sh` |
| 3307, 3309 | the backup drill's MySQL servers | `backup-drill.sh` |

The catalogue in [deploy/README](../../backend/deploy/README.md#ports) says which drill uses which port.

**`run-local.sh` and its scripts are not for a production machine.** Their ports are production's (8080, 80, 443,
9001), and 6380 is the `events` store's port on B and C. Each script refuses a port already taken on `127.0.0.1`
(`dev-nginx.sh` on every address). But on the three-machine layout the units bind their private address, so on a
production machine much of it is free on `127.0.0.1`: 3314 always, and on A 6380, 8080, 8081 and 9021 too.
`run-local.sh` would start those, and stop only at nginx's ports, which production's nginx holds.

## 9. What was checked, and what was not

**Run as written** by
[`backend/scripts/check-ports-el9.sh`](../../backend/scripts/README.md#check-ports-el9sh): **on 2026-10-19**, 156
checks, none failing; **again on 2026-11-25**, with 80 open (D-115), 181 checks, none failing.
- **Where**: Rocky Linux 9.5, which is rebuilt from RHEL 9's sources, with RHEL 9's firewalld (1.3.4), systemd as its
  first process, and no network.
- **What ran**, from a release built from the code as it is now, each process with the settings the release ships:
  - on A, the front here (`10.0.0.1`, and the public `198.51.100.1`): MySQL with TLS (01 §9's commands), the session
    store, the platform, the gateway, the arena `a1` with 12 rooms, and nginx with the shipped configuration;
  - on B (`10.0.0.2`), in a network namespace of its own: its platform, gateway and arena `b1`, on A's store and
    MySQL, through A's firewall;
  - C's first worker and its scratch MySQL, on A, for their ports; and a stand-in for sshd.

  They ran as root, with small heaps. Neither changes where they listen.
- **The certificate**: a root and the site's certificate made by `private-ca.sh`, for `198.51.100.1` and the list's
  seven names, and installed by `install-certificate.sh` before nginx had ever started, as [06](06-certificates.md)
  says.
- **Who connected**: C (`10.0.0.3`), a machine of the internal network (`10.0.0.50`) and a player (`198.51.100.7`),
  each in a namespace of its own. The player also came over IPv6, and also routed to the private addresses through
  the public link, as a neighbour on a provider's network could.

**What listens**: §2's ports on §2's addresses, and nothing else; §6's listing is the check's. Nothing on 33060 or
33062. Since 2026-11-25, 80 on every address, IPv4 and IPv6. The arena `a1` announced
`198.51.100.1:9001, TLS as a1.arena.internal`.

**The firewall**, tested three times: stopped; as RHEL 9 has it; and after §5's commands for A, taken from this page.

| From | To A's | Stopped | As RHEL 9 has it | After §5's commands |
|---|---|---|---|---|
| a player | 443, 9001 (over IPv6 too) | open | refused | open |
| a player | 22 | open | open | refused (IPv6 too) |
| a player | 80 (over IPv6 too) | open (2026-10-19, before D-115: `Connection refused`, nothing listened) | refused | open (before D-115: refused) |
| a player | 9090 | `Connection refused`: nothing listens | let through | refused |
| a player | 8080, 9011, 9120 on the public address | `Connection refused`: bound elsewhere | refused | refused |
| a player routing to `10.0.0.1` | 6379, 3306, 8080, 9011 | **open** | refused | refused |
| B | 6379, 3306, 8080, 8090, 9011 | open | refused | open |
| B | 22 | open | open | open |
| B | 443 | open | refused | refused: the `backend` zone decides alone |
| C | 6379, 9011 | open | refused | open |
| the internal network's machine | 22 | open | open | open: the rule for 10.0.0.0/24 |
| the internal network's machine | 6379, 8080, 9011 | open | refused | refused |
| the internal network's machine | 443 | open | refused | open, as for anyone |
| A itself | its loopback and private ports; out to the player | open | open | open |

"Refused" was `No route to host` over IPv4 and `Permission denied` over IPv6, at once. A player's session on 22,
open while the commands ran, went on working after `--reload`; a new one was refused. A player's ping was answered.

**Through the front, as a player trusting the backend's root alone:**
- four registrations by HTTPS to the address, `201` each, and the lobby, `101 Switching Protocols`;
- port 80 (2026-11-25, D-115): the API's health in clear, `200`; `/match/a1.arena.internal` and
  `/match/b1.arena.internal`, each answered `101 Switching Protocols` by A's arena and by B's, through nginx by the
  list; `/match/nope.arena.internal`, `404`;
- 9001 as `a1.arena.internal` and as `b1.arena.internal`: `Verify return code: 0 (ok)`. As a name the certificate
  does not hold: `62 (hostname mismatch)`. Trusting another root: `21`, refused;
- `install-certificate.sh --check`: 443 for the address and 9001 for each of the seven names, each `0 (ok)`;
- **the C# client**, the headless driver from `client/Headless` published for Linux, in the player's namespace: 16
  scenarios through 443 and 9001, all passed. They were `play`, `resume`, `coldresume`, `badticket`, `lifecycle`,
  `untrusted`, `lobby`, `duel`, `walkover`, `guest`, `replaced`, `badsession`, `phrase`, `decline`, `party` and
  `prediction`. `replaced` failed in the first run; that is [T-60](../defects.md#4-concurrency), fixed;
- `stream.log`: every connection reached the arena its name gives, 14 to `a1` (`10.0.0.1:9011`) and 13 to `b1`
  (`10.0.0.2:9011`); a name not in the list reached none;
- `access.log`: requests served by both machines' platforms and gateways; C's, which ran none, passed over
  (`10.0.0.3:8080, 10.0.0.1:8080`);
- the login limits' keys in the store named the player's address, `198.51.100.7`, and never the front's:
  `BACKEND_TRUSTED_PROXIES` at work;
- with A's platform stopped, a registration still answered `201`, served by B's.

**And then:**
- the rules held after a restart of firewalld;
- A's commands, run a second time, printed §5's messages and changed nothing;
- B's and C's commands, each on a firewall as RHEL 9 has it, made the zones §5 says;
- the install guide's §12 likewise: 80, 443 and 9001 open, 22 to the internal network alone. Its old single command
  was refused, and nothing of it applied (O-42).

The kernel gives outgoing connections local ports from 32768 to 60999, its default, read in the check: above every
port here.

**Mutation-checked** (each mutant, a copy with one rule broken on purpose, run on its own and caught), in earlier runs
of this page's layout: A's SSH line removed; C left out of A's sources; the install guide's old command put back;
`mysqlx` on; the example's rooms back to 4; the line closing cockpit removed; 9001 without TLS in the release's nginx
(nine checks failed, the C# client's matches among them). And on 2026-11-25, `http` left out of A's commands: seven
checks failed (80 refused to the player over IPv4 and IPv6, the public zone without `http`, every probe of 80). The
code's own rules are checked by their tests: `BACKEND_TRUSTED_PROXIES`, `BACKEND_ADVERTISE_PORT`,
`BACKEND_ADVERTISE_TLS_NAME`, the grants' `tlsName`, the client's `TrustRoot`, and T-60's replacement across gateways.

**Not checked** by this run:
- RHEL 9 itself, and real machines on a real private network, behind a provider's forwarding;
- SELinux enforcing. A service started from `/opt` runs unconfined (09 §6), so no port label applies; that was not
  seen in this run. (Later checks ran parts of the release under SELinux enforcing in a Rocky 9 guest: D-90, D-110.)
- B's and C's own firewalls: their commands were run and their zones read, on A's firewalld;
- the app in Unity on a phone: the C# core is the app's, and the Unity scripts were compiled against stubs (since
  D-108 also by Unity 2019.4's own compiler, `client/unity2019-check.sh`; the editor and a phone still not);
- the cost of the front: nginx's TLS for every match byte, and the extra hop, not measured;
- IPv6 between the machines (the private network was IPv4 here, as in the examples).
