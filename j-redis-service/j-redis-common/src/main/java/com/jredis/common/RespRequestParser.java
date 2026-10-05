package com.jredis.common;

import io.netty.buffer.ByteBuf;

import java.util.List;

/**
 * Resumable RESP2 request parser: multibulk ({@code *N $len data ...}) and inline commands.
 *
 * <p>Feed it the same cumulating buffer repeatedly; it consumes bytes only for parts it has fully
 * understood and keeps its position between calls, so a request split across TCP packets is simply
 * continued. Used by the server's network decoder, the AOF loader and the AOF checker.
 *
 * <p>Not thread-safe: one parser per connection or file.
 */
public final class RespRequestParser {

    /** Returned for input that forms no command (an empty inline line, {@code *0}, {@code *-1}). */
    public static final byte[][] EMPTY_REQUEST = new byte[0][];

    public static final int DEFAULT_MAX_INLINE = 64 * 1024;
    public static final int MAX_MULTIBULK = 1024 * 1024;
    private static final int MAX_HEADER = 32;

    private long maxBulkLen;
    private final int maxInline;
    private final boolean allowInline;

    private int argc = -1;          // -1: at the start of a request
    private int argIndex;
    private byte[][] argv;
    private int bulkLen = -1;       // -1: expecting a bulk header

    public RespRequestParser(long maxBulkLen, int maxInline, boolean allowInline) {
        this.maxBulkLen = maxBulkLen;
        this.maxInline = maxInline;
        this.allowInline = allowInline;
    }

    /** Changes the bulk length limit; takes effect at the next bulk header. */
    public void maxBulkLen(long limit) {
        this.maxBulkLen = limit;
    }

    /** True while a multibulk request has started but not finished (used to detect truncation). */
    public boolean isMidRequest() {
        return argc >= 0;
    }

    public void reset() {
        argc = -1;
        argv = null;
        argIndex = 0;
        bulkLen = -1;
    }

    /**
     * @return the next request, {@link #EMPTY_REQUEST} for input that forms no command, or
     *         {@code null} if more bytes are needed
     * @throws RespProtocolException on malformed input
     */
    public byte[][] parse(ByteBuf in) {
        if (argc < 0) {
            if (!in.isReadable()) {
                return null;
            }
            int ri = in.readerIndex();
            if (in.getByte(ri) != '*') {
                if (!allowInline) {
                    throw new RespProtocolException("expected '*', got '" + printableByte(in.getByte(ri)) + "'");
                }
                return parseInline(in);
            }
            int eol = findCrlf(in, ri, MAX_HEADER, "invalid multibulk length");
            if (eol < 0) {
                return null;
            }
            long n = NumberCodec.parseHeaderLong(in, ri + 1, eol);
            if (n == Long.MIN_VALUE || n > MAX_MULTIBULK) {
                throw new RespProtocolException("invalid multibulk length");
            }
            in.readerIndex(eol + 2);
            if (n <= 0) {
                return EMPTY_REQUEST;
            }
            argc = (int) n;
            argv = new byte[Math.min(argc, 1024)][];   // grows with the arguments that actually arrive
            argIndex = 0;
            bulkLen = -1;
        }
        while (argIndex < argc) {
            if (bulkLen < 0) {
                if (!in.isReadable()) {
                    return null;
                }
                int ri = in.readerIndex();
                byte marker = in.getByte(ri);
                if (marker != '$') {
                    throw new RespProtocolException("expected '$', got '" + printableByte(marker) + "'");
                }
                int eol = findCrlf(in, ri, MAX_HEADER, "invalid bulk length");
                if (eol < 0) {
                    return null;
                }
                long len = NumberCodec.parseHeaderLong(in, ri + 1, eol);
                if (len < 0 || len > maxBulkLen) {
                    throw new RespProtocolException("invalid bulk length");
                }
                in.readerIndex(eol + 2);
                bulkLen = (int) len;
            }
            if (in.readableBytes() < bulkLen + 2) {
                return null;
            }
            byte[] arg = new byte[bulkLen];
            in.readBytes(arg);
            if (in.readByte() != '\r' || in.readByte() != '\n') {
                throw new RespProtocolException("expected CRLF after bulk string");
            }
            if (argIndex == argv.length) {
                argv = java.util.Arrays.copyOf(argv, (int) Math.min(argc, 2L * argv.length));
            }
            argv[argIndex++] = arg;
            bulkLen = -1;
        }
        byte[][] result = argv;
        reset();
        return result;
    }

    private byte[][] parseInline(ByteBuf in) {
        int ri = in.readerIndex();
        int readable = in.readableBytes();
        int lf = in.indexOf(ri, ri + Math.min(readable, maxInline + 2), (byte) '\n');
        if (lf < 0) {
            if (readable > maxInline) {
                throw new RespProtocolException("too big inline request");
            }
            return null;
        }
        int end = lf;
        if (end > ri && in.getByte(end - 1) == '\r') {
            end--;
        }
        byte[] line = new byte[end - ri];
        in.getBytes(ri, line);
        in.readerIndex(lf + 1);
        List<byte[]> args = ArgSplitter.split(line);
        if (args == null) {
            throw new RespProtocolException("unbalanced quotes in request");
        }
        if (args.isEmpty()) {
            return EMPTY_REQUEST;
        }
        return args.toArray(new byte[0][]);
    }

    /**
     * Finds the CRLF ending a header that starts at {@code from}.
     *
     * @return the index of '\r', or -1 if not yet available
     */
    static int findCrlf(ByteBuf in, int from, int maxLen, String errorIfTooLong) {
        int readable = in.writerIndex() - from;
        int searchTo = from + Math.min(readable, maxLen);
        int lf = in.indexOf(from, searchTo, (byte) '\n');
        if (lf < 0) {
            if (readable >= maxLen) {
                throw new RespProtocolException(errorIfTooLong);
            }
            return -1;
        }
        if (lf == from || in.getByte(lf - 1) != '\r') {
            throw new RespProtocolException(errorIfTooLong);
        }
        return lf - 1;
    }

    static String printableByte(byte b) {
        int c = b & 0xff;
        return (c >= 0x20 && c < 0x7f) ? String.valueOf((char) c) : String.format("\\x%02x", c);
    }
}
