#!/usr/bin/env bash
# Builds everything from this repository alone, with no network (docs/detailed-design/09 §3, D-77):
# the committed JDK and Maven (vendor/), java21-offline as Maven's only repository, and a local
# repository of its own, so nothing comes from ~/.m2. Then j-redis-service, the backend, and the
# release (backend/target/release/). Needs bash, coreutils, findutils, sed, tar and gzip; no Java,
# no Maven, no network.
#
#   ./build-offline.sh                 with every test (the backend's need MySQL: BACKEND_DB_URL etc.,
#                                      as backend/README.md says)
#   SKIP_TESTS=1 ./build-offline.sh    compiled, packaged and released, no test run
#   WORK=<dir>                         where its settings and local repository go (default under TMPDIR)
set -euo pipefail

B=$(cd "$(dirname "$0")" && pwd)
export JAVA_HOME="$B/vendor/jdk-21"
export PATH="$JAVA_HOME/bin:$B/vendor/maven-3.9/bin:$PATH"
WORK=${WORK:-${TMPDIR:-/tmp}/backend-build-offline}
mkdir -p "$WORK"

# The bundle's settings, pointed at the bundle where it is: a mirror of everything, and never -o,
# which refuses file:// repositories too (java21-offline/README.md).
sed "s#file:///opt/java21-offline/repository#file://$B/java21-offline/repository#g" \
    "$B/java21-offline/settings-offline.xml" > "$WORK/settings.xml"
MVN="mvn -B -q -s $WORK/settings.xml -Dmaven.repo.local=$WORK/m2"
TESTS=$([ "${SKIP_TESTS:-0}" = 1 ] && echo "-DskipTests" || true)

echo "--- j-redis-service"
(cd "$B/j-redis-service" && $MVN clean install $TESTS)
echo "--- backend"
(cd "$B/backend" && $MVN clean install $TESTS)
# The release builds offline (-o) from the local repository: what it runs that the build above does not
# (the clean plugin there, the dependency plugin here) is fetched from the bundle first.
(cd "$B/backend" && $MVN -pl common dependency:copy-dependencies -DoutputDirectory="$WORK/probe" > /dev/null)
echo "--- release"
(cd "$B/backend" && MVN="$MVN" scripts/make-release.sh)
