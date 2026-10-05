# common

Small utilities every process shares, kept free of dependencies on purpose:
- metrics in the Prometheus text format, and the loopback HTTP server that serves them;
- secrets read from the environment, never the command line;
- positional arguments that refuse a bad value (exit 2) rather than fail (exit 1);
- the default logging and its flush at shutdown;
- three helpers for the tick loop.

Its only runtime dependencies are HdrHistogram and slf4j. logback is optional here: `Logs.flush`
compiles against it, and each process brings its own.

## Layout

`com.backend.common`:

| Class | What it does | Used by |
|---|---|---|
| `Metrics` | A process's metrics: counters, gauges, labelled counters, labelled histograms and labelled gauges, each a supplier read at scrape time; `jvm()`; `render()` in the text format 0.0.4 | arena, gateway, platform, worker, handoff |
| `MetricsServer` | Serves `render()` at `GET /metrics` when `BACKEND_METRICS_ADDR` names an address | arena, gateway, platform, worker |
| `Secrets` | `read(env, NAME)`: `NAME_FILE` (a file holding it) wins over `NAME` (the value); the value is never printed | handoff, persistence, platform, arena |
| `Arguments` | `integer` and `decimal` at a position, a default when absent, `RefusedConfiguration` when present and not a number | arena, gateway, platform, worker |
| `RefusedConfiguration` | The process cannot run with these settings. Every main exits 2 for it, and the units list 2 in `RestartPreventExitStatus` | every main, handoff, persistence |
| `Logs` | `flush()`: writes out what the log queue still holds and stops logging; the last call of a shutdown, and before `System.exit` | arena, gateway, platform, worker |
| `IntList` | A growable `int` array without boxing, its array exposed for the tick loop | sim, arena |
| `Xorshift` | xorshift128+, seeded through SplitMix64; one per room, never shared between threads | sim, tools |
| `PhaseTimer` | Per-phase nanosecond histograms for a tick loop, with a p99 against a budget | sim, arena, tools |

Resource: `logback.xml`, the default logging of every process (below).

## Configuration

| Name | Default | Meaning |
|---|---|---|
| `BACKEND_METRICS_ADDR` | unset: no server, no port taken | `host:port` to serve `GET /metrics` on, loopback in every example (9101 platform, 9102 and 9112 workers, 9103 gateway, 9110 onwards arenas). Without a `:`, with a port that is not a number, or one that cannot be bound: `RefusedConfiguration`, exit 2 |
| `<NAME>_FILE` / `<NAME>` | — | The convention `Secrets` reads, for each secret a process names: `BACKEND_STORE_PASSWORD`, `BACKEND_EVENTS_STORE_PASSWORD`, `BACKEND_DB_PASSWORD`, `BACKEND_ARENA_TLS_PASSWORD` and `BACKEND_ADMIN_TOKEN`. The file wins. One trailing line ending is removed from it (`\n`, then `\r`). A file named but unreadable or empty is exit 2, never a fallback |
| `logback.configurationFile` (system property) | the bundled `logback.xml` | An operator's logging, e.g. `-Dlogback.configurationFile=/etc/backend/logback.xml`, without a rebuild |

**The bundled logging.**
- INFO and above go to standard output, which systemd hands to the journal.
- Lines go through an `AsyncAppender`: a queue of 8 192, written by a thread of its own, and
  `neverBlock`. A stuck journal therefore never stalls a room thread or an event loop.
- When less than a fifth of the queue is left, INFO lines are dropped first. When it is full,
  everything is dropped rather than waited for.
- `Logs.flush()` writes out what is queued, waiting at most a second.

## Metrics

**The format.**
- A family per name, sorted, each with `# HELP` and `# TYPE`.
- Label values have `\`, `"` and newlines escaped.
- A supplier that throws costs its own family, not the scrape.
- A supplier runs on the scrape thread, so it reads only what is safe from another thread: an
  atomic, a volatile, a concurrent collection.

**Histograms** (`LabeledHistogram`) count into fixed upper bounds.
- Each label value has cumulative `_bucket{le="…"}` series, then `le="+Inf"`, `_sum` and `_count`.
- An observation above the last finite bound is counted only in `+Inf`, `_sum` and `_count`.
- A scrape during an observation may see its count before its bucket.

**Every process** that calls `jvm()` reports:

| Metric | Type | Meaning |
|---|---|---|
| `backend_jvm_heap_used_bytes` | gauge | Heap in use |
| `backend_jvm_threads` | gauge | Live threads |
| `backend_jvm_gc_seconds_total` | counter | The sum of every garbage collector's reported collection time, in seconds |
| `backend_process_uptime_seconds` | gauge | Seconds since the JVM started |

**`MetricsServer`** answers only `GET /metrics`; any other path or method gets a 404. It uses one
daemon thread, so it never keeps a process alive. The process-specific metrics are listed in each
module's README and in [operations/01](../../docs/operations/01-deploy.md#7-installing-a-machine).

## Build and test

From `backend/`:

```bash
export JAVA_HOME=/opt/jdk21 PATH=/opt/jdk21/bin:$PATH
/opt/maven/bin/mvn -o -pl common install
/opt/maven/bin/mvn -o -pl common test -Dtest=MetricsTest
```

| Class | Tests | What it covers |
|---|---|---|
| `MetricsTest` | 4 | The text format (help, type, sorting, escaping); a histogram's cumulative buckets, sum and count; a throwing supplier; the server serving `/metrics` when configured and not at all when not |
| `LoggingTest` | 3 | A journal that stops reading does not stop the thread that logs; stopping the logging writes out the queue; `Logs.flush` before a halt |
| `ArgumentsTest` | 3 | A missing argument takes its default; an empty or malformed one refuses to start, naming it |
| `SecretsTest` | 2 | The file wins, loses one line ending and is never printed; an unreadable or empty file refuses the configuration |

## Operational notes

- **Exit codes.** Every main exits 2 for `RefusedConfiguration`, and 1 for any other failure at
  start. Restarting cannot fix a setting, so the units do not restart on 2, and the one log line
  that says what to fix is not buried.
- **Secrets on the command line.** Do not pass them there: anyone on the machine can read a process's
  arguments. `LoadCredential=` in the unit, with `NAME_FILE=%d/<name>`, is the arrangement
  ([operations/01 §2](../../docs/operations/01-deploy.md#secrets)).

## Design documents

- [07 — Threading and performance](../../docs/detailed-design/07-threading-and-performance.md):
  `IntList` (§2) and `PhaseTimer` (§4).
- [Operations: deploy](../../docs/operations/01-deploy.md#2-conventions): secrets, exit codes and
  metrics.
