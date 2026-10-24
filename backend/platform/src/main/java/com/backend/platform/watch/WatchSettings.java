package com.backend.platform.watch;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.backend.common.RefusedConfiguration;
import com.backend.common.Secrets;

/**
 * The watch's settings (04 §12, D-86), read once at the platform's start: what it reads, where it sends, how
 * often it repeats. Anything it cannot use refuses the start (exit 2), before the platform serves.
 *
 * @param targets this machine's processes' metrics, each a job and a loopback address; empty: the watch is off
 * @param peers the other machines' platforms, by name, whose {@code /health} is read
 * @param disks the paths whose file systems are watched
 * @param machine this machine's name on every alert it raises
 * @param webhook where alerts are sent, or null for nowhere but the store, the admin API and the journal
 * @param repeatMillis how often a firing alert is sent again
 */
public record WatchSettings(List<Target> targets, List<Peer> peers, List<Path> disks, String machine, URI webhook,
                            long repeatMillis) {

    public record Target(String job, String host, int port) { }

    public record Peer(String name, String host, int port) { }

    public static final String TARGETS = "BACKEND_WATCH_TARGETS";
    public static final String PEERS = "BACKEND_WATCH_PEERS";
    public static final String DISKS = "BACKEND_WATCH_DISKS";
    public static final String MACHINE = "BACKEND_WATCH_MACHINE";
    public static final String WEBHOOK = "BACKEND_ALERT_WEBHOOK_URL";
    public static final String REPEAT_HOURS = "BACKEND_ALERT_REPEAT_HOURS";

    private static final List<Path> DEFAULT_DISKS = List.of(Path.of("/"), Path.of("/var/lib"), Path.of("/var/log"), Path.of("/opt/backend"));
    private static final int DEFAULT_REPEAT_HOURS = 4;

    public WatchSettings {
        targets = List.copyOf(targets);
        peers = List.copyOf(peers);
        disks = List.copyOf(disks);
    }

    public boolean enabled() {
        return !targets.isEmpty();
    }

    public static WatchSettings fromEnvironment(Map<String, String> env) {
        List<Target> targets = new ArrayList<>();
        for (String[] pair : pairs(env, TARGETS, "job=host:port")) {
            String[] address = hostPort(TARGETS, pair[1]);
            targets.add(new Target(pair[0], address[0], Integer.parseInt(address[1])));
        }
        List<Peer> peers = new ArrayList<>();
        for (String[] pair : pairs(env, PEERS, "name=host:port")) {
            String[] address = hostPort(PEERS, pair[1]);
            peers.add(new Peer(pair[0], address[0], Integer.parseInt(address[1])));
        }
        List<Path> disks = new ArrayList<>();
        String diskList = env.getOrDefault(DISKS, "").strip();
        if (diskList.isEmpty()) {
            disks.addAll(DEFAULT_DISKS);
        } else {
            for (String d : diskList.split(",")) {
                if (!d.strip().isEmpty()) {
                    disks.add(Path.of(d.strip()));
                }
            }
        }
        String machine = env.getOrDefault(MACHINE, "").strip();
        if (machine.isEmpty()) {
            machine = hostname();
        }
        Secrets.Secret hook = Secrets.read(env, WEBHOOK);
        URI webhook = null;
        if (hook != null && !hook.value().isBlank()) {
            try {
                webhook = URI.create(hook.value().strip());
            } catch (IllegalArgumentException e) {
                throw new RefusedConfiguration(WEBHOOK + " is not a URL" + (hook.source() == null ? "" : " (" + hook.source() + ")"));
            }
            String scheme = webhook.getScheme();
            if (webhook.getHost() == null || !("http".equals(scheme) || "https".equals(scheme))) {
                throw new RefusedConfiguration(WEBHOOK + " must be an http(s) URL with a host");
            }
        }
        String repeat = env.get(REPEAT_HOURS);
        int hours = DEFAULT_REPEAT_HOURS;
        if (repeat != null) {
            if (!repeat.matches("[0-9]{1,2}") || Integer.parseInt(repeat) < 1 || Integer.parseInt(repeat) > 24) {
                throw new RefusedConfiguration(REPEAT_HOURS + " must be whole hours, 1 to 24, not '" + repeat + "'");
            }
            hours = Integer.parseInt(repeat);
        }
        return new WatchSettings(targets, peers, disks, machine, webhook, hours * 3_600_000L);
    }

    /** {@code a=b,c=d}: the pairs, each side non-empty, or the variable refused. */
    private static List<String[]> pairs(Map<String, String> env, String name, String shape) {
        List<String[]> out = new ArrayList<>();
        String value = env.getOrDefault(name, "").strip();
        if (value.isEmpty()) {
            return out;
        }
        for (String item : value.split(",")) {
            String s = item.strip();
            int eq = s.indexOf('=');
            if (eq <= 0 || eq == s.length() - 1) {
                throw new RefusedConfiguration(name + " entries are " + shape + ", not '" + s + "'");
            }
            out.add(new String[] {s.substring(0, eq).strip(), s.substring(eq + 1).strip()});
        }
        return out;
    }

    private static String[] hostPort(String name, String value) {
        int colon = value.lastIndexOf(':');
        if (colon <= 0 || colon == value.length() - 1) {
            throw new RefusedConfiguration(name + " addresses are host:port, not '" + value + "'");
        }
        String port = value.substring(colon + 1);
        if (!port.matches("[0-9]{1,5}") || Integer.parseInt(port) > 65535 || Integer.parseInt(port) == 0) {
            throw new RefusedConfiguration(name + ": '" + port + "' is not a port");
        }
        return new String[] {value.substring(0, colon), port};
    }

    private static String hostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            return "unnamed";
        }
    }
}
