package com.jredis.cli;

import com.jredis.common.ArgSplitter;
import com.jredis.common.Bytes;
import com.jredis.common.Reply;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * j-redis-cli: interactive shell and one-shot commands.
 *
 * <pre>
 * j-redis-cli [-h host] [-p port] [-a password] [--raw] [command args...]
 * j-redis-cli --scan [--pattern p] [--count n]
 * j-redis-cli --bigkeys
 * j-redis-cli --latency
 * </pre>
 */
public final class Cli {

    private final PrintStream out = new PrintStream(System.out, true);
    private String host = "127.0.0.1";
    private int port = 6379;
    private String password;
    private boolean raw;
    private RawConnection conn;

    private Cli() {
    }

    public static void main(String[] args) {
        int code;
        try {
            code = new Cli().run(args);
        } catch (IOException e) {
            System.err.println("Could not connect or talk to the server: " + e.getMessage());
            code = 1;
        } catch (RuntimeException e) {
            System.err.println("Error: " + e.getMessage());
            code = 1;
        }
        System.exit(code);
    }

    private int run(String[] args) throws IOException {
        List<String> command = new ArrayList<>();
        String mode = null;
        String pattern = null;
        int count = 100;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!command.isEmpty()) {
                command.add(a);
                continue;
            }
            switch (a) {
                case "-h": host = args[++i]; break;
                case "-p": port = Integer.parseInt(args[++i]); break;
                case "-a": password = args[++i]; break;
                case "--raw": raw = true; break;
                case "--scan": mode = "scan"; break;
                case "--bigkeys": mode = "bigkeys"; break;
                case "--latency": mode = "latency"; break;
                case "--pattern": pattern = args[++i]; break;
                case "--count": count = Integer.parseInt(args[++i]); break;
                case "--help": usage(); return 0;
                default: command.add(a);
            }
        }
        conn = new RawConnection(host, port, 3000);
        if (password != null) {
            Reply r = conn.call(argv("AUTH", password));
            if (r.isError()) {
                System.err.println("AUTH failed: " + r.asString());
                return 1;
            }
        }
        if ("scan".equals(mode)) {
            return scan(pattern, count);
        }
        if ("bigkeys".equals(mode)) {
            return BigKeys.run(conn, out);
        }
        if ("latency".equals(mode)) {
            return latency();
        }
        if (!command.isEmpty()) {
            byte[][] a = new byte[command.size()][];
            for (int i = 0; i < a.length; i++) {
                a[i] = command.get(i).getBytes(StandardCharsets.UTF_8);
            }
            Reply r;
            try {
                r = conn.call(a);
            } catch (IOException e) {
                if (Bytes.upperAscii(a[0]).equals("SHUTDOWN")) {
                    return 0;                          // success: the server closes without replying
                }
                throw e;
            }
            printReply(a, r);
            return r.isError() ? 1 : 0;
        }
        return repl();
    }

    private static byte[][] argv(String... parts) {
        byte[][] a = new byte[parts.length][];
        for (int i = 0; i < parts.length; i++) {
            a[i] = parts[i].getBytes(StandardCharsets.UTF_8);
        }
        return a;
    }

    private int repl() throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String prompt = host + ":" + port + "> ";
        while (true) {
            out.print(prompt);
            out.flush();
            String line = in.readLine();
            if (line == null) {
                out.println();
                return 0;
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            List<byte[]> words = ArgSplitter.split(line.getBytes(StandardCharsets.UTF_8));
            if (words == null) {
                out.println("Invalid argument(s): unbalanced quotes");
                continue;
            }
            String cmd = Bytes.upperAscii(words.get(0));
            if (cmd.equals("EXIT") || cmd.equals("QUIT")) {
                return 0;
            }
            if (cmd.equals("HELP")) {
                out.println("Type a command, e.g. SET key value. Replies are shown like redis-cli. EXIT quits.");
                continue;
            }
            byte[][] argv = words.toArray(new byte[0][]);
            Reply r;
            try {
                r = conn.call(argv);
            } catch (IOException e) {
                if (cmd.equals("SHUTDOWN")) {
                    out.println("(the server is shutting down)");
                    return 0;
                }
                throw e;
            }
            printReply(argv, r);
            if (!r.isError() && (cmd.equals("SUBSCRIBE") || cmd.equals("PSUBSCRIBE"))) {
                out.println("Reading messages... (press Ctrl-C to quit)");
                int acks = words.size() - 2;
                for (int i = 0; i < acks; i++) {
                    print(conn.read(), "");
                }
                while (true) {
                    print(conn.read(), "");
                }
            }
        }
    }

    private int scan(String pattern, int count) throws IOException {
        String cursor = "0";
        do {
            List<String> a = new ArrayList<>();
            a.add("SCAN");
            a.add(cursor);
            if (pattern != null) {
                a.add("MATCH");
                a.add(pattern);
            }
            a.add("COUNT");
            a.add(Integer.toString(count));
            Reply r = conn.call(argv(a.toArray(new String[0])));
            if (r.isError()) {
                System.err.println(r.asString());
                return 1;
            }
            cursor = r.asList().get(0).asString();
            for (Reply k : r.asList().get(1).asList()) {
                out.println(k.asString());
            }
        } while (!cursor.equals("0"));
        return 0;
    }

    private int latency() throws IOException {
        long count = 0;
        double min = Double.MAX_VALUE;
        double max = 0;
        double sum = 0;
        out.println("Measuring PING round trips (Ctrl-C to stop)...");
        long lastPrint = 0;
        while (true) {
            long t0 = System.nanoTime();
            conn.call(argv("PING"));
            double ms = (System.nanoTime() - t0) / 1e6;
            count++;
            sum += ms;
            min = Math.min(min, ms);
            max = Math.max(max, ms);
            if (System.currentTimeMillis() - lastPrint > 1000) {
                lastPrint = System.currentTimeMillis();
                out.printf(Locale.ROOT, "min: %.3f ms, max: %.3f ms, avg: %.3f ms (%d samples)%n", min, max, sum / count, count);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                return 0;
            }
        }
    }

    /** Like redis-cli: INFO and CLIENT LIST/INFO are multi-line text and are shown as is. */
    private void printReply(byte[][] argv, Reply r) {
        String cmd = Bytes.upperAscii(argv[0]);
        boolean text = cmd.equals("INFO")
                || (cmd.equals("CLIENT") && argv.length >= 2 && (Bytes.isKeyword(argv[1], "LIST") || Bytes.isKeyword(argv[1], "INFO")));
        if (text && r.type() == Reply.Type.BULK) {
            out.print(r.asString());
            out.flush();
        } else {
            print(r, "");
        }
    }

    /** redis-cli style rendering. */
    void print(Reply r, String indent) {
        if (raw) {
            printRaw(r);
            return;
        }
        switch (r.type()) {
            case SIMPLE: out.println(r.asString()); break;
            case ERROR: out.println("(error) " + r.asString()); break;
            case INTEGER: out.println("(integer) " + r.asLong()); break;
            case BULK: out.println(quote(r.asBytes())); break;
            case NULL: out.println("(nil)"); break;
            default: {
                List<Reply> l = r.asList();
                if (l.isEmpty()) {
                    out.println("(empty array)");
                    return;
                }
                int width = Integer.toString(l.size()).length();
                for (int i = 0; i < l.size(); i++) {
                    String num = String.format(Locale.ROOT, "%" + width + "d) ", i + 1);
                    out.print(i == 0 ? num : indent + num);
                    print(l.get(i), indent + repeat(' ', num.length()));
                }
            }
        }
    }

    private void printRaw(Reply r) {
        if (r.type() == Reply.Type.ARRAY) {
            for (Reply e : r.asList()) {
                printRaw(e);
            }
        } else if (r.isNull()) {
            out.println();
        } else {
            out.println(r.asString());
        }
    }

    /** Quotes like redis-cli: printable ASCII as is, common escapes, other bytes as \\xHH. */
    static String quote(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length + 2).append('"');
        for (byte x : b) {
            switch (x) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case 7: sb.append("\\a"); break;
                case '\b': sb.append("\\b"); break;
                default:
                    if (x >= 0x20 && x < 0x7f) {
                        sb.append((char) x);
                    } else {
                        sb.append(String.format(Locale.ROOT, "\\x%02x", x & 0xff));
                    }
            }
        }
        return sb.append('"').toString();
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    private static void usage() {
        System.out.println("Usage: j-redis-cli [-h host] [-p port] [-a password] [--raw] [command [args...]]");
        System.out.println("       j-redis-cli --scan [--pattern glob] [--count n]   list keys with SCAN");
        System.out.println("       j-redis-cli --bigkeys                             largest key per type");
        System.out.println("       j-redis-cli --latency                             PING round-trip times");
        System.out.println("Without a command, starts an interactive shell.");
    }
}
