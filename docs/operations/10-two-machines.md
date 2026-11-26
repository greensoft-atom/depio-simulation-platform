# Two machines: a standby for the launch's machine

This page makes a second machine, B, the standby of the launch's machine, A. It is for the operator who runs the site,
and it assumes A was installed by the [install guide](03-install-guide.md). The steps make B a standby
([D-96](../architecture/03-decision-log.md#d-96--a-second-machine-is-the-first-ones-standby-the-replicas-and-a-front-ready-to-start)):
B holds replicas of A's MySQL and of both of A's stores, made from A's running primaries. B also gets everything else
A runs, installed from A's own files and stopped. The [glossary](../glossary.md) defines standby, primary, replica,
promotion and the other terms used here.

What the standby is for:
- **A's database or a store fails**: the replica on B is promoted, and A's processes follow it.
- **A itself fails**: B is started in A's place, and the site's address is forwarded to B.

Either takes minutes, where one machine alone took hours
([availability](../architecture/02-availability.md#on-one-machine-the-launch-d-93)). §10 adds arenas on B, for the
load past about 4 000 players in battle: three arenas of B's own, behind A's nginx (D-97).

**Tested as written.** `backend/scripts/check-two-machines-el9.sh` runs this page exactly as it is written:
1. It installs A by the install guide's own check, in Red Hat's UBI 9.5 with systemd and no network. Then it starts a
   second such machine, B, joined to A by a private link alone.
2. It runs every `bash` block between this page's markers, in order, each on the machine its first line names.
   Meanwhile a probe uses the site through A's nginx twice a second, and times every refusal; every tenth second it
   signs up at the account service and takes the token for a session (D-117). Then it checks that B is the standby
   (§7).
3. It runs §10's blocks, B's own arenas. It reaches each of them by its name on A's 9001, verified and passed to B;
   it checks that A's watch reads them; and the C# headless client plays matches on them through A's nginx, with A's
   arenas stopped for it.
4. It freezes B for a minute. A match is played on A's arenas meanwhile, A's watch sees B's arenas go, and then B
   comes back.
5. It loses A's MySQL while A runs, and promotes B's (the [runbook's §2](02-runbook.md#2-stateful-primary-failure)):
   both epochs raised, the backend's and the account service's, and a sign-up through A's nginx written on B.
6. It loses A, cut off and frozen, and runs the [runbook's steps for that case](02-runbook.md#on-two-machines-d-96)
   on B. The session made on A is served by B, every account made on A logs in there, B's watch reads every process
   there, the account service's among them, and a match is played on B's arenas.

`STANDBY_ONLY=1` leaves §10 out, so that B fails over with A's arenas. The check does not run the blocks marked
`console`: the copies to B, which it makes the same way through docker, and the firewall, which `check-ports-el9.sh`
runs.

**What it measured.** It ran on a development VM under other load, so each figure moves by a few seconds between
runs. The figures are from two runs of the page as written on 2026-11-26, with the account service and §10 (plan item
142); in brackets, the earlier ones, from the runs of D-96 and D-97 before the account service.

| What happened | How long the site refused requests |
|---|---|
| MySQL's restart (§3) | 23 to 26 s (15 s) |
| each store's restart (§3) | none seen by the probe (5 to 10 s) |
| the platform's restart (§6) | 9 to 10 s (14 to 16 s) |
| A's MySQL lost | 11 s, from its stop to the next write, on B (11 to 16 s) |
| A lost, the runbook's steps run at once | until B served, 68 to 69 s after the cut, with B's arenas (74 s; 62 to 67 s without them) |
| §10: A's platform's restart | 9 to 10 s (10 to 18 s) |
| §10: B frozen | no gap |

Of about 500 requests in each run, 64 and 59 were refused, all of them within those gaps. In the second run the probe
signed up 25 times: 24 were answered 201, every one of them then in B's `account` database, three of them refused
their session in a gap (the platform's refusal, the account having been made). The probe's sign-up waits up to 10 s
for each of its two answers, and the probe sends nothing else meanwhile, so one that falls in a gap lengthens it: the
longer gap at MySQL's restart may be partly the probe's own.

## 1. What it makes

| | A, the launch's machine | B, its standby |
|---|---|---|
| MySQL | the primary, now also on its private address, with TLS and GTIDs | a replica of A's, cloned from it, read-only |
| Stores | `session` and `events`, the primaries, also on the private address | a replica of each |
| nginx, the account service, platform, gateway, workers, arenas `a1` to `a3` | as installed, given both machines' addresses | installed from A's files, the same names and settings, **stopped and not enabled** |
| Backups and their proof | as installed | none until B is the primary ([runbook](02-runbook.md#on-two-machines-d-96)) |
| Arenas of B's own, `b1` to `b3` (§10, when the load needs them) | its nginx passes them their matches, its watch reads them | running, on its private address; A's arenas' settings taken off it |

**What players see while the steps run.** The owner accepted these gaps (D-96):
- A's MySQL restarts once, each store once, and each Java process once, in a deployment's order (§6).
- While MySQL or a store restarts, requests that need it are refused.
- While the platform restarts, nginx has nothing to pass requests to.
- The gateway's restart drops the lobby connections, which reconnect.
- Each arena finishes its matches before it restarts.

Nothing committed is lost.

## 2. Before you start

- **B**: a server like A (install guide [§1](03-install-guide.md#1-what-the-server-needs): RHEL 9.x, the same size,
  its second disk at `/var/lib/backend`), on the private network with A, with SSH between the two as root. Nothing
  else: B is given the release A runs (§4).
- **A**: installed by the install guide, and serving.
- **An hour of low traffic**, for the restarts of §3 and §6.

<!-- two-machine-steps -->

Set the values in every shell, on either machine: the install guide's [§2](03-install-guide.md#2-the-repository)
values, with A's values on both machines, and these:

```bash
# On A and on B: the two machines' private addresses, and the private network as MySQL's accounts name it
export A_PRIVATE=10.0.0.1                # A, the launch's machine, the primaries
export B_PRIVATE=10.0.0.2                # B, its standby, the replicas
export PRIVATE_NET='10.0.0.%'
```

## 3. On A: MySQL and the stores on the private network

The first block, on A, makes what replication needs:
1. three secrets, made as the install guide's §5 makes the others: the clone account's, the promotion account's
   (`failover`), and replication's, which is 32 characters long because MySQL refuses a longer one for a replication
   source (error 3056);
2. the promotion account's option file, `mysql-failover.cnf`, as runbook §2 runs it on the replica's machine: this
   machine's server by its socket, the other machine's over TLS with its name checked;
3. a CA of MySQL's own, in `/root/backend-mysql-ca`, and from it a certificate for each machine that names the
   machine's private address, since the processes check that name ([01 §9](01-deploy.md#9-mysql));
4. A's certificate and key, installed where MySQL reads them, in `/etc/mysql-tls/`;
5. the processes' trust store, `/etc/backend/tls/mysql-ca.p12`, which holds that CA alone.

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

**`/root/backend-mysql-ca/ca.key` is MySQL's CA key.** Whoever holds it can pretend to be either database to the
processes. Once §4 has copied B's certificate, move the directory off A, to where the site's root key is kept
(install guide §9). It is needed again only to issue a machine's certificate, in about two years (each lasts 825
days).

**GTIDs next.** B's replica follows A by MySQL's GTIDs (global transaction identifiers). The next block turns them on
while MySQL runs, one step at a time, as 01 §9 does: no restart, and no request refused. The order of the steps
matters because of the binlog stream, the backups' copy of the binary logs (01 §10):
- The stream reads the binary logs as a replica does, and after a restart it resumes from the last file it copied.
- A server with GTIDs on refuses to send a file written without them. So before the mode reaches `ON`, the stream
  must be moved onto a new binary log, written with GTIDs alone.
- MySQL begins a new binary log at each change of the mode. But a transaction that began before `ON_PERMISSIVE` still
  has no GTID, and it is written into the log that change began.

So the block:
1. sets `enforce_gtid_consistency` on, then the mode to `OFF_PERMISSIVE` and `ON_PERMISSIVE`;
2. waits, up to a minute, until no transaction without a GTID is left (`Ongoing_anonymous_transaction_count` 0);
3. flushes the binary logs, so that a new log begins, with GTIDs alone;
4. waits, up to a minute, until the stream has copied that new log; the stream may be behind, or restarting. (The
   check stops the stream during this step on purpose, to prove the wait.) If it has not, the block stops, and
   `journalctl -u backend-binlog-stream` says why;
5. sets the mode to `ON`.

When the mode was set at a restart instead, the stream resumed from a file written without GTIDs, which the server
then refused to send, and the stream stopped for good. That was the check's first run (D-96).

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

**Then the rest of MySQL's configuration, at once.** The next block changes `backend.cnf` and restarts MySQL:
- it listens on loopback and on A's private address;
- it serves its certificate, and refuses TCP without TLS. The processes' URL asks for TLS already, and the backups'
  clients use it;
- it loads the clone plugin, which makes the replica;
- `server_id` 1 and the GTID settings are written into the file too, and B's copy of the file starts with them.

**One restart, of a few seconds**: requests that read or write the database are refused while it lasts. Then the
block makes the accounts that B and the processes use over the network (01 §9): `backend`, `repl` and `clone`, each
from the private network and over TLS alone; and `failover`, on `localhost` and from the private network.

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
    CREATE USER 'account'@'$PRIVATE_NET' IDENTIFIED BY '$(cat /etc/backend/credentials/account-db-password)' REQUIRE SSL;
    GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON account.* TO 'account'@'$PRIVATE_NET';
    CREATE USER 'repl'@'$PRIVATE_NET' IDENTIFIED BY '$(cat /etc/backend/credentials/repl-password)' REQUIRE SSL;
    GRANT REPLICATION SLAVE ON *.* TO 'repl'@'$PRIVATE_NET';
    CREATE USER 'clone'@'$PRIVATE_NET' IDENTIFIED BY '$(cat /etc/backend/credentials/clone-password)' REQUIRE SSL;
    GRANT BACKUP_ADMIN ON *.* TO 'clone'@'$PRIVATE_NET';
    CREATE USER 'failover'@'localhost' IDENTIFIED BY '$(cat /etc/backend/credentials/failover-password)';
    CREATE USER 'failover'@'$PRIVATE_NET' IDENTIFIED BY '$(cat /etc/backend/credentials/failover-password)' REQUIRE SSL;
    GRANT SYSTEM_VARIABLES_ADMIN, CONNECTION_ADMIN, REPLICATION_SLAVE_ADMIN, RELOAD ON *.* TO 'failover'@'localhost', 'failover'@'$PRIVATE_NET';
    GRANT SELECT ON performance_schema.* TO 'failover'@'localhost', 'failover'@'$PRIVATE_NET';
    GRANT SELECT, UPDATE ON backend.ha_epoch TO 'failover'@'localhost', 'failover'@'$PRIVATE_NET';
    GRANT SELECT, UPDATE ON account.ha_epoch TO 'failover'@'localhost', 'failover'@'$PRIVATE_NET';
    GRANT SELECT ON backend.ha_heartbeat TO 'failover'@'localhost', 'failover'@'$PRIVATE_NET';
SQL
```

**The stores**, next, listen on loopback and on the private address too (01 §3). Each needs **a restart, of a few
seconds**. While the session store restarts, requests that need a session are refused. Each store reloads its data
from its log, so no session is lost.

```bash
# On A: each store on the private network too: a restart each
for s in session events; do
    sed -i "s/^bind .*/bind 127.0.0.1 $A_PRIVATE/" "/etc/backend/store-$s.conf"
    systemctl restart "backend-store@$s"
done
```

**Every process's settings**, last, name both machines: each store's two addresses, and MySQL's URL with both hosts,
its certificate checked against its name (01 §3, §9); the account service's URL to its own database the same way
(D-117). Its settings are otherwise this machine's: its key set, its issuer and its events stay on loopback, so on B
they are B's own, and B's account service signs with A's key, copied with the rest (§4). Each process takes the new settings when it restarts, in §6,
after B is ready. The files are changed now so that §4 copies them to B as they are.

```bash
# On A: every process's settings name both machines' stores and MySQL: taken at each one's restart, §6
db="jdbc:mysql://$A_PRIVATE:$MYSQL_PORT,$B_PRIVATE:$MYSQL_PORT/backend?sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=file:/etc/backend/tls/mysql-ca.p12&trustCertificateKeyStoreType=PKCS12"
for f in /etc/backend/platform.env /etc/backend/gateway.env /etc/backend/worker.env /etc/backend/arena-*.env; do
    sed -i -e "/^BACKEND_DB_URL=/c BACKEND_DB_URL=$db" \
        -e "/^#\?BACKEND_STORE_ADDRESSES=/c BACKEND_STORE_ADDRESSES=$A_PRIVATE:6379,$B_PRIVATE:6379" \
        -e "/^BACKEND_EVENTS_STORE=/c BACKEND_EVENTS_STORE=$A_PRIVATE:6380,$B_PRIVATE:6380" "$f"
done
acc="jdbc:mysql://$A_PRIVATE:$MYSQL_PORT,$B_PRIVATE:$MYSQL_PORT/account?sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=file:/etc/backend/tls/mysql-ca.p12&trustCertificateKeyStoreType=PKCS12"
sed -i "/^ACCOUNT_DB_URL=/c ACCOUNT_DB_URL=$acc" /etc/backend/account.env
```

## 4. To B: the release and A's files

Run this on B, as root, over SSH to A. It copies to B:
- the release A was installed from. The two machines must run one release, because the clone refuses servers of
  different versions;
- A's `/etc/backend`: the secrets, every process's settings, nginx's configuration and the site's certificate, and
  the stores' files;
- A's units' drop-ins (`/etc/systemd/system/backend-*.service.d`);
- MySQL's CA certificate, and B's MySQL certificate and key.

On B nothing is then edited for B except MySQL's file and the stores' files (§5), and, with §10, B's arenas' settings
and its watch's targets.

<!-- the copy to B -->
```console
# On B, as root, in a shell with the values (§2)
mkdir -p "$SRC/backend/target/release"
scp "root@$A_PRIVATE:$SRC/backend/target/release/backend-*.tar.gz" "$SRC/backend/target/release/"
ssh "root@$A_PRIVATE" "cd / && tar -cf - etc/backend etc/systemd/system/backend-*.service.d root/backend-mysql-ca/ca.pem root/backend-mysql-ca/$B_PRIVATE" \
    > /root/from-a.tar
```

## 5. On B: the replicas, and the front stopped

First run the install guide's [§4](03-install-guide.md#4-the-release-in-opt) and
[§5](03-install-guide.md#5-users-directories-secrets-units) on B, as written there: the release in `/opt`, the users
and directories, and the units. §5 makes new secrets on B; the next block replaces them with A's. Do not run the
guide's steps after §5: B's MySQL, stores and settings come from A.

<!-- on B: the install guide's 4.; 5. -->

Then the block below unpacks A's files over B's own, puts B's MySQL certificate and key where MySQL reads them, and
removes the copy of MySQL's CA directory.

```bash
# On B: A's files over B's: the secrets and settings, the units' drop-ins; B's MySQL certificate where MySQL reads it
tar -xpf /root/from-a.tar -C / && rm /root/from-a.tar
install -d -m 0755 /etc/mysql-tls
install -m 0644 /root/backend-mysql-ca/ca.pem "/root/backend-mysql-ca/$B_PRIVATE/server-cert.pem" /etc/mysql-tls/
install -m 0640 -o root -g mysql "/root/backend-mysql-ca/$B_PRIVATE/server-key.pem" /etc/mysql-tls/
rm -rf /root/backend-mysql-ca
systemctl daemon-reload
```

**B's MySQL.** The next block:
1. takes A's configuration, with B's own `server_id` (2), B's private address, and `super_read_only`, which lets
   nothing but replication write (01 §9);
2. initialises a new, empty server, and starts it;
3. runs the clone, over TLS. It replaces this server's data, accounts and GTID history with A's, while A goes on
   serving. Then it stops this server, and systemd does not start it again by itself (the clone's error 3707 says
   so). So the block waits until the server has stopped, starts it again, and checks that the clone completed. The
   server comes back read-only, with A's data and accounts; root's password is now A's;
4. starts replication from A, over TLS with A's certificate checked, following A by GTIDs.

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

**B's stores.** Each store becomes a replica of A's: the block adds `replicaof` and `primaryauth`, the password the
replica gives A. That password is the store's own, because both servers of a store share one (01 §3). Each store
listens on loopback and on B's private address, takes a full copy from A, then follows it.

```bash
# On B: each store a replica of A's, on loopback and B's private address
for s in session:6379 events:6380; do
    f=/etc/backend/store-${s%%:*}.conf
    sed -i "s/^bind .*/bind 127.0.0.1 $B_PRIVATE/" "$f"
    printf 'replicaof %s %s\nprimaryauth %s\n' "$A_PRIVATE" "${s#*:}" "$(cat /etc/backend/credentials/store-password)" >> "$f"
done
systemctl enable --now backend-store@session backend-store@events
```

**Nothing else is started or enabled on B**: not nginx, the platform, the gateway, the workers, the arenas or the
backups. Their units and settings are A's, so they start as A's do when the runbook starts them. If they were
enabled, a reboot of B would start them beside A's processes, and B's arenas, under A's names, would announce
themselves in A's stores.

## 6. On A: every process on both machines

Back on A, each Java process restarts once, and takes §3's settings. The order is a deployment's (01 §5):
1. the two workers;
2. the account service, then the platform: requests are refused until each answers again, which the block waits for;
3. the gateway: lobby connections reconnect;
4. each arena in turn, drained first: it takes no new match, and stops once its matches have ended, while the other
   two take the new ones. That takes up to twelve minutes an arena. The block waits for each arena's metrics to
   answer before the next one.

```bash
# On A: every Java process restarted once, on both machines' addresses, in a deployment's order
systemctl restart backend-worker@c1
systemctl restart backend-worker@c2
systemctl restart backend-account
for i in $(seq 300); do curl -s -o /dev/null http://127.0.0.1:8070/v1/health && break; sleep 1; done
systemctl restart backend-platform
for i in $(seq 300); do curl -s -o /dev/null http://127.0.0.1:8080/health && break; sleep 1; done
systemctl restart backend-gateway
for a in a1:9110 a2:9111 a3:9113; do
    systemctl restart "backend-arena@${a%%:*}"       # drained first: up to twelve minutes
    for i in $(seq 120); do curl -s -o /dev/null "http://127.0.0.1:${a#*:}/metrics" && break; sleep 1; done
done
```

## 7. Check it

On B, the first block shows MySQL's replication and each store's role. On A, the second shows what the workers read
of B's replicas.

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

**What you should see:**
- on B, MySQL's `receiver` `ON`, `applier` `ON` and `read_only` `1`;
- on B, each store `role:replica` and `primary_link_status:up`;
- on A, `backend_mysql_replica_up{host="10.0.0.2:3306"} 1` with its lag in seconds, `backend_store_replicas 1` and
  `backend_events_store_replicas 1`.

From now on the watch on A raises an alert when a replica falls behind or goes away
([architecture/02 §7](../architecture/02-availability.md#7-a-replicas-health-measured-designed-2026-10-02-plan-item-59)).

<!-- /two-machine-steps -->

## 8. The firewall

Each machine may reach the other's MySQL and stores, and nothing else of it but SSH. B's replicas connect to A's
primaries, and after a promotion A's processes connect to B's. B's public side is the same as A's, the install
guide's [§12](03-install-guide.md#12-open-the-firewall), because B becomes the front when A is lost. As in
[05 §5](05-ports-and-firewall.md#5-the-firewall-on-rhel-9), the private side is a zone, `backend`, whose source is
the other machine's address. The commands use §2's values (`A_PRIVATE`, `B_PRIVATE`, `MYSQL_PORT`).

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

On B, first the install guide's §12, as on A, then:

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
| Promote a replica on B, when A's MySQL or a store is lost | the [runbook's §2](02-runbook.md#2-stateful-primary-failure), on B, with A as the old primary |
| Run the site from B, when A is lost | the [runbook](02-runbook.md#on-two-machines-d-96): A powered off first, then B's steps |
| Deploy a new version | A as before ([01 §5](01-deploy.md#5-rolling-deployment)). On B the same unpacking and link switch; its arenas restarted one at a time, drained, as A's are; its two stores and MySQL restarted only when the release changed them, the replica before the primary |
| Change a process's settings | on A, then the same file copied to B, so that what B starts is what A runs. The exceptions: the arenas' settings, each machine's own, and with arenas on B (§10) the watch's targets in `platform.env` |
| Renew the site's certificate | on both machines ([06 §4](06-certificates.md#4-installing-it-on-every-machine)) |
| Renew a MySQL certificate, in about two years | §3's lines for that machine's address, with MySQL's CA, then a restart of that MySQL |

## 10. Arenas on B

Add B's own arenas when the load needs them, past about 4 000 players in battle
([04 §6](04-scaling-and-performance.md#6-when-to-add-capacity)), and only once B is the standby. They are three,
`b1` to `b3`: the names the three-machine list of arenas gives B
([D-97](../architecture/03-decision-log.md#d-97--arenas-on-the-standby-three-of-its-own-behind-as-nginx-watched-from-a)).
They run on B's private address. A's nginx passes them their matches by name, and A's watch reads their metrics. With
A's three, that makes six arenas and 10 800 places. Nothing on A restarts except its platform, once, for its watch:
requests are refused for some seconds.

The order is [06 §7](06-certificates.md#7-adding-an-arena-or-changing-the-address)'s: first the names in nginx's list
of arenas, then a certificate issued with them, then the arenas, because a device refuses an arena whose name the
certificate lacks.

Issuing the certificate needs the root. Its directory was moved off A after the install guide's
[§9](03-install-guide.md#9-nginx-and-the-certificate): bring it back to `/root/backend-ca` on A for this step, and
move it off again afterwards.

<!-- two-machine-arenas -->

The first block, on A, adds B's three arenas to nginx's list (`backend-arenas.conf`) at B's private address, issues
the certificate again with every name in the list, and installs it.

```bash
# On A, the root's directory back in /root/backend-ca: B's arenas in nginx's list, the certificate issued again with their names
grep -q '^b1\.arena\.internal ' /etc/backend/nginx/backend-arenas.conf || printf '%s\n' "b1.arena.internal  $B_PRIVATE:9011;" \
    "b2.arena.internal  $B_PRIVATE:9012;" "b3.arena.internal  $B_PRIVATE:9013;" >> /etc/backend/nginx/backend-arenas.conf
/opt/backend/scripts/private-ca.sh issue /root/backend-ca /root/backend-certificate-b "$ADDRESS" \
    $(/opt/backend/scripts/private-ca.sh names /etc/backend/nginx/backend-arenas.conf)
/opt/backend/scripts/install-certificate.sh /root/backend-certificate-b/fullchain.pem /root/backend-certificate-b/privkey.pem
```

nginx reloads with the new list and the new certificate together, and drops nothing. Both of its maps read the list:
9001's and 80's (D-115). Devices are sent to B's arenas only once those arenas announce themselves.

B needs the same two files, the list and the certificate, because B becomes the front once A is lost. Copy them to B:

<!-- the arenas' copy to B -->
```console
# On B, as root, in a shell with the values (§2)
ssh "root@$A_PRIVATE" "cd / && tar -cf - etc/backend/nginx/backend-arenas.conf root/backend-certificate-b" > /root/from-a.tar
```

Then open B's firewall to A's nginx and A's watch: the arenas' ports and their metrics ports, from A alone, in §8's
`backend` zone.

<!-- firewall two B arenas -->
```console
firewall-cmd --permanent --zone=backend --add-port=9011-9013/tcp --add-port=9110-9111/tcp --add-port=9113/tcp
firewall-cmd --reload
```
<!-- /firewall -->

The next block, on B:
1. unpacks the list and the certificate, installs the certificate, and removes the copy, which holds its key;
2. writes B's three arenas' settings from A's first arena's (both machines' stores, the site's address and 9001 to
   announce), each with its own name, port and metrics port, on B's address; and makes them root's alone, since they
   hold the store's password (D-102);
3. removes A's arenas' settings from B. B never runs A's arenas, and the runbook starts the arenas a machine has
   settings for;
4. gives B's watch its targets. B's platform runs only after A is lost, but its watch is given now what it will read
   then: B's own processes on loopback, the account service's among them, and B's arenas on B's address. This is the
   one line of B's `platform.env` that differs from A's;
5. starts and enables `b1` to `b3`, and waits until each one's metrics answer.

```bash
# On B: the list and the certificate with B's arenas' names; B's three arenas on its address, started and enabled
tar -xpf /root/from-a.tar -C / && rm /root/from-a.tar
/opt/backend/scripts/install-certificate.sh /root/backend-certificate-b/fullchain.pem /root/backend-certificate-b/privkey.pem
rm -rf /root/backend-certificate-b
for b in b1:9011:9110 b2:9012:9111 b3:9013:9113; do
    IFS=: read -r name port metrics <<< "$b"
    sed -e "s/^ARENA_BIND=.*/ARENA_BIND=$B_PRIVATE/" -e "s/^ARENA_PORT=.*/ARENA_PORT=$port/" \
        -e "s/^BACKEND_ADVERTISE_TLS_NAME=.*/BACKEND_ADVERTISE_TLS_NAME=$name.arena.internal/" \
        -e "s/^BACKEND_METRICS_ADDR=.*/BACKEND_METRICS_ADDR=$B_PRIVATE:$metrics/" \
        /etc/backend/arena-a1.env > "/etc/backend/arena-$name.env"
done
chmod 0600 /etc/backend/arena-b*.env               # the store's password is in them (D-102)
rm /etc/backend/arena-a1.env /etc/backend/arena-a2.env /etc/backend/arena-a3.env
targets="platform=127.0.0.1:9101,gateway=127.0.0.1:9103,arena=$B_PRIVATE:9110,arena=$B_PRIVATE:9111,arena=$B_PRIVATE:9113"
sed -i "/^BACKEND_WATCH_TARGETS=/c BACKEND_WATCH_TARGETS=$targets,worker=127.0.0.1:9102,worker=127.0.0.1:9112,account=127.0.0.1:9104" \
    /etc/backend/platform.env
systemctl enable --now backend-arena@b1 backend-arena@b2 backend-arena@b3
for m in 9110 9111 9113; do
    for i in $(seq 120); do curl -s -o /dev/null "http://$B_PRIVATE:$m/metrics" && break; sleep 1; done
done
```

Back on A, the next block removes the certificate's directory, which holds its key; adds B's three arenas to the
watch's targets; and restarts A's platform for it. Requests are refused until the platform answers again.

```bash
# On A: the watch reads B's arenas too: A's platform restarted once
rm -rf /root/backend-certificate-b
sed -i "/^BACKEND_WATCH_TARGETS=/s/\$/,arena=$B_PRIVATE:9110,arena=$B_PRIVATE:9111,arena=$B_PRIVATE:9113/" /etc/backend/platform.env
systemctl restart backend-platform
for i in $(seq 300); do curl -s -o /dev/null http://127.0.0.1:8080/health && break; sleep 1; done
```

Then move the root's directory off A again. Last, check from A: each of B's arenas by its name on 9001, the
certificate verified for that name and nginx passing the connection to B; and the watch reading every target, with
none failing.

```bash
# On A: B's arenas by name on 9001, passed to B; the watch's reads, none failed
for n in b1 b2 b3; do
    echo | openssl s_client -connect 127.0.0.1:9001 -servername $n.arena.internal -verify_hostname $n.arena.internal \
        -CAfile /etc/backend/tls/ca.pem 2>/dev/null | grep -E '^subject=|Verify return code'
done
sleep 2
grep -E '"b[1-3]\.arena\.internal"' /var/log/backend-nginx/stream.log | tail -3
sleep 30
curl -s http://127.0.0.1:9101/metrics | grep '^backend_watch_scrapes_total'
```

<!-- /two-machine-arenas -->

**What you should see:**
- three `subject=` lines, each with `Verify return code: 0 (ok)`;
- the log's lines naming `10.0.0.2:9011` to `9013`, B's address, each with `200`;
- `backend_watch_scrapes_total{outcome="failed"}` absent, or not growing between two reads half a minute apart, while
  `outcome="ok"` grows by ten a round (A's watch now has ten targets).

From then on, a player's battle on B is a device connecting to the site's address and 9001 with B's arena's name.
Nothing in the app changes.

**B lost, while A runs**: the battles on B are lost, as A's are when A is lost. Players are sent to A's arenas once
B's have left the arena directory, within its 10 s. A's watch raises `TargetDown` for B's arenas, beside its
replicas' alerts. When B comes back, its arenas announce themselves again and its replicas catch up. If B is lost for
good, make a new B at the same address with this page's steps on B, from §4 on, and this section's steps on B; what
they did on A still stands.

## What this page does not do

- **A watch of A from B**: B runs no platform, so nothing raises an alert when A is lost. Where alerts go is held
  (Q-60).
- **A watch of B's machine itself**: nothing reads B's disks, memory and load until its platform runs, after A is
  lost. What fails there shows on A's watch as B's arenas or replicas down (D-97).
- **A reboot of either machine after these steps** is not run by the check. MySQL and the stores listen on the private
  address, which must be up before they start (`After=network-online.target`, as their units have).
