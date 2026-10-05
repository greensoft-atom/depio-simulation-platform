package com.jredis.server.db;

import java.util.Random;
import java.util.function.Consumer;

/** A hash: field → value. */
public final class HashValue extends TrackedValue {

    private final Dict<FieldEntry> dict;

    public HashValue(SipHash hasher) {
        super(MemoryEstimator.CONTAINER);
        this.dict = new Dict<>(hasher);
    }

    @Override
    public int size() {
        return dict.size();
    }

    public byte[] get(byte[] field) {
        FieldEntry e = dict.get(field);
        return e == null ? null : e.value;
    }

    public boolean contains(byte[] field) {
        return dict.get(field) != null;
    }

    /** @return true if the field is new */
    public boolean put(byte[] field, byte[] value) {
        int h = dict.hashOf(field);
        FieldEntry e = dict.get(field, h);
        if (e != null) {
            adjust(MemoryEstimator.byteArray(value.length) - MemoryEstimator.byteArray(e.value.length));
            e.value = value;
            return false;
        }
        dict.add(new FieldEntry(field, h, value));
        adjust(MemoryEstimator.FIELD_ENTRY + MemoryEstimator.byteArray(field.length) + MemoryEstimator.byteArray(value.length));
        return true;
    }

    public boolean remove(byte[] field) {
        FieldEntry e = dict.remove(field);
        if (e == null) {
            return false;
        }
        adjust(-(MemoryEstimator.FIELD_ENTRY + MemoryEstimator.byteArray(e.key.length) + MemoryEstimator.byteArray(e.value.length)));
        return true;
    }

    public void forEach(Consumer<FieldEntry> visitor) {
        dict.forEach(visitor);
    }

    public long scan(long cursor, Consumer<FieldEntry> visitor) {
        return dict.scan(cursor, visitor);
    }

    public FieldEntry random(Random rnd) {
        return dict.random(rnd);
    }

    /** A detached deep copy (COPY command). Byte arrays are immutable once stored, so they are shared. */
    public HashValue copy(SipHash hasher) {
        HashValue c = new HashValue(hasher);
        dict.forEach(e -> c.put(e.key, e.value));
        return c;
    }
}
