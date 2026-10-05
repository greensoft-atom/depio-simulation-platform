package com.jredis.server.tx;

import com.jredis.server.core.ByteKey;
import com.jredis.server.core.Client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Keys watched by clients for optimistic transactions. Command-thread only. */
public final class WatchRegistry {

    private final Map<ByteKey, ArrayList<Client>> watchers = new HashMap<>();
    private int watchingClients;

    public int watchingClients() {
        return watchingClients;
    }

    public void watch(Client c, byte[] key) {
        ByteKey k = new ByteKey(key);
        ArrayList<Client> list = watchers.computeIfAbsent(k, x -> new ArrayList<>());
        if (list.contains(c)) {
            return;
        }
        list.add(c);
        if (c.watchedKeys.isEmpty()) {
            watchingClients++;
        }
        c.watchedKeys.add(key);
    }

    public void unwatchAll(Client c) {
        if (c.watchedKeys.isEmpty()) {
            c.dirtyCas = false;
            return;
        }
        for (byte[] key : c.watchedKeys) {
            ByteKey k = new ByteKey(key);
            ArrayList<Client> list = watchers.get(k);
            if (list != null) {
                list.remove(c);
                if (list.isEmpty()) {
                    watchers.remove(k);
                }
            }
        }
        c.watchedKeys.clear();
        c.dirtyCas = false;
        watchingClients--;
    }

    /** A key changed: every client watching it will see its EXEC aborted. */
    public void touch(byte[] key) {
        if (watchers.isEmpty()) {
            return;
        }
        ArrayList<Client> list = watchers.get(new ByteKey(key));
        if (list != null) {
            for (Client c : list) {
                c.dirtyCas = true;
            }
        }
    }

    /** FLUSHALL: invalidates the watchers of keys that exist (a key that was never there did not change). */
    public void touchExisting(com.jredis.server.db.Db db) {
        for (Map.Entry<ByteKey, ArrayList<Client>> e : watchers.entrySet()) {
            if (db.lookupRead(e.getKey().bytes) != null) {
                for (Client c : e.getValue()) {
                    c.dirtyCas = true;
                }
            }
        }
    }
}
