package com.jredis.server.command;

import com.jredis.common.Glob;
import com.jredis.server.core.CommandContext;
import com.jredis.server.db.Db;
import com.jredis.server.db.KeyEntry;
import com.jredis.server.db.MemberEntry;
import com.jredis.server.db.SetValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.jredis.server.command.CommandSpec.DENYOOM;
import static com.jredis.server.command.CommandSpec.FAST;
import static com.jredis.server.command.CommandSpec.READONLY;
import static com.jredis.server.command.CommandSpec.WRITE;

/** Set commands. */
final class SetCommands {

    private static final int INTER = 0;
    private static final int UNION = 1;
    private static final int DIFF = 2;

    private SetCommands() {
    }

    static void register(CommandTable t) {
        t.register("SADD", -3, WRITE | DENYOOM | FAST, SetCommands::sadd);
        t.register("SREM", -3, WRITE | FAST, SetCommands::srem);
        t.register("SISMEMBER", 3, READONLY | FAST, SetCommands::sismember);
        t.register("SMISMEMBER", -3, READONLY | FAST, SetCommands::smismember);
        t.register("SMEMBERS", 2, READONLY, SetCommands::smembers);
        t.register("SCARD", 2, READONLY | FAST, SetCommands::scard);
        t.register("SPOP", -2, WRITE | FAST, SetCommands::spop);
        t.register("SRANDMEMBER", -2, READONLY, SetCommands::srandmember);
        t.register("SMOVE", 4, WRITE | FAST, SetCommands::smove);
        t.register("SINTER", -2, READONLY, ctx -> combine(ctx, INTER, false));
        t.register("SUNION", -2, READONLY, ctx -> combine(ctx, UNION, false));
        t.register("SDIFF", -2, READONLY, ctx -> combine(ctx, DIFF, false));
        t.register("SINTERSTORE", -3, WRITE | DENYOOM, ctx -> combine(ctx, INTER, true));
        t.register("SUNIONSTORE", -3, WRITE | DENYOOM, ctx -> combine(ctx, UNION, true));
        t.register("SDIFFSTORE", -3, WRITE | DENYOOM, ctx -> combine(ctx, DIFF, true));
        t.register("SINTERCARD", -3, READONLY, SetCommands::sintercard);
        t.register("SSCAN", -3, READONLY, SetCommands::sscan);
    }

    static void sadd(CommandContext ctx) {
        byte[] key = ctx.arg(1);
        KeyEntry e = Keys.write(ctx, key, KeyEntry.SET);
        SetValue set;
        if (e == null) {
            set = new SetValue(ctx.db().hasher());
            ctx.db().add(key, KeyEntry.SET, set);
        } else {
            set = e.set();
        }
        int added = 0;
        for (int i = 2; i < ctx.argc(); i++) {
            if (set.add(ctx.arg(i))) {
                added++;
            }
        }
        if (added > 0) {
            ctx.db().signalModified(key);
            ctx.propagateAsIs();
        }
        ctx.integer(added);
    }

    static void srem(CommandContext ctx) {
        byte[] key = ctx.arg(1);
        KeyEntry e = Keys.write(ctx, key, KeyEntry.SET);
        if (e == null) {
            ctx.integer(0);
            return;
        }
        int removed = 0;
        for (int i = 2; i < ctx.argc(); i++) {
            if (e.set().remove(ctx.arg(i))) {
                removed++;
            }
        }
        if (removed > 0) {
            afterRemoval(ctx, e, key);
            ctx.propagateAsIs();
        }
        ctx.integer(removed);
    }

    private static void afterRemoval(CommandContext ctx, KeyEntry e, byte[] key) {
        if (e.set().size() == 0) {
            ctx.db().deleteEntry(e);
        } else {
            ctx.db().signalModified(key);
        }
    }

    static void sismember(CommandContext ctx) {
        KeyEntry e = Keys.read(ctx, ctx.arg(1), KeyEntry.SET);
        ctx.bool(e != null && e.set().contains(ctx.arg(2)));
    }

    static void smismember(CommandContext ctx) {
        KeyEntry e = Keys.read(ctx, ctx.arg(1), KeyEntry.SET);
        ctx.arrayHeader(ctx.argc() - 2);
        for (int i = 2; i < ctx.argc(); i++) {
            ctx.bool(e != null && e.set().contains(ctx.arg(i)));
        }
    }

    static void smembers(CommandContext ctx) {
        KeyEntry e = Keys.read(ctx, ctx.arg(1), KeyEntry.SET);
        if (e == null) {
            ctx.emptyArray();
            return;
        }
        ctx.arrayHeader(e.set().size());
        e.set().forEach(m -> ctx.bulk(m.key));
    }

    static void scard(CommandContext ctx) {
        KeyEntry e = Keys.read(ctx, ctx.arg(1), KeyEntry.SET);
        ctx.integer(e == null ? 0 : e.set().size());
    }

    static void spop(CommandContext ctx) {
        byte[] key = ctx.arg(1);
        long count = -1;
        if (ctx.argc() == 3) {
            count = ctx.longArg(2);
            if (count < 0) {
                throw new CommandException("ERR value is out of range, must be positive");
            }
        } else if (ctx.argc() > 3) {
            throw CommandException.SYNTAX;
        }
        KeyEntry e = Keys.write(ctx, key, KeyEntry.SET);
        if (e == null) {
            if (count < 0) {
                ctx.nullBulk();
            } else {
                ctx.emptyArray();
            }
            return;
        }
        SetValue set = e.set();
        long n = count < 0 ? 1 : Math.min(count, set.size());
        List<byte[]> popped = new ArrayList<>((int) n);
        for (long i = 0; i < n; i++) {
            byte[] m = set.random(ctx.db().random());
            set.remove(m);
            popped.add(m);
        }
        if (count < 0) {
            ctx.bulk(popped.get(0));
        } else {
            ctx.arrayHeader(popped.size());
            for (byte[] m : popped) {
                ctx.bulk(m);
            }
        }
        if (!popped.isEmpty()) {
            boolean emptied = e.set().size() == 0;
            afterRemoval(ctx, e, key);
            if (emptied) {
                ctx.propagate(Words.DEL, key);
            } else {
                // SREM in batches of 1024 members, atomic as one MULTI/EXEC: one record with a million
                // members would exceed what the AOF loader accepts in a single command
                ctx.engine().effects().beginTransaction();
                for (int from = 0; from < popped.size(); from += 1024) {
                    int batch = Math.min(1024, popped.size() - from);
                    byte[][] effect = new byte[batch + 2][];
                    effect[0] = Words.SREM;
                    effect[1] = key;
                    for (int i = 0; i < batch; i++) {
                        effect[i + 2] = popped.get(from + i);
                    }
                    ctx.propagate(effect);
                }
                ctx.engine().effects().endTransaction();
            }
        }
    }

    static void srandmember(CommandContext ctx) {
        if (ctx.argc() > 3) {
            throw CommandException.SYNTAX;
        }
        KeyEntry e = Keys.read(ctx, ctx.arg(1), KeyEntry.SET);
        if (ctx.argc() == 2) {
            ctx.bulkOrNull(e == null ? null : e.set().random(ctx.db().random()));
            return;
        }
        long count = ctx.longArg(2);
        if (e == null || count == 0) {
            ctx.emptyArray();
            return;
        }
        final SetValue set = e.set();
        List<byte[]> picked = RandomSample.pick(set.size(), count, ctx.db().random(), set::random, () -> {
            List<byte[]> all = new ArrayList<>(set.size());
            set.forEach(m -> all.add(m.key));
            return all;
        }, b -> b);
        ctx.arrayHeader(picked.size());
        for (byte[] m : picked) {
            ctx.bulk(m);
        }
    }

    static void smove(CommandContext ctx) {
        byte[] src = ctx.arg(1);
        byte[] dst = ctx.arg(2);
        byte[] member = ctx.arg(3);
        KeyEntry s = Keys.read(ctx, src, KeyEntry.SET);
        if (s == null) {
            ctx.integer(0);                     // a missing source first, whatever dst is (as Redis)
            return;
        }
        Keys.checkType(ctx, dst, KeyEntry.SET);
        if (!s.set().contains(member)) {
            ctx.integer(0);
            return;
        }
        if (Arrays.equals(src, dst)) {
            ctx.integer(1);
            return;
        }
        Db db = ctx.db();
        db.prepareWrite(s);
        s.set().remove(member);
        afterRemoval(ctx, s, src);
        KeyEntry d = Keys.write(ctx, dst, KeyEntry.SET);
        SetValue to;
        if (d == null) {
            to = new SetValue(db.hasher());
            db.add(dst, KeyEntry.SET, to);
        } else {
            to = d.set();
        }
        to.add(member);
        db.signalModified(dst);
        ctx.propagateAsIs();
        ctx.integer(1);
    }

    /** The sets named by argv[from..], missing keys as null; WRONGTYPE checked for all first. */
    private static List<SetValue> sets(CommandContext ctx, int from, int to) {
        List<SetValue> out = new ArrayList<>();
        for (int i = from; i < to; i++) {
            KeyEntry e = Keys.read(ctx, ctx.arg(i), KeyEntry.SET);
            out.add(e == null ? null : e.set());
        }
        return out;
    }

    /** Computes the result as a detached set (not yet stored). */
    private static SetValue compute(CommandContext ctx, List<SetValue> sets, int op, long limit) {
        SetValue result = new SetValue(ctx.db().hasher());
        switch (op) {
            case INTER: {
                SetValue smallest = null;
                for (SetValue s : sets) {
                    if (s == null) {
                        return result;          // any missing key -> empty intersection
                    }
                    if (smallest == null || s.size() < smallest.size()) {
                        smallest = s;
                    }
                }
                final SetValue base = smallest;
                List<byte[]> members = new ArrayList<>();
                base.forEach(m -> members.add(m.key));
                for (byte[] m : members) {
                    boolean inAll = true;
                    for (SetValue s : sets) {
                        if (s != base && !s.contains(m)) {
                            inAll = false;
                            break;
                        }
                    }
                    if (inAll) {
                        result.add(m);
                        if (limit > 0 && result.size() >= limit) {
                            break;
                        }
                    }
                }
                return result;
            }
            case UNION:
                for (SetValue s : sets) {
                    if (s != null) {
                        s.forEach(m -> result.add(m.key));
                    }
                }
                return result;
            default: {
                SetValue first = sets.get(0);
                if (first == null) {
                    return result;
                }
                first.forEach(m -> {
                    for (int i = 1; i < sets.size(); i++) {
                        SetValue s = sets.get(i);
                        if (s != null && s.contains(m.key)) {
                            return;
                        }
                    }
                    result.add(m.key);
                });
                return result;
            }
        }
    }

    static void combine(CommandContext ctx, int op, boolean store) {
        int from = store ? 2 : 1;
        List<SetValue> sets = sets(ctx, from, ctx.argc());
        SetValue result = compute(ctx, sets, op, 0);
        if (!store) {
            ctx.arrayHeader(result.size());
            result.forEach(m -> ctx.bulk(m.key));
            return;
        }
        byte[] dst = ctx.arg(1);
        Db db = ctx.db();
        if (result.size() == 0) {
            if (db.delete(dst)) {
                ctx.propagate(Words.DEL, dst);
            }
            ctx.integer(0);
            return;
        }
        db.setValue(dst, KeyEntry.SET, result, false);
        ctx.propagateAsIs();
        ctx.integer(result.size());
    }

    static void sintercard(CommandContext ctx) {
        long numkeys = ctx.longArg(1);
        if (numkeys <= 0) {
            throw new CommandException("ERR numkeys should be greater than 0");
        }
        if (numkeys > ctx.argc() - 2) {
            throw new CommandException("ERR Number of keys can't be greater than number of args");
        }
        int end = 2 + (int) numkeys;
        long limit = 0;
        if (end < ctx.argc()) {
            if (end + 2 != ctx.argc() || !ctx.argIs(end, "LIMIT")) {
                throw CommandException.SYNTAX;
            }
            limit = ctx.longArg(end + 1);
            if (limit < 0) {
                throw new CommandException("ERR LIMIT can't be negative");
            }
        }
        ctx.integer(compute(ctx, sets(ctx, 2, end), INTER, limit).size());
    }

    static void sscan(CommandContext ctx) {
        ScanArgs a = ScanArgs.parse(ctx, 2, false);
        KeyEntry e = Keys.read(ctx, ctx.arg(1), KeyEntry.SET);
        if (e == null) {
            ScanArgs.replyCursor(ctx, 0);
            ctx.emptyArray();
            return;
        }
        List<MemberEntry> found = new ArrayList<>();
        long cursor = a.cursor;
        long budget = a.count * 10;
        do {
            cursor = e.set().scan(cursor, found::add);
        } while (cursor != 0 && found.size() < a.count && --budget > 0);
        List<byte[]> out = new ArrayList<>();
        for (MemberEntry m : found) {
            if (a.match == null || Glob.match(a.match, m.key)) {
                out.add(m.key);
            }
        }
        ScanArgs.replyCursor(ctx, cursor);
        ctx.arrayHeader(out.size());
        for (byte[] m : out) {
            ctx.bulk(m);
        }
    }
}
