package com.jredis.server.db;

/** A sorted-set member, pointing at its skip-list node (which holds the score). */
public final class ZEntry extends DictEntry {
    SkipList.Node node;

    ZEntry(byte[] member, int hash, SkipList.Node node) {
        super(member, hash);
        this.node = node;
    }

    public byte[] member() {
        return key;
    }

    public double score() {
        return node.score;
    }

    /** The skip-list node holding this member's score. */
    public SkipList.Node nodeRef() {
        return node;
    }
}
