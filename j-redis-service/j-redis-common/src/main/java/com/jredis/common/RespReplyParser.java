package com.jredis.common;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Resumable RESP2 reply parser producing {@link Reply} trees. Nested arrays are assembled with an
 * explicit stack, so parsing can stop and resume at any byte. Not thread-safe.
 */
public final class RespReplyParser {

    private static final int MAX_LINE = 64 * 1024;

    private final long maxBulkLen;
    private final ArrayDeque<Frame> stack = new ArrayDeque<>();
    private int pendingBulk = -1;           // >= 0: header consumed, waiting for the data

    public RespReplyParser() {
        this(512L * 1024 * 1024);
    }

    public RespReplyParser(long maxBulkLen) {
        this.maxBulkLen = maxBulkLen;
    }

    private static final class Frame {
        final Reply[] elements;
        int filled;

        Frame(int n) {
            elements = new Reply[n];
        }
    }

    /**
     * @return the next complete reply, or {@code null} if more bytes are needed
     * @throws RespProtocolException on malformed input
     */
    public Reply parse(ByteBuf in) {
        while (true) {
            Reply value = parseOne(in);
            if (value == null) {
                return null;
            }
            // parseOne returns an ArrayStart marker for non-empty arrays
            if (value instanceof ArrayStart) {
                stack.push(new Frame(((ArrayStart) value).size));
                continue;
            }
            while (true) {
                if (stack.isEmpty()) {
                    return value;
                }
                Frame f = stack.peek();
                f.elements[f.filled++] = value;
                if (f.filled < f.elements.length) {
                    break;
                }
                stack.pop();
                value = new Reply.ArrayReply(Arrays.asList(f.elements));
            }
        }
    }

    private Reply parseOne(ByteBuf in) {
        if (pendingBulk >= 0) {
            return readBulkData(in);
        }
        if (!in.isReadable()) {
            return null;
        }
        int ri = in.readerIndex();
        byte type = in.getByte(ri);
        int lf = in.indexOf(ri, ri + Math.min(in.readableBytes(), MAX_LINE), (byte) '\n');
        if (lf < 0) {
            if (in.readableBytes() >= MAX_LINE) {
                throw new RespProtocolException("reply line too long");
            }
            return null;
        }
        if (lf == ri || in.getByte(lf - 1) != '\r') {
            throw new RespProtocolException("expected CRLF");
        }
        int lineEnd = lf - 1;
        switch (type) {
            case '+': {
                String s = in.toString(ri + 1, lineEnd - ri - 1, StandardCharsets.UTF_8);
                in.readerIndex(lf + 1);
                return Reply.simple(s);
            }
            case '-': {
                String s = in.toString(ri + 1, lineEnd - ri - 1, StandardCharsets.UTF_8);
                in.readerIndex(lf + 1);
                return new Reply.ErrorReply(s);
            }
            case ':': {
                long v = parseSignedLong(in, ri + 1, lineEnd);
                in.readerIndex(lf + 1);
                return Reply.integer(v);
            }
            case '$': {
                long len = parseSignedLong(in, ri + 1, lineEnd);
                in.readerIndex(lf + 1);
                if (len == -1) {
                    return Reply.NULL_BULK;
                }
                if (len < 0 || len > maxBulkLen) {
                    throw new RespProtocolException("invalid bulk length " + len);
                }
                pendingBulk = (int) len;
                return readBulkData(in);
            }
            case '*': {
                long n = parseSignedLong(in, ri + 1, lineEnd);
                in.readerIndex(lf + 1);
                if (n == -1) {
                    return Reply.NULL_ARRAY;
                }
                if (n < 0 || n > Integer.MAX_VALUE - 8) {
                    throw new RespProtocolException("invalid array length " + n);
                }
                if (n == 0) {
                    return Reply.EMPTY_ARRAY;
                }
                return new ArrayStart((int) n);
            }
            default:
                throw new RespProtocolException("unknown reply type '" + RespRequestParser.printableByte(type) + "'");
        }
    }

    private Reply readBulkData(ByteBuf in) {
        if (in.readableBytes() < pendingBulk + 2) {
            return null;
        }
        byte[] data = new byte[pendingBulk];
        in.readBytes(data);
        if (in.readByte() != '\r' || in.readByte() != '\n') {
            throw new RespProtocolException("expected CRLF after bulk string");
        }
        pendingBulk = -1;
        return Reply.bulk(data);
    }

    private static long parseSignedLong(ByteBuf in, int from, int to) {
        try {
            return NumberCodec.parseLong(in, from, to);
        } catch (NumberFormatException e) {
            throw new RespProtocolException("invalid number in reply");
        }
    }

    /** Internal marker: the header of a non-empty array whose elements follow. */
    private static final class ArrayStart extends Reply {
        final int size;

        ArrayStart(int size) {
            this.size = size;
        }

        @Override
        public Type type() {
            return Type.ARRAY;
        }
    }
}
