package com.jredis.server.db;

/** Receives each key's value as of the snapshot instant (see persistence). */
public interface SnapshotSink {
    void write(KeyEntry entry);
}
