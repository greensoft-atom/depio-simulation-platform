#!/bin/bash
# Builds a release directory: one lib/ per process type, the Java runtime they run on, the j-redis
# store, MySQL and nginx, the units, their configuration and the example settings
# (docs/detailed-design/09 §4, docs/operations/01-deploy.md §7).
#
#   scripts/make-release.sh            -> target/release/backend-<version>/ and a .tar.gz
#
# It builds offline (-o), from what the local repository already holds; MVN adds settings, such
# as the offline bundle's. A local repository filled from more than one source (the bundle and
# central) hides artifacts from an offline build that names the other one; for that, add
#   MVN="mvn -Daether.enhancedLocalRepository.trackingFilename=_none"
# The runtime is made from the committed JDK, vendor/jdk-21 (JDK= for another JDK 21 with jmods);
# MySQL and nginx are vendor/'s, as committed (D-77).
#
# One lib/ per process, not one shared: handoff's module graph is what keeps the MySQL driver
# off an arena's classpath, and a shared directory would put it back.
set -euo pipefail
cd "$(dirname "$0")/.."
MVN=${MVN:-mvn}
VENDOR=$(cd ../vendor && pwd)
JDK=${JDK:-$VENDOR/jdk-21}
JREDIS_SRC=$(cd ../j-redis-service && pwd)
# The project's own <version>, the first one in the root pom (the parent block has none).
VERSION=$(sed -n 's:^  <version>\(.*\)</version>$:\1:p' pom.xml | head -1)
[ -n "$VERSION" ] || { echo "could not read the version from pom.xml" >&2; exit 1; }
OUT=target/release/backend-$VERSION

# The store first: the backend builds against its client, and the release carries its server.
(cd "$JREDIS_SRC" && $MVN -q -o -DskipTests install)
# Clean first: a file deleted from the sources, such as a migration replaced by the baseline (D-75),
# would otherwise stay in target/classes and ship. Clean empties target/, the release's own folder too.
$MVN -q -o -DskipTests clean install
mkdir -p "$OUT"
for process in arena platform worker gateway; do
    mkdir -p "$OUT/lib/$process"
    $MVN -q -o -pl "$process" dependency:copy-dependencies \
        -DincludeScope=runtime -DoutputDirectory="$PWD/$OUT/lib/$process"
    cp "$process/target/$process-$VERSION.jar" "$OUT/lib/$process/"
done

# j-redis: its launchers (they run on $JAVA_HOME, the scripts point it at runtime/), its jars and
# its configuration's template.
mkdir -p "$OUT/jredis/bin" "$OUT/jredis/lib" "$OUT/jredis/conf"
for m in server cli tools; do
    cp "$JREDIS_SRC"/j-redis-$m/target/j-redis-$m-*-all.jar "$OUT/jredis/lib/j-redis-$m.jar"
    cp "$JREDIS_SRC/dist/bin/j-redis-$m" "$OUT/jredis/bin/"
done
cp "$JREDIS_SRC/dist/conf/j-redis.conf" "$OUT/jredis/conf/"

# The runtime (09 §4.1): java.se, every java.* module, and the jdk.* ones the processes use:
# jdk.httpserver (the platform's API and every metrics endpoint), jdk.unsupported (Netty, JCTools),
# jdk.jfr, jdk.net (socket options), jdk.crypto.ec (the curves TLS negotiates, which no static
# reading finds), and jdk.jcmd, jdk.management and jdk.management.agent, to take a heap dump or a
# recording from a running process. Named, not computed: the libraries are explicit modules whose
# optional requirements are absent (Netty's codec-marshalling's JBoss Marshalling), so jdeps cannot
# resolve them; it reads our own jars, and anything they use outside this list stops the release.
modules=java.se,jdk.httpserver,jdk.unsupported,jdk.jfr,jdk.net,jdk.crypto.ec,jdk.jcmd,jdk.management,jdk.management.agent
for dir in "$OUT"/lib/* "$OUT/jredis/lib"; do
    own=$(ls "$dir"/*.jar | grep -E "/(arena|platform|worker|gateway|common|handoff|persistence|protocol|sim)-$VERSION\.jar$|/j-redis-" || true)
    used=$("$JDK/bin/jdeps" -q --multi-release 21 --ignore-missing-deps --print-module-deps \
               --class-path "$dir/*" $own)
    for m in ${used//,/ }; do
        case "$m" in java.*) ;; *) [[ ",$modules," == *",$m,"* ]] \
            || { echo "the runtime lacks $m, which $dir uses: add it to modules" >&2; exit 1; } ;; esac
    done
done
# --strip-java-debug-attributes, not --strip-debug: that one also strips native symbols with objcopy,
# which a minimal RHEL 9 lacks (binutils), and the JDK's native libraries come stripped already.
"$JDK/bin/jlink" --module-path "$JDK/jmods" --add-modules "$modules" \
    --strip-java-debug-attributes --no-man-pages --no-header-files --compress zip-6 --output "$OUT/runtime"

# MySQL and nginx as committed: binaries for RHEL 9's glibc (09 §1).
cp -a "$VENDOR/mysql-8.4" "$OUT/mysql"
cp -a "$VENDOR/nginx-1.30" "$OUT/nginx"

# Their configuration beside them, under names of its own.
cp -r deploy/systemd deploy/env "$OUT/"
cp -r deploy/mysql "$OUT/mysql-conf"
cp -r deploy/nginx "$OUT/nginx-conf"
# The operational scripts: the backup units and certbot's deploy hook run them from
# /opt/backend/scripts; they find mysql/, jredis/ and runtime/ beside them.
mkdir -p "$OUT/scripts"
cp scripts/backup-mysql.sh scripts/backup-store.sh scripts/binlog-stream.sh \
    scripts/restore-drill.sh scripts/store-restore-drill.sh scripts/install-certificate.sh \
    scripts/promote-store.sh scripts/promote-mysql.sh scripts/record-backup.sh scripts/restore-proof.sh \
    scripts/backup-offsite.sh scripts/fetch-offsite.sh "$OUT/scripts/"
git rev-parse HEAD > "$OUT/COMMIT" 2>/dev/null || true
{
    cat "$VENDOR/VERSIONS.md"
    echo
    echo "## This release"
    echo
    echo "- backend $VERSION, commit $(cat "$OUT/COMMIT" 2>/dev/null || echo unknown)"
    echo "- j-redis $(basename "$JREDIS_SRC"/j-redis-server/target/j-redis-server-*-all.jar | sed 's/j-redis-server-\(.*\)-all.jar/\1/')"
    echo "- runtime: $("$OUT/runtime/bin/java" -version 2>&1 | head -1), modules $modules"
} > "$OUT/VERSIONS.md"
tar -C target/release -czf "target/release/backend-$VERSION.tar.gz" "backend-$VERSION"
echo "release: $OUT"
for process in arena platform worker gateway; do
    printf '  lib/%-9s %3d jars\n' "$process" "$(ls "$OUT/lib/$process" | wc -l)"
done
du -sh "$OUT/runtime" "$OUT/mysql" "$OUT/nginx" "$OUT/jredis" | sed 's/^/  /'
