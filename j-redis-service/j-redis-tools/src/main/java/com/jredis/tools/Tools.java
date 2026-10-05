package com.jredis.tools;

import java.util.Arrays;

/** Entry point of j-redis-tools.jar: {@code benchmark}, {@code check-aof}, {@code dump}. */
public final class Tools {

    private Tools() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        int code;
        try {
            switch (args[0]) {
                case "benchmark": code = Benchmark.run(rest); break;
                case "check-aof": code = CheckAof.run(rest); break;
                case "dump": code = Dump.run(rest); break;
                default:
                    usage();
                    code = 2;
            }
        } catch (UsageException e) {
            System.err.println(e.getMessage());
            code = 2;
        } catch (Exception e) {
            System.err.println("error: " + e);
            code = 1;
        }
        System.exit(code);
    }

    private static void usage() {
        System.err.println("Usage: j-redis-tools <tool> [options]");
        System.err.println("  benchmark  load generator with latency percentiles (benchmark --help)");
        System.err.println("  check-aof  verify a data directory or AOF file; --fix cuts a torn tail");
        System.err.println("  dump       print the contents of a base (.jrdb) file");
    }

    /** Bad command line: printed without a stack trace, exit code 2. */
    static final class UsageException extends Exception {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }

    static String value(String[] args, int i) throws UsageException {
        if (i >= args.length) {
            throw new UsageException("missing value after " + args[i - 1]);
        }
        return args[i];
    }

    static int intValue(String[] args, int i) throws UsageException {
        String v = value(args, i);
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new UsageException("not a number after " + args[i - 1] + ": " + v);
        }
    }
}
