# 03 — Protocol

## 1. Why RESP2

We write both ends — server and client — so the wire format is an internal
choice. We use **RESP2**, the Redis protocol, because:

- it is a complete, proven specification we do not have to design or document;
- it is trivially simple (five types) and binary-safe;
- it is readable in a packet capture and usable by hand over `telnet` / `nc`;
- the AOF uses the same encoding, so one decoder serves both
  ([08](08-persistence.md));
- it keeps the door open to swapping implementations later.

Speaking RESP does not mean using any third-party software. Our own
`j-redis-client` and `j-redis-cli` are the only programs that talk to the server
([14](14-decision-log.md), D-1, D-14).

## 2. Types

Every frame starts with a one-byte type marker and ends with CRLF (`\r\n`).

| Marker | Type | Encoding | Example |
|---|---|---|---|
| `+` | Simple string | text without CR/LF | `+OK\r\n` |
| `-` | Error | `PREFIX message` | `-WRONGTYPE Operation against a key holding the wrong kind of value\r\n` |
| `:` | Integer | signed 64-bit decimal | `:1000\r\n` |
| `$` | Bulk string | length, CRLF, bytes, CRLF | `$5\r\nhello\r\n` |
| | Null bulk | length −1 | `$-1\r\n` |
| `*` | Array | count, CRLF, elements | `*2\r\n:1\r\n$1\r\na\r\n` |
| | Null array | count −1 | `*-1\r\n` |

Bulk strings are binary-safe: the length prefix, not a delimiter, marks the end.

## 3. Requests

### Multibulk (normal)

An array of bulk strings; element 0 is the command name, case-insensitive.

```
SET user:42:name Alice   →   *3\r\n$3\r\nSET\r\n$12\r\nuser:42:name\r\n$5\r\nAlice\r\n
```

### Inline (for humans)

A line of space-separated words, for `telnet` and manual debugging. Double and
single quotes group words; `\xNN`, `\n`, `\r`, `\t`, `\"` escapes work inside
double quotes. Recognised when the first byte is not `*`.

```
$ nc 127.0.0.1 6379
PING
+PONG
SET greeting "hello world"
+OK
```

### Pipelining

A client may send any number of requests without waiting. The server executes
them in order and replies in the same order ([02 §3](02-architecture.md#ordering-guarantee)).

## 4. The decoder

`RespDecoder` lives in `j-redis-common` and is used by the server (requests), the
client (replies), and the AOF loader. It extends Netty's `ByteToMessageDecoder`
and is a resumable state machine: a frame split across TCP packets is simply
continued on the next read, with no copying until a complete argument is
available.

```
state: TYPE → (multibulk) COUNT → [ BULK_LEN → BULK_BODY ]*count → emit argv
               (inline)   LINE → split → emit argv
```

- Integers in headers are parsed directly from bytes; no `String` is created.
- Each complete argument is copied into its own `byte[]` on the I/O thread.
- One read may emit many requests; each is enqueued separately.

### Limits

| Limit | Default | Config | On violation |
|---|---|---|---|
| Bulk string length | 64 MB | `proto-max-bulk-len` | protocol error |
| Arguments per request | 1,048,576 | fixed | protocol error |
| Inline line length | 64 KB | fixed | protocol error |
| Unparsed bytes per client | 128 MB | `client-query-buffer-limit` | protocol error |

Redis's bulk limit is 512 MB; ours is lower because our values are small and a
large limit only helps a buggy client exhaust the heap.

### Protocol errors

A protocol error is detected on the I/O thread, but earlier requests from the
same client may still be queued. To keep replies in order, the I/O thread
stops reading the socket and enqueues a `ProtocolError` event. `cmd` appends
`-ERR Protocol error: <reason>` after the client's earlier replies, flushes, and
closes the connection.

## 5. Replies by command family

| Reply | Used for |
|---|---|
| `+OK` | `SET`, `MSET`, `RENAME`, `FLUSHALL`… |
| `:n` | counts, booleans (`:1`/`:0`), `INCR` results, TTLs |
| `$…` / `$-1` | values; null when absent |
| `*…` | multi-value results; `*-1` for `BLPOP` timeout and aborted `EXEC` |
| `-PREFIX …` | errors ([§8](#8-error-conventions)) |

## 6. Pub/Sub frames

In subscriber mode the server pushes arrays at any time:

```
*3  $9 subscribe   $<channel>  :<subscription count>
*3  $7 message     $<channel>  $<payload>
*4  $8 pmessage    $<pattern>  $<channel>  $<payload>
*3  $11 unsubscribe $<channel> :<remaining count>
```

A RESP2 connection in subscriber mode accepts only `SUBSCRIBE`, `UNSUBSCRIBE`,
`PSUBSCRIBE`, `PUNSUBSCRIBE`, `PING`, `QUIT` and `RESET`. `PING` in subscriber
mode replies `*2 $4 pong $0 ""` as in Redis. This is why pub/sub uses a
dedicated client connection ([10](10-client-library.md)).

## 7. Number formatting

### Integers

Replies use plain decimal. **Parsing is strict**: an optional `-`, then digits,
no leading zeros (except `0` itself), no `+`, no whitespace, within signed
64-bit range. Java's `Long.parseLong` is more permissive (it accepts `+5` and
`007`), so we use our own parser. Failure →
`-ERR value is not an integer or out of range`.

### Doubles (sorted-set scores, `INCRBYFLOAT`)

**Parsing** accepts an optional sign, digits, optional fraction and exponent,
and `inf` / `+inf` / `-inf` (case-insensitive). It rejects `NaN`, whitespace,
hex, and Java-only suffixes. `Double.parseDouble` alone is not safe here:
it accepts `" 1.5d"`, `NaN` and `Infinity`, so input is validated first.
Failure → `-ERR value is not a valid float`.

**Formatting:**

| Value | Output |
|---|---|
| Integral and \|v\| < 2^53 | integer form: `1`, `-42`, `1000` |
| ±∞ | `inf`, `-inf` |
| Otherwise | Java's `Double.toString`, normalised to Redis style: `0.1`, `1.5`, `1e+20` |

The value always round-trips exactly. The text matches Redis for integers and
common decimals. `Double.toString` occasionally prints one more digit
than the shortest possible (fixed only in JDK 19); byte-identical output in
every case is a non-goal ([01 §6](01-requirements-and-scope.md#6-out-of-scope)).

**`INCRBYFLOAT` / `HINCRBYFLOAT`** are different, as in Redis. Redis adds in
`long double` and prints the result with 17 fractional digits, trailing zeros
removed, never in exponent form. j-redis adds the two decimal values exactly
(`BigDecimal`) and presents the result the same way. So `0.1 + 0.2` is `0.3`
(binary `double` arithmetic would give `0.30000000000000004`), and `1e20` is
`100000000000000000000`. The two can differ only in the 17th decimal of
unusual values.

## 8. Error conventions

The first word of an error is its **prefix**; clients branch on the prefix, not
the message.

| Prefix | Meaning |
|---|---|
| `ERR` | Generic: syntax, arity, invalid argument, unknown command |
| `WRONGTYPE` | Command used against a key of another type |
| `NOAUTH` | Authentication required |
| `WRONGPASS` | `AUTH` failed |
| `OOM` | Write refused because memory is over `maxmemory` |
| `MISCONF` | Write refused because persistence is failing |
| `EXECABORT` | Transaction discarded because a queued command was invalid |
| `NOPROTO` | `HELLO` asked for an unsupported protocol version |

## 9. `HELLO` and connection set-up

- `HELLO` or `HELLO 2 [AUTH user pass] [SETNAME name]` → server info as a flat
  array (`server`, `version`, `proto` 2, `id`, `mode` "standalone", `role`
  "master", `modules` empty).
- `HELLO 3` → `-NOPROTO unsupported protocol version`. Clients that try RESP3
  fall back to RESP2.
- `AUTH <password>` and `AUTH default <password>` are both accepted.

## 10. A complete conversation

```
C: *3\r\n$3\r\nSET\r\n$7\r\nsess:ab\r\n$2\r\n42\r\n        SET sess:ab 42
S: +OK\r\n
C: *2\r\n$3\r\nGET\r\n$7\r\nsess:ab\r\n                    GET sess:ab     ┐ pipelined:
C: *2\r\n$4\r\nINCR\r\n$7\r\nsess:ab\r\n                   INCR sess:ab    │ sent together
C: *2\r\n$5\r\nHGETALL\r\n$7\r\nsess:ab\r\n                HGETALL sess:ab ┘
S: $2\r\n42\r\n                                             "42"
S: :43\r\n                                                  43
S: -WRONGTYPE Operation against a key holding the wrong kind of value\r\n
```
