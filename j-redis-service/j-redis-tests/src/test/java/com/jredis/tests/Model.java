package com.jredis.tests;

import com.jredis.common.NumberCodec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * The reference model: the same command semantics implemented as naively as possible (Java
 * collections, sorting on demand, lazy expiry on access). Replies are rendered like {@link R}.
 */
final class Model {

    static final String WRONGTYPE = "-WRONGTYPE Operation against a key holding the wrong kind of value";
    static final String NOT_INTEGER = "-ERR value is not an integer or out of range";
    static final String OVERFLOW = "-ERR increment or decrement would overflow";

    private static final class Entry {
        Object v;
        long expireAt = -1;

        Entry(Object v) {
            this.v = v;
        }
    }

    private final TreeMap<String, Entry> keys = new TreeMap<>();
    long now;

    /** A stream: entries by ID, and the last ID ever given, which outlives its entry. */
    private static final class Stream {
        final TreeMap<Id, List<String>> entries = new TreeMap<>();
        Id last = new Id(0, 0);
    }

    /** IDs here stay far below 2^63, so signed comparison is the naive and correct one. */
    private record Id(long ms, long seq) implements Comparable<Id> {
        @Override
        public int compareTo(Id o) {
            return ms != o.ms ? Long.compare(ms, o.ms) : Long.compare(seq, o.seq);
        }

        @Override
        public String toString() {
            return ms + "-" + seq;
        }
    }

    private static final String NOT_GREATER = "-ERR The ID specified in XADD is equal or smaller than the target stream top item";

    private static Id id(String s, long missingSeq) {
        int dash = s.indexOf('-');
        return dash < 0 ? new Id(Long.parseLong(s), missingSeq)
                : new Id(Long.parseLong(s.substring(0, dash)), Long.parseLong(s.substring(dash + 1)));
    }

    private static String entries(Iterable<Map.Entry<Id, List<String>>> es, int count) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<Id, List<String>> e : es) {
            if (count >= 0 && out.size() == count) {
                break;
            }
            out.add("[" + e.getKey() + ", " + arr(e.getValue()) + "]");
        }
        return arr(out);
    }

    Model(long now) {
        this.now = now;
    }

    // ------------------------------------------------------------------ helpers

    private Entry live(String k) {
        Entry e = keys.get(k);
        if (e != null && e.expireAt >= 0 && e.expireAt <= now) {
            keys.remove(k);
            return null;
        }
        return e;
    }

    private static final class WrongType extends RuntimeException {
        private static final long serialVersionUID = 1L;

        WrongType() {
            super(null, null, false, false);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T get(String k, Class<T> type) {
        Entry e = live(k);
        if (e == null) {
            return null;
        }
        if (!type.isInstance(e.v)) {
            throw new WrongType();
        }
        return (T) e.v;
    }

    private <T> T getOrCreate(String k, Class<T> type, T fresh) {
        T v = get(k, type);
        if (v == null) {
            keys.put(k, new Entry(fresh));
            return fresh;
        }
        return v;
    }

    private void dropIfEmpty(String k, int size) {
        if (size == 0) {
            keys.remove(k);
        }
    }

    private static Long parseLong(String s) {
        if (s.isEmpty() || s.length() > 20 || s.equals("-")) {
            return null;
        }
        int i = s.charAt(0) == '-' ? 1 : 0;
        if (s.charAt(i) == '0' && s.length() > i + 1) {
            return null;
        }
        if (s.equals("-0")) {
            return null;
        }
        for (int j = i; j < s.length(); j++) {
            if (s.charAt(j) < '0' || s.charAt(j) > '9') {
                return null;
            }
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String fmt(double d) {
        return NumberCodec.formatDouble(d);
    }

    private static String arr(List<String> items) {
        return "[" + String.join(", ", items) + "]";
    }

    /** Sorted-set members ordered by (score, member). */
    private static List<Map.Entry<String, Double>> ordered(Map<String, Double> z) {
        List<Map.Entry<String, Double>> l = new ArrayList<>(z.entrySet());
        l.sort((a, b) -> {
            int c = Double.compare(a.getValue(), b.getValue());
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });
        return l;
    }

    private static int[] range(long start, long stop, int size) {
        if (start < 0) {
            start = Math.max(0, size + start);
        }
        if (stop < 0) {
            stop = size + stop;
        }
        if (stop >= size) {
            stop = size - 1;
        }
        if (start > stop || start >= size) {
            return null;
        }
        return new int[] {(int) start, (int) stop};
    }

    // ------------------------------------------------------------------ commands

    String exec(String[] a) {
        try {
            return run(a);
        } catch (WrongType e) {
            return WRONGTYPE;
        }
    }

    @SuppressWarnings("unchecked")
    private String run(String[] a) {
        String k = a.length > 1 ? a[1] : null;
        switch (a[0]) {
            case "SET": {
                boolean nx = false, xx = false, keepTtl = false;
                long expireAt = -1;
                for (int i = 3; i < a.length; i++) {
                    switch (a[i]) {
                        case "NX": nx = true; break;
                        case "XX": xx = true; break;
                        case "KEEPTTL": keepTtl = true; break;
                        case "EX": expireAt = now + Long.parseLong(a[++i]) * 1000; break;
                        case "PX": expireAt = now + Long.parseLong(a[++i]); break;
                        default: throw new IllegalArgumentException(a[i]);
                    }
                }
                Entry old = live(k);
                if ((nx && old != null) || (xx && old == null)) {
                    return "(nil)";
                }
                Entry e = new Entry(a[2]);
                e.expireAt = keepTtl && old != null ? old.expireAt : expireAt;
                keys.put(k, e);
                return "+OK";
            }
            case "GET": {
                String s = get(k, String.class);
                return s == null ? "(nil)" : s;
            }
            case "GETDEL": {
                String s = get(k, String.class);
                if (s == null) {
                    return "(nil)";
                }
                keys.remove(k);
                return s;
            }
            case "DEL": {
                int n = 0;
                for (int i = 1; i < a.length; i++) {
                    if (live(a[i]) != null) {
                        keys.remove(a[i]);
                        n++;
                    }
                }
                return ":" + n;
            }
            case "EXISTS": return ":" + (live(k) != null ? 1 : 0);
            case "TYPE": {
                Entry e = live(k);
                if (e == null) {
                    return "+none";
                }
                Object v = e.v;
                if (v instanceof Stream) {
                    return "+stream";
                }
                return "+" + (v instanceof String ? "string" : v instanceof Map ? (v instanceof TreeMap ? "zset" : "hash")
                        : v instanceof List ? "list" : "set");
            }
            case "INCRBY": {
                Entry e = live(k);
                long cur = 0;
                if (e != null) {
                    if (!(e.v instanceof String)) {
                        return WRONGTYPE;
                    }
                    Long p = parseLong((String) e.v);
                    if (p == null) {
                        return NOT_INTEGER;
                    }
                    cur = p;
                }
                long next;
                try {
                    next = Math.addExact(cur, Long.parseLong(a[2]));
                } catch (ArithmeticException ex) {
                    return OVERFLOW;
                }
                if (e == null) {
                    keys.put(k, new Entry(Long.toString(next)));
                } else {
                    e.v = Long.toString(next);
                }
                return ":" + next;
            }
            case "APPEND": {
                Entry e = live(k);
                if (e == null) {
                    keys.put(k, new Entry(a[2]));
                    return ":" + a[2].length();
                }
                if (!(e.v instanceof String)) {
                    return WRONGTYPE;
                }
                e.v = e.v + a[2];
                return ":" + ((String) e.v).length();
            }
            case "STRLEN": {
                String s = get(k, String.class);
                return ":" + (s == null ? 0 : s.length());
            }
            case "PEXPIRE": {
                Entry e = live(k);
                if (e == null) {
                    return ":0";
                }
                long when = now + Long.parseLong(a[2]);
                if (when <= now) {
                    keys.remove(k);
                } else {
                    e.expireAt = when;
                }
                return ":1";
            }
            case "PERSIST": {
                Entry e = live(k);
                if (e == null || e.expireAt < 0) {
                    return ":0";
                }
                e.expireAt = -1;
                return ":1";
            }
            case "TTL":
            case "PTTL": {
                Entry e = live(k);
                if (e == null) {
                    return ":-2";
                }
                if (e.expireAt < 0) {
                    return ":-1";
                }
                long ttl = Math.max(0, e.expireAt - now);
                return ":" + (a[0].equals("PTTL") ? ttl : (ttl + 500) / 1000);
            }
            case "RENAME": {
                Entry e = live(k);
                if (e == null) {
                    return "-ERR no such key";
                }
                keys.remove(k);
                keys.put(a[2], e);
                return "+OK";
            }
            // ---------------------------------------------------------- hashes
            case "HSET": {
                Map<String, String> h = getOrCreate(k, HashMap.class, new HashMap<String, String>());
                int added = 0;
                for (int i = 2; i + 1 < a.length; i += 2) {
                    if (h.put(a[i], a[i + 1]) == null) {
                        added++;
                    }
                }
                return ":" + added;
            }
            case "HGET": {
                Map<String, String> h = get(k, HashMap.class);
                String v = h == null ? null : h.get(a[2]);
                return v == null ? "(nil)" : v;
            }
            case "HDEL": {
                Map<String, String> h = get(k, HashMap.class);
                if (h == null) {
                    return ":0";
                }
                int n = 0;
                for (int i = 2; i < a.length; i++) {
                    if (h.remove(a[i]) != null) {
                        n++;
                    }
                }
                dropIfEmpty(k, h.size());
                return ":" + n;
            }
            case "HLEN": {
                Map<String, String> h = get(k, HashMap.class);
                return ":" + (h == null ? 0 : h.size());
            }
            case "HINCRBY": {
                Map<String, String> h = get(k, HashMap.class);
                long cur = 0;
                if (h != null && h.containsKey(a[2])) {
                    Long p = parseLong(h.get(a[2]));
                    if (p == null) {
                        return "-ERR hash value is not an integer";
                    }
                    cur = p;
                }
                long next;
                try {
                    next = Math.addExact(cur, Long.parseLong(a[3]));
                } catch (ArithmeticException ex) {
                    return OVERFLOW;
                }
                if (h == null) {
                    h = getOrCreate(k, HashMap.class, new HashMap<String, String>());
                }
                h.put(a[2], Long.toString(next));
                return ":" + next;
            }
            case "HGETALL": {
                Map<String, String> h = get(k, HashMap.class);
                List<String> pairs = new ArrayList<>();
                if (h != null) {
                    h.forEach((f, v) -> pairs.add(f + "=" + v));
                }
                Collections.sort(pairs);
                return arr(pairs);
            }
            // ---------------------------------------------------------- lists
            case "LPUSH":
            case "RPUSH": {
                List<String> l = getOrCreate(k, ArrayList.class, new ArrayList<String>());
                for (int i = 2; i < a.length; i++) {
                    if (a[0].equals("LPUSH")) {
                        l.add(0, a[i]);
                    } else {
                        l.add(a[i]);
                    }
                }
                return ":" + l.size();
            }
            case "LPOP":
            case "RPOP": {
                List<String> l = get(k, ArrayList.class);
                if (l == null) {
                    return "(nil)";
                }
                boolean left = a[0].equals("LPOP");
                if (a.length == 2) {
                    String v = left ? l.remove(0) : l.remove(l.size() - 1);
                    dropIfEmpty(k, l.size());
                    return v;
                }
                int n = Integer.parseInt(a[2]);
                List<String> out = new ArrayList<>();
                while (n-- > 0 && !l.isEmpty()) {
                    out.add(left ? l.remove(0) : l.remove(l.size() - 1));
                }
                dropIfEmpty(k, l.size());
                return arr(out);
            }
            case "LRANGE": {
                List<String> l = get(k, ArrayList.class);
                if (l == null) {
                    return "[]";
                }
                int[] r = range(Long.parseLong(a[2]), Long.parseLong(a[3]), l.size());
                return r == null ? "[]" : arr(l.subList(r[0], r[1] + 1));
            }
            case "LLEN": {
                List<String> l = get(k, ArrayList.class);
                return ":" + (l == null ? 0 : l.size());
            }
            case "LINDEX": {
                List<String> l = get(k, ArrayList.class);
                if (l == null) {
                    return "(nil)";
                }
                int i = Integer.parseInt(a[2]);
                if (i < 0) {
                    i += l.size();
                }
                return i < 0 || i >= l.size() ? "(nil)" : l.get(i);
            }
            case "LSET": {
                List<String> l = get(k, ArrayList.class);
                if (l == null) {
                    return "-ERR no such key";
                }
                int i = Integer.parseInt(a[2]);
                if (i < 0) {
                    i += l.size();
                }
                if (i < 0 || i >= l.size()) {
                    return "-ERR index out of range";
                }
                l.set(i, a[3]);
                return "+OK";
            }
            case "LREM": {
                List<String> l = get(k, ArrayList.class);
                if (l == null) {
                    return ":0";
                }
                long count = Long.parseLong(a[2]);
                long limit = count == 0 ? Long.MAX_VALUE : Math.abs(count);
                int removed = 0;
                if (count >= 0) {
                    for (int i = 0; i < l.size() && removed < limit; ) {
                        if (l.get(i).equals(a[3])) {
                            l.remove(i);
                            removed++;
                        } else {
                            i++;
                        }
                    }
                } else {
                    for (int i = l.size() - 1; i >= 0 && removed < limit; i--) {
                        if (l.get(i).equals(a[3])) {
                            l.remove(i);
                            removed++;
                        }
                    }
                }
                dropIfEmpty(k, l.size());
                return ":" + removed;
            }
            // ---------------------------------------------------------- sets
            case "SADD": {
                Set<String> s = getOrCreate(k, HashSet.class, new HashSet<String>());
                int n = 0;
                for (int i = 2; i < a.length; i++) {
                    if (s.add(a[i])) {
                        n++;
                    }
                }
                return ":" + n;
            }
            case "SREM": {
                Set<String> s = get(k, HashSet.class);
                if (s == null) {
                    return ":0";
                }
                int n = 0;
                for (int i = 2; i < a.length; i++) {
                    if (s.remove(a[i])) {
                        n++;
                    }
                }
                dropIfEmpty(k, s.size());
                return ":" + n;
            }
            case "SCARD": {
                Set<String> s = get(k, HashSet.class);
                return ":" + (s == null ? 0 : s.size());
            }
            case "SISMEMBER": {
                Set<String> s = get(k, HashSet.class);
                return ":" + (s != null && s.contains(a[2]) ? 1 : 0);
            }
            case "SMEMBERS": {
                Set<String> s = get(k, HashSet.class);
                List<String> l = s == null ? new ArrayList<>() : new ArrayList<>(s);
                Collections.sort(l);
                return arr(l);
            }
            case "SINTERSTORE":
            case "SUNIONSTORE": {
                boolean inter = a[0].equals("SINTERSTORE");
                Set<String> result = null;
                for (int i = 2; i < a.length; i++) {
                    Set<String> s = get(a[i], HashSet.class);
                    Set<String> src = s == null ? Collections.<String>emptySet() : s;
                    if (result == null) {
                        result = new HashSet<>(src);
                    } else if (inter) {
                        result.retainAll(src);
                    } else {
                        result.addAll(src);
                    }
                }
                keys.remove(a[1]);
                if (!result.isEmpty()) {
                    keys.put(a[1], new Entry(result));
                }
                return ":" + result.size();
            }
            // ---------------------------------------------------------- sorted sets
            case "ZADD": {
                TreeMap<String, Double> z = getOrCreate(k, TreeMap.class, new TreeMap<String, Double>());
                int added = 0;
                for (int i = 2; i + 1 < a.length; i += 2) {
                    if (z.put(a[i + 1], Double.parseDouble(a[i])) == null) {
                        added++;
                    }
                }
                return ":" + added;
            }
            case "ZINCRBY": {
                TreeMap<String, Double> z = getOrCreate(k, TreeMap.class, new TreeMap<String, Double>());
                double s = z.getOrDefault(a[3], 0.0) + Double.parseDouble(a[2]);
                z.put(a[3], s);
                return fmt(s);
            }
            case "ZREM": {
                TreeMap<String, Double> z = get(k, TreeMap.class);
                if (z == null) {
                    return ":0";
                }
                int n = 0;
                for (int i = 2; i < a.length; i++) {
                    if (z.remove(a[i]) != null) {
                        n++;
                    }
                }
                dropIfEmpty(k, z.size());
                return ":" + n;
            }
            case "ZSCORE": {
                TreeMap<String, Double> z = get(k, TreeMap.class);
                Double s = z == null ? null : z.get(a[2]);
                return s == null ? "(nil)" : fmt(s);
            }
            case "ZCARD": {
                TreeMap<String, Double> z = get(k, TreeMap.class);
                return ":" + (z == null ? 0 : z.size());
            }
            case "ZRANK":
            case "ZREVRANK": {
                TreeMap<String, Double> z = get(k, TreeMap.class);
                if (z == null || !z.containsKey(a[2])) {
                    return "(nil)";
                }
                List<Map.Entry<String, Double>> o = ordered(z);
                int r = 0;
                while (!o.get(r).getKey().equals(a[2])) {
                    r++;
                }
                return ":" + (a[0].equals("ZRANK") ? r : o.size() - 1 - r);
            }
            case "ZRANGE": {
                TreeMap<String, Double> z = get(k, TreeMap.class);
                if (z == null) {
                    return "[]";
                }
                List<Map.Entry<String, Double>> o = ordered(z);
                int[] r = range(Long.parseLong(a[2]), Long.parseLong(a[3]), o.size());
                List<String> out = new ArrayList<>();
                if (r != null) {
                    for (int i = r[0]; i <= r[1]; i++) {
                        out.add(o.get(i).getKey());
                        if (a.length > 4) {
                            out.add(fmt(o.get(i).getValue()));
                        }
                    }
                }
                return arr(out);
            }
            case "ZPOPMIN": {
                TreeMap<String, Double> z = get(k, TreeMap.class);
                if (z == null) {
                    return "[]";
                }
                int n = a.length > 2 ? Integer.parseInt(a[2]) : 1;
                List<String> out = new ArrayList<>();
                List<Map.Entry<String, Double>> o = ordered(z);
                for (int i = 0; i < n && i < o.size(); i++) {
                    out.add(o.get(i).getKey());
                    out.add(fmt(o.get(i).getValue()));
                    z.remove(o.get(i).getKey());
                }
                dropIfEmpty(k, z.size());
                return arr(out);
            }
            case "J.ZAROUND": {
                TreeMap<String, Double> z = get(k, TreeMap.class);
                if (z == null || !z.containsKey(a[2])) {
                    return "(nil)";
                }
                int count = Integer.parseInt(a[3]);
                boolean rev = a.length > 4 && a[4].equals("REV");
                List<Map.Entry<String, Double>> o = ordered(z);
                if (rev) {
                    Collections.reverse(o);
                }
                int r = 0;
                while (!o.get(r).getKey().equals(a[2])) {
                    r++;
                }
                List<String> window = new ArrayList<>();
                for (int i = Math.max(0, r - count); i <= Math.min(o.size() - 1, r + count); i++) {
                    window.add(o.get(i).getKey());
                }
                return "[:" + r + ", " + arr(window) + "]";
            }
            case "XADD": {
                int i = 2;
                long maxLen = -1;
                if (a[i].equals("MAXLEN")) {
                    maxLen = Long.parseLong(a[i + 1]);
                    i += 2;
                }
                Stream s = get(k, Stream.class);
                Id last = s == null ? new Id(0, 0) : s.last;
                Id id;
                if (a[i].equals("*")) {
                    id = now > last.ms ? new Id(now, 0) : new Id(last.ms, last.seq + 1);
                } else if (a[i].endsWith("-*")) {
                    long ms = Long.parseLong(a[i].substring(0, a[i].length() - 2));
                    if (ms < last.ms) {
                        return NOT_GREATER;
                    }
                    id = ms > last.ms ? new Id(ms, 0) : new Id(ms, last.seq + 1);
                } else {
                    id = id(a[i], 0);
                    if (id.ms == 0 && id.seq == 0) {
                        return "-ERR The ID specified in XADD must be greater than 0-0";
                    }
                    if (id.compareTo(last) <= 0) {
                        return NOT_GREATER;
                    }
                }
                s = getOrCreate(k, Stream.class, new Stream());
                List<String> fields = new ArrayList<>();
                for (int j = i + 1; j < a.length; j++) {
                    fields.add(a[j]);
                }
                s.entries.put(id, fields);
                s.last = id;
                while (maxLen >= 0 && s.entries.size() > maxLen) {
                    s.entries.pollFirstEntry();
                }
                return id.toString();
            }
            case "XDEL": {
                Stream s = get(k, Stream.class);
                int n = 0;
                for (int i = 2; i < a.length; i++) {
                    if (s != null && s.entries.remove(id(a[i], 0)) != null) {
                        n++;
                    }
                }
                return ":" + n;
            }
            case "XTRIM": {
                Stream s = get(k, Stream.class);
                if (s == null) {
                    return ":0";
                }
                int n = 0;
                if (a[2].equals("MAXLEN")) {
                    long maxLen = Long.parseLong(a[3]);
                    while (s.entries.size() > maxLen) {
                        s.entries.pollFirstEntry();
                        n++;
                    }
                } else {
                    Id min = id(a[3], 0);
                    while (!s.entries.isEmpty() && s.entries.firstKey().compareTo(min) < 0) {
                        s.entries.pollFirstEntry();
                        n++;
                    }
                }
                return ":" + n;
            }
            case "XLEN": {
                Stream s = get(k, Stream.class);
                return ":" + (s == null ? 0 : s.entries.size());
            }
            case "XRANGE":
            case "XREVRANGE": {
                boolean rev = a[0].equals("XREVRANGE");
                Stream s = get(k, Stream.class);
                String lo = rev ? a[3] : a[2];
                String hi = rev ? a[2] : a[3];
                Id from = lo.equals("-") ? new Id(0, 0) : id(lo, 0);
                Id to = hi.equals("+") ? new Id(Long.MAX_VALUE, Long.MAX_VALUE) : id(hi, Long.MAX_VALUE);
                int count = a.length == 6 ? Integer.parseInt(a[5]) : -1;
                if (s == null || from.compareTo(to) > 0) {
                    return "[]";
                }
                NavigableMap<Id, List<String>> sub = s.entries.subMap(from, true, to, true);
                return entries((rev ? sub.descendingMap() : sub).entrySet(), count);
            }
            default:
                throw new IllegalArgumentException("model does not know " + a[0]);
        }
    }

    // ------------------------------------------------------------------ state

    /** The live keyspace in the format of {@link StateDump}. */
    @SuppressWarnings("unchecked")
    Map<String, String> dump() {
        Map<String, String> out = new TreeMap<>();
        for (String k : new ArrayList<>(keys.keySet())) {
            Entry e = live(k);
            if (e == null) {
                continue;
            }
            String type;
            String value;
            if (e.v instanceof Stream) {
                type = "stream";
                value = entries(((Stream) e.v).entries.entrySet(), -1);
            } else if (e.v instanceof String) {
                type = "string";
                value = (String) e.v;
            } else if (e.v instanceof TreeMap) {
                type = "zset";
                List<String> items = new ArrayList<>();
                for (Map.Entry<String, Double> m : ordered((TreeMap<String, Double>) e.v)) {
                    items.add(m.getKey());
                    items.add(fmt(m.getValue()));
                }
                value = arr(items);
            } else if (e.v instanceof HashMap) {
                type = "hash";
                value = new TreeMap<>((Map<String, String>) e.v).toString();
            } else if (e.v instanceof List) {
                type = "list";
                value = e.v.toString();
            } else {
                type = "set";
                List<String> m = new ArrayList<>((Set<String>) e.v);
                Collections.sort(m);
                value = m.toString();
            }
            out.put(k, type + " " + value + (e.expireAt >= 0 ? " @" + e.expireAt : ""));
        }
        return out;
    }
}
