# Ports and the firewall

Every port the backend uses in production, on each of the three machines: what listens on it,
on which address, who connects to it and from where, and which setting names it. Then what the
firewall opens on each machine, as commands for RHEL 9's firewalld, how to check it, and what
to change together when a port changes. Development's and the drills' ports are apart, at the
end (§8), so they are not mistaken for production's. The certificate the front serves is
[06](06-certificates.md)'s.

**The site has one public address**, an IP address with no domain, which the provider forwards
to one machine, the front ([D-82](../architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name),
[C-9](../requirements/01-scope-and-nfrs.md#3-constraints)). Players reach two ports there:
- **443**, the API and the lobby;
- **9001**, match traffic.

On both, the front's nginx ends TLS and passes the request or the connection to a machine behind
it, over the private network. The other machines have no public address. Operators come in by SSH
from the internal network alone.

Every port here was read from the file that sets it (the units, the example settings, the nginx
and MySQL configurations), and from the code where the code picks its address. The listeners and
the firewall were then run as this page gives them; §9 says how, and what was not.

The addresses are the examples':
- the site's public address `203.0.113.10`;
- the private network: A `10.0.0.1`, B `10.0.0.2`, C `10.0.0.3`;
- the internal network operators come from, `10.0.0.0/24`.

Put your own in their place. The front is C in the examples, and any machine can be it: all three
are set up the same.

## 1. Three kinds of port

| Kind | Who reaches it | Bound to | The firewall |
|---|---|---|---|
| **Public** | players' devices, through the provider's forwarding to the front | every address | opened to everyone (the `public` zone, §5) |
| **Private** | the other two machines, and nothing else | the machine's private address, or that and `127.0.0.1` (the stores) | opened to the other two machines' addresses alone (the `backend` zone, §5) |
| **Loopback** | this machine's own processes; an operator over SSH | `127.0.0.1` | nothing to open: no other machine can reach a loopback address, whatever the firewall says |

**Exactly what the internet reaches**, on the front, after §5's commands:

| Port | What |
|---|---|
| 443/tcp | nginx: HTTPS, the API (`/v1/`) and the lobby (`/lobby`) |
| 9001/tcp | nginx's `stream`: match traffic, TLS ended there and each connection passed to its arena |

Nothing else, over IPv4 or IPv6:
- **80**, the third of the owner's ports, stays closed, and nothing listens on it. It served only a
  public CA's challenge, and the site has no domain for one.
- **22**, SSH, is open to the internal network alone.
- **9090**, cockpit, which RHEL's default zone allows, is closed.
- `dhcpv6-client`, the default zone's third service, takes DHCPv6 replies on UDP 546 from
  link-local addresses alone, which no other network can send.
- ICMP stays allowed, so a machine answers ping; it is not a port.

**The provider forwards 443 to 443 and 9001 to 9001**, on the front. The arenas announce 9001,
and players dial it: a forwarding that changed the number would send them to the wrong port. The
same two ports on another machine are what a failover forwards to.

## 2. Every port in production

### On every machine: A, B and C

Every machine runs nginx, so that any can be the front; only the front's is reached from outside.

| Port | What listens | Bound to | Who connects | Set in |
|---|---|---|---|---|
| **443**/tcp | nginx (`backend-nginx`): HTTPS, ends TLS. `/v1/` to the platforms, `/lobby` to the gateways, of all three machines | every address, IPv4 and IPv6 | players | `listen 443 ssl` in `/etc/backend/nginx/backend.conf`; the upstreams in `backend-upstreams.conf` |
| **9001**/tcp | nginx's `stream`: ends match traffic's TLS and passes each connection to the arena its handshake named (SNI) | every address, IPv4 and IPv6 | players | `listen 9001 ssl` and the names' map in `/etc/backend/nginx/backend-stream.conf` |
| **22**/tcp | sshd, the system's own | every address | operators, from the internal network alone (§5) | the system |
| 8080 | the platform (`backend-platform`): the API, plain HTTP | the machine's private address | the front's nginx; this machine's gateway | `PLATFORM_BIND`, `PLATFORM_PORT` in `platform.env`; `PLATFORM_URL` in `gateway.env`; `backend-upstreams.conf` |
| 8090 | the gateway (`backend-gateway`): the lobby, plain WebSocket | the machine's private address | the front's nginx | `GATEWAY_BIND`, `GATEWAY_PORT` in `gateway.env`; `backend-upstreams.conf` |
| 9011, 9012, and 9013 on B | the arenas (`backend-arena@<name>`): match traffic, plain TCP behind nginx. One port each, §3 | the machine's private address | the front's nginx | `ARENA_PORT`, `ARENA_BIND` in `arena-<name>.env`; `backend-stream.conf`'s map |
| 9120 | the platform's admin API, only when it is named (unset, there is none) | loopback, enforced: any other address and the platform refuses to start (exit 2) | an operator on the machine, or over SSH: `ssh -L 9120:127.0.0.1:9120 <machine>` ([api/03](../api/03-admin-api.md)) | `BACKEND_ADMIN_ADDR` in `platform.env`, with the drop-in `platform-admin.conf.example` for its secret |
| 9101 | the platform's metrics, `GET /metrics` | `127.0.0.1` | this machine's scraper (none ships yet: 01 §7) | `BACKEND_METRICS_ADDR` in `platform.env` |
| 9103 | the gateway's metrics | `127.0.0.1` | the scraper | `BACKEND_METRICS_ADDR` in `gateway.env` |
| 9110, 9111, and 9113 on B | the arenas' metrics, one per arena (§3) | `127.0.0.1` | the scraper | `BACKEND_METRICS_ADDR` in `arena-<name>.env` |

The gateway's default port in the code is 8081, but the shipped settings give it 8090, and
nginx's upstreams name 8090: a gateway started by hand without `GATEWAY_PORT` would not be the
one nginx sends the lobby to.

### On some machines only

| Port | What listens | Machine | Bound to | Who connects | Set in |
|---|---|---|---|---|---|
| **6379**/tcp | j-redis `session`: sessions, tickets, the arena directory, leaderboards, the pushes' and commands' channels. The primary on A, its replica on B | A, B | `127.0.0.1` and the machine's private address (01 §3) | every platform, gateway and arena on A, B and C, and the workers on C (each is given both addresses, and asks each which is the primary: D-34); on A, B's replica, which replicates from it; the store's scripts, on its machine by `127.0.0.1`, and a promotion from B | `port` and `bind` in `/etc/backend/store-session.conf` |
| **6380**/tcp | j-redis `events`: the match results' stream (D-7). The primary on B, its replica on C | B, C | `127.0.0.1` and the private address | the arenas on A, B and C and the workers on C (both addresses each); on B, C's replica, which replicates from it; a promotion from C | `port` and `bind` in `/etc/backend/store-events.conf` |
| **3306**/tcp | MySQL (`backend-mysql`), TLS required. The primary on A, its replica on C | A, C | the private address alone, not `127.0.0.1` (`bind-address`) | every platform on A, B and C, and the workers on C (each given both hosts asks each which is writable: 06 §10); on C, the replica (replication, and the clone that made it), the binlog stream, the nightly dump and the proofs, all from A's | `bind-address` in `/etc/backend/mysql/backend.cnf`; no `port` line, so MySQL's default, 3306 |
| 9102, 9112 | the workers' metrics, `backend-worker@c1` and `@c2` | C | `127.0.0.1` | the scraper | `BACKEND_METRICS_ADDR` in `/etc/backend/worker-<id>.env` |
| 3309 | the scratch MySQL the weekly and monthly proofs restore onto (01 §10) | C | `127.0.0.1` | the proofs, on C | `port` and `bind-address` in `/etc/backend/scratch-my.cnf` |

On the database's machine, root uses MySQL through its socket,
`/run/backend-mysql/mysqld.sock`, which is a file and not a port. A process on A reaches
MySQL at `10.0.0.1`, A's own private address, and never at `127.0.0.1`, where nothing
listens.

### What does not listen, and is sometimes looked for

- **80**: nothing, on any machine (§1).
- **33060**, MySQL's X Protocol: `mysqlx = OFF` in both MySQL configurations. On, it would
  listen on every address, whatever `bind-address` says.
- **33062**, MySQL's administrative port: not configured (`admin_address` is not set).
- **No debugger and no JMX port**: the units start Java with neither. The debugger ports of
  §8 are development's alone.
- **No UDP port** of the backend's: match traffic is TCP.
- **One port per store**: a j-redis replica replicates over its primary's port, and MySQL's
  replication and clone use 3306 too.
- **The worker serves nothing but its metrics.** It, the binlog stream, the dumps, the proofs
  and the copy off the site only connect.

## 3. The arenas: their ports and their names

Players all dial the site's 9001; the front's nginx tells the arenas apart by the name the
client's TLS handshake gives, which the platform handed it with its grant (`tlsName`). Each
arena announces:
- the site's address, `ADVERTISE_HOST=203.0.113.10`;
- the site's port, `BACKEND_ADVERTISE_PORT=9001`;
- its name, `BACKEND_ADVERTISE_TLS_NAME`.

It listens on a private port of its own, since 9001 is nginx's on every machine:

| Arena | Its name (`BACKEND_ADVERTISE_TLS_NAME`) | Listens on (`ARENA_BIND`:`ARENA_PORT`) | Its metrics |
|---|---|---|---|
| `a1`, `a2` | `a1.arena.internal`, `a2.arena.internal` | `10.0.0.1:9011`, `10.0.0.1:9012` | `127.0.0.1:9110`, `9111` |
| `b1`, `b2`, `b3` | `b1.arena.internal` … `b3.arena.internal` | `10.0.0.2:9011` … `10.0.0.2:9013` | `9110`, `9111`, `9113` |
| `c1`, `c2` | `c1.arena.internal`, `c2.arena.internal` | `10.0.0.3:9011`, `10.0.0.3:9012` | `9110`, `9111` |

- **A name is a label, not a DNS name.** No DNS holds it: a player dials the address and only
  sends the name. `.internal` is the suffix reserved for such private use.
- **Three places name an arena, and must agree**:
  - its `BACKEND_ADVERTISE_TLS_NAME`;
  - its line in `backend-stream.conf`'s map, the name to its machine's private address and
    `ARENA_PORT`, the same file on every machine;
  - the site's certificate, which names every arena ([06](06-certificates.md)).

  A name missing from the map is closed by nginx; one missing from the certificate is refused by
  the client.
- **9112 is skipped** for an arena's metrics because it is the second worker's on C, so that a third
  arena on C could not take it. A port in use stops the process that comes second, with exit 2, which
  systemd does not restart.
- **No process connects to an arena** but the front's nginx: the platform's commands reach it
  through the session store.
- **Without the front**, on a machine alone or in a drill, an arena may end TLS itself, with a
  keystore and no TLS name (01 §8). That is not the site's layout.

## 4. Who connects to whom

### Inside a machine

- The gateway connects to its own machine's platform, on that machine's private address.
- The front's nginx connects to its own machine's platform, gateway and arenas the same way, by the
  private address. That is loopback traffic, which the firewall lets through.
- The scraper, when there is one, connects to the metrics ports. An operator on the machine, or
  through an SSH tunnel, connects to 9120.
- The store's backup and its restore drill (`backup-store.sh`, `store-restore-drill.sh`) connect to
  `127.0.0.1` on the store's port. The proofs on C connect to `127.0.0.1:3309`.
- The promotions (`promote-store.sh`, `promote-mysql.sh`) run on the replica's machine, and reach
  the old primary on its own machine too (the table below).

### Between the machines: the private network

| From | To | Port | Why |
|---|---|---|---|
| the front's nginx | A, B and C | 8080, 8090 | the API and the lobby, passed on; a machine that fails is passed over |
| the front's nginx | each arena's machine | 9011 to 9013 | match traffic, by the arena's name |
| A, B, C: platform, gateway, arenas; C: workers | A and B | 6379 | the `session` store, its primary and its replica |
| A, B, C: arenas; C: workers | B and C | 6380 | the `events` store, its primary and its replica |
| A, B, C: platform; C: workers | A and C | 3306 | MySQL, its primary and its replica |
| B: the `session` replica | A | 6379 | replication |
| C: the `events` replica | B | 6380 | replication |
| C: the MySQL replica, the binlog stream, the dump, the proofs | A | 3306 | replication, the clone, the copies off the database's machine (01 §10) |
| B: `promote-store.sh` for `session`; C: for `events`, and `promote-mysql.sh` | A (6379, 3306), B (6380) | a promotion asks the old primary whether it still answers, and fences it with `--demote-old` (runbook §2) |

After a failover the roles change machine, and the ports stay where they are. If C's MySQL is
promoted, for example, A's rebuilt server clones and replicates from C's 3306 (runbook §2). If the
front changes, the provider forwards to another machine, whose nginx already has everything. §5's
firewall allows both, because each machine opens its ports to both of the others.

### From the internet

Players connect to the front's 443 and 9001, nothing else.

### Out to the internet

None of the processes needs the internet. The copy off the site goes out from C by SSH, to a host
not chosen yet (Q-53, 01 §10). firewalld filters what comes in, and lets out what goes out, with its
replies: it needs no rule.

## 5. The firewall on RHEL 9

**What RHEL 9 has already.** firewalld, installed and running unless the installation turned it
off: `firewall-cmd --state` says `running`. Its default zone is `public`, which allows `ssh`,
`dhcpv6-client` and `cockpit`. Every network interface that is not given a zone is in it. So,
untouched, players reach SSH, and cockpit's 9090 where cockpit runs, and nothing of the backend's;
and the other two machines reach neither store, nor MySQL (seen in §9).

**What these commands do.** Two zones, on each machine:
- **`public`**, for players and anyone not named below: 443 and 9001, the same on every machine,
  since any may be the front. Cockpit and SSH are taken out of it, and SSH comes back for the
  internal network alone, by a rule for its addresses.
- **`backend`**, a new zone holding the other two machines' private addresses as its sources,
  with the ports they reach on this one, and SSH. firewalld decides a connection from those
  addresses by this zone alone, not by `public`, which is why SSH is repeated here. A machine on
  the private network at any other address falls to `public`, and is refused the stores, MySQL,
  the platforms and the arenas as the internet is (§9).

The rules are by address, not by interface, so they hold whichever interface the private network
is on. `--permanent` writes them to `/etc/firewalld/zones/`, and `--reload` applies them together.
A rule added without `--permanent` would be gone at the next reload or boot.

**Run them from where SSH stays open**: the console, one of the other two machines, or the
internal network. A session from anywhere else is not cut, since firewalld's `--reload` keeps the
connections already made (§9 saw one go on), but it is the last: once it ends, SSH from there is
refused. `echo "${SSH_CONNECTION%% *}"` shows where this one comes from.

**Machine A**: the `session` store's primary, MySQL's primary, arenas `a1` and `a2`.

<!-- firewall A -->
```bash
# The public side, the same on every machine since any may be the front: 443 and 9001 alone
firewall-cmd --permanent --zone=public --add-service=https
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
# The public side, the same on every machine since any may be the front: 443 and 9001 alone
firewall-cmd --permanent --zone=public --add-service=https
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

**Machine C**, the front in the examples: the `events` store's replica, MySQL's replica, arenas
`c1` and `c2`. The workers, the backups and the scratch server open nothing: they only connect, or
listen on loopback.

<!-- firewall C -->
```bash
# The public side, the same on every machine since any may be the front: 443 and 9001 alone
firewall-cmd --permanent --zone=public --add-service=https
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

Each prints `success`. Services and ports are in separate commands on purpose: firewalld takes
several of one option in a command, but refuses `--add-service` and `--add-port` together
(`argument --add-port: not allowed with argument --add-service`), and applies none of that
command. Run a second time, `--new-zone` says `Error: NAME_CONFLICT: new_zone(): 'backend'`, and
the others `Warning: ALREADY_ENABLED: …` or `NOT_ENABLED: …` then `success`: they change nothing.

**An operator on IPv6** needs the SSH rule again with `family="ipv6"` and that network.

**Later changes:**
- **An arena added** on a machine: its private port in that machine's `backend` zone,
  `firewall-cmd --permanent --zone=backend --add-port=9013/tcp && firewall-cmd --reload`. Nothing
  public changes: it is reached through 9001 by its name (§3, 04 §2.4).
- **A machine added** to the private network, `10.0.0.4` say, is added as a source on each machine:
  `firewall-cmd --permanent --zone=backend --add-source=10.0.0.4 && firewall-cmd --reload`. Its own
  `backend` zone holds the others' addresses.
- **A machine replaced** with a new address: its old address is removed in the same way, with
  `--remove-source`.
- **A provider's firewall** in front of the machines (a cloud's security group, say) needs the
  same: 443 and 9001 from anywhere to the front, SSH from the internal network, and the private
  ports from the three machines' addresses alone.

## 6. Checking

**What listens**, on the machine (`-p` names the process, as root):

```console
ss -ltnp
```

On A, with the admin API named, the backend's lines are these, as the check of §9 printed them
(sshd's 22, the system's, left out):

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

- **`[::ffff:10.0.0.1]` is `10.0.0.1`** on a Java socket, which is IPv6: an IPv4 address on it,
  reached over IPv4 alone. Search for `:8080`, not `10.0.0.1:8080`, which does not match it. The
  store shows plain IPv4, because its unit sets `-Djava.net.preferIPv4Stack=true`.
- **Every JVM is `java`**: `ss -ltnp` gives its pid too, and `ps -o args= -p <pid>` its main
  class.

On B the stores' lines are 6379 and 6380, its arenas' 9011 to 9013, and there is no MySQL. On C,
6380 and MySQL's 3306 on `10.0.0.3`, the workers' 9102 and 9112, and the scratch server's
`127.0.0.1:3309`.

A port on the wrong address is the setting's fault, and the firewall cannot mend it. A store on
`127.0.0.1` alone cannot be reached by the other machines, and a platform on `0.0.0.0` is open to
anyone the firewall lets in.

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

From B and C, A's 6379, 3306, 8080, 8090 and arenas' ports are `open`. From outside, the front's
443 and 9001 are `open`, and 80, 22, 6379, 3306, 8080, 9011 and 9120 are not.

**Where nginx sent each request and connection**, on the front:

```console
tail /var/log/backend-nginx/access.log    # each request, and its machine: $upstream_addr
tail /var/log/backend-nginx/stream.log    # each match connection: the name asked for, the arena given
```

An `$upstream_addr` of two addresses, `10.0.0.2:8080, 10.0.0.1:8080`, is a machine passed over:
nginx tried the first, which failed, and served from the second.

**Reading a failure** (§9 saw the first three):

| The message | Means |
|---|---|
| `No route to host` over IPv4, `Permission denied` over IPv6, at once | the firewall refused it: the port is not opened to this address |
| `Connection refused`, at once | it reached the machine, and nothing listens on that address and port: the process is down, or bound to another address (`127.0.0.1`, say) |
| nothing, until the timeout | dropped on the way: a firewall in between that drops rather than refuses, such as a provider's, or the wrong address |

## 7. Changing a port

Each port is named in more than one place. Change them together, or the parts stop finding each
other.

| Port | Change together | Then |
|---|---|---|
| The platform's, 8080 | `PLATFORM_PORT` in `platform.env`; `PLATFORM_URL` in `gateway.env`; the three `server …:8080` lines in `backend-upstreams.conf`, on every machine; the `backend` zone's port on every machine | restart the platform and the gateway; reload nginx |
| The gateway's, 8090 | `GATEWAY_PORT` in `gateway.env`; the three `server …:8090` lines in `backend-upstreams.conf`, on every machine; the `backend` zone's port | restart the gateway; reload nginx |
| An arena's, 9011 to 9013 | its `ARENA_PORT`; its line in `backend-stream.conf`'s map, on every machine; the `backend` zone's port on its machine | restart that arena (it drains first: 01 §5); reload nginx |
| An arena's name | its `BACKEND_ADVERTISE_TLS_NAME`; its line in the map, on every machine; the site's certificate, issued again with it ([06](06-certificates.md)) | install the certificate; restart that arena; reload nginx |
| A metrics port | its `BACKEND_METRICS_ADDR` alone, unique on the machine; and the scraper's configuration, when there is one | restart that process |
| The admin API's | `BACKEND_ADMIN_ADDR`, loopback only; and the port in the SSH tunnel | restart the platform |
| A store's, 6379 or 6380 | `port` in that store's configuration on both machines, primary and replica; the replica's `replicaof`; on every machine, `STORE_PORT` and `BACKEND_STORE_ADDRESSES` (6379) or `BACKEND_EVENTS_STORE` (6380) in every `.env` file that has them; the `backend` zone's port on both store machines; the port argument of the store's scripts (`backup-store.sh`'s third, the restore drill's second, the old primary's `host:port` given to `promote-store.sh`) | restart the stores, then every process |
| MySQL's, 3306 | a `port` line in `backend.cnf`, on A and C; every `BACKEND_DB_URL`; a `port=` line in the backups' option files (`mysql-backup.cnf`, `mysql-binlog.cnf`); the replica's `SOURCE_PORT` and `clone_valid_donor_list`; the old primary's `host:port` given to `promote-mysql.sh`; the `backend` zone's port on A and C | restart MySQL, then the platforms and the workers |
| The public 9001 | `listen 9001` in `backend-stream.conf`; every arena's `BACKEND_ADVERTISE_PORT`; the `public` zone's port; the provider's forwarding | reload nginx; restart the arenas |
| The public 443 | `listen 443` in `backend.conf`; the app's endpoints (`https://203.0.113.10:<port>`), a client release; the `public` zone; the provider's forwarding | reload nginx |

## 8. Ports that are not production

These are on a developer's machine, not on A, B or C. All of them are bound to `127.0.0.1`.

| Port | What | Started by |
|---|---|---|
| 3314 | the development MySQL (`backend_test`, `backend_dev`), `vendor/`'s 8.4 | `dev-mysql.sh`, and `build-offline.sh` for the tests (D-80) |
| 6380, 8080, 8081, 9001 | the store, the platform, the gateway, the arena | `run-local.sh start` ([development/03](../development/03-build-and-run.md)) |
| 5005 to 5009 | the debugger, one per process | `DEBUG=1 ./run-local.sh start` ([development/05](../development/05-vscode.md)) |
| 6390, 6392, 8093, 8094, 9011, 9195 to 9199, 3307, 3308 | the live drill's processes, MySQL servers and metrics | `client/headless-drill.sh` |
| 3307, 3309 | the backup drill's MySQL servers | `backup-drill.sh` |
| 3306 | the development VM's MySQL 8.0, `backend_dev`, which both drills use | the VM |

The catalogue in [deploy/README](../../backend/deploy/README.md#ports) says which drill uses
which.

**`run-local.sh` is not for a production machine.** Its ports are production's (8080, 9001), and
6380, its store's, is the `events` store's on B and C. It refuses a port already taken rather than
share it, so on a production machine it would stop at the first one.

## 9. What was checked, and what was not

**Run as written on 2026-10-19** by
[`backend/scripts/check-ports-el9.sh`](../../backend/scripts/README.md#check-ports-el9sh): 156
checks, none failing.
- **Where**: Rocky Linux 9.5, rebuilt from RHEL 9's sources, with RHEL 9's firewalld (1.3.4),
  systemd as its first process, and no network.
- **What ran**, from a release built from the code as it is now, each process with the settings
  the release ships:
  - on A, the front here (`10.0.0.1`, and the public `198.51.100.1`): MySQL with TLS (01 §9's
    commands), the session store, the platform, the gateway, the arena `a1` with 12 rooms, and
    nginx with the shipped configuration;
  - on B (`10.0.0.2`), a network namespace of its own: its platform, gateway and arena `b1`,
    on A's store and MySQL through A's firewall;
  - C's first worker and its scratch MySQL, on A, for their ports; a stand-in for sshd.

  They ran as root, with small heaps, which changes nothing about where they listen.
- **The certificate**: a root and the site's certificate made by `private-ca.sh`, for
  `198.51.100.1` and the map's seven names, and installed by `install-certificate.sh` before
  nginx had ever started, as [06](06-certificates.md) says.
- **Who connected**: C (`10.0.0.3`), a machine of the internal network (`10.0.0.50`) and a player
  (`198.51.100.7`), each in a namespace of its own. The player came over IPv6 as well, and also
  routed to the private addresses through the public link, as a neighbour on a provider's network
  could.

**What listens**: §2's ports on §2's addresses, and nothing else: §6's listing is the check's.
Nothing on 80, 33060 or 33062. `a1` announced `198.51.100.1:9001, TLS as a1.arena.internal`.

**The firewall**, three times: stopped, as RHEL 9 has it, and after §5's commands for A, taken
from this page.

| From | To A's | Stopped | As RHEL 9 has it | After §5's commands |
|---|---|---|---|---|
| a player | 443, 9001 (over IPv6 too) | open | refused | open |
| a player | 22 | open | open | refused (IPv6 too) |
| a player | 80, 9090 | `Connection refused`: nothing listens | 80 refused, 9090 let through | refused |
| a player | 8080, 9011, 9120 on the public address | `Connection refused`: bound elsewhere | refused | refused |
| a player routing to `10.0.0.1` | 6379, 3306, 8080, 9011 | **open** | refused | refused |
| B | 6379, 3306, 8080, 8090, 9011 | open | refused | open |
| B | 22 | open | open | open |
| B | 443 | open | refused | refused: the `backend` zone decides alone |
| C | 6379, 9011 | open | refused | open |
| the internal network's machine | 22 | open | open | open: the rule for 10.0.0.0/24 |
| the internal network's machine | 6379, 8080, 9011 | open | refused | refused |
| the internal network's machine | 443 | open | refused | open, as anyone |
| A itself | its loopback and private ports; out to the player | open | open | open |

"Refused" was `No route to host` over IPv4 and `Permission denied` over IPv6, at once. A player's
session on 22, open while the commands ran, went on working after `--reload`; a new one was
refused. A player's ping was answered.

**Through the front, as a player trusting the backend's root alone:**
- four registrations by HTTPS to the address, `201` each, and the lobby, `101 Switching Protocols`;
- port 80: nothing;
- 9001 as `a1.arena.internal` and as `b1.arena.internal`: `Verify return code: 0 (ok)`. As a name
  the certificate does not hold: `62 (hostname mismatch)`. Trusting another root: `21`, refused;
- `install-certificate.sh --check`: 443 for the address and 9001 for each of the seven names,
  each `0 (ok)`;
- **the C# client**, the headless driver from `client/Headless` published for Linux, in the
  player's namespace: 16 scenarios through 443 and 9001, all passed. They were `play`, `resume`,
  `coldresume`, `badticket`, `lifecycle`, `untrusted`, `lobby`, `duel`, `walkover`, `guest`,
  `replaced`, `badsession`, `phrase`, `decline`, `party` and `prediction`. `replaced` failed in the
  first run, and that is [T-60](../defects.md#4-concurrency), fixed;
- `stream.log`: every connection reached the arena its name gives, 14 to `a1` (`10.0.0.1:9011`)
  and 13 to `b1` (`10.0.0.2:9011`); a name not in the map, none;
- `access.log`: requests served by both machines' platforms and gateways, and C's, which ran
  none, passed over (`10.0.0.3:8080, 10.0.0.1:8080`);
- the login limits' keys in the store named the player's address, `198.51.100.7`, and never the
  front's: `BACKEND_TRUSTED_PROXIES` at work;
- A's platform stopped: a registration still `201`, served by B's.

**And then:**
- the rules held after a restart of firewalld;
- A's commands run a second time printed §5's messages and changed nothing;
- B's and C's commands, each on a firewall as RHEL 9 has it, made the zones §5 says;
- the install guide's §12, likewise: 443 and 9001 open, 22 to the internal network alone. Its
  old single command was refused, and nothing of it applied (O-42).

The kernel gives outgoing connections local ports from 32768 to 60999, its default, read in the
check: above every port here.

**Mutation-checked** (each mutant run on its own and caught), in earlier runs of this page's
layout: A's SSH line removed; C left out of A's sources; the install guide's old command put back;
`mysqlx` on; the example's rooms back to 4; the line closing cockpit removed; 9001 without TLS in
the release's nginx (nine checks failed, the C# client's matches among them). The code's own rules,
in their tests: `BACKEND_TRUSTED_PROXIES`, `BACKEND_ADVERTISE_PORT`, `BACKEND_ADVERTISE_TLS_NAME`,
the grants' `tlsName`, the client's `TrustRoot`, and T-60's replacement across gateways.

**Not checked:**
- RHEL 9 itself, and real machines on a real private network, behind a provider's forwarding;
- SELinux enforcing. A service started from `/opt` runs unconfined (09 §6), so no port label
  applies; that was not seen here;
- B's and C's own firewalls: their commands were run and their zones read, on A's firewalld;
- the app in Unity on a phone: the C# core is the app's, the Unity scripts compile against stubs;
- the cost of the front: nginx's TLS for every match byte, and the extra hop, not measured;
- IPv6 between the machines (the private network was IPv4 here, as in the examples).
