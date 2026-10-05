package com.jredis.server.command;

import com.jredis.common.Glob;
import com.jredis.server.core.CommandContext;
import com.jredis.server.db.Db;
import com.jredis.server.db.KeyEntry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.jredis.server.command.CommandSpec.ADMIN;
import static com.jredis.server.command.CommandSpec.DENYOOM;
import static com.jredis.server.command.CommandSpec.FAST;
import static com.jredis.server.command.CommandSpec.READONLY;
import static com.jredis.server.command.CommandSpec.WRITE;

/** Generic key commands: DEL, EXISTS, TYPE, the EXPIRE family, RENAME, COPY, SCAN, KEYS... */
final class KeyCommands {

    private KeyCommands() {
    }

    static void register(CommandTable t) {
        t.register("DEL", -2, WRITE, KeyCommands::del);
        t.register("UNLINK", -2, WRITE | FAST, KeyCommands::del);
        t.register("EXISTS", -2, READONLY | FAST, KeyCommands::exists);
        t.register("TOUCH", -2, READONLY | FAST, KeyCommands::exists);
        t.register("TYPE", 2, READONLY | FAST, KeyCommands::type);
        t.register("EXPIRE", -3, WRITE | FAST, ctx -> expire(ctx, false, false));
        t.register("PEXPIRE", -3, WRITE | FAST, ctx -> expire(ctx, true, false));
        t.register("EXPIREAT", -3, WRITE | FAST, ctx -> expire(ctx, false, true));
        t.register("PEXPIREAT", -3, WRITE | FAST, ctx -> expire(ctx, true, true));
        t.register("TTL", 2, READONLY | FAST, ctx -> ttl(ctx, false, false));
        t.register("PTTL", 2, READONLY | FAST, ctx -> ttl(ctx, true, false));
        t.register("EXPIRETIME", 2, READONLY | FAST, ctx -> ttl(ctx, false, true));
        t.register("PEXPIRETIME", 2, READONLY | FAST, ctx -> ttl(ctx, true, true));
        t.register("PERSIST", 2, WRITE | FAST, KeyCommands::persist);
        t.register("RENAME", 3, WRITE, ctx -> rename(ctx, false));
        t.register("RENAMENX", 3, WRITE | FAST, ctx -> rename(ctx, true));
        t.register("COPY", -3, WRITE | DENYOOM, KeyCommands::copy);
        t.register("SCAN", -2, READONLY, KeyCommands::scan);
        t.register("KEYS", 2, READONLY | ADMIN, KeyCommands::keys);
        t.register("RANDOMKEY", 1, READONLY, KeyCommands::randomKey);
    }

    static void del(CommandContext ctx) {
        Db db = ctx.db();
        List<byte[]> deleted = new ArrayList<>();
        for (int i = 1; i < ctx.argc(); i++) {
            if (db.delete(ctx.arg(i))) {
                deleted.add(ctx.arg(i));
            }
        }
        if (!deleted.isEmpty()) {
            byte[][] effect = new byte[deleted.size() + 1][];
            effect[0] = Words.DEL;
            for (int i = 0; i < deleted.size(); i++) {
                effect[i + 1] = deleted.get(i);
            }
            ctx.propagate(effect);
        }
        ctx.integer(deleted.size());
    }

    static void exists(CommandContext ctx) {
        long n = 0;
        for (int i = 1; i < ctx.argc(); i++) {
            if (ctx.db().lookupRead(ctx.arg(i)) != null) {
                n++;
            }
        }
        ctx.integer(n);
    }

    static void type(CommandContext ctx) {
        KeyEntry e = ctx.db().lookupRead(ctx.arg(1));
        ctx.simple(e == null ? "none" : KeyEntry.typeName(e.type()));
    }

    static void expire(CommandContext ctx, boolean millis, boolean absolute) {
        byte[] key = ctx.arg(1);
        long v = ctx.longArg(2);
        boolean nx = false;
        boolean xx = false;
        boolean gt = false;
        boolean lt = false;
        for (int i = 3; i < ctx.argc(); i++) {
            if (ctx.argIs(i, "NX")) {
                nx = true;
            } else if (ctx.argIs(i, "XX")) {
                xx = true;
            } else if (ctx.argIs(i, "GT")) {
                gt = true;
            } else if (ctx.argIs(i, "LT")) {
                lt = true;
            } else {
                throw new CommandException("ERR Unsupported option " + ctx.argString(i));
            }
        }
        if (nx && (xx || gt || lt)) {
            throw new CommandException("ERR NX and XX, GT or LT options at the same time are not compatible");
        }
        if (gt && lt) {
            throw new CommandException("ERR GT and LT options at the same time are not compatible");
        }
        long when;
        try {
            long ms = millis ? v : Math.multiplyExact(v, 1000L);
            when = absolute ? ms : Math.addExact(ms, ctx.now());
        } catch (ArithmeticException e) {
            throw new CommandException("ERR invalid expire time in '" + ctx.spec().name.toLowerCase() + "' command");
        }
        Db db = ctx.db();
        KeyEntry e = db.lookupRead(key);
        if (e == null) {
            ctx.integer(0);
            return;
        }
        long cur = e.expireAt();
        if ((nx && cur != -1) || (xx && cur == -1) || (gt && (cur == -1 || when <= cur)) || (lt && cur != -1 && when >= cur)) {
            ctx.integer(0);
            return;
        }
        if (when <= ctx.now() && !db.isLoading()) {
            db.deleteEntry(e);
            ctx.propagate(Words.DEL, key);
        } else {
            db.setExpire(e, when);
            ctx.propagate(Words.PEXPIREAT, key, com.jredis.common.NumberCodec.toBytes(when));
        }
        ctx.integer(1);
    }

    static void ttl(CommandContext ctx, boolean millis, boolean absolute) {
        KeyEntry e = ctx.db().lookupRead(ctx.arg(1));
        if (e == null) {
            ctx.integer(-2);
            return;
        }
        long at = e.expireAt();
        if (at < 0) {
            ctx.integer(-1);
            return;
        }
        if (absolute) {
            ctx.integer(millis ? at : at / 1000);
            return;
        }
        long ttl = Math.max(0, at - ctx.now());
        ctx.integer(millis ? ttl : (ttl + 500) / 1000);
    }

    static void persist(CommandContext ctx) {
        KeyEntry e = ctx.db().lookupRead(ctx.arg(1));
        if (e != null && ctx.db().persist(e)) {
            ctx.propagateAsIs();
            ctx.integer(1);
        } else {
            ctx.integer(0);
        }
    }

    static void rename(CommandContext ctx, boolean nx) {
        byte[] src = ctx.arg(1);
        byte[] dst = ctx.arg(2);
        Db db = ctx.db();
        KeyEntry s = db.lookupRead(src);
        if (s == null) {
            throw CommandException.NO_SUCH_KEY;
        }
        if (Arrays.equals(src, dst)) {
            if (nx) {
                ctx.integer(0);
            } else {
                ctx.ok();
            }
            return;
        }
        if (nx && db.lookupRead(dst) != null) {
            ctx.integer(0);
            return;
        }
        byte type = s.type();
        Object value = s.value();
        long expireAt = s.expireAt();
        db.deleteEntry(s);
        KeyEntry d = db.setValue(dst, type, value, false);
        if (expireAt >= 0) {
            db.setExpire(d, expireAt);
        }
        ctx.propagate(Words.RENAME, src, dst);
        if (nx) {
            ctx.integer(1);
        } else {
            ctx.ok();
        }
    }

    static void copy(CommandContext ctx) {
        byte[] src = ctx.arg(1);
        byte[] dst = ctx.arg(2);
        boolean replace = false;
        for (int i = 3; i < ctx.argc(); i++) {
            if (ctx.argIs(i, "REPLACE")) {
                replace = true;
            } else if (ctx.argIs(i, "DB") && i + 1 < ctx.argc()) {
                if (ctx.longArg(++i) != 0) {
                    throw new CommandException("ERR DB index is out of range");
                }
            } else {
                throw CommandException.SYNTAX;
            }
        }
        if (Arrays.equals(src, dst)) {
            throw new CommandException("ERR source and destination objects are the same");
        }
        Db db = ctx.db();
        KeyEntry s = db.lookupRead(src);
        if (s == null || (!replace && db.lookupRead(dst) != null)) {
            ctx.integer(0);
            return;
        }
        Object copy;
        switch (s.type()) {
            case KeyEntry.STRING: copy = s.stringValue(); break;
            case KeyEntry.HASH: copy = s.hash().copy(db.hasher()); break;
            case KeyEntry.LIST: copy = s.list().copy(); break;
            case KeyEntry.SET: copy = s.set().copy(db.hasher()); break;
            case KeyEntry.STREAM: copy = s.stream().copy(); break;
            default: copy = s.zset().copy(db.hasher()); break;
        }
        long expireAt = s.expireAt();
        KeyEntry d = db.setValue(dst, s.type(), copy, false);
        if (expireAt >= 0) {
            db.setExpire(d, expireAt);
        }
        ctx.propagateAsIs();
        ctx.integer(1);
    }

    static void scan(CommandContext ctx) {
        ScanArgs a = ScanArgs.parse(ctx, 1, true);
        Db db = ctx.db();
        List<KeyEntry> found = new ArrayList<>();
        long cursor = a.cursor;
        long budget = a.count * 10;
        do {
            cursor = db.scan(cursor, found::add);
        } while (cursor != 0 && found.size() < a.count && --budget > 0);
        List<byte[]> keys = new ArrayList<>(found.size());
        for (KeyEntry e : found) {
            if (!e.isAlive()) {
                continue;
            }
            if (db.isExpired(e)) {
                db.lookupRead(e.key);        // expires it (safe: the scan step is over)
                continue;
            }
            if (a.type != null && !KeyEntry.typeName(e.type()).equals(a.type)) {
                continue;
            }
            if (a.match != null && !Glob.match(a.match, e.key)) {
                continue;
            }
            keys.add(e.key);
        }
        ScanArgs.replyCursor(ctx, cursor);
        ctx.arrayHeader(keys.size());
        for (byte[] k : keys) {
            ctx.bulk(k);
        }
    }

    static void keys(CommandContext ctx) {
        byte[] pattern = ctx.arg(1);
        Db db = ctx.db();
        List<KeyEntry> matched = new ArrayList<>();
        if (Glob.isLiteral(pattern)) {
            KeyEntry e = db.peek(pattern);
            if (e != null) {
                matched.add(e);
            }
        } else {
            db.forEach(e -> {
                if (Glob.match(pattern, e.key)) {
                    matched.add(e);
                }
            });
        }
        List<byte[]> out = new ArrayList<>(matched.size());
        for (KeyEntry e : matched) {
            if (db.lookupRead(e.key) != null) {
                out.add(e.key);
            }
        }
        ctx.arrayHeader(out.size());
        for (byte[] k : out) {
            ctx.bulk(k);
        }
    }

    /** Each expired sample is deleted by the lookup, so this ends: with a live key or an empty keyspace. */
    static void randomKey(CommandContext ctx) {
        Db db = ctx.db();
        while (true) {
            KeyEntry e = db.randomEntry();
            if (e == null) {
                ctx.nullBulk();
                return;
            }
            if (db.lookupRead(e.key) != null) {
                ctx.bulk(e.key);
                return;
            }
        }
    }
}
