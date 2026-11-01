# Two machines: a standby for the launch's machine

The launch's machine, A, installed by the [install guide](03-install-guide.md), and a second, B, made its
standby ([D-96](../architecture/03-decision-log.md#d-96--a-second-machine-is-the-first-ones-standby-the-replicas-and-a-front-ready-to-start)):
B holds the replicas of MySQL and both stores, made from A's running primaries, and everything else A runs,
installed from A's own files and stopped. Should A's database or a store fail, the replica on B is promoted
and A's processes follow it; should A fail, B is started in its place and the site's address forwarded to it:
minutes, where one machine alone was hours ([availability](../architecture/02-availability.md#on-one-machine-the-launch-d-93)).
Arenas on B, for the load past about 4 000 in battle, are the second half (plan item 115 (b)).

**Tested as written.** `backend/scripts/check-two-machines-el9.sh` installs A by the install guide's own
check, in Red Hat's UBI 9.5 with systemd and no network, then a second such machine, B, joined to it by a
private link alone; it runs every `bash` block between this page's markers in order, each on the machine its
first line names, while a probe uses the site through A's nginx twice a second and times every refusal; then it
checks B is the standby (below), loses A's MySQL with A running and promotes B's (the [runbook's §2](02-runbook.md#2-stateful-primary-failure)),
then loses A, cut off and frozen, and runs the [runbook's steps for it](02-runbook.md#on-two-machines-d-96) on B:
the session made on A served by B, every account made on A logging in there. The blocks marked `console` are
not run by it: the copy to B, which it makes the same through docker, and the firewall, which
`check-ports-el9.sh` runs. Measured by it, on a development VM under other load, so each figure moves by a few
seconds between runs (two runs of the page as written, the same within that in nine with a rule broken): the site
refused requests for 15 s at MySQL's restart, 5 to 10 s at the stores', 14 to 16 s at the platform's; A's MySQL
lost, 11 to 16 s from its stop to the next write, on B; A lost, B serving 62 to 67 s after the cut, the runbook's
steps run at once. Of about 200 requests in a run, 50 refused, all within those gaps; every registration answered
201 was on B.

## 1. What it makes

| | A, the launch's machine | B, its standby |
|---|---|---|
| MySQL | the primary, now also on its private address, with TLS and GTIDs | a replica of A's, cloned from it, read-only |
| Stores | `session` and `events`, the primaries, also on the private address | a replica of each |
| nginx, platform, gateway, workers, arenas `a1` to `a3` | as installed, given both machines' addresses | installed from A's files, the same names and settings, **stopped and not enabled** |
| Backups and their proof | as installed | none until B is the primary ([runbook](02-runbook.md#on-two-machines-d-96)) |

**What the site sees while it is done** (the owner accepted these gaps, D-96): MySQL on A restarted once,
each store once, and each Java process once, in a deployment's order (§6). While MySQL or a store restarts,
requests that need it are refused; while the platform restarts nginx has nothing to pass them to; the gateway's
restart drops lobby connections, which reconnect; each arena finishes its matches before it restarts.
Nothing committed is lost.

## 2. Before you start

- **B**: a server as A is (install guide [§1](03-install-guide.md#1-what-the-server-needs): RHEL 9.x, the same
  size, its second disk at `/var/lib/backend`), on the private network with A, and SSH between them as root.
  Nothing else: B is given the release A runs (§4).
- **A**: installed by the install guide, serving.
- **An hour of low traffic**, for §3's restarts and §6's.

<!-- two-machine-steps -->

The values, in every shell on either machine: the install guide's [§2](03-install-guide.md#2-the-repository),
A's on both, and these.

```bash
# On A and on B: the two machines' private addresses, and the private network as MySQL's accounts name it
export A_PRIVATE=10.0.0.1                # A, the launch's machine, the primaries
export B_PRIVATE=10.0.0.2                # B, its standby, the replicas
export PRIVATE_NET='10.0.0.%'
```

## 3. On A: MySQL and the stores on the private network

The secrets replication needs, as the install guide's §5 makes the others; a CA of MySQL's own and a
certificate from it for each machine, naming its private address, since the processes check the name
([01 §9](01-deploy.md#9-mysql)); the processes' trust store, that CA alone.

```bash
# On A: the replication's secrets, MySQL's CA and each machine's certificate, the processes' trust store
for s in clone-password failover-password; do
    [ -s "/etc/backend/credentials/$s" ] || (umask 077; openssl rand -hex 24 > "/etc/backend/credentials/$s")
done
# Replication's, 32 characters: MySQL refuses a longer one for a replication source (error 3056)
[ -s /etc/backend/credentials/repl-password ] || (umask 077; openssl rand -hex 16 > /etc/backend/credentials/repl-password)
# The promotion's account, as runbook §2 runs it on the replica's machine: this machine's server by the socket,
# the other's over TLS, its name checked.
(umask 077; printf '[client]\nuser=failover\npassword=%s\nsocket=/run/backend-mysql/mysqld.sock\n\n[client_remote]\nssl-mode=VERIFY_IDENTITY\nssl-ca=/etc/mysql-tls/ca.pem\n' \
    "$(cat /etc/backend/credentials/failover-password)" > /etc/backend/credentials/mysql-failover.cnf)
ca=/root/backend-mysql-ca
install -d -m 0700 "$ca"
[ -s "$ca/ca.pem" ] || openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -subj "/CN=backend MySQL CA" \
    -keyout "$ca/ca.key" -out "$ca/ca.pem"
for ip in "$A_PRIVATE" "$B_PRIVATE"; do
    install -d -m 0700 "$ca/$ip"
    openssl req -newkey rsa:2048 -nodes -subj "/CN=$ip" -keyout "$ca/$ip/server-key.pem" -out "$ca/$ip/server.csr"
    openssl x509 -req -in "$ca/$ip/server.csr" -CA "$ca/ca.pem" -CAkey "$ca/ca.key" -CAcreateserial -days 825 \
        -extfile <(printf 'subjectAltName=IP:%s\nextendedKeyUsage=serverAuth\n' "$ip") -out "$ca/$ip/server-cert.pem"
done
install -d -m 0755 /etc/mysql-tls
install -m 0644 "$ca/ca.pem" "$ca/$A_PRIVATE/server-cert.pem" /etc/mysql-tls/
install -m 0640 -o root -g mysql "$ca/$A_PRIVATE/server-key.pem" /etc/mysql-tls/
rm -f /etc/backend/tls/mysql-ca.p12
/opt/backend/runtime/bin/keytool -J-Dkeystore.pkcs12.certProtectionAlgorithm=NONE -J-Dkeystore.pkcs12.macAlgorithm=NONE \
    -importcert -noprompt -alias mysql-ca -file "$ca/ca.pem" \
    -storetype PKCS12 -keystore /etc/backend/tls/mysql-ca.p12 -storepass unused
chmod 0644 /etc/backend/tls/mysql-ca.p12
```

**`/root/backend-mysql-ca/ca.key` is MySQL's CA key**: whoever holds it can pass for either database to the
processes. Once §4 has copied B's certificate, move the directory off A, to where the site's root key is kept
(install guide §9); it is needed again only to issue a machine's certificate, in two years.

MySQL's GTIDs first, which the replica follows A by: turned on live, one step at a time, as 01 §9 does, with no
restart and nothing refused. The binlog stream reads the binlogs as a replica does, and resumes from the file it
copied last: before the mode reaches `ON` it is moved onto a new binary log, written with GTIDs alone. MySQL begins
a new binary log at each change of the mode, but a transaction begun before `ON_PERMISSIVE` stays without a GTID and
is written in the log that change began; hence the flush once none is left, and the wait for the stream, which may
be behind or restarting (the check stops it for this step). Set at a restart instead, the stream resumed from a file written without them, which a server with GTIDs on refuses to send,
and stopped for good (the check's first run, D-96).

```bash
# On A: GTIDs on, live, the binlog stream on a binary log of GTIDs alone before they are
my() { /opt/backend/mysql/bin/mysql --defaults-extra-file=/etc/backend/credentials/mysql-root.cnf -N "$@"; }
my -e "SET PERSIST enforce_gtid_consistency = ON; SET PERSIST gtid_mode = OFF_PERMISSIVE; SET PERSIST gtid_mode = ON_PERMISSIVE"
for i in $(seq 60); do [ "$(my -e "SHOW STATUS LIKE 'Ongoing_anonymous_transaction_count'" | cut -f2)" = 0 ] && break; sleep 1; done
my -e "FLUSH BINARY LOGS"
current=$(my -e "SHOW BINARY LOG STATUS" | cut -f1)
for i in $(seq 60); do [ -e "/var/lib/backend/binlog/$current" ] && break; sleep 1; done
[ -e "/var/lib/backend/binlog/$current" ] || { echo "the binlog stream has not reached $current: journalctl -u backend-binlog-stream"; exit 1; }
my -e "SET PERSIST gtid_mode = ON"
```

Then MySQL with the rest at once: loopback and the private address, the certificate, TCP without TLS refused (the
processes' URL asks for TLS already, and the backups' clients use it), and the clone plugin, which makes the
replica; GTIDs written in its file too, which B's copy of it starts with. **One restart, seconds**: requests that
read or write the database are refused while it lasts. Then the accounts B and the processes use over the network
(01 §9).

```bash
# On A: MySQL on the private network too, with TLS and the clone plugin: one restart; then its accounts
sed -i -e "s/^bind-address .*/bind-address               = 127.0.0.1,$A_PRIVATE/" \
    -e "s/^require_secure_transport .*/require_secure_transport   = ON/" \
    -e '/^tls_version /a ssl_ca                     = /etc/mysql-tls/ca.pem\nssl_cert                   = /etc/mysql-tls/server-cert.pem\nssl_key                    = /etc/mysql-tls/server-key.pem' \
    -e '/^\[mysqld\]/a server_id                  = 1\ngtid_mode                  = ON\nenforce_gtid_consistency   = ON\nplugin-load-add            = mysql_clone.so' \
    /etc/backend/mysql/backend.cnf
systemctl restart backend-mysql
/opt/backend/mysql/bin/mysql --defaults-extra-file=/etc/backend/credentials/mysql-root.cnf <<SQL
    CREATE USER 'backend'@'$PRIVATE_NET' IDENTIFIED BY '$(cat /etc/backend/credentials/db-password)' REQUIRE SSL;
    GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON backend.* TO 'backend'@'$PRIVATE_NET';
    CREATE USER 'repl'@'$PRIVATE_NET' IDENTIFIED BY '$(cat /etc/backend/credentials/repl-password)' REQUIRE SSL;
    GRANT REPLICATION SLAVE ON *.* TO 'repl'@'$PRIVATE_NET';
    CREATE USER 'clone'@'$PRIVATE_NET' IDENTIFIED BY '$(cat /etc/backend/credentials/clone-password)' REQUIRE SSL;
    GRANT BACKUP_ADMIN ON *.* TO 'clone'@'$PRIVATE_NET';
    CREATE USER 'failover'@'localhost' IDENTIFIED BY '$(cat /etc/backend/credentials/failover-password)';
    CREATE USER 'failover'@'$PRIVATE_NET' IDENTIFIED BY '$(cat /etc/backend/credentials/failover-password)' REQUIRE SSL;
    GRANT SYSTEM_VARIABLES_ADMIN, CONNECTION_ADMIN, REPLICATION_SLAVE_ADMIN, RELOAD ON *.* TO 'failover'@'localhost', 'failover'@'$PRIVATE_NET';
    GRANT SELECT ON performance_schema.* TO 'failover'@'localhost', 'failover'@'$PRIVATE_NET';
    GRANT SELECT, UPDATE ON backend.ha_epoch TO 'failover'@'localhost', 'failover'@'$PRIVATE_NET';
    GRANT SELECT ON backend.ha_heartbeat TO 'failover'@'localhost', 'failover'@'$PRIVATE_NET';
SQL
```

The stores, on loopback and the private address (01 §3): **a restart each, seconds**, the session store's refusing
what needs a session while it lasts. Each reloads its data from its log, so no session is lost.

```bash
# On A: each store on the private network too: a restart each
for s in session events; do
    sed -i "s/^bind .*/bind 127.0.0.1 $A_PRIVATE/" "/etc/backend/store-$s.conf"
    systemctl restart "backend-store@$s"
done
```

Every process's settings given both machines: each store's two addresses, and MySQL's URL with both hosts, its
certificate checked against its name (01 §3, §9). Each process takes them when it restarts, in §6, after B is
ready; the files are made now so that §4 copies them to B as they are.

```bash
# On A: every process's settings name both machines' stores and MySQL: taken at each one's restart, §6
db="jdbc:mysql://$A_PRIVATE:$MYSQL_PORT,$B_PRIVATE:$MYSQL_PORT/backend?sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=file:/etc/backend/tls/mysql-ca.p12&trustCertificateKeyStoreType=PKCS12"
for f in /etc/backend/platform.env /etc/backend/gateway.env /etc/backend/worker.env /etc/backend/arena-*.env; do
    sed -i -e "/^BACKEND_DB_URL=/c BACKEND_DB_URL=$db" \
        -e "/^#\?BACKEND_STORE_ADDRESSES=/c BACKEND_STORE_ADDRESSES=$A_PRIVATE:6379,$B_PRIVATE:6379" \
        -e "/^BACKEND_EVENTS_STORE=/c BACKEND_EVENTS_STORE=$A_PRIVATE:6380,$B_PRIVATE:6380" "$f"
done
```

## 4. To B: the release and A's files

On B, as root, over SSH from A: the release A was installed from (the two machines run one release, and the
clone refuses servers of different versions), A's `/etc/backend` (the secrets, every process's settings,
nginx's configuration and the site's certificate, the stores' files), its units' drop-ins, and B's MySQL
certificate. Nothing on B is edited for B but MySQL's file and the stores' (§5).

<!-- the copy to B -->
```console
# On B, as root, in a shell with the values (§2)
mkdir -p "$SRC/backend/target/release"
scp "root@$A_PRIVATE:$SRC/backend/target/release/backend-*.tar.gz" "$SRC/backend/target/release/"
ssh "root@$A_PRIVATE" "cd / && tar -cf - etc/backend etc/systemd/system/backend-*.service.d root/backend-mysql-ca/ca.pem root/backend-mysql-ca/$B_PRIVATE" \
    > /root/from-a.tar
```

## 5. On B: the replicas, and the front stopped

On B: the install guide's [§4](03-install-guide.md#4-the-release-in-opt) and [§5](03-install-guide.md#5-users-directories-secrets-units),
as written there: the release in `/opt`, the users and directories, the units. §5's secrets are made, then
replaced by A's below. Nothing of the guide after §5: B's MySQL, stores and settings come from A.

<!-- on B: the install guide's 4.; 5. -->

```bash
# On B: A's files over B's: the secrets and settings, the units' drop-ins; B's MySQL certificate where MySQL reads it
tar -xpf /root/from-a.tar -C / && rm /root/from-a.tar
install -d -m 0755 /etc/mysql-tls
install -m 0644 /root/backend-mysql-ca/ca.pem "/root/backend-mysql-ca/$B_PRIVATE/server-cert.pem" /etc/mysql-tls/
install -m 0640 -o root -g mysql "/root/backend-mysql-ca/$B_PRIVATE/server-key.pem" /etc/mysql-tls/
rm -rf /root/backend-mysql-ca
systemctl daemon-reload
```

MySQL as A's, with B's own `server_id`, its private address and `super_read_only`, which lets nothing but
replication write (01 §9). Then the clone, over TLS: it replaces this server's data, accounts and GTID history
with A's, while A goes on serving, then stops this server, which systemd does not start again by itself (error 3707
from the clone says so), so the block starts it; it comes back read-only. Then it follows A by GTIDs.

```bash
# On B: MySQL, read-only, a copy of A's by the clone, then replicating from it
sed -i -e "s/^bind-address .*/bind-address               = 127.0.0.1,$B_PRIVATE/" \
    -e "s/^server_id .*/server_id                  = 2/" \
    -e '/^\[mysqld\]/a super_read_only            = ON' /etc/backend/mysql/backend.cnf
install -d -o mysql -g mysql -m 0750 /var/lib/backend-mysql /var/log/backend-mysql
/opt/backend/mysql/bin/mysqld --defaults-file=/etc/backend/mysql/backend.cnf --initialize-insecure --user=mysql
systemctl enable --now backend-mysql
/opt/backend/mysql/bin/mysql -S /run/backend-mysql/mysqld.sock -u root <<SQL || true
SET GLOBAL offline_mode = ON;
SET GLOBAL super_read_only = OFF;
SET GLOBAL clone_valid_donor_list = '$A_PRIVATE:$MYSQL_PORT';
SET GLOBAL clone_ssl_ca = '/etc/mysql-tls/ca.pem';
CLONE INSTANCE FROM 'clone'@'$A_PRIVATE':$MYSQL_PORT IDENTIFIED BY '$(cat /etc/backend/credentials/clone-password)' REQUIRE SSL;
SQL
# The clone stopped this server: once it has, started again, with A's data and accounts, root's password A's.
for i in $(seq 60); do systemctl is-active -q backend-mysql || break; sleep 1; done
systemctl start backend-mysql
state=$(/opt/backend/mysql/bin/mysql --defaults-extra-file=/etc/backend/credentials/mysql-root.cnf -N \
    -e "SELECT CONCAT(STATE, ' ', ERROR_NO, ' ', ERROR_MESSAGE) FROM performance_schema.clone_status")
case $state in "Completed 0 "*) echo "the clone: $state" ;; *) echo "the clone did not complete: $state"; exit 1 ;; esac
/opt/backend/mysql/bin/mysql --defaults-extra-file=/etc/backend/credentials/mysql-root.cnf <<SQL
CHANGE REPLICATION SOURCE TO SOURCE_HOST = '$A_PRIVATE', SOURCE_PORT = $MYSQL_PORT,
    SOURCE_USER = 'repl', SOURCE_PASSWORD = '$(cat /etc/backend/credentials/repl-password)', SOURCE_AUTO_POSITION = 1,
    SOURCE_SSL = 1, SOURCE_SSL_CA = '/etc/mysql-tls/ca.pem', SOURCE_SSL_VERIFY_SERVER_CERT = 1;
START REPLICA;
SQL
```

Each store a replica of A's: its `replicaof` and the password it gives A, which is its own, since both servers
of a store share one (01 §3). Each takes a full copy, then follows.

```bash
# On B: each store a replica of A's, on loopback and B's private address
for s in session:6379 events:6380; do
    f=/etc/backend/store-${s%%:*}.conf
    sed -i "s/^bind .*/bind 127.0.0.1 $B_PRIVATE/" "$f"
    printf 'replicaof %s %s\nprimaryauth %s\n' "$A_PRIVATE" "${s#*:}" "$(cat /etc/backend/credentials/store-password)" >> "$f"
done
systemctl enable --now backend-store@session backend-store@events
```

**Nothing else is started on B, nor enabled**: nginx, the platform, the gateway, the workers, the arenas, the
backups. Their units and settings are A's, so they start as A's do when the runbook starts them; enabled, a
reboot of B would start them beside A's, and B's arenas, under A's names, would announce themselves in A's stores.

## 6. On A: every process on both machines

Each Java process restarted once, taking §3's settings, in a deployment's order (01 §5): the workers, the platform
(requests refused until it answers again), the gateway (lobby connections reconnect), then each arena, drained
first: it takes no new match and stops once its matches have ended, while the other two take the new ones. Up to
twelve minutes an arena.

```bash
# On A: every Java process restarted once, on both machines' addresses, in a deployment's order
systemctl restart backend-worker@c1
systemctl restart backend-worker@c2
systemctl restart backend-platform
for i in $(seq 300); do curl -s -o /dev/null http://127.0.0.1:8080/health && break; sleep 1; done
systemctl restart backend-gateway
for a in a1:9110 a2:9111 a3:9113; do
    systemctl restart "backend-arena@${a%%:*}"       # drained first: up to twelve minutes
    for i in $(seq 120); do curl -s -o /dev/null "http://127.0.0.1:${a#*:}/metrics" && break; sleep 1; done
done
```

## 7. Check it

```bash
# On B: MySQL's receiver and applier ON and the server read-only; each store a replica, its link up
/opt/backend/mysql/bin/mysql --defaults-extra-file=/etc/backend/credentials/mysql-root.cnf -t -e "
    SELECT c.SERVICE_STATE AS receiver, a.SERVICE_STATE AS applier, @@global.super_read_only AS read_only
    FROM performance_schema.replication_connection_status c, performance_schema.replication_applier_status a"
for p in 6379 6380; do
    { echo "AUTH $(cat /etc/backend/credentials/store-password)"; echo 'INFO replication'; } \
        | JAVA_HOME=/opt/backend/runtime /opt/backend/jredis/bin/j-redis-cli --raw -p "$p" | grep -E 'role:|primary_link_status:'
done
```

```bash
# On A: the workers read B's replicas: MySQL's up and its lag, each store's replica connected
curl -s http://127.0.0.1:9102/metrics | grep -E '^backend_(mysql_replica_(up|lag_seconds)|store_replicas|events_store_replicas) '
```

`ON`, `ON`, `1`; each store `role:replica` and `primary_link_status:up`; on A,
`backend_mysql_replica_up{host="10.0.0.2:3306"} 1` with its lag in seconds, and `backend_store_replicas 1` and
`backend_events_store_replicas 1`. The watch on A alerts from now on when a replica falls behind or goes
([architecture/02 §7](../architecture/02-availability.md#7-a-replicas-health-measured-designed-2026-10-02-plan-item-59)).

<!-- /two-machine-steps -->

## 8. The firewall

Each machine reaches the other's MySQL and stores, and nothing else of it but SSH: replication from B to A, and
after a promotion A's processes to B. B's public side is A's, the install guide's
[§12](03-install-guide.md#12-open-the-firewall), since B becomes the front when A is lost. As in
[05 §5](05-ports-and-firewall.md#5-the-firewall-on-rhel-9), a zone of the other machine's address.

On A, after the install guide's §12:

<!-- firewall two A -->
```console
firewall-cmd --permanent --new-zone=backend
firewall-cmd --permanent --zone=backend --add-source="$B_PRIVATE"
firewall-cmd --permanent --zone=backend --add-service=ssh
firewall-cmd --permanent --zone=backend --add-port="$MYSQL_PORT/tcp" --add-port=6379/tcp --add-port=6380/tcp
firewall-cmd --reload
```
<!-- /firewall -->

On B, the install guide's §12 first, as on A, then:

<!-- firewall two B -->
```console
firewall-cmd --permanent --new-zone=backend
firewall-cmd --permanent --zone=backend --add-source="$A_PRIVATE"
firewall-cmd --permanent --zone=backend --add-service=ssh
firewall-cmd --permanent --zone=backend --add-port="$MYSQL_PORT/tcp" --add-port=6379/tcp --add-port=6380/tcp
firewall-cmd --reload
```
<!-- /firewall -->

## 9. Operating two machines

| To | Where |
|---|---|
| Promote a replica on B, A's MySQL or a store lost | the [runbook's §2](02-runbook.md#2-stateful-primary-failure), on B, A the old primary |
| Run the site from B, A lost | the [runbook](02-runbook.md#on-two-machines-d-96): A powered off first, then B's steps |
| Deploy a new version | A as before ([01 §5](01-deploy.md#5-rolling-deployment)); on B the same unpacking and symlink, and its two stores and MySQL restarted only when the release changed them, replica before primary |
| Change a process's settings | on A, then the same file copied to B, so that what B starts is what A runs |
| Renew the site's certificate | on both machines ([06 §4](06-certificates.md#4-installing-it-on-every-machine)) |
| Renew a MySQL certificate, in two years | §3's lines for that machine's address, with MySQL's CA, then a restart of that MySQL |

## What this page does not do

- **Arenas on B**, and nginx on A passing matches to them: plan item 115 (b).
- **A watch of A from B**: B runs no platform, so nothing alerts when A is lost; where alerts go is held (Q-60).
- **A reboot of either machine after these steps** is not run by the check: MySQL and the stores listen on the
  private address, which must be up before they start (`After=network-online.target`, as their units have).
