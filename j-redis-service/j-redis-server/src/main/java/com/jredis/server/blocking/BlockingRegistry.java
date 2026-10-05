package com.jredis.server.blocking;

import com.jredis.server.core.ByteKey;
import com.jredis.server.core.Client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Clients blocked in BLPOP & co.: per key a FIFO of waiters, a timeout index, and the set of keys
 * that became ready during the current command. Command-thread only.
 */
public final class BlockingRegistry {

    private final Map<ByteKey, ArrayDeque<Client>> waiters = new HashMap<>();
    private final TreeMap<Long, ArrayList<Client>> timeouts = new TreeMap<>();
    private final LinkedHashSet<ByteKey> ready = new LinkedHashSet<>();
    private int blockedClients;

    public int blockedClients() {
        return blockedClients;
    }

    public void block(Client c, BlockState state) {
        c.block = state;
        blockedClients++;
        for (byte[] key : state.keys) {
            ArrayDeque<Client> q = waiters.computeIfAbsent(new ByteKey(key), k -> new ArrayDeque<>());
            if (!q.contains(c)) {
                q.addLast(c);
            }
        }
        if (state.timeoutAtMillis > 0) {
            timeouts.computeIfAbsent(state.timeoutAtMillis, t -> new ArrayList<>()).add(c);
        }
    }

    /** Removes the client from every structure. Safe to call for a client that is not blocked. */
    public void unblock(Client c) {
        BlockState state = c.block;
        if (state == null) {
            return;
        }
        c.block = null;
        blockedClients--;
        for (byte[] key : state.keys) {
            ByteKey k = new ByteKey(key);
            ArrayDeque<Client> q = waiters.get(k);
            if (q != null) {
                q.remove(c);
                if (q.isEmpty()) {
                    waiters.remove(k);
                }
            }
        }
        if (state.timeoutAtMillis > 0) {
            ArrayList<Client> list = timeouts.get(state.timeoutAtMillis);
            if (list != null) {
                list.remove(c);
                if (list.isEmpty()) {
                    timeouts.remove(state.timeoutAtMillis);
                }
            }
        }
    }

    /** Called for every modified key; cheap when nobody waits. */
    public void signalReady(byte[] key) {
        if (waiters.isEmpty()) {
            return;
        }
        ByteKey k = new ByteKey(key);
        if (waiters.containsKey(k)) {
            ready.add(k);
        }
    }

    public boolean hasReady() {
        return !ready.isEmpty();
    }

    public List<byte[]> drainReady() {
        List<byte[]> out = new ArrayList<>(ready.size());
        for (ByteKey k : ready) {
            out.add(k.bytes);
        }
        ready.clear();
        return out;
    }

    /** The clients waiting on a key, in arrival order (a copy: serving changes the queue). */
    public List<Client> waitersSnapshot(byte[] key) {
        ArrayDeque<Client> q = waiters.get(new ByteKey(key));
        return q == null ? java.util.Collections.<Client>emptyList() : new ArrayList<>(q);
    }

    /** Clients whose timeout has passed; they are removed from the registry. */
    public List<Client> expireTimeouts(long nowMillis) {
        if (timeouts.isEmpty() || timeouts.firstKey() > nowMillis) {
            return java.util.Collections.emptyList();
        }
        List<Client> out = new ArrayList<>();
        Iterator<Map.Entry<Long, ArrayList<Client>>> it = timeouts.headMap(nowMillis, true).entrySet().iterator();
        while (it.hasNext()) {
            out.addAll(it.next().getValue());
        }
        for (Client c : out) {
            unblock(c);
        }
        return out;
    }

    /** Earliest pending timeout, or Long.MAX_VALUE. */
    public long nextTimeoutMillis() {
        return timeouts.isEmpty() ? Long.MAX_VALUE : timeouts.firstKey();
    }
}
