package com.jredis.server.repl;

import io.netty.buffer.ByteBuf;

import java.io.IOException;

/**
 * Finds the end of an image arriving in pieces (docs/16-replication.md §5.2): the bytes before the
 * 40-character mark are the image. The mark may straddle two reads, so the last 39 bytes of a read
 * are kept back until the next says whether they begin it.
 */
final class EndMark {

    /** Takes {@code length} image bytes from the buffer. */
    interface Sink {
        void write(ByteBuf from, int length) throws IOException;
    }

    private final byte[] mark;

    EndMark(byte[] mark) {
        this.mark = mark;
    }

    /**
     * Hands the image bytes {@code in} holds to {@code out}, keeping back what may begin the mark.
     *
     * @return true once the mark is consumed: what {@code in} holds then follows the image
     */
    boolean scan(ByteBuf in, Sink out) throws IOException {
        int at = indexOf(in);
        if (at < 0) {
            int n = in.readableBytes() - (mark.length - 1);
            if (n > 0) {
                out.write(in, n);
            }
            return false;
        }
        out.write(in, at - in.readerIndex());
        in.skipBytes(mark.length);
        return true;
    }

    private int indexOf(ByteBuf in) {
        int last = in.writerIndex() - mark.length;
        outer:
        for (int i = in.readerIndex(); i <= last; i++) {
            for (int j = 0; j < mark.length; j++) {
                if (in.getByte(i + j) != mark[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
