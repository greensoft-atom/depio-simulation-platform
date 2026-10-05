package com.jredis.tests;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Command semantics found by review; each test failed before its fix. */
class CommandRegressionTest extends EmbeddedTest {

    /** Redis adds in long double and prints 17 digits; binary double arithmetic did not match. */
    @Test
    void incrByFloatGivesTheDecimalResultsRedisGives() {
        expect("SET f 0.1", "+OK");
        expect("INCRBYFLOAT f 0.2", "0.3");
        expect("SET g 3.2", "+OK");
        expect("INCRBYFLOAT g 0.1", "3.3");
        for (int i = 0; i < 10; i++) {
            call("INCRBYFLOAT h 0.1");
        }
        expect("GET h", "1");
        expect("HINCRBYFLOAT hash f 0.1", "0.1");
        expect("HINCRBYFLOAT hash f 0.2", "0.3");
        expect("INCRBYFLOAT big 1e20", "100000000000000000000");
        expectError("INCRBYFLOAT f inf", "ERR increment would produce NaN or Infinity");
        expect("SET notnum abc", "+OK");
        expectError("INCRBYFLOAT notnum 1", "ERR value is not a valid float");
    }

    @Test
    void repeatedSetOptionsAreAllowedMixedOnesAreNot() {
        expect("SET k v EX 10 EX 20", "+OK");
        expect("TTL k", ":20");
        expect("SET k v KEEPTTL KEEPTTL", "+OK");
        expect("TTL k", ":20");
        expectError("SET k v EX 10 PX 100", "ERR syntax error");
        expectError("SET k v KEEPTTL EX 10", "ERR syntax error");
        expect("GETEX k PERSIST PERSIST", "v");
        expect("TTL k", ":-1");
        expect("GETEX missing EX 0", "(nil)");                  // the key is looked up first
        expectError("GETEX k EX 0", "ERR invalid expire time");
    }

    @Test
    void aMissingSourceIsReportedBeforeTheDestinationType() {
        expect("SET str v", "+OK");
        expect("LMOVE missing str LEFT LEFT", "(nil)");
        expect("RPOPLPUSH missing str", "(nil)");
        expect("SMOVE missing str m", ":0");
        expect("LINDEX missing notanumber", "(nil)");
    }

    @Test
    void setrangeWithAHugeOffsetIsASizeError() {
        expectError("SETRANGE sr 9223372036854775807 x", "ERR string exceeds maximum allowed size");
        expect("EXISTS sr", ":0");
    }

    @Test
    void randomCommandsWithAHugePositiveCountReturnEverything() {
        expect("SADD s a b", ":2");
        assertThat(members("SRANDMEMBER s 20000000")).containsExactly("a", "b");
        expectError("SRANDMEMBER s -20000000", "ERR value is out of range");
    }

    @Test
    void randomKeyFindsTheLiveKeyAmongManyExpiredOnes() {
        expect("DEBUG SET-ACTIVE-EXPIRE 0", "+OK");
        for (int i = 0; i < 2_000; i++) {
            sync.send("SET", "tmp:" + i, "x", "PX", 100);
        }
        expect("SET live yes", "+OK");
        clock.advance(1_000);
        expect("RANDOMKEY", "live");
    }

    @Test
    void spopWithAManyMembersCountLeavesAConsistentSet() {
        Object[] sadd = new Object[2 + 3_000];
        sadd[0] = "SADD";
        sadd[1] = "big";
        for (int i = 0; i < 3_000; i++) {
            sadd[2 + i] = "m" + i;
        }
        sync.send(sadd);
        assertThat(sync.send("SPOP", "big", 2_500).asList()).hasSize(2_500);
        expect("SCARD big", ":500");
    }
}
