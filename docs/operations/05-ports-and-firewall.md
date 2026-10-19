# Ports and the firewall

Every port the backend uses in production, on each of the three machines: what listens on it,
on which address, who connects to it and from where, and which setting names it. Then what the
firewall opens on each machine, as commands for RHEL 9's firewalld, how to check it, and what
to change together when a port changes. Development's and the drills' ports are apart, at the
end (§8), so they are not mistaken for production's.

**Players may reach three ports a machine: 80, 443 and 9001.** That is the owner's limit
([C-9](../requirements/01-scope-and-nfrs.md#3-constraints)); between the machines any port may be
used. So a machine runs one arena, on 9001 ([D-81](../architecture/03-decision-log.md#d-81--three-public-ports-a-machine-so-one-arena-on-each)).

Every port here was read from the file that sets it (the units, the example settings, the nginx
and MySQL configurations), and from the code where the code picks its address. The listeners
and the firewall were then run as this page gives them; §9 says how, and what was not.

The addresses are the examples' (01 §1): the private network A `10.0.0.1`, B `10.0.0.2`,
C `10.0.0.3`; put your own in their place. Each machine's public name (01 §11) is not chosen
yet; `a.example.com` stands for A's.

## 1. Three kinds of port

| Kind | Who reaches it | Bound to | The firewall |
|---|---|---|---|
| **Public** | players' devices, from the internet | every address | opened to everyone (the `public` zone, §5) |
| **Private** | the other two machines, and nothing else | the machine's private address (MySQL), or that and `127.0.0.1` (the stores) | opened to the other two machines' addresses alone (the `backend` zone, §5) |
| **Loopback** | this machine's own processes; an operator over SSH | `127.0.0.1` | nothing to open: no other machine can reach a loopback address, whatever the firewall says |

Most ports are loopback. Players reach two things only: nginx, for the API and the lobby, and
the arenas, directly for match traffic
([D-5](../architecture/03-decision-log.md#d-5--match-traffic-never-passes-through-nginx-or-the-gateway)).
The machines reach each other on three ports: the two stores and MySQL.

## 2. Every port in production

### On every machine: A, B and C

| Port | What listens | Bound to | Who connects | Set in |
|---|---|---|---|---|
| **443**/tcp | nginx (`backend-nginx`): HTTPS. `/v1/` is the API, `/lobby` the lobby's WebSocket | every address, IPv4 and IPv6 | players | `listen 443 ssl` in `/etc/backend/nginx/backend.conf` |
| **80**/tcp | nginx: the certificate's HTTP-01 challenge (01 §11); everything else is answered 403 `HTTPS only`, with no redirect (01 §4) | every address, IPv4 and IPv6 | the CA | `listen 80` in the same file |
| **9001**/tcp | the machine's one arena (`backend-arena@a1` on A, `@b1` on B, `@c1` on C; D-81): match traffic, the binary protocol over TCP; TLS on the same port once the arenas have a certificate (01 §8). §3 | every address, IPv4 and IPv6: `ARENA_BIND=0.0.0.0`, which the arena's native transport takes as both (`ss` shows `*:9001`) | players, directly | `ARENA_PORT` and `ARENA_BIND` in `/etc/backend/arena-<name>.env` |
| **22**/tcp | sshd, the system's own | every address | operators | the system |
| 8080 | the platform (`backend-platform`): the API, plain HTTP | `127.0.0.1` | this machine's nginx (`/v1/`) and gateway | `PLATFORM_PORT` in `platform.env`; nginx's `upstream backend_platform`; `PLATFORM_URL` in `gateway.env` |
| 8090 | the gateway (`backend-gateway`): the lobby, plain WebSocket | `127.0.0.1` | this machine's nginx (`/lobby`) | `GATEWAY_PORT` in `gateway.env`; nginx's `upstream backend_gateway` |
| 9120 | the platform's admin API, only when it is named (unset, there is none) | loopback, enforced: any other address and the platform refuses to start (exit 2) | an operator on the machine, or over SSH: `ssh -L 9120:127.0.0.1:9120 <machine>` ([api/03](../api/03-admin-api.md)) | `BACKEND_ADMIN_ADDR` in `platform.env`, with the drop-in `platform-admin.conf.example` for its secret |
| 9101 | the platform's metrics, `GET /metrics` | `127.0.0.1` | this machine's scraper (none ships yet: 01 §7) | `BACKEND_METRICS_ADDR` in `platform.env` |
| 9103 | the gateway's metrics | `127.0.0.1` | the scraper | `BACKEND_METRICS_ADDR` in `gateway.env` |
| 9110 | the arena's metrics | `127.0.0.1` | the scraper | `BACKEND_METRICS_ADDR` in `arena-<name>.env` |

The gateway's default port in the code is 8081, but the shipped settings give it 8090, and
nginx's upstream names 8090: a gateway started by hand without `GATEWAY_PORT` would not be
the one nginx sends the lobby to.

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

## 3. The arenas' ports

**One arena a machine, on 9001** (D-81). Of the three ports players may reach, 80 and 443 are
nginx's. So `a1` on A, `b1` on B and `c1` on C each listen on 9001, with their metrics on
`127.0.0.1:9110`, and each one's rooms carry its machine's match traffic (`MAX_ROOMS=12`,
[04 §2.3](04-scaling-and-performance.md#23-settings-that-size-an-arena)).

- **The same port on every machine is right.** An arena registers this machine's public name
  (`ADVERTISE_HOST`) with its port, and the platform hands that pair to the player. It is the
  arena's *name* that must be unique across the machines (01 §2), not its port.
- **Nothing else needs the arena's port.** No process connects to an arena: the platform's
  commands reach it through the session store.
- **Not through nginx.** nginx proxies raw TCP only with its `stream` module, which the release's
  nginx is built without. Even with it, nginx could tell arenas apart only by the names in a TLS
  handshake, since the platform hands each player one particular arena. D-81 says why that
  was not taken.
- **A second arena on a machine** needs a second public port, beyond C-9. Should the owner allow
  one: `a2` on 9002, its metrics on 9111 ([04 §2.4](04-scaling-and-performance.md#24-adding-capacity)).
  A third would take 9003 and 9113: 9112 is skipped, the second worker's on C. A port in use
  stops the process that comes second, with exit 2, which systemd does not restart.

## 4. Who connects to whom

### Inside a machine

- nginx connects to `127.0.0.1:8080` (the platform) and `127.0.0.1:8090` (the gateway).
- The gateway connects to `127.0.0.1:8080`, the platform on its own machine.
- The scraper, when there is one, connects to the metrics ports. An operator on the machine,
  or through an SSH tunnel, connects to 9120.
- The store's backup and its restore drill (`backup-store.sh`, `store-restore-drill.sh`)
  connect to `127.0.0.1` on the store's port. The proofs on C connect to `127.0.0.1:3309`.
- The promotions (`promote-store.sh`, `promote-mysql.sh`) run on the replica's machine, and
  reach the old primary on its own machine too (the table below).

### Between the machines: the private network

| From | To | Port | Why |
|---|---|---|---|
| A, B, C: platform, gateway, arenas; C: workers | A and B | 6379 | the `session` store, its primary and its replica |
| A, B, C: arenas; C: workers | B and C | 6380 | the `events` store, its primary and its replica |
| A, B, C: platform; C: workers | A and C | 3306 | MySQL, its primary and its replica |
| B: the `session` replica | A | 6379 | replication |
| C: the `events` replica | B | 6380 | replication |
| C: the MySQL replica, the binlog stream, the dump, the proofs | A | 3306 | replication, the clone, the copies off the database's machine (01 §10) |
| B: `promote-store.sh` for `session`; C: for `events`, and `promote-mysql.sh` | A (6379, 3306), B (6380) | a promotion asks the old primary whether it still answers, and fences it with `--demote-old` (runbook §2) |

A process that reaches a store or MySQL on its own machine does so by the private address too,
which is loopback traffic, and the firewall lets it through. After a failover the roles change
machine, and the ports stay where they are. If C's MySQL is promoted, for example, A's rebuilt
server clones and replicates from C's 3306 (runbook §2). The firewall of §5 already allows that,
because each machine opens its ports to both of the others.

Nothing connects between machines on 8080, 8090, a metrics port or an arena's port. The
worker's pushes reach the players' gateways, and the platform's commands reach the arenas,
through the `session` store's channels.

### From the internet

Players connect to 443 and to 9001. The CA connects to 80. Operators connect to 22.

### Out to the internet

None of the processes needs the internet. Two things in the design go out:
- the copy off the site, from C by SSH to a host not chosen yet (Q-53, 01 §10);
- certbot, to the CA (01 §11).

firewalld filters what comes in, and lets out what goes out, with its replies; neither needs a
rule.

## 5. The firewall on RHEL 9

**What RHEL 9 has already.** firewalld, installed and running unless the installation turned it
off: `firewall-cmd --state` says `running`. Its default zone is `public`, which allows `ssh`,
`dhcpv6-client` and `cockpit`. Every network interface that is not given a zone is in it. So,
untouched, the firewall lets players reach SSH alone, and the other two machines reach nothing of the
backend's: neither store, nor MySQL (seen in §9).

**What these commands do.** Two zones, on each machine:
- **`public`**, for players: nginx's 443 and 80, and the arena's 9001. SSH is in it already.
- **`backend`**, a new zone holding the other two machines' private addresses as its
  sources, with only the ports they reach on this one, and SSH. firewalld decides a
  connection from those addresses by this zone alone, not by `public`, which is why SSH is
  repeated here. Without it, one machine could not SSH to another. A machine on the private
  network at any other address falls to `public`, and is refused the stores and MySQL as the
  internet is (§9).

The rules are by address, not by interface, so they hold whichever interface the private network
is on. `--permanent` writes them to `/etc/firewalld/zones/`, and `--reload` applies them. A rule
added without `--permanent` would be gone at the next reload or boot.

**Machine A**: the `session` store's primary, MySQL's primary, arena `a1`.

<!-- firewall A -->
```bash
# Players: nginx (443, and 80 for the certificate's challenge), and this machine's arena a1
firewall-cmd --permanent --zone=public --add-service=https --add-service=http
firewall-cmd --permanent --zone=public --add-port=9001/tcp
# B and C, by their private addresses: SSH, the session store and MySQL
firewall-cmd --permanent --new-zone=backend
firewall-cmd --permanent --zone=backend --add-source=10.0.0.2 --add-source=10.0.0.3
firewall-cmd --permanent --zone=backend --add-service=ssh
firewall-cmd --permanent --zone=backend --add-port=6379/tcp --add-port=3306/tcp
firewall-cmd --reload
```
<!-- /firewall -->

**Machine B**: the `session` store's replica, the `events` store's primary, arena `b1`.

<!-- firewall B -->
```bash
# Players: nginx, and this machine's arena b1
firewall-cmd --permanent --zone=public --add-service=https --add-service=http
firewall-cmd --permanent --zone=public --add-port=9001/tcp
# A and C, by their private addresses: SSH, and both stores
firewall-cmd --permanent --new-zone=backend
firewall-cmd --permanent --zone=backend --add-source=10.0.0.1 --add-source=10.0.0.3
firewall-cmd --permanent --zone=backend --add-service=ssh
firewall-cmd --permanent --zone=backend --add-port=6379/tcp --add-port=6380/tcp
firewall-cmd --reload
```
<!-- /firewall -->

**Machine C**: the `events` store's replica, MySQL's replica, arena `c1`. The workers,
the backups and the scratch server open nothing: they only connect, or listen on loopback.

<!-- firewall C -->
```bash
# Players: nginx, and this machine's arena c1
firewall-cmd --permanent --zone=public --add-service=https --add-service=http
firewall-cmd --permanent --zone=public --add-port=9001/tcp
# A and B, by their private addresses: SSH, the events store and MySQL
firewall-cmd --permanent --new-zone=backend
firewall-cmd --permanent --zone=backend --add-source=10.0.0.1 --add-source=10.0.0.2
firewall-cmd --permanent --zone=backend --add-service=ssh
firewall-cmd --permanent --zone=backend --add-port=6380/tcp --add-port=3306/tcp
firewall-cmd --reload
```
<!-- /firewall -->

Each prints `success`. Services and ports are in separate commands on purpose: firewalld takes
several of one option in a command, but refuses `--add-service` and `--add-port` together
(`argument --add-port: not allowed with argument --add-service`), and applies none of that
command. Run a second time, `--new-zone` says `Error: NAME_CONFLICT: new_zone():
'backend'`, and each of the others `Warning: ALREADY_ENABLED: …` then `success`: they change
nothing.

**Cockpit**, RHEL's web console, is allowed in `public` by default, on port 9090, and nothing of
the backend's uses it. Close it unless you use it:
`firewall-cmd --permanent --zone=public --remove-service=cockpit && firewall-cmd --reload`.

**Later changes:**
- **A second arena on a machine**, should the owner allow a fourth public port, opens it there:
  `firewall-cmd --permanent --zone=public --add-port=9002/tcp && firewall-cmd --reload`
  (04 §2.4).
- **A machine added** to the private network, `10.0.0.4` say, is added as a source on each
  machine it connects to: `firewall-cmd --permanent --zone=backend --add-source=10.0.0.4 &&
  firewall-cmd --reload`. Its own `backend` zone holds the others' addresses.
- **A machine replaced** with a new address: its old address is removed in the same way, with
  `--remove-source`.
- **A provider's firewall** in front of the machines (a cloud's security group, say) needs the
  same: the public ports from anywhere, the private ones from the three addresses alone.

## 6. Checking

**What listens**, on the machine (`-p` names the process, as root):

```console
ss -ltnp
```

On A, with the admin API named, the backend's lines are these, as the
check of §9 printed them (sshd's 22, the system's, left out):

```
0.0.0.0:80                nginx
[::]:80                   nginx
0.0.0.0:443               nginx
[::]:443                  nginx
*:9001                    java     arena a1
10.0.0.1:3306             mysqld
10.0.0.1:6379             java     the session store
127.0.0.1:6379            java     the session store
[::ffff:127.0.0.1]:8080   java     the platform
[::ffff:127.0.0.1]:8090   java     the gateway
[::ffff:127.0.0.1]:9101   java     the platform's metrics
[::ffff:127.0.0.1]:9103   java     the gateway's metrics
[::ffff:127.0.0.1]:9110   java     a1's metrics
[::ffff:127.0.0.1]:9120   java     the admin API
```

- **`[::ffff:127.0.0.1]` is `127.0.0.1`** on a Java socket, which is IPv6: an IPv4 address on it,
  reached over IPv4 loopback alone. Search for `:8080`, not `127.0.0.1:8080`, which does not
  match it. The store shows plain IPv4, because its unit sets `-Djava.net.preferIPv4Stack=true`.
- **`*` is the wildcard of both families**: the arena takes connections over IPv4 and IPv6.
- **Every JVM is `java`**: `ss -ltnp` gives its pid too, and `ps -o args= -p <pid>` its main
  class.

On B the stores' lines are 6379 and 6380, and there is no MySQL. On C, 6380 and MySQL's 3306 on
`10.0.0.3`, the workers' 9102 and 9112, and the scratch server's `127.0.0.1:3309`.

A port on the wrong address is the setting's fault, and the firewall cannot mend it. A store
on `127.0.0.1` alone cannot be reached by the other machines, and a platform on `0.0.0.0` is
open to anyone the firewall lets in.

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

From B and C, A's 6379 and 3306 are `open`. From outside, a machine's 443 and 9001 are `open`,
and 6379, 3306, 8080 and 9120 are not.

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
| The platform's, 8080 | `PLATFORM_PORT` in `platform.env`; `server 127.0.0.1:8080` in `backend.conf`'s `upstream backend_platform`; `PLATFORM_URL` in `gateway.env` | restart the platform and the gateway; reload nginx |
| The gateway's, 8090 | `GATEWAY_PORT` in `gateway.env`; `server 127.0.0.1:8090` in `upstream backend_gateway` | restart the gateway; reload nginx |
| An arena's | its `ARENA_PORT`; the firewall's port, §5. Nothing else: the arena registers its port, and the platform hands it to players | restart that arena (it drains first: 01 §5) |
| A metrics port | its `BACKEND_METRICS_ADDR` alone, unique on the machine; and the scraper's configuration, when there is one | restart that process |
| The admin API's | `BACKEND_ADMIN_ADDR`, loopback only; and the port in the SSH tunnel | restart the platform |
| A store's, 6379 or 6380 | `port` in that store's configuration on both machines, primary and replica; the replica's `replicaof`; on every machine, `STORE_PORT` and `BACKEND_STORE_ADDRESSES` (6379) or `BACKEND_EVENTS_STORE` (6380) in every `.env` file that has them; the `backend` zone's port on both store machines; the port argument of the store's scripts (`backup-store.sh`'s third, the restore drill's second, the old primary's `host:port` given to `promote-store.sh`) | restart the stores, then every process |
| MySQL's, 3306 | a `port` line in `backend.cnf`, on A and C; every `BACKEND_DB_URL`; a `port=` line in the backups' option files (`mysql-backup.cnf`, `mysql-binlog.cnf`); the replica's `SOURCE_PORT` and `clone_valid_donor_list`; the old primary's `host:port` given to `promote-mysql.sh`; the `backend` zone's port on A and C | restart MySQL, then the platforms and the workers |
| nginx's, 443 and 80 | leave them | players dial `https://`, whose port is 443, and the CA's HTTP-01 challenge always comes to port 80 |

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
[`backend/scripts/check-ports-el9.sh`](../../backend/scripts/README.md#check-ports-el9sh): 118
checks, none failing, again after D-81 on a release rebuilt with its example.
- **Where**: Rocky Linux 9.5, rebuilt from RHEL 9's sources, with RHEL 9's firewalld (1.3.4),
  systemd as its first process, and no network.
- **What ran**: machine A's processes from a release built from the code as it is now, each
  with the settings the release ships, filled in as an operator would (the admin API named):
  - the session store, MySQL with TLS (01 §9's commands), the platform, the gateway, the
    machine's one arena `a1` with the example's 12 rooms, and nginx;
  - two of C's, the first worker and the scratch MySQL, for their ports;
  - a stand-in for sshd on 22.

  They ran as root, with small heaps, which changes nothing about where they listen.
- **Who connected**: B (10.0.0.2), C (10.0.0.3), a stranger on the private network (10.0.0.50)
  and a player (198.51.100.7), each in a network namespace of its own. The player came over
  IPv6 as well, and also routed to the private addresses through the public link, as a
  neighbour on a provider's network could.

**What listens**: §2's ports on §2's addresses, and nothing else: §6's listing is the check's.
Nothing on 33060 or 33062. With `mysqlx` turned on, MySQL listened on `*:33060`, every address,
and the check failed, as it should.

**The firewall**, three times: stopped, as RHEL 9 has it, and after §5's commands for A, taken
from this page.

| From | To A's | Stopped | As RHEL 9 has it | After §5's commands |
|---|---|---|---|---|
| a player | 443, 80, 9001 (443 and 9001 over IPv6 too) | open | refused | open |
| a player | 9002, where no arena listens | `Connection refused` | refused | refused |
| a player | 22 | open | open | open |
| a player | 8080, 9120 | `Connection refused`: loopback | refused | refused |
| a player routing to `10.0.0.1` | 6379, 3306 | **open** | refused | refused |
| B and C | 6379, 3306 | open | refused | open |
| B | 22 | open | open | open |
| B | 443, 9001 | open | refused | refused: the `backend` zone decides alone |
| the stranger | 6379, 3306 | open | refused | refused |
| the stranger | 22 / 443 | open | open / refused | open |
| A itself | `127.0.0.1`'s 8080, 9120, 6379; `10.0.0.1:3306`; out to the player | open | open | open |

"Refused" was `No route to host` over IPv4 and `Permission denied` over IPv6, at once.

**Through the opened ports, the processes answered**:
- a player's registration, through nginx, the platform, and MySQL over TLS: `201`;
- the lobby's WebSocket, through nginx and the gateway: `101 Switching Protocols`;
- port 80: `HTTPS only`;
- from B, the store (`-NOAUTH Authentication required.`) and MySQL (its greeting, `8.4.11`);
- the admin API listed `a1` on 9001.

**And then:**
- the rules held after a restart of firewalld;
- A's commands run a second time printed §5's messages and changed nothing;
- B's and C's commands, each on a firewall as RHEL 9 has it, made the zones §5 says;
- the install guide's §12, likewise, opened 443 and 9001 to the player, where its old single
  command was refused, and nothing of it applied (O-42).

The kernel gives outgoing connections local ports from 32768 to 60999, its default, read in the
check: above every port here, so a connection never takes one a process needs. Design 07 §5's
`10240 65000`, if it is set, is above them too.

**Mutation-checked**, each mutant run on its own and caught:
- A's SSH line removed: B refused 22;
- C left out of A's sources: C refused 6379 and 3306;
- the install guide's old command put back: the player refused 443 and 9001;
- `mysqlx` on: a listener beyond §2's;
- A's public ports back to `9001-9002`, as before D-81: the player's 9002 answered `Connection
  refused`, not the firewall's refusal, and the zone listed `9001-9002/tcp`;
- the example's rooms back to 4: the arena started with `maxRooms 4`.

**Not checked:**
- RHEL 9 itself, and real machines on a real private network;
- SELinux enforcing. A service started from `/opt` runs unconfined (09 §6), so no port label
  applies; that was not seen here;
- B's and C's own processes: their commands were run and their zones read, not probed from the
  other machines;
- the arenas over TLS, which share the port;
- a provider's firewall, and IPv6 between the machines (the private network was IPv4 here, as in
  the examples).
