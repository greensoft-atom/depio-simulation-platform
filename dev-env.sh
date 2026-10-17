# Sourced, not run: the environment that builds, tests and runs the backend from this checkout
# alone (decision D-80, docs/development/03-build-and-run.md). The committed JDK and Maven
# (vendor/), the bundle as Maven's only repository (java21-offline/), and everything they write
# in work/ beside this file (WORK=<dir> for another place):
#
#   work/settings.xml   Maven's settings: the bundle as the mirror of every repository, work/m2 local
#   work/m2/            Maven's local repository
#   work/tmp/           TMPDIR, and java.io.tmpdir for Maven and the tests' JVMs
#   work/mysql/         the development MySQL (dev-mysql.sh), 127.0.0.1:3314 (MYSQL_PORT)
#   work/run/           a run by hand (run-local.sh)
#
# The machine's own Java, Maven and MySQL are neither used nor changed: JAVA_HOME and PATH point
# into vendor/, Maven skips /etc/mavenrc and ~/.mavenrc, and the tests' JDBC_URL is the
# development MySQL unless already set.
#
#   . ./dev-env.sh      then java, mvn and the tests in this shell, offline

B=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
WORK=${WORK:-$B/work}
# MAVEN_ARGS is split at spaces, and the paths go into XML and sed unescaped.
case "$B$WORK" in
    *[!A-Za-z0-9._/+@-]*)
        echo "dev-env.sh: $B or $WORK holds a character the build cannot carry (a space, for one):" \
             "use a path of letters, digits and . _ - + @ only, or a link to this one" >&2
        return 1 ;;
esac
for f in vendor/jdk-21/bin/java vendor/maven-3.9/bin/mvn vendor/mysql-8.4/bin/mysqld; do
    [ -x "$B/$f" ] || { echo "dev-env.sh: $f is not executable: the copy lost the files' modes;" \
                             "copy the repository with git clone, or tar, not file by file" >&2; return 1; }
done

export JAVA_HOME=$B/vendor/jdk-21
export PATH=$JAVA_HOME/bin:$B/vendor/maven-3.9/bin:$PATH
export MAVEN_SKIP_RC=1
mkdir -p "$WORK/m2" "$WORK/tmp"
sed -e "s|file:///opt/java21-offline/repository|file://$B/java21-offline/repository|g" \
    -e "s|<mirrors>|<localRepository>$WORK/m2</localRepository>\n  <mirrors>|" \
    "$B/java21-offline/settings-offline.xml" > "$WORK/settings.xml"
export MAVEN_ARGS="-s $WORK/settings.xml -DargLine=-Djava.io.tmpdir=$WORK/tmp"
export MAVEN_OPTS="-Djava.io.tmpdir=$WORK/tmp"
export TMPDIR=$WORK/tmp
export MYSQL_PORT=${MYSQL_PORT:-3314}
DEV_JDBC_URL="jdbc:mysql://127.0.0.1:$MYSQL_PORT/backend_test?useSSL=false&allowPublicKeyRetrieval=true"
export JDBC_URL=${JDBC_URL:-$DEV_JDBC_URL}
