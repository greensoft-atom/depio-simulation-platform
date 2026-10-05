package com.jredis.server.command;

import com.jredis.server.config.ConfigException;
import com.jredis.server.config.Directives;
import com.jredis.server.config.ServerConfig;
import com.jredis.server.persist.Persistence;
import com.jredis.server.core.CommandContext;
import com.jredis.server.core.Engine;
import com.jredis.server.db.Digest;
import com.jredis.server.info.Info;
import com.jredis.server.info.SlowLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.jredis.server.command.CommandSpec.ADMIN;
import static com.jredis.server.command.CommandSpec.FAST;
import static com.jredis.server.command.CommandSpec.NO_MULTI;
import static com.jredis.server.command.CommandSpec.READONLY;
import static com.jredis.server.command.CommandSpec.WRITE;

/** DBSIZE, TIME, INFO, CONFIG, SLOWLOG, FLUSHALL, SAVE, BGREWRITEAOF, LASTSAVE, SHUTDOWN, DEBUG. */
final class ServerCommands {

    private ServerCommands() {
    }

    static void register(CommandTable t, boolean debug) {
        t.register("DBSIZE", 1, READONLY | FAST, ctx -> ctx.integer(ctx.db().size()));
        t.register("TIME", 1, FAST, ServerCommands::time);
        t.register("INFO", -1, 0, ServerCommands::info);
        t.register("CONFIG", -2, ADMIN, ServerCommands::config);
        t.register("SLOWLOG", -2, ADMIN, ServerCommands::slowlog);
        t.register("FLUSHALL", -1, WRITE | ADMIN, ServerCommands::flushall);
        t.register("FLUSHDB", -1, WRITE | ADMIN, ServerCommands::flushall);
        t.register("SAVE", 1, ADMIN | NO_MULTI, ServerCommands::save);
        t.register("BGSAVE", -1, ADMIN | NO_MULTI, ctx -> bgrewrite(ctx, "Background saving started"));
        t.register("BGREWRITEAOF", 1, ADMIN | NO_MULTI, ctx -> bgrewrite(ctx, "Background append only file rewriting started"));
        t.register("LASTSAVE", 1, FAST, ctx -> ctx.integer(ctx.engine().persistence().lastSaveSeconds()));
        t.register("SHUTDOWN", -1, ADMIN | NO_MULTI, ServerCommands::shutdown);
        if (debug) {
            t.register("DEBUG", -2, ADMIN | NO_MULTI, ServerCommands::debug);
        }
    }

    private static long timeAnchorMillis = System.currentTimeMillis();
    private static long timeAnchorNanos = System.nanoTime();

    /**
     * Wall-clock microseconds: the monotonic clock measured from an anchor, re-anchored whenever
     * it drifts from the wall clock by more than a millisecond (NTP or manual adjustments).
     */
    private static synchronized long nowMicros() {
        long millis = System.currentTimeMillis();
        long nanos = System.nanoTime();
        long micros = timeAnchorMillis * 1000 + (nanos - timeAnchorNanos) / 1000;
        if (Math.abs(micros / 1000 - millis) > 1) {
            timeAnchorMillis = millis;
            timeAnchorNanos = nanos;
            micros = millis * 1000;
        }
        return micros;
    }

    static void time(CommandContext ctx) {
        long micros = nowMicros();
        ctx.arrayHeader(2);
        ctx.bulk(Long.toString(micros / 1_000_000));
        ctx.bulk(Long.toString(micros % 1_000_000));
    }

    static void info(CommandContext ctx) {
        List<String> sections = new ArrayList<>();
        for (int i = 1; i < ctx.argc(); i++) {
            sections.add(ctx.argString(i));
        }
        ctx.bulk(Info.build(ctx.engine(), sections));
    }

    static void config(CommandContext ctx) {
        String sub = ctx.argString(1).toUpperCase(Locale.ROOT);
        switch (sub) {
            case "GET": {
                if (ctx.argc() < 3) {
                    throw new CommandException("ERR wrong number of arguments for 'config|get' command");
                }
                Map<String, String> out = new LinkedHashMap<>();
                for (int i = 2; i < ctx.argc(); i++) {
                    for (String[] kv : Directives.get(ctx.config(), ctx.argString(i))) {
                        out.put(kv[0], kv[1]);
                    }
                }
                ctx.arrayHeader(2L * out.size());
                for (Map.Entry<String, String> kv : out.entrySet()) {
                    ctx.bulk(kv.getKey());
                    ctx.bulk(kv.getValue());
                }
                return;
            }
            case "SET": {
                if (ctx.argc() < 4 || ctx.argc() % 2 != 0) {
                    throw new CommandException("ERR wrong number of arguments for 'config|set' command");
                }
                // all or nothing: validate every pair on a scratch config, then apply
                ServerConfig scratch = new ServerConfig();
                for (int pass = 0; pass < 2; pass++) {
                    for (int i = 2; i < ctx.argc(); i += 2) {
                        try {
                            Directives.setAtRuntime(pass == 0 ? scratch : ctx.config(), ctx.argString(i), ctx.argString(i + 1));
                        } catch (ConfigException e) {
                            throw new CommandException("ERR " + e.getMessage());
                        }
                    }
                }
                ctx.ok();
                return;
            }
            case "RESETSTAT": {
                Engine e = ctx.engine();
                for (CommandSpec spec : e.table().all()) {
                    spec.stat.reset();
                }
                e.stats().reset();
                ctx.ok();
                return;
            }
            case "REWRITE":
                throw new CommandException("ERR CONFIG REWRITE is not supported: edit the config file instead");
            default:
                throw new CommandException("ERR unknown subcommand '" + ctx.argString(1) + "'. Try CONFIG HELP.");
        }
    }

    static void slowlog(CommandContext ctx) {
        SlowLog log = ctx.engine().slowlog();
        String sub = ctx.argString(1).toUpperCase(Locale.ROOT);
        switch (sub) {
            case "GET": {
                long count = ctx.argc() > 2 ? ctx.longArg(2) : 10;
                List<SlowLog.Entry> entries = log.get(count < 0 ? Integer.MAX_VALUE : (int) Math.min(count, Integer.MAX_VALUE));
                ctx.arrayHeader(entries.size());
                for (SlowLog.Entry e : entries) {
                    ctx.arrayHeader(6);
                    ctx.integer(e.id);
                    ctx.integer(e.timestampSeconds);
                    ctx.integer(e.durationMicros);
                    ctx.arrayHeader(e.args.size());
                    for (String a : e.args) {
                        ctx.bulk(a);
                    }
                    ctx.bulk(e.clientAddress);
                    ctx.bulk(e.clientName);
                }
                return;
            }
            case "LEN":
                ctx.integer(log.size());
                return;
            case "RESET":
                log.reset();
                ctx.ok();
                return;
            default:
                throw new CommandException("ERR unknown subcommand '" + ctx.argString(1) + "'. Try SLOWLOG HELP.");
        }
    }

    static void flushall(CommandContext ctx) {
        if (ctx.argc() > 2 || (ctx.argc() == 2 && !ctx.argIs(1, "ASYNC") && !ctx.argIs(1, "SYNC"))) {
            throw CommandException.SYNTAX;
        }
        Engine e = ctx.engine();
        e.persistence().abortRewrite("FLUSHALL");
        e.replication().abortSync("FLUSHALL");            // as a rewrite: the replicas ask again, and get a small image
        e.watches().touchExisting(ctx.db());           // as Redis: a WATCH on a missing key survives FLUSHALL
        ctx.db().flushAll();
        ctx.propagate(Words.FLUSHALL);
        ctx.ok();
    }

    static void save(CommandContext ctx) {
        Engine e = ctx.engine();
        if (e.persistence().rewriteInProgress()) {
            throw new CommandException("ERR Background save already in progress");
        }
        try {
            e.persistence().saveBlocking();
        } catch (Exception ex) {
            String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            throw new CommandException(msg.startsWith("ERR") ? msg : "ERR " + msg);
        }
        ctx.ok();
    }

    static void bgrewrite(CommandContext ctx, String startedMessage) {
        if (ctx.argc() > 2 || (ctx.argc() == 2 && !ctx.argIs(1, "SCHEDULE"))) {
            throw CommandException.SYNTAX;
        }
        Persistence p = ctx.engine().persistence();
        if (ctx.argc() == 2 && p.rewriteInProgress()) {      // BGSAVE SCHEDULE
            p.scheduleRewrite();
            ctx.simple("Background saving scheduled");
            return;
        }
        String error = p.startRewrite();
        if (error != null) {
            throw new CommandException(error);
        }
        ctx.simple(startedMessage);
    }

    static void shutdown(CommandContext ctx) {
        boolean save = false;
        for (int i = 1; i < ctx.argc(); i++) {
            if (ctx.argIs(i, "SAVE")) {
                save = true;
            } else if (ctx.argIs(i, "NOSAVE")) {
                save = false;
            } else if (ctx.argIs(i, "NOW") || ctx.argIs(i, "FORCE")) {
                continue;
            } else {
                throw CommandException.SYNTAX;
            }
        }
        ctx.engine().shutdownFromCommand(save);
    }

    static void debug(CommandContext ctx) {
        String sub = ctx.argString(1).toUpperCase(Locale.ROOT);
        switch (sub) {
            case "SLEEP": {
                if (ctx.argc() != 3) {
                    throw CommandException.SYNTAX;
                }
                double seconds = ctx.doubleArg(2);
                try {
                    Thread.sleep((long) (seconds * 1000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                ctx.ok();
                return;
            }
            case "RELOAD":
                try {
                    ctx.engine().persistence().debugReload();
                } catch (Exception e) {
                    String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                    throw new CommandException(msg.startsWith("ERR") ? msg : "ERR " + msg);
                }
                ctx.ok();
                return;
            case "DIGEST":
                if (ctx.argc() != 2) {
                    throw CommandException.SYNTAX;
                }
                ctx.bulk(Digest.of(ctx.engine().db()));
                return;
            case "DIGEST-VALUE":
                ctx.arrayHeader(ctx.argc() - 2);
                for (int i = 2; i < ctx.argc(); i++) {
                    ctx.bulk(Digest.of(ctx.db().lookupRead(ctx.arg(i))));
                }
                return;
            case "SET-ACTIVE-EXPIRE":
                if (ctx.argc() != 3) {
                    throw CommandException.SYNTAX;
                }
                ctx.engine().activeExpire(ctx.longArg(2) != 0);
                ctx.ok();
                return;
            default:
                throw new CommandException("ERR unknown DEBUG subcommand '" + ctx.argString(1) + "'");
        }
    }
}
