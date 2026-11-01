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
# settings are the same for each (dev-env.sh); give every command the same.
set -euo pipefail
B=$(cd "$(dirname "$0")" && pwd)

case "${1:-}" in
start)
    "$B/dev-mysql.sh" start; "$B/dev-jredis.sh" start; "$B/dev-backend.sh" start; "$B/dev-nginx.sh" start ;;
stop)
    "$B/dev-nginx.sh" stop; "$B/dev-backend.sh" stop; "$B/dev-jredis.sh" stop ;;
restart)
    "$0" stop && exec "$0" start ;;
status)
    for s in nginx backend jredis mysql; do "$B/dev-$s.sh" status || true; done ;;
*)
    echo "usage: $0 start | stop | restart | status" >&2; exit 2 ;;
esac
