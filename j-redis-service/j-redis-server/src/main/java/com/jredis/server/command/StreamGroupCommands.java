package com.jredis.server.command;

import com.jredis.server.core.CommandContext;
import com.jredis.server.db.KeyEntry;
import com.jredis.server.db.StreamDelivery;
import com.jredis.server.db.StreamGroup;
import com.jredis.server.db.StreamId;
import com.jredis.server.db.StreamValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.jredis.server.command.CommandSpec.DENYOOM;
import static com.jredis.server.command.CommandSpec.FAST;
import static com.jredis.server.command.CommandSpec.READONLY;
import static com.jredis.server.command.CommandSpec.WRITE;
import static com.jredis.server.command.StreamCommands.bytes;
import static com.jredis.server.command.StreamCommands.is;
import static com.jredis.server.command.StreamCommands.parseId;
import static com.jredis.server.command.StreamCommands.peek;

/**
 * Consumer groups ([15 §2–§3](../../../../../../../../docs/15-streams.md)). What a command changes
 * is logged as deterministic effects (D-34): a delivery or a claim as {@code XCLAIM … TIME
 * RETRYCOUNT FORCE JUSTID}, an entry dropped from a pending list as {@code XACK}, a consumer a
 * command made as {@code XGROUP CREATECONSUMER}; several at once in one transaction.
 */
final class StreamGroupCommands {

    private StreamGroupCommands() {
    }

    private static final CommandException KEY_REQUIRED = new CommandException(
            "ERR The XGROUP subcommand requires the key to exist. Note that for CREATE you may want to use the MKSTREAM option to create an empty stream automatically.");
    private static final CommandException BUSYGROUP = new CommandException("BUSYGROUP Consumer Group name already exists");

    private static byte[] w(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static final byte[] XGROUP = w("XGROUP");
    private static final byte[] CREATE = w("CREATE");
    private static final byte[] SETID = w("SETID");
    private static final byte[] CREATECONSUMER = w("CREATECONSUMER");
    private static final byte[] MKSTREAM = w("MKSTREAM");
    private static final byte[] XACK = w("XACK");

    static void register(CommandTable t) {
        t.register("XGROUP", -2, WRITE | DENYOOM, StreamGroupCommands::xgroup);
        t.register("XREADGROUP", -7, WRITE | CommandSpec.BLOCKING, StreamGroupCommands::xreadgroup);
        t.register("XACK", -4, WRITE | FAST, StreamGroupCommands::xack);
        t.register("XPENDING", -3, READONLY, StreamGroupCommands::xpending);
        t.register("XCLAIM", -6, WRITE | FAST, StreamGroupCommands::xclaim);
        t.register("XAUTOCLAIM", -6, WRITE | FAST, StreamGroupCommands::xautoclaim);
        t.register("XINFO", -2, READONLY, StreamGroupCommands::xinfo);
    }

    private static String text(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private static CommandException noGroupFor(byte[] key, byte[] group) {
        return new CommandException("NOGROUP No such consumer group '" + text(group) + "' for key name '" + text(key) + "'");
    }

    private static CommandException noKeyOrGroup(byte[] key, byte[] group, String more) {
        return new CommandException("NOGROUP No such key '" + text(key) + "' or consumer group '" + text(group) + "'" + more);
    }

    private static void wrongArgs(CommandContext ctx, int... allowed) {
        for (int n : allowed) {
            if (ctx.argc() == n) {
                return;
            }
        }
        throw new CommandException("ERR wrong number of arguments for '" + ctx.argString(0).toLowerCase(Locale.ROOT)
                + "|" + ctx.argString(1).toLowerCase(Locale.ROOT) + "' command");
    }

    /** Effects of one command, logged in one transaction when there are several. */
    private static void log(CommandContext ctx, List<byte[][]> effects) {
        if (effects.size() > 1) {
            ctx.engine().effects().beginTransaction();
        }
        for (byte[][] e : effects) {
            ctx.propagate(e);
        }
        if (effects.size() > 1) {
            ctx.engine().effects().endTransaction();
        }
    }

    private static byte[][] claimEffect(byte[] key, byte[] group, byte[] consumer, StreamId id, long time, long count) {
        return StreamDelivery.claimEffect(key, group, consumer, id, time, count);
    }

    // ------------------------------------------------------------------ XGROUP

    static void xgroup(CommandContext ctx) {
        String sub = ctx.argString(1).toUpperCase(Locale.ROOT);
        switch (sub) {
            case "CREATE": create(ctx); break;
            case "SETID": setId(ctx); break;
            case "DESTROY": destroy(ctx); break;
            case "CREATECONSUMER": createConsumer(ctx); break;
            case "DELCONSUMER": delConsumer(ctx); break;
            default: throw new CommandException("ERR unknown subcommand '" + ctx.argString(1) + "'. Try XGROUP HELP.");
        }
    }

    private static void create(CommandContext ctx) {
        wrongArgs(ctx, 5, 6);
        byte[] key = ctx.arg(2);
        byte[] group = ctx.arg(3);
        boolean mk = ctx.argc() == 6;
        if (mk && !ctx.argIs(5, "MKSTREAM")) {
            throw CommandException.SYNTAX;
        }
        StreamId id = is(ctx.arg(4), '$') ? null : parseId(ctx.arg(4), 0);
        StreamValue s = peek(ctx, key);
        if (s == null && !mk) {
            throw KEY_REQUIRED;
        }
        if (s != null && s.group(group) != null) {
            throw BUSYGROUP;
        }
        if (s == null) {
            s = new StreamValue();
            ctx.db().add(key, KeyEntry.STREAM, s);
        } else {
            ctx.db().lookupWrite(key);
        }
        StreamId at = id == null ? s.lastId() : id;
        s.createGroup(group, at.ms(), at.seq());
        ctx.db().signalModified(key);
        ctx.propagate(mk ? new byte[][] {XGROUP, CREATE, key, group, bytes(at), MKSTREAM}
                : new byte[][] {XGROUP, CREATE, key, group, bytes(at)});
        ctx.ok();
    }

    /** The stream and its group, both required. */
    private static StreamValue existing(CommandContext ctx, byte[] key, byte[] group) {
        StreamValue s = peek(ctx, key);
        if (s == null) {
            throw KEY_REQUIRED;
        }
        if (s.group(group) == null) {
            throw noGroupFor(key, group);
        }
        return s;
    }

    private static void setId(CommandContext ctx) {
        wrongArgs(ctx, 5);
        byte[] key = ctx.arg(2);
        byte[] group = ctx.arg(3);
        StreamId id = is(ctx.arg(4), '$') ? null : parseId(ctx.arg(4), 0);
        StreamValue s = existing(ctx, key, group);
        ctx.db().lookupWrite(key);
        StreamId at = id == null ? s.lastId() : id;
        s.setGroupId(s.group(group), at.ms(), at.seq());
        ctx.db().signalModified(key);
        ctx.propagate(XGROUP, SETID, key, group, bytes(at));
        ctx.ok();
    }

    private static void destroy(CommandContext ctx) {
        wrongArgs(ctx, 4);
        byte[] key = ctx.arg(2);
        StreamValue s = peek(ctx, key);
        if (s == null) {
            throw KEY_REQUIRED;
        }
        if (s.group(ctx.arg(3)) == null) {
            ctx.integer(0);
            return;
        }
        ctx.db().lookupWrite(key);
        s.destroyGroup(ctx.arg(3));
        ctx.db().signalModified(key);
        ctx.propagateAsIs();
        ctx.integer(1);
    }

    private static void createConsumer(CommandContext ctx) {
        wrongArgs(ctx, 5);
        byte[] key = ctx.arg(2);
        StreamValue s = existing(ctx, key, ctx.arg(3));
        StreamGroup g = s.group(ctx.arg(3));
        if (g.consumer(ctx.arg(4)) != null) {
            ctx.integer(0);
            return;
        }
        ctx.db().lookupWrite(key);
        s.consumer(g, ctx.arg(4), ctx.now(), new boolean[1]);
        ctx.db().signalModified(key);
        ctx.propagateAsIs();
        ctx.integer(1);
    }

    private static void delConsumer(CommandContext ctx) {
        wrongArgs(ctx, 5);
        byte[] key = ctx.arg(2);
        StreamValue s = existing(ctx, key, ctx.arg(3));
        StreamGroup g = s.group(ctx.arg(3));
        if (g.consumer(ctx.arg(4)) == null) {
            ctx.integer(0);
            return;
        }
        ctx.db().lookupWrite(key);
        long pending = s.deleteConsumer(g, ctx.arg(4));
        ctx.db().signalModified(key);
        ctx.propagateAsIs();
        ctx.integer(pending);
    }

    // ------------------------------------------------------------------ XREADGROUP

    static void xreadgroup(CommandContext ctx) {
        if (!ctx.argIs(1, "GROUP")) {
            throw CommandException.SYNTAX;
        }
        byte[] groupName = ctx.arg(2);
        byte[] consumerName = ctx.arg(3);
        long count = -1;
        long block = -1;
        boolean noack = false;
        int i = 4;
        for (; i < ctx.argc(); i++) {
            if (ctx.argIs(i, "COUNT") && i + 1 < ctx.argc()) {
                count = ctx.longArg(++i);
                if (count <= 0) {
                    count = -1;                          // as Redis: no limit
                }
            } else if (ctx.argIs(i, "BLOCK") && i + 1 < ctx.argc()) {
                block = StreamCommands.blockMillis(ctx, ++i);
            } else if (ctx.argIs(i, "NOACK")) {
                noack = true;
            } else if (ctx.argIs(i, "STREAMS")) {
                i++;
                break;
            } else {
                throw CommandException.SYNTAX;
            }
        }
        int rest = ctx.argc() - i;
        if (rest <= 0 || rest % 2 != 0) {
            throw new CommandException("ERR Unbalanced 'xreadgroup' list of streams: for each stream key an ID or '>' must be specified.");
        }
        int n = rest / 2;
        StreamId[] from = new StreamId[n];                 // null: new entries, '>'
        for (int k = 0; k < n; k++) {
            byte[] id = ctx.arg(i + n + k);
            if (is(id, '$')) {
                throw new CommandException("ERR The $ ID is meaningless in the context of XREADGROUP: you want to read the history of this consumer by specifying a proper ID, or use the > ID to get new messages. The $ ID would just return an empty result set.");
            }
            from[k] = is(id, '>') ? null : parseId(id, 0);
        }
        for (int k = 0; k < n; k++) {
            StreamValue s = peek(ctx, ctx.arg(i + k));
            if (s == null || s.group(groupName) == null) {
                throw noKeyOrGroup(ctx.arg(i + k), groupName, " in XREADGROUP with GROUP option");
            }
        }

        long now = ctx.now();
        List<byte[][]> effects = new ArrayList<>();
        List<Object[]> results = new ArrayList<>();         // key, entries
        for (int k = 0; k < n; k++) {
            byte[] key = ctx.arg(i + k);
            StreamValue s = peek(ctx, key);
            ctx.db().lookupWrite(key);
            StreamGroup g = s.group(groupName);
            boolean[] created = new boolean[1];
            StreamGroup.Consumer c = s.consumer(g, consumerName, now, created);
            if (created[0]) {
                effects.add(new byte[][] {XGROUP, CREATECONSUMER, key, groupName, consumerName});
            }
            List<Object[]> entries = new ArrayList<>();
            if (from[k] == null) {
                entries.addAll(StreamDelivery.deliverNew(s, g, c, key, count, noack, now, effects));
                if (!entries.isEmpty()) {
                    results.add(new Object[] {key, entries});
                }
            } else {
                for (Map.Entry<StreamId, StreamGroup.Pending> e : c.pending().tailMap(from[k], false).entrySet()) {
                    if (count > 0 && entries.size() == count) {
                        break;
                    }
                    entries.add(new Object[] {e.getKey(), s.fields(e.getKey().ms(), e.getKey().seq())});
                }
                StreamValue.touch(c, now, false);
                results.add(new Object[] {key, entries});
            }
            if (created[0] || !entries.isEmpty() && from[k] == null) {
                ctx.db().signalModified(key);
            }
        }
        log(ctx, effects);
        if (results.isEmpty()) {
            // Only reads of new entries come here empty: a history read always answers.
            if (StreamCommands.mayBlock(ctx, block)) {
                byte[][] keys = Arrays.copyOfRange(ctx.argv(), i, i + n);
                ctx.engine().blocking().block(ctx.client(), com.jredis.server.blocking.BlockState.xreadgroup(
                        keys, block == 0 ? 0 : now + block, groupName, consumerName, noack, count));
            } else {
                ctx.nullArray();
            }
            return;
        }
        StreamCommands.replyStreams(ctx, results);
    }

    // ------------------------------------------------------------------ XACK, XPENDING

    static void xack(CommandContext ctx) {
        byte[] key = ctx.arg(1);
        StreamId[] ids = new StreamId[ctx.argc() - 3];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = parseId(ctx.arg(i + 3), 0);
        }
        StreamValue s = peek(ctx, key);
        if (s == null || s.group(ctx.arg(2)) == null) {
            ctx.integer(0);
            return;
        }
        ctx.db().lookupWrite(key);
        StreamGroup g = s.group(ctx.arg(2));
        int acked = 0;
        for (StreamId id : ids) {
            if (s.ack(g, id)) {
                acked++;
            }
        }
        if (acked > 0) {
            ctx.db().signalModified(key);
            ctx.propagateAsIs();
        }
        ctx.integer(acked);
    }

    private static final Comparator<StreamGroup.Consumer> BY_NAME = (a, b) -> Arrays.compareUnsigned(a.name(), b.name());

    static void xpending(CommandContext ctx) {
        byte[] key = ctx.arg(1);
        byte[] groupName = ctx.arg(2);
        long minIdle = 0;
        int i = 3;
        if (ctx.argc() > 3 && ctx.argIs(3, "IDLE")) {
            if (ctx.argc() < 5) {
                throw CommandException.SYNTAX;
            }
            minIdle = ctx.longArg(4);
            i = 5;
        }
        boolean summary = ctx.argc() == 3;
        if (!summary && ctx.argc() != i + 3 && ctx.argc() != i + 4) {
            throw CommandException.SYNTAX;
        }
        StreamId start = summary ? null : StreamCommands.rangeStart(ctx.arg(i));
        StreamId end = summary ? null : StreamCommands.rangeEnd(ctx.arg(i + 1));
        long count = summary ? 0 : ctx.longArg(i + 2);
        KeyEntry e = Keys.read(ctx, key, KeyEntry.STREAM);
        StreamGroup g = e == null ? null : e.stream().group(groupName);
        if (g == null) {
            throw noKeyOrGroup(key, groupName, "");
        }
        if (summary) {
            if (g.pending().isEmpty()) {
                ctx.arrayHeader(4);
                ctx.integer(0);
                ctx.nullBulk();
                ctx.nullBulk();
                ctx.nullArray();
                return;
            }
            List<StreamGroup.Consumer> with = new ArrayList<>();
            for (StreamGroup.Consumer c : g.consumers()) {
                if (!c.pending().isEmpty()) {
                    with.add(c);
                }
            }
            with.sort(BY_NAME);
            ctx.arrayHeader(4);
            ctx.integer(g.pending().size());
            ctx.bulk(bytes(g.pending().firstKey()));
            ctx.bulk(bytes(g.pending().lastKey()));
            ctx.arrayHeader(with.size());
            for (StreamGroup.Consumer c : with) {
                ctx.arrayHeader(2);
                ctx.bulk(c.name());
                ctx.bulk(Integer.toString(c.pending().size()));
            }
            return;
        }
        StreamGroup.Consumer only = ctx.argc() == i + 4 ? g.consumer(ctx.arg(i + 3)) : null;
        List<Map.Entry<StreamId, StreamGroup.Pending>> out = new ArrayList<>();
        if (!(ctx.argc() == i + 4 && only == null) && count > 0 && start.compareTo(end) <= 0) {
            for (Map.Entry<StreamId, StreamGroup.Pending> p : (only != null ? only.pending() : g.pending()).subMap(start, true, end, true).entrySet()) {
                if (minIdle > 0 && ctx.now() - p.getValue().deliveryTime() < minIdle) {
                    continue;                           // only with IDLE, as Redis: a delivery ahead of this
                }                                       // clock (a replica's, behind) is still listed
                out.add(p);
                if (out.size() == count) {
                    break;
                }
            }
        }
        ctx.arrayHeader(out.size());
        for (Map.Entry<StreamId, StreamGroup.Pending> p : out) {
            ctx.arrayHeader(4);
            ctx.bulk(bytes(p.getKey()));
            ctx.bulk(p.getValue().consumer().name());
            ctx.integer(ctx.now() - p.getValue().deliveryTime());
            ctx.integer(p.getValue().deliveryCount());
        }
    }

    // ------------------------------------------------------------------ XCLAIM, XAUTOCLAIM

    private static long minIdle(CommandContext ctx, int i, String command) {
        try {
            return Math.max(0, com.jredis.common.NumberCodec.parseLong(ctx.arg(i)));
        } catch (NumberFormatException e) {
            throw new CommandException("ERR Invalid min-idle-time argument for " + command);
        }
    }

    static void xclaim(CommandContext ctx) {
        byte[] key = ctx.arg(1);
        byte[] groupName = ctx.arg(2);
        byte[] consumerName = ctx.arg(3);
        long minIdle = minIdle(ctx, 4, "XCLAIM");
        List<StreamId> ids = new ArrayList<>();
        int i = 5;
        for (; i < ctx.argc(); i++) {
            try {
                ids.add(parseId(ctx.arg(i), 0));
            } catch (CommandException notAnId) {
                break;
            }
        }
        long now = ctx.now();
        long time = now;
        long retryCount = -1;
        boolean force = false;
        boolean justId = false;
        StreamId lastId = null;
        for (; i < ctx.argc(); i++) {
            boolean more = i + 1 < ctx.argc();
            if (ctx.argIs(i, "IDLE") && more) {
                time = now - ctx.longArg(++i);
            } else if (ctx.argIs(i, "TIME") && more) {
                time = ctx.longArg(++i);
            } else if (ctx.argIs(i, "RETRYCOUNT") && more) {
                retryCount = ctx.longArg(++i);
            } else if (ctx.argIs(i, "FORCE")) {
                force = true;
            } else if (ctx.argIs(i, "JUSTID")) {
                justId = true;
            } else if (ctx.argIs(i, "LASTID") && more) {
                lastId = parseId(ctx.arg(++i), 0);
            } else {
                throw new CommandException("ERR Unrecognized XCLAIM option '" + ctx.argString(i) + "'");
            }
        }
        if (!ctx.db().isLoading() && (time < 0 || time > now)) {
            time = now;                                 // a client's; a logged one keeps its time, as a
        }                                               // replica's clock may run behind its primary's
        StreamValue s = peek(ctx, key);
        if (s == null || s.group(groupName) == null) {
            throw noKeyOrGroup(key, groupName, "");
        }
        ctx.db().lookupWrite(key);
        StreamGroup g = s.group(groupName);
        List<byte[][]> effects = new ArrayList<>();
        boolean[] created = new boolean[1];
        StreamGroup.Consumer c = s.consumer(g, consumerName, now, created);
        if (created[0]) {
            effects.add(new byte[][] {XGROUP, CREATECONSUMER, key, groupName, consumerName});
        }
        if (lastId != null && lastId.compareTo(g.lastDelivered()) > 0) {
            s.setGroupId(g, lastId.ms(), lastId.seq());
            effects.add(new byte[][] {XGROUP, SETID, key, groupName, bytes(lastId)});
        }
        List<Object[]> claimed = new ArrayList<>();
        for (StreamId id : ids) {
            StreamGroup.Pending p = g.pending().get(id);
            byte[][] fields = s.fields(id.ms(), id.seq());
            if (fields == null) {
                if (p != null) {                           // gone from the stream: dropped
                    s.ack(g, id);
                    effects.add(new byte[][] {XACK, key, groupName, bytes(id)});
                }
                continue;
            }
            if (p == null && !force) {
                continue;
            }
            if (p != null && minIdle > 0 && now - p.deliveryTime() < minIdle) {
                continue;
            }
            long n = retryCount >= 0 ? retryCount : (p == null ? 0 : p.deliveryCount()) + (justId ? 0 : 1);
            s.deliver(g, c, id, time, n);
            effects.add(claimEffect(key, groupName, consumerName, id, time, n));
            claimed.add(new Object[] {id, fields});
        }
        StreamValue.touch(c, now, !claimed.isEmpty());
        if (!effects.isEmpty()) {
            ctx.db().signalModified(key);
        }
        log(ctx, effects);
        replyClaimed(ctx, claimed, justId);
    }

    private static void replyClaimed(CommandContext ctx, List<Object[]> claimed, boolean justId) {
        if (justId) {
            ctx.arrayHeader(claimed.size());
            for (Object[] e : claimed) {
                ctx.bulk(bytes((StreamId) e[0]));
            }
        } else {
            StreamCommands.replyEntries(ctx, claimed);
        }
    }

    static void xautoclaim(CommandContext ctx) {
        byte[] key = ctx.arg(1);
        byte[] groupName = ctx.arg(2);
        byte[] consumerName = ctx.arg(3);
        long minIdle = minIdle(ctx, 4, "XAUTOCLAIM");
        StreamId start = StreamCommands.rangeStart(ctx.arg(5));
        long count = 100;
        boolean justId = false;
        for (int i = 6; i < ctx.argc(); i++) {
            if (ctx.argIs(i, "COUNT") && i + 1 < ctx.argc()) {
                count = ctx.longArg(++i);
                if (count < 1 || count > Long.MAX_VALUE / 10) {
                    throw new CommandException("ERR COUNT must be > 0");
                }
            } else if (ctx.argIs(i, "JUSTID")) {
                justId = true;
            } else {
                throw CommandException.SYNTAX;
            }
        }
        StreamValue s = peek(ctx, key);
        if (s == null || s.group(groupName) == null) {
            throw noKeyOrGroup(key, groupName, "");
        }
        ctx.db().lookupWrite(key);
        StreamGroup g = s.group(groupName);
        long now = ctx.now();
        List<byte[][]> effects = new ArrayList<>();
        boolean[] created = new boolean[1];
        StreamGroup.Consumer c = s.consumer(g, consumerName, now, created);
        if (created[0]) {
            effects.add(new byte[][] {XGROUP, CREATECONSUMER, key, groupName, consumerName});
        }
        List<Object[]> claimed = new ArrayList<>();
        List<StreamId> deleted = new ArrayList<>();
        long attempts = count * 10;
        long left = count;
        StreamId cur = g.pending().ceilingKey(start);
        while (cur != null && left > 0 && attempts-- > 0) {
            StreamId next = g.pending().higherKey(cur);
            byte[][] fields = s.fields(cur.ms(), cur.seq());
            if (fields == null) {
                s.ack(g, cur);
                deleted.add(cur);
                effects.add(new byte[][] {XACK, key, groupName, bytes(cur)});
            } else {
                StreamGroup.Pending p = g.pending().get(cur);
                if (now - p.deliveryTime() >= minIdle) {
                    long n = p.deliveryCount() + (justId ? 0 : 1);
                    s.deliver(g, c, cur, now, n);
                    effects.add(claimEffect(key, groupName, consumerName, cur, now, n));
                    claimed.add(new Object[] {cur, fields});
                    left--;
                }
            }
            cur = next;
        }
        StreamValue.touch(c, now, !claimed.isEmpty());
        if (!effects.isEmpty()) {
            ctx.db().signalModified(key);
        }
        log(ctx, effects);
        ctx.arrayHeader(3);
        ctx.bulk(bytes(cur == null ? StreamId.MIN : cur));
        replyClaimed(ctx, claimed, justId);
        ctx.arrayHeader(deleted.size());
        for (StreamId id : deleted) {
            ctx.bulk(bytes(id));
        }
    }

    // ------------------------------------------------------------------ XINFO

    static void xinfo(CommandContext ctx) {
        String sub = ctx.argString(1).toUpperCase(Locale.ROOT);
        switch (sub) {
            case "STREAM": {
                wrongArgs(ctx, 3);
                StreamValue s = infoStream(ctx, ctx.arg(2));
                ctx.arrayHeader(14);
                ctx.bulk("length");
                ctx.integer(s.size());
                ctx.bulk("radix-tree-keys");
                ctx.integer(s.chunkCount());
                ctx.bulk("radix-tree-nodes");
                ctx.integer(s.chunkCount());
                ctx.bulk("last-generated-id");
                ctx.bulk(bytes(s.lastId()));
                ctx.bulk("groups");
                ctx.integer(s.groups().size());
                ctx.bulk("first-entry");
                edge(ctx, s, false);
                ctx.bulk("last-entry");
                edge(ctx, s, true);
                break;
            }
            case "GROUPS": {
                wrongArgs(ctx, 3);
                StreamValue s = infoStream(ctx, ctx.arg(2));
                ctx.arrayHeader(s.groups().size());
                for (StreamGroup g : s.groups()) {
                    ctx.arrayHeader(10);
                    ctx.bulk("name");
                    ctx.bulk(g.name());
                    ctx.bulk("consumers");
                    ctx.integer(g.consumers().size());
                    ctx.bulk("pending");
                    ctx.integer(g.pending().size());
                    ctx.bulk("last-delivered-id");
                    ctx.bulk(bytes(g.lastDelivered()));
                    ctx.bulk("lag");
                    ctx.integer(s.countAfter(g.lastDelivered().ms(), g.lastDelivered().seq()));
                }
                break;
            }
            case "CONSUMERS": {
                wrongArgs(ctx, 4);
                StreamValue s = infoStream(ctx, ctx.arg(2));
                StreamGroup g = s.group(ctx.arg(3));
                if (g == null) {
                    throw noGroupFor(ctx.arg(2), ctx.arg(3));
                }
                List<StreamGroup.Consumer> cs = new ArrayList<>(g.consumers());
                cs.sort(BY_NAME);
                long now = ctx.now();
                ctx.arrayHeader(cs.size());
                for (StreamGroup.Consumer c : cs) {
                    ctx.arrayHeader(8);
                    ctx.bulk("name");
                    ctx.bulk(c.name());
                    ctx.bulk("pending");
                    ctx.integer(c.pending().size());
                    ctx.bulk("idle");
                    ctx.integer(now - c.seenTime());
                    ctx.bulk("inactive");
                    ctx.integer(c.activeTime() < 0 ? -1 : now - c.activeTime());
                }
                break;
            }
            default:
                throw new CommandException("ERR unknown subcommand '" + ctx.argString(1) + "'. Try XINFO HELP.");
        }
    }

    private static StreamValue infoStream(CommandContext ctx, byte[] key) {
        KeyEntry e = Keys.read(ctx, key, KeyEntry.STREAM);
        if (e == null) {
            throw CommandException.NO_SUCH_KEY;
        }
        return e.stream();
    }

    private static void edge(CommandContext ctx, StreamValue s, boolean last) {
        Object[][] found = new Object[1][];
        s.range(StreamId.MIN, StreamId.MAX, last, 1, (ms, seq, fields) -> {
            found[0] = new Object[] {new StreamId(ms, seq), fields};
            return false;
        });
        if (found[0] == null) {
            ctx.nullArray();
        } else {
            StreamCommands.replyEntry(ctx, (StreamId) found[0][0], (byte[][]) found[0][1]);
        }
    }
}
