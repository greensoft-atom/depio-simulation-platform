package com.jredis.server.db;

import java.lang.management.ManagementFactory;

/**
 * Object-size constants for the memory estimate. They assume a 64-bit HotSpot JVM. Two independent
 * flags decide the sizes, and they are <em>not</em> the same flag: the object header is 8 bytes of
 * mark word plus the class pointer (4 bytes when compressed, else 8), while an instance field
 * holding a reference is 4 bytes when oops are compressed, else 8.
 * <p>
 * They diverge under ZGC, which turns compressed oops off but keeps compressed class pointers: a
 * 12-byte header with 8-byte references. Both are detected once at start-up.
 */
public final class MemoryEstimator {

    public static final boolean COMPRESSED_OOPS = detectCompressedOops();
    public static final boolean COMPRESSED_CLASS_POINTERS =
            detectVmFlag("UseCompressedClassPointers", COMPRESSED_OOPS);
    static final int HEADER = headerBytes(COMPRESSED_CLASS_POINTERS);
    static final int REF = refBytes(COMPRESSED_OOPS);

    /** 8-byte mark word plus the class pointer. Compressed class pointers, not oops, decide this. */
    static int headerBytes(boolean compressedClassPointers) {
        return compressedClassPointers ? 12 : 16;
    }

    /** An instance field holding a reference. Compressed oops, not class pointers, decide this. */
    static int refBytes(boolean compressedOops) {
        return compressedOops ? 4 : 8;
    }

    /** Per-key: KeyEntry plus about two bucket slots. */
    public static final int KEY_ENTRY = align(HEADER + 3 * REF + 4 * 4 + 8 + 1 + 1) + 2 * REF;   // key,next,value; hash,bucket,slot,epoch; expireAt; type,alive
    /** Per hash field: FieldEntry plus bucket slots. */
    public static final int FIELD_ENTRY = align(HEADER + 3 * REF + 4) + 2 * REF;
    /** Per set member. */
    public static final int MEMBER_ENTRY = align(HEADER + 2 * REF + 4) + 2 * REF;
    /** Per sorted-set member, excluding the skip-list node. */
    public static final int ZSET_ENTRY = align(HEADER + 3 * REF + 4) + 2 * REF;
    /** A container object with its (initially small) dictionary. */
    public static final int CONTAINER = align(HEADER + 3 * REF + 8) + align(HEADER + 2 * REF + 5 * 4) + byteArrayOf(4 * REF);

    private MemoryEstimator() {
    }

    public static int align(long n) {
        return (int) ((n + 7) & ~7L);
    }

    public static int byteArray(int length) {
        return align(HEADER + 4 + (long) length);
    }

    private static int byteArrayOf(int bytes) {
        return align(HEADER + 4 + (long) bytes);
    }

    /** An array of {@code n} longs. */
    public static int longArray(int n) {
        return align(HEADER + 4 + (long) n * 8);
    }

    /** An array of {@code n} references. */
    public static int refArray(int n) {
        return align(HEADER + 4 + (long) n * REF);
    }

    public static int skipListNode(int level) {
        return align(HEADER + 2 * REF + 8 + 2 * REF) + refArray(level) + align(HEADER + 4 + 4L * level);
    }

    /** Asks HotSpot for a boolean VM flag; {@code fallback} if this is not HotSpot or it is unknown. */
    private static boolean detectVmFlag(String name, boolean fallback) {
        try {                                  // HotSpot knows for sure, whatever the flags were
            com.sun.management.HotSpotDiagnosticMXBean hotspot =
                    ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
            if (hotspot != null) {
                return Boolean.parseBoolean(hotspot.getVMOption(name).getValue());
            }
        } catch (Throwable notHotSpot) {
            // fall through to the caller's fallback
        }
        return fallback;
    }

    private static boolean detectCompressedOops() {
        try {                                  // HotSpot knows for sure, whatever the flags were
            com.sun.management.HotSpotDiagnosticMXBean hotspot =
                    ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
            if (hotspot != null) {
                return Boolean.parseBoolean(hotspot.getVMOption("UseCompressedOops").getValue());
            }
        } catch (Throwable notHotSpot) {
            // fall back to reading the command line
        }
        try {
            for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (arg.equals("-XX:-UseCompressedOops")) {
                    return false;
                }
                if (arg.startsWith("-Xmx")) {
                    long bytes = parseSize(arg.substring(4));
                    if (bytes >= 32L * 1024 * 1024 * 1024) {
                        return false;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // fall through to the common default
        }
        return true;
    }

    private static long parseSize(String s) {
        char unit = Character.toLowerCase(s.charAt(s.length() - 1));
        long mul = unit == 'g' ? 1L << 30 : unit == 'm' ? 1L << 20 : unit == 'k' ? 1L << 10 : 1;
        String digits = Character.isDigit(unit) ? s : s.substring(0, s.length() - 1);
        return Long.parseLong(digits) * mul;
    }
}
