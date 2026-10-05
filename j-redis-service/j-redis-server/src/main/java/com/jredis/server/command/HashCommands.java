package com.jredis.server.command;

import com.jredis.common.Glob;
import com.jredis.common.NumberCodec;
import com.jredis.server.core.CommandContext;
import com.jredis.server.db.Db;
import com.jredis.server.db.FieldEntry;
import com.jredis.server.db.HashValue;
import com.jredis.server.db.KeyEntry;

import java.util.ArrayList;
import java.util.List;

import static com.jredis.server.command.CommandSpec.DENYOOM;
import static com.jredis.server.command.CommandSpec.FAST;
import static com.jredis.server.command.CommandSpec.READONLY;
import static com.jredis.server.command.CommandSpec.WRITE;

/** Hash commands. */
final class HashCommands {

    private HashCommands() {
    }

    static void register(CommandTable t) {
        t.register("HSET", -4, WRITE | DENYOOM | FAST, ctx -> hset(ctx, false));
        t.register("HMSET", -4, WRITE | DENYOOM | FAST, ctx -> hset(ctx, true));
        t.register("HSETNX", 4, WRITE | DENYOOM | FAST, HashCommands::hsetnx);
        t.register("HGET", 3, READONLY | FAST, HashCommands::hget);
        t.register("HMGET", -3, READONLY | FAST, HashCommands::hmget);
        t.register("HGETALL", 2, READONLY, HashCommands::hgetall);
        t.register("HDEL", -3, WRITE | FAST, HashCommands::hdel);
        t.register("HLEN", 2, READONLY | FAST, HashCommands::hlen);
        t.register("HEXISTS", 3, READONLY | FAST, HashCommands::hexists);
        t.register("HKEYS", 2, READONLY, ctx -> keysOrValues(ctx, true));
        t.register("HVALS", 2, READONLY, ctx -> keysOrValues(ctx, false));
        t.register("HINCRBY", 4, WRITE | DENYOOM | FAST, HashCommands::hincrby);
        t.register("HINCRBYFLOAT", 4, WRITE | DENYOOM | FAST, HashCommands::hincrbyfloat);
        t.register("HSTRLEN", 3, READONLY | FAST, HashCommands::hstrlen);
        t.register("HSCAN", -3, READONLY, HashCommands::hscan);
        t.register("HRANDFIELD", -2, READONLY, HashCommands::hrandfield);
    }

    /** The hash at key prepared for writing, created if absent. */
    private static HashValue hashForWrite(CommandContext ctx, byte[] key) {
        KeyEntry e = Keys.write(ctx, key, KeyEntry.HASH);
        if (e != null) {
            return e.hash();
        }
        HashValue h = new HashValue(ctx.db().hasher());
        ctx.db().add(key, KeyEntry.HASH, h);
        return h;
    }

    static void hset(CommandContext ctx, boolean hmset) {
        if (ctx.argc() % 2 != 0) {
            throw new CommandException("ERR wrong number of arguments for '" + ctx.spec().name.toLowerCase() + "' command");
        }
        byte[] key = ctx.arg(1);
        HashValue h = hashForWrite(ctx, key);
        int added = 0;
        for (int i = 2; i < ctx.argc(); i += 2) {
            if (h.put(ctx.arg(i), ctx.arg(i + 1))) {
                added++;
            }
        }
        ctx.db().signalModified(key);
        byte[][] effect = ctx.argv().clone();
        effect[0] = Words.HSET;
        ctx.propagate(effect);
        if (hmset) {
            ctx.ok();
        } else {
            ctx.integer(added);
        }
    }

    static void hsetnx(CommandContext ctx) {
        byte[] key = ctx.arg(1);
        KeyEntry e = Keys.read(ctx, key, KeyEntry.HASH);
        if (e != null && e.hash().contains(ctx.arg(2))) {
            ctx.integer(0);
            return;
        }
        hashForWrite(ctx, key).put(ctx.arg(2), ctx.arg(3));
        ctx.db().signalModified(key);
        ctx.propagate(Words.HSET, key, ctx.arg(2), ctx.arg(3));
        ctx.integer(1);
    }

    static void hget(CommandContext ctx) {
        KeyEntry e = Keys.read(ctx, ctx.arg(1), KeyEntry.HASH);
        ctx.bulkOrNull(e == null ? null : e.hash().get(ctx.arg(2)));
    }

    static void hmget(CommandContext ctx) {
        KeyEntry e = Keys.read(ctx, ctx.arg(1), KeyEntry.HASH);
        ctx.arrayHeader(ctx.argc() - 2);
        for (int i = 2; i < ctx.argc(); i++) {
            ctx.bulkOrNull(e == null ? null : e.hash().get(ctx.arg(i)));
        }
    }

    static void hgetall(CommandContext ctx) {
        KeyEntry e = Keys.read(ctx, ctx.arg(1), KeyEntry.HASH);
        if (e == null) {
            ctx.emptyArray();
            return;
        }
        HashValue h = e.hash();
        ctx.arrayHeader(2L * h.size());
        h.forEach(f -> {
            ctx.bulk(f.field());
            ctx.bulk(f.value());
        });
    }

    static void hdel(CommandContext ctx) {
        byte[] key = ctx.arg(1);
        KeyEntry e = Keys.write(ctx, key, KeyEntry.HASH);
        if (e == null) {
            ctx.integer(0);
            return;
        }
        HashValue h = e.hash();
        int removed = 0;
        for (int i = 2; i < ctx.argc(); i++) {
            if (h.remove(ctx.arg(i))) {
                removed++;
            }
        }
        if (removed > 0) {
            if (h.size() == 0) {
                ctx.db().deleteEntry(e);
            } else {
                ctx.db().signalModified(key);
            }
            ctx.propagateAsIs();
        }
        ctx.integer(removed);
    }

    static void hlen(CommandContext ctx) {
        KeyEntry e = Keys.read(ctx, ctx.arg(1), KeyEntry.HASH);
        ctx.integer(e == null ? 0 : e.hash().size());
    }

    static void hexists(CommandContext ctx) {
        KeyEntry e = Keys.read(ctx, ctx.arg(1), KeyEntry.HASH);
        ctx.bool(e != null && e.hash().contains(ctx.arg(2)));
    }

    static void keysOrValues(CommandContext ctx, boolean keys) {
        KeyEntry e = Keys.read(ctx, ctx.arg(1), KeyEntry.HASH);
        if (e == null) {
            ctx.emptyArray();
            return;
        }
        ctx.arrayHeader(e.hash().size());
        e.hash().forEach(f -> ctx.bulk(keys ? f.field() : f.value()));
    }

    static void hincrby(CommandContext ctx) {
        byte[] key = ctx.arg(1);
        byte[] field = ctx.arg(2);
        long incr = ctx.longArg(3);
        KeyEntry e = Keys.read(ctx, key, KeyEntry.HASH);
        long current = 0;
        byte[] cur = e == null ? null : e.hash().get(field);
        if (cur != null) {
            try {
                current = NumberCodec.parseLong(cur);
            } catch (NumberFormatException ex) {
                throw new CommandException("ERR hash value is not an integer");
            }
        }
        long result;
        try {
            result = Math.addExact(current, incr);
        } catch (ArithmeticException ex) {
            throw CommandException.OVERFLOW;
        }
        hashForWrite(ctx, key).put(field, NumberCodec.toBytes(result));
        ctx.db().signalModified(key);
        ctx.propagateAsIs();
        ctx.integer(result);
    }

    static void hincrbyfloat(CommandContext ctx) {
        byte[] key = ctx.arg(1);
        byte[] field = ctx.arg(2);
        ctx.doubleArg(3);                                   // validates the increment
        KeyEntry e = Keys.read(ctx, key, KeyEntry.HASH);
        byte[] cur = e == null ? null : e.hash().get(field);
        if (cur != null) {
            try {
                NumberCodec.parseDouble(cur);
            } catch (NumberFormatException ex) {
                throw new CommandException("ERR hash value is not a float");
            }
        }
        byte[] bytes = NumberCodec.addFloats(cur == null ? new byte[]{'0'} : cur, ctx.arg(3));
        if (bytes == null) {
            throw new CommandException("ERR increment would produce NaN or Infinity");
        }
        hashForWrite(ctx, key).put(field, bytes);
        ctx.db().signalModified(key);
        ctx.propagate(Words.HSET, key, field, bytes);
        ctx.bulk(bytes);
    }

    static void hstrlen(CommandContext ctx) {
        KeyEntry e = Keys.read(ctx, ctx.arg(1), KeyEntry.HASH);
        byte[] v = e == null ? null : e.hash().get(ctx.arg(2));
        ctx.integer(v == null ? 0 : v.length);
    }

    static void hscan(CommandContext ctx) {
        ScanArgs a = ScanArgs.parse(ctx, 2, false);
        KeyEntry e = Keys.read(ctx, ctx.arg(1), KeyEntry.HASH);
        if (e == null) {
            ScanArgs.replyCursor(ctx, 0);
            ctx.emptyArray();
            return;
        }
        List<FieldEntry> found = new ArrayList<>();
        long cursor = a.cursor;
        long budget = a.count * 10;
        do {
            cursor = e.hash().scan(cursor, found::add);
        } while (cursor != 0 && found.size() < a.count && --budget > 0);
        List<FieldEntry> out = new ArrayList<>(found.size());
        for (FieldEntry f : found) {
            if (a.match == null || Glob.match(a.match, f.field())) {
                out.add(f);
            }
        }
        ScanArgs.replyCursor(ctx, cursor);
        ctx.arrayHeader(2L * out.size());
        for (FieldEntry f : out) {
            ctx.bulk(f.field());
            ctx.bulk(f.value());
        }
    }

    static void hrandfield(CommandContext ctx) {
        KeyEntry e;
        if (ctx.argc() == 2) {
            e = Keys.read(ctx, ctx.arg(1), KeyEntry.HASH);
            ctx.bulkOrNull(e == null ? null : e.hash().random(ctx.db().random()).field());
            return;
        }
        long count = ctx.longArg(2);
        boolean withValues = false;
        if (ctx.argc() == 4 && ctx.argIs(3, "WITHVALUES")) {
            withValues = true;
        } else if (ctx.argc() != 3) {
            throw CommandException.SYNTAX;
        }
        e = Keys.read(ctx, ctx.arg(1), KeyEntry.HASH);
        if (e == null || count == 0) {
            ctx.emptyArray();
            return;
        }
        final HashValue h = e.hash();
        List<FieldEntry> picked = RandomSample.pick(h.size(), count, ctx.db().random(),
                h::random, () -> {
                    List<FieldEntry> all = new ArrayList<>(h.size());
                    h.forEach(all::add);
                    return all;
                }, FieldEntry::field);
        ctx.arrayHeader(withValues ? 2L * picked.size() : picked.size());
        for (FieldEntry f : picked) {
            ctx.bulk(f.field());
            if (withValues) {
                ctx.bulk(f.value());
            }
        }
    }
}
