package com.jredis.server.command;

import com.jredis.common.NumberCodec;
import com.jredis.server.core.CommandContext;
import com.jredis.server.db.Db;
import com.jredis.server.db.KeyEntry;

import java.util.Arrays;

import static com.jredis.server.command.CommandSpec.DENYOOM;
import static com.jredis.server.command.CommandSpec.FAST;
import static com.jredis.server.command.CommandSpec.READONLY;
import static com.jredis.server.command.CommandSpec.WRITE;

/** String commands. */
final class StringCommands {

    private StringCommands() {
    }

    static void register(CommandTable t) {
        t.register("GET", 2, READONLY | FAST, StringCommands::get);
        t.register("SET", -3, WRITE | DENYOOM, StringCommands::set);
        t.register("SETNX", 3, WRITE | DENYOOM | FAST, StringCommands::setnx);
        t.register("SETEX", 4, WRITE | DENYOOM, ctx -> setex(ctx, false));
        t.register("PSETEX", 4, WRITE | DENYOOM, ctx -> setex(ctx, true));
        t.register("GETSET", 3, WRITE | DENYOOM | FAST, StringCommands::getset);
        t.register("GETDEL", 2, WRITE | FAST, StringCommands::getdel);
        t.register("GETEX", -2, WRITE | FAST, StringCommands::getex);
        t.register("MGET", -2, READONLY | FAST, StringCommands::mget);
        t.register("MSET", -3, WRITE | DENYOOM, StringCommands::mset);
        t.register("MSETNX", -3, WRITE | DENYOOM, StringCommands::msetnx);
        t.register("INCR", 2, WRITE | DENYOOM | FAST, ctx -> incrBy(ctx, 1));
        t.register("DECR", 2, WRITE | DENYOOM | FAST, ctx -> incrBy(ctx, -1));
        t.register("INCRBY", 3, WRITE | DENYOOM | FAST, ctx -> incrBy(ctx, ctx.longArg(2)));
        t.register("DECRBY", 3, WRITE | DENYOOM | FAST, StringCommands::decrBy);
        t.register("INCRBYFLOAT", 3, WRITE | DENYOOM | FAST, StringCommands::incrByFloat);
        t.register("APPEND", 3, WRITE | DENYOOM | FAST, StringCommands::append);
        t.register("SETRANGE", 4, WRITE | DENYOOM, StringCommands::setrange);
        t.register("STRLEN", 2, READONLY | FAST, StringCommands::strlen);
        t.register("GETRANGE", 4, READONLY, StringCommands::getrange);
        t.register("SUBSTR", 4, READONLY, StringCommands::getrange);
    }

    static byte[] getString(CommandContext ctx, byte[] key) {
        KeyEntry e = Keys.read(ctx, key, KeyEntry.STRING);
        return e == null ? null : e.stringValue();
    }

    static void get(CommandContext ctx) {
        ctx.bulkOrNull(getString(ctx, ctx.arg(1)));
    }

    /** Parsed SET options. */
    static final class SetOptions {
        boolean nx;
        boolean xx;
        boolean get;
        boolean keepTtl;
        long expireAt = -1;
    }

    /**
     * Parses SET-style options starting at {@code from}.
     *
     * @param allowConditions NX/XX/GET (SET) or not (J.CAS)
     */
    static SetOptions parseSetOptions(CommandContext ctx, int from, boolean allowConditions) {
        SetOptions o = new SetOptions();
        String expireKind = null;               // EX, PX, EXAT, PXAT or KEEPTTL; repeats allowed, mixing not
        for (int i = from; i < ctx.argc(); i++) {
            boolean hasNext = i + 1 < ctx.argc();
            String kind = ctx.argString(i).toUpperCase(java.util.Locale.ROOT);
            if (allowConditions && kind.equals("NX") && !o.xx) {
                o.nx = true;
            } else if (allowConditions && kind.equals("XX") && !o.nx) {
                o.xx = true;
            } else if (allowConditions && kind.equals("GET")) {
                o.get = true;
            } else if (kind.equals("KEEPTTL") && (expireKind == null || expireKind.equals(kind))) {
                o.keepTtl = true;
                expireKind = kind;
            } else if (hasNext && (kind.equals("EX") || kind.equals("PX") || kind.equals("EXAT") || kind.equals("PXAT"))
                    && (expireKind == null || expireKind.equals(kind))) {
                boolean millis = kind.equals("PX") || kind.equals("PXAT");
                boolean absolute = kind.equals("EXAT") || kind.equals("PXAT");
                long v = ctx.longArg(i + 1);
                o.expireAt = Keys.expireAt(ctx, v, millis, absolute, ctx.spec().name.toLowerCase());
                expireKind = kind;
                i++;
            } else {
                throw CommandException.SYNTAX;
            }
        }
        return o;
    }

    /** Stores a string with SET semantics and propagates the deterministic effect. */
    static void storeString(CommandContext ctx, byte[] key, byte[] value, boolean keepTtl, long expireAt) {
        Db db = ctx.db();
        KeyEntry e = db.setValue(key, KeyEntry.STRING, value, keepTtl);
        if (expireAt >= 0) {
            db.setExpire(e, expireAt);
            ctx.propagate(Words.SET, key, value, Words.PXAT, NumberCodec.toBytes(expireAt));
        } else if (keepTtl) {
            ctx.propagate(Words.SET, key, value, Words.KEEPTTL);
        } else {
            ctx.propagate(Words.SET, key, value);
        }
    }

    static void set(CommandContext ctx) {
        byte[] key = ctx.arg(1);
        byte[] value = ctx.arg(2);
        SetOptions o = parseSetOptions(ctx, 3, true);
        KeyEntry existing = ctx.db().lookupRead(key);
        byte[] old = null;
        if (o.get && existing != null) {
            if (existing.type() != KeyEntry.STRING) {
                throw CommandException.WRONGTYPE;
            }
            old = existing.stringValue();
        }
        if ((o.nx && existing != null) || (o.xx && existing == null)) {
            if (o.get) {
                ctx.bulkOrNull(old);
            } else {
                ctx.nullBulk();
            }
            return;
        }
        storeString(ctx, key, value, o.keepTtl, o.expireAt);
        if (o.get) {
            ctx.bulkOrNull(old);
        } else {
            ctx.ok();
        }
    }

    static void setnx(CommandContext ctx) {
        if (ctx.db().lookupRead(ctx.arg(1)) != null) {
            ctx.integer(0);
            return;
        }
        storeString(ctx, ctx.arg(1), ctx.arg(2), false, -1);
        ctx.integer(1);
    }

    static void setex(CommandContext ctx, boolean millis) {
        long v = ctx.longArg(2);
        long at = Keys.expireAt(ctx, v, millis, false, millis ? "psetex" : "setex");
        storeString(ctx, ctx.arg(1), ctx.arg(3), false, at);
        ctx.ok();
    }

    static void getset(CommandContext ctx) {
        byte[] old = getString(ctx, ctx.arg(1));
        storeString(ctx, ctx.arg(1), ctx.arg(2), false, -1);
        ctx.bulkOrNull(old);
    }

    static void getdel(CommandContext ctx) {
        byte[] key = ctx.arg(1);
        byte[] old = getString(ctx, key);
        if (old != null) {
            ctx.db().delete(key);
            ctx.propagate(Words.DEL, key);
        }
        ctx.bulkOrNull(old);
    }

    static void getex(CommandContext ctx) {
        byte[] key = ctx.arg(1);
        long expireAt = -1;
        boolean persist = false;
        String kind = null;                     // repeating an option is allowed, mixing is not
        int valueIndex = -1;
        for (int i = 2; i < ctx.argc(); i++) {
            String opt = ctx.argString(i).toUpperCase(java.util.Locale.ROOT);
            if (kind != null && !kind.equals(opt)) {
                throw CommandException.SYNTAX;
            }
            if (opt.equals("PERSIST")) {
                persist = true;
            } else if ((opt.equals("EX") || opt.equals("PX") || opt.equals("EXAT") || opt.equals("PXAT")) && i + 1 < ctx.argc()) {
                valueIndex = ++i;
            } else {
                throw CommandException.SYNTAX;
            }
            kind = opt;
        }
        KeyEntry e = Keys.read(ctx, key, KeyEntry.STRING);
        if (e == null) {
            ctx.nullBulk();
            return;
        }
        if (valueIndex > 0) {
            boolean millis = kind.equals("PX") || kind.equals("PXAT");
            boolean absolute = kind.equals("EXAT") || kind.equals("PXAT");
            expireAt = Keys.expireAt(ctx, ctx.longArg(valueIndex), millis, absolute, "getex");
        }
        byte[] value = e.stringValue();
        Db db = ctx.db();
        if (expireAt >= 0) {
            if (expireAt <= ctx.now() && !db.isLoading()) {
                db.deleteEntry(e);
                ctx.propagate(Words.DEL, key);
            } else {
                db.setExpire(e, expireAt);
                ctx.propagate(Words.PEXPIREAT, key, NumberCodec.toBytes(expireAt));
            }
        } else if (persist && db.persist(e)) {
            ctx.propagate(Words.PERSIST, key);
        }
        ctx.bulk(value);
    }

    static void mget(CommandContext ctx) {
        ctx.arrayHeader(ctx.argc() - 1);
        for (int i = 1; i < ctx.argc(); i++) {
            KeyEntry e = ctx.db().lookupRead(ctx.arg(i));
            ctx.bulkOrNull(e != null && e.type() == KeyEntry.STRING ? e.stringValue() : null);
        }
    }

    private static void checkPairs(CommandContext ctx) {
        if ((ctx.argc() - 1) % 2 != 0) {
            throw new CommandException("ERR wrong number of arguments for '" + ctx.spec().name.toLowerCase() + "' command");
        }
    }

    static void mset(CommandContext ctx) {
        checkPairs(ctx);
        setAll(ctx);
        ctx.ok();
    }

    static void msetnx(CommandContext ctx) {
        checkPairs(ctx);
        for (int i = 1; i < ctx.argc(); i += 2) {
            if (ctx.db().lookupRead(ctx.arg(i)) != null) {
                ctx.integer(0);
                return;
            }
        }
        setAll(ctx);
        ctx.integer(1);
    }

    /** Stores every key/value pair and logs them as one MSET. */
    private static void setAll(CommandContext ctx) {
        Db db = ctx.db();
        for (int i = 1; i < ctx.argc(); i += 2) {
            db.setValue(ctx.arg(i), KeyEntry.STRING, ctx.arg(i + 1), false);
        }
        byte[][] effect = ctx.argv().clone();
        effect[0] = Words.MSET;
        ctx.propagate(effect);
    }

    static void decrBy(CommandContext ctx) {
        long v = ctx.longArg(2);
        if (v == Long.MIN_VALUE) {
            throw new CommandException("ERR decrement would overflow");
        }
        incrBy(ctx, -v);
    }

    static void incrBy(CommandContext ctx, long delta) {
        byte[] key = ctx.arg(1);
        KeyEntry e = Keys.read(ctx, key, KeyEntry.STRING);
        long current = 0;
        if (e != null) {
            try {
                current = NumberCodec.parseLong(e.stringValue());
            } catch (NumberFormatException ex) {
                throw CommandException.NOT_INTEGER;
            }
        }
        long result;
        try {
            result = Math.addExact(current, delta);
        } catch (ArithmeticException ex) {
            throw CommandException.OVERFLOW;
        }
        byte[] bytes = NumberCodec.toBytes(result);
        if (e == null) {
            ctx.db().add(key, KeyEntry.STRING, bytes);
        } else {
            ctx.db().replaceString(e, bytes);
        }
        ctx.propagateAsIs();
        ctx.integer(result);
    }

    static void incrByFloat(CommandContext ctx) {
        byte[] key = ctx.arg(1);
        ctx.doubleArg(2);                                   // validates the increment
        KeyEntry e = Keys.read(ctx, key, KeyEntry.STRING);
        byte[] current = ZERO;
        if (e != null) {
            current = e.stringValue();
            try {
                NumberCodec.parseDouble(current);
            } catch (NumberFormatException ex) {
                throw CommandException.NOT_FLOAT;
            }
        }
        byte[] bytes = NumberCodec.addFloats(current, ctx.arg(2));
        if (bytes == null) {
            throw new CommandException("ERR increment would produce NaN or Infinity");
        }
        if (e == null) {
            ctx.db().add(key, KeyEntry.STRING, bytes);
        } else {
            ctx.db().replaceString(e, bytes);
        }
        ctx.propagate(Words.SET, key, bytes, Words.KEEPTTL);
        ctx.bulk(bytes);
    }

    private static final byte[] ZERO = {'0'};

    /**
     * A string may not grow beyond proto-max-bulk-len. Not checked while replaying the AOF: the
     * write was accepted when it happened, and a lowered limit must not drop it now.
     */
    private static void checkSize(CommandContext ctx, long offset, long extra) {
        if (!ctx.db().isLoading() && offset > ctx.config().protoMaxBulkLen() - extra) {   // no overflow
            throw new CommandException("ERR string exceeds maximum allowed size (proto-max-bulk-len)");
        }
    }

    static void append(CommandContext ctx) {
        byte[] key = ctx.arg(1);
        byte[] add = ctx.arg(2);
        KeyEntry e = Keys.read(ctx, key, KeyEntry.STRING);
        if (e == null) {
            ctx.db().add(key, KeyEntry.STRING, add);
            ctx.propagateAsIs();
            ctx.integer(add.length);
            return;
        }
        byte[] cur = e.stringValue();
        checkSize(ctx, cur.length, add.length);
        byte[] next = Arrays.copyOf(cur, cur.length + add.length);
        System.arraycopy(add, 0, next, cur.length, add.length);
        ctx.db().replaceString(e, next);
        ctx.propagateAsIs();
        ctx.integer(next.length);
    }

    static void setrange(CommandContext ctx) {
        byte[] key = ctx.arg(1);
        long offset = ctx.longArg(2);
        byte[] value = ctx.arg(3);
        if (offset < 0) {
            throw new CommandException("ERR offset is out of range");
        }
        KeyEntry e = Keys.read(ctx, key, KeyEntry.STRING);
        byte[] cur = e == null ? new byte[0] : e.stringValue();
        if (value.length == 0) {
            ctx.integer(cur.length);
            return;
        }
        checkSize(ctx, offset, value.length);
        int len = (int) Math.max(cur.length, offset + value.length);
        byte[] next = Arrays.copyOf(cur, len);
        System.arraycopy(value, 0, next, (int) offset, value.length);
        if (e == null) {
            ctx.db().add(key, KeyEntry.STRING, next);
        } else {
            ctx.db().replaceString(e, next);
        }
        ctx.propagateAsIs();
        ctx.integer(next.length);
    }

    static void strlen(CommandContext ctx) {
        byte[] v = getString(ctx, ctx.arg(1));
        ctx.integer(v == null ? 0 : v.length);
    }

    static void getrange(CommandContext ctx) {
        long start = ctx.longArg(2);
        long end = ctx.longArg(3);
        byte[] v = getString(ctx, ctx.arg(1));
        if (v == null) {
            ctx.bulk(new byte[0]);
            return;
        }
        long len = v.length;
        if (start < 0 && end < 0 && start > end) {
            ctx.bulk(new byte[0]);
            return;
        }
        if (start < 0) {
            start = len + start;
        }
        if (end < 0) {
            end = len + end;
        }
        if (start < 0) {
            start = 0;
        }
        if (end < 0) {
            end = 0;
        }
        if (end >= len) {
            end = len - 1;
        }
        if (start > end || len == 0) {
            ctx.bulk(new byte[0]);
            return;
        }
        ctx.bulk(Arrays.copyOfRange(v, (int) start, (int) end + 1));
    }
}
