#!/bin/bash
# Checks a release on RHEL 9's kernel with SELinux enforcing (D-110), which check-release-el9.sh's containers lack:
# they have this machine's kernel and no SELinux. In the Rocky Linux 9 guest check-selinux-el9.sh makes (work/el9), on
# a layer of its disk thrown away after: the release unpacked and MySQL set up as the install guide does (03 §4-§6,
# one machine), backend-mysql.service started by systemd; MySQL serving, TLS over TCP, its native AIO, the binary log
# read by mysqlbinlog and a dump by mysqldump, its process in SELinux's unconfined_service_t and no denial. Then
# check-release-el9.sh's own checks as root in the guest: every native file, program and plugin, and every process.
#
#   backend/scripts/check-release-guest-el9.sh [release-dir]   from the checkout; the release's .tar.gz beside it.
#                                    Needs qemu-system-x86_64, qemu-img, ssh, and work/el9 from one run of
#                                    check-selinux-el9.sh. About half an hour without KVM.
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/../../dev-env.sh"
W=$WORK/el9
REL=${1:-$(ls -d "$HERE"/../target/release/backend-*/ 2> /dev/null | head -1)}
[ -n "$REL" ] && REL=$(cd "$REL" && pwd) || { echo "no release: scripts/make-release.sh first, or name one" >&2; exit 1; }
[ -f "$REL.tar.gz" ] || { echo "no $REL.tar.gz beside the release" >&2; exit 1; }
[ -f "$W/disk.qcow2" ] && [ -f "$W/key" ] && [ -f "$W/seed.img" ] \
    || { echo "no guest in $W: check-selinux-el9.sh makes it, once" >&2; exit 1; }
NAME=$(basename "$REL")
PORT=${EL9_SSH_PORT:-2224}
T=$(mktemp -d "$WORK/tmp/guest-el9.XXXXXX")
qemu-img create -q -f qcow2 -b "$W/disk.qcow2" -F qcow2 "$T/layer.qcow2" || exit 1

SSH=(ssh -q -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=20
     -o ServerAliveInterval=30 -i "$W/key" -p "$PORT")
accel=tcg; [ -c /dev/kvm ] && accel=kvm
echo "--- booting the guest ($accel, ssh on 127.0.0.1:$PORT, console $T/console.log)"
qemu-system-x86_64 -machine q35,accel=$accel -cpu max -smp 4 -m 4096 -display none -monitor none -serial file:"$T/console.log" \
    -drive file="$T/layer.qcow2",if=virtio,format=qcow2 -drive file="$W/seed.img",if=virtio,format=raw \
    -netdev user,id=n0,hostfwd=tcp:127.0.0.1:$PORT-:22 -device virtio-net-pci,netdev=n0 &
QEMU=$!
trap 'kill $QEMU 2> /dev/null; wait $QEMU 2> /dev/null; rm -rf "$T"' EXIT
deadline=$(( $(date +%s) + 1800 ))
until "${SSH[@]}" rocky@127.0.0.1 true 2> /dev/null; do
    kill -0 $QEMU 2> /dev/null || { echo "the guest died; its console: $T/console.log" >&2; exit 1; }
    [ "$(date +%s)" -lt $deadline ] || { echo "no ssh in half an hour" >&2; exit 1; }
    sleep 20
done
# As root in the guest, the script through a file with stdin closed: ausearch reads a piped stdin as its input.
root() { "${SSH[@]}" rocky@127.0.0.1 'cat > /tmp/cmd.sh && sudo bash /tmp/cmd.sh < /dev/null' <<< "$*"; }
echo "--- copying the release in"
scp -q -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i "$W/key" -P "$PORT" \
    "$REL.tar.gz" "$HERE/check-release-el9.sh" rocky@127.0.0.1:/tmp/ || { echo "cannot copy into the guest" >&2; exit 1; }

root "$(cat <<EOF
set -u
R=/opt/backend
M="\$R/mysql/bin/mysql -S /run/backend-mysql/mysqld.sock -u root"
ok() { echo "  ok   \$*"; }
bad() { echo "  FAIL \$*"; FAILS=\$((FAILS + 1)); }
FAILS=0
echo "--- \$(cat /etc/redhat-release), kernel \$(uname -r), SELinux \$(getenforce)"
[ "\$(getenforce)" = Enforcing ] || bad "SELinux not enforcing"
rpm -q libxcrypt-compat > /dev/null && echo "  (libxcrypt-compat installed: libcrypt.so.1 is the system's too)"
echo "--- the release in /opt and MySQL, as the install guide sets them up (03 §4-§6)"
tar -xzf /tmp/$NAME.tar.gz -C /opt && ln -sfn /opt/$NAME /opt/backend
useradd --system --home-dir /var/lib/backend-mysql --shell /usr/sbin/nologin mysql
mkdir -p /etc/backend/mysql
cp \$R/systemd/backend-mysql.service /etc/systemd/system/ && systemctl daemon-reload
sed -e "s/^bind-address .*/bind-address = 127.0.0.1/" -e "s/^innodb_buffer_pool_size .*/innodb_buffer_pool_size = 256M/" \
    -e "/^ssl_/d" -e "s/^require_secure_transport .*/require_secure_transport = OFF/" \
    \$R/mysql-conf/backend.cnf.example > /etc/backend/mysql/backend.cnf
install -d -o mysql -g mysql -m 0750 /var/lib/backend-mysql /var/log/backend-mysql
\$R/mysql/bin/mysqld --defaults-file=/etc/backend/mysql/backend.cnf --initialize-insecure --user=mysql \
    && ok "initialised" || bad "initialise"
systemctl start backend-mysql
[ "\$(systemctl is-active backend-mysql)" = active ] && ok "backend-mysql active: \$(\$M -N -e 'SELECT VERSION()')" \
    || { bad "backend-mysql"; journalctl -u backend-mysql --no-pager | tail -8; }
label=\$(ps -C mysqld -o label= | head -1)
[[ \$label == system_u:system_r:unconfined_service_t:* ]] && ok "its SELinux domain: \$label" || bad "its domain: \$label"
\$M -e "CREATE USER probe@'127.0.0.1' IDENTIFIED BY 'probe-pw'; GRANT ALL ON *.* TO probe@'127.0.0.1';
    CREATE DATABASE probe; CREATE TABLE probe.t (id INT PRIMARY KEY, v VARCHAR(20)); INSERT INTO probe.t VALUES (1, 'el9')"
P="\$R/mysql/bin/mysql -h 127.0.0.1 -P 3306 -u probe -pprobe-pw"
tls=\$(\$P --ssl-mode=REQUIRED -N -e "SHOW SESSION STATUS LIKE 'Ssl_version'" 2> /dev/null | awk '{print \$2}')
[[ \$tls == TLSv1.[23] ]] && ok "TLS over TCP, its own OpenSSL: \$tls" || bad "TLS over TCP: '\$tls'"
aio=\$(\$M -N -e "SELECT @@innodb_use_native_aio")
[ "\$aio" = 1 ] && ! grep -qi "native aio.*disabled\|io_setup" /var/log/backend-mysql/error.log \
    && ok "InnoDB's native AIO, libaio on this kernel" || bad "native AIO: \$aio; \$(grep -i aio /var/log/backend-mysql/error.log)"
log=\$(\$M -N -e "SHOW BINARY LOGS" | tail -1 | awk '{print \$1}')
\$R/mysql/bin/mysqlbinlog --read-from-remote-server -h 127.0.0.1 -u probe -pprobe-pw --ssl-mode=REQUIRED -v "\$log" 2> /dev/null \
    | grep -q "INSERT INTO .probe.\..t." && ok "mysqlbinlog reads \$log from the server, over TLS" || bad "mysqlbinlog"
\$R/mysql/bin/mysqldump -S /run/backend-mysql/mysqld.sock -u root --single-transaction probe 2> /dev/null \
    | grep -q "INSERT INTO .t. VALUES (1,'el9')" && ok "mysqldump" || bad "mysqldump"
grep "\[ERROR\]" /var/log/backend-mysql/error.log | head -3 | sed 's/^/  FAIL /'
grep -q "\[ERROR\]" /var/log/backend-mysql/error.log && FAILS=\$((FAILS + 1))
denied=\$(ausearch -m avc,user_avc -ts boot 2> /dev/null | grep -c 'denied')
[ "\$denied" = 0 ] && ok "no SELinux denial since the boot" || { bad "\$denied denials"; ausearch -m avc -ts boot | tail -5; }
systemctl stop backend-mysql
echo "--- \$FAILS failed"
echo "=== check-release-el9.sh, as root in this guest"
REL_NAME=$NAME PACE=\${PACE:-5} bash /tmp/check-release-el9.sh --inside; inside=\$?
[ \$FAILS = 0 ] && [ \$inside = 0 ]
EOF
)"
rc=$?
root "poweroff" 2> /dev/null
for _ in $(seq 60); do kill -0 $QEMU 2> /dev/null || break; sleep 5; done
[ $rc = 0 ] && echo "ALL PASSED: the release on RHEL 9's kernel, SELinux enforcing" || echo "FAILED"
exit $rc
