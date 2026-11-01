#!/bin/bash
# Can this user bind these TCP ports, here? Each is bound on BIND (127.0.0.1; 0.0.0.0 for a server on every
# address, as dev-nginx.sh's, which a listener on any one address holds, O-83) and let go, and a refusal is
# explained: the policy of this machine that refuses it, the command that lifts it, and the way round
# it (D-88, O-46). A port above 1023 is any user's on Linux, so "Permission denied" on one is never the
# user's rank: it is a policy, which the process's own error does not name; one below 1024 is root's, and
# said to be (O-48). The development scripts
# run this before they start anything; on a server, when a unit fails with Permission denied:
#
#   check-bind.sh PORT...          0: every port is free; 1: one is in use or refused, and why; 2: usage
#   runuser -u backend -- /opt/backend/scripts/check-bind.sh 8080 8081       as the unit's user
#
# The bind is python3's (RHEL 9 carries it for dnf, Ubuntu for itself); without it, the repository's
# JDK compiles and runs a probe class (the release's runtime cannot: no jdk.compiler); with neither,
# the check says so and ends 0, so that a start goes on.
set -uo pipefail
[ $# -ge 1 ] || { echo "usage: $0 PORT..." >&2; exit 2; }
BIND=${BIND:-127.0.0.1}
case $BIND in 127.0.0.1|0.0.0.0) ;; *) echo "BIND is 127.0.0.1 or 0.0.0.0, not $BIND" >&2; exit 2 ;; esac
S=$(cd "$(dirname "$0")" && pwd)

# A bind of each port, as the servers bind (SO_REUSEADDR, so a port in TIME_WAIT is free, as it is
# for them): "<port> ok|EADDRINUSE|EACCES|<another errno's name>", a line each.
probe() {
    if command -v python3 > /dev/null 2>&1; then
        python3 - "$BIND" "$@" <<'PY'
import errno, socket, sys
for p in sys.argv[2:]:
    s = socket.socket()
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        s.bind((sys.argv[1], int(p))); print(p, "ok")
    except OSError as e:
        print(p, errno.errorcode.get(e.errno, str(e.errno)))
    s.close()
PY
        return
    fi
    local java=$S/../../vendor/jdk-21/bin/java d rc      # the repository's JDK, when this is the source tree
    [ -x "$java" ] || { echo "no python3, and no vendor/jdk-21 by this script: nothing here can try a bind; skipped" >&2; return 2; }
    d=$(mktemp -d "${TMPDIR:-/tmp}/check-bind.XXXXXX")
    cat > "$d/BindProbe.java" <<'JAVA'
import java.net.InetSocketAddress;
import java.net.ServerSocket;
public class BindProbe {
    public static void main(String[] args) {
        for (String p : java.util.Arrays.copyOfRange(args, 1, args.length)) {
            try (ServerSocket s = new ServerSocket()) {     // SO_REUSEADDR is on by default here
                s.bind(new InetSocketAddress(args[0], Integer.parseInt(p)));
                System.out.println(p + " ok");
            } catch (java.io.IOException e) {
                String m = String.valueOf(e.getMessage());
                System.out.println(p + " " + (m.contains("in use") ? "EADDRINUSE" : m.contains("ermission denied") ? "EACCES" : m));
            }
        }
    }
}
JAVA
    LC_ALL=C "$java" "$d/BindProbe.java" "$BIND" "$@"; rc=$?
    rm -rf "$d"; return $rc
}

# What listens on port $1, from ss when it is there (/usr/sbin on RHEL, so by path too): the process
# column, which ss shows for one's own processes and to root.
holder() {
    local ss; ss=$(command -v ss 2> /dev/null || ls /usr/sbin/ss /sbin/ss 2> /dev/null | head -1)
    [ -n "$ss" ] || return 0
    "$ss" -H -ltnp 2> /dev/null | awk -v p="$1" '{ n = split($4, a, ":"); if (a[n] == p) { if (NF >= 6) print " (" $6 ")"; exit } }'
}

# The policies of this machine that refuse these ports to this user, each with what lifts it: the threshold for every
# port below it, and the rest, SELinux's grant among them, for port $1, the highest (O-83).
explain() {
    local port=$1 found=0 start ctx b p below=()
    shift
    start=$(cat /proc/sys/net/ipv4/ip_unprivileged_port_start 2> /dev/null)
    for p in "$@"; do [ -n "$start" ] && [ "$p" -lt "$start" ] && below+=("$p"); done
    if [ ${#below[@]} -gt 0 ]; then
        found=1
        echo "  net.ipv4.ip_unprivileged_port_start is $start: this machine keeps the ports below it for root (Linux's own line is 1024): ${below[*]}."
        echo "    lift it:   sudo sysctl -w net.ipv4.ip_unprivileged_port_start=1024   (and that line in /etc/sysctl.d/90-ports.conf, to keep it over a reboot)"
        echo "    or use ports from $start up"
    fi
    if command -v getenforce > /dev/null 2>&1 && [ "$(getenforce 2> /dev/null)" = Enforcing ]; then
        ctx=$(id -Z 2> /dev/null)
        case $ctx in
            unconfined_u:*|"") ;;
            *)  found=1
                b=$(getsebool selinuxuser_tcp_server 2> /dev/null || getsebool user_tcp_server 2> /dev/null \
                    || echo "selinuxuser_tcp_server: not in this policy")
                echo "  SELinux is enforcing and this login is confined, $ctx: a confined user listens only with the boolean on, and it reads: $b"
                echo "    lift it:   sudo setsebool -P selinuxuser_tcp_server on"
                echo "    or make the login unconfined:   sudo semanage login -m -s unconfined_u $(id -un)   then log in again"
                echo "    or allow this port alone, from its own denial (the exact grant, seen in a Rocky 9 guest):"
                echo "      sudo ausearch -m avc -ts recent | grep 'src=$port' | audit2allow -M backend-dev && sudo semodule -i backend-dev.pp"
                echo "    the refusal itself:   sudo ausearch -m avc -ts recent | grep 'src=$port'" ;;
        esac
    fi
    if [ -e /proc/sys/fs/binfmt_misc/WSLInterop ] || grep -qi microsoft /proc/sys/kernel/osrelease 2> /dev/null; then
        found=1
        echo "  This is WSL: under WSL 1, and WSL 2 with mirrored networking, Windows' reserved port ranges (Hyper-V's) hold here, and a bind in one is refused. The ranges:"
        echo "    netsh.exe interface ipv4 show excludedportrange protocol=tcp"
        echo "    use ports outside them, or keep this one out of them, in PowerShell as administrator:"
        echo "    net stop winnat; netsh int ipv4 add excludedportrange protocol=tcp startport=$port numberofports=1; net start winnat"
    fi
    [ $found = 1 ] || echo "  no policy this check knows refuses it. The refusal itself: strace -f -e trace=bind <the command>; on a machine with SELinux, sudo ausearch -m avc -ts recent"
}

out=$(probe "$@"); rc=$?
[ $rc = 2 ] && exit 0
[ $rc = 0 ] || { echo "the bind could not be tried (exit $rc)" >&2; exit 2; }
bad=0; free=(); refused=()
while read -r port state; do
    case $state in
        ok)         free+=("$port") ;;
        EADDRINUSE) bad=1; echo "$BIND:$port: in use$(holder "$port")" ;;
        EACCES)     bad=1; refused+=("$port"); echo "$BIND:$port: refused (Permission denied)" ;;
        *)          bad=1; echo "$BIND:$port: cannot be bound: $state" ;;
    esac
done <<< "$out"
[ ${#free[@]} = 0 ] || echo "free for this user: ${free[*]}"
# Below the unprivileged-port threshold and below 1024, a port is root's by Linux's own line, as nginx's 80 and 443
# are (D-92, O-48): no policy to find. The others refused are a policy's.
start=$(cat /proc/sys/net/ipv4/ip_unprivileged_port_start 2> /dev/null || echo 1024)
low=(); policy=()
for port in "${refused[@]}"; do
    if [ "$port" -lt 1024 ] && [ "$port" -lt "$start" ]; then low+=("$port"); else policy+=("$port"); fi
done
if [ ${#low[@]} -gt 0 ] && [ "$(id -u)" = 0 ]; then
    # Root refused: not its rank, a capability taken away, as a container or a unit may (O-83).
    echo "Ports below 1024 refused to root: ${low[*]}. This process lacks CAP_NET_BIND_SERVICE: a container started"
    echo "    without it, or a unit whose CapabilityBoundingSet= or AmbientCapabilities= leaves it out"
elif [ ${#low[@]} -gt 0 ]; then
    least=${low[0]}; for port in "${low[@]}"; do [ "$port" -lt "$least" ] && least=$port; done
    echo "Ports below 1024 are root's on Linux: ${low[*]}. Run it as root,"
    echo "    or give them to users:   sudo sysctl -w net.ipv4.ip_unprivileged_port_start=$least   (and that line in /etc/sysctl.d/90-ports.conf, to keep it over a reboot)"
    echo "    or use ports from 1024 up"
fi
if [ ${#policy[@]} -gt 0 ]; then
    top=${policy[0]}; for port in "${policy[@]}"; do [ "$port" -gt "$top" ] && top=$port; done
    echo "A port above 1023 is any user's on Linux: a refusal is a policy of this machine. Found:"
    explain "$top" "${policy[@]}"
fi
exit $bad
