j-redis-service - a Redis-compatible in-memory data store for Java 21

  bin/         launchers: j-redis-server, j-redis-cli, j-redis-tools, j-redis-examples (.cmd on Windows)
  lib/         self-contained jars (only a Java 21 or newer runtime is needed)
  conf/        j-redis.conf (production, Linux), j-redis-dev.conf (workstation)
  systemd/     unit file for Linux servers

Quick start (from this directory):
  Linux:    bin/j-redis-server conf/j-redis-dev.conf
  Windows:  bin\j-redis-server.cmd conf\j-redis-dev.conf
  Then:     bin/j-redis-cli ping        -> PONG
  Examples: bin/j-redis-examples --list  (run them with no arguments; add -p 6379 to use the server)

Exit codes of the server: 0 clean stop, 1 configuration/start-up error, 2 data directory in use,
3 data could not be loaded (run: bin/j-redis-tools check-aof <dir>), 4 fail-stop (restart it).

Full documentation: docs/ in the source tree (11-operations-and-security.md for operators).
