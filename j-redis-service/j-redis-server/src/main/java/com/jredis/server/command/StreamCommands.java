package com.jredis.server.command;

import com.jredis.server.core.CommandContext;
import com.jredis.server.db.KeyEntry;
import com.jredis.server.db.StreamId;
import com.jredis.server.db.StreamValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static com.jredis.server.command.CommandSpec.DENYOOM;
import static com.jredis.server.command.CommandSpec.FAST;
import static com.jredis.server.command.CommandSpec.READONLY;
import static com.jredis.server.command.CommandSpec.WRITE;

/**
 * Stream commands ([15](../../../../../../../../docs/15-streams.md)). Every argument is checked
 * before the stream is prepared for writing: an error after a change would stop the server.
 */
final class StreamCommands {

    private StreamCommands() {
    }

    static final CommandException INVALID_ID =
            new CommandException("ERR Invalid stream ID specified as stream command argument");
    private static final CommandException NOT_GREATER =
            new CommandException("ERR The ID specified in XADD is equal or smaller than the target stream top item");
    private static final CommandException ZERO_ID =
            new CommandException("ERR The ID specified in XADD must be greater than 0-0");
    private static final CommandException EXHAUSTED =
            new CommandException("ERR The stream has exhausted the last possible ID, unable to add more items");

    static void register(CommandTable t) {
        t.register("XADD", -5, WRITE | DENYOOM | FAST, StreamCommands::xadd);
        t.register("XLEN", 2, READONLY | FAST, StreamCommands::xlen);
        t.register("XRANGE", -4, READONLY, ctx -> xrange(ctx, false));
        t.register("XREVRANGE", -4, READONLY, ctx -> xrange(ctx, true));
        t.register("XDEL", -3, WRITE | FAST, StreamCommands::xdel);
        t.register("XTRIM", -4, WRITE, StreamCommands::xtrim);
        t.register("XREAD", -4, READONLY | CommandSpec.BLOCKING, StreamCommands::xread);
        StreamGroupCommands.register(t);
    }

    // ------------------------------------------------------------------ IDs

    /** {@code ms} or {@code ms-seq}, each an unsigned 64-bit decimal; a bare {@code ms} takes {@code missingSeq}. */
    static StreamId parseId(byte[] b, long missingSeq) {
        String s = new String(b, StandardCharsets.US_ASCII);
        int dash = s.indexOf('-');
        return dash < 0
                ? new StreamId(unsigned(s), missingSeq)
                : new StreamId(unsigned(s.substring(0, dash)), unsigned(s.substring(dash + 1)));
    }

    private static long unsigned(String s) {
        if (s.isEmpty() || !Character.isDigit(s.charAt(0))) {    // parseUnsignedLong would take a '+'
            throw INVALID_ID;
        }
        try {
            return Long.parseUnsignedLong(s);
        } catch (NumberFormatException e) {
            throw INVALID_ID;
        }
    }

    static boolean is(byte[] b, char c) {
        return b.length == 1 && b[0] == c;
    }

    /** A range's lower end: {@code -}, an ID, or {@code (} and the ID it starts after. */
    static StreamId rangeStart(byte[] b) {
        if (is(b, '-')) {
            return StreamId.MIN;
        }
        if (is(b, '+')) {
            return StreamId.MAX;
        }
        if (b.length > 1 && b[0] == '(') {
            StreamId after = parseId(java.util.Arrays.copyOfRange(b, 1, b.length), 0).next();
            if (after == null) {
                throw new CommandException("ERR invalid start ID for the interval");
            }
            return after;
        }
        return parseId(b, 0);
    }

    /** A range's upper end: {@code +}, an ID (a bare millisecond whole), or {@code (} and the ID it stops before. */
    static StreamId rangeEnd(byte[] b) {
        if (is(b, '+')) {
            return StreamId.MAX;
        }
        if (is(b, '-')) {
            return StreamId.MIN;
        }
        if (b.length > 1 && b[0] == '(') {
            StreamId before = parseId(java.util.Arrays.copyOfRange(b, 1, b.length), -1L).previous();
            if (before == null) {
                throw new CommandException("ERR invalid end ID for the interval");
            }
            return before;
        }
        return parseId(b, -1L);
    }

    static byte[] bytes(StreamId id) {
        return id.toString().getBytes(StandardCharsets.US_ASCII);
    }

    // ------------------------------------------------------------------ trimming

    /** A trim as given: {@code MAXLEN} or {@code MINID}, exact or {@code ~}, and a limit. */
    private static final class Trim {
        boolean given;
        boolean minId;
        boolean approx;
        long maxLen;
        StreamId min;
        long limit = -1;

        long apply(StreamValue s) {
            if (!given) {
                return 0;
            }
            return minId ? s.trimMinId(min, limit) : s.trimMaxLen(maxLen, limit);
        }
    }

    /**
     * Reads trim options from {@code i}, and {@code NOMKSTREAM} if allowed, until an argument is
     * neither. @return the index after them; {@code nomk[0]} tells whether NOMKSTREAM was there
     */
    private static int parseTrim(CommandContext ctx, int i, Trim t, boolean[] nomk) {
        boolean limitGiven = false;
        while (i < ctx.argc()) {
            if (nomk != null && ctx.argIs(i, "NOMKSTREAM")) {
                nomk[0] = true;
                i++;
            } else if (ctx.argIs(i, "MAXLEN") || ctx.argIs(i, "MINID")) {
                boolean minId = ctx.argIs(i, "MINID");
                if (t.given && t.minId != minId) {
                    throw new CommandException("ERR syntax error, MAXLEN and MINID options at the same time are not compatible");
                }
                t.given = true;
                t.minId = minId;
                i++;
                if (i < ctx.argc() && (is(ctx.arg(i), '~') || is(ctx.arg(i), '='))) {
                    t.approx = is(ctx.arg(i), '~');
                    i++;
                }
                if (i >= ctx.argc()) {
                    throw CommandException.SYNTAX;
                }
                if (minId) {
                    t.min = parseId(ctx.arg(i), 0);
                } else {
                    t.maxLen = ctx.longArg(i);
                    if (t.maxLen < 0) {
                        throw new CommandException("ERR The MAXLEN argument must be >= 0.");
                    }
                }
                i++;
            } else if (ctx.argIs(i, "LIMIT") && i + 1 < ctx.argc()) {
                t.limit = ctx.longArg(i + 1);
                if (t.limit < 0) {
                    throw new CommandException("ERR The LIMIT argument must be >= 0.");
                }
                limitGiven = true;
                i += 2;
            } else {
                break;
            }
        }
        if (limitGiven && !t.approx) {
            throw new CommandException("ERR syntax error, LIMIT cannot be used without the special ~ option");
        }
        return i;
    }

    // ------------------------------------------------------------------ commands

    /** The stream at key, or null; WRONGTYPE for another type. Not yet prepared for writing. */
    static StreamValue peek(CommandContext ctx, byte[] key) {
        KeyEntry e = ctx.db().lookupRead(key);
        if (e == null) {
            return null;
        }
        if (e.type() != KeyEntry.STREAM) {
            throw CommandException.WRONGTYPE;
        }
        return e.stream();
    }

    static void xadd(CommandContext ctx) {
        byte[] key = ctx.arg(1);
        Trim trim = new Trim();
        boolean[] nomk = new boolean[1];
        int at = parseTrim(ctx, 2, trim, nomk);
        int pairs = ctx.argc() - at - 1;
        if (at >= ctx.argc() || pairs < 2 || pairs % 2 != 0) {
            throw new CommandException("ERR wrong number of arguments for 'xadd' command");
        }
        StreamValue s = peek(ctx, key);
        if (s == null && nomk[0]) {
            ctx.nullBulk();
            return;
        }
        long lastMs = s == null ? 0 : s.lastMs();
        long lastSeq = s == null ? 0 : s.lastSeq();
        StreamId id = newId(ctx, ctx.arg(at), lastMs, lastSeq);

        if (s == null) {
            s = new StreamValue();
            ctx.db().add(key, KeyEntry.STREAM, s);
        } else {
            ctx.db().lookupWrite(key);
        }
        byte[][] fields = new byte[pairs][];
        for (int i = 0; i < pairs; i++) {
            fields[i] = ctx.arg(at + 1 + i);
        }
        s.append(id.ms(), id.seq(), fields);
        trim.apply(s);
        ctx.db().signalModified(key);
        byte[][] effect = ctx.argv().clone();
        effect[at] = bytes(id);                          // what the clock gave, so replay gives the same
        ctx.propagate(effect);
        ctx.bulk(bytes(id));
    }

    /** The ID an XADD adds: {@code *}, {@code ms-*} or explicit, greater than the last. */
    private static StreamId newId(CommandContext ctx, byte[] arg, long lastMs, long lastSeq) {
        if (is(arg, '*')) {
            long now = ctx.now();
            if (Long.compareUnsigned(now, lastMs) > 0) {
                return new StreamId(now, 0);
            }
            if (lastSeq != -1L) {
                return new StreamId(lastMs, lastSeq + 1);
            }
            if (lastMs == -1L) {
                throw EXHAUSTED;
            }
            return new StreamId(lastMs + 1, 0);
        }
        int n = arg.length;
        if (n > 2 && arg[n - 1] == '*' && arg[n - 2] == '-') {
            long ms = unsigned(new String(arg, 0, n - 2, StandardCharsets.US_ASCII));
            int c = Long.compareUnsigned(ms, lastMs);
            if (c > 0) {
                return new StreamId(ms, 0);
            }
            if (c < 0 || lastSeq == -1L) {
                throw NOT_GREATER;
            }
            return new StreamId(ms, lastSeq + 1);
        }
        StreamId id = parseId(arg, 0);
        if (id.ms() == 0 && id.seq() == 0) {
            throw ZERO_ID;
        }
        if (StreamId.compare(id.ms(), id.seq(), lastMs, lastSeq) <= 0) {
            throw NOT_GREATER;
        }
        return id;
    }

    static void xlen(CommandContext ctx) {
        KeyEntry e = Keys.read(ctx, ctx.arg(1), KeyEntry.STREAM);
        ctx.integer(e == null ? 0 : e.stream().size());
    }

    static void xrange(CommandContext ctx, boolean rev) {
        StreamId start = rev ? rangeStart(ctx.arg(3)) : rangeStart(ctx.arg(2));
        StreamId end = rev ? rangeEnd(ctx.arg(2)) : rangeEnd(ctx.arg(3));
        long count = -1;
        if (ctx.argc() == 6 && ctx.argIs(4, "COUNT")) {
            count = Math.max(0, ctx.longArg(5));
        } else if (ctx.argc() != 4) {
            throw CommandException.SYNTAX;
        }
        KeyEntry e = Keys.read(ctx, ctx.arg(1), KeyEntry.STREAM);
        List<Object[]> out = new ArrayList<>();
        if (e != null) {
            e.stream().range(start, end, rev, count, (ms, seq, fields) -> out.add(new Object[] {new StreamId(ms, seq), fields}));
        }
        replyEntries(ctx, out);
    }

    /** Entries as Redis replies them: each an array of its ID and an array of its fields. */
    static void replyEntries(CommandContext ctx, List<Object[]> entries) {
        ctx.arrayHeader(entries.size());
        for (Object[] entry : entries) {
            replyEntry(ctx, (StreamId) entry[0], (byte[][]) entry[1]);
        }
    }

    /** One entry: its ID and its fields, or a nil for fields that are gone (a pending entry deleted). */
    static void replyEntry(CommandContext ctx, StreamId id, byte[][] fields) {
        ctx.arrayHeader(2);
        ctx.bulk(bytes(id));
        if (fields == null) {
            ctx.nullArray();
            return;
        }
        ctx.arrayHeader(fields.length);
        for (byte[] f : fields) {
            ctx.bulk(f);
        }
    }

    static void xdel(CommandContext ctx) {
        byte[] key = ctx.arg(1);
        StreamId[] ids = new StreamId[ctx.argc() - 2];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = parseId(ctx.arg(i + 2), 0);
        }
        StreamValue s = peek(ctx, key);
        if (s == null) {
            ctx.integer(0);
            return;
        }
        ctx.db().lookupWrite(key);
        int deleted = 0;
        for (StreamId id : ids) {
            if (s.delete(id.ms(), id.seq())) {
                deleted++;
            }
        }
        if (deleted > 0) {
            ctx.db().signalModified(key);
            ctx.propagateAsIs();
        }
        ctx.integer(deleted);
    }

    /** BLOCK's argument: milliseconds, 0 for ever. */
    static long blockMillis(CommandContext ctx, int i) {
        long ms;
        try {
            ms = com.jredis.common.NumberCodec.parseLong(ctx.arg(i));
        } catch (NumberFormatException e) {
            throw new CommandException("ERR timeout is not an integer or out of range");
        }
        if (ms < 0) {
            throw new CommandException("ERR timeout is negative");
        }
        return ms;
    }

    /** Whether a read may wait: never inside a transaction or while loading, as the list pops. */
    static boolean mayBlock(CommandContext ctx, long blockMs) {
        return blockMs >= 0 && !ctx.engine().inExec() && !ctx.db().isLoading();
    }

    static void xread(CommandContext ctx) {
        long count = -1;
        long block = -1;
        int i = 1;
        boolean streams = false;
        for (; i < ctx.argc(); i++) {
            if (ctx.argIs(i, "COUNT") && i + 1 < ctx.argc()) {
                count = ctx.longArg(++i);
                if (count <= 0) {
                    count = -1;                                  // as Redis: no limit
                }
            } else if (ctx.argIs(i, "BLOCK") && i + 1 < ctx.argc()) {
                block = blockMillis(ctx, ++i);
            } else if (ctx.argIs(i, "STREAMS")) {
                i++;
                streams = true;
                break;
            } else {
                throw CommandException.SYNTAX;
            }
        }
        int rest = ctx.argc() - i;
        if (!streams || rest <= 0 || rest % 2 != 0) {
            throw new CommandException("ERR Unbalanced 'xread' list of streams: for each stream key an ID or '$' must be specified.");
        }
        int n = rest / 2;
        byte[][] keys = new byte[n][];
        StreamId[] after = new StreamId[n];
        StreamValue[] found = new StreamValue[n];
        for (int k = 0; k < n; k++) {
            keys[k] = ctx.arg(i + k);
            byte[] id = ctx.arg(i + n + k);
            if (is(id, '>')) {
                throw new CommandException("ERR The > ID can be specified only when calling XREADGROUP using the GROUP <group> <consumer> option.");
            }
            KeyEntry e = Keys.read(ctx, keys[k], KeyEntry.STREAM);
            found[k] = e == null ? null : e.stream();
            after[k] = is(id, '$') ? (found[k] == null ? StreamId.MIN : found[k].lastId()) : parseId(id, 0);
        }
        List<Object[]> results = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            if (found[k] != null) {
                List<Object[]> entries = com.jredis.server.db.StreamDelivery.after(found[k], after[k], count);
                if (!entries.isEmpty()) {
                    results.add(new Object[] {keys[k], entries});
                }
            }
        }
        if (results.isEmpty()) {
            if (mayBlock(ctx, block)) {
                ctx.engine().blocking().block(ctx.client(),
                        com.jredis.server.blocking.BlockState.xread(keys, block == 0 ? 0 : ctx.now() + block, after, count));
            } else {
                ctx.nullArray();
            }
            return;
        }
        replyStreams(ctx, results);
    }

    /** {@code [[key, entries] …]}, a stream read's reply. */
    @SuppressWarnings("unchecked")
    static void replyStreams(CommandContext ctx, List<Object[]> results) {
        ctx.arrayHeader(results.size());
        for (Object[] r : results) {
            ctx.arrayHeader(2);
            ctx.bulk((byte[]) r[0]);
            List<Object[]> entries = (List<Object[]>) r[1];
            ctx.arrayHeader(entries.size());
            for (Object[] e : entries) {
                replyEntry(ctx, (StreamId) e[0], (byte[][]) e[1]);
            }
        }
    }

    static void xtrim(CommandContext ctx) {
        byte[] key = ctx.arg(1);
        Trim trim = new Trim();
        int end = parseTrim(ctx, 2, trim, null);
        if (end != ctx.argc()) {                         // a trim unread, or none given
            throw CommandException.SYNTAX;
        }
        StreamValue s = peek(ctx, key);
        if (s == null) {
            ctx.integer(0);
            return;
        }
        ctx.db().lookupWrite(key);
        long removed = trim.apply(s);
        if (removed > 0) {
            ctx.db().signalModified(key);
            ctx.propagateAsIs();
        }
        ctx.integer(removed);
    }
}
