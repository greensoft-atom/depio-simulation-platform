package com.jredis.server.command;

import com.jredis.common.Bytes;
import com.jredis.server.Version;
import com.jredis.server.core.Client;
import com.jredis.server.core.CommandContext;
import com.jredis.server.core.Engine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.jredis.server.command.CommandSpec.ADMIN;
import static com.jredis.server.command.CommandSpec.FAST;
import static com.jredis.server.command.CommandSpec.NOAUTH;
import static com.jredis.server.command.CommandSpec.NO_MULTI;
import static com.jredis.server.command.CommandSpec.PUBSUB;
import static com.jredis.server.command.CommandSpec.TX_CONTROL;

/** PING, ECHO, QUIT, RESET, HELLO, AUTH, SELECT, CLIENT, COMMAND. */
final class ConnectionCommands {

    private static final Logger log = LoggerFactory.getLogger(ConnectionCommands.class);
    private static final byte[] PONG = "pong".getBytes(StandardCharsets.US_ASCII);

    private ConnectionCommands() {
    }

    static void register(CommandTable t) {
        t.register("PING", -1, FAST | PUBSUB, ConnectionCommands::ping);
        t.register("ECHO", 2, FAST, ctx -> ctx.bulk(ctx.arg(1)));
        t.register("QUIT", -1, FAST | PUBSUB | NOAUTH | TX_CONTROL, ConnectionCommands::quit);
        t.register("RESET", 1, FAST | PUBSUB | NOAUTH | TX_CONTROL, ConnectionCommands::reset);
        t.register("HELLO", -1, FAST | NOAUTH | NO_MULTI, ConnectionCommands::hello);
        t.register("AUTH", -2, FAST | NOAUTH | NO_MULTI, ConnectionCommands::auth);
        t.register("SELECT", 2, FAST, ConnectionCommands::select);
        t.register("CLIENT", -2, 0, ConnectionCommands::client);
        t.register("COMMAND", -1, 0, ConnectionCommands::command);
    }

    static void ping(CommandContext ctx) {
        if (ctx.argc() > 2) {
            throw new CommandException("ERR wrong number of arguments for 'ping' command");
        }
        if (ctx.client().inPubSubMode()) {
            ctx.arrayHeader(2);
            ctx.bulk(PONG);
            ctx.bulk(ctx.argc() == 2 ? ctx.arg(1) : new byte[0]);
        } else if (ctx.argc() == 2) {
            ctx.bulk(ctx.arg(1));
        } else {
            ctx.simple("PONG");
        }
    }

    static void quit(CommandContext ctx) {
        ctx.ok();
        ctx.engine().closeAfterReplies(ctx.client());
    }

    static void reset(CommandContext ctx) {
        Client c = ctx.client();
        Engine e = ctx.engine();
        TransactionCommands.discardState(e, c);
        e.pubsub().removeClient(c);
        c.name = "";
        c.authenticated = ctx.config().requirepass().isEmpty();
        ctx.simple("RESET");
    }

    private static boolean passwordMatches(CommandContext ctx, byte[] given) {
        byte[] expected = ctx.config().requirepass().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(given, expected);     // constant time
    }

    private static void authFailed(CommandContext ctx) {
        ctx.engine().stats().authFailures++;
        log.warn("authentication failed for client id={} addr={}", ctx.client().id, ctx.client().out.remoteAddress());
        throw new CommandException("WRONGPASS invalid username-password pair or user is disabled.");
    }

    static void auth(CommandContext ctx) {
        if (ctx.argc() > 3) {
            throw CommandException.SYNTAX;
        }
        if (ctx.config().requirepass().isEmpty()) {
            if (ctx.argc() == 3 && ctx.argIs(1, "DEFAULT")) {
                ctx.client().authenticated = true;   // like Redis: the default user has no password, any is accepted
                ctx.ok();
                return;
            }
            if (ctx.argc() == 3) {
                authFailed(ctx);
            }
            throw new CommandException("ERR AUTH <password> called without any password configured for the default user. "
                    + "Are you sure your configuration is correct?");
        }
        if (ctx.argc() == 3 && !ctx.argIs(1, "DEFAULT")) {
            authFailed(ctx);
        }
        if (!passwordMatches(ctx, ctx.arg(ctx.argc() - 1))) {
            authFailed(ctx);
        }
        ctx.client().authenticated = true;
        ctx.ok();
    }

    static void hello(CommandContext ctx) {
        int i = 1;
        if (ctx.argc() >= 2) {
            long version;
            try {
                version = com.jredis.common.NumberCodec.parseLong(ctx.arg(1));
            } catch (NumberFormatException e) {
                throw new CommandException("ERR Protocol version is not an integer or out of range");
            }
            if (version != 2) {
                throw new CommandException("NOPROTO unsupported protocol version");
            }
            i = 2;
        }
        String setName = null;
        for (; i < ctx.argc(); i++) {
            if (ctx.argIs(i, "AUTH") && i + 2 < ctx.argc()) {
                boolean noPassword = ctx.config().requirepass().isEmpty();
                if (!ctx.argIs(i + 1, "DEFAULT") || (!noPassword && !passwordMatches(ctx, ctx.arg(i + 2)))) {
                    authFailed(ctx);
                }
                ctx.client().authenticated = true;
                i += 2;
            } else if (ctx.argIs(i, "SETNAME") && i + 1 < ctx.argc()) {
                setName = validName(ctx.argString(i + 1));
                i++;
            } else {
                throw new CommandException("ERR Syntax error in HELLO option '" + ctx.argString(i) + "'");
            }
        }
        Client c = ctx.client();
        if (!c.authenticated) {
            throw new CommandException("NOAUTH HELLO must be called with the client already authenticated, otherwise the "
                    + "HELLO <proto> AUTH <user> <pass> option can be used to authenticate the client and select "
                    + "the RESP protocol version at the same time");
        }
        if (setName != null) {
            c.name = setName;
        }
        ctx.arrayHeader(14);
        ctx.bulk("server");
        ctx.bulk("j-redis");
        ctx.bulk("version");
        ctx.bulk(Version.VERSION);
        ctx.bulk("proto");
        ctx.integer(2);
        ctx.bulk("id");
        ctx.integer(c.id);
        ctx.bulk("mode");
        ctx.bulk("standalone");
        ctx.bulk("role");
        ctx.bulk("master");
        ctx.bulk("modules");
        ctx.emptyArray();
    }

    static void select(CommandContext ctx) {
        if (ctx.longArg(1) != 0) {
            throw new CommandException("ERR DB index is out of range");
        }
        ctx.ok();
    }

    private static String validName(String name) {
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch < '!' || ch > '~') {
                throw new CommandException("ERR Client names cannot contain spaces, newlines or special characters.");
            }
        }
        return name;
    }

    static void client(CommandContext ctx) {
        String sub = ctx.argString(1).toUpperCase(Locale.ROOT);
        Client self = ctx.client();
        switch (sub) {
            case "ID":
                ctx.integer(self.id);
                return;
            case "GETNAME":
                if (self.name.isEmpty()) {
                    ctx.nullBulk();
                } else {
                    ctx.bulk(self.name);
                }
                return;
            case "SETNAME":
                requireArgs(ctx, 3);
                self.name = validName(ctx.argString(2));
                ctx.ok();
                return;
            case "SETINFO":
                requireArgs(ctx, 4);
                String value = validName(ctx.argString(3));
                if (ctx.argIs(2, "LIB-NAME")) {
                    self.libName = value;
                } else if (ctx.argIs(2, "LIB-VER")) {
                    self.libVersion = value;
                } else {
                    throw new CommandException("ERR Unrecognized option '" + ctx.argString(2) + "'");
                }
                ctx.ok();
                return;
            case "INFO":
                ctx.bulk(clientLine(ctx.engine(), self, ctx.now()));
                return;
            case "LIST":
                StringBuilder sb = new StringBuilder();
                for (Client c : ctx.engine().clients()) {
                    sb.append(clientLine(ctx.engine(), c, ctx.now()));
                }
                ctx.bulk(sb.toString());
                return;
            case "KILL":
                kill(ctx);
                return;
            default:
                throw new CommandException("ERR unknown subcommand '" + ctx.argString(1) + "'. Try CLIENT HELP.");
        }
    }

    private static void requireArgs(CommandContext ctx, int n) {
        if (ctx.argc() != n) {
            throw new CommandException("ERR wrong number of arguments for 'client|" + ctx.argString(1).toLowerCase(Locale.ROOT) + "' command");
        }
    }

    static String clientLine(Engine engine, Client c, long now) {
        StringBuilder flags = new StringBuilder();
        if (c.isBlocked()) {
            flags.append('b');
        }
        if (c.inPubSubMode()) {
            flags.append('P');
        }
        if (c.inMulti) {
            flags.append('x');
        }
        if (c.dirtyCas) {
            flags.append('d');
        }
        if (c.closeAfterReply) {
            flags.append('c');
        }
        if (c.replica != null) {
            flags.append('S');                   // a replica, as in Redis
        }
        if (flags.length() == 0) {
            flags.append('N');
        }
        return "id=" + c.id
                + " addr=" + c.out.remoteAddress()
                + " laddr=" + c.out.localAddress()
                + " name=" + c.name
                + " age=" + (now - c.createdAtMillis) / 1000
                + " idle=" + (now - c.lastInteractionMillis) / 1000
                + " flags=" + flags
                + " db=0"
                + " sub=" + c.channels.size()
                + " psub=" + c.patterns.size()
                + " multi=" + (c.inMulti ? c.multiQueue.size() : -1)
                + " watch=" + c.watchedKeys.size()
                + " omem=" + c.out.pendingBytes()
                + " cmd=" + c.lastCommand.toLowerCase(Locale.ROOT)
                + " user=default"
                + " lib-name=" + c.libName
                + " lib-ver=" + c.libVersion
                + "\n";
    }

    private static void kill(CommandContext ctx) {
        Engine engine = ctx.engine();
        Client self = ctx.client();
        if (ctx.argc() == 2) {                       // no filter: never "kill everybody"
            throw new CommandException("ERR wrong number of arguments for 'client|kill' command");
        }
        if (ctx.argc() == 3) {                       // old form: CLIENT KILL addr:port
            String addr = ctx.argString(2);
            for (Client c : new ArrayList<>(engine.clients())) {
                if (addr.equals(c.out.remoteAddress())) {
                    killOne(engine, self, c);
                    ctx.ok();
                    return;
                }
            }
            throw new CommandException("ERR No such client");
        }
        Long id = null;
        String addr = null;
        String laddr = null;
        String type = null;
        boolean skipMe = true;
        for (int i = 2; i < ctx.argc(); i += 2) {
            if (i + 1 >= ctx.argc()) {
                throw CommandException.SYNTAX;
            }
            String opt = ctx.argString(i).toUpperCase(Locale.ROOT);
            String val = ctx.argString(i + 1);
            switch (opt) {
                case "ID": id = ctx.longArg(i + 1); break;
                case "ADDR": addr = val; break;
                case "LADDR": laddr = val; break;
                case "TYPE":
                    type = val.toLowerCase(Locale.ROOT);
                    if (!type.equals("normal") && !type.equals("pubsub") && !type.equals("master")
                            && !type.equals("replica") && !type.equals("slave")) {
                        throw new CommandException("ERR Unknown client type '" + Bytes.printable(ctx.arg(i + 1), 64) + "'");
                    }
                    break;
                case "USER": if (!val.equals("default")) { ctx.integer(0); return; } break;
                case "SKIPME": skipMe = val.equalsIgnoreCase("yes"); break;
                default: throw CommandException.SYNTAX;
            }
        }
        int killed = 0;
        List<Client> all = new ArrayList<>(engine.clients());
        for (Client c : all) {
            if (id != null && c.id != id) {
                continue;
            }
            if (addr != null && !addr.equals(c.out.remoteAddress())) {
                continue;
            }
            if (laddr != null && !laddr.equals(c.out.localAddress())) {
                continue;
            }
            if (c.closeAfterReply || c.closed) {
                continue;                                  // already going away
            }
            if (type != null && !matchesType(type, c)) {
                continue;
            }
            if (skipMe && c == self) {
                continue;
            }
            killOne(engine, self, c);
            killed++;
        }
        ctx.integer(killed);
    }

    /** There are no replicas, so master/replica/slave match nobody. */
    private static boolean matchesType(String type, Client c) {
        switch (type) {
            case "normal": return !c.inPubSubMode();
            case "pubsub": return c.inPubSubMode();
            default: return false;
        }
    }

    private static void killOne(Engine engine, Client self, Client target) {
        if (target == self) {
            engine.closeAfterReplies(target);      // reply first, then close
        } else {
            engine.killClient(target);
        }
    }

    static void command(CommandContext ctx) {
        CommandTable table = ctx.engine().table();
        List<CommandSpec> enabled = table.enabled();
        if (ctx.argc() == 1 || (ctx.argc() == 2 && ctx.argIs(1, "INFO"))) {   // COMMAND, COMMAND INFO: all
            ctx.arrayHeader(enabled.size());
            for (CommandSpec spec : enabled) {
                commandInfo(ctx, spec);
            }
            return;
        }
        String sub = ctx.argString(1).toUpperCase(Locale.ROOT);
        switch (sub) {
            case "COUNT":
                ctx.integer(enabled.size());
                return;
            case "DOCS":
                ctx.emptyArray();
                return;
            case "LIST":
                ctx.arrayHeader(enabled.size());
                for (CommandSpec spec : enabled) {
                    ctx.bulk(spec.name.toLowerCase(Locale.ROOT));
                }
                return;
            case "INFO":
                ctx.arrayHeader(ctx.argc() - 2);
                for (int i = 2; i < ctx.argc(); i++) {
                    CommandSpec spec = table.lookup(ctx.arg(i));
                    if (spec == null || spec.disabled) {
                        ctx.nullArray();
                    } else {
                        commandInfo(ctx, spec);
                    }
                }
                return;
            default:
                throw new CommandException("ERR unknown subcommand '" + ctx.argString(1) + "'. Try COMMAND HELP.");
        }
    }

    private static void commandInfo(CommandContext ctx, CommandSpec spec) {
        List<String> flags = new ArrayList<>();
        if (spec.has(CommandSpec.WRITE)) flags.add("write");
        if (spec.has(CommandSpec.READONLY)) flags.add("readonly");
        if (spec.has(CommandSpec.DENYOOM)) flags.add("denyoom");
        if (spec.has(CommandSpec.FAST)) flags.add("fast");
        if (spec.has(CommandSpec.BLOCKING)) flags.add("blocking");
        if (spec.has(CommandSpec.PUBSUB)) flags.add("pubsub");
        if (spec.has(CommandSpec.NOAUTH)) flags.add("no_auth");
        if (spec.has(ADMIN)) flags.add("admin");
        if (spec.has(NO_MULTI)) flags.add("no_multi");
        ctx.arrayHeader(6);
        ctx.bulk(spec.name.toLowerCase(Locale.ROOT));
        ctx.integer(spec.arity);
        ctx.arrayHeader(flags.size());
        for (String f : flags) {
            ctx.simple(f);
        }
        ctx.integer(0);
        ctx.integer(0);
        ctx.integer(0);
    }
}
