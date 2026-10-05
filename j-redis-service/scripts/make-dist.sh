#!/bin/sh
# Builds the project offline and assembles the distribution:
#   target/dist/j-redis-<version>/   and   target/dist/j-redis-<version>.tar.gz / .zip
#
# Usage:  scripts/make-dist.sh [--skip-build]
# Env:    MAVEN_REPO  local repository to build from (e.g. the java21-offline bundle's repository)
#         MVN         Maven command (default: mvn)
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
MVN=${MVN:-mvn}
VERSION=$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$ROOT/pom.xml" | head -1)

if [ "${1:-}" != "--skip-build" ]; then
    (cd "$ROOT" && $MVN -B -o ${MAVEN_REPO:+-Dmaven.repo.local=$MAVEN_REPO} clean install)
fi

DIST="$ROOT/target/dist"
OUT="$DIST/j-redis-$VERSION"
rm -rf "$OUT" "$DIST/j-redis-$VERSION.tar.gz" "$DIST/j-redis-$VERSION.zip"
mkdir -p "$OUT/lib"
cp -R "$ROOT/dist/." "$OUT/"
for m in server cli tools examples; do
    cp "$ROOT/j-redis-$m/target/j-redis-$m-$VERSION-all.jar" "$OUT/lib/j-redis-$m.jar"
done
chmod 755 "$OUT/bin/j-redis-server" "$OUT/bin/j-redis-cli" "$OUT/bin/j-redis-tools" "$OUT/bin/j-redis-examples"

tar -C "$DIST" -czf "$DIST/j-redis-$VERSION.tar.gz" "j-redis-$VERSION"
if command -v jar >/dev/null 2>&1; then
    (cd "$DIST" && jar -cMf "j-redis-$VERSION.zip" "j-redis-$VERSION")
fi
echo "distribution: $OUT"
ls -l "$DIST"
