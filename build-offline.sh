#!/usr/bin/env bash
# Builds everything from this repository alone, with no network (docs/detailed-design/09 §3, D-77,
# D-80): the committed JDK and Maven (vendor/), java21-offline as Maven's only repository, and a
# local repository of its own, in work/ like everything it writes (dev-env.sh). Then j-redis-service,
# the backend with its tests, and the release (backend/target/release/). The tests' MySQL is the
# development one (dev-mysql.sh), started for them and stopped after, unless JDBC_URL names another.
# Needs bash, coreutils, findutils, sed, tar and gzip; no Java, no Maven, no MySQL, no network.
#
#   ./build-offline.sh                 with every test, about an hour
#   SKIP_TESTS=1 ./build-offline.sh    compiled, packaged and released, no test run
#   WORK=<dir>                         where it writes, instead of work/ (dev-env.sh)
set -euo pipefail

B=$(cd "$(dirname "$0")" && pwd)
. "$B/dev-env.sh"
MVN="mvn -B -q"       # dev-env.sh's MAVEN_ARGS name the settings
TESTS=$([ "${SKIP_TESTS:-0}" = 1 ] && echo "-DskipTests" || true)

if [ -z "$TESTS" ] && [ "$JDBC_URL" = "$DEV_JDBC_URL" ] && ! "$B/dev-mysql.sh" status > /dev/null; then
    "$B/dev-mysql.sh" start
    trap '"$B/dev-mysql.sh" stop' EXIT
fi

echo "--- j-redis-service"
(cd "$B/j-redis-service" && $MVN clean install $TESTS)
echo "--- backend"
(cd "$B/backend" && $MVN clean install $TESTS)
# The release builds offline (-o) from the local repository: what it runs that the build above does not
# (the clean plugin there, the dependency plugin here) is fetched from the bundle first.
(cd "$B/backend" && $MVN -pl common dependency:copy-dependencies -DoutputDirectory="$WORK/probe" > /dev/null)
echo "--- release"
(cd "$B/backend" && MVN="$MVN" scripts/make-release.sh)
echo "--- done: $(ls -d "$B"/backend/target/release/backend-*/ | head -1)"
