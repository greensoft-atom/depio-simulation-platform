package com.backend.handoff;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.backend.common.RefusedConfiguration;
import com.backend.common.Secrets;
import com.jredis.client.JRedisClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opens a process's connection to the store, with its password.
 *
 * Production j-redis runs with {@code requirepass} (its own install notes say so), and no
 * process sent a password: every one of them would have been refused by the store it was
 * deployed next to. The password comes from {@code BACKEND_STORE_PASSWORD_FILE} or
 * {@code BACKEND_STORE_PASSWORD}, by the same rules as the database's ({@link Secrets}).
 *
 * <h2>What is refused at start-up, and what is not</h2>
 *
 * A store that answers {@code NOAUTH} wants a password this process was not given: a
 * configuration error, refused at once ({@link RefusedConfiguration}, exit 2) rather than
 * discovered one failed request at a time. A store that cannot be reached is not refused: it
 * may simply not be up yet, and an arena is designed to run and spool results while it is
 * down. A wrong password cannot be told apart from an unreachable store from here — the
 * client logs "authentication failed" and retries slowly — so it is left to that log line.
 *
 * <h2>A store and its replica</h2>
 *
 * A store may be named by both its addresses, {@code host:port,host:port}: its primary and its
 * replica, in any order (D-34). The client then uses the primary with the highest epoch and
 * follows a promotion by itself. {@code BACKEND_STORE_ADDRESSES} names the session store so,
 * over the host and port on the command line; {@code BACKEND_EVENTS_STORE} takes a list too.
 */
public final class StoreClients {

    private static final Logger log = LoggerFactory.getLogger(StoreClients.class);

    public static JRedisClient open(String host, int port, String clientName) {
        return open(host, port, clientName, System.getenv());
    }

    static JRedisClient open(String host, int port, String clientName, Map<String, String> env) {
        String list = env.get("BACKEND_STORE_ADDRESSES");
        String[] addresses = list == null || list.isBlank()
                ? new String[] {host + ":" + port} : addresses(list, "BACKEND_STORE_ADDRESSES");
        return open(addresses, clientName, Secrets.read(env, "BACKEND_STORE_PASSWORD"), "BACKEND_STORE_PASSWORD");
    }

    /** {@code host:port}, or several separated by commas: a store's primary and its replica. */
    static String[] addresses(String value, String setting) {
        String[] parts = value.split(",", -1);
        for (int i = 0; i < parts.length; i++) {
            // Trimmed: "a:1, b:2" as people write it gave " b", a host that never resolved, and the client retried
            // quietly instead of refusing (the gateway review, 2026-10-04).
            parts[i] = parts[i].trim();
        }
        for (String a : parts) {
            int colon = a.lastIndexOf(':');
            boolean ok = colon > 0;
            if (ok) {
                try {
                    int port = Integer.parseInt(a.substring(colon + 1));
                    ok = port > 0 && port <= 65535;
                } catch (NumberFormatException e) {
                    ok = false;
                }
            }
            if (!ok) {
                throw new RefusedConfiguration(setting + " must be host:port, or host:port,host:port for a store"
                        + " and its replica, not " + value);
            }
        }
        return parts;
    }

    /**
     * The client for the {@code events} instance (D-7): the durable result queue, apart from
     * sessions, tickets and leaderboards, so a backlog of unapplied results can never crowd
     * out a login, and the two fail and restart independently.
     *
     * {@code BACKEND_EVENTS_STORE} names it as host:port. Unset, the events client is simply
     * {@code session}: one instance serves both, as in development or a small deployment.
     * Its password is {@code BACKEND_EVENTS_STORE_PASSWORD(_FILE)}, or else the session
     * store's, which is the usual arrangement.
     */
    public static JRedisClient openEvents(JRedisClient session, String clientName) {
        return openEvents(session, clientName, System.getenv());
    }

    static JRedisClient openEvents(JRedisClient session, String clientName, Map<String, String> env) {
        String addr = env.get("BACKEND_EVENTS_STORE");
        if (addr == null || addr.isBlank()) {
            return session;
        }
        String[] addresses = addresses(addr, "BACKEND_EVENTS_STORE");
        Secrets.Secret password = Secrets.read(env, "BACKEND_EVENTS_STORE_PASSWORD");
        if (password == null) {
            password = Secrets.read(env, "BACKEND_STORE_PASSWORD");
        }
        return open(addresses, clientName, password, "BACKEND_EVENTS_STORE_PASSWORD");
    }

    /** {@code setting}: the password's setting, named in what is said when it is missing. */
    private static JRedisClient open(String[] addresses, String clientName, Secrets.Secret password, String setting) {
        if (password == null) {
            log.warn("no " + setting + "_FILE or " + setting + ": connecting to the"
                    + " store without a password, which only a development store accepts");
        }
        if (addresses.length > 1) {
            // The client finds the primary by asking each address its role, and a refused AUTH
            // there reads as "no primary". Ask each plainly first.
            for (String a : addresses) {
                int colon = a.lastIndexOf(':');
                JRedisClient.Builder b = JRedisClient.builder().address(a.substring(0, colon),
                        Integer.parseInt(a.substring(colon + 1))).clientName(clientName);
                if (password != null) {
                    b.password(password.value());
                }
                JRedisClient probe = b.build().start();
                refuseIfUnauthenticated(probe, a, setting);
                probe.close();
            }
        }
        JRedisClient.Builder builder = JRedisClient.builder().addresses(addresses).clientName(clientName);
        if (password != null) {
            builder.password(password.value());
        }
        JRedisClient client = builder.build().start();
        if (addresses.length == 1) {
            refuseIfUnauthenticated(client, addresses[0], setting);
        }
        return client;
    }

    /**
     * Closes {@code client} and refuses the configuration if the store says it needs a
     * password; returns quietly on anything else, including no answer at all.
     */
    static void refuseIfUnauthenticated(JRedisClient client, String where) {
        refuseIfUnauthenticated(client, where, "BACKEND_STORE_PASSWORD");
    }

    /** As {@link #refuseIfUnauthenticated(JRedisClient, String)}, naming {@code setting}: the events store's has its own. */
    static void refuseIfUnauthenticated(JRedisClient client, String where, String setting) {
        try {
            client.ping().get(2, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            String message = String.valueOf(e.getCause().getMessage());
            if (message.startsWith("NOAUTH")) {
                client.close();
                throw new RefusedConfiguration("the store at " + where
                        + " requires a password: set " + setting + "_FILE");
            }
            // Anything else is the store not being there yet, which is survivable.
        } catch (java.util.concurrent.TimeoutException e) {
            // Not answering yet: the same.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Whether the store results go to was named with its replica: the events store's list, or,
     * with none named, the session store's, which then holds the results (Q-11).
     */
    public static boolean eventsReplicated() {
        return eventsReplicated(System.getenv());
    }

    static boolean eventsReplicated(Map<String, String> env) {
        String events = env.get("BACKEND_EVENTS_STORE");
        String named = events == null || events.isBlank() ? env.get("BACKEND_STORE_ADDRESSES") : events;
        return named != null && named.contains(",");
    }

    /** The store client's own counters: what a store outage looks like from this process. */
    public static void registerMetrics(com.backend.common.Metrics m, JRedisClient client) {
        registerMetrics(m, client, "store");
    }

    /** As above, under {@code backend_<prefix>_...}: "events_store" for the events instance. */
    public static void registerMetrics(com.backend.common.Metrics m, JRedisClient client, String prefix) {
        com.jredis.client.ClientMetrics c = client.metrics();
        String p = "backend_" + prefix + "_";
        m.counter(p + "timeouts_total", "Store commands that timed out.", c.timeouts::get);
        m.counter(p + "failed_fast_total",
                "Store commands refused at once because the store was not connected.", c.failedFast::get);
        m.counter(p + "reconnects_total", "Reconnections to the store.", c.reconnects::get);
        m.counter(p + "server_errors_total", "Error replies from the store.", c.serverErrors::get);
        m.gauge(p + "connected", "1 while connected to the store.",
                () -> client.isConnected() ? 1 : 0);
    }

    /** A store primary's replicas, as its {@code INFO replication} tells (D-58). */
    public record Replicas(int connected, long behindBytes, long ackSeconds) { }

    /**
     * Reads a primary's {@code INFO replication}: its replicas connected, the most bytes of its
     * stream one has not acknowledged, and the longest any has been silent. None connected is
     * nothing behind and no silence: the count is what says so.
     */
    static Replicas replicas(String info) {
        long offset = 0;
        int connected = 0;
        long behind = 0;
        long silent = 0;
        java.util.List<String> replicaLines = new java.util.ArrayList<>();
        for (String line : info.split("\r?\n")) {
            if (line.startsWith("repl_offset:")) {
                offset = Long.parseLong(line.substring("repl_offset:".length()).trim());
            } else if (line.startsWith("connected_replicas:")) {
                connected = Integer.parseInt(line.substring("connected_replicas:".length()).trim());
            } else if (line.matches("replica\\d+:.*")) {
                replicaLines.add(line);
            }
        }
        for (String line : replicaLines) {
            Map<String, String> f = new java.util.HashMap<>();
            for (String pair : line.substring(line.indexOf(':') + 1).trim().split(",")) {
                int eq = pair.indexOf('=');
                f.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
            behind = Math.max(behind, offset - Long.parseLong(f.get("offset")));
            silent = Math.max(silent, Long.parseLong(f.get("lag")));
        }
        return new Replicas(connected, behind, silent);
    }

    /** The store's replicas, asked of the primary this client is connected to (D-58). */
    public static Replicas replicas(JRedisClient client) {
        return replicas(client.sync().send("INFO", "replication").asString());
    }

    /** The store's replicas, under {@code backend_<prefix>_...}, read from its primary at each scrape (D-58). */
    public static void registerReplicaMetrics(com.backend.common.Metrics m, JRedisClient client, String prefix) {
        String p = "backend_" + prefix + "_";
        m.gauge(p + "replicas", "The store's replicas connected to its primary.", () -> replicas(client).connected());
        m.gauge(p + "replica_behind_bytes", "Bytes of the store's stream its slowest replica has not acknowledged.",
                () -> replicas(client).behindBytes());
        m.gauge(p + "replica_ack_seconds", "The longest any of the store's replicas has been silent.",
                () -> replicas(client).ackSeconds());
    }

    private StoreClients() {
    }
}
