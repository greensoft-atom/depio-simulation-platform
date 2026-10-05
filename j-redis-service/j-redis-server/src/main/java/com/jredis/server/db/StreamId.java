package com.jredis.server.db;

/**
 * A stream entry's ID: milliseconds and a sequence, two unsigned 64-bit numbers, ordered by the
 * first and then the second ([15 §2](../../../../../../../../docs/15-streams.md)).
 */
public record StreamId(long ms, long seq) implements Comparable<StreamId> {

    public static final StreamId MIN = new StreamId(0, 0);
    public static final StreamId MAX = new StreamId(-1L, -1L);

    public static int compare(long ams, long aseq, long bms, long bseq) {
        int c = Long.compareUnsigned(ams, bms);
        return c != 0 ? c : Long.compareUnsigned(aseq, bseq);
    }

    @Override
    public int compareTo(StreamId o) {
        return compare(ms, seq, o.ms, o.seq);
    }

    /** The next ID, or null past the largest. */
    public StreamId next() {
        if (seq != -1L) {
            return new StreamId(ms, seq + 1);
        }
        return ms == -1L ? null : new StreamId(ms + 1, 0);
    }

    /** The previous ID, or null before the smallest. */
    public StreamId previous() {
        if (seq != 0) {
            return new StreamId(ms, seq - 1);
        }
        return ms == 0 ? null : new StreamId(ms - 1, -1L);
    }

    @Override
    public String toString() {
        return Long.toUnsignedString(ms) + "-" + Long.toUnsignedString(seq);
    }
}
