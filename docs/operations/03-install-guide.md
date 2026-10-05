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
`active`, the registration `201`, about twenty-five minutes. Running it found
three faults, fixed before this text (O-32 to O-34 in the
[defect register](../defects.md)). The blocks marked `console` are not run by
it (the clone, the firewall).

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

## 2. The repository

```console
dnf install -y git                       # only to clone; or copy a tarball of the repository instead
git clone https://github.com/greensoft-atom/depio-simulation-platform.git /srv/depio-simulation-platform   # private: a token or a deploy key
```

<!-- guide-steps -->

Your values, set once in the shell every later step runs in:

```bash
export SRC=/srv/depio-simulation-platform   # where the repository is
export NAME=a.example.com                # the name players' devices reach this machine by
export MYSQL_BUFFER_POOL=14G             # MySQL's buffer pool: 14G of 20 GB on a production machine
```

## 3. Build

The committed JDK and Maven, the committed libraries, no network
(`build-offline.sh`, 09 §3). About fifteen minutes; `SKIP_TESTS=1` because the
backend's tests need a MySQL of their own ([build and run](../development/03-build-and-run.md#3-the-tests)).

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
local_db="jdbc:mysql://127.0.0.1:3306/backend?sslMode=REQUIRED"
for p in platform gateway worker; do
    sed -e "s/^STORE_HOST=.*/STORE_HOST=127.0.0.1/" -e "s#^BACKEND_DB_URL=.*#BACKEND_DB_URL=$local_db#" \
        "/opt/backend/env/$p.env.example" > "/etc/backend/$p.env"
done
sed -e "s/^STORE_HOST=.*/STORE_HOST=127.0.0.1/" -e "s/^ADVERTISE_HOST=.*/ADVERTISE_HOST=$NAME/" \
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

## 9. nginx

The release's nginx (01 §4). Until the real certificate is installed (01 §11), a
placeholder lets it start; players' devices refuse it, so it is for this check
and for the certificate's first challenge only.

```bash
cp /opt/backend/nginx-conf/*.conf /etc/backend/nginx/
sed -i "s/a\.example\.com/$NAME/g" /etc/backend/nginx/backend.conf
install -d -m 0755 /var/www/acme /var/log/backend-nginx
/opt/backend/scripts/install-certificate.sh --placeholder
```

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
# A registration as a device makes it, through nginx (-k: the placeholder certificate)
curl -sk --resolve "$NAME:443:127.0.0.1" -o /dev/null -w "a registration through nginx: %{http_code}\n" \
    -X POST -H 'Content-Type: application/json' -d '{"username":"installcheck","password":"install-check-1"}' \
    "https://$NAME/v1/accounts"
```

Every unit `active`, and `201`. Each unit's log: `journalctl -u backend-platform`
(and so on); their metrics on loopback (01 §7).

<!-- /guide-steps -->

## 12. Open the firewall

Players reach nginx (443, and 80 for the certificate's challenge) and the arena
(`ARENA_PORT`, 9001); nothing else is reachable from outside.

```console
firewall-cmd --permanent --add-service=https --add-service=http --add-port=9001/tcp
firewall-cmd --reload
```

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
| nginx, platform, gateway, arenas | yes | yes | yes |
| worker | | | yes |
| Backups and their proof | | | yes (01 §10) |

Each machine's private address replaces `127.0.0.1` where a process reaches
another machine (`STORE_HOST`, `BACKEND_STORE_ADDRESSES`, `BACKEND_DB_URL`), the
database's URL carries its TLS settings (01 §9), each arena's and worker's
instance name is unique across the three (01 §2), and the units are pinned to
their cores (01 §7).

## What this guide does not do

- **The certificate and the domain** (01 §11): deferred by the owner; the
  placeholder above stands in.
- **The copy off the site** (01 §10, Q-53): its host is not chosen.
- **The client**: the .NET SDK and its packages are not in the repository (the
  owner's choice); the client is built on a developer's machine.
- **The checks that use Docker** (`check-release-el9.sh` and the others) run on a
  build machine, not on a server.
