# common

`common` holds the small utilities that every process of the backend shares. The four processes (arena,
gateway, platform and worker, see the [glossary](../../docs/glossary.md#1-processes-and-places)) and the
modules `handoff`, `persistence`, `sim` and `tools` depend on it. It has few dependencies on purpose. This page
lists its classes, its settings, the metrics every process reports, its tests and the rules it sets for every
process: exit codes and secrets.

What it gives:
- metrics in the Prometheus text format, and a small HTTP server on loopback that serves them;
- secrets read from the environment, never from the command line;
- positional arguments that refuse a bad value with exit 2, instead of failing with exit 1;
- the default logging configuration, and the call that writes out the last log lines at shutdown;
- three helpers for the tick loop (a tick is one simulation step, 25 a second).

At runtime it needs only HdrHistogram and slf4j. logback is an optional dependency: `Logs.flush` needs it to
compile, and each process brings its own logback at runtime. `common` does not pass logback on, so a library
module never chooses a logging backend for the modules that use it.

## Layout

All classes are in the package `com.backend.common`:

| Class | What it does | Used by |
|---|---|---|
| `Metrics` | One process's metrics: counters, gauges, labelled counters, labelled histograms and labelled gauges. A counter or a gauge is a supplier that the scrape reads at that moment. A labelled counter or histogram is an object that the process updates. `jvm()` adds the JVM's own metrics. `render()` writes them all in the text format 0.0.4 | arena, gateway, platform, worker, handoff |
| `MetricsText` | Reads the text format back into samples: a name, its labels and a value. The platform's watch uses it to read this machine's processes (04 §12). A line it cannot read is skipped; the other lines are still read | platform |
| `MetricsServer` | Serves `render()` at `GET /metrics`, but only when `BACKEND_METRICS_ADDR` names an address | arena, gateway, platform, worker |
| `Secrets` | `read(env, NAME)` reads a secret. `NAME_FILE` (the path of a file that holds the secret) wins over `NAME` (the secret itself). The value is never printed: printing a `Secret` prints only where it came from | handoff, persistence, platform, arena |
| `Arguments` | Reads a main's positional arguments. `integer` and `decimal` read a number at a position; `text` reads a string, trimmed. Each returns a default when the argument is absent. It throws `RefusedConfiguration` when the argument is present but not a number, or, for `text`, present but empty | arena, gateway, platform, worker |
| `RefusedConfiguration` | Means "the process cannot run with these settings". Every main exits 2 for it, and the units list 2 in `RestartPreventExitStatus` | every main, handoff, persistence |
| `Logs` | `flush()` writes out what the log queue still holds, then stops logging. Call it last in a shutdown, and before `System.exit` | arena, gateway, platform, worker |
| `TrustedProxies` | `fromEnvironment(env)` reads `BACKEND_TRUSTED_PROXIES`: IP literals only, else `RefusedConfiguration`. `trusts(peer)` is true for loopback and for each address named. The platform and the gateway believe the `X-Forwarded-For` header only from a trusted peer (D-82) | gateway, platform |
| `IntList` | A growable `int` array without boxing. It exposes its backing array, so the tick loop can index it directly | sim, arena |
| `Xorshift` | The random number generator xorshift128+, seeded through SplitMix64. Each room has its own; it is never shared between threads | sim, tools |
| `PhaseTimer` | Nanosecond histograms, one for each phase of a tick loop, and a report of the total's p99 against a budget | sim, arena, tools |

Resource: `logback.xml`, the default logging of every process (see [The bundled logging](#the-bundled-logging)).

## Configuration

These settings are read by `common`'s classes, in every process that uses them:

| Name | Default | Meaning |
|---|---|---|
| `BACKEND_METRICS_ADDR` | unset or blank: no server, no port taken | `host:port` on which to serve `GET /metrics`. Every example uses loopback: 9101 the platform, 9102 and 9112 the workers, 9103 the gateway, 9110 onwards the arenas. The process refuses to start with exit 2 (`RefusedConfiguration`) when the value has no `:` or starts with one, when the port is not a number, or when the address cannot be bound (the port is in use, or the address is not this machine's). A port number outside 0 to 65535 is not caught this way: the process fails at start with exit 1 |
| `<NAME>_FILE` / `<NAME>` | none | The convention that `Secrets` reads, for each secret a process names: `BACKEND_STORE_PASSWORD`, `BACKEND_EVENTS_STORE_PASSWORD`, `BACKEND_DB_PASSWORD`, `BACKEND_ARENA_TLS_PASSWORD`, `BACKEND_ADMIN_TOKEN` and `BACKEND_ALERT_WEBHOOK_URL`. When both are set, the file wins. One trailing line ending is removed from the file's text (`\n`, then `\r`). A file that is named but cannot be read, or is empty, stops the start with exit 2; the process never falls back to `<NAME>` |
| `logback.configurationFile` (a Java system property) | the bundled `logback.xml` | An operator's own logging, for example `-Dlogback.configurationFile=/etc/backend/logback.xml`. No rebuild is needed |

### The bundled logging

The bundled `logback.xml` makes sure that a slow journal never slows the process down:
- INFO and higher go to standard output. systemd passes standard output to the journal.
- Lines go through an `AsyncAppender`: a queue of 8 192 lines, written out by a thread of its own, with
  `neverBlock` set. So a stuck journal never stalls a room thread or a Netty event loop.
- When less than a fifth of the queue is free, INFO lines are dropped first. When the queue is full, every new
  line is dropped; the thread that logs never waits.
- `Logs.flush()` writes out what is still queued. It waits at most one second.

Without this file, logback would log at DEBUG. In a 20-second run the worker and the gateway then wrote about 150
DEBUG lines, mostly the store client's reconnections, which buried the lines that matter.

## Metrics

**The format.** `render()` writes the Prometheus text format, version 0.0.4:
- one family per metric name, sorted by name, each with its `# HELP` and `# TYPE` lines;
- label values with `\`, `"` and newlines escaped;
- a supplier that throws loses only its own family; the rest of the scrape is still written.

A supplier runs on the scrape thread, not on the thread that owns the data. So it must read only what is safe to
read from another thread: an atomic, a volatile, or a concurrent collection.

**Histograms** (`LabeledHistogram`) count each observation into fixed upper bounds:
- Each label value has cumulative `_bucket{le="…"}` series, then `le="+Inf"`, `_sum` and `_count`.
- An observation above the last finite bound is counted only in `+Inf`, `_sum` and `_count`.
- A scrape that runs during an observation may see the new count before the new bucket. The format's readers
  accept this.

**Every process** that calls `jvm()` reports these four metrics:

| Metric | Type | Meaning |
|---|---|---|
| `backend_jvm_heap_used_bytes` | gauge | Heap in use |
| `backend_jvm_threads` | gauge | Live threads |
| `backend_jvm_gc_seconds_total` | counter | The sum of the collection time that every garbage collector reports, in seconds |
| `backend_process_uptime_seconds` | gauge | Seconds since the JVM started |

**`MetricsServer`** answers only `GET /metrics`, with `Content-Type: text/plain; version=0.0.4; charset=utf-8`.
Any other path or method gets a 404. It runs on one daemon thread, so it never keeps a process alive. Each
module's README lists the metrics of its own process, and so does
[operations/01](../../docs/operations/01-deploy.md#7-installing-a-machine).

## Build and test

Run these from `backend/`:

```bash
. ../dev-env.sh  # vendor/'s JDK and Maven, the bundle, work/ (D-80)
mvn -pl common install
mvn -pl common test -Dtest=MetricsTest
```

| Class | Tests | What it covers |
|---|---|---|
| `MetricsTextTest` | 5 | What `render()` writes is read back whole, including a histogram's buckets, sum and count; every value the format allows (NaN, the infinities, exponents, negatives, a timestamp after the value); label values that hold commas, braces, quotes and spaces; a line it cannot read is skipped and the lines around it are kept; empty text, or comments alone, read as no samples |
| `MetricsTest` | 4 | The text format (help, type, sorting, escaping); a histogram's cumulative buckets, sum and count for each label value; a supplier that throws; the server serves `/metrics` when an address is configured, and does not start when none is |
| `LoggingTest` | 3 | A journal that stops reading does not stop the thread that logs; stopping the logging writes out the queue; `Logs.flush` writes out the queue before a halt |
| `TrustedProxiesTest` | 3 | Unset, only loopback is trusted; the named addresses are trusted and no others; anything but IP literals is refused, and nothing is looked up in DNS |
| `ArgumentsTest` | 3 | A missing argument takes its default; an empty or malformed number refuses to start, and the message names the argument; `text` returns its default when absent and refuses an empty value |
| `SecretsTest` | 2 | The file wins over the variable, loses one line ending and is never printed; a file that cannot be read, or is empty, refuses the configuration |

## Operational notes

- **Exit codes.** Every main exits 2 for `RefusedConfiguration`, and 1 for any other failure at start.
  Restarting cannot fix a setting. So the units do not restart a process that exits 2
  (`RestartPreventExitStatus=2`), and the one log line that says what to fix is not buried under restarts.
- **Secrets on the command line.** Never pass a secret as an argument: anyone on the machine can read a
  process's arguments. The deployed arrangement is the variable itself, `NAME`, in the process's own settings
  file, `/etc/backend/<process>.env`, which only root can read (D-102,
  [operations/01 §2](../../docs/operations/01-deploy.md#secrets)). `NAME_FILE`, the path of a file that holds the
  secret, wins where both are set.

## Design documents

- [07 — Threading and performance](../../docs/detailed-design/07-threading-and-performance.md):
  `IntList` (§2) and `PhaseTimer` (§4).
- [Operations: deploy](../../docs/operations/01-deploy.md#2-conventions): secrets, exit codes and
  metrics.
