# 07 — Deployment and operations

How the backend is laid out on its three machines, what a release holds, and how the operations
that change it run: a rolling deploy, a store's failover, MySQL's failover, the backups with their
timers, and a restore to a moment. The text these pictures follow is
[operations/01-deploy](../operations/01-deploy.md) and the
[runbook](../operations/02-runbook.md); the files are described in
[deploy/README](../../backend/deploy/README.md) and
[scripts/README](../../backend/scripts/README.md), which also hold the catalogues of settings,
ports and timers.

## The three machines

What runs where, with the ports each listener takes, as
[01 §1](../operations/01-deploy.md#1-what-runs-where) and the `deploy/env/` examples set it. Every
process uses the `session` store's primary; the arenas and workers also use the `events` store's;
`platform` and `worker` use the MySQL primary. Each process is given both addresses of a store and
both MySQL hosts, so the replicas drawn here are where a failover moves those lines. nginx, MySQL
and the stores are the release's own, not the distribution's, each run by its unit from
`/opt/backend` (D-77, [09](../detailed-design/09-release-and-packaging.md)).

```mermaid
flowchart LR
    client["client device"]
    offsite["off-site host, named by the owner (Q-53)"]
    subgraph MA["Machine A, 10.0.0.1"]
        Anginx["nginx 80, 443<br/>backend-nginx"]
        Agw["gateway 127.0.0.1:8090"]
        Apf["platform 127.0.0.1:8080"]
        Aar["arenas a1, a2<br/>0.0.0.0, 9001 and up"]
        Amy["MySQL primary 3306<br/>backend-mysql"]
        Ases["j-redis session primary 6379<br/>backend-store@session"]
    end
    subgraph MB["Machine B, 10.0.0.2"]
        Bnginx["nginx 80, 443<br/>backend-nginx"]
        Bgw["gateway 127.0.0.1:8090"]
        Bpf["platform 127.0.0.1:8080"]
        Bar["arenas b1 to b3<br/>0.0.0.0, 9001 and up"]
        Bses["j-redis session replica 6379<br/>backend-store@session"]
        Bev["j-redis events primary 6380<br/>backend-store@events"]
    end
    subgraph MC["Machine C, 10.0.0.3"]
        Cnginx["nginx 80, 443<br/>backend-nginx"]
        Cgw["gateway 127.0.0.1:8090"]
        Cpf["platform 127.0.0.1:8080"]
        Car["arenas c1, c2<br/>0.0.0.0, 9001 and up"]
        Cwk["workers c1, c2"]
        Cmy["MySQL replica 3306<br/>backend-mysql"]
        Cev["j-redis events replica 6380<br/>backend-store@events"]
        Cbk["backups: binlog stream, dumps, proofs<br/>scratch MySQL 127.0.0.1:3309, backend-scratch-mysql"]
    end
    client -->|"HTTPS and WSS"| Anginx
    client -->|"HTTPS and WSS"| Bnginx
    client -->|"HTTPS and WSS"| Cnginx
    client -->|"match traffic, direct"| Aar
    client -->|"match traffic, direct"| Bar
    client -->|"match traffic, direct"| Car
    Anginx --> Agw
    Anginx --> Apf
    Agw --> Apf
    Bnginx --> Bgw
    Bnginx --> Bpf
    Bgw --> Bpf
    Cnginx --> Cgw
    Cnginx --> Cpf
    Cgw --> Cpf
    Amy -->|"replication, GTIDs, TLS"| Cmy
    Ases -->|"replication"| Bses
    Bev -->|"replication"| Cev
    Amy -->|"binlogs as written, dumps"| Cbk
    Cbk -->|"encrypted, rsync over SSH, hourly"| offsite
```

## nginx's routes

One nginx per machine, in front of that machine's gateway and platform only; match traffic never
passes it (D-5). From `deploy/nginx/backend.conf` and its snippet `backend-platform.conf`.

```mermaid
flowchart LR
    dev["client device"]
    p80["port 80"]
    p443["port 443, TLS 1.2 and 1.3"]
    acme["/.well-known/acme-challenge/<br/>files in /var/www/acme"]
    refuse["anything else on 80<br/>403 HTTPS only, no redirect"]
    lobby["= /lobby"]
    auth["= /v1/sessions, = /v1/accounts,<br/>= /v1/guests, = /v1/accounts/upgrade<br/>limit_req 2 a second, burst 30"]
    v1["/v1/, the rest"]
    other["anything else on 443<br/>404"]
    gw["gateway 127.0.0.1:8090<br/>WebSocket, 150 s timeouts"]
    pf["platform 127.0.0.1:8080<br/>keepalive 32, 15 s read timeout"]
    dev --> p80
    dev --> p443
    p80 --> acme
    p80 --> refuse
    p443 --> lobby
    p443 --> auth
    p443 --> v1
    p443 --> other
    lobby --> gw
    auth --> pf
    v1 --> pf
    gw -->|"PLATFORM_URL"| pf
```

## The release

What `scripts/make-release.sh` builds, after j-redis and a clean build, and how it is installed
([deploy/README](../../backend/deploy/README.md#the-release)). Each process has a `lib/` of its
own, so the MySQL driver stays off the arena and the gateway. The release carries what the
processes run on too: a Java runtime linked from `vendor/jdk-21`, j-redis, and MySQL and nginx from
`vendor/` as committed ([09 §4](../detailed-design/09-release-and-packaging.md#4-the-release)).

```mermaid
flowchart LR
    tgz["backend-VERSION.tar.gz"]
    root["backend-VERSION/<br/>unpacked to /opt/backend-VERSION<br/>/opt/backend points at it"]
    rt["runtime/<br/>Java 21, made by jlink, 63 MB"]
    lib["lib/"]
    la["arena/<br/>its jar and runtime jars"]
    lp["platform/"]
    lw["worker/"]
    lg["gateway/"]
    jr["jredis/<br/>j-redis 2.2.1: bin/, lib/, conf/j-redis.conf"]
    my["mysql/<br/>MySQL 8.4 LTS, vendor/mysql-8.4"]
    ng["nginx/<br/>nginx 1.30, vendor/nginx-1.30"]
    sd["systemd/<br/>units, timers, drop-in examples"]
    ev["env/<br/>the six env examples"]
    mc["mysql-conf/<br/>backend.cnf.example, scratch-my.cnf.example"]
    nc["nginx-conf/<br/>nginx.conf, backend.conf, backend-platform.conf"]
    sc["scripts/<br/>the twelve operational scripts"]
    vs["VERSIONS.md<br/>what is inside, with versions"]
    cm["COMMIT<br/>the git commit"]
    tgz --> root
    root --> rt
    root --> lib
    lib --> la
    lib --> lp
    lib --> lw
    lib --> lg
    root --> jr
    root --> my
    root --> ng
    root --> sd
    root --> ev
    root --> mc
    root --> nc
    root --> sc
    root --> vs
    root --> cm
```

## A rolling deploy

The order of [01 §5](../operations/01-deploy.md#5-rolling-deployment), from the least visible to
players to the most; no script drives it. Each restart is checked before the next: the platform's
`/health`, the journal, the metrics.

```mermaid
flowchart TD
    build["make-release.sh<br/>clean build, tarball"]
    jr{"needs a newer j-redis?"}
    jrup["each store upgraded in its window<br/>replica before primary"]
    unpack["on each machine, unpacked<br/>to /opt/backend-VERSION"]
    units["changed units, drop-ins, nginx files copied<br/>systemctl daemon-reload, reload backend-nginx, which tests first"]
    link["ln -sfn /opt/backend-VERSION /opt/backend<br/>nothing restarts yet"]
    wk["backend-worker@ c1, c2 restarted<br/>Flyway migrates at the first start"]
    pf["backend-platform restarted<br/>one machine at a time"]
    gw["backend-gateway restarted<br/>one machine at a time"]
    ar["backend-arena@ restarted one at a time<br/>drain up to 11 min, TimeoutStopSec 12 min"]
    chk{"health, journal, metrics good?"}
    done["the release in place"]
    back["rollback: symlink back, the same restarts<br/>the schema stays forward"]
    build --> jr
    jr -->|"yes"| jrup
    jr -->|"no"| unpack
    jrup --> unpack
    unpack --> units
    units --> link
    link --> wk
    wk --> pf
    pf --> gw
    gw --> ar
    ar --> chk
    chk -->|"yes"| done
    chk -->|"no"| back
```

## A store's failover

`scripts/promote-store.sh`, run as root on the replica's machine
([runbook §2](../operations/02-runbook.md#a-j-redis-store-built-2026-09-29-d-34)). The clients
follow the highest epoch by themselves; the old primary's machine, when it is back, is made a
replica before it starts.

```mermaid
sequenceDiagram
    participant O as operator
    participant S as promote-store.sh
    participant R as replica
    participant P as old primary
    participant K as processes
    O->>S: sudo promote-store.sh with the config file, the old primary, and old-is-down or demote-old
    S->>S: port and requirepass read from the config file
    S->>R: ROLE, on 127.0.0.1
    alt already a primary
        R-->>S: primary
        S-->>O: nothing to do, the epoch printed (stops here)
    else a replica
        R-->>S: replica
        S->>P: ROLE
        alt old-is-down given, and P answers as primary
            S-->>O: REFUSED, stop it, cut it off, or demote it (stops here)
        else demote-old given, and P answers as primary
            S->>P: REPLICAOF this replica
            P-->>S: OK, it takes no writes now
        else P does not answer as primary
            S->>S: promoting
        end
        S->>R: REPLICAOF NO ONE
        R->>R: epoch raised and recorded in its data directory
        S->>S: config file copied aside, replicaof commented out
        S-->>O: the new role and epoch
        K->>R: each address asked its role, the highest epoch wins
        K->>R: commands resume within a reconnect
    end
```

## MySQL's failover

`scripts/promote-mysql.sh`, run as root on the replica's machine, C
([runbook §2](../operations/02-runbook.md#mysql-built-2026-09-29-d-35)). It refuses before
touching anything when the replica's last heartbeat from the primary is stale, and never promotes
while the old primary may still serve.

```mermaid
sequenceDiagram
    participant O as operator
    participant S as promote-mysql.sh
    participant R as replica on C
    participant P as old primary on A
    participant K as processes
    O->>S: sudo promote-mysql.sh with the old primary, old-is-down or demote-old, maybe stale-ok
    S->>R: replication source configured?
    alt none
        S-->>O: a primary already, its epoch printed (stops here)
    end
    S->>R: the primary's last heartbeat applied, and its age
    alt older than 300 s or unreadable, and no stale-ok
        S-->>O: REFUSED before touching anything (stops here)
    end
    S->>P: read_only, to see whether it answers
    alt old-is-down given, and P answers
        S-->>O: REFUSED, it still answers (stops here)
    else demote-old given, and P answers
        S->>P: SET PERSIST super_read_only ON, SET GLOBAL offline_mode ON
        P-->>S: fenced, writes stopped, connections closed but administrators
    else P does not answer
        S->>S: promoting
    end
    S->>R: WAIT_FOR_EXECUTED_GTID_SET of what it received, 60 s
    S->>R: STOP REPLICA, RESET REPLICA ALL
    S->>R: SET PERSIST super_read_only OFF, read_only OFF
    S->>R: ha_epoch raised by one
    S-->>O: the epoch and the executed GTID set
    K->>R: new connections go to the writable host with the highest epoch
```

## The backups

The backup machine's units and timers ([01 §10](../operations/01-deploy.md#10-copies-off-the-databases-machine)):
`backend-binlog-stream`, `backend-mysql-backup`, `backend-backup-offsite`,
`backend-restore-proof` and `backend-restore-proof-offsite`, each running its script from
`/opt/backend/scripts/`. Every step but the stream records its outcome in `backup_run`, which every
worker reports.

```mermaid
flowchart LR
    prim["MySQL primary on A"]
    t1["backend-mysql-backup.timer<br/>03:30 UTC daily"]
    t2["backend-backup-offsite.timer<br/>hourly, once a host is named"]
    t3["backend-restore-proof.timer<br/>Sundays 04:00 UTC"]
    t4["backend-restore-proof-offsite.timer<br/>first Sunday, 05:00 UTC"]
    st["binlog-stream.sh<br/>always on"]
    dm["backup-mysql.sh<br/>dump, 8 days kept"]
    of["backup-offsite.sh<br/>flush, encrypt, rsync --delete"]
    pr["restore-proof.sh<br/>into backend_proof"]
    po["restore-proof.sh, from off the site<br/>into backend_proof_offsite"]
    fe["fetch-offsite.sh<br/>rsync back, decrypt"]
    bl["/var/lib/backend/binlog"]
    du["/var/lib/backend/mysql-dumps"]
    mi["/var/lib/backend/offsite-mirror"]
    host["off-site host"]
    rd["restore-drill.sh, PROOF=1"]
    sc["scratch MySQL 127.0.0.1:3309"]
    br["backup_run on the primary<br/>by record-backup.sh"]
    wm["worker metrics<br/>backend_backup_succeeded, _failed, _restore_seconds"]
    prim -->|"binlogs as written"| st
    st --> bl
    t1 --> dm
    prim -->|"mysqldump over TLS"| dm
    dm --> du
    dm -->|"prunes copies before the oldest dump's first file"| bl
    t2 --> of
    of -->|"FLUSH BINARY LOGS"| prim
    du --> of
    bl --> of
    of --> mi
    mi -->|"SSH"| host
    t3 --> pr
    pr --> rd
    du --> rd
    bl --> rd
    t4 --> po
    po --> fe
    host --> fe
    fe --> rd
    rd --> sc
    dm --> br
    of --> br
    pr --> br
    po --> br
    br --> wm
```

The two proofs never run at the same time. The binlog stream records nothing, so a stopped stream
shows only in the next copy off the site or the next proof; `systemctl status
backend-binlog-stream` is in the daily checks ([runbook §6](../operations/02-runbook.md#6-routine)).

## A restore to a moment

`scripts/restore-drill.sh` with `STOP_AT`, run by hand on the backup machine
([runbook](../operations/02-runbook.md#recovering-to-a-moment)): the copy is made on the scratch
server, never on the primary, and what the mistake changed is written back from it.

```mermaid
flowchart TD
    m["the moment just before the mistake, in UTC<br/>from the general or slow log, the admin audit, the first error"]
    d["the newest dump taken before it<br/>in /var/lib/backend/mysql-dumps"]
    run["as root on the backup machine<br/>STOP_AT=moment restore-drill.sh dump backend backend_before /var/lib/backend/binlog"]
    same{"the scratch database is the source<br/>on the same server?"}
    r1["refused"]
    pos["the source's position read<br/>SHOW MASTER STATUS, or SHOW BINARY LOG STATUS"]
    wait{"the copies reach it within 30 s?"}
    r2["refused, is the stream running?"]
    gap{"the copies start at the dump's file<br/>with no gap?"}
    r3["refused before anything is loaded"]
    load["backend_before dropped and made again<br/>the dump loaded, sql_log_bin 0"]
    replay["mysqlbinlog --skip-gtids --rewrite-db<br/>stopping before the moment"]
    checks{"every table there, no ledger row at or after the moment,<br/>the ledger reconciled?"}
    fail["DRILL FAILED"]
    ok["drill passed, backend_before holds the moment"]
    back["what the mistake changed, written back<br/>to the primary as ordinary statements"]
    m --> d
    d --> run
    run --> same
    same -->|"yes"| r1
    same -->|"no"| pos
    pos --> wait
    wait -->|"no"| r2
    wait -->|"yes"| gap
    gap -->|"no"| r3
    gap -->|"yes"| load
    load --> replay
    replay --> checks
    checks -->|"no"| fail
    checks -->|"yes"| ok
    ok --> back
```

With no server holding the database left, the same script runs with `NO_SOURCE=1` from the copy
off the site, fetched back by `fetch-offsite.sh`
([runbook](../operations/02-runbook.md#a-last-resort-restore-with-no-source-left)).
