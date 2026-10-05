package com.jredis.server.command;

import com.jredis.common.Glob;
import com.jredis.server.blocking.BlockState;
import com.jredis.server.core.CommandContext;
import com.jredis.server.core.Engine;
import com.jredis.server.db.Db;
import com.jredis.server.db.KeyEntry;
import com.jredis.server.db.ScoreRange;
import com.jredis.server.db.SkipList;
import com.jredis.server.db.ZEntry;
import com.jredis.server.db.ZSetValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.jredis.server.command.CommandSpec.BLOCKING;
import static com.jredis.server.command.CommandSpec.DENYOOM;
import static com.jredis.server.command.CommandSpec.FAST;
import static com.jredis.server.command.CommandSpec.READONLY;
import static com.jredis.server.command.CommandSpec.WRITE;

/** Sorted-set commands, the blocking pops, and the J.ZAROUND extension. */
final class ZSetCommands {

    static final int ZAROUND_MAX_COUNT = 1000;

    private ZSetCommands() {
    }

    static void register(CommandTable t) {
        t.register("ZADD", -4, WRITE | DENYOOM | FAST, ZSetCommands::zadd);
        t.register("ZINCRBY", 4, WRITE | DENYOOM | FAST, ZSetCommands::zincrby);
        t.register("ZREM", -3, WRITE | FAST, ZSetCommands::zrem);
        t.register("ZREMRANGEBYRANK", 4, WRITE, ZSetCommands::zremrangebyrank);
        t.register("ZREMRANGEBYSCORE", 4, WRITE, ZSetCommands::zremrangebyscore);
        t.register("ZPOPMIN", -2, WRITE | FAST, ctx -> zpop(ctx, true));
        t.register("ZPOPMAX", -2, WRITE | FAST, ctx -> zpop(ctx, false));
        t.register("ZSCORE", 3, READONLY | FAST, ZSetCommands::zscore);
        t.register("ZMSCORE", -3, READONLY | FAST, ZSetCommands::zmscore);
        t.register("ZCARD", 2, READONLY | FAST, ZSetCommands::zcard);
        t.register("ZCOUNT", 4, READONLY | FAST, ZSetCommands::zcount);
        t.register("ZRANK", -3, READONLY | FAST, ctx -> zrank(ctx, false));
        t.register("ZREVRANK", -3, READONLY | FAST, ctx -> zrank(ctx, true));
        t.register("ZRANGE", -4, READONLY, ZSetCommands::zrange);
        t.register("ZREVRANGE", -4, READONLY, ZSetCommands::zrevrange);
        t.register("ZRANGEBYSCORE", -4, READONLY, ctx -> zrangebyscore(ctx, false));
        t.register("ZREVRANGEBYSCORE", -4, READONLY, ctx -> zrangebyscore(ctx, true));
        t.register("ZSCAN", -3, READONLY, ZSetCommands::zscan);
        t.register("ZRANDMEMBER", -2, READONLY, ZSetCommands::zrandmember);
        t.register("BZPOPMIN", -3, WRITE | BLOCKING, ctx -> bzpop(ctx, true));
        t.register("BZPOPMAX", -3, WRITE | BLOCKING, ctx -> bzpop(ctx, false));
        t.register("J.ZAROUND", -4, READONLY, ZSetCommands::zaround);
    }

    private static ZSetValue zsetForWrite(CommandContext ctx, byte[] key, boolean create) {
        KeyEntry e = Keys.write(ctx, key, KeyEntry.ZSET);
        if (e != null) {
            return e.zset();
        }
        if (!create) {
            return null;
        }
        ZSetValue z = new ZSetValue(ctx.db().hasher());
        ctx.db().add(key, KeyEntry.ZSET, z);
        return z;
    }

    private static void afterRemoval(CommandContext ctx, byte[] key) {
        Db db = ctx.db();
        KeyEntry e = db.lookupRead(key);
        if (e != null && e.zset().size() == 0) {
            db.deleteEntry(e);
        } else {
            db.signalModified(key);
        }
    }

    static void zadd(CommandContext ctx) {
        boolean nx = false;
        boolean xx = false;
        boolean gt = false;
        boolean lt = false;
        boolean ch = false;
        boolean incr = false;
        int i = 2;
        for (; i < ctx.argc(); i++) {
            if (ctx.argIs(i, "NX")) {
                nx = true;
            } else if (ctx.argIs(i, "XX")) {
                xx = true;
            } else if (ctx.argIs(i, "GT")) {
                gt = true;
            } else if (ctx.argIs(i, "LT")) {
                lt = true;
            } else if (ctx.argIs(i, "CH")) {
                ch = true;
            } else if (ctx.argIs(i, "INCR")) {
                incr = true;
            } else {
                break;
            }
        }
        int elements = ctx.argc() - i;
        if (elements == 0 || elements % 2 != 0) {
            throw CommandException.SYNTAX;
        }
        if (nx && xx) {
            throw new CommandException("ERR XX and NX options at the same time are not compatible");
        }
        if ((gt && nx) || (lt && nx) || (gt && lt)) {
            throw new CommandException("ERR GT, LT, and/or NX options at the same time are not compatible");
        }
        if (incr && elements > 2) {
            throw new CommandException("ERR INCR option supports a single increment-element pair");
        }
        int pairs = elements / 2;
        double[] scores = new double[pairs];
        for (int p = 0; p < pairs; p++) {
            scores[p] = ctx.doubleArg(i + 2 * p);
        }
        byte[] key = ctx.arg(1);
        Keys.checkType(ctx, key, KeyEntry.ZSET);
        if (xx && ctx.db().lookupRead(key) == null) {
            if (incr) {
                ctx.nullBulk();
            } else {
                ctx.integer(0);
            }
            return;
        }
        ZSetValue z = zsetForWrite(ctx, key, true);
        int added = 0;
        int updated = 0;
        double result = 0;
        boolean applied = false;
        for (int p = 0; p < pairs; p++) {
            byte[] member = ctx.arg(i + 2 * p + 1);
            double score = scores[p];
            ZEntry ze = z.find(member);
            if (ze != null) {
                if (nx) {
                    continue;
                }
                double cur = ze.score();
                double next = incr ? cur + score : score;
                if (Double.isNaN(next)) {
                    throw new CommandException("ERR resulting score is not a number (NaN)");
                }
                if ((gt && next <= cur) || (lt && next >= cur)) {
                    continue;
                }
                if (next != cur) {
                    z.updateScore(ze, next);
                    updated++;
                }
                result = next;
                applied = true;
            } else {
                if (xx) {
                    continue;
                }
                z.insert(member, score);
                added++;
                result = score;
                applied = true;
            }
        }
        if (added + updated > 0) {
            ctx.db().signalModified(key);
            ctx.propagateAsIs();
        } else if (z.size() == 0) {
            afterRemoval(ctx, key);      // created but nothing inserted: do not leave an empty key
        }
        if (incr) {
            if (applied) {
                ctx.bulkDouble(result);
            } else {
                ctx.nullBulk();
            }
        } else {
            ctx.integer(ch ? added + updated : added);
        }
    }

    static void zincrby(CommandContext ctx) {
        byte[] key = ctx.arg(1);
        double incr = ctx.doubleArg(2);
        byte[] member = ctx.arg(3);
        Keys.checkType(ctx, key, KeyEntry.ZSET);
        KeyEntry existing = ctx.db().lookupRead(key);
        ZEntry ze = existing == null ? null : existing.zset().find(member);
        double result = ze == null ? incr : ze.score() + incr;
        if (Double.isNaN(result)) {
            throw new CommandException("ERR resulting score is not a number (NaN)");
        }
        ZSetValue z = zsetForWrite(ctx, key, true);
        if (ze == null) {
            z.insert(member, result);
        } else {
            z.updateScore(ze, result);
        }
        ctx.db().signalModified(key);
        ctx.propagateAsIs();
        ctx.bulkDouble(result);
    }

    static void zrem(CommandContext ctx) {
        byte[] key = ctx.arg(1);
        ZSetValue z = zsetForWrite(ctx, key, false);
        if (z == null) {
            ctx.integer(0);
            return;
        }
        int removed = 0;
        for (int i = 2; i < ctx.argc(); i++) {
            if (z.remove(ctx.arg(i))) {
                removed++;
            }
        }
        if (removed > 0) {
            afterRemoval(ctx, key);
            ctx.propagateAsIs();
        }
        ctx.integer(removed);
    }

    private static ScoreRange range(CommandContext ctx, int minIdx, int maxIdx) {
        try {
            return ScoreRange.parse(ctx.arg(minIdx), ctx.arg(maxIdx));
        } catch (NumberFormatException e) {
            throw new CommandException("ERR min or max is not a float");
        }
    }

    static void zremrangebyscore(CommandContext ctx) {
        byte[] key = ctx.arg(1);
        ScoreRange r = range(ctx, 2, 3);
        ZSetValue z = zsetForWrite(ctx, key, false);
        int removed = z == null ? 0 : z.deleteRangeByScore(r);
        if (removed > 0) {
            afterRemoval(ctx, key);
            ctx.propagateAsIs();
        }
        ctx.integer(removed);
    }

    static void zremrangebyrank(CommandContext ctx) {
        byte[] key = ctx.arg(1);
        long start = ctx.longArg(2);
        long stop = ctx.longArg(3);
        ZSetValue z = zsetForWrite(ctx, key, false);
        long[] r = z == null ? null : Keys.range(start, stop, z.size());
        int removed = r == null ? 0 : z.deleteRangeByRank(r[0], r[1]);
        if (removed > 0) {
            afterRemoval(ctx, key);
            ctx.propagateAsIs();
        }
        ctx.integer(removed);
    }

    static void zpop(CommandContext ctx, boolean min) {
        byte[] key = ctx.arg(1);
        long count = 1;
        if (ctx.argc() == 3) {
            count = ctx.longArg(2);
            if (count < 0) {
                throw new CommandException("ERR value is out of range, must be positive");
            }
        } else if (ctx.argc() > 3) {
            throw CommandException.SYNTAX;
        }
        ZSetValue z = zsetForWrite(ctx, key, false);
        if (z == null || count == 0) {
            ctx.emptyArray();
            return;
        }
        long n = Math.min(count, z.size());
        ctx.arrayHeader(2 * n);
        for (long i = 0; i < n; i++) {
            SkipList.Node node = min ? z.list().first() : z.list().last();
            byte[] member = node.member();
            double score = node.score();
            z.remove(member);
            ctx.bulk(member);
            ctx.bulkDouble(score);
        }
        afterRemoval(ctx, key);
        ctx.propagateAsIs();
    }

    static void zscore(CommandContext ctx) {
        KeyEntry e = Keys.read(ctx, ctx.arg(1), KeyEntry.ZSET);
        Double s = e == null ? null : e.zset().score(ctx.arg(2));
        if (s == null) {
            ctx.nullBulk();
        } else {
            ctx.bulkDouble(s);
        }
    }

    static void zmscore(CommandContext ctx) {
        KeyEntry e = Keys.read(ctx, ctx.arg(1), KeyEntry.ZSET);
        ctx.arrayHeader(ctx.argc() - 2);
        for (int i = 2; i < ctx.argc(); i++) {
            Double s = e == null ? null : e.zset().score(ctx.arg(i));
            if (s == null) {
                ctx.nullBulk();
            } else {
                ctx.bulkDouble(s);
            }
        }
    }

    static void zcard(CommandContext ctx) {
        KeyEntry e = Keys.read(ctx, ctx.arg(1), KeyEntry.ZSET);
        ctx.integer(e == null ? 0 : e.zset().size());
    }

    static void zcount(CommandContext ctx) {
        ScoreRange r = range(ctx, 2, 3);
        KeyEntry e = Keys.read(ctx, ctx.arg(1), KeyEntry.ZSET);
        if (e == null) {
            ctx.integer(0);
            return;
        }
        SkipList list = e.zset().list();
        SkipList.Node first = list.firstInRange(r);
        if (first == null) {
            ctx.integer(0);
            return;
        }
        SkipList.Node last = list.lastInRange(r);
        long count = list.rank(last.score(), last.member()) - list.rank(first.score(), first.member()) + 1;
        ctx.integer(count);
    }

    static void zrank(CommandContext ctx, boolean reverse) {
        boolean withScore = false;
        if (ctx.argc() == 4 && ctx.argIs(3, "WITHSCORE")) {
            withScore = true;
        } else if (ctx.argc() != 3) {
            throw CommandException.SYNTAX;
        }
        KeyEntry e = Keys.read(ctx, ctx.arg(1), KeyEntry.ZSET);
        long rank = e == null ? -1 : e.zset().rank(ctx.arg(2), reverse);
        if (rank < 0) {
            if (withScore) {
                ctx.nullArray();
            } else {
                ctx.nullBulk();
            }
            return;
        }
        if (withScore) {
            ctx.arrayHeader(2);
            ctx.integer(rank);
            ctx.bulkDouble(e.zset().score(ctx.arg(2)));
        } else {
            ctx.integer(rank);
        }
    }

    static void zrange(CommandContext ctx) {
        boolean byScore = false;
        boolean rev = false;
        boolean withScores = false;
        boolean limitGiven = false;
        long offset = 0;
        long count = -1;
        for (int i = 4; i < ctx.argc(); i++) {
            if (ctx.argIs(i, "BYSCORE")) {
                byScore = true;
            } else if (ctx.argIs(i, "BYLEX")) {
                throw new CommandException("ERR BYLEX is not supported by this server");
            } else if (ctx.argIs(i, "REV")) {
                rev = true;
            } else if (ctx.argIs(i, "WITHSCORES")) {
                withScores = true;
            } else if (ctx.argIs(i, "LIMIT") && i + 2 < ctx.argc()) {
                offset = ctx.longArg(i + 1);
                count = ctx.longArg(i + 2);
                limitGiven = true;
                i += 2;
            } else {
                throw CommandException.SYNTAX;
            }
        }
        if (limitGiven && !byScore) {
            throw new CommandException("ERR syntax error, LIMIT is only supported in combination with either BYSCORE or BYLEX");
        }
        if (byScore) {
            ScoreRange r = rev ? range(ctx, 3, 2) : range(ctx, 2, 3);
            rangeByScore(ctx, r, rev, withScores, offset, count);
        } else {
            rangeByIndex(ctx, ctx.longArg(2), ctx.longArg(3), rev, withScores);
        }
    }

    static void zrevrange(CommandContext ctx) {
        boolean withScores = false;
        if (ctx.argc() == 5 && ctx.argIs(4, "WITHSCORES")) {
            withScores = true;
        } else if (ctx.argc() != 4) {
            throw CommandException.SYNTAX;
        }
        rangeByIndex(ctx, ctx.longArg(2), ctx.longArg(3), true, withScores);
    }

    static void zrangebyscore(CommandContext ctx, boolean rev) {
        boolean withScores = false;
        long offset = 0;
        long count = -1;
        for (int i = 4; i < ctx.argc(); i++) {
            if (ctx.argIs(i, "WITHSCORES")) {
                withScores = true;
            } else if (ctx.argIs(i, "LIMIT") && i + 2 < ctx.argc()) {
                offset = ctx.longArg(i + 1);
                count = ctx.longArg(i + 2);
                i += 2;
            } else {
                throw CommandException.SYNTAX;
            }
        }
        ScoreRange r = rev ? range(ctx, 3, 2) : range(ctx, 2, 3);
        rangeByScore(ctx, r, rev, withScores, offset, count);
    }

    private static void rangeByIndex(CommandContext ctx, long start, long stop, boolean rev, boolean withScores) {
        KeyEntry e = Keys.read(ctx, ctx.arg(1), KeyEntry.ZSET);
        if (e == null) {
            ctx.emptyArray();
            return;
        }
        SkipList list = e.zset().list();
        long len = list.length();
        long[] r = Keys.range(start, stop, len);
        if (r == null) {
            ctx.emptyArray();
            return;
        }
        long n = r[1] - r[0] + 1;
        ctx.arrayHeader(withScores ? 2 * n : n);
        SkipList.Node node = rev ? list.byRank(len - r[0]) : list.byRank(r[0] + 1);
        for (long i = 0; i < n && node != null; i++) {
            ctx.bulk(node.member());
            if (withScores) {
                ctx.bulkDouble(node.score());
            }
            node = rev ? node.prev() : node.next();
        }
    }

    private static void rangeByScore(CommandContext ctx, ScoreRange r, boolean rev, boolean withScores, long offset, long count) {
        KeyEntry e = Keys.read(ctx, ctx.arg(1), KeyEntry.ZSET);
        if (e == null || offset < 0 || count == 0) {
            ctx.emptyArray();
            return;
        }
        SkipList list = e.zset().list();
        SkipList.Node node = rev ? list.lastInRange(r) : list.firstInRange(r);
        while (node != null && offset > 0) {
            node = rev ? node.prev() : node.next();
            offset--;
        }
        List<SkipList.Node> out = new ArrayList<>();
        while (node != null && (rev ? r.gteMin(node.score()) : r.lteMax(node.score())) && (count < 0 || out.size() < count)) {
            out.add(node);
            node = rev ? node.prev() : node.next();
        }
        ctx.arrayHeader(withScores ? 2L * out.size() : out.size());
        for (SkipList.Node n : out) {
            ctx.bulk(n.member());
            if (withScores) {
                ctx.bulkDouble(n.score());
            }
        }
    }

    static void zscan(CommandContext ctx) {
        ScanArgs a = ScanArgs.parse(ctx, 2, false);
        KeyEntry e = Keys.read(ctx, ctx.arg(1), KeyEntry.ZSET);
        if (e == null) {
            ScanArgs.replyCursor(ctx, 0);
            ctx.emptyArray();
            return;
        }
        List<ZEntry> found = new ArrayList<>();
        long cursor = a.cursor;
        long budget = a.count * 10;
        do {
            cursor = e.zset().scan(cursor, found::add);
        } while (cursor != 0 && found.size() < a.count && --budget > 0);
        List<ZEntry> out = new ArrayList<>();
        for (ZEntry z : found) {
            if (a.match == null || Glob.match(a.match, z.member())) {
                out.add(z);
            }
        }
        ScanArgs.replyCursor(ctx, cursor);
        ctx.arrayHeader(2L * out.size());
        for (ZEntry z : out) {
            ctx.bulk(z.member());
            ctx.bulkDouble(z.score());
        }
    }

    static void zrandmember(CommandContext ctx) {
        if (ctx.argc() == 2) {
            KeyEntry e = Keys.read(ctx, ctx.arg(1), KeyEntry.ZSET);
            ctx.bulkOrNull(e == null ? null : e.zset().random(ctx.db().random()).member());
            return;
        }
        long count = ctx.longArg(2);
        boolean withScores = false;
        if (ctx.argc() == 4 && ctx.argIs(3, "WITHSCORES")) {
            withScores = true;
        } else if (ctx.argc() != 3) {
            throw CommandException.SYNTAX;
        }
        KeyEntry e = Keys.read(ctx, ctx.arg(1), KeyEntry.ZSET);
        if (e == null || count == 0) {
            ctx.emptyArray();
            return;
        }
        final ZSetValue z = e.zset();
        List<SkipList.Node> picked = RandomSample.pick(z.size(), count, ctx.db().random(),
                rnd -> z.random(rnd).nodeRef(), () -> {
                    List<SkipList.Node> all = new ArrayList<>(z.size());
                    for (SkipList.Node n = z.list().first(); n != null; n = n.next()) {
                        all.add(n);
                    }
                    return all;
                }, SkipList.Node::member);
        ctx.arrayHeader(withScores ? 2L * picked.size() : picked.size());
        for (SkipList.Node n : picked) {
            ctx.bulk(n.member());
            if (withScores) {
                ctx.bulkDouble(n.score());
            }
        }
    }

    static void bzpop(CommandContext ctx, boolean min) {
        long deadline = Keys.blockingDeadline(ctx, ctx.argc() - 1);
        byte[][] keys = Arrays.copyOfRange(ctx.argv(), 1, ctx.argc() - 1);
        Db db = ctx.db();
        for (byte[] key : keys) {
            KeyEntry e = db.lookupRead(key);
            if (e == null) {
                continue;
            }
            if (e.type() != KeyEntry.ZSET) {
                throw CommandException.WRONGTYPE;
            }
            ZSetValue z = e.zset();
            if (z.size() > 0) {
                db.prepareWrite(e);
                SkipList.Node node = min ? z.list().first() : z.list().last();
                byte[] member = node.member();
                double score = node.score();
                z.remove(member);
                afterRemoval(ctx, key);
                ctx.propagate(min ? Words.ZPOPMIN : Words.ZPOPMAX, key);
                ctx.arrayHeader(3);
                ctx.bulk(key);
                ctx.bulk(member);
                ctx.bulkDouble(score);
                return;
            }
        }
        Engine engine = ctx.engine();
        if (engine.inExec() || db.isLoading()) {
            ctx.nullArray();
            return;
        }
        engine.blocking().block(ctx.client(), new BlockState(min ? BlockState.BZPOPMIN : BlockState.BZPOPMAX, keys, deadline, null, false, false));
    }

    /** J.ZAROUND key member count [REV] [WITHSCORES]: rank of member and its neighbours in one call. */
    static void zaround(CommandContext ctx) {
        long count = ctx.longArg(3);
        if (count < 0 || count > ZAROUND_MAX_COUNT) {
            throw new CommandException("ERR count must be between 0 and " + ZAROUND_MAX_COUNT);
        }
        boolean rev = false;
        boolean withScores = false;
        for (int i = 4; i < ctx.argc(); i++) {
            if (ctx.argIs(i, "REV")) {
                rev = true;
            } else if (ctx.argIs(i, "WITHSCORES")) {
                withScores = true;
            } else {
                throw CommandException.SYNTAX;
            }
        }
        KeyEntry e = Keys.read(ctx, ctx.arg(1), KeyEntry.ZSET);
        if (e == null) {
            ctx.nullArray();
            return;
        }
        ZSetValue z = e.zset();
        long rank = z.rank(ctx.arg(2), rev);
        if (rank < 0) {
            ctx.nullArray();
            return;
        }
        long size = z.size();
        long from = Math.max(0, rank - count);
        long to = Math.min(size - 1, rank + count);
        long n = to - from + 1;
        ctx.arrayHeader(2);
        ctx.integer(rank);
        ctx.arrayHeader(withScores ? 2 * n : n);
        SkipList list = z.list();
        SkipList.Node node = rev ? list.byRank(size - from) : list.byRank(from + 1);
        for (long i = 0; i < n && node != null; i++) {
            ctx.bulk(node.member());
            if (withScores) {
                ctx.bulkDouble(node.score());
            }
            node = rev ? node.prev() : node.next();
        }
    }
}
