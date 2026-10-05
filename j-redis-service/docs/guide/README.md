# j-redis-service guides

Practical, step-by-step guides. The design documents one level up
([../](../)) explain *why* things work the way they do; these explain *how to*
use and work on the service.

| # | Guide | Read it when you want to… |
|---|---|---|
| 1 | [Set up a development machine](01-setup.md) | install Java 21 and Maven on Windows or Linux, and set up an IDE |
| 2 | [Build with the java21-offline repository](02-offline-repository.md) | build without internet access, using the `java21-offline` bundle as the source of every library and plugin |
| 3 | [Build, test and package](03-build-and-test.md) | compile, run the tests, check the Java 21 guarantee, build the distribution, fix build problems |
| 4 | [Run and operate the server](04-run-and-operate.md) | start, configure, secure, deploy on Linux, back up, monitor; use the CLI and the tools |
| 5 | [Use it from Java: client guide](05-client-guide.md) | connect from an application: every data type, transactions, locks, queues, pub/sub, errors, testing, with runnable examples |
| 6 | [Develop the service itself](06-developer-guide.md) | find your way around the code, add a command, write tests, debug |
| 7 | [Upgrade](07-upgrade-guide.md) | upgrade an installation, bump the version, update a dependency or the JDK |

**The shortest path from nothing to a running server:** guide 1 (install) →
guide 2, method 1 (offline repository) → `mvn clean install` →
`scripts/make-dist.sh` → `target/dist/j-redis-2.1.0/bin/j-redis-server conf/j-redis-dev.conf`.

New to Redis-style data stores? Read [../00-redis-primer.md](../00-redis-primer.md)
first. It explains the ideas in about 20 minutes.
