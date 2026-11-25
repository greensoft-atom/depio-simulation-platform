#!/bin/bash
# client-simple-no-tls's check (D-114): client-simple's own, over this tree, its TLS checks left out
# (../client-simple/check.sh, NO_TLS=1). Compiled by Unity 2019.4.28f1's compiler at every level, offline on its Mono,
# and live against a backend run plain.
#
#   client-simple-no-tls/check.sh
#   client-simple-no-tls/check.sh live <http://api> <ws://lobby>
export NO_TLS=1
exec "$(cd "$(dirname "$0")" && pwd)/../client-simple/check.sh" "$@"
