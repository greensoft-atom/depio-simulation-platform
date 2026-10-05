package com.jredis.server.info;

import com.jredis.server.Version;
import com.jredis.server.command.CommandSpec;
import com.jredis.server.config.ServerConfig;
import com.jredis.server.core.Client;
import com.jredis.server.core.Engine;
import com.jredis.server.core.Stats;
import com.jredis.server.db.MemoryEstimator;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The INFO report: {@code field:value} lines grouped under {@code # Section} headers, as in Redis. */
public final class Info {

    private static final List<String> DEFAULT = Arrays.asList("server", "clients", "memory", "persistence", "stats", "replication", "cpu", "keyspace");

    private Info() {
    }

    public static String build(Engine e, List<String> requested) {
        Set<String> sections = new LinkedHashSet<>();
        if (requested.isEmpty()) {
            sections.addAll(DEFAULT);
        }
        for (String r : requested) {
            String s = r.toLowerCase(Locale.ROOT);
            if (s.equals("default")) {
                sections.addAll(DEFAULT);
            } else if (s.equals("all") || s.equals("everything")) {
                sections.addAll(DEFAULT);
                sections.add("commandstats");
            } else {
                sections.add(s);
            }
        }
        StringBuilder sb = new StringBuilder(2048);
        for (String s : sections) {
            int before = sb.length();
            switch (s) {
                case "server": server(e, sb); break;
                case "clients": clients(e, sb); break;
                case "memory": memory(e, sb); break;
                case "persistence": sb.append("# Persistence\r\n"); e.persistence().appendInfo(sb); break;
                case "replication": sb.append("# Replication\r\n"); e.replication().appendInfo(sb); break;
                case "stats": stats(e, sb); break;
                case "cpu": cpu(e, sb); break;
                case "commandstats": commandstats(e, sb); break;
                case "keyspace": keyspace(e, sb); break;
                default: continue;
            }
            if (sb.length() > before) {
                sb.append("\r\n");
            }
        }
        return sb.toString();
    }

    private static void line(StringBuilder sb, String k, Object v) {
        sb.append(k).append(':').append(v).append("\r\n");
    }

    private static void server(Engine e, StringBuilder sb) {
        ServerConfig c = e.config();
        long uptime = (System.currentTimeMillis() - e.startMillis()) / 1000;
        sb.append("# Server\r\n");
        line(sb, "jredis_version", Version.VERSION);
        line(sb, "redis_mode", "standalone");
        line(sb, "compatible_with", "redis 7.2 command semantics (RESP2)");
        line(sb, "os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        line(sb, "java_version", System.getProperty("java.version"));
        line(sb, "java_vendor", System.getProperty("java.vendor"));
        line(sb, "process_id", processId());
        line(sb, "run_id", e.runId());
        line(sb, "tcp_port", c.port());
        line(sb, "io_threads", c.ioThreads());
        line(sb, "uptime_in_seconds", uptime);
        line(sb, "uptime_in_days", uptime / 86400);
        line(sb, "config_file", c.configFile() == null ? "" : c.configFile());
    }

    private static String processId() {
        String name = ManagementFactory.getRuntimeMXBean().getName();   // pid@host on HotSpot
        int at = name.indexOf('@');
        return at > 0 ? name.substring(0, at) : name;
    }

    private static void clients(Engine e, StringBuilder sb) {
        int pubsub = 0;
        long maxOutput = 0;
        for (Client c : e.clients()) {
            if (c.inPubSubMode()) {
                pubsub++;
            }
            maxOutput = Math.max(maxOutput, c.out.pendingBytes());
        }
        sb.append("# Clients\r\n");
        line(sb, "connected_clients", e.clients().size());
        line(sb, "blocked_clients", e.blocking().blockedClients());
        line(sb, "pubsub_clients", pubsub);
        line(sb, "watching_clients", e.watches().watchingClients());
        line(sb, "maxclients", e.config().maxclients());
        line(sb, "client_recent_max_output_bytes", maxOutput);
    }

    private static void memory(Engine e, StringBuilder sb) {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long used = e.db().usedMemory();
        long gcCount = 0;
        long gcTime = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += Math.max(0, gc.getCollectionCount());
            gcTime += Math.max(0, gc.getCollectionTime());
        }
        sb.append("# Memory\r\n");
        line(sb, "used_memory", used);
        line(sb, "used_memory_human", human(used));
        line(sb, "maxmemory", e.config().maxmemory());
        line(sb, "maxmemory_human", human(e.config().maxmemory()));
        line(sb, "maxmemory_policy", e.config().maxmemoryPolicy());
        line(sb, "jvm_heap_used", heap.getUsed());
        line(sb, "jvm_heap_committed", heap.getCommitted());
        line(sb, "jvm_heap_max", heap.getMax());
        line(sb, "estimate_to_heap_ratio", heap.getUsed() == 0 ? "0" : String.format(Locale.ROOT, "%.2f", (double) used / heap.getUsed()));
        line(sb, "compressed_oops", MemoryEstimator.COMPRESSED_OOPS ? 1 : 0);
        line(sb, "gc_count", gcCount);
        line(sb, "gc_time_ms", gcTime);
    }

    private static void stats(Engine e, StringBuilder sb) {
        Stats s = e.stats();
        sb.append("# Stats\r\n");
        line(sb, "total_connections_received", s.connectionsReceived);
        line(sb, "total_commands_processed", s.commandsProcessed);
        line(sb, "instantaneous_ops_per_sec", s.instantaneousOpsPerSec());
        line(sb, "total_net_input_bytes", s.netInputBytes.get());
        line(sb, "total_net_output_bytes", s.netOutputBytes.get());
        line(sb, "rejected_connections", s.rejectedConnections.get());
        line(sb, "expired_keys", s.expiredKeys);
        line(sb, "keyspace_hits", s.keyspaceHits);
        line(sb, "keyspace_misses", s.keyspaceMisses);
        line(sb, "pubsub_channels", e.pubsub().channelCount());
        line(sb, "pubsub_patterns", e.pubsub().patternCount());
        line(sb, "client_output_limit_disconnects", s.outputLimitDisconnects);
        line(sb, "total_error_replies", s.rejectedCalls + s.failedCalls);
        line(sb, "rejected_calls", s.rejectedCalls);
        line(sb, "failed_calls", s.failedCalls);
        line(sb, "auth_failures", s.authFailures);
        line(sb, "internal_errors", s.internalErrors);
        line(sb, "aof_load_errors", s.aofLoadErrors);
    }

    private static void cpu(Engine e, StringBuilder sb) {
        sb.append("# CPU\r\n");
        line(sb, "cmd_thread_cpu_percent", String.format(Locale.ROOT, "%.1f", e.stats().cmdThreadCpuPercent));
        line(sb, "background_duty_percent", String.format(Locale.ROOT, "%.1f", e.stats().backgroundDutyPercent));
        java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean) {
            long cpuNanos = ((com.sun.management.OperatingSystemMXBean) os).getProcessCpuTime();
            line(sb, "used_cpu_process_seconds", String.format(Locale.ROOT, "%.2f", cpuNanos / 1e9));
        }
    }

    private static void commandstats(Engine e, StringBuilder sb) {
        sb.append("# Commandstats\r\n");
        for (CommandSpec spec : e.table().all()) {
            if (spec.stat.calls == 0 && spec.stat.rejected == 0) {
                continue;
            }
            long calls = spec.stat.calls;
            sb.append("cmdstat_").append(spec.name.toLowerCase(Locale.ROOT)).append(':')
                    .append("calls=").append(calls)
                    .append(",usec=").append(spec.stat.micros)
                    .append(",usec_per_call=").append(String.format(Locale.ROOT, "%.2f", calls == 0 ? 0.0 : (double) spec.stat.micros / calls))
                    .append(",rejected_calls=").append(spec.stat.rejected)
                    .append(",failed_calls=").append(spec.stat.failed)
                    .append(",p50=").append(spec.stat.percentile(50))
                    .append(",p99=").append(spec.stat.percentile(99))
                    .append(",p999=").append(spec.stat.percentile(99.9))
                    .append("\r\n");
        }
    }

    private static void keyspace(Engine e, StringBuilder sb) {
        sb.append("# Keyspace\r\n");
        int keys = e.db().size();
        if (keys > 0) {
            line(sb, "db0", "keys=" + keys + ",expires=" + e.db().expiresCount() + ",avg_ttl=0");
        }
    }

    static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        String[] units = {"K", "M", "G", "T"};
        double v = bytes;
        int u = -1;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format(Locale.ROOT, "%.2f%s", v, units[u]);
    }
}
