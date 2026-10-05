package com.jredis.cli;

import com.jredis.common.Reply;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** --bigkeys: walks the keyspace with SCAN and reports the largest key of each type. */
final class BigKeys {

    private BigKeys() {
    }

    private static byte[][] argv(Object... parts) {
        byte[][] a = new byte[parts.length][];
        for (int i = 0; i < parts.length; i++) {
            a[i] = parts[i] instanceof byte[] ? (byte[]) parts[i] : String.valueOf(parts[i]).getBytes(StandardCharsets.UTF_8);
        }
        return a;
    }

    static int run(RawConnection conn, PrintStream out) throws IOException {
        Map<String, String> sizeCommand = new LinkedHashMap<>();
        sizeCommand.put("string", "STRLEN");
        sizeCommand.put("hash", "HLEN");
        sizeCommand.put("list", "LLEN");
        sizeCommand.put("set", "SCARD");
        sizeCommand.put("zset", "ZCARD");
        sizeCommand.put("stream", "XLEN");
        Map<String, String> biggestKey = new LinkedHashMap<>();
        Map<String, Long> biggestSize = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        String cursor = "0";
        long scanned = 0;
        do {
            Reply r = conn.call(argv("SCAN", cursor, "COUNT", 1000));
            if (r.isError()) {
                out.println("SCAN failed: " + r.asString());
                return 1;
            }
            cursor = r.asList().get(0).asString();
            List<Reply> keys = r.asList().get(1).asList();
            for (Reply k : keys) {
                byte[] key = k.asBytes();
                String type = conn.call(argv("TYPE", key)).asString();
                String cmd = sizeCommand.get(type);
                if (cmd == null) {
                    continue;
                }
                Reply sizeReply = conn.call(argv(cmd, key));
                if (sizeReply.isError()) {
                    continue;                       // e.g. the key changed type in between
                }
                long size = sizeReply.asLong();
                scanned++;
                counts.merge(type, 1L, Long::sum);
                if (size > biggestSize.getOrDefault(type, -1L)) {
                    biggestSize.put(type, size);
                    biggestKey.put(type, new String(key, StandardCharsets.UTF_8));
                }
            }
        } while (!cursor.equals("0"));
        out.println("Scanned " + scanned + " keys.");
        for (String type : sizeCommand.keySet()) {
            if (!biggestKey.containsKey(type)) {
                continue;
            }
            String unit = type.equals("string") ? "bytes" : type.equals("hash") ? "fields" : type.equals("stream") ? "entries" : "members";
            out.printf("Biggest %-6s: \"%s\" with %d %s (%d %s keys)%n", type, biggestKey.get(type), biggestSize.get(type), unit,
                    counts.get(type), type);
        }
        return 0;
    }
}
