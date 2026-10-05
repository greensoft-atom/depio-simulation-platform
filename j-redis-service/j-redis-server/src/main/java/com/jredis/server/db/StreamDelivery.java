package com.jredis.server.db;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reading a stream's new entries, for a plain reader or a group's consumer, and the effects a
 * delivery logs (D-34): shared by the commands and by the server of blocked readers, so a read
 * that waited delivers and logs exactly as one that did not.
 */
public final class StreamDelivery {

    private StreamDelivery() {
    }

    private static byte[] w(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static final byte[] XGROUP = w("XGROUP");
    private static final byte[] SETID = w("SETID");
    private static final byte[] CREATECONSUMER = w("CREATECONSUMER");
    private static final byte[] XCLAIM = w("XCLAIM");
    private static final byte[] ZERO = w("0");
    private static final byte[] TIME = w("TIME");
    private static final byte[] RETRYCOUNT = w("RETRYCOUNT");
    private static final byte[] FORCE = w("FORCE");
    private static final byte[] JUSTID = w("JUSTID");

    public static byte[] bytes(StreamId id) {
        return w(id.toString());
    }

    /** A delivery or a claim, as its exact outcome: whose, since when, how many times. */
    public static byte[][] claimEffect(byte[] key, byte[] group, byte[] consumer, StreamId id, long time, long count) {
        return new byte[][] {XCLAIM, key, group, consumer, ZERO, bytes(id), TIME, w(Long.toString(time)),
                RETRYCOUNT, w(Long.toString(count)), FORCE, JUSTID};
    }

    public static byte[][] createConsumerEffect(byte[] key, byte[] group, byte[] consumer) {
        return new byte[][] {XGROUP, CREATECONSUMER, key, group, consumer};
    }

    public static byte[][] setIdEffect(byte[] key, byte[] group, StreamId id) {
        return new byte[][] {XGROUP, SETID, key, group, bytes(id)};
    }

    /** Entries after an ID, at most {@code count} (negative: all), each {@code {StreamId, byte[][]}}. */
    public static List<Object[]> after(StreamValue s, StreamId id, long count) {
        StreamId from = id.next();
        if (from == null) {
            return Collections.emptyList();
        }
        List<Object[]> out = new ArrayList<>();
        s.range(from, StreamId.MAX, false, count, (ms, seq, fields) -> out.add(new Object[] {new StreamId(ms, seq), fields}));
        return out;
    }

    /**
     * Delivers the entries a group has not delivered yet to one of its consumers: pending for it
     * (unless {@code noack}), and the group's last ID moved past them. The effects go to {@code effects}.
     */
    public static List<Object[]> deliverNew(StreamValue s, StreamGroup g, StreamGroup.Consumer c, byte[] key,
                                            long count, boolean noack, long now, List<byte[][]> effects) {
        List<Object[]> entries = after(s, g.lastDelivered(), count);
        for (Object[] e : entries) {
            StreamId id = (StreamId) e[0];
            if (!noack) {
                s.deliver(g, c, id, now, 1);
                effects.add(claimEffect(key, g.name(), c.name(), id, now, 1));
            }
        }
        if (!entries.isEmpty()) {
            StreamId last = (StreamId) entries.get(entries.size() - 1)[0];
            s.setGroupId(g, last.ms(), last.seq());
            effects.add(setIdEffect(key, g.name(), last));
        }
        StreamValue.touch(c, now, !entries.isEmpty());
        return entries;
    }
}
