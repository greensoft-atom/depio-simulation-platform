package com.jredis.server.persist;

/** {@code appendonly no}: a pure in-memory server. */
public final class NoPersistence implements Persistence {

    public static final NoPersistence INSTANCE = new NoPersistence();

    private NoPersistence() {
    }

    @Override public boolean enabled() { return false; }
    @Override public void append(byte[] chunk) { }
    @Override public void endOfBatch() { }
    @Override public boolean writable() { return true; }
    @Override public void background(long deadlineNanos) { }
    @Override public boolean busy() { return false; }
    @Override public boolean rewriteInProgress() { return false; }
    @Override public String startRewrite() { return "ERR persistence is disabled (appendonly no)"; }
    @Override public void abortRewrite(String reason) { }

    @Override
    public void saveBlocking() {
        throw new IllegalStateException("ERR persistence is disabled (appendonly no)");
    }

    @Override public long lastSaveSeconds() { return 0; }
    @Override public void shutdown() { }
    @Override public void emergencyFlush() { }

    @Override
    public void appendInfo(StringBuilder sb) {
        sb.append("aof_enabled:0\r\n");
    }

    @Override
    public void debugReload() {
        throw new IllegalStateException("ERR persistence is disabled (appendonly no)");
    }
}
