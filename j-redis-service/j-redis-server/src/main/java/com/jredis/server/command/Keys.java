package com.jredis.server.command;

import com.jredis.server.core.CommandContext;
import com.jredis.server.core.Stats;
import com.jredis.server.db.Db;
import com.jredis.server.db.KeyEntry;

/**
 * Typed key lookups. The type is checked <b>before</b> a write is announced to the Db, so a
 * WRONGTYPE error never follows a snapshot pre-image or sets the mutation flag.
 */
final class Keys {

    private Keys() {
    }

    /** Entry of the given type, or null if absent. WRONGTYPE otherwise. Counts hits/misses. */
    static KeyEntry read(CommandContext ctx, byte[] key, byte type) {
        KeyEntry e = ctx.db().lookupRead(key);
        Stats stats = ctx.engine().stats();
        if (e == null) {
            stats.keyspaceMisses++;
            return null;
        }
        if (e.type() != type) {
            throw CommandException.WRONGTYPE;
        }
        stats.keyspaceHits++;
        return e;
    }

    /** Entry of the given type prepared for modification, or null if absent. WRONGTYPE otherwise. */
    static KeyEntry write(CommandContext ctx, byte[] key, byte type) {
        Db db = ctx.db();
        KeyEntry e = db.lookupRead(key);
        if (e == null) {
            return null;
        }
        if (e.type() != type) {
            throw CommandException.WRONGTYPE;
        }
        db.prepareWrite(e);
        return e;
    }

    /** Validation only: WRONGTYPE if the key exists with another type. */
    static void checkType(CommandContext ctx, byte[] key, byte type) {
        KeyEntry e = ctx.db().lookupRead(key);
        if (e != null && e.type() != type) {
            throw CommandException.WRONGTYPE;
        }
    }

    /** Normalises Redis-style inclusive [start, end] indexes against a length; null if empty. */
    static long[] range(long start, long end, long length) {
        if (start < 0) {
            start = length + start;
        }
        if (end < 0) {
            end = length + end;
        }
        if (start < 0) {
            start = 0;
        }
        if (start > end || start >= length) {
            return null;
        }
        if (end >= length) {
            end = length - 1;
        }
        return new long[]{start, end};
    }

    /** Relative-or-absolute expiry option value to absolute epoch ms; throws on overflow or <= 0. */
    static long expireAt(CommandContext ctx, long value, boolean millis, boolean absolute, String command) {
        if (value <= 0) {
            throw new CommandException("ERR invalid expire time in '" + command + "' command");
        }
        try {
            long ms = millis ? value : Math.multiplyExact(value, 1000L);
            return absolute ? ms : Math.addExact(ms, ctx.now());
        } catch (ArithmeticException e) {
            throw new CommandException("ERR invalid expire time in '" + command + "' command");
        }
    }

    /** Timeout argument of blocking commands: seconds as a double; 0 = forever. Returns absolute ms or 0. */
    static long blockingDeadline(CommandContext ctx, int argIndex) {
        double seconds;
        try {
            seconds = com.jredis.common.NumberCodec.parseDouble(ctx.arg(argIndex));
        } catch (NumberFormatException e) {
            throw new CommandException("ERR timeout is not a float or out of range");
        }
        if (seconds < 0) {
            throw new CommandException("ERR timeout is negative");
        }
        if (seconds == 0) {
            return 0;
        }
        double ms = seconds * 1000.0;
        if (ms > (double) (Long.MAX_VALUE / 2)) {
            throw new CommandException("ERR timeout is out of range");
        }
        return ctx.now() + Math.max(1, (long) Math.ceil(ms));
    }
}
