# Installing on a RHEL 9 server, from a clone of the repository

A step-by-step guide: from a fresh RHEL 9.x server and this repository to every
process running and a player registering through nginx, with nothing installed
from the operating system but `git` (and that only to clone). Every command is
here; the reasons are in [01 — Deployment](01-deploy.md) and
[09](../detailed-design/09-release-and-packaging.md).

**Tested as written.** `backend/scripts/check-install-guide-el9.sh` runs every
`bash` block of the steps below, in order, in Red Hat's UBI 9.5 image with
systemd and **no network**, on a plain copy of the repository; it ends with a
registration through nginx. Last run 2026-10-05, in UBI 9.5: every unit
`active`, the registration `201`, about twenty-five minutes. Also run the same day
in Rocky Linux 9 with the system's own Java 8 and MySQL 8.0 installed and running
(`MYSQL_PORT=3307`, §1): the same result, the system's MySQL still running beside
ours. Running it found four faults, fixed before this text (O-32 to O-35 in the
[defect register](../defects.md)). The blocks marked `console` are not run by it
(the clone, stopping the system's MySQL, the firewall).

The steps install **one machine with every role**: a trial, or the first of the
three. [Three machines](#three-machines) says what differs.

## 1. What the server needs

- RHEL 9.x on x86-64 (checked with 9.5), as root.
- What any RHEL 9 server installation has: systemd, bash, coreutils, util-linux,
  `tar`, `gzip`, `findutils`, `sed`, `openssl`, `curl`. Nothing else: the JDK,
  Maven, MySQL, nginx and every library are in the repository (`vendor/`,
  `java21-offline/`).
- Memory: the units' heaps and MySQL's buffer pool are sized for the production
  machines (64 GB). On a smaller machine, step 8 gives them smaller ones by itself.
- Disk: about 2 GB for the repository and the build, and what the data takes.

### A Java or a MySQL already on the server

Neither is used, and neither is in the way, except MySQL's port.

- **Java**, any version, from the operating system's packages: nothing to do. The
  units run the release's own runtime (`/opt/backend/runtime/bin/java`) and the
  build the committed JDK; `alternatives`, the `PATH` and a `JAVA_HOME` reach
  neither. By hand, `java` or `jcmd` is still the old one: type
  `/opt/backend/runtime/bin/jcmd`.
- **MySQL or MariaDB**, from the operating system's packages (RHEL 9 has MySQL
  8.0 and MariaDB): it holds port 3306, which the backend's MySQL would take.
  Everything else is apart already: the unit (`mysqld` or `mariadb`, ours
  `backend-mysql`), the data (`/var/lib/mysql`, ours `/var/lib/backend-mysql`),
  the socket and the configuration (ours reads only `/etc/backend/mysql/`); the
  `mysql` user is shared, which step 5 allows for. Then either:
  - **nothing else uses it**: stop it before step 6; its data is kept.
    ```console
    systemctl disable --now mysqld          # or mariadb
    ```
  - **another application uses it**: leave it, and give the backend's MySQL
    another port, `MYSQL_PORT` below (3307, say).

  Never the backend on that server: it runs on MySQL 8.4 alone (Q-56), and 8.0
  is out of support since April 2026. The `mysql` on the `PATH` is that
  package's client: ours is `/opt/backend/mysql/bin/mysql`, which the scripts
  use.

## 2. The repository

```console
dnf install -y git                       # only to clone; or copy a tarball of the repository instead
git clone https://github.com/greensoft-atom/depio-simulation-platform.git /srv/depio-simulation-platform   # private: a token or a deploy key
```

<!-- guide-steps -->

Your values, set once in the shell every later step runs in:

```bash
export SRC=/srv/depio-simulation-platform   # where the repository is
export ADDRESS=203.0.113.10              # the public address players' devices reach this machine by
export MYSQL_BUFFER_POOL=14G             # MySQL's buffer pool: 14G of 20 GB on a production machine
export MYSQL_PORT=3306                   # another if the server's own MySQL keeps 3306 (§1)
```

## 3. Build

The committed JDK and Maven, the committed libraries, no network
(`build-offline.sh`, 09 §3). About fifteen minutes; `SKIP_TESTS=1` because the
tests, an hour more on their own MySQL, belong on the machine the release is made on
([build and run](../development/03-build-and-run.md#3-the-tests)), not on a server.

```bash
cd "$SRC"
SKIP_TESTS=1 WORK=/var/tmp/backend-build ./build-offline.sh
```

## 4. The release in /opt

```bash
tar -xzf "$SRC"/backend/target/release/backend-*.tar.gz -C /opt
ln -sfn "$(ls -d /opt/backend-*/ | sort | tail -1 | sed 's:/$::')" /opt/backend
tail -3 /opt/backend/VERSIONS.md
```

## 5. Users, directories, secrets, units

```bash
for u in backend nginx jredis; do
    id "$u" > /dev/null 2>&1 || useradd --system --home-dir /nonexistent --shell /usr/sbin/nologin "$u"
done
id mysql > /dev/null 2>&1 || useradd --system --home-dir /var/lib/backend-mysql --shell /usr/sbin/nologin mysql
install -d -m 0755 /etc/backend /etc/backend/tls /etc/backend/mysql /etc/backend/nginx
install -d -m 0700 /etc/backend/credentials
# Three secrets, made here, never typed: the database's, the store's, and MySQL's root.
for s in db-password store-password mysql-root; do
    [ -s "/etc/backend/credentials/$s" ] || (umask 077; openssl rand -hex 24 > "/etc/backend/credentials/$s")
done
cp /opt/backend/systemd/*.service /opt/backend/systemd/*.timer /etc/systemd/system/
systemctl daemon-reload
```

## 6. MySQL

The release's MySQL 8.4 (01 §9), on loopback: on one machine nothing crosses the
network, so TLS is not required here; on three, it is (01 §9).

```bash
sed -e "s/^bind-address .*/bind-address               = 127.0.0.1/" \
    -e "/^\[mysqld\]/a port                       = $MYSQL_PORT" \
    -e "s/^innodb_buffer_pool_size .*/innodb_buffer_pool_size    = $MYSQL_BUFFER_POOL/" \
    -e "/^ssl_/d" -e "s/^require_secure_transport .*/require_secure_transport   = OFF/" \
    /opt/backend/mysql-conf/backend.cnf.example > /etc/backend/mysql/backend.cnf
install -d -o mysql -g mysql -m 0750 /var/lib/backend-mysql /var/log/backend-mysql
/opt/backend/mysql/bin/mysqld --defaults-file=/etc/backend/mysql/backend.cnf --initialize-insecure --user=mysql
systemctl enable --now backend-mysql
# root's password, then the application's database and user (01 §9's grants)
/opt/backend/mysql/bin/mysql -S /run/backend-mysql/mysqld.sock -u root \
    -e "ALTER USER root@localhost IDENTIFIED BY '$(cat /etc/backend/credentials/mysql-root)'"
(umask 077; printf '[client]\nuser=root\npassword=%s\nsocket=/run/backend-mysql/mysqld.sock\n' \
    "$(cat /etc/backend/credentials/mysql-root)" > /etc/backend/credentials/mysql-root.cnf)
/opt/backend/mysql/bin/mysql --defaults-extra-file=/etc/backend/credentials/mysql-root.cnf -e "
    CREATE DATABASE backend;
    CREATE USER 'backend'@'127.0.0.1' IDENTIFIED BY '$(cat /etc/backend/credentials/db-password)';
    GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON backend.* TO 'backend'@'127.0.0.1'"
```

The schema is made by `platform` at its first start (Flyway: `V1` the tables,
`V2` the first rows); nothing else to load.

## 7. The store

One j-redis instance, `session`, for everything on one machine (01 §3: leave
`BACKEND_EVENTS_STORE` unset).

```bash
sed -e "s/^port .*/port 6379/" -e "s/^bind .*/bind 127.0.0.1/" \
    -e "s/^requirepass .*/requirepass $(cat /etc/backend/credentials/store-password)/" \
    -e "s#^dir .*#dir /var/lib/backend-store/session#" \
    /opt/backend/jredis/conf/j-redis.conf > /etc/backend/store-session.conf
chown root:jredis /etc/backend/store-session.conf
chmod 0640 /etc/backend/store-session.conf
cp /opt/backend/env/store.env.example /etc/backend/store-session.env
```

## 8. The processes' settings

From the release's examples (`env/`), pointed at this machine.

```bash
local_db="jdbc:mysql://127.0.0.1:$MYSQL_PORT/backend?sslMode=REQUIRED"
# Everything on loopback: this machine is its own front, and nginx reaches its processes there (D-82).
for p in platform gateway worker; do
    sed -e "s/^STORE_HOST=.*/STORE_HOST=127.0.0.1/" -e "s#^BACKEND_DB_URL=.*#BACKEND_DB_URL=$local_db#" \
        -e "s/^PLATFORM_BIND=.*/PLATFORM_BIND=127.0.0.1/" -e "s/^GATEWAY_BIND=.*/GATEWAY_BIND=127.0.0.1/" \
        -e "s#^PLATFORM_URL=.*#PLATFORM_URL=http://127.0.0.1:8080#" -e "s/^BACKEND_TRUSTED_PROXIES=.*/BACKEND_TRUSTED_PROXIES=/" \
        "/opt/backend/env/$p.env.example" > "/etc/backend/$p.env"
done
# The arena behind nginx: on loopback, announcing this machine's address, 9001 and its name.
sed -e "s/^STORE_HOST=.*/STORE_HOST=127.0.0.1/" -e "s/^ARENA_BIND=.*/ARENA_BIND=127.0.0.1/" \
    -e "s/^ADVERTISE_HOST=.*/ADVERTISE_HOST=$ADDRESS/" \
    /opt/backend/env/arena.env.example > /etc/backend/arena-a1.env
# A machine smaller than the production ones (64 GB): smaller heaps, by a drop-in each.
mem_gb=$(awk '/MemTotal/ {print int($2 / 1048576)}' /proc/meminfo)
if [ "$mem_gb" -lt 48 ]; then
    sed -i "s/^STORE_HEAP=.*/STORE_HEAP=1g/" /etc/backend/store-session.env
    for u in backend-platform backend-gateway backend-worker@ backend-arena@; do
        mkdir -p "/etc/systemd/system/$u.service.d"
        printf '[Service]\nEnvironment="_JAVA_OPTIONS=-Xms512m -Xmx1g -XX:-AlwaysPreTouch"\n' \
            > "/etc/systemd/system/$u.service.d/heap.conf"
    done
    systemctl daemon-reload
fi
```

## 9. nginx and the certificate

The release's nginx (01 §4), this machine's front: it ends TLS on 443 and 9001, and passes
requests and match connections to this machine's processes ([D-82](../architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name)). Its certificate comes from
the backend's own CA, since the site has no domain ([06](06-certificates.md)): a root made once,
whose certificate the app ships, and from it the certificate nginx serves, naming this machine's
address and its arena's name.

```bash
cp /opt/backend/nginx-conf/*.conf /etc/backend/nginx/
sed -i "s/203\.0\.113\.10/$ADDRESS/" /etc/backend/nginx/backend.conf
# This machine's platform, gateway and arena, on loopback: the site's addresses, for one machine.
printf '%s\n' 'upstream backend_gateway {' '    least_conn;' '    server 127.0.0.1:8090;' '}' \
    'upstream backend_platform {' '    server 127.0.0.1:8080;' '    keepalive 32;' '    keepalive_timeout 20s;' '}' \
    > /etc/backend/nginx/backend-upstreams.conf
sed -i -e '/^    [a-c][0-9]\.arena\.internal /d' \
    -e 's/^    default .*/    a1.arena.internal  127.0.0.1:9011;\n    default            "";/' \
    /etc/backend/nginx/backend-stream.conf
install -d -m 0755 /var/log/backend-nginx
# The root, once: its key belongs off this machine afterwards (06 §2). Then the certificate, for this
# machine's address and its arena's name, installed for nginx.
/opt/backend/scripts/private-ca.sh root /root/backend-ca
/opt/backend/scripts/private-ca.sh issue /root/backend-ca /root/backend-certificate "$ADDRESS" \
    $(/opt/backend/scripts/private-ca.sh names /etc/backend/nginx/backend-stream.conf)
install -m 0644 /root/backend-ca/ca.pem /etc/backend/tls/ca.pem
/opt/backend/scripts/install-certificate.sh /root/backend-certificate/fullchain.pem /root/backend-certificate/privkey.pem
```

**`/root/backend-ca/ca.key` is the root's key.** Whoever holds it can pass for the backend to every
player. Move it off this machine now, to where you keep secrets, with a copy apart. It is needed
again only to issue the next certificate, in a year (06 §4). `ca.pem` beside it is the app's: it
goes into the client (06 §5).

## 10. Start everything

In this order; each unit also starts by itself at boot from now on.

```bash
systemctl enable --now backend-store@session
systemctl enable --now backend-platform           # migrates the empty database at its first start
systemctl enable --now backend-gateway backend-worker@c1 backend-arena@a1 backend-nginx
```

## 11. Check it

```bash
for i in $(seq 90); do curl -s -o /dev/null http://127.0.0.1:8080/health && break; sleep 1; done
sleep 10
systemctl is-active backend-mysql backend-store@session backend-platform backend-gateway \
    backend-worker@c1 backend-arena@a1 backend-nginx
# A registration as a device makes it, through nginx, trusting the backend's root alone
curl -s --cacert /etc/backend/tls/ca.pem --connect-to "$ADDRESS:443:127.0.0.1:443" -o /dev/null \
    -w "a registration through nginx: %{http_code}\n" \
    -X POST -H 'Content-Type: application/json' -d '{"username":"installcheck","password":"install-check-1"}' \
    "https://$ADDRESS/v1/accounts"
# Match traffic's TLS on 9001, as a device sees it: the arena's name, verified against the root
echo | openssl s_client -connect 127.0.0.1:9001 -servername a1.arena.internal -verify_hostname a1.arena.internal \
    -CAfile /etc/backend/tls/ca.pem 2>/dev/null | grep 'Verify return code'
/opt/backend/scripts/install-certificate.sh --check
```

Every unit `active`, `201`, `Verify return code: 0 (ok)`, and the certificate's check naming both
ports. Each unit's log: `journalctl -u backend-platform`
(and so on); their metrics on loopback (01 §7).

<!-- /guide-steps -->

## 12. Open the firewall

Players reach nginx's 443 and 9001, and nothing else; SSH comes from the internal network alone
([D-82](../architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name)). Run it from that network, or from the console: a session from elsewhere goes on, but is
the last (05 §5).

<!-- firewall one machine -->
```console
LAN=10.0.0.0/24                          # the internal network operators come from
firewall-cmd --permanent --add-service=https
firewall-cmd --permanent --add-port=9001/tcp
firewall-cmd --permanent --remove-service=cockpit --remove-service=ssh
firewall-cmd --permanent --add-rich-rule="rule family=\"ipv4\" source address=\"$LAN\" service name=\"ssh\" accept"
firewall-cmd --reload
```
<!-- /firewall -->

Services and ports go in separate commands: firewalld refuses `--add-service` and `--add-port` in
one, and applies none of it. Cockpit's 9090, which RHEL allows, is closed, and so is 80: neither is
the backend's. On three machines, each one's firewall also opens its private ports to the other
two, and to no one else: [05 §5](05-ports-and-firewall.md#5-the-firewall-on-rhel-9) has each
machine's commands, and every port is in [05](05-ports-and-firewall.md).

## Operating it

| To | Where |
|---|---|
| Deploy a new version | build again (§3), then [01 §5](01-deploy.md#5-rolling-deployment): unpack beside, switch the symlink, restart in order |
| Back up the database, and prove the backups | [01 §10](01-deploy.md#10-copies-off-the-databases-machine): the binlog stream, the dumps, the weekly proof (on C, or this machine on its own) |
| Handle a failure | the [runbook](02-runbook.md): triage, the store's and MySQL's failover, restores |
| Act as an operator (kick, ban, notices, tournaments) | the admin API: `systemd/platform-admin.conf.example` as a drop-in, then [04 §10](../detailed-design/04-platform-services.md#10-admin-api) |
| Watch it | each process's metrics (01 §7); what to watch, the [runbook §5](02-runbook.md#5-what-to-watch) |
| Take security fixes for Java, MySQL, nginx and OpenSSL | the [runbook](02-runbook.md#6-routine)'s monthly routine; how a version moves, [vendor/README](../../vendor/README.md) |
| Read a log | `journalctl -u <unit>`; GC logs in `/var/log/backend`, MySQL's in `/var/log/backend-mysql`, nginx's in `/var/log/backend-nginx` |

## Three machines

The same steps on each, with these differences (01 §1, architecture/01 §2):

| | A | B | C |
|---|---|---|---|
| MySQL | primary (§6, with TLS: 01 §9) | none | replica ([01 §9, the replica](01-deploy.md#the-replica-on-c-plan-item-13-d-35)) |
| Stores | `session` primary | `session` replica, `events` primary | `events` replica (01 §3) |
| nginx, platform, gateway | yes, every machine set up as the front | yes | yes; the front in the examples (D-82) |
| arenas | `a1`, `a2` | `b1`, `b2`, `b3` | `c1`, `c2` |
| worker | | | yes |
| Backups and their proof | | | yes (01 §10) |

Each machine keeps the shipped examples' private addresses rather than step 8's loopback ones: the
platform, the gateway and the arenas listen on its private address, and reach the stores and MySQL
on theirs (`STORE_HOST`, `BACKEND_STORE_ADDRESSES`, `BACKEND_DB_URL`, with MySQL's TLS settings,
01 §9). nginx keeps the shipped `backend-upstreams.conf` and `backend-stream.conf`, the same file on
every machine, for all three machines' processes. One certificate serves the whole site: issued once
for the site's address and every arena's name, and installed on every machine
([06](06-certificates.md)). Each arena's and worker's instance name is unique across the three
(01 §2), each arena's TLS name too (05 §3), and the units are pinned to their cores (01 §7).

## What this guide does not do

- **The copy off the site** (01 §10, Q-53): its host is not chosen.
- **The client**: the .NET SDK and its packages are not in the repository (the
  owner's choice); the client is built on a developer's machine.
- **The checks that use Docker** (`check-release-el9.sh` and the others) run on a
  build machine, not on a server.
