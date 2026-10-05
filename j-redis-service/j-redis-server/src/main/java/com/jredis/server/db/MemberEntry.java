package com.jredis.server.db;

/** A set member. */
public final class MemberEntry extends DictEntry {
    MemberEntry(byte[] member, int hash) {
        super(member, hash);
    }
}
