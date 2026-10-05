package com.backend.protocol;

import java.nio.charset.StandardCharsets;

/**
 * Reader mirroring {@link SnapshotWriter}. Used by {@link SnapshotReader} and by tests that
 * decode what the server produced; the real client's decoder is the C# one in client/Core (Snapshot.cs,
 * WireReader.cs), held to the same golden vectors.
 */
public final class WireReader {

    private byte[] a;
    private int pos;
    private int limit;

    public WireReader() {
    }

    public WireReader(byte[] array) {
        reset(array, 0, array.length);
    }

    public void reset(byte[] array, int offset, int length) {
        this.a = array;
        this.pos = offset;
        this.limit = offset + length;
    }

    public int position() {
        return pos;
    }

    public boolean done() {
        return pos >= limit;
    }

    public int remaining() {
        return limit - pos;
    }

    public int u8() {
        require(1);
        return a[pos++] & 0xFF;
    }

    public int u16() {
        require(2);
        return (a[pos++] & 0xFF) | ((a[pos++] & 0xFF) << 8);
    }

    public int i16() {
        return (short) u16();
    }

    /** Big-endian, as {@link SnapshotWriter#u32} writes it: the bits of an f32 (MotionRule). */
    public int u32() {
        require(4);
        return ((a[pos++] & 0xFF) << 24) | ((a[pos++] & 0xFF) << 16) | ((a[pos++] & 0xFF) << 8) | (a[pos++] & 0xFF);
    }

    public long varint() {
        long v = 0;
        int shift = 0;
        while (true) {
            int c = u8();
            v |= (long) (c & 0x7F) << shift;
            if ((c & 0x80) == 0) {
                return v;
            }
            shift += 7;
            if (shift > 63) {
                throw new IllegalStateException("varint too long");
            }
        }
    }

    public long svarint() {
        long v = varint();
        return (v >>> 1) ^ -(v & 1);
    }

    /**
     * A length or a count off the wire, refused if no frame this size could hold it: one read as it came was
     * allocated as an array, or cast to a negative int and overran an index (P-51; the C# reader's LengthOf).
     */
    public int lengthOf(long v) {
        if (v < 0 || v > limit - pos) {
            throw new IllegalStateException("length " + v + " with " + (limit - pos) + " bytes left in the frame");
        }
        return (int) v;
    }

    public String str() {
        int n = lengthOf(varint());
        require(n);
        String s = new String(a, pos, n, StandardCharsets.UTF_8);
        pos += n;
        return s;
    }

    public void skip(int n) {
        require(n);
        pos += n;
    }

    private void require(int n) {
        if (pos + n > limit) {
            throw new IllegalStateException("truncated frame: need " + n
                    + " at " + pos + ", limit " + limit);
        }
    }
}
