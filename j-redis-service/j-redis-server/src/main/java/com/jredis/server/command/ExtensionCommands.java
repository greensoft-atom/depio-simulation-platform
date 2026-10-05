package com.jredis.server.command;

import com.jredis.server.core.CommandContext;
import com.jredis.server.db.KeyEntry;

import java.util.Arrays;

import static com.jredis.server.command.CommandSpec.DENYOOM;
import static com.jredis.server.command.CommandSpec.FAST;
import static com.jredis.server.command.CommandSpec.WRITE;

/**
 * J.CAS and J.CAD: compare-and-set / compare-and-delete on strings. They exist because standard
 * Redis needs a Lua script to do these atomically (e.g. releasing a lock only if still owned).
 * J.ZAROUND lives with the sorted-set commands.
 */
final class ExtensionCommands {

    private ExtensionCommands() {
    }

    static void register(CommandTable t) {
        t.register("J.CAS", -4, WRITE | DENYOOM | FAST, ExtensionCommands::cas);
        t.register("J.CAD", 3, WRITE | FAST, ExtensionCommands::cad);
    }

    /** J.CAS key expected new [EX s | PX ms | KEEPTTL] → 1 if swapped, 0 otherwise. */
    static void cas(CommandContext ctx) {
        byte[] key = ctx.arg(1);
        byte[] expected = ctx.arg(2);
        byte[] next = ctx.arg(3);
        StringCommands.SetOptions o = StringCommands.parseSetOptions(ctx, 4, false);
        KeyEntry e = Keys.read(ctx, key, KeyEntry.STRING);
        if (e == null || !Arrays.equals(e.stringValue(), expected)) {
            ctx.integer(0);
            return;
        }
        StringCommands.storeString(ctx, key, next, o.keepTtl, o.expireAt);
        ctx.integer(1);
    }

    /** J.CAD key expected → 1 if the key held exactly expected and was deleted, 0 otherwise. */
    static void cad(CommandContext ctx) {
        byte[] key = ctx.arg(1);
        KeyEntry e = Keys.read(ctx, key, KeyEntry.STRING);
        if (e == null || !Arrays.equals(e.stringValue(), ctx.arg(2))) {
            ctx.integer(0);
            return;
        }
        ctx.db().deleteEntry(e);
        ctx.propagate(Words.DEL, key);
        ctx.integer(1);
    }
}
