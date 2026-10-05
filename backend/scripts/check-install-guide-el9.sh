#!/bin/bash
# Runs docs/operations/03-install-guide.md as written: every bash block between its guide-steps
# markers, in order, in Red Hat's UBI 9 init image (systemd its first process) with NO network, on a
# plain copy of the repository's files (what a clone has: tracked and new, nothing ignored). From the
# build with build-offline.sh to a registration through nginx.
#
#   scripts/check-install-guide-el9.sh        needs docker and git; about twenty-five minutes
#   IMAGE=registry.access.redhat.com/ubi9/ubi-init:9.6 scripts/check-install-guide-el9.sh
#
# One thing it adds that the guide does not do: systemd in a container cannot mount the private
# directory LoadCredential delivers into, so the Java units are given their secrets as files their
# user may read, by a drop-in (operations/01 §2 says what was and was not checked).
set -u
if [ "${1:-}" != --inside ]; then
    HERE=$(cd "$(dirname "$0")" && pwd)
    REPO=$(cd "$HERE/../.." && pwd)
    WORK=$(mktemp -d "${TMPDIR:-/tmp}/check-install-guide.XXXXXX")
    trap 'rm -rf "$WORK"' EXIT
    (cd "$REPO" && git ls-files --cached --others --exclude-standard \
        | tar -czf "$WORK/repository.tar.gz" -T -) || exit 1
    IMAGE=${IMAGE:-registry.access.redhat.com/ubi9/ubi-init:9.5}
    NAME="backend-check-guide-$$"
    docker run -d --rm --privileged --network none --name "$NAME" -v "$WORK:/check:ro" \
        -v "$HERE/check-install-guide-el9.sh:/check.sh:ro" "$IMAGE" > /dev/null || exit 1
    sleep 5
    docker exec "$NAME" bash /check.sh --inside
    rc=$?
    docker stop "$NAME" > /dev/null
    exit "$rc"
fi

echo "--- $(cat /etc/redhat-release); network interfaces besides loopback: $(ls /sys/class/net | grep -vc '^lo$')"
mkdir -p /srv/depio-simulation-platform
tar -xzf /check/repository.tar.gz -C /srv/depio-simulation-platform
GUIDE=/srv/depio-simulation-platform/docs/operations/03-install-guide.md

# The guide's steps as one script: its bash blocks in order. After the first (the values), this
# check's own: a test name, a small buffer pool. After the one that installs the units, the container's
# credentials.
awk '
    BEGIN { print "set -euo pipefail" }
    /<!-- guide-steps -->/ { on = 1 }
    /<!-- \/guide-steps -->/ { on = 0 }
    on && /^```bash/ { inb = 1; n++; useradd = 0; print "echo \"=== step " n "\""; next }
    on && inb && /^```/ {
        inb = 0
        if (n == 1) print "export NAME=install.check MYSQL_BUFFER_POOL=256M"
        if (useradd) print "container_credentials"
        next
    }
    inb { print; if ($0 ~ /useradd/) useradd = 1 }
' "$GUIDE" > /tmp/guide-steps.sh
echo "--- the guide's steps: $(grep -c '^echo "=== step' /tmp/guide-steps.sh) blocks"

container_credentials() {
    install -d -o backend -m 0500 /etc/backend/container-credentials
    install -o backend -m 0400 /etc/backend/credentials/db-password /etc/backend/credentials/store-password \
        /etc/backend/container-credentials/
    for u in backend-platform backend-gateway backend-worker@ backend-arena@; do
        mkdir -p "/etc/systemd/system/$u.service.d"
        printf '[Service]\nEnvironment=BACKEND_DB_PASSWORD_FILE=/etc/backend/container-credentials/db-password\nEnvironment=BACKEND_STORE_PASSWORD_FILE=/etc/backend/container-credentials/store-password\n' \
            > "/etc/systemd/system/$u.service.d/container-credentials.conf"
    done
    systemctl daemon-reload
}
export -f container_credentials

bash /tmp/guide-steps.sh 2>&1 | tee /tmp/guide.log | grep -E "^=== step|^active|^inactive|^failed|^activating|registration|ERROR|error|refus" | head -60
rc=${PIPESTATUS[0]}
echo "--- the steps ran: exit $rc"
FAILS=0
[ "$rc" = 0 ] || { FAILS=1; tail -25 /tmp/guide.log; }
grep -q "a registration through nginx: 201" /tmp/guide.log || { echo "  FAIL no registration through nginx"; FAILS=1; }
bad=$(grep -cE "^(inactive|failed|activating)$" /tmp/guide.log)
[ "$bad" = 0 ] || { echo "  FAIL $bad units not active"; FAILS=1; }
echo "--- $FAILS failed"
[ "$FAILS" = 0 ]
