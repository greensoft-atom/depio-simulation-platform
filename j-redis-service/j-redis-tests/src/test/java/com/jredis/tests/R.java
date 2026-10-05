package com.jredis.tests;

import com.jredis.common.ArgSplitter;
import com.jredis.common.Reply;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Compact reply rendering for assertions: {@code +OK}, {@code -ERR ...}, {@code :5}, a bulk string
 * as its text, {@code (nil)}, and arrays as {@code [a, :1, (nil)]}.
 */
final class R {

    private R() {
    }

    static String render(Reply r) {
        switch (r.type()) {
            case SIMPLE: return "+" + r.asString();
            case ERROR: return "-" + r.asString();
            case INTEGER: return ":" + r.asLong();
            case BULK: return new String(r.asBytes(), StandardCharsets.UTF_8);
            case NULL: return "(nil)";
            default: {
                StringBuilder sb = new StringBuilder("[");
                List<Reply> l = r.asList();
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(render(l.get(i)));
                }
                return sb.append(']').toString();
            }
        }
    }

    /** Splits a command line like redis-cli does (quotes and escapes supported). */
    static Object[] args(String line) {
        List<byte[]> parts = ArgSplitter.split(line.getBytes(StandardCharsets.UTF_8));
        if (parts == null) {
            throw new IllegalArgumentException("unbalanced quotes: " + line);
        }
        return parts.toArray(new Object[0]);
    }

    /** The same, as the engine takes a command. */
    static byte[][] bytes(String line) {
        Object[] a = args(line);
        byte[][] out = new byte[a.length][];
        for (int i = 0; i < a.length; i++) {
            out[i] = (byte[]) a[i];
        }
        return out;
    }
}
