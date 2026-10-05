#!/bin/sh
# Sets the project version in every POM (the only place it is defined).
# Usage:  scripts/set-version.sh 1.1.0
set -eu
[ $# -eq 1 ] || { echo "usage: $0 <new-version>" >&2; exit 2; }
NEW=$1
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OLD=$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$ROOT/pom.xml" | head -1)
# the parent POM: its own version is the first <version> element
sed -i.bak "0,/<version>$OLD<\/version>/s//<version>$NEW<\/version>/" "$ROOT/pom.xml"
# every module: the <parent> reference
for pom in "$ROOT"/j-redis-*/pom.xml; do
    sed -i.bak "s#<artifactId>j-redis-parent</artifactId><version>$OLD</version>#<artifactId>j-redis-parent</artifactId><version>$NEW</version>#" "$pom"
done
find "$ROOT" -maxdepth 2 -name pom.xml.bak -delete
echo "version $OLD -> $NEW"
grep -n "<version>$NEW</version>" "$ROOT/pom.xml" "$ROOT"/j-redis-*/pom.xml
