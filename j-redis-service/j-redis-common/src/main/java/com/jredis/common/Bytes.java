package com.jredis.common;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Small helpers for the binary-safe byte[] values that flow through the whole system. */
public final class Bytes {

    public static final byte[] EMPTY = new byte[0];

    private Bytes() {
    }

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static String str(byte[] b) {
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    /** ASCII-only strings (keywords, numbers) without the UTF-8 decoder. */
    public static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    public static boolean equal(byte[] a, byte[] b) {
        return Arrays.equals(a, b);
    }

    /** Unsigned lexicographic comparison, the order Redis uses for members with equal scores. */
    public static int compare(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = a[i] & 0xff;
            int y = b[i] & 0xff;
            if (x != y) {
                return x - y;
            }
        }
        return a.length - b.length;
    }

    /** True if {@code arg} equals {@code upperKeyword} ignoring ASCII case. The keyword must be upper case. */
    public static boolean isKeyword(byte[] arg, String upperKeyword) {
        int n = upperKeyword.length();
        if (arg.length != n) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            int c = arg[i];
            if (c >= 'a' && c <= 'z') {
                c -= 32;
            }
            if (c != upperKeyword.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    public static String upperAscii(byte[] b) {
        char[] out = new char[b.length];
        for (int i = 0; i < b.length; i++) {
            int c = b[i] & 0xff;
            if (c >= 'a' && c <= 'z') {
                c -= 32;
            }
            out[i] = (char) c;
        }
        return new String(out);
    }

    public static String lowerAscii(byte[] b) {
        char[] out = new char[b.length];
        for (int i = 0; i < b.length; i++) {
            int c = b[i] & 0xff;
            if (c >= 'A' && c <= 'Z') {
                c += 32;
            }
            out[i] = (char) c;
        }
        return new String(out);
    }

    /**
     * A printable rendering for logs and SLOWLOG: printable ASCII as is, everything else as
     * {@code \xHH}, truncated to {@code max} source bytes.
     */
    public static String printable(byte[] b, int max) {
        StringBuilder sb = new StringBuilder(Math.min(b.length, max) + 8);
        int n = Math.min(b.length, max);
        for (int i = 0; i < n; i++) {
            int c = b[i] & 0xff;
            if (c == '\\' || c == '"') {
                sb.append('\\').append((char) c);
            } else if (c >= 0x20 && c < 0x7f) {
                sb.append((char) c);
            } else {
                sb.append("\\x");
                sb.append(Character.forDigit(c >> 4, 16)).append(Character.forDigit(c & 0xf, 16));
            }
        }
        if (b.length > max) {
            sb.append("... (").append(b.length - max).append(" more bytes)");
        }
        return sb.toString();
    }

    public static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
