package com.jredis.server.db;

/** A hash field and its value. */
public final class FieldEntry extends DictEntry {
    byte[] value;

    FieldEntry(byte[] field, int hash, byte[] value) {
        super(field, hash);
        this.value = value;
    }

    public byte[] field() {
        return key;
    }

    public byte[] value() {
        return value;
    }
}
