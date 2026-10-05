package com.jredis.client;

import com.jredis.common.NumberCodec;
import com.jredis.common.Reply;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reply → Java value conversions for the typed API. */
final class Replies {

    private Replies() {
    }

    static String str(Reply r) {
        return r.isNull() ? null : r.asString();
    }

    static Long lng(Reply r) {
        return r.isNull() ? null : r.asLong();
    }

    static Boolean bool(Reply r) {
        return r.asLong() == 1;
    }

    static Double dbl(Reply r) {
        return r.isNull() ? null : NumberCodec.parseDouble(r.asBytes());
    }

    static Void ok(Reply r) {
        return null;
    }

    static List<String> strings(Reply r) {
        if (r.isNull()) {
            return null;
        }
        List<String> out = new ArrayList<>(r.asList().size());
        for (Reply e : r.asList()) {
            out.add(str(e));
        }
        return out;
    }

    static Set<String> set(Reply r) {
        return new LinkedHashSet<>(strings(r));
    }

    static Map<String, String> map(Reply r) {
        List<Reply> l = r.asList();
        Map<String, String> out = new LinkedHashMap<>(l.size());
        for (int i = 0; i + 1 < l.size(); i += 2) {
            out.put(str(l.get(i)), str(l.get(i + 1)));
        }
        return out;
    }

    /** Flat [member, score, member, score, ...] → scored members. */
    static List<ScoredMember> scored(Reply r) {
        if (r.isNull()) {
            return Collections.emptyList();
        }
        List<Reply> l = r.asList();
        List<ScoredMember> out = new ArrayList<>(l.size() / 2);
        for (int i = 0; i + 1 < l.size(); i += 2) {
            out.add(new ScoredMember(str(l.get(i)), NumberCodec.parseDouble(l.get(i + 1).asBytes())));
        }
        return out;
    }

    static ScanResult scan(Reply r) {
        List<Reply> l = r.asList();
        return new ScanResult(str(l.get(0)), strings(l.get(1)));
    }

    /** J.ZAROUND reply; the window starts at rank max(0, rank - count). */
    static ZAround zaround(Reply r, long count) {
        if (r.isNull()) {
            return null;
        }
        List<Reply> l = r.asList();
        long rank = l.get(0).asLong();
        return new ZAround(rank, Math.max(0, rank - count), scored(l.get(1)));
    }
}
