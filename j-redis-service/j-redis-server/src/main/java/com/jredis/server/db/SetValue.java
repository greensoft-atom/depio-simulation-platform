package com.jredis.server.db;

import java.util.Random;
import java.util.function.Consumer;

/** A set of unique members. */
public final class SetValue extends TrackedValue {

    private final Dict<MemberEntry> dict;

    public SetValue(SipHash hasher) {
        super(MemoryEstimator.CONTAINER);
        this.dict = new Dict<>(hasher);
    }

    @Override
    public int size() {
        return dict.size();
    }

    public boolean contains(byte[] member) {
        return dict.get(member) != null;
    }

    /** @return true if added (was not present) */
    public boolean add(byte[] member) {
        int h = dict.hashOf(member);
        if (dict.get(member, h) != null) {
            return false;
        }
        dict.add(new MemberEntry(member, h));
        adjust(MemoryEstimator.MEMBER_ENTRY + MemoryEstimator.byteArray(member.length));
        return true;
    }

    public boolean remove(byte[] member) {
        MemberEntry e = dict.remove(member);
        if (e == null) {
            return false;
        }
        adjust(-(MemoryEstimator.MEMBER_ENTRY + MemoryEstimator.byteArray(member.length)));
        return true;
    }

    public void forEach(Consumer<MemberEntry> visitor) {
        dict.forEach(visitor);
    }

    public long scan(long cursor, Consumer<MemberEntry> visitor) {
        return dict.scan(cursor, visitor);
    }

    public byte[] random(Random rnd) {
        MemberEntry e = dict.random(rnd);
        return e == null ? null : e.key;
    }

    /** A detached deep copy. */
    public SetValue copy(SipHash hasher) {
        SetValue c = new SetValue(hasher);
        dict.forEach(e -> c.add(e.key));
        return c;
    }
}
