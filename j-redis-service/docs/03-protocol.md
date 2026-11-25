# 03 — Protocol

This page describes the bytes that j-redis's clients and server send each other.
It is for engineers who work on the server's network code, on `j-redis-client` or
`j-redis-cli`, or who read a packet capture. It covers why the protocol is RESP2,
its types, requests, the parsers and their limits, replies, pub/sub messages,
number formats, error prefixes, `HELLO`, and a complete example.

## 1. Why RESP2

RESP is the REdis Serialization Protocol, the wire format of Redis. We write
both ends, the server and the client, so the wire format is our own choice. We
use **RESP2**, its version 2, because:

- it is a complete, proven specification that we do not have to design or
  document;
- it is very simple (five types) and binary-safe;
- a person can read it in a packet capture and type it by hand over `telnet` or
  `nc`;
- the append-only file (AOF) uses the same encoding, so one parser serves both
  ([08](08-persistence.md));
- it keeps the door open to swapping implementations later.

Speaking RESP does not mean using any third-party software. Our own
`j-redis-client` and `j-redis-cli` are the only programs that talk to the server
([14](14-decision-log.md), D-1, D-14).

## 2. Types

Every frame starts with a one-byte type marker and ends with CRLF (the two bytes
`\r\n`).

| Marker | Type | Encoding | Example |
|---|---|---|---|
| `+` | Simple string | text without CR or LF | `+OK\r\n` |
| `-` | Error | `PREFIX message` | `-WRONGTYPE Operation against a key holding the wrong kind of value\r\n` |
| `:` | Integer | signed 64-bit decimal | `:1000\r\n` |
| `$` | Bulk string | length, CRLF, bytes, CRLF | `$5\r\nhello\r\n` |
| | Null bulk | length −1 | `$-1\r\n` |
| `*` | Array | count, CRLF, elements | `*2\r\n:1\r\n$1\r\na\r\n` |
| | Null array | count −1 | `*-1\r\n` |

Bulk strings are binary-safe: the length prefix marks the end, not a delimiter.

## 3. Requests

A request is either a multibulk array (what programs send) or an inline line
(what people type).

### Multibulk (normal)

A multibulk request is an array of bulk strings. Element 0 is the command name,
and its case does not matter.

```
SET user:42:name Alice   →   *3\r\n$3\r\nSET\r\n$12\r\nuser:42:name\r\n$5\r\nAlice\r\n
```

### Inline (for humans)

An inline request is one line of words separated by spaces, for `telnet` and
for debugging by hand. The server treats a request as inline when its first
byte is not `*`. Quotes group words, the same way as in Redis's inline protocol
and `redis-cli`:

- Inside double quotes, these escapes work: `\n`, `\r`, `\t`, `\b`, `\a`, `\\`,
  `\"` and `\xHH` (a byte in hexadecimal).
- Inside single quotes, only `\'` is an escape.
- A closing quote must be followed by a space or the end of the line.

```
$ nc 127.0.0.1 6379
PING
+PONG
SET greeting "hello world"
+OK
```

Only network clients may send inline requests. The AOF loader, `j-redis-tools
check-aof` and a replica's link to its primary accept multibulk requests only.

### Pipelining

A client may send any number of requests without waiting for replies. The
server runs them in order and replies in the same order
([02 §3](02-architecture.md#ordering-guarantee)).

## 4. The decoder

Two parsers in `j-redis-common` read RESP:

| Parser | Reads | Used by |
|---|---|---|
| `RespRequestParser` | Requests: multibulk, and inline where allowed | The server's network decoder (`RequestDecoder`, a Netty `ByteToMessageDecoder` in `j-redis-server`), the AOF loader, the AOF checker, and a replica's link to its primary |
| `RespReplyParser` | Replies of every type | `j-redis-client` (its `ReplyDecoder`), `j-redis-cli`, and the client's search for the primary |

`RespRequestParser` can resume. A caller feeds it the same growing buffer again
and again. It consumes bytes only for the parts it has fully understood, and it
keeps its position between calls. So a request split across TCP packets simply
continues on the next read. It copies nothing until a complete argument has
arrived.

```
state: start → (multibulk) COUNT → [ BULK_LEN → BULK_BODY ]*count → emit argv
               (inline)    LINE  → split → emit argv
```

- The parser reads the integers in headers directly from the bytes; it creates no
  `String`.
- It copies each complete argument into its own `byte[]`, on the I/O thread.
- One read may produce many requests. The decoder queues each one separately.
- An empty inline line, `*0` and `*-1` form no command and are skipped.

### Limits

| Limit | Default | Directive | On violation |
|---|---|---|---|
| Bulk string length | 64 MB | `proto-max-bulk-len` (1 KB to 2 GB) | protocol error |
| Arguments per request | 1,048,576 | fixed | protocol error |
| Inline line length | 64 KB | fixed | protocol error |
| A header line (`*N` or `$N`) | 32 bytes | fixed | protocol error |
| Unparsed bytes per client | 128 MB | `client-query-buffer-limit` (at least 1 MB) | protocol error |

The bulk limit of Redis is 512 MB. Ours is lower because our values are small,
and a large limit only helps a buggy client use up the heap. A `CONFIG SET` of
`proto-max-bulk-len` applies to open connections too, from their next bulk
header.

### Protocol errors

The I/O thread finds a protocol error, but earlier requests from the same client
may still be in the queue. To keep the replies in order, the server does this:

1. The decoder stops parsing. From then on it reads and drops every byte that the
   client sends. It goes on reading so that the connection later closes with a
   normal FIN, not a reset that could destroy replies still owed for earlier
   commands.
2. The I/O thread queues an `Events.ProtocolError` event.
3. `cmd` (the command thread) writes `-ERR Protocol error: <reason>` after the
   client's earlier replies, flushes, and closes the connection.

## 5. Replies by command family

| Reply | Used for |
|---|---|
| `+OK` | `SET`, `MSET`, `RENAME`, `FLUSHALL`… |
| `:n` | counts, booleans (`:1` or `:0`), `INCR` results, TTLs |
| `$…` / `$-1` | values; null when absent |
| `*…` | results with several values; `*-1` when a `BLPOP` times out and when `EXEC` is aborted by `WATCH` |
| `-PREFIX …` | errors ([§8](#8-error-conventions)) |

## 6. Pub/Sub frames

In subscriber mode, the server pushes arrays at any time:

```
*3  $9 subscribe   $<channel>  :<subscription count>
*3  $7 message     $<channel>  $<payload>
*4  $8 pmessage    $<pattern>  $<channel>  $<payload>
*3  $11 unsubscribe $<channel> :<remaining count>
```

A RESP2 connection in subscriber mode accepts only `SUBSCRIBE`, `UNSUBSCRIBE`,
`PSUBSCRIBE`, `PUNSUBSCRIBE`, `PING`, `QUIT` and `RESET`. `PING` in subscriber
mode replies with an array, `*2 $4 pong $0 ""`, as Redis does (with an argument,
the second element is the argument). This is why pub/sub uses a connection of its
own in the client ([10](10-client-library.md)).

## 7. Number formatting

`NumberCodec` in `j-redis-common` parses and formats numbers. Parsing is strict,
and formatting follows Redis.

### Integers

Replies use plain decimal. **Parsing is strict.** A valid integer is an optional
`-`, then digits, within the signed 64-bit range. It has no leading zeros (except
`0` itself), no `+` and no spaces. Java's `Long.parseLong` accepts more (for
example `+5` and `007`), so j-redis uses its own parser. A failure replies
`-ERR value is not an integer or out of range`.

### Doubles (sorted-set scores, `INCRBYFLOAT`)

**Parsing** accepts:

- an optional sign, digits, an optional fraction and an optional exponent;
- `inf` and `infinity`, with an optional sign, in any case.

It rejects `NaN`, spaces, hexadecimal, Java-only suffixes such as `1.5d`, more
than 5,120 characters, and values that overflow or underflow a `double`.
`Double.parseDouble` alone is not safe here, because it accepts `" 1.5d"` and
`NaN`. So j-redis checks the text first, then converts it. A failure replies
`-ERR value is not a valid float`.

**Formatting:**

| Value | Output |
|---|---|
| Integral, and between −(2⁶² − 1) and 2⁶² − 1 (`Long.MAX_VALUE / 2`, about 4.6 × 10¹⁸) | integer form: `1`, `-42`, `1000` |
| ±∞ | `inf`, `-inf` |
| Not integral, and 10⁻⁵ ≤ \|v\| < 10¹⁷ | plain decimal from Java's `Double.toString`, trailing zeros removed: `0.1`, `1.5` |
| Otherwise | exponent form, Redis style: `1e+20`, `1e-7` |

The value always round-trips exactly. The text matches Redis for integers and
common decimals. Before JDK 19, `Double.toString` sometimes printed one more
digit than the shortest form; j-redis runs on Java 21, where it prints the
shortest. Text that is byte for byte the same as Redis's in every case is still
a non-goal ([01 §6](01-requirements-and-scope.md#6-out-of-scope)).

**`INCRBYFLOAT` and `HINCRBYFLOAT`** work differently, as in Redis. Redis adds in
`long double` and prints the result with 17 digits after the point, trailing
zeros removed, never in exponent form. j-redis adds the two decimal values
exactly (`BigDecimal`), rounds to 17 digits after the point, and presents the
result the same way ([14](14-decision-log.md), D-29). So `0.1 + 0.2` is `0.3`
(binary `double` arithmetic would give `0.30000000000000004`), and `1e20` is
`100000000000000000000`. The two can differ only in the 17th decimal of unusual
values.

## 8. Error conventions

The first word of an error is its **prefix**. Clients branch on the prefix, not
on the message.

| Prefix | Meaning |
|---|---|
| `ERR` | Generic: syntax, number of arguments, invalid argument, unknown command |
| `WRONGTYPE` | A command used against a key of another type |
| `NOAUTH` | Authentication required |
| `WRONGPASS` | `AUTH` failed |
| `OOM` | Write refused because memory is over `maxmemory` |
| `MISCONF` | Write refused because persistence is failing |
| `EXECABORT` | Transaction discarded because a queued command was invalid, or because of `MISCONF` or `OOM` at `EXEC` |
| `NOPROTO` | `HELLO` asked for a protocol version other than 2 |
| `DENIED` | Protected mode refused the connection: no password is set and the client is not on the loopback interface. Sent once, at connect, before the server closes the connection. |
| `READONLY` | Write sent to a replica (2.2, [16](16-replication.md)) |
| `NOREPLICAS` | Write refused because there are fewer good replicas than `min-replicas-to-write` (2.2) |
| `EPOCH` | A primary refused a replica whose epoch is newer than its own: that primary was demoted and must not be followed (2.2, [16 §9](16-replication.md#9-promotion-and-epochs)) |
| `BUSYGROUP` | The stream consumer group already exists (2.1, [15](15-streams.md)) |
| `NOGROUP` | No such stream consumer group, or the group a blocked client waited on was deleted (2.1) |
| `UNBLOCKED` | The stream that a blocked client waited on was deleted (2.1) |

## 9. `HELLO` and connection set-up

- `HELLO` or `HELLO 2 [AUTH default <password>] [SETNAME name]` replies with
  server information as a flat array of 14 elements: `server` "j-redis",
  `version`, `proto` 2, `id`, `mode` "standalone", `role` "master" (also on a
  replica), and `modules` (empty).
- `HELLO 3` replies `-NOPROTO unsupported protocol version`. A client that tries
  RESP3 falls back to RESP2.
- In `HELLO … AUTH`, the user name must be `default`; any other name fails with
  `WRONGPASS`. When a password is set, `HELLO` without `AUTH` from a client that
  has not authenticated fails with `NOAUTH`.
- `AUTH <password>` and `AUTH default <password>` are both accepted. When no
  password is set, `AUTH default <anything>` succeeds, as in Redis, and
  `AUTH <password>` replies with an error.

## 10. A complete conversation

The client (`C`) sets a key, then pipelines three commands. The server (`S`)
replies in order; the third command fails because the key holds a string, not a
hash.

```
C: *3\r\n$3\r\nSET\r\n$7\r\nsess:ab\r\n$2\r\n42\r\n        SET sess:ab 42
S: +OK\r\n
C: *2\r\n$3\r\nGET\r\n$7\r\nsess:ab\r\n                    GET sess:ab     ┐ pipelined:
C: *2\r\n$4\r\nINCR\r\n$7\r\nsess:ab\r\n                   INCR sess:ab    │ sent together
C: *2\r\n$7\r\nHGETALL\r\n$7\r\nsess:ab\r\n                HGETALL sess:ab ┘
S: $2\r\n42\r\n                                             "42"
S: :43\r\n                                                  43
S: -WRONGTYPE Operation against a key holding the wrong kind of value\r\n
```
