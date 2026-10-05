package com.jredis.server.command;

import com.jredis.server.core.CommandContext;

import static com.jredis.server.command.CommandSpec.ADMIN;
import static com.jredis.server.command.CommandSpec.FAST;
import static com.jredis.server.command.CommandSpec.NO_MULTI;

/** REPLICAOF, ROLE, and the link's own PSYNC and REPLCONF (docs/16-replication.md). */
final class ReplicationCommands {

    private ReplicationCommands() {
    }

    static void register(CommandTable t) {
        t.register("REPLICAOF", 3, ADMIN | NO_MULTI, ReplicationCommands::replicaof);
        t.register("ROLE", 1, FAST, ctx -> ctx.engine().replication().role(ctx));
        t.register("PSYNC", 3, NO_MULTI, ReplicationCommands::psync);
        t.register("REPLCONF", -2, NO_MULTI, ReplicationCommands::replconf);
        t.register("WAIT", 3, NO_MULTI, ReplicationCommands::waitFor);
    }

    static void replicaof(CommandContext ctx) {
        if (ctx.argIs(1, "NO") && ctx.argIs(2, "ONE")) {
            try {
                ctx.engine().replication().promote();
            } catch (java.io.IOException e) {
                throw new CommandException("ERR could not record the new epoch, so this server stays a replica: " + e.getMessage());
            }
            ctx.ok();
            return;
        }
        long port;
        try {
            port = Long.parseLong(ctx.argString(2));
        } catch (NumberFormatException e) {
            port = -1;
        }
        if (port < 1 || port > 65535) {
            throw new CommandException("ERR invalid primary port");
        }
        if (ctx.engine().replication().replicaOf(ctx.argString(1), (int) port)) {
            ctx.ok();
            ctx.engine().closeListeners();
        } else {
            ctx.simple("OK Already a replica of that primary");
        }
    }

    static void psync(CommandContext ctx) {
        long from;
        try {
            from = Long.parseLong(ctx.argString(2));
        } catch (NumberFormatException e) {
            throw new CommandException("ERR invalid offset");
        }
        ctx.engine().replication().psync(ctx.client(), ctx.argString(1), from);
    }

    static void waitFor(CommandContext ctx) {
        long wanted = ctx.longArg(1);
        long timeout = ctx.longArg(2);
        if (wanted < 0 || timeout < 0) {
            throw new CommandException("ERR timeout is negative");
        }
        ctx.engine().replication().waitFor(ctx.client(), wanted, timeout);
    }

    static void replconf(CommandContext ctx) {
        if (ctx.argc() % 2 == 0) {
            throw CommandException.SYNTAX;
        }
        for (int i = 1; i < ctx.argc(); i += 2) {
            if (ctx.argIs(i, "LISTENING-PORT")) {
                long p = ctx.longArg(i + 1);
                if (p < 0 || p > 65535) {
                    throw new CommandException("ERR invalid listening port");
                }
                ctx.client().replListeningPort = (int) p;
            } else if (ctx.argIs(i, "EPOCH")) {
                ctx.engine().replication().checkEpoch(ctx.longArg(i + 1));
            } else if (ctx.argIs(i, "ACK")) {
                ctx.engine().replication().acknowledged(ctx.client(), ctx.longArg(i + 1));
                return;                              // a replica's connection takes no reply
            } else {
                throw new CommandException("ERR Unrecognized REPLCONF option: " + ctx.argString(i));
            }
        }
        ctx.ok();
    }
}
