package com.jredis.server.db;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.TreeMap;

/**
 * A stream's consumer group: how far it has delivered, what it delivered and has not heard back
 * about, and who reads it ([15 §2](../../../../../../../../docs/15-streams.md)). Changed only
 * through {@link StreamValue}, which keeps the memory estimate.
 */
public final class StreamGroup {

    /** An entry delivered and not yet acknowledged. */
    public static final class Pending {
        Consumer consumer;
        long deliveryTime;
        long deliveryCount;

        public Consumer consumer() {
            return consumer;
        }

        public long deliveryTime() {
            return deliveryTime;
        }

        public long deliveryCount() {
            return deliveryCount;
        }
    }

    /** A consumer: its pending entries, and when it was last seen and last given anything. */
    public static final class Consumer {
        final byte[] name;
        long seenTime;
        long activeTime = -1;
        final TreeMap<StreamId, Pending> pending = new TreeMap<>();

        Consumer(byte[] name, long seenTime) {
            this.name = name;
            this.seenTime = seenTime;
        }

        public byte[] name() {
            return name;
        }

        public long seenTime() {
            return seenTime;
        }

        /** The last delivery or claim; -1 if never. */
        public long activeTime() {
            return activeTime;
        }

        public TreeMap<StreamId, Pending> pending() {
            return pending;
        }
    }

    final byte[] name;
    long lastMs;
    long lastSeq;
    final TreeMap<StreamId, Pending> pending = new TreeMap<>();
    final LinkedHashMap<String, Consumer> consumers = new LinkedHashMap<>();

    StreamGroup(byte[] name, long lastMs, long lastSeq) {
        this.name = name;
        this.lastMs = lastMs;
        this.lastSeq = lastSeq;
    }

    static String keyOf(byte[] name) {
        return new String(name, StandardCharsets.ISO_8859_1);     // one char per byte: exact
    }

    public byte[] name() {
        return name;
    }

    public StreamId lastDelivered() {
        return new StreamId(lastMs, lastSeq);
    }

    public TreeMap<StreamId, Pending> pending() {
        return pending;
    }

    public Consumer consumer(byte[] name) {
        return consumers.get(keyOf(name));
    }

    public java.util.Collection<Consumer> consumers() {
        return consumers.values();
    }
}
