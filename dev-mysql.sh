#!/bin/bash
# The development MySQL, for the tests and a run by hand (decision D-80): vendor/'s MySQL 8.4,
# its data, socket and logs in work/mysql, listening on 127.0.0.1:$MYSQL_PORT (3314) only. The
# machine's own MySQL is not touched: no option file is read (--no-defaults), and this one has
# its own port, socket and data. The socket is named relative to the data directory, where mysqld
# runs, so that no path is too long for a socket (107 bytes) however deep the checkout. Each start
# makes sure of the databases backend_test (the tests'; they wipe it) and backend_dev (a run by
# hand's), and of their user, backend / backend-dev-password.
#
#   ./dev-mysql.sh start | stop | restart | status
set -euo pipefail
. "$(dirname "$0")/dev-env.sh"
M=$B/vendor/mysql-8.4/bin
D=$WORK/mysql
# The clients reach it by the socket, from the data directory.
admin() { (cd "$D/data" 2> /dev/null && "$M/mysqladmin" --no-defaults -S mysqld.sock -u root "$@"); }
up() { admin ping > /dev/null 2>&1; }

case "${1:-}" in
start)
    if up; then
        on=$(cd "$D/data" && "$M/mysql" --no-defaults -S mysqld.sock -u root -N -e 'SELECT @@port')
        [ "$on" = "$MYSQL_PORT" ] || { echo "the development MySQL already runs, on $on, not $MYSQL_PORT:" \
                                            "$0 stop first" >&2; exit 1; }
    else
        # In use, or refused by a policy of the machine: named before anything starts (D-88)
        "$B/backend/scripts/check-bind.sh" "$MYSQL_PORT" || { echo "another port for it: MYSQL_PORT=<a free port> $0 start" >&2; exit 1; }
        mkdir -p "$D"
        if [ ! -d "$D/data" ]; then
            "$M/mysqld" --no-defaults --initialize-insecure --datadir="$D/data" --user="$(id -un)" \
                --log-error="$D/init.err" \
                || { rm -rf "$D/data"; echo "MySQL could not be initialised: $D/init.err" >&2; exit 1; }
        fi
        nohup "$M/mysqld" --no-defaults --datadir="$D/data" --user="$(id -un)" --port="$MYSQL_PORT" \
            --bind-address=127.0.0.1 --mysqlx=OFF --socket=mysqld.sock --pid-file="$D/mysqld.pid" \
            --log-error="$D/mysqld.err" --tmpdir="$TMPDIR" --default-time-zone=+00:00 > /dev/null 2>&1 &
        for _ in $(seq 60); do up && break; sleep 1; done
        up || { echo "MySQL did not start; its log, $D/mysqld.err, ends:" >&2; tail -5 "$D/mysqld.err" >&2; exit 1; }
    fi
    (cd "$D/data" && "$M/mysql" --no-defaults -S mysqld.sock -u root -e "
        CREATE DATABASE IF NOT EXISTS backend_test; CREATE DATABASE IF NOT EXISTS backend_dev;
        CREATE USER IF NOT EXISTS backend@'127.0.0.1' IDENTIFIED BY 'backend-dev-password';
        GRANT ALL ON backend_test.* TO backend@'127.0.0.1'; GRANT ALL ON backend_dev.* TO backend@'127.0.0.1'")
    echo "the development MySQL runs on 127.0.0.1:$MYSQL_PORT, its files in $D" ;;
stop)
    if up; then admin shutdown; echo "the development MySQL stopped"
    else echo "the development MySQL is not running"; fi ;;
restart)
    # stop returns once the server has ended: mysqladmin waits for its pid file to go.
    "$0" stop && exec "$0" start ;;
status)
    if up; then echo "the development MySQL runs on 127.0.0.1:$(cd "$D/data" && "$M/mysql" --no-defaults \
                     -S mysqld.sock -u root -N -e 'SELECT @@port'), its files in $D"
    else echo "the development MySQL is not running"; exit 1; fi ;;
*)
    echo "usage: $0 start | stop | restart | status" >&2; exit 2 ;;
esac
