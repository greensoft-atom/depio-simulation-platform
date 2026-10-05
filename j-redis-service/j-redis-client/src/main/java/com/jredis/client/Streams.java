package com.jredis.client;

import com.jredis.common.Reply;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What stream commands answer ([15](../../../../../../../docs/15-streams.md)), typed. */
public final class Streams {

    private Streams() {
    }

    /** An entry: its ID and its fields in order; fields null for a pending entry gone from the stream. */
    public record Entry(String id, Map<String, String> fields) { }

    /** XPENDING's summary: how many, the first and last ID (null when none), and how many per consumer. */
    public record Pending(long count, String first, String last, Map<String, Long> consumers) { }

    /** One pending entry: whose, idle how long, delivered how many times. */
    public record PendingEntry(String id, String consumer, long idleMillis, long deliveries) { }

    /** XAUTOCLAIM's answer: where to go on from ({@code 0-0} when done), what it claimed, what was gone. */
    public record AutoClaim(String next, List<Entry> claimed, List<String> deleted) { }

    /** A consumer group as XINFO GROUPS describes it. */
    public record Group(String name, long consumers, long pending, String lastDeliveredId, long lag) { }

    static Entry entry(Reply r) {
        List<Reply> l = r.asList();
        Reply f = l.get(1);
        if (f.isNull()) {
            return new Entry(l.get(0).asString(), null);
        }
        Map<String, String> fields = new LinkedHashMap<>();
        List<Reply> fl = f.asList();
        for (int i = 0; i + 1 < fl.size(); i += 2) {
            fields.put(fl.get(i).asString(), fl.get(i + 1).asString());
        }
        return new Entry(l.get(0).asString(), fields);
    }

    static List<Entry> entries(Reply r) {
        if (r.isNull()) {
            return Collections.emptyList();
        }
        List<Entry> out = new ArrayList<>();
        for (Reply e : r.asList()) {
            out.add(entry(e));
        }
        return out;
    }

    /** XREAD and XREADGROUP: entries per stream, in the order answered; empty for none (a nil). */
    static Map<String, List<Entry>> streams(Reply r) {
        Map<String, List<Entry>> out = new LinkedHashMap<>();
        if (r.isNull()) {
            return out;
        }
        for (Reply s : r.asList()) {
            out.put(s.asList().get(0).asString(), entries(s.asList().get(1)));
        }
        return out;
    }

    static Pending pending(Reply r) {
        List<Reply> l = r.asList();
        Map<String, Long> consumers = new LinkedHashMap<>();
        if (!l.get(3).isNull()) {
            for (Reply c : l.get(3).asList()) {
                consumers.put(c.asList().get(0).asString(), Long.parseLong(c.asList().get(1).asString()));
            }
        }
        return new Pending(l.get(0).asLong(), Replies.str(l.get(1)), Replies.str(l.get(2)), consumers);
    }

    static List<PendingEntry> pendingEntries(Reply r) {
        List<PendingEntry> out = new ArrayList<>();
        for (Reply p : r.asList()) {
            List<Reply> e = p.asList();
            out.add(new PendingEntry(e.get(0).asString(), e.get(1).asString(), e.get(2).asLong(), e.get(3).asLong()));
        }
        return out;
    }

    static AutoClaim autoClaim(Reply r) {
        List<Reply> l = r.asList();
        List<String> deleted = new ArrayList<>();
        for (Reply d : l.get(2).asList()) {
            deleted.add(d.asString());
        }
        return new AutoClaim(l.get(0).asString(), entries(l.get(1)), deleted);
    }

    static List<Group> groups(Reply r) {
        List<Group> out = new ArrayList<>();
        for (Reply g : r.asList()) {
            Map<String, Reply> f = new LinkedHashMap<>();
            List<Reply> l = g.asList();
            for (int i = 0; i + 1 < l.size(); i += 2) {
                f.put(l.get(i).asString(), l.get(i + 1));
            }
            out.add(new Group(f.get("name").asString(), f.get("consumers").asLong(), f.get("pending").asLong(),
                    f.get("last-delivered-id").asString(), f.get("lag").asLong()));
        }
        return out;
    }

    /** {@code STREAMS key… id…}, in the map's order. */
    static void addStreams(List<Object> a, Map<String, String> streams) {
        a.add("STREAMS");
        a.addAll(streams.keySet());
        a.addAll(streams.values());
    }
}
