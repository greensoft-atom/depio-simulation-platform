package com.jredis.server.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * All server settings. Fields that {@code CONFIG SET} may change at runtime are volatile, because
 * the I/O threads and the persistence writer read them while the command thread may change them.
 * Everything else is fixed after start-up.
 */
public final class ServerConfig {

    public enum FsyncPolicy { EVERYSEC, NO }

    public static final long KB = 1024;
    public static final long MB = 1024 * KB;

    // ---------------------------------------------------------------- fixed after start-up
    private String configFile;
    private int port = 6379;
    private List<String> bind = Collections.singletonList("127.0.0.1");
    private int tcpKeepalive = 300;
    private int ioThreads = 2;
    private String dir = "./data";
    private boolean appendonly = true;
    private String maxmemoryPolicy = "noeviction";
    private final Set<String> disabledCommands = new LinkedHashSet<>();
    private boolean enableDebugCommand;
    private String replicaofHost;                  // null: start as a primary (docs/16-replication.md §9)
    private int replicaofPort;
    private long replBacklogSize = 64 * MB;

    // ---------------------------------------------------------------- changeable with CONFIG SET
    private volatile boolean protectedMode = true;
    private volatile String requirepass = "";
    private volatile int maxclients = 1000;
    private volatile int timeout;
    private volatile FsyncPolicy appendfsync = FsyncPolicy.EVERYSEC;
    private volatile int appendfsyncIntervalMillis = 1000;
    private volatile boolean aofLoadTruncated = true;
    private volatile int autoAofRewritePercentage = 100;
    private volatile long autoAofRewriteMinSize = 64 * MB;
    private volatile long maxmemory;
    private volatile long protoMaxBulkLen = 64 * MB;
    private volatile long clientQueryBufferLimit = 128 * MB;
    private volatile long normalOutputHardLimit;
    private volatile long normalOutputSoftLimit;
    private volatile int normalOutputSoftSeconds;
    private volatile long pubsubOutputHardLimit = 32 * MB;
    private volatile long pubsubOutputSoftLimit = 8 * MB;
    private volatile int pubsubOutputSoftSeconds = 60;
    private volatile long replicaOutputHardLimit = 256 * MB;
    private volatile long replicaOutputSoftLimit = 64 * MB;
    private volatile int replicaOutputSoftSeconds = 60;
    private volatile String primaryauth = "";
    private volatile int replTimeout = 60;
    private volatile int replPingReplicaPeriod = 10;
    private volatile int minReplicasToWrite;
    private volatile int minReplicasMaxLag = 10;
    private volatile long slowlogLogSlowerThan = 10_000;
    private volatile int slowlogMaxLen = 128;
    private volatile int backgroundSliceMicros = 250;
    private volatile int backgroundMaxDuty = 25;

    public String configFile() { return configFile; }
    public void configFile(String v) { configFile = v; }

    public int port() { return port; }
    public void port(int v) { port = v; }

    public List<String> bind() { return bind; }
    public void bind(List<String> v) { bind = Collections.unmodifiableList(new ArrayList<>(v)); }

    public int tcpKeepalive() { return tcpKeepalive; }
    public void tcpKeepalive(int v) { tcpKeepalive = v; }

    public int ioThreads() { return ioThreads; }
    public void ioThreads(int v) { ioThreads = v; }

    public String dir() { return dir; }
    public void dir(String v) { dir = v; }

    public boolean appendonly() { return appendonly; }
    public void appendonly(boolean v) { appendonly = v; }

    public String maxmemoryPolicy() { return maxmemoryPolicy; }
    public void maxmemoryPolicy(String v) { maxmemoryPolicy = v; }

    public Set<String> disabledCommands() { return disabledCommands; }

    public boolean enableDebugCommand() { return enableDebugCommand; }
    public void enableDebugCommand(boolean v) { enableDebugCommand = v; }

    public boolean protectedMode() { return protectedMode; }
    public void protectedMode(boolean v) { protectedMode = v; }

    public String requirepass() { return requirepass; }
    public void requirepass(String v) { requirepass = v; }

    public int maxclients() { return maxclients; }
    public void maxclients(int v) { maxclients = v; }

    public int timeout() { return timeout; }
    public void timeout(int v) { timeout = v; }

    public FsyncPolicy appendfsync() { return appendfsync; }
    public void appendfsync(FsyncPolicy v) { appendfsync = v; }

    public int appendfsyncIntervalMillis() { return appendfsyncIntervalMillis; }
    public void appendfsyncIntervalMillis(int v) { appendfsyncIntervalMillis = v; }

    public boolean aofLoadTruncated() { return aofLoadTruncated; }
    public void aofLoadTruncated(boolean v) { aofLoadTruncated = v; }

    public int autoAofRewritePercentage() { return autoAofRewritePercentage; }
    public void autoAofRewritePercentage(int v) { autoAofRewritePercentage = v; }

    public long autoAofRewriteMinSize() { return autoAofRewriteMinSize; }
    public void autoAofRewriteMinSize(long v) { autoAofRewriteMinSize = v; }

    public long maxmemory() { return maxmemory; }
    public void maxmemory(long v) { maxmemory = v; }

    public long protoMaxBulkLen() { return protoMaxBulkLen; }
    public void protoMaxBulkLen(long v) { protoMaxBulkLen = v; }

    public long clientQueryBufferLimit() { return clientQueryBufferLimit; }
    public void clientQueryBufferLimit(long v) { clientQueryBufferLimit = v; }

    public long normalOutputHardLimit() { return normalOutputHardLimit; }
    public long normalOutputSoftLimit() { return normalOutputSoftLimit; }
    public int normalOutputSoftSeconds() { return normalOutputSoftSeconds; }

    public void normalOutputLimits(long hard, long soft, int seconds) {
        normalOutputHardLimit = hard;
        normalOutputSoftLimit = soft;
        normalOutputSoftSeconds = seconds;
    }

    public long pubsubOutputHardLimit() { return pubsubOutputHardLimit; }
    public long pubsubOutputSoftLimit() { return pubsubOutputSoftLimit; }
    public int pubsubOutputSoftSeconds() { return pubsubOutputSoftSeconds; }

    public void pubsubOutputLimits(long hard, long soft, int seconds) {
        pubsubOutputHardLimit = hard;
        pubsubOutputSoftLimit = soft;
        pubsubOutputSoftSeconds = seconds;
    }

    public long replicaOutputHardLimit() { return replicaOutputHardLimit; }
    public long replicaOutputSoftLimit() { return replicaOutputSoftLimit; }
    public int replicaOutputSoftSeconds() { return replicaOutputSoftSeconds; }

    public void replicaOutputLimits(long hard, long soft, int seconds) {
        replicaOutputHardLimit = hard;
        replicaOutputSoftLimit = soft;
        replicaOutputSoftSeconds = seconds;
    }

    /** The primary this server starts as a replica of, or null. */
    public String replicaofHost() { return replicaofHost; }
    public int replicaofPort() { return replicaofPort; }

    public void replicaof(String host, int port) {
        replicaofHost = host;
        replicaofPort = port;
    }

    public long replBacklogSize() { return replBacklogSize; }
    public void replBacklogSize(long v) { replBacklogSize = v; }

    public String primaryauth() { return primaryauth; }
    public void primaryauth(String v) { primaryauth = v; }

    /** Seconds without a byte from the other side before a link is dropped (docs/16-replication.md §4). */
    public int replTimeout() { return replTimeout; }
    public void replTimeout(int v) { replTimeout = v; }

    public int replPingReplicaPeriod() { return replPingReplicaPeriod; }
    public void replPingReplicaPeriod(int v) { replPingReplicaPeriod = v; }

    /** A primary refuses writes while fewer replicas than this acknowledged within the lag (docs/16-replication.md §8). */
    public int minReplicasToWrite() { return minReplicasToWrite; }
    public void minReplicasToWrite(int v) { minReplicasToWrite = v; }

    public int minReplicasMaxLag() { return minReplicasMaxLag; }
    public void minReplicasMaxLag(int v) { minReplicasMaxLag = v; }

    public long slowlogLogSlowerThan() { return slowlogLogSlowerThan; }
    public void slowlogLogSlowerThan(long v) { slowlogLogSlowerThan = v; }

    public int slowlogMaxLen() { return slowlogMaxLen; }
    public void slowlogMaxLen(int v) { slowlogMaxLen = v; }

    public int backgroundSliceMicros() { return backgroundSliceMicros; }
    public void backgroundSliceMicros(int v) { backgroundSliceMicros = v; }

    public int backgroundMaxDuty() { return backgroundMaxDuty; }
    public void backgroundMaxDuty(int v) { backgroundMaxDuty = v; }
}
