package com.jredis.server.command;

import com.jredis.server.core.Client;
import com.jredis.server.core.CommandContext;
import com.jredis.server.core.Engine;

import java.util.ArrayList;
import java.util.List;

import static com.jredis.server.command.CommandSpec.FAST;
import static com.jredis.server.command.CommandSpec.TX_CONTROL;

/** MULTI, EXEC, DISCARD, WATCH, UNWATCH. */
final class TransactionCommands {

    private TransactionCommands() {
    }

    static void register(CommandTable t) {
        t.register("MULTI", 1, TX_CONTROL | FAST, TransactionCommands::multi);
        t.register("EXEC", 1, TX_CONTROL, TransactionCommands::exec);
        t.register("DISCARD", 1, TX_CONTROL | FAST, TransactionCommands::discard);
        t.register("WATCH", -2, TX_CONTROL | FAST, TransactionCommands::watch);
        t.register("UNWATCH", 1, FAST, TransactionCommands::unwatch);
    }

    static void multi(CommandContext ctx) {
        Client c = ctx.client();
        if (c.inMulti) {
            throw new CommandException("ERR MULTI calls can not be nested");
        }
        c.inMulti = true;
        c.multiAborted = false;
        c.multiFlags = 0;
        c.multiQueue.clear();
        ctx.ok();
    }

    static void discardState(Engine engine, Client c) {
        c.inMulti = false;
        c.multiAborted = false;
        c.multiFlags = 0;
        c.multiQueue.clear();
        engine.watches().unwatchAll(c);
    }

    static void exec(CommandContext ctx) {
        Client c = ctx.client();
        Engine engine = ctx.engine();
        if (!c.inMulti) {
            throw new CommandException("ERR EXEC without MULTI");
        }
        if (c.multiAborted) {
            discardState(engine, c);
            throw new CommandException("EXECABORT Transaction discarded because of previous errors.");
        }
        // conditions may have changed since the commands were queued (as in Redis)
        if ((c.multiFlags & CommandSpec.WRITE) != 0 && !engine.persistence().writable()) {
            discardState(engine, c);
            throw new CommandException("EXECABORT Transaction discarded because of: " + Engine.MISCONF_ERROR);
        }
        if ((c.multiFlags & CommandSpec.DENYOOM) != 0 && engine.overMaxmemory()) {
            discardState(engine, c);
            throw new CommandException("EXECABORT Transaction discarded because of: " + Engine.OOM_ERROR);
        }
        for (byte[] key : new ArrayList<>(c.watchedKeys)) {
            ctx.db().lookupRead(key);       // a watched key that expired counts as modified
        }
        if (c.dirtyCas) {
            discardState(engine, c);
            ctx.nullArray();
            return;
        }
        List<byte[][]> queued = new ArrayList<>(c.multiQueue);
        discardState(engine, c);
        ctx.arrayHeader(queued.size());
        engine.execTransaction(c, queued);
    }

    static void discard(CommandContext ctx) {
        Client c = ctx.client();
        if (!c.inMulti) {
            throw new CommandException("ERR DISCARD without MULTI");
        }
        discardState(ctx.engine(), c);
        ctx.ok();
    }

    static void watch(CommandContext ctx) {
        Client c = ctx.client();
        if (c.inMulti) {
            throw new CommandException("ERR WATCH inside MULTI is not allowed");
        }
        for (int i = 1; i < ctx.argc(); i++) {
            ctx.db().lookupRead(ctx.arg(i));   // an already-expired key is removed now, not counted as a change later
            ctx.engine().watches().watch(c, ctx.arg(i));
        }
        ctx.ok();
    }

    static void unwatch(CommandContext ctx) {
        ctx.engine().watches().unwatchAll(ctx.client());
        ctx.ok();
    }
}
