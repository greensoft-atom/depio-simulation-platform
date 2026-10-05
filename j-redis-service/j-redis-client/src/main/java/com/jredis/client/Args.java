package com.jredis.client;

import com.jredis.common.NumberCodec;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Converts Java arguments into RESP bulk strings. */
final class Args {

    private Args() {
    }

    static byte[] of(Object o) {
        if (o instanceof byte[]) {
            return (byte[]) o;
        }
        if (o instanceof String) {
            return ((String) o).getBytes(StandardCharsets.UTF_8);
        }
        if (o instanceof Long || o instanceof Integer || o instanceof Short || o instanceof Byte) {
            return NumberCodec.toBytes(((Number) o).longValue());
        }
        if (o instanceof Double || o instanceof Float) {
            return NumberCodec.formatDoubleBytes(((Number) o).doubleValue());
        }
        if (o == null) {
            throw new IllegalArgumentException("null argument");
        }
        return String.valueOf(o).getBytes(StandardCharsets.UTF_8);
    }

    static byte[][] of(Object... args) {
        byte[][] out = new byte[args.length][];
        for (int i = 0; i < args.length; i++) {
            out[i] = of(args[i]);
        }
        return out;
    }

    static byte[][] of(List<Object> args) {
        return of(args.toArray());
    }
}
