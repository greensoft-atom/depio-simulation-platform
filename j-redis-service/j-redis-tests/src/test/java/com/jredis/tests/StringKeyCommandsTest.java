package com.jredis.tests;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StringKeyCommandsTest extends EmbeddedTest {

    @Test
    void setAndGetWithOptions() {
        expect("SET k v", "+OK");
        expect("GET k", "v");
        expect("SET k v2 NX", "(nil)");
        expect("SET k v2 XX", "+OK");
        expect("SET k v3 XX GET", "v2");
        expect("SET missing v XX", "(nil)");
        expect("SET n 1 NX GET", "(nil)");
        expect("GET n", "1");
        expectError("SET k v NX XX", "ERR syntax error");
        expectError("SET k v EX 0", "ERR invalid expire time");
        expectError("SET k v EX 10 PX 100", "ERR syntax error");
        expect("GETDEL k", "v3");
        expect("EXISTS k", ":0");
        expect("GET nothing", "(nil)");
    }

    @Test
    void expiryFollowsTheClock() {
        expect("SET k v EX 10", "+OK");
        advanceSeconds(3);
        expect("TTL k", ":7");
        expect("PTTL k", ":7000");
        expect("PEXPIRETIME k", ":" + (T0 + 10_000));
        advanceSeconds(7);
        expect("GET k", "(nil)");
        expect("TTL k", ":-2");
        expect("SET p v", "+OK");
        expect("TTL p", ":-1");
        expect("EXPIRE p 100", ":1");
        expect("PERSIST p", ":1");
        expect("TTL p", ":-1");
        expect("EXPIRE p -1", ":1");                // a past time deletes
        expect("EXISTS p", ":0");
        expect("SET q v PX 1500", "+OK");
        expect("SET q w KEEPTTL", "+OK");
        expect("PTTL q", ":1500");
        expect("SET q x", "+OK");                   // plain SET clears the TTL
        expect("TTL q", ":-1");
        expect("SET r v", "+OK");
        expect("EXPIRE r 50 NX", ":1");
        expect("EXPIRE r 60 NX", ":0");
        expect("EXPIRE r 40 GT", ":0");
        expect("EXPIRE r 70 GT", ":1");
        expect("EXPIRE r 30 LT", ":1");
        expect("TTL r", ":30");
    }

    @Test
    void countersAndOverflow() {
        expect("INCR c", ":1");
        expect("INCRBY c 41", ":42");
        expect("DECRBY c 2", ":40");
        expect("DECR c", ":39");
        expect("SET big 9223372036854775807", "+OK");
        expectError("INCR big", "ERR increment or decrement would overflow");
        expect("SET s abc", "+OK");
        expectError("INCR s", "ERR value is not an integer or out of range");
        expect("SET sp \" 1\"", "+OK");
        expectError("INCR sp", "ERR value is not an integer or out of range");
        expect("INCRBYFLOAT f 10.5", "10.5");
        expect("INCRBYFLOAT f 0.1", "10.6");
        expect("INCRBYFLOAT f -5", "5.6");
        expect("SET e 5.0e3", "+OK");
        expect("INCRBYFLOAT e 200", "5200");
        expectError("INCRBYFLOAT f inf", "ERR increment would produce NaN or Infinity");
    }

    @Test
    void appendStrlenRanges() {
        expect("APPEND a Hello", ":5");
        expect("APPEND a \" World\"", ":11");
        expect("STRLEN a", ":11");
        expect("GETRANGE a 0 4", "Hello");
        expect("GETRANGE a -5 -1", "World");
        expect("GETRANGE a 5 1", "");
        expect("SETRANGE a 6 Redis", ":11");
        expect("GET a", "Hello Redis");
        expect("SETRANGE pad 3 x", ":4");
        expect("STRLEN nothing", ":0");
    }

    @Test
    void multiKey() {
        expect("MSET a 1 b 2 c 3", "+OK");
        expect("MGET a b missing c", "[1, 2, (nil), 3]");
        expect("MSETNX c 9 d 4", ":0");
        expect("EXISTS d", ":0");
        expect("MSETNX d 4 e 5", ":1");
        expect("DEL a b missing", ":2");
        expect("EXISTS a c c", ":2");
        expect("UNLINK c", ":1");
    }

    @Test
    void typesRenameAndWrongType() {
        expect("SET s v", "+OK");
        expect("LPUSH l a", ":1");
        expect("TYPE s", "+string");
        expect("TYPE l", "+list");
        expect("TYPE nothing", "+none");
        expectError("LPUSH s x", "WRONGTYPE Operation against a key holding the wrong kind of value");
        expectError("GET l", "WRONGTYPE");
        expect("SET t v EX 100", "+OK");
        expect("RENAME t t2", "+OK");
        expect("TTL t2", ":100");                   // RENAME keeps the TTL
        expectError("RENAME nothing x", "ERR no such key");
        expect("RENAMENX t2 s", ":0");
        expect("RENAMENX t2 u", ":1");
        expect("COPY u v", ":1");
        expect("COPY u v", ":0");
        expect("COPY u v REPLACE", ":1");
        expect("GET v", "v");
    }

    @Test
    void scanKeysAndRandomKey() {
        for (int i = 0; i < 500; i++) {
            sync.set("user:" + i, "x");
            sync.set("item:" + i, "y");
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        String cursor = "0";
        do {
            com.jredis.client.ScanResult r = sync.scan(cursor, "user:*", 50);
            seen.addAll(r.keys);
            cursor = r.cursor;
        } while (!cursor.equals("0"));
        assertThat(seen).hasSize(500).allMatch(k -> k.startsWith("user:"));
        expect("DBSIZE", ":1000");
        assertThat(call("KEYS item:1?")).startsWith("[item:1");
        assertThat(call("RANDOMKEY")).matches("(user|item):\\d+");
        expect("FLUSHALL", "+OK");
        expect("RANDOMKEY", "(nil)");
    }

    @Test
    void casAndCadExtensions() {
        expect("J.CAS lock nobody me", ":0");      // missing key never matches
        expect("SET lock me", "+OK");
        expect("J.CAS lock other you", ":0");
        expect("J.CAS lock me you PX 5000", ":1");
        expect("PTTL lock", ":5000");
        expect("J.CAD lock me", ":0");
        expect("J.CAD lock you", ":1");
        expect("EXISTS lock", ":0");
    }

    @Test
    void unknownCommandAndArity() {
        expectError("NOSUCH a b", "ERR unknown command 'NOSUCH'");
        expectError("GET", "ERR wrong number of arguments for 'get' command");
        expectError("SET k", "ERR wrong number of arguments for 'set' command");
    }
}
