package com.jredis.server.db;

import java.util.ArrayList;

/**
 * A stream: its entries in chunks of up to {@link #CHUNK}, and the last ID it ever gave
 * ([15 §4](../../../../../../../../docs/15-streams.md), D-33).
 *
 * <h2>A chunk never changes once another holder can see it</h2>
 *
 * A chunk is a view, {@code [from, to)}, over three parallel arrays: the IDs' milliseconds and
 * sequences, and each entry's fields. Appends write only past the end of the last chunk; a
 * delete replaces its chunk with a copy of exactly the entries left; a trim moves the first
 * chunk's start forward. So only the last chunk ever has room past its end, and only one stream
 * holds it: a {@link #copy()} gets its last chunk in arrays of its own. A list of chunk
 * references therefore keeps meaning the same entries however the stream changes afterwards: a
 * copy shares its source's other chunks, and a rewrite can take a stream without copying it.
 *
 * <p>A trimmed first chunk still references the entries before its start until the whole chunk
 * goes, so up to one chunk's worth of trimmed entries outlives the trim. The estimate counts them
 * gone.
 */
public final class StreamValue extends TrackedValue {

    public static final int CHUNK = 256;
    private static final int FIRST_CAPACITY = 8;

    /** Receives entries in order; false stops the walk. */
    public interface EntryVisitor {
        boolean visit(long ms, long seq, byte[][] fields);
    }

    static final class Chunk {
        final long[] ms;
        final long[] seq;
        final byte[][][] fields;
        final int from;
        final int to;

        Chunk(long[] ms, long[] seq, byte[][][] fields, int from, int to) {
            this.ms = ms;
            this.seq = seq;
            this.fields = fields;
            this.from = from;
            this.to = to;
        }

        int size() {
            return to - from;
        }

        int capacity() {
            return ms.length;
        }

        static Chunk allocate(int capacity) {
            return new Chunk(new long[capacity], new long[capacity], new byte[capacity][][], 0, 0);
        }

        /** The live entries in fresh arrays of the given capacity, from 0. */
        Chunk copy(int capacity) {
            Chunk c = allocate(capacity);
            System.arraycopy(ms, from, c.ms, 0, size());
            System.arraycopy(seq, from, c.seq, 0, size());
            System.arraycopy(fields, from, c.fields, 0, size());
            return new Chunk(c.ms, c.seq, c.fields, 0, size());
        }
    }

    private static final int BASE = MemoryEstimator.align(MemoryEstimator.HEADER + 3 * MemoryEstimator.REF + 3 * 8 + 4)
            + MemoryEstimator.align(MemoryEstimator.HEADER + MemoryEstimator.REF + 2 * 4) + MemoryEstimator.refArray(10);
    private static final int CHUNK_OBJECT = MemoryEstimator.align(MemoryEstimator.HEADER + 3 * MemoryEstimator.REF + 2 * 4);
    private static final int TREE_NODE = MemoryEstimator.align(MemoryEstimator.HEADER + 5 * MemoryEstimator.REF + 1);
    private static final int MAP_NODE = MemoryEstimator.align(MemoryEstimator.HEADER + 5 * MemoryEstimator.REF + 4);
    private static final int TREE_MAP = MemoryEstimator.align(MemoryEstimator.HEADER + 3 * MemoryEstimator.REF + 2 * 4);
    /** A pending entry: its record, its ID, and a node in the group's list and in its consumer's. */
    private static final long PENDING_BYTES = MemoryEstimator.align(MemoryEstimator.HEADER + MemoryEstimator.REF + 2 * 8)
            + MemoryEstimator.align(MemoryEstimator.HEADER + 2 * 8) + 2L * TREE_NODE;

    /** A group or consumer's own objects, its name kept twice (the bytes and the map's key), and its map node. */
    private static long namedBytes(byte[] name, int objectRefs, int objectLongs) {
        return MemoryEstimator.align(MemoryEstimator.HEADER + (long) objectRefs * MemoryEstimator.REF + objectLongs * 8L)
                + 2L * MemoryEstimator.byteArray(name.length) + MemoryEstimator.align(MemoryEstimator.HEADER + MemoryEstimator.REF + 8)
                + MAP_NODE + TREE_MAP;
    }

    private static long groupBytes(byte[] name) {
        return namedBytes(name, 3, 2) + MemoryEstimator.align(MemoryEstimator.HEADER + 5 * MemoryEstimator.REF + 4 * 4) + MemoryEstimator.refArray(16);
    }

    private static long consumerBytes(byte[] name) {
        return namedBytes(name, 2, 2);
    }

    private final ArrayList<Chunk> chunks = new ArrayList<>();
    private final java.util.LinkedHashMap<String, StreamGroup> groups = new java.util.LinkedHashMap<>();
    private long lastMs;
    private long lastSeq;
    private int length;

    public StreamValue() {
        super(BASE);
    }

    @Override
    public int size() {
        return length;
    }

    public long lastMs() {
        return lastMs;
    }

    public long lastSeq() {
        return lastSeq;
    }

    public StreamId lastId() {
        return new StreamId(lastMs, lastSeq);
    }

    private static long chunkBytes(Chunk c) {
        int n = c.capacity();
        return CHUNK_OBJECT + MemoryEstimator.REF + 2L * MemoryEstimator.longArray(n) + MemoryEstimator.refArray(n);
    }

    private static long entryBytes(byte[][] fields) {
        long b = MemoryEstimator.refArray(fields.length);
        for (byte[] f : fields) {
            b += MemoryEstimator.byteArray(f.length);
        }
        return b;
    }

    /** Appends an entry; its ID must be greater than {@link #lastId()}, which the caller checks. */
    public void append(long ms, long seq, byte[][] fields) {
        Chunk tail = chunks.isEmpty() ? null : chunks.get(chunks.size() - 1);
        if (tail != null && tail.to < tail.capacity()) {
            write(tail, tail.to, ms, seq, fields);
            chunks.set(chunks.size() - 1, new Chunk(tail.ms, tail.seq, tail.fields, tail.from, tail.to + 1));
        } else if (tail != null && tail.size() < CHUNK) {
            Chunk grown = tail.copy(Math.min(CHUNK, Math.max(FIRST_CAPACITY, tail.size() * 2)));
            write(grown, grown.to, ms, seq, fields);
            chunks.set(chunks.size() - 1, new Chunk(grown.ms, grown.seq, grown.fields, 0, grown.to + 1));
            adjust(chunkBytes(grown) - chunkBytes(tail));
        } else {
            Chunk fresh = Chunk.allocate(FIRST_CAPACITY);
            write(fresh, 0, ms, seq, fields);
            chunks.add(new Chunk(fresh.ms, fresh.seq, fresh.fields, 0, 1));
            adjust(chunkBytes(fresh));
        }
        lastMs = ms;
        lastSeq = seq;
        length++;
        adjust(entryBytes(fields));
    }

    private static void write(Chunk c, int i, long ms, long seq, byte[][] fields) {
        c.ms[i] = ms;
        c.seq[i] = seq;
        c.fields[i] = fields;
    }

    /** Index of the first chunk whose last entry is at or after the ID; {@code chunks.size()} if none. */
    private int chunkCeiling(long ms, long seq) {
        int lo = 0;
        int hi = chunks.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            Chunk c = chunks.get(mid);
            if (StreamId.compare(c.ms[c.to - 1], c.seq[c.to - 1], ms, seq) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** Index of the last chunk whose first entry is at or before the ID; -1 if none. */
    private int chunkFloor(long ms, long seq) {
        int lo = 0;
        int hi = chunks.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            Chunk c = chunks.get(mid);
            if (StreamId.compare(c.ms[c.from], c.seq[c.from], ms, seq) <= 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo - 1;
    }

    /** The first index in the chunk at or after the ID; {@code c.to} if none. */
    private static int offsetCeiling(Chunk c, long ms, long seq) {
        int lo = c.from;
        int hi = c.to;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (StreamId.compare(c.ms[mid], c.seq[mid], ms, seq) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** The last index in the chunk at or before the ID; {@code c.from - 1} if none. */
    private static int offsetFloor(Chunk c, long ms, long seq) {
        int lo = c.from;
        int hi = c.to;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (StreamId.compare(c.ms[mid], c.seq[mid], ms, seq) <= 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo - 1;
    }

    /**
     * Visits the entries from {@code start} to {@code end}, both included, in order or reversed, at
     * most {@code count} of them (negative: all).
     */
    public void range(StreamId start, StreamId end, boolean rev, long count, EntryVisitor visitor) {
        if (count == 0 || start.compareTo(end) > 0) {
            return;
        }
        long left = count;
        if (!rev) {
            for (int ci = chunkCeiling(start.ms(), start.seq()); ci < chunks.size(); ci++) {
                Chunk c = chunks.get(ci);
                for (int i = offsetCeiling(c, start.ms(), start.seq()); i < c.to; i++) {
                    if (StreamId.compare(c.ms[i], c.seq[i], end.ms(), end.seq()) > 0
                            || !visitor.visit(c.ms[i], c.seq[i], c.fields[i]) || --left == 0) {
                        return;
                    }
                }
            }
        } else {
            for (int ci = chunkFloor(end.ms(), end.seq()); ci >= 0; ci--) {
                Chunk c = chunks.get(ci);
                for (int i = offsetFloor(c, end.ms(), end.seq()); i >= c.from; i--) {
                    if (StreamId.compare(c.ms[i], c.seq[i], start.ms(), start.seq()) < 0
                            || !visitor.visit(c.ms[i], c.seq[i], c.fields[i]) || --left == 0) {
                        return;
                    }
                }
            }
        }
    }

    /** Deletes one entry. @return false if there is none with that ID */
    public boolean delete(long ms, long seq) {
        int ci = chunkCeiling(ms, seq);
        if (ci == chunks.size()) {
            return false;
        }
        Chunk c = chunks.get(ci);
        int at = offsetCeiling(c, ms, seq);
        if (c.ms[at] != ms || c.seq[at] != seq) {
            return false;
        }
        byte[][] removed = c.fields[at];
        int n = c.size() - 1;
        if (n == 0) {
            chunks.remove(ci);
            adjust(-chunkBytes(c));
        } else {
            // A copy, never a shorter view: the slot would be written again by the next append,
            // under a holder still reading the old entry there.
            Chunk d = Chunk.allocate(n);
            int k = at - c.from;
            System.arraycopy(c.ms, c.from, d.ms, 0, k);
            System.arraycopy(c.seq, c.from, d.seq, 0, k);
            System.arraycopy(c.fields, c.from, d.fields, 0, k);
            System.arraycopy(c.ms, at + 1, d.ms, k, n - k);
            System.arraycopy(c.seq, at + 1, d.seq, k, n - k);
            System.arraycopy(c.fields, at + 1, d.fields, k, n - k);
            Chunk replaced = new Chunk(d.ms, d.seq, d.fields, 0, n);
            chunks.set(ci, replaced);
            adjust(chunkBytes(replaced) - chunkBytes(c));
        }
        length--;
        adjust(-entryBytes(removed));
        return true;
    }

    /** Removes the oldest entries down to {@code maxLen}, at most {@code limit} (negative: no limit). */
    public long trimMaxLen(long maxLen, long limit) {
        long n = length - maxLen;
        return removeHead(limit < 0 ? n : Math.min(n, limit));
    }

    /** Removes the entries before {@code minId}, at most {@code limit} (negative: no limit). */
    public long trimMinId(StreamId minId, long limit) {
        long before = 0;
        int ci = chunkCeiling(minId.ms(), minId.seq());
        for (int i = 0; i < ci; i++) {
            before += chunks.get(i).size();
        }
        if (ci < chunks.size()) {
            Chunk c = chunks.get(ci);
            before += offsetCeiling(c, minId.ms(), minId.seq()) - c.from;
        }
        return removeHead(limit < 0 ? before : Math.min(before, limit));
    }

    private long removeHead(long n) {
        if (n <= 0) {
            return 0;
        }
        long left = n;
        int whole = 0;
        while (left > 0 && left >= chunks.get(whole).size()) {
            Chunk c = chunks.get(whole);
            for (int i = c.from; i < c.to; i++) {
                adjust(-entryBytes(c.fields[i]));
            }
            adjust(-chunkBytes(c));
            left -= c.size();
            whole++;
            if (whole == chunks.size()) {
                break;
            }
        }
        chunks.subList(0, whole).clear();
        if (left > 0) {
            Chunk c = chunks.get(0);
            int cut = (int) left;
            for (int i = c.from; i < c.from + cut; i++) {
                adjust(-entryBytes(c.fields[i]));
            }
            chunks.set(0, new Chunk(c.ms, c.seq, c.fields, c.from + cut, c.to));
        }
        length -= (int) n;
        return n;
    }

    // ------------------------------------------------------------------ entries by ID

    /** The fields of the entry with this ID, or null if the stream does not hold it. */
    public byte[][] fields(long ms, long seq) {
        int ci = chunkCeiling(ms, seq);
        if (ci == chunks.size()) {
            return null;
        }
        Chunk c = chunks.get(ci);
        int at = offsetCeiling(c, ms, seq);
        return c.ms[at] == ms && c.seq[at] == seq ? c.fields[at] : null;
    }

    /** How many entries come after an ID: a group's lag. */
    public long countAfter(long ms, long seq) {
        int ci = chunkFloor(ms, seq);
        if (ci < 0) {
            return length;
        }
        long upTo = 0;
        for (int i = 0; i < ci; i++) {
            upTo += chunks.get(i).size();
        }
        Chunk c = chunks.get(ci);
        upTo += offsetFloor(c, ms, seq) - c.from + 1;
        return length - upTo;
    }

    public int chunkCount() {
        return chunks.size();
    }

    // ------------------------------------------------------------------ consumer groups

    public java.util.Collection<StreamGroup> groups() {
        return groups.values();
    }

    public StreamGroup group(byte[] name) {
        return groups.get(StreamGroup.keyOf(name));
    }

    /** @return the new group, or null if one has that name */
    public StreamGroup createGroup(byte[] name, long ms, long seq) {
        String k = StreamGroup.keyOf(name);
        if (groups.containsKey(k)) {
            return null;
        }
        StreamGroup g = new StreamGroup(name, ms, seq);
        groups.put(k, g);
        adjust(groupBytes(name));
        return g;
    }

    public boolean destroyGroup(byte[] name) {
        StreamGroup g = groups.remove(StreamGroup.keyOf(name));
        if (g == null) {
            return false;
        }
        adjust(-groupBytes(g.name) - g.pending.size() * PENDING_BYTES);
        for (StreamGroup.Consumer c : g.consumers.values()) {
            adjust(-consumerBytes(c.name));
        }
        return true;
    }

    public void setGroupId(StreamGroup g, long ms, long seq) {
        g.lastMs = ms;
        g.lastSeq = seq;
    }

    /** The consumer, created and seen now if absent; {@code created[0]} says whether it was. */
    public StreamGroup.Consumer consumer(StreamGroup g, byte[] name, long now, boolean[] created) {
        String k = StreamGroup.keyOf(name);
        StreamGroup.Consumer c = g.consumers.get(k);
        if (c == null) {
            c = new StreamGroup.Consumer(name, now);
            g.consumers.put(k, c);
            adjust(consumerBytes(name));
            created[0] = true;
        }
        return c;
    }

    /** A consumer seen now, and given something now if {@code active}. */
    public static void touch(StreamGroup.Consumer c, long now, boolean active) {
        c.seenTime = now;
        if (active) {
            c.activeTime = now;
        }
    }

    /** Deletes a consumer and its pending entries. @return how many were pending, or -1 if none had the name */
    public long deleteConsumer(StreamGroup g, byte[] name) {
        StreamGroup.Consumer c = g.consumers.remove(StreamGroup.keyOf(name));
        if (c == null) {
            return -1;
        }
        long n = c.pending.size();
        for (StreamId id : c.pending.keySet()) {
            g.pending.remove(id);
        }
        adjust(-consumerBytes(c.name) - n * PENDING_BYTES);
        return n;
    }

    /** An entry pending for a consumer, from now: created, or moved from another. */
    public void deliver(StreamGroup g, StreamGroup.Consumer c, StreamId id, long time, long count) {
        StreamGroup.Pending p = g.pending.get(id);
        if (p == null) {
            p = new StreamGroup.Pending();
            g.pending.put(id, p);
            adjust(PENDING_BYTES);
        } else if (p.consumer != c) {
            p.consumer.pending.remove(id);
        }
        p.consumer = c;
        c.pending.put(id, p);
        p.deliveryTime = time;
        p.deliveryCount = count;
    }

    /** No longer pending. @return false if it was not */
    public boolean ack(StreamGroup g, StreamId id) {
        StreamGroup.Pending p = g.pending.remove(id);
        if (p == null) {
            return false;
        }
        p.consumer.pending.remove(id);
        adjust(-PENDING_BYTES);
        return true;
    }

    /** A group whose every record is its own, consumers and pending entries included. */
    private static StreamGroup copyOf(StreamGroup g) {
        StreamGroup n = new StreamGroup(g.name, g.lastMs, g.lastSeq);
        java.util.IdentityHashMap<StreamGroup.Consumer, StreamGroup.Consumer> same = new java.util.IdentityHashMap<>();
        for (StreamGroup.Consumer c : g.consumers.values()) {
            StreamGroup.Consumer d = new StreamGroup.Consumer(c.name, c.seenTime);
            d.activeTime = c.activeTime;
            n.consumers.put(StreamGroup.keyOf(c.name), d);
            same.put(c, d);
        }
        for (java.util.Map.Entry<StreamId, StreamGroup.Pending> e : g.pending.entrySet()) {
            StreamGroup.Pending p = e.getValue();
            StreamGroup.Pending q = new StreamGroup.Pending();
            q.consumer = same.get(p.consumer);
            q.deliveryTime = p.deliveryTime;
            q.deliveryCount = p.deliveryCount;
            n.pending.put(e.getKey(), q);
            q.consumer.pending.put(e.getKey(), q);
        }
        return n;
    }

    /** The stream at a moment, whatever it does afterwards: what a rewrite encodes (D-33). */
    public static final class Frozen {
        private final java.util.List<Chunk> chunks;
        private final long lastMs;
        private final long lastSeq;
        private final int length;
        private final java.util.List<StreamGroup> groups;

        Frozen(java.util.List<Chunk> chunks, long lastMs, long lastSeq, int length, java.util.List<StreamGroup> groups) {
            this.chunks = chunks;
            this.lastMs = lastMs;
            this.lastSeq = lastSeq;
            this.length = length;
            this.groups = groups;
        }

        /** The groups as they were: copies, O(pending entries). */
        public java.util.List<StreamGroup> groups() {
            return groups;
        }

        public int size() {
            return length;
        }

        public long lastMs() {
            return lastMs;
        }

        public long lastSeq() {
            return lastSeq;
        }

        public void forEach(EntryVisitor v) {
            for (Chunk c : chunks) {
                for (int i = c.from; i < c.to; i++) {
                    if (!v.visit(c.ms[i], c.seq[i], c.fields[i])) {
                        return;
                    }
                }
            }
        }
    }

    /**
     * O(chunks) for the entries: the chunk list is copied, the chunks are shared, and none of them
     * will change. The groups are copied whole, O(pending entries), which a healthy group keeps few.
     */
    public Frozen freeze() {
        java.util.List<StreamGroup> gs = new ArrayList<>(groups.size());
        for (StreamGroup g : groups.values()) {
            gs.add(copyOf(g));
        }
        return new Frozen(new ArrayList<>(chunks), lastMs, lastSeq, length, gs);
    }

    /** A loaded group's consumer, with the times it had. */
    public StreamGroup.Consumer restoreConsumer(StreamGroup g, byte[] name, long seen, long active) {
        StreamGroup.Consumer c = consumer(g, name, seen, new boolean[1]);
        c.seenTime = seen;
        c.activeTime = active;
        return c;
    }

    /** Sets the last ID a loaded stream had given, which may be past its last entry. */
    public void restoreLastId(long ms, long seq) {
        lastMs = ms;
        lastSeq = seq;
    }

    /**
     * A copy sharing this stream's chunks but its last, which it gets in arrays of its own: the
     * last chunk is the one that grows in place, and two streams must never write the same slot.
     */
    public StreamValue copy() {
        StreamValue d = new StreamValue();
        d.chunks.addAll(chunks);
        if (!chunks.isEmpty()) {
            Chunk tail = chunks.get(chunks.size() - 1);
            d.chunks.set(d.chunks.size() - 1, tail.copy(tail.capacity()));
        }
        d.lastMs = lastMs;
        d.lastSeq = lastSeq;
        d.length = length;
        for (StreamGroup g : groups.values()) {
            d.groups.put(StreamGroup.keyOf(g.name), copyOf(g));
        }
        d.adjust(bytes() - d.bytes());
        return d;
    }
}
