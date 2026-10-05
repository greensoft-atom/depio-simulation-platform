package com.jredis.server.repl;

/**
 * The replication stream's last bytes, and its offset (docs/16-replication.md §4, §7). A ring:
 * the byte at stream offset {@code o} lives at {@code o % size}, so appending past the size
 * overwrites the oldest. Command thread only.
 */
public final class Backlog {

    private final byte[] ring;
    private long offset;

    public Backlog(int size) {
        this.ring = new byte[size];
    }

    /** Bytes in the stream so far: the offset of the next byte. */
    public long offset() {
        return offset;
    }

    /** The oldest offset still held. */
    public long firstOffset() {
        return Math.max(0, offset - ring.length);
    }

    public void append(byte[] chunk) {
        append(chunk, 0, chunk.length);
    }

    public void append(byte[] b, int off, int len) {
        long at = offset;
        int from = off;
        int left = len;
        while (left > 0) {                              // past the size, later bytes overwrite earlier ones
            int pos = (int) (at % ring.length);
            int n = Math.min(left, ring.length - pos);
            System.arraycopy(b, from, ring, pos, n);
            at += n;
            from += n;
            left -= n;
        }
        offset += len;
    }

    /** The bytes from offset {@code from} to the end, or null if they are not all held. */
    public byte[] from(long from) {
        if (from < firstOffset() || from > offset) {
            return null;
        }
        byte[] out = new byte[(int) (offset - from)];
        int done = 0;
        while (done < out.length) {
            int pos = (int) ((from + done) % ring.length);
            int n = Math.min(out.length - done, ring.length - pos);
            System.arraycopy(ring, pos, out, done, n);
            done += n;
        }
        return out;
    }
}
