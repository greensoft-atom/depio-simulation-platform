package com.backend.protocol;

/**
 * Little-endian byte writer with LEB128 varints, over a reusable buffer.
 *
 * One of these per room thread, reset before each client's snapshot. It never allocates in
 * steady state: the buffer grows only if a snapshot ever exceeds it, which the entity
 * budget is meant to prevent.
 */
public final class SnapshotWriter {

    private byte[] buf;
    private int pos;

    public SnapshotWriter(int initialCapacity) {
        buf = new byte[Math.max(64, initialCapacity)];
    }

    public void reset() {
        pos = 0;
    }

    public int length() {
        return pos;
    }

    public byte[] array() {
        return buf;
    }

    /** Copies the written bytes out. For tests and vectors, not the hot path. */
    public byte[] toBytes() {
        byte[] out = new byte[pos];
        System.arraycopy(buf, 0, out, 0, pos);
        return out;
    }

    private void ensure(int extra) {
        if (pos + extra > buf.length) {
            int size = buf.length;
            while (size < pos + extra) {
                size <<= 1;
            }
            byte[] bigger = new byte[size];
            System.arraycopy(buf, 0, bigger, 0, pos);
            buf = bigger;
        }
    }

    public void u8(int v) {
        ensure(1);
        buf[pos++] = (byte) v;
    }

    public void u16(int v) {
        ensure(2);
        buf[pos++] = (byte) v;
        buf[pos++] = (byte) (v >>> 8);
    }

    /** i16, written identically to u16; the reader sign-extends. */
    public void i16(int v) {
        u16(v);
    }

    /** Big-endian, matching the u32 the client sent in its Ping. */
    public void u32(long v) {
        ensure(4);
        buf[pos++] = (byte) (v >>> 24);
        buf[pos++] = (byte) (v >>> 16);
        buf[pos++] = (byte) (v >>> 8);
        buf[pos++] = (byte) v;
    }

    /** An IEEE 754 float's bits, as a big-endian u32 (MotionRule, D-62): the value itself, not a rounding. */
    public void f32(float v) {
        u32(Float.floatToRawIntBits(v));
    }

    public void varint(long v) {
        ensure(10);
        while (true) {
            int part = (int) (v & 0x7F);
            v >>>= 7;
            if (v == 0) {
                buf[pos++] = (byte) part;
                return;
            }
            buf[pos++] = (byte) (part | 0x80);
        }
    }

    public void svarint(long v) {
        varint((v << 1) ^ (v >> 63));       // zigzag
    }

    public void bytes(byte[] src, int len) {
        ensure(len);
        System.arraycopy(src, 0, buf, pos, len);
        pos += len;
    }

    /**
     * Writes a varint count into a slot reserved earlier.
     *
     * Counts are not known until a section has been built, and a section cannot be built
     * into a scratch buffer without copying. Reserving one byte works because no section
     * exceeds 127 entries — the entity budget is far below that — and
     * {@link #reserveCount()} is paired with a bounds check here.
     */
    public int reserveCount() {
        ensure(1);
        return pos++;
    }

    public void patchCount(int slot, int count) {
        if (count > 127) {
            throw new IllegalStateException("section of " + count
                    + " exceeds the single-byte reservation; entity budget should prevent this");
        }
        buf[slot] = (byte) count;
    }
}
