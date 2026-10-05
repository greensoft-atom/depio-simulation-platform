package com.jredis.common;

import io.netty.buffer.ByteBuf;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/**
 * Strict number parsing and Redis-style formatting.
 *
 * <p>Integers: optional '-', digits, no leading zeros (except "0"), no '+', no whitespace, within
 * signed 64-bit range. {@link Long#parseLong} is more permissive, so it is not used.
 *
 * <p>Doubles: decimal notation with optional exponent, or inf / +inf / -inf / infinity
 * (case-insensitive). NaN, hex, whitespace, Java suffixes ("1.5d") and values that overflow or
 * underflow are rejected.
 */
public final class NumberCodec {

    private static final int CACHE_MAX = 10_000;
    private static final byte[][] CACHE = new byte[CACHE_MAX + 1][];

    static {
        for (int i = 0; i <= CACHE_MAX; i++) {
            CACHE[i] = Integer.toString(i).getBytes(StandardCharsets.US_ASCII);
        }
    }

    private NumberCodec() {
    }

    // ------------------------------------------------------------------ integers

    /** Shared, stackless: an invalid number is ordinary client input, not a bug. */
    private static final NumberFormatException NOT_AN_INTEGER = new StacklessNumberFormatException("not an integer");

    /** Returns true if {@code b} is a strict decimal signed 64-bit integer. */
    public static boolean isLong(byte[] b) {
        try {
            parseLong(b, 0, b.length);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Parses a strict signed 64-bit decimal.
     *
     * @throws NumberFormatException if the value is not a strict integer
     */
    public static long parseLong(byte[] b) {
        return parseLong(b, 0, b.length);
    }

    public static long parseLong(byte[] b, int off, int len) {
        if (len == 0 || len > 20) {
            throw NOT_AN_INTEGER;
        }
        int p = off;
        int end = off + len;
        if (len == 1 && b[p] == '0') {
            return 0;
        }
        boolean negative = false;
        if (b[p] == '-') {
            negative = true;
            p++;
            if (p == end) {
                throw NOT_AN_INTEGER;
            }
        }
        if (b[p] < '1' || b[p] > '9') {
            throw NOT_AN_INTEGER;               // no leading zeros, no '+', no whitespace
        }
        // Accumulate negatively so that Long.MIN_VALUE fits.
        long result = 0;
        long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
        long multmin = limit / 10;
        while (p < end) {
            int d = b[p++] - '0';
            if (d < 0 || d > 9 || result < multmin) {
                throw NOT_AN_INTEGER;
            }
            result *= 10;
            if (result < limit + d) {
                throw NOT_AN_INTEGER;
            }
            result -= d;
        }
        return negative ? result : -result;
    }

    /** Parses an ASCII decimal header value (RESP lengths) from a buffer region; -1 on failure. */
    public static long parseHeaderLong(ByteBuf buf, int from, int to) {
        if (from >= to || to - from > 20) {
            return Long.MIN_VALUE;
        }
        boolean negative = false;
        int p = from;
        if (buf.getByte(p) == '-') {
            negative = true;
            p++;
            if (p == to) {
                return Long.MIN_VALUE;
            }
        }
        if (buf.getByte(p) == '0' && to - p > 1 || negative && buf.getByte(p) == '0') {
            return Long.MIN_VALUE;             // no leading zeros, no "-0" (like Redis string2ll)
        }
        long v = 0;
        while (p < to) {
            int d = buf.getByte(p++) - '0';
            if (d < 0 || d > 9 || v > (Long.MAX_VALUE - d) / 10) {
                return Long.MIN_VALUE;         // not a digit, or it would overflow
            }
            v = v * 10 + d;
        }
        return negative ? -v : v;
    }

    /**
     * Parses a full-range signed decimal from a buffer region (used for RESP ':' replies, where
     * Long.MIN_VALUE is a legitimate value).
     *
     * @throws NumberFormatException if the region is not a valid integer
     */
    public static long parseLong(ByteBuf buf, int from, int to) {
        int len = to - from;
        if (len <= 0 || len > 20) {
            throw NOT_AN_INTEGER;
        }
        byte[] tmp = new byte[len];
        buf.getBytes(from, tmp);
        if (len > 1 && tmp[0] == '0') {
            throw NOT_AN_INTEGER;
        }
        return parseLong(tmp, 0, len);
    }

    public static byte[] toBytes(long v) {
        if (v >= 0 && v <= CACHE_MAX) {
            return CACHE[(int) v];
        }
        return Long.toString(v).getBytes(StandardCharsets.US_ASCII);
    }

    /** Writes the decimal digits of {@code v} without allocating. */
    public static void writeAscii(ByteBuf out, long v) {
        if (v >= 0 && v <= CACHE_MAX) {
            out.writeBytes(CACHE[(int) v]);
            return;
        }
        if (v == Long.MIN_VALUE) {
            out.writeBytes(Bytes.ascii(Long.toString(v)));
            return;
        }
        if (v < 0) {
            out.writeByte('-');
            v = -v;
        }
        int digits = 1;
        for (long t = v / 10; t != 0; t /= 10) {
            digits++;
        }
        int start = out.writerIndex();
        out.ensureWritable(digits);
        out.writerIndex(start + digits);
        int pos = start + digits - 1;
        do {
            out.setByte(pos--, (int) ('0' + v % 10));
            v /= 10;
        } while (v != 0);
    }

    // ------------------------------------------------------------------ doubles

    private static final NumberFormatException NOT_A_FLOAT = new StacklessNumberFormatException("not a float");

    private static final java.math.BigDecimal MAX_DOUBLE = new java.math.BigDecimal(Double.MAX_VALUE);

    /**
     * INCRBYFLOAT arithmetic as Redis presents it: the decimal sum of two valid floats, rounded to
     * 17 fractional digits, trailing zeros removed, never in exponent form. Redis adds in long double
     * and prints with "%.17Lf"; exact decimal addition gives the same text for the values people
     * type (0.1 + 0.2 is "0.3"), where binary double arithmetic would not.
     *
     * @return the sum, or null if either input or the sum is not a finite double
     */
    public static byte[] addFloats(byte[] a, byte[] b) {
        if (Double.isInfinite(parseDouble(a)) || Double.isInfinite(parseDouble(b))) {
            return null;
        }
        java.math.BigDecimal sum = new java.math.BigDecimal(new String(a, StandardCharsets.US_ASCII))
                .add(new java.math.BigDecimal(new String(b, StandardCharsets.US_ASCII)));
        if (sum.abs().compareTo(MAX_DOUBLE) > 0) {
            return null;
        }
        sum = sum.setScale(17, java.math.RoundingMode.HALF_EVEN);
        String text = sum.signum() == 0 ? "0" : sum.stripTrailingZeros().toPlainString();
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Parses a strict double.
     *
     * @throws NumberFormatException if the value is not a valid float (NaN is never valid)
     */
    public static double parseDouble(byte[] b) {
        int len = b.length;
        if (len == 0 || len > 5 * 1024) {       // Redis's long-double limit
            throw NOT_A_FLOAT;
        }
        int p = 0;
        boolean negative = false;
        if (b[0] == '+' || b[0] == '-') {
            negative = b[0] == '-';
            p = 1;
        }
        if (isInfinity(b, p)) {
            return negative ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        // mantissa: digits [ '.' digits ] | '.' digits
        int intDigits = 0;
        int fracDigits = 0;
        boolean nonZeroDigit = false;
        while (p < len && b[p] >= '0' && b[p] <= '9') {
            nonZeroDigit |= b[p] != '0';
            p++;
            intDigits++;
        }
        if (p < len && b[p] == '.') {
            p++;
            while (p < len && b[p] >= '0' && b[p] <= '9') {
                nonZeroDigit |= b[p] != '0';
                p++;
                fracDigits++;
            }
        }
        if (intDigits + fracDigits == 0) {
            throw NOT_A_FLOAT;
        }
        if (p < len && (b[p] == 'e' || b[p] == 'E')) {
            p++;
            if (p < len && (b[p] == '+' || b[p] == '-')) {
                p++;
            }
            int expDigits = 0;
            while (p < len && b[p] >= '0' && b[p] <= '9') {
                p++;
                expDigits++;
            }
            if (expDigits == 0) {
                throw NOT_A_FLOAT;
            }
        }
        if (p != len) {
            throw NOT_A_FLOAT;
        }
        double d = Double.parseDouble(new String(b, StandardCharsets.US_ASCII));
        if (Double.isInfinite(d) || (d == 0.0 && nonZeroDigit)) {
            throw NOT_A_FLOAT;   // overflow or underflow, as strtod's ERANGE in Redis
        }
        return d;
    }

    private static boolean isInfinity(byte[] b, int p) {
        int rest = b.length - p;
        if (rest == 3) {
            return lowerEquals(b, p, "inf");
        }
        if (rest == 8) {
            return lowerEquals(b, p, "infinity");
        }
        return false;
    }

    private static boolean lowerEquals(byte[] b, int p, String lower) {
        for (int i = 0; i < lower.length(); i++) {
            int c = b[p + i];
            if (c >= 'A' && c <= 'Z') {
                c += 32;
            }
            if (c != lower.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Redis-style double formatting: integral values within +/- Long.MAX_VALUE/2 print as integers,
     * infinities as inf / -inf, values of ordinary magnitude in plain decimal, others in e-notation.
     * The result always parses back to exactly the same double.
     */
    public static String formatDouble(double d) {
        if (Double.isNaN(d)) {
            return "nan";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "inf" : "-inf";
        }
        if (d == 0.0) {
            return (1.0 / d) < 0 ? "-0" : "0";
        }
        if (d >= -(Long.MAX_VALUE / 2) && d <= Long.MAX_VALUE / 2) {
            long l = (long) d;
            if (l == d) {
                return Long.toString(l);
            }
        }
        String s = Double.toString(d);            // shortest-ish, always round-trips
        double abs = Math.abs(d);
        if (abs >= 1e-5 && abs < 1e17) {
            if (s.indexOf('E') >= 0) {
                s = new BigDecimal(s).toPlainString();
            }
            return stripTrailingZeros(s);
        }
        // e-notation: "1.5E20" -> "1.5e+20", "1.0E-7" -> "1e-7"
        int e = s.indexOf('E');
        if (e < 0) {
            return stripTrailingZeros(s);
        }
        String mantissa = stripTrailingZeros(s.substring(0, e));
        String exp = s.substring(e + 1);
        return mantissa + "e" + (exp.startsWith("-") ? exp : "+" + exp);
    }

    public static byte[] formatDoubleBytes(double d) {
        return formatDouble(d).getBytes(StandardCharsets.US_ASCII);
    }

    private static String stripTrailingZeros(String s) {
        if (s.indexOf('.') < 0) {
            return s;
        }
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && s.charAt(end - 1) == '.') {
            end--;
        }
        return s.substring(0, end);
    }

    /** NumberFormatException without a stack trace: thrown for ordinary invalid client input. */
    static final class StacklessNumberFormatException extends NumberFormatException {
        private static final long serialVersionUID = 1L;

        StacklessNumberFormatException(String message) {
            super(message);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }
}
