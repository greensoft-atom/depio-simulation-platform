package com.jredis.tools;

import com.jredis.common.Bytes;
import com.jredis.common.NumberCodec;
import com.jredis.server.db.HashValue;
import com.jredis.server.db.KeyEntry;
import com.jredis.server.db.ListValue;
import com.jredis.server.db.SetValue;
import com.jredis.server.db.SipHash;
import com.jredis.server.db.SkipList;
import com.jredis.server.db.StreamValue;
import com.jredis.server.db.ZSetValue;
import com.jredis.server.persist.BaseFormat;
import com.jredis.server.persist.Manifest;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

/**
 * {@code dump [--values] [--max-elements n] <base-file | data-dir>}
 *
 * <p>Prints one line per key (type, key, TTL, size); with {@code --values} also the contents.
 * Reads the file only; safe while the server runs.
 */
final class Dump {

    private static final String USAGE = "usage: dump [--values] [--max-elements n] <base-file.jrdb | data-dir>";

    private Dump() {
    }

    static int run(String[] args) throws Exception {
        boolean values = false;
        int maxElements = 20;
        Path target = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--values": values = true; break;
                case "--max-elements": maxElements = Tools.intValue(args, ++i); break;
                default:
                    if (args[i].startsWith("-")) {
                        throw new Tools.UsageException(USAGE);
                    }
                    target = Paths.get(args[i]);
            }
        }
        if (target == null) {
            throw new Tools.UsageException(USAGE);
        }
        Path file = target;
        if (Files.isDirectory(target)) {
            Manifest m = Manifest.read(target.resolve(Manifest.FILE));
            if (m.base == null) {
                System.out.println("No base file yet (all data is in " + m.incrs + ").");
                return 0;
            }
            file = target.resolve(m.base);
        }
        PrintStream out = new PrintStream(System.out, false, "UTF-8");
        final boolean showValues = values;
        final int max = maxElements;
        long n = BaseFormat.read(file, SipHash.random(), (type, key, expireAt, value) -> {
            out.print(KeyEntry.typeName(type));
            out.print(' ');
            out.print('"');
            out.print(Bytes.printable(key, 200));
            out.print('"');
            out.print(" size=" + size(type, value));
            if (expireAt >= 0) {
                out.print(" expires=" + Instant.ofEpochMilli(expireAt));
            }
            out.println();
            if (showValues) {
                printValue(out, type, value, max);
            }
        });
        out.println("# " + n + " keys in " + file.getFileName());
        out.flush();
        return 0;
    }

    private static long size(byte type, Object value) {
        switch (type) {
            case KeyEntry.STRING: return ((byte[]) value).length;
            case KeyEntry.HASH: return ((HashValue) value).size();
            case KeyEntry.LIST: return ((ListValue) value).size();
            case KeyEntry.SET: return ((SetValue) value).size();
            case KeyEntry.STREAM: return ((StreamValue) value).size();
            default: return ((ZSetValue) value).size();
        }
    }

    private static void printValue(PrintStream out, byte type, Object value, int max) {
        int[] shown = {0};
        switch (type) {
            case KeyEntry.STRING:
                out.println("  \"" + Bytes.printable((byte[]) value, 1000) + "\"");
                return;
            case KeyEntry.HASH:
                ((HashValue) value).forEach(f -> {
                    if (shown[0]++ < max) {
                        out.println("  \"" + Bytes.printable(f.field(), 200) + "\" => \"" + Bytes.printable(f.value(), 200) + "\"");
                    }
                });
                break;
            case KeyEntry.LIST: {
                ListValue l = (ListValue) value;
                for (int i = 0; i < l.size() && i < max; i++) {
                    out.println("  [" + i + "] \"" + Bytes.printable(l.get(i), 200) + "\"");
                }
                shown[0] = l.size();
                break;
            }
            case KeyEntry.SET:
                ((SetValue) value).forEach(m -> {
                    if (shown[0]++ < max) {
                        out.println("  \"" + Bytes.printable(m.key, 200) + "\"");
                    }
                });
                break;
            case KeyEntry.STREAM: {
                StreamValue.Frozen f = ((StreamValue) value).freeze();
                f.forEach((ms, seq, fields) -> {
                    if (shown[0]++ < max) {
                        StringBuilder sb = new StringBuilder("  " + Long.toUnsignedString(ms) + "-" + Long.toUnsignedString(seq));
                        for (byte[] b : fields) {
                            sb.append(" \"").append(Bytes.printable(b, 200)).append('"');
                        }
                        out.println(sb);
                    }
                    return true;
                });
                break;
            }
            default: {
                ZSetValue z = (ZSetValue) value;
                int i = 0;
                for (SkipList.Node node = z.list().first(); node != null && i < max; node = node.next(), i++) {
                    out.println("  " + NumberCodec.formatDouble(node.score())
                            + " \"" + Bytes.printable(node.member(), 200) + "\"");
                }
                shown[0] = z.size();
            }
        }
        if (shown[0] > max) {
            out.println("  ... " + (shown[0] - max) + " more");
        }
    }
}
