package com.jredis.server.command;

import com.jredis.server.blocking.BlockState;
import com.jredis.server.blocking.BlockingServer;
import com.jredis.server.core.CommandContext;
import com.jredis.server.core.Engine;
import com.jredis.server.db.Db;
import com.jredis.server.db.KeyEntry;
import com.jredis.server.db.ListValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.jredis.server.command.CommandSpec.BLOCKING;
import static com.jredis.server.command.CommandSpec.DENYOOM;
import static com.jredis.server.command.CommandSpec.FAST;
import static com.jredis.server.command.CommandSpec.READONLY;
import static com.jredis.server.command.CommandSpec.WRITE;

/** List commands, including the blocking pops. */
final class ListCommands {

    private ListCommands() {
    }

    static void register(CommandTable t) {
        t.register("LPUSH", -3, WRITE | DENYOOM | FAST, ctx -> push(ctx, true, false));
        t.register("RPUSH", -3, WRITE | DENYOOM | FAST, ctx -> push(ctx, false, false));
        t.register("LPUSHX", -3, WRITE | DENYOOM | FAST, ctx -> push(ctx, true, true));
        t.register("RPUSHX", -3, WRITE | DENYOOM | FAST, ctx -> push(ctx, false, true));
        t.register("LPOP", -2, WRITE | FAST, ctx -> pop(ctx, true));
        t.register("RPOP", -2, WRITE | FAST, ctx -> pop(ctx, false));
        t.register("LLEN", 2, READONLY | FAST, ListCommands::llen);
        t.register("LRANGE", 4, READONLY, ListCommands::lrange);
        t.register("LINDEX", 3, READONLY, ListCommands::lindex);
        t.register("LSET", 4, WRITE | DENYOOM, ListCommands::lset);
        t.register("LINSERT", 5, WRITE | DENYOOM, ListCommands::linsert);
        t.register("LREM", 4, WRITE, ListCommands::lrem);
        t.register("LTRIM", 4, WRITE, ListCommands::ltrim);
        t.register("LPOS", -3, READONLY, ListCommands::lpos);
        t.register("LMOVE", 5, WRITE | DENYOOM, ListCommands::lmove);
        t.register("RPOPLPUSH", 3, WRITE | DENYOOM, ctx -> move(ctx, ctx.arg(1), ctx.arg(2), false, true));
        t.register("BLPOP", -3, WRITE | BLOCKING, ctx -> bpop(ctx, true));
        t.register("BRPOP", -3, WRITE | BLOCKING, ctx -> bpop(ctx, false));
        t.register("BLMOVE", 6, WRITE | DENYOOM | BLOCKING, ListCommands::blmove);
        t.register("BRPOPLPUSH", 4, WRITE | DENYOOM | BLOCKING, ListCommands::brpoplpush);
    }

    static void push(CommandContext ctx, boolean left, boolean onlyIfExists) {
        byte[] key = ctx.arg(1);
        KeyEntry e = Keys.write(ctx, key, KeyEntry.LIST);
        ListValue list;
        if (e == null) {
            if (onlyIfExists) {
                ctx.integer(0);
                return;
            }
            list = new ListValue();
            ctx.db().add(key, KeyEntry.LIST, list);
        } else {
            list = e.list();
        }
        for (int i = 2; i < ctx.argc(); i++) {
            if (left) {
                list.addFirst(ctx.arg(i));
            } else {
                list.addLast(ctx.arg(i));
            }
        }
        ctx.db().signalModified(key);
        ctx.propagateAsIs();
        ctx.integer(list.size());
    }

    static void pop(CommandContext ctx, boolean left) {
        byte[] key = ctx.arg(1);
        long count = -1;
        if (ctx.argc() == 3) {
            count = ctx.longArg(2);
            if (count < 0) {
                throw new CommandException("ERR value is out of range, must be positive");
            }
        } else if (ctx.argc() > 3) {
            throw new CommandException("ERR wrong number of arguments for '" + ctx.spec().name.toLowerCase() + "' command");
        }
        KeyEntry e = Keys.write(ctx, key, KeyEntry.LIST);
        if (e == null) {
            if (count < 0) {
                ctx.nullBulk();
            } else {
                ctx.nullArray();
            }
            return;
        }
        ListValue list = e.list();
        if (count < 0) {
            ctx.bulk(left ? list.pollFirst() : list.pollLast());
        } else {
            long n = Math.min(count, list.size());
            ctx.arrayHeader(n);
            for (long i = 0; i < n; i++) {
                ctx.bulk(left ? list.pollFirst() : list.pollLast());
            }
            if (n == 0) {
                return;
            }
        }
        afterRemoval(ctx, e, key);
        ctx.propagateAsIs();
    }

    private static void afterRemoval(CommandContext ctx, KeyEntry e, byte[] key) {
        if (e.list().size() == 0) {
            ctx.db().deleteEntry(e);
        } else {
            ctx.db().signalModified(key);
        }
    }

    static void llen(CommandContext ctx) {
        KeyEntry e = Keys.read(ctx, ctx.arg(1), KeyEntry.LIST);
        ctx.integer(e == null ? 0 : e.list().size());
    }

    static void lrange(CommandContext ctx) {
        long start = ctx.longArg(2);
        long end = ctx.longArg(3);
        KeyEntry e = Keys.read(ctx, ctx.arg(1), KeyEntry.LIST);
        if (e == null) {
            ctx.emptyArray();
            return;
        }
        ListValue list = e.list();
        long[] r = Keys.range(start, end, list.size());
        if (r == null) {
            ctx.emptyArray();
            return;
        }
        ctx.arrayHeader(r[1] - r[0] + 1);
        for (long i = r[0]; i <= r[1]; i++) {
            ctx.bulk(list.get((int) i));
        }
    }

    private static long normalizeIndex(long index, int size) {
        return index < 0 ? size + index : index;
    }

    static void lindex(CommandContext ctx) {
        KeyEntry e = Keys.read(ctx, ctx.arg(1), KeyEntry.LIST);
        if (e == null) {
            ctx.nullBulk();                     // before parsing the index, as Redis
            return;
        }
        long index = ctx.longArg(2);
        long i = normalizeIndex(index, e.list().size());
        ctx.bulkOrNull(i < 0 || i >= e.list().size() ? null : e.list().get((int) i));
    }

    static void lset(CommandContext ctx) {
        byte[] key = ctx.arg(1);
        long index = ctx.longArg(2);
        KeyEntry peek = Keys.read(ctx, key, KeyEntry.LIST);
        if (peek == null) {
            throw CommandException.NO_SUCH_KEY;
        }
        long i = normalizeIndex(index, peek.list().size());
        if (i < 0 || i >= peek.list().size()) {
            throw CommandException.INDEX_OUT_OF_RANGE;
        }
        KeyEntry e = Keys.write(ctx, key, KeyEntry.LIST);
        e.list().set((int) i, ctx.arg(3));
        ctx.db().signalModified(key);
        ctx.propagateAsIs();
        ctx.ok();
    }

    static void linsert(CommandContext ctx) {
        boolean before;
        if (ctx.argIs(2, "BEFORE")) {
            before = true;
        } else if (ctx.argIs(2, "AFTER")) {
            before = false;
        } else {
            throw CommandException.SYNTAX;
        }
        byte[] key = ctx.arg(1);
        KeyEntry peek = Keys.read(ctx, key, KeyEntry.LIST);
        if (peek == null) {
            ctx.integer(0);
            return;
        }
        int idx = peek.list().indexOf(ctx.arg(3), 0);
        if (idx < 0) {
            ctx.integer(-1);
            return;
        }
        KeyEntry e = Keys.write(ctx, key, KeyEntry.LIST);
        e.list().insert(before ? idx : idx + 1, ctx.arg(4));
        ctx.db().signalModified(key);
        ctx.propagateAsIs();
        ctx.integer(e.list().size());
    }

    static void lrem(CommandContext ctx) {
        byte[] key = ctx.arg(1);
        long count = ctx.longArg(2);
        KeyEntry e = Keys.write(ctx, key, KeyEntry.LIST);
        if (e == null) {
            ctx.integer(0);
            return;
        }
        int removed = e.list().removeMatching(ctx.arg(3), count);
        if (removed > 0) {
            afterRemoval(ctx, e, key);
            ctx.propagateAsIs();
        }
        ctx.integer(removed);
    }

    static void ltrim(CommandContext ctx) {
        byte[] key = ctx.arg(1);
        long start = ctx.longArg(2);
        long end = ctx.longArg(3);
        KeyEntry e = Keys.write(ctx, key, KeyEntry.LIST);
        if (e == null) {
            ctx.ok();
            return;
        }
        ListValue list = e.list();
        int size = list.size();
        if (start < 0) {
            start = size + start;
        }
        if (end < 0) {
            end = size + end;
        }
        if (start < 0) {
            start = 0;
        }
        boolean changed;
        if (start > end || start >= size) {
            ctx.db().deleteEntry(e);
            changed = true;
        } else {
            if (end >= size) {
                end = size - 1;
            }
            changed = start > 0 || end < size - 1;
            if (changed) {
                list.trim((int) start, (int) end);
                ctx.db().signalModified(key);
            }
        }
        if (changed) {
            ctx.propagateAsIs();
        }
        ctx.ok();
    }

    static void lpos(CommandContext ctx) {
        long rank = 1;
        long count = -1;
        long maxlen = 0;
        for (int i = 3; i < ctx.argc(); i += 2) {
            if (i + 1 >= ctx.argc()) {
                throw CommandException.SYNTAX;
            }
            if (ctx.argIs(i, "RANK")) {
                rank = ctx.longArg(i + 1);
                if (rank == 0) {
                    throw new CommandException("ERR RANK can't be zero: use 1 to start from the first match, "
                            + "2 from the second ... or use negative to start from the last match");
                }
                if (rank == Long.MIN_VALUE) {
                    throw new CommandException("ERR value is out of range");
                }
            } else if (ctx.argIs(i, "COUNT")) {
                count = ctx.longArg(i + 1);
                if (count < 0) {
                    throw new CommandException("ERR COUNT can't be negative");
                }
            } else if (ctx.argIs(i, "MAXLEN")) {
                maxlen = ctx.longArg(i + 1);
                if (maxlen < 0) {
                    throw new CommandException("ERR MAXLEN can't be negative");
                }
            } else {
                throw CommandException.SYNTAX;
            }
        }
        KeyEntry e = Keys.read(ctx, ctx.arg(1), KeyEntry.LIST);
        if (e == null) {
            if (count >= 0) {
                ctx.emptyArray();
            } else {
                ctx.nullBulk();
            }
            return;
        }
        ListValue list = e.list();
        byte[] element = ctx.arg(2);
        int size = list.size();
        boolean forward = rank > 0;
        long skip = Math.abs(rank) - 1;
        long limit = count == 0 ? Long.MAX_VALUE : (count < 0 ? 1 : count);
        List<Long> matches = new ArrayList<>();
        long examined = 0;
        for (int step = 0; step < size && matches.size() < limit; step++) {
            if (maxlen > 0 && examined >= maxlen) {
                break;
            }
            examined++;
            int idx = forward ? step : size - 1 - step;
            if (Arrays.equals(list.get(idx), element)) {
                if (skip > 0) {
                    skip--;
                } else {
                    matches.add((long) idx);
                }
            }
        }
        if (count >= 0) {
            ctx.arrayHeader(matches.size());
            for (long m : matches) {
                ctx.integer(m);
            }
        } else if (matches.isEmpty()) {
            ctx.nullBulk();
        } else {
            ctx.integer(matches.get(0));
        }
    }

    private static boolean parseDirection(CommandContext ctx, int i) {
        if (ctx.argIs(i, "LEFT")) {
            return true;
        }
        if (ctx.argIs(i, "RIGHT")) {
            return false;
        }
        throw CommandException.SYNTAX;
    }

    static void lmove(CommandContext ctx) {
        move(ctx, ctx.arg(1), ctx.arg(2), parseDirection(ctx, 3), parseDirection(ctx, 4));
    }

    /** LMOVE / RPOPLPUSH, and the non-blocking path of BLMOVE. Validates both keys before changing either. */
    static void move(CommandContext ctx, byte[] src, byte[] dst, boolean fromLeft, boolean toLeft) {
        KeyEntry s = Keys.read(ctx, src, KeyEntry.LIST);
        if (s == null) {
            ctx.nullBulk();                     // a missing source first, whatever dst is (as Redis)
            return;
        }
        Keys.checkType(ctx, dst, KeyEntry.LIST);
        byte[] v = BlockingServer.moveElement(ctx.db(), s, src, dst, fromLeft, toLeft);
        ctx.propagate(Words.LMOVE, src, dst, fromLeft ? Words.LEFT : Words.RIGHT, toLeft ? Words.LEFT : Words.RIGHT);
        ctx.bulk(v);
    }

    static void bpop(CommandContext ctx, boolean left) {
        long deadline = Keys.blockingDeadline(ctx, ctx.argc() - 1);
        byte[][] keys = Arrays.copyOfRange(ctx.argv(), 1, ctx.argc() - 1);
        Db db = ctx.db();
        for (byte[] key : keys) {
            KeyEntry e = db.lookupRead(key);
            if (e == null) {
                continue;
            }
            if (e.type() != KeyEntry.LIST) {
                throw CommandException.WRONGTYPE;
            }
            if (e.list().size() > 0) {
                db.prepareWrite(e);
                byte[] v = left ? e.list().pollFirst() : e.list().pollLast();
                afterRemoval(ctx, e, key);
                ctx.propagate(left ? Words.LPOP : Words.RPOP, key);
                ctx.arrayHeader(2);
                ctx.bulk(key);
                ctx.bulk(v);
                return;
            }
        }
        Engine engine = ctx.engine();
        if (engine.inExec() || db.isLoading()) {
            ctx.nullArray();
            return;
        }
        engine.blocking().block(ctx.client(), new BlockState(left ? BlockState.BLPOP : BlockState.BRPOP, keys, deadline, null, false, false));
    }

    static void blmove(CommandContext ctx) {
        blockingMove(ctx, ctx.arg(1), ctx.arg(2), parseDirection(ctx, 3), parseDirection(ctx, 4), 5);
    }

    static void brpoplpush(CommandContext ctx) {
        blockingMove(ctx, ctx.arg(1), ctx.arg(2), false, true, 3);
    }

    private static void blockingMove(CommandContext ctx, byte[] src, byte[] dst, boolean fromLeft, boolean toLeft, int timeoutIdx) {
        long deadline = Keys.blockingDeadline(ctx, timeoutIdx);
        KeyEntry s = Keys.read(ctx, src, KeyEntry.LIST);
        if (s != null) {
            move(ctx, src, dst, fromLeft, toLeft);
            return;
        }
        Engine engine = ctx.engine();
        if (engine.inExec() || ctx.db().isLoading()) {
            ctx.nullBulk();
            return;
        }
        engine.blocking().block(ctx.client(), new BlockState(BlockState.BLMOVE, new byte[][]{src}, deadline, dst, fromLeft, toLeft));
    }
}
