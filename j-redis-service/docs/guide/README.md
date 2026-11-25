# j-redis-service guides

These guides give practical, step-by-step instructions for j-redis. They are for
engineers who set up a machine for j-redis, build it, run it, call it from Java,
change its code or upgrade it. Each guide says *how to* do one job. The design
documents one level up ([../](../)) explain *why* j-redis works the way it does.

| # | Guide | Read it when you want to… |
|---|---|---|
| 1 | [Set up a development machine](01-setup.md) | install Java 21 and Maven on Windows or Linux, and set up an IDE |
| 2 | [Build with the java21-offline repository](02-offline-repository.md) | build without internet access, with the `java21-offline` bundle as the source of every library and plugin |
| 3 | [Build, test and package](03-build-and-test.md) | compile, run the tests, check the Java 21 guarantee, build the distribution, fix build problems |
| 4 | [Run and operate the server](04-run-and-operate.md) | start, configure, secure, deploy on Linux, back up, monitor; use the CLI and the tools; run a replica and promote it |
| 5 | [Use it from Java: client guide](05-client-guide.md) | connect from an application: every data type, transactions, locks, queues, pub/sub, errors, testing, with runnable examples |
| 6 | [Develop the service itself](06-developer-guide.md) | find your way around the code, add a command, write tests, debug |
| 7 | [Upgrade](07-upgrade-guide.md) | upgrade an installation, bump the version, update a dependency or the JDK |

**The shortest path from nothing to a running server:**

1. Install JDK 21 and Maven (guide 1).
2. Connect Maven to the offline repository with method 1 (guide 2).
3. Build and test: `mvn clean install`.
4. Assemble the distribution from the jars just built: `scripts/make-dist.sh --skip-build`.
5. Start the server from the distribution's directory, because the
   configuration's path and its `dir data` are relative to the current
   directory: `cd target/dist/j-redis-2.2.1`, then
   `bin/j-redis-server conf/j-redis-dev.conf`.

**Inside the backend's repository**, steps 1 and 2 are already done: source
`dev-env.sh` from the repository's root, then run `./dev-jredis.sh build` and
`./dev-jredis.sh start`. That server listens on 127.0.0.1:6380
([build and run](../../../docs/development/03-build-and-run.md)).

Are Redis-style data stores new to you? Read
[../00-redis-primer.md](../00-redis-primer.md) first. It explains the ideas in
about 20 minutes.
