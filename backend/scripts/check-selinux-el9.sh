#!/bin/bash
# Checks check-bind.sh's SELinux branch, and the three grants it names, where they are real: a Rocky Linux 9
# guest with SELinux enforcing and a login it confines (D-90, after O-46). This machine has no SELinux, so the
# guest runs under QEMU, in software emulation where there is no KVM. As the confined user devel (user_u),
# against 3314 6380 8080 8081 9001, with python3 and with Java 21's HttpServer, the owner's symptom:
#   - confined, the boolean off: every bind refused, and check-bind.sh names the policy and the remedies;
#   - permissive: every bind allowed, so SELinux is the refuser;
#   - the module grant from 8080's own denial: 8080 allowed, the others still refused;
#   - the boolean on: every bind allowed, the labelled ports (8080, 8081, 9001) too;
#   - the login mapped unconfined, CentOS's default: every bind allowed, check-bind.sh "free for this user".
#
#   backend/scripts/check-selinux-el9.sh      from the checkout. Needs qemu-system-x86_64, qemu-img and
#                                             cloud-localds (Ubuntu: qemu-system-x86, cloud-image-utils), ssh,
#                                             and the network for the image (650 MB, once) and the guest's dnf.
#                                             About forty minutes without KVM the first time, ten after: the
#                                             image, the guest's disk and its packages stay in work/el9.
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/../../dev-env.sh"
W=$WORK/el9
mkdir -p "$W"
PORT=${EL9_SSH_PORT:-2223}
IMAGES=https://dl.rockylinux.org/pub/rocky/9/images/x86_64
for t in qemu-system-x86_64 qemu-img cloud-localds ssh ssh-keygen curl; do
    command -v "$t" > /dev/null || { echo "no $t (Ubuntu: apt-get install qemu-system-x86 qemu-utils cloud-image-utils openssh-client curl)" >&2; exit 1; }
done

# --- the image, checked against Rocky's checksum; the key; the seed cloud-init reads once
if [ ! -f "$W/rocky9.qcow2" ]; then
    echo "--- downloading the Rocky 9 image to $W"
    curl -sS -L -o "$W/rocky9.qcow2" "$IMAGES/Rocky-9-GenericCloud-Base.latest.x86_64.qcow2" || exit 1
    curl -sS -L -o "$W/CHECKSUM" "$IMAGES/CHECKSUM" || exit 1
fi
want=$(grep 'GenericCloud-Base.latest.x86_64.qcow2) =' "$W/CHECKSUM" | awk '{print $4}')
have=$(sha256sum "$W/rocky9.qcow2" | cut -c1-64)
[ "$want" = "$have" ] || { echo "the image's checksum is not Rocky's: $have, wanted $want; remove $W/rocky9.qcow2" >&2; exit 1; }
[ -f "$W/key" ] || ssh-keygen -q -t ed25519 -N '' -f "$W/key" -C check-selinux-el9
KEY=$(cat "$W/key.pub")
cat > "$W/user-data" <<EOF
#cloud-config
hostname: el9selinux
users:
  - { name: rocky, sudo: "ALL=(ALL) NOPASSWD:ALL", shell: /bin/bash, ssh_authorized_keys: ["$KEY"] }
  - { name: devel, shell: /bin/bash, lock_passwd: true, ssh_authorized_keys: ["$KEY"] }
ssh_pwauth: false
runcmd:
  - [ sh, -c, "dnf -y -q install policycoreutils-python-utils python3 java-21-openjdk-devel > /var/log/seed-dnf.log 2>&1; echo dnf-exit \$? >> /var/log/seed-dnf.log" ]
  - [ sh, -c, "semanage login -a -s user_u -r s0 devel >> /var/log/seed-dnf.log 2>&1" ]
  - [ sh, -c, "touch /var/tmp/seed-done" ]
EOF
printf 'instance-id: el9selinux-1\nlocal-hostname: el9selinux\n' > "$W/meta-data"
cloud-localds "$W/seed.img" "$W/user-data" "$W/meta-data" || exit 1
[ -f "$W/disk.qcow2" ] || qemu-img create -q -f qcow2 -b "$W/rocky9.qcow2" -F qcow2 "$W/disk.qcow2" 20G || exit 1

# --- the probe the guest runs: python3 and Java's HttpServer, each port on 127.0.0.1
cat > "$W/probe.sh" <<'EOF'
#!/bin/bash
echo "== $(id -un) $(id -Z) enforcing=$(getenforce)"
for p in "$@"; do python3 - "$p" <<'PY'
import socket, sys, errno
p = int(sys.argv[1]); s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
try:
    s.bind(("127.0.0.1", p)); s.listen(1); print(f"python3 127.0.0.1:{p}: ok")
except OSError as e:
    print(f"python3 127.0.0.1:{p}: {errno.errorcode.get(e.errno, e.errno)} {e.strerror}")
PY
done
cat > /tmp/HttpBind.java <<'J'
import com.sun.net.httpserver.HttpServer; import java.net.InetSocketAddress;
public class HttpBind { public static void main(String[] a) throws Exception { for (String p : a) {
    try { HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", Integer.parseInt(p)), 128); s.stop(0);
          System.out.println("java HttpServer 127.0.0.1:" + p + ": ok"); }
    catch (java.io.IOException e) { System.out.println("java HttpServer 127.0.0.1:" + p + ": " + e); } } } }
J
java /tmp/HttpBind.java "$@" 2>&1 | grep -v '^Note'
EOF

# --- the guest, and the way in
SSH=(ssh -q -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=20 -i "$W/key" -p "$PORT")
accel=tcg; [ -c /dev/kvm ] && accel=kvm
echo "--- booting the guest ($accel, ssh on 127.0.0.1:$PORT, console $W/console.log)"
qemu-system-x86_64 -machine q35,accel=$accel -cpu max -smp 4 -m 2560 -display none -monitor none -serial file:"$W/console.log" \
    -drive file="$W/disk.qcow2",if=virtio,format=qcow2 -drive file="$W/seed.img",if=virtio,format=raw \
    -netdev user,id=n0,hostfwd=tcp:127.0.0.1:$PORT-:22 -device virtio-net-pci,netdev=n0 -pidfile "$W/qemu.pid" &
QEMU=$!
trap 'kill $QEMU 2> /dev/null' EXIT
deadline=$(( $(date +%s) + 3600 ))
until "${SSH[@]}" rocky@127.0.0.1 'test -f /var/tmp/seed-done' 2> /dev/null; do
    kill -0 $QEMU 2> /dev/null || { echo "the guest died; its console: $W/console.log" >&2; exit 1; }
    [ "$(date +%s)" -lt $deadline ] || { echo "the guest was not ready in an hour; its console: $W/console.log" >&2; exit 1; }
    sleep 20
done
# As root in the guest. The script goes through a file with stdin closed: ausearch reads a piped stdin as its input.
root() { "${SSH[@]}" rocky@127.0.0.1 'cat > /tmp/cmd.sh && sudo bash /tmp/cmd.sh < /dev/null' <<< "$*"; }
devel() { "${SSH[@]}" devel@127.0.0.1 "$@"; }
scp -q -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i "$W/key" -P "$PORT" \
    "$W/probe.sh" "$HERE/check-bind.sh" devel@127.0.0.1: || { echo "cannot copy into the guest" >&2; exit 1; }
root "tail -1 /var/log/seed-dnf.log; cat /etc/os-release | grep PRETTY; getenforce; java -version 2>&1 | head -1"

# --- the stages, each with what must and must not be in its output
bad=0
expect() {   # name, output, must-match pattern, must-not-match pattern
    local name=$1 out=$2 must=$3 mustnot=$4 ok=1
    [ -z "$must" ] || grep -q -E -- "$must" <<< "$out" || ok=0
    [ -z "$mustnot" ] || ! grep -q -E -- "$mustnot" <<< "$out" || ok=0
    if [ $ok = 1 ]; then echo "PASS $name"; else echo "FAIL $name"; echo "$out" | sed 's/^/    /'; bad=1; fi
}
PORTS="3314 6380 8080 8081 9001"
refused='Permission denied'
# The start, and a rerun's start: the login confined, the boolean off, no module of ours, enforcing.
root "semanage login -m -s user_u -r s0 devel 2>/dev/null || semanage login -a -s user_u -r s0 devel; setsebool -P selinuxuser_tcp_server off; semodule -r backend-dev 2>/dev/null; setenforce 1"
out=$(devel "bash ~/probe.sh $PORTS")
expect "confined, boolean off: every bind refused, python3 and Java ($(grep -c "$refused" <<< "$out") of 10)" "$out" 'user_u:user_r:user_t' ': ok'
[ "$(grep -c "$refused" <<< "$out")" = 10 ] || { echo "FAIL ten refusals wanted"; bad=1; }
out=$(devel "bash ~/check-bind.sh 3314 6380 8080")
expect "confined: check-bind.sh names SELinux, the boolean off, and the three remedies" "$out" \
    'SELinux is enforcing and this login is confined, user_u:user_r:user_t:s0.*selinuxuser_tcp_server --> off' 'free for this user'
expect "confined: check-bind.sh's lines: setsebool, semanage login, the module from src=8080" "$out" \
    "setsebool -P selinuxuser_tcp_server on" ''
grep -q "semanage login -m -s unconfined_u devel" <<< "$out" && grep -q "grep 'src=8080' | audit2allow -M backend-dev && sudo semodule -i backend-dev.pp" <<< "$out" \
    || { echo "FAIL the login and module lines"; bad=1; }
out=$(root "ausearch -m avc -ts recent | grep -o 'denied  { [a-z_]* }.*tcontext=[^ ]*' | grep -o 'name_bind.*src=8080.*http_cache_port_t' | head -1")
expect "confined: the denial is name_bind, with src=8080 on http_cache_port_t" "$out" 'name_bind.*src=8080.*http_cache_port_t' ''

root "setenforce 0"
out=$(devel "bash ~/probe.sh $PORTS")
expect "permissive: every bind allowed ($(grep -c ': ok' <<< "$out") of 10)" "$out" 'enforcing=Permissive' "$refused"
root "setenforce 1"

devel "bash ~/probe.sh 8080" > /dev/null                      # a fresh denial for the module to come from
out=$(root "ausearch -m avc -ts recent | grep 'src=8080' | audit2allow -M backend-dev > /dev/null && cat backend-dev.te && semodule -i backend-dev.pp && echo module-installed")
expect "module: built from 8080's denial alone and installed" "$out" 'allow user_t http_cache_port_t:tcp_socket name_bind;' ''
grep -q module-installed <<< "$out" || { echo "FAIL module-installed"; bad=1; }
out=$(devel "bash ~/probe.sh 8080 3314 6380")
expect "module: 8080 allowed, python3 and Java, 3314 and 6380 still refused" "$out" '8080: ok' '(3314|6380): ok'
[ "$(grep -c '8080: ok' <<< "$out")" = 2 ] && [ "$(grep -c "$refused" <<< "$out")" = 4 ] || { echo "FAIL two allowed, four refused wanted"; bad=1; }

root "setsebool -P selinuxuser_tcp_server on"
out=$(devel "bash ~/probe.sh $PORTS")
expect "boolean on: every bind allowed, the labelled 8080, 8081 and 9001 too ($(grep -c ': ok' <<< "$out") of 10)" "$out" 'enforcing=Enforcing' "$refused"
out=$(devel "bash ~/check-bind.sh 3314 6380 8080")
expect "boolean on: check-bind.sh finds them free" "$out" '^free for this user: 3314 6380 8080$' 'refused'

root "setsebool -P selinuxuser_tcp_server off; semodule -r backend-dev; semanage login -m -s unconfined_u -r s0-s0:c0.c1023 devel"
out=$(devel "bash ~/probe.sh $PORTS")
expect "unconfined login: every bind allowed, boolean off, no module ($(grep -c ': ok' <<< "$out") of 10)" "$out" 'unconfined_u:unconfined_r:unconfined_t' "$refused"
out=$(devel "bash ~/check-bind.sh 3314 6380 8080")
expect "unconfined login: check-bind.sh finds them free" "$out" '^free for this user: 3314 6380 8080$' 'refused'

root "semanage login -m -s user_u -r s0 devel; poweroff" 2> /dev/null
for _ in $(seq 60); do kill -0 $QEMU 2> /dev/null || break; sleep 5; done
if [ $bad = 0 ]; then echo "ALL PASSED: check-bind.sh's SELinux branch and the three grants, in Rocky 9 enforcing"; else echo "FAILED"; fi
exit $bad
