package com.jredis.server.persist;

import java.util.Arrays;

/** A growable byte array for encoding base-file records (on the command thread, or a snapshot writer's). */
public final class ByteSink {

    private byte[] buf = new byte[64 * 1024];
    private int size;

    public int size() {
        return size;
    }

    private void ensure(int extra) {
        if (size + extra > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, size + extra));
        }
    }

    void writeByte(int b) {
        ensure(1);
        buf[size++] = (byte) b;
    }

    void writeShort(int v) {
        ensure(2);
        buf[size++] = (byte) (v >>> 8);
        buf[size++] = (byte) v;
    }

    void writeLong(long v) {
        ensure(8);
        for (int shift = 56; shift >= 0; shift -= 8) {
            buf[size++] = (byte) (v >>> shift);
        }
    }

    void writeVarint(long v) {
        ensure(10);
        while ((v & ~0x7FL) != 0) {
            buf[size++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        buf[size++] = (byte) v;
    }

    void writeBytes(byte[] b) {
        ensure(b.length);
        System.arraycopy(b, 0, buf, size, b.length);
        size += b.length;
    }

    void writeLenBytes(byte[] b) {
        writeVarint(b.length);
        writeBytes(b);
    }

    /** Returns the accumulated bytes and resets; shrinks back if a huge record made it grow. */
    public byte[] take() {
        byte[] out = Arrays.copyOf(buf, size);
        size = 0;
        if (buf.length > 4 * 1024 * 1024) {
            buf = new byte[64 * 1024];
        }
        return out;
    }

    void reset() {
        size = 0;
    }
}
