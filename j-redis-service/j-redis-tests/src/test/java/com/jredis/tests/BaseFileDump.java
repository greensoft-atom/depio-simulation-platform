package com.jredis.tests;

import com.jredis.common.NumberCodec;
import com.jredis.server.db.HashValue;
import com.jredis.server.db.KeyEntry;
import com.jredis.server.db.ListValue;
import com.jredis.server.db.SetValue;
import com.jredis.server.db.SipHash;
import com.jredis.server.db.SkipList;
import com.jredis.server.db.ZSetValue;
import com.jredis.server.persist.BaseFormat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Reads a base file into the {@link StateDump} format. */
final class BaseFileDump {

    private BaseFileDump() {
    }

    private static String s(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    /** As {@link StateDump} writes a stream's groups. */
    private static String groups(List<com.jredis.server.db.StreamGroup> gs) {
        if (gs.isEmpty()) {
            return "";
        }
        List<String> out = new ArrayList<>();
        for (com.jredis.server.db.StreamGroup g : gs) {
            List<String> pending = new ArrayList<>();
            g.pending().forEach((id, p) -> pending.add(id + " " + s(p.consumer().name()) + " " + p.deliveryCount()));
            List<String> consumers = new ArrayList<>();
            for (com.jredis.server.db.StreamGroup.Consumer c : g.consumers()) {
                consumers.add(s(c.name()));
            }
            Collections.sort(consumers);
            out.add(s(g.name()) + " " + g.lastDelivered() + " " + pending + " " + consumers);
        }
        return " groups " + out;
    }

    static Map<String, String> of(Path file) throws Exception {
        Map<String, String> out = new TreeMap<>();
        BaseFormat.read(file, SipHash.random(), (type, key, expireAt, value) -> {
            String t;
            String v;
            switch (type) {
                case KeyEntry.STRING:
                    t = "string";
                    v = s((byte[]) value);
                    break;
                case KeyEntry.HASH: {
                    t = "hash";
                    Map<String, String> m = new TreeMap<>();
                    ((HashValue) value).forEach(f -> m.put(s(f.field()), s(f.value())));
                    v = m.toString();
                    break;
                }
                case KeyEntry.LIST: {
                    t = "list";
                    List<String> l = new ArrayList<>();
                    ListValue lv = (ListValue) value;
                    for (int i = 0; i < lv.size(); i++) {
                        l.add(s(lv.get(i)));
                    }
                    v = l.toString();
                    break;
                }
                case KeyEntry.SET: {
                    t = "set";
                    List<String> l = new ArrayList<>();
                    ((SetValue) value).forEach(m -> l.add(s(m.key)));
                    Collections.sort(l);
                    v = l.toString();
                    break;
                }
                case KeyEntry.STREAM: {
                    t = "stream";
                    List<String> l = new ArrayList<>();
                    ((com.jredis.server.db.StreamValue) value).freeze().forEach((ms, seq, fields) -> {
                        List<String> f = new ArrayList<>();
                        for (byte[] b : fields) {
                            f.add(s(b));
                        }
                        l.add("[" + Long.toUnsignedString(ms) + "-" + Long.toUnsignedString(seq) + ", [" + String.join(", ", f) + "]]");
                        return true;
                    });
                    v = "[" + String.join(", ", l) + "]" + groups(((com.jredis.server.db.StreamValue) value).freeze().groups());
                    break;
                }
                default: {
                    t = "zset";
                    List<String> l = new ArrayList<>();
                    for (SkipList.Node n = ((ZSetValue) value).list().first(); n != null; n = n.next()) {
                        l.add(s(n.member()));
                        l.add(NumberCodec.formatDouble(n.score()));
                    }
                    v = "[" + String.join(", ", l) + "]";
                }
            }
            out.put(s(key), t + " " + v + (expireAt >= 0 ? " @" + expireAt : ""));
        });
        return out;
    }
}
