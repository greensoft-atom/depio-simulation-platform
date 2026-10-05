package com.jredis.server.db;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * {@code DEBUG DIGEST}: a digest of the whole dataset that two servers holding the same data give
 * alike, whatever the order of their hash tables (docs/16-replication.md §12). Each key's digest
 * covers its name, type, expiry and value; keys are combined by XOR, and so are a hash's fields, a
 * set's members and a stream's groups and pending entries, whose order is not state. A stream
 * consumer's seen and active times are left out: they are never logged, so a replica's differ.
 */
public final class Digest {

    private static final int SIZE = 20;

    private Digest() {
    }

    /** 40 hex characters; all zeros for an empty dataset. */
    public static String of(Db db) {
        byte[] all = new byte[SIZE];
        db.forEach(e -> xor(all, key(e)));
        return hex(all);
    }

    /** One key's digest ({@code DEBUG DIGEST-VALUE}), all zeros for a key that does not exist. */
    public static String of(KeyEntry e) {
        return hex(e == null ? new byte[SIZE] : key(e));
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(SIZE * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static byte[] key(KeyEntry e) {
        MessageDigest md = sha1();
        md.update(e.type());
        bytes(md, e.key);
        number(md, e.expireAt());
        switch (e.type()) {
            case KeyEntry.STRING -> bytes(md, e.stringValue());
            case KeyEntry.HASH -> {
                byte[] fields = new byte[SIZE];
                e.hash().forEach(f -> {
                    MessageDigest m = sha1();
                    bytes(m, f.field());
                    bytes(m, f.value());
                    xor(fields, m.digest());
                });
                md.update(fields);
            }
            case KeyEntry.LIST -> {
                ListValue l = e.list();
                number(md, l.size());
                for (int i = 0; i < l.size(); i++) {
                    bytes(md, l.get(i));
                }
            }
            case KeyEntry.SET -> {
                byte[] members = new byte[SIZE];
                e.set().forEach(m -> {
                    MessageDigest d = sha1();
                    bytes(d, m.key);
                    xor(members, d.digest());
                });
                md.update(members);
            }
            case KeyEntry.ZSET -> {
                for (SkipList.Node n = e.zset().list().first(); n != null; n = n.next()) {
                    bytes(md, n.member());
                    number(md, Double.doubleToLongBits(n.score()));
                }
            }
            case KeyEntry.STREAM -> stream(md, e.stream().freeze());
            default -> throw new IllegalStateException("unknown type " + e.type());
        }
        return md.digest();
    }

    private static void stream(MessageDigest md, StreamValue.Frozen f) {
        number(md, f.lastMs());
        number(md, f.lastSeq());
        f.forEach((ms, seq, fields) -> {
            number(md, ms);
            number(md, seq);
            number(md, fields.length);
            for (byte[] b : fields) {
                bytes(md, b);
            }
            return true;
        });
        byte[] groups = new byte[SIZE];
        for (StreamGroup g : f.groups()) {
            MessageDigest gd = sha1();
            bytes(gd, g.name());
            number(gd, g.lastDelivered().ms());
            number(gd, g.lastDelivered().seq());
            byte[] consumers = new byte[SIZE];
            for (StreamGroup.Consumer c : g.consumers()) {
                MessageDigest cd = sha1();
                bytes(cd, c.name());
                xor(consumers, cd.digest());
            }
            gd.update(consumers);
            byte[] pending = new byte[SIZE];
            for (Map.Entry<StreamId, StreamGroup.Pending> p : g.pending().entrySet()) {
                MessageDigest pd = sha1();
                number(pd, p.getKey().ms());
                number(pd, p.getKey().seq());
                bytes(pd, p.getValue().consumer().name());
                number(pd, p.getValue().deliveryTime());
                number(pd, p.getValue().deliveryCount());
                xor(pending, pd.digest());
            }
            gd.update(pending);
            xor(groups, gd.digest());
        }
        md.update(groups);
    }

    private static void bytes(MessageDigest md, byte[] b) {
        number(md, b.length);
        md.update(b);
    }

    private static void number(MessageDigest md, long v) {
        md.update(ByteBuffer.allocate(8).putLong(v).array());
    }

    private static void xor(byte[] into, byte[] d) {
        for (int i = 0; i < SIZE; i++) {
            into[i] ^= d[i];
        }
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required of every Java runtime", e);
        }
    }
}
