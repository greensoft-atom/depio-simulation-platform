#!/bin/bash
# The whole backend on this machine, from the release build-offline.sh made (decisions D-80, D-92,
# docs/development/03-build-and-run.md §5): each service's own script, in order. The development MySQL, j-redis,
# the backend (the platform, a worker, the gateway and an arena) and nginx in front, on 80, 443 and 9001.
#
#   ./run-local.sh start      dev-mysql.sh, dev-jredis.sh, dev-backend.sh, dev-nginx.sh, each started
#   ./run-local.sh stop       nginx, the backend and j-redis stopped; the MySQL is left running (./dev-mysql.sh stop)
#   ./run-local.sh restart    both
#   ./run-local.sh status     each
#
# One service alone, or one process of the backend, is its own script's: ./dev-backend.sh restart platform. The
# settings are the same for each (dev-env.sh); give every command the same. PLAIN=1: no nginx (D-99).
set -euo pipefail
B=$(cd "$(dirname "$0")" && pwd)
nginx=nginx; [ "${PLAIN:-0}" != 1 ] || nginx=      # plain: none to start or list; stop still stops one left from before

case "${1:-}" in
start)
    for s in mysql jredis backend $nginx; do "$B/dev-$s.sh" start; done ;;
stop)
    rc=0
    for s in nginx backend jredis; do "$B/dev-$s.sh" stop || rc=1; done
    exit $rc ;;                                       # each stopped that can be: nginx may be root's (D-98, O-91)
restart)
    "$B/run-local.sh" stop && exec "$B/run-local.sh" start ;;       # by path: bash run-local.sh gave a name alone (O-86)
status)
    rc=0
    for s in $nginx backend jredis mysql; do "$B/dev-$s.sh" status || rc=1; done
    exit $rc ;;                                       # each one's own: a service down is not 0 (O-86)
*)
    echo "usage: $0 start | stop | restart | status" >&2; exit 2 ;;
esac
