package com.jredis.server.info;

import com.jredis.common.Bytes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Commands slower than the configured threshold, newest first. Command-thread only. */
public final class SlowLog {

    public static final class Entry {
        public final long id;
        public final long timestampSeconds;
        public final long durationMicros;
        public final List<String> args;
        public final String clientAddress;
        public final String clientName;

        Entry(long id, long ts, long micros, List<String> args, String addr, String name) {
            this.id = id;
            this.timestampSeconds = ts;
            this.durationMicros = micros;
            this.args = args;
            this.clientAddress = addr;
            this.clientName = name;
        }
    }

    private static final int MAX_ARGS = 32;
    private static final int MAX_ARG_BYTES = 128;

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private long nextId;

    public void add(byte[][] argv, long durationMicros, long nowMillis, String addr, String name, int maxLen) {
        if (maxLen <= 0) {
            entries.clear();
            return;
        }
        List<String> args = new ArrayList<>(Math.min(argv.length, MAX_ARGS));
        for (int i = 0; i < argv.length && i < MAX_ARGS; i++) {
            if (i == MAX_ARGS - 1 && argv.length > MAX_ARGS) {
                args.add("... (" + (argv.length - MAX_ARGS + 1) + " more arguments)");
            } else {
                args.add(Bytes.printable(argv[i], MAX_ARG_BYTES));
            }
        }
        entries.addFirst(new Entry(nextId++, nowMillis / 1000, durationMicros, args, addr, name));
        while (entries.size() > maxLen) {
            entries.removeLast();
        }
    }

    public List<Entry> get(int count) {
        List<Entry> out = new ArrayList<>();
        Iterator<Entry> it = entries.iterator();
        while (it.hasNext() && out.size() < count) {
            out.add(it.next());
        }
        return out;
    }

    public int size() {
        return entries.size();
    }

    public void reset() {
        entries.clear();
    }
}
