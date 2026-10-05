package com.jredis.common;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Allocation-free RESP2 encoding into a {@link ByteBuf}. */
public final class RespWriter {

    public static final byte[] CRLF = {'\r', '\n'};
    public static final byte[] OK = ascii("+OK\r\n");
    public static final byte[] QUEUED = ascii("+QUEUED\r\n");
    public static final byte[] PONG = ascii("+PONG\r\n");
    public static final byte[] NULL_BULK = ascii("$-1\r\n");
    public static final byte[] NULL_ARRAY = ascii("*-1\r\n");
    public static final byte[] EMPTY_ARRAY = ascii("*0\r\n");
    public static final byte[] EMPTY_BULK = ascii("$0\r\n\r\n");
    public static final byte[] ZERO = ascii(":0\r\n");
    public static final byte[] ONE = ascii(":1\r\n");

    private static final int HEADER_CACHE = 1024;
    private static final byte[][] BULK_HEADERS = new byte[HEADER_CACHE][];
    private static final byte[][] ARRAY_HEADERS = new byte[HEADER_CACHE][];
    private static final byte[][] INTEGERS = new byte[HEADER_CACHE][];

    static {
        for (int i = 0; i < HEADER_CACHE; i++) {
            BULK_HEADERS[i] = ascii("$" + i + "\r\n");
            ARRAY_HEADERS[i] = ascii("*" + i + "\r\n");
            INTEGERS[i] = ascii(":" + i + "\r\n");
        }
    }

    private RespWriter() {
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    public static void simple(ByteBuf out, String s) {
        out.writeByte('+');
        out.writeCharSequence(s, StandardCharsets.UTF_8);
        out.writeBytes(CRLF);
    }

    /** Longest error text sent; errors that echo client input could otherwise be huge. */
    public static final int MAX_ERROR_LENGTH = 1024;

    /** Error replies must not contain CR or LF (replaced by spaces) and are cut to {@link #MAX_ERROR_LENGTH}. */
    public static void error(ByteBuf out, String s) {
        if (s.length() > MAX_ERROR_LENGTH) {
            s = s.substring(0, MAX_ERROR_LENGTH) + "...";
        }
        out.writeByte('-');
        out.writeCharSequence(s.replace('\r', ' ').replace('\n', ' '), StandardCharsets.UTF_8);
        out.writeBytes(CRLF);
    }

    public static void integer(ByteBuf out, long v) {
        if (v >= 0 && v < HEADER_CACHE) {
            out.writeBytes(INTEGERS[(int) v]);
            return;
        }
        out.writeByte(':');
        NumberCodec.writeAscii(out, v);
        out.writeBytes(CRLF);
    }

    public static void bulk(ByteBuf out, byte[] b) {
        bulkHeader(out, b.length);
        out.writeBytes(b);
        out.writeBytes(CRLF);
    }

    public static void bulk(ByteBuf out, byte[] b, int off, int len) {
        bulkHeader(out, len);
        out.writeBytes(b, off, len);
        out.writeBytes(CRLF);
    }

    public static void bulk(ByteBuf out, String s) {
        bulk(out, s.getBytes(StandardCharsets.UTF_8));
    }

    public static void bulkHeader(ByteBuf out, int len) {
        if (len < HEADER_CACHE) {
            out.writeBytes(BULK_HEADERS[len]);
            return;
        }
        out.writeByte('$');
        NumberCodec.writeAscii(out, len);
        out.writeBytes(CRLF);
    }

    public static void arrayHeader(ByteBuf out, long n) {
        if (n >= 0 && n < HEADER_CACHE) {
            out.writeBytes(ARRAY_HEADERS[(int) n]);
            return;
        }
        out.writeByte('*');
        NumberCodec.writeAscii(out, n);
        out.writeBytes(CRLF);
    }

    public static void nullBulk(ByteBuf out) {
        out.writeBytes(NULL_BULK);
    }

    public static void nullArray(ByteBuf out) {
        out.writeBytes(NULL_ARRAY);
    }

    /** A request (or AOF record): an array of bulk strings. */
    public static void command(ByteBuf out, byte[][] argv) {
        arrayHeader(out, argv.length);
        for (byte[] a : argv) {
            bulk(out, a);
        }
    }

    public static void command(ByteBuf out, List<byte[]> argv) {
        arrayHeader(out, argv.size());
        for (byte[] a : argv) {
            bulk(out, a);
        }
    }

    /** Exact encoded size of a request, for pre-sizing buffers. */
    public static int commandSize(byte[][] argv) {
        int n = 1 + digits(argv.length) + 2;
        for (byte[] a : argv) {
            n += 1 + digits(a.length) + 2 + a.length + 2;
        }
        return n;
    }

    private static int digits(long v) {
        int d = 1;
        while (v >= 10) {
            v /= 10;
            d++;
        }
        return d;
    }
}
