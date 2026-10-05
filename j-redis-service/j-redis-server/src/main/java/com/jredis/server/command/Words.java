package com.jredis.server.command;

import java.nio.charset.StandardCharsets;

/** Byte-array constants for effects written to the AOF. */
final class Words {

    private Words() {
    }

    private static byte[] a(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    static final byte[] SET = a("SET");
    static final byte[] DEL = a("DEL");
    static final byte[] PXAT = a("PXAT");
    static final byte[] KEEPTTL = a("KEEPTTL");
    static final byte[] PEXPIREAT = a("PEXPIREAT");
    static final byte[] PERSIST = a("PERSIST");
    static final byte[] MSET = a("MSET");
    static final byte[] HSET = a("HSET");
    static final byte[] SREM = a("SREM");
    static final byte[] LMOVE = a("LMOVE");
    static final byte[] LEFT = a("LEFT");
    static final byte[] RIGHT = a("RIGHT");
    static final byte[] RENAME = a("RENAME");
    static final byte[] FLUSHALL = a("FLUSHALL");
    static final byte[] ZADD = a("ZADD");
    static final byte[] LPOP = a("LPOP");
    static final byte[] RPOP = a("RPOP");
    static final byte[] ZPOPMIN = a("ZPOPMIN");
    static final byte[] ZPOPMAX = a("ZPOPMAX");
}
