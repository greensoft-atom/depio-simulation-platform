package com.jredis.server.config;

import com.jredis.common.Glob;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The single registry of configuration directives. The config file, command-line overrides and
 * {@code CONFIG GET/SET} all go through it, so every directive is parsed and validated in exactly
 * one place.
 */
public final class Directives {

    private interface Setter {
        void set(ServerConfig c, String[] args) throws ConfigException;
    }

    private interface Getter {
        String get(ServerConfig c);
    }

    private static final class Directive {
        final String name;
        final boolean runtime;
        final int minArgs;
        final int maxArgs;
        final Setter setter;
        final Getter getter;

        Directive(String name, boolean runtime, int minArgs, int maxArgs, Setter setter, Getter getter) {
            this.name = name;
            this.runtime = runtime;
            this.minArgs = minArgs;
            this.maxArgs = maxArgs;
            this.setter = setter;
            this.getter = getter;
        }
    }

    private static final Map<String, Directive> DIRECTIVES = new LinkedHashMap<>();

    private static void fixed(String name, int minArgs, int maxArgs, Setter s, Getter g) {
        DIRECTIVES.put(name, new Directive(name, false, minArgs, maxArgs, s, g));
    }

    private static void runtime(String name, Setter s, Getter g) {
        DIRECTIVES.put(name, new Directive(name, true, 1, 1, s, g));
    }

    private static void runtime(String name, int minArgs, int maxArgs, Setter s, Getter g) {
        DIRECTIVES.put(name, new Directive(name, true, minArgs, maxArgs, s, g));
    }

    static {
        fixed("port", 1, 1, (c, a) -> c.port(intRange(a[0], 0, 65535)), c -> Integer.toString(c.port()));
        fixed("bind", 1, 16, (c, a) -> c.bind(Arrays.asList(a)), c -> String.join(" ", c.bind()));
        fixed("tcp-keepalive", 1, 1, (c, a) -> c.tcpKeepalive(intRange(a[0], 0, Integer.MAX_VALUE)), c -> Integer.toString(c.tcpKeepalive()));
        fixed("io-threads", 1, 1, (c, a) -> c.ioThreads(intRange(a[0], 1, 128)), c -> Integer.toString(c.ioThreads()));
        fixed("dir", 1, 1, (c, a) -> c.dir(a[0]), ServerConfig::dir);
        fixed("appendonly", 1, 1, (c, a) -> c.appendonly(yesNo(a[0])), c -> yn(c.appendonly()));
        fixed("maxmemory-policy", 1, 1, (c, a) -> {
            if (!"noeviction".equalsIgnoreCase(a[0])) {
                throw new ConfigException("maxmemory-policy: only 'noeviction' is supported");
            }
            c.maxmemoryPolicy("noeviction");
        }, ServerConfig::maxmemoryPolicy);
        fixed("disable-command", 1, 64, (c, a) -> {
            for (String cmd : a) {
                c.disabledCommands().add(cmd.toUpperCase(Locale.ROOT));
            }
        }, c -> String.join(" ", c.disabledCommands()));
        fixed("enable-debug-command", 1, 1, (c, a) -> c.enableDebugCommand(yesNo(a[0])), c -> yn(c.enableDebugCommand()));
        fixed("replicaof", 2, 2, (c, a) -> c.replicaof(a[0], intRange(a[1], 1, 65535)),
                c -> c.replicaofHost() == null ? "" : c.replicaofHost() + " " + c.replicaofPort());
        fixed("repl-backlog-size", 1, 1, (c, a) -> {
            long v = memory(a[0]);
            if (v < 16 * 1024 || v > Integer.MAX_VALUE) {
                throw new ConfigException("repl-backlog-size must be between 16kb and 2gb");
            }
            c.replBacklogSize(v);
        }, c -> Long.toString(c.replBacklogSize()));

        runtime("protected-mode", (c, a) -> c.protectedMode(yesNo(a[0])), c -> yn(c.protectedMode()));
        runtime("requirepass", (c, a) -> c.requirepass(a[0]), ServerConfig::requirepass);
        runtime("primaryauth", (c, a) -> c.primaryauth(a[0]), ServerConfig::primaryauth);
        runtime("repl-timeout", (c, a) -> c.replTimeout(intRange(a[0], 1, 3600)), c -> Integer.toString(c.replTimeout()));
        runtime("min-replicas-to-write", (c, a) -> c.minReplicasToWrite(intRange(a[0], 0, 1000)),
                c -> Integer.toString(c.minReplicasToWrite()));
        runtime("min-replicas-max-lag", (c, a) -> c.minReplicasMaxLag(intRange(a[0], 1, 3600)),
                c -> Integer.toString(c.minReplicasMaxLag()));
        runtime("repl-ping-replica-period", (c, a) -> c.replPingReplicaPeriod(intRange(a[0], 1, 3600)),
                c -> Integer.toString(c.replPingReplicaPeriod()));
        runtime("maxclients", (c, a) -> c.maxclients(intRange(a[0], 1, 1_000_000)), c -> Integer.toString(c.maxclients()));
        runtime("timeout", (c, a) -> c.timeout(intRange(a[0], 0, Integer.MAX_VALUE)), c -> Integer.toString(c.timeout()));
        runtime("appendfsync", (c, a) -> {
            String v = a[0].toLowerCase(Locale.ROOT);
            if (v.equals("everysec")) {
                c.appendfsync(ServerConfig.FsyncPolicy.EVERYSEC);
            } else if (v.equals("no")) {
                c.appendfsync(ServerConfig.FsyncPolicy.NO);
            } else {
                throw new ConfigException("appendfsync must be 'everysec' or 'no'");
            }
        }, c -> c.appendfsync().name().toLowerCase(Locale.ROOT));
        runtime("appendfsync-interval-millis", (c, a) -> c.appendfsyncIntervalMillis(intRange(a[0], 10, 60_000)),
                c -> Integer.toString(c.appendfsyncIntervalMillis()));
        runtime("aof-load-truncated", (c, a) -> c.aofLoadTruncated(yesNo(a[0])), c -> yn(c.aofLoadTruncated()));
        runtime("auto-aof-rewrite-percentage", (c, a) -> c.autoAofRewritePercentage(intRange(a[0], 0, 100_000)),
                c -> Integer.toString(c.autoAofRewritePercentage()));
        runtime("auto-aof-rewrite-min-size", (c, a) -> c.autoAofRewriteMinSize(memory(a[0])), c -> Long.toString(c.autoAofRewriteMinSize()));
        runtime("maxmemory", (c, a) -> c.maxmemory(memory(a[0])), c -> Long.toString(c.maxmemory()));
        runtime("proto-max-bulk-len", (c, a) -> {
            long v = memory(a[0]);
            if (v < 1024 || v > Integer.MAX_VALUE - 16) {
                throw new ConfigException("proto-max-bulk-len must be between 1kb and 2gb");
            }
            c.protoMaxBulkLen(v);
        }, c -> Long.toString(c.protoMaxBulkLen()));
        runtime("client-query-buffer-limit", (c, a) -> c.clientQueryBufferLimit(Math.max(1024 * 1024, memory(a[0]))),
                c -> Long.toString(c.clientQueryBufferLimit()));
        runtime("client-output-buffer-limit", 4, 12, (c, a) -> {
            if (a.length % 4 != 0) {
                throw new ConfigException("client-output-buffer-limit: expected <class> <hard> <soft> <seconds>");
            }
            for (int i = 0; i < a.length; i += 4) {
                long hard = memory(a[i + 1]);
                long soft = memory(a[i + 2]);
                int secs = intRange(a[i + 3], 0, Integer.MAX_VALUE);
                String cls = a[i].toLowerCase(Locale.ROOT);
                if (cls.equals("pubsub")) {
                    c.pubsubOutputLimits(hard, soft, secs);
                } else if (cls.equals("normal")) {
                    c.normalOutputLimits(hard, soft, secs);
                } else if (cls.equals("replica")) {
                    c.replicaOutputLimits(hard, soft, secs);
                } else {
                    throw new ConfigException("client-output-buffer-limit: class must be 'normal', 'pubsub' or 'replica'");
                }
            }
        }, c -> "normal " + c.normalOutputHardLimit() + " " + c.normalOutputSoftLimit() + " " + c.normalOutputSoftSeconds()
                + " pubsub " + c.pubsubOutputHardLimit() + " " + c.pubsubOutputSoftLimit() + " " + c.pubsubOutputSoftSeconds()
                + " replica " + c.replicaOutputHardLimit() + " " + c.replicaOutputSoftLimit() + " " + c.replicaOutputSoftSeconds());
        runtime("slowlog-log-slower-than", (c, a) -> c.slowlogLogSlowerThan(longValue(a[0], -1)),
                c -> Long.toString(c.slowlogLogSlowerThan()));
        runtime("slowlog-max-len", (c, a) -> c.slowlogMaxLen(intRange(a[0], 0, 1_000_000)), c -> Integer.toString(c.slowlogMaxLen()));
        runtime("background-slice-micros", (c, a) -> c.backgroundSliceMicros(intRange(a[0], 50, 100_000)),
                c -> Integer.toString(c.backgroundSliceMicros()));
        runtime("background-max-duty", (c, a) -> c.backgroundMaxDuty(intRange(a[0], 1, 100)),
                c -> Integer.toString(c.backgroundMaxDuty()));
    }

    private Directives() {
    }

    /** Applies a directive from the config file or command line (fixed and runtime directives). */
    public static void apply(ServerConfig c, String name, String[] args) throws ConfigException {
        Directive d = DIRECTIVES.get(name.toLowerCase(Locale.ROOT));
        if (d == null) {
            throw new ConfigException("unknown directive '" + name + "'");
        }
        args = splitIfMulti(d, args);
        if (args.length < d.minArgs || args.length > d.maxArgs) {
            throw new ConfigException("wrong number of arguments for '" + d.name + "'");
        }
        d.setter.set(c, args);
    }

    /**
     * A directive that takes several arguments also accepts them as one quoted value, e.g.
     * {@code --client-output-buffer-limit "pubsub 32mb 8mb 60"} (as Redis does). Single-argument
     * directives keep their value exactly, spaces included (a password may contain spaces).
     */
    private static String[] splitIfMulti(Directive d, String[] args) {
        if (args.length == 1 && d.maxArgs > 1 && !args[0].trim().isEmpty()) {
            return args[0].trim().split("\\s+");
        }
        return args;
    }

    /** {@code CONFIG SET name value}: one value as the client sent it. */
    public static void setAtRuntime(ServerConfig c, String name, String value) throws ConfigException {
        setAtRuntime(c, name, new String[]{value});
    }

    /** {@code CONFIG SET}: only runtime directives. */
    public static void setAtRuntime(ServerConfig c, String name, String[] args) throws ConfigException {
        Directive d = DIRECTIVES.get(name.toLowerCase(Locale.ROOT));
        if (d == null) {
            throw new ConfigException("Unknown option or number of arguments for CONFIG SET - '" + name + "'");
        }
        if (!d.runtime) {
            throw new ConfigException("CONFIG SET failed (possibly related to argument '" + name + "') - can't set immutable config");
        }
        args = splitIfMulti(d, args);
        if (args.length < d.minArgs || args.length > d.maxArgs) {
            throw new ConfigException("wrong number of arguments for '" + d.name + "'");
        }
        d.setter.set(c, args);
    }

    /** {@code CONFIG GET pattern}: name/value pairs of every directive matching the glob. */
    public static List<String[]> get(ServerConfig c, String pattern) {
        List<String[]> out = new ArrayList<>();
        byte[] p = pattern.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        for (Directive d : DIRECTIVES.values()) {
            if (Glob.match(p, d.name.getBytes(StandardCharsets.UTF_8))) {
                out.add(new String[]{d.name, d.getter.get(c)});
            }
        }
        return out;
    }

    public static List<String> names() {
        return Collections.unmodifiableList(new ArrayList<>(DIRECTIVES.keySet()));
    }

    // ---------------------------------------------------------------- value parsing

    static boolean yesNo(String v) throws ConfigException {
        if (v.equalsIgnoreCase("yes")) {
            return true;
        }
        if (v.equalsIgnoreCase("no")) {
            return false;
        }
        throw new ConfigException("argument must be 'yes' or 'no', got '" + v + "'");
    }

    private static String yn(boolean b) {
        return b ? "yes" : "no";
    }

    static int intRange(String v, int min, int max) throws ConfigException {
        long l = longValue(v, min);
        if (l < min || l > max) {
            throw new ConfigException("value '" + v + "' out of range [" + min + ", " + max + "]");
        }
        return (int) l;
    }

    private static long longValue(String v, long min) throws ConfigException {
        try {
            long l = Long.parseLong(v.trim());
            if (l < min) {
                throw new ConfigException("value '" + v + "' must be >= " + min);
            }
            return l;
        } catch (NumberFormatException e) {
            throw new ConfigException("invalid number '" + v + "'");
        }
    }

    /**
     * Redis memory notation: a plain number of bytes, or a number with k/m/g (powers of 1000) or
     * kb/mb/gb (powers of 1024), case-insensitive.
     */
    public static long memory(String v) throws ConfigException {
        String s = v.trim().toLowerCase(Locale.ROOT);
        long mul = 1;
        String digits = s;
        String[][] units = {{"kb", "1024"}, {"mb", "1048576"}, {"gb", "1073741824"},
                {"k", "1000"}, {"m", "1000000"}, {"g", "1000000000"}, {"b", "1"}};
        for (String[] u : units) {
            if (s.endsWith(u[0])) {
                mul = Long.parseLong(u[1]);
                digits = s.substring(0, s.length() - u[0].length());
                break;
            }
        }
        try {
            long n = Long.parseLong(digits);
            if (n < 0) {
                throw new ConfigException("memory value must not be negative: '" + v + "'");
            }
            return Math.multiplyExact(n, mul);
        } catch (NumberFormatException | ArithmeticException e) {
            throw new ConfigException("invalid memory value '" + v + "'");
        }
    }
}
