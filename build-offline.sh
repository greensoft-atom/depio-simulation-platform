#!/usr/bin/env bash
# Builds everything from this repository alone, with no network (docs/detailed-design/09 §3, D-77,
# D-80): the committed JDK and Maven (vendor/), java21-offline as Maven's only repository, and a
# local repository of its own, in work/ like everything it writes (dev-env.sh). Then j-redis-service
# (dev-jredis.sh build), the backend with its tests, and the release, backend/target/release/ (dev-backend.sh
# build). The tests' MySQL is the development one (dev-mysql.sh), started for them and stopped after, unless
# JDBC_URL names another.
# Needs bash, coreutils, findutils, sed, tar and gzip; no Java, no Maven, no MySQL, no network.
#
#   ./build-offline.sh                 with every test, about an hour
#   SKIP_TESTS=1 ./build-offline.sh    compiled, packaged and released, no test run
#   WORK=<dir>                         where it writes, instead of work/ (dev-env.sh)
set -euo pipefail

B=$(cd "$(dirname "$0")" && pwd)
. "$B/dev-env.sh"

# Each service's own build, in order (D-92): the backend needs j-redis's client, and its release j-redis's server.
"$B/dev-jredis.sh" build
"$B/dev-backend.sh" build
