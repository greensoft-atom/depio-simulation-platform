package com.jredis.server.db;

/** What the keyspace tells the rest of the engine. Implemented by the engine. */
public interface DbHooks {

    /** A key was created, changed or deleted: invalidate WATCHers, wake blocked clients. */
    void keyModified(byte[] key);

    /** A key was removed because its TTL passed: propagate DEL, count it. Called after removal. */
    void keyExpired(byte[] key);
}
