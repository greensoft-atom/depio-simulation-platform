package com.jredis.server.command;

import com.jredis.server.core.CommandContext;
import com.jredis.server.pubsub.PubSub;

import java.util.List;

import static com.jredis.server.command.CommandSpec.FAST;
import static com.jredis.server.command.CommandSpec.NO_MULTI;
import static com.jredis.server.command.CommandSpec.PUBSUB;
import static com.jredis.server.command.CommandSpec.READONLY;

/** SUBSCRIBE, PSUBSCRIBE, their UN- forms, PUBLISH and PUBSUB introspection. */
final class PubSubCommands {

    private PubSubCommands() {
    }

    static void register(CommandTable t) {
        t.register("SUBSCRIBE", -2, PUBSUB | NO_MULTI, PubSubCommands::subscribe);
        t.register("UNSUBSCRIBE", -1, PUBSUB | NO_MULTI, PubSubCommands::unsubscribe);
        t.register("PSUBSCRIBE", -2, PUBSUB | NO_MULTI, PubSubCommands::psubscribe);
        t.register("PUNSUBSCRIBE", -1, PUBSUB | NO_MULTI, PubSubCommands::punsubscribe);
        t.register("PUBLISH", 3, FAST, PubSubCommands::publish);
        t.register("PUBSUB", -2, READONLY, PubSubCommands::pubsub);
    }

    static void subscribe(CommandContext ctx) {
        PubSub ps = ctx.engine().pubsub();
        for (int i = 1; i < ctx.argc(); i++) {
            ps.subscribe(ctx.client(), ctx.arg(i));
        }
    }

    static void unsubscribe(CommandContext ctx) {
        PubSub ps = ctx.engine().pubsub();
        if (ctx.argc() == 1) {
            ps.unsubscribeAll(ctx.client(), true);
            return;
        }
        for (int i = 1; i < ctx.argc(); i++) {
            ps.unsubscribe(ctx.client(), ctx.arg(i), true);
        }
    }

    static void psubscribe(CommandContext ctx) {
        PubSub ps = ctx.engine().pubsub();
        for (int i = 1; i < ctx.argc(); i++) {
            ps.psubscribe(ctx.client(), ctx.arg(i));
        }
    }

    static void punsubscribe(CommandContext ctx) {
        PubSub ps = ctx.engine().pubsub();
        if (ctx.argc() == 1) {
            ps.punsubscribeAll(ctx.client(), true);
            return;
        }
        for (int i = 1; i < ctx.argc(); i++) {
            ps.punsubscribe(ctx.client(), ctx.arg(i), true);
        }
    }

    static void publish(CommandContext ctx) {
        ctx.integer(ctx.engine().pubsub().publish(ctx.arg(1), ctx.arg(2)));
    }

    static void pubsub(CommandContext ctx) {
        PubSub ps = ctx.engine().pubsub();
        if (ctx.argIs(1, "CHANNELS") && ctx.argc() <= 3) {
            List<byte[]> channels = ps.channels(ctx.argc() == 3 ? ctx.arg(2) : null);
            ctx.arrayHeader(channels.size());
            for (byte[] c : channels) {
                ctx.bulk(c);
            }
        } else if (ctx.argIs(1, "NUMSUB")) {
            ctx.arrayHeader(2L * (ctx.argc() - 2));
            for (int i = 2; i < ctx.argc(); i++) {
                ctx.bulk(ctx.arg(i));
                ctx.integer(ps.numsub(ctx.arg(i)));
            }
        } else if (ctx.argIs(1, "NUMPAT") && ctx.argc() == 2) {
            ctx.integer(ps.patternCount());
        } else {
            throw new CommandException("ERR unknown subcommand or wrong number of arguments for 'pubsub|"
                    + ctx.argString(1).toLowerCase() + "'");
        }
    }
}
