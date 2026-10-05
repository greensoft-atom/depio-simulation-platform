package com.jredis.tests;

import com.jredis.client.ScoredMember;
import com.jredis.client.ZAround;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionCommandsTest extends EmbeddedTest {

    @Test
    void hashes() {
        expect("HSET h a 1 b 2", ":2");
        expect("HSET h a 10 c 3", ":1");
        expect("HGET h a", "10");
        expect("HMGET h a missing c", "[10, (nil), 3]");
        expect("HLEN h", ":3");
        expect("HEXISTS h b", ":1");
        expect("HSETNX h b 99", ":0");
        expect("HINCRBY h b 5", ":7");
        expect("HINCRBYFLOAT h b 0.5", "7.5");
        expectError("HINCRBY h b 1", "ERR hash value is not an integer");
        expect("HDEL h a b missing", ":2");
        expect("HGETALL h", "[c, 3]");
        expect("HSTRLEN h c", ":1");
        expect("HDEL h c", ":1");
        expect("EXISTS h", ":0");                   // an emptied hash is removed
        expect("HGETALL nothing", "[]");
    }

    @Test
    void lists() {
        expect("RPUSH l a b c", ":3");
        expect("LPUSH l z", ":4");
        expect("LRANGE l 0 -1", "[z, a, b, c]");
        expect("LRANGE l -2 100", "[b, c]");
        expect("LRANGE l 5 10", "[]");
        expect("LINDEX l -1", "c");
        expect("LINDEX l 10", "(nil)");
        expect("LSET l 1 A", "+OK");
        expectError("LSET l 10 x", "ERR index out of range");
        expect("LINSERT l BEFORE b x", ":5");
        expect("LINSERT l AFTER nothing y", ":-1");
        expect("LRANGE l 0 -1", "[z, A, x, b, c]");
        expect("RPUSH l x x", ":7");
        expect("LREM l -1 x", ":1");
        expect("LRANGE l 0 -1", "[z, A, x, b, c, x]");
        expect("LREM l 0 x", ":2");
        expect("LPOS l c", ":3");
        expect("LTRIM l 1 -2", "+OK");
        expect("LRANGE l 0 -1", "[A, b]");
        expect("LPOP l 5", "[A, b]");
        expect("EXISTS l", ":0");
        expect("LPOP l", "(nil)");
        expect("RPUSH src 1 2 3", ":3");
        expect("LMOVE src dst RIGHT LEFT", "3");
        expect("RPOPLPUSH src dst", "2");
        expect("LRANGE dst 0 -1", "[2, 3]");
        expect("LPUSHX nothing a", ":0");
        expect("LLEN dst", ":2");
    }

    @Test
    void sets() {
        expect("SADD a 1 2 3 4", ":4");
        expect("SADD a 4", ":0");
        expect("SADD b 3 4 5", ":3");
        assertThat(members("SINTER a b")).containsExactly("3", "4");
        assertThat(members("SUNION a b")).containsExactly("1", "2", "3", "4", "5");
        expect("SDIFFSTORE d a b", ":2");
        expect("SCARD d", ":2");
        expect("SISMEMBER d 1", ":1");
        expect("SMISMEMBER d 1 5", "[:1, :0]");
        expect("SMOVE a b 1", ":1");
        expect("SISMEMBER b 1", ":1");
        expect("SREM a 2 3 4", ":3");
        expect("EXISTS a", ":0");
        expect("SINTERCARD 2 b d", ":1");
        expect("SPOP nothing", "(nil)");
        assertThat(sync.send("SRANDMEMBER", "b", -10).asList()).hasSize(10);
    }

    @Test
    void sortedSets() {
        expect("ZADD z 1 a 2 b 3 c", ":3");
        expect("ZADD z NX 10 a 4 d", ":1");
        expect("ZADD z XX CH 5 a 9 e", ":1");
        expect("ZADD z GT 1 a", ":0");
        expect("ZSCORE z a", "5");
        expect("ZADD z LT CH 0.5 a", ":1");
        expect("ZADD z INCR 2 a", "2.5");
        expectError("ZADD z NX XX 1 a", "ERR XX and NX options at the same time are not compatible");
        expectError("ZADD z 1 a 2", "ERR syntax error");
        expectError("ZADD z 1", "ERR wrong number of arguments");
        expectError("ZADD z nan a", "ERR value is not a valid float");
        expect("ZRANGE z 0 -1 WITHSCORES", "[b, 2, a, 2.5, c, 3, d, 4]");
        expect("ZREVRANGE z 0 1", "[d, c]");
        expect("ZRANGE z (2 3 BYSCORE", "[a, c]");
        expect("ZRANGE z +inf -inf BYSCORE REV LIMIT 1 2", "[c, a]");
        expect("ZRANGEBYSCORE z -inf +inf LIMIT 0 2", "[b, a]");
        expect("ZCOUNT z (2 +inf", ":3");
        expect("ZRANK z c", ":2");
        expect("ZREVRANK z c", ":1");
        expect("ZRANK z nobody", "(nil)");
        expect("ZINCRBY z 10 b", "12");
        expect("ZPOPMAX z", "[b, 12]");
        expect("ZPOPMIN z 2", "[a, 2.5, c, 3]");
        expect("ZADD z -inf lo +inf hi", ":2");
        expect("ZRANGE z 0 -1 WITHSCORES", "[lo, -inf, d, 4, hi, inf]");
        expect("ZREMRANGEBYSCORE z -inf (4", ":1");
        expect("ZREMRANGEBYRANK z -1 -1", ":1");
        expect("ZCARD z", ":1");
        expect("ZREM z d", ":1");
        expect("EXISTS z", ":0");
        expect("ZADD tie 1 b 1 a 1 c", ":3");
        expect("ZRANGE tie 0 -1", "[a, b, c]");      // equal scores order by member bytes
        expect("ZMSCORE tie a nobody c", "[1, (nil), 1]");
        expect("ZREVRANGEBYSCORE tie +inf -inf WITHSCORES LIMIT 0 2", "[c, 1, b, 1]");
        expect("ZRANGE tie 0 -1 REV", "[c, b, a]");
    }

    @Test
    void zaroundGivesAWindowAroundAMember() {
        for (int i = 0; i < 20; i++) {
            sync.zadd("lb", i * 10, "p" + i);
        }
        expect("J.ZAROUND lb p0 2", "[:0, [p0, p1, p2]]");
        expect("J.ZAROUND lb p10 1 WITHSCORES", "[:10, [p9, 90, p10, 100, p11, 110]]");
        expect("J.ZAROUND lb p19 1 REV", "[:0, [p19, p18]]");
        expect("J.ZAROUND lb p5 2 REV", "[:14, [p7, p6, p5, p4, p3]]");
        expect("J.ZAROUND lb nobody 3", "(nil)");
        expect("J.ZAROUND nothing p1 3", "(nil)");
        expectError("J.ZAROUND lb p1 1001", "ERR count must be between 0 and 1000");
        ZAround z = sync.zaround("lb", "p10", 2, true);
        assertThat(z.rank).isEqualTo(9);
        assertThat(z.firstRank).isEqualTo(7);
        assertThat(z.window).extracting(m -> m.member).containsExactly("p12", "p11", "p10", "p9", "p8");
    }

    /** Leaderboard-sized sorted set: ranks stay exact through many random score updates. */
    @Test
    void largeLeaderboardRanksAreExact() {
        Random rnd = new Random(1);
        Map<String, Double> scores = new HashMap<>();
        int users = 120_000;
        for (int batch = 0; batch < users; batch += 1_000) {
            Object[] args = new Object[2 + 2 * 1_000];
            args[0] = "ZADD";
            args[1] = "board";
            for (int i = 0; i < 1_000; i++) {
                String p = "user:" + (batch + i);
                double s = rnd.nextInt(1_000_000);
                scores.put(p, s);
                args[2 + 2 * i] = s;
                args[3 + 2 * i] = p;
            }
            sync.send(args);
        }
        for (int i = 0; i < 20_000; i++) {
            String p = "user:" + rnd.nextInt(users);
            double inc = rnd.nextInt(1_000);
            scores.merge(p, inc, Double::sum);
            client.zincrby("board", inc, p);
        }
        assertThat(sync.zcard("board")).isEqualTo(users);
        List<Map.Entry<String, Double>> sorted = new java.util.ArrayList<>(scores.entrySet());
        sorted.sort((a, b) -> {
            int c = Double.compare(b.getValue(), a.getValue());
            return c != 0 ? c : b.getKey().compareTo(a.getKey());   // REV order: members descending on ties
        });
        List<ScoredMember> top = sync.zrevrangeWithScores("board", 0, 99);
        for (int i = 0; i < 100; i++) {
            assertThat(top.get(i).member).isEqualTo(sorted.get(i).getKey());
            assertThat(top.get(i).score).isEqualTo(sorted.get(i).getValue());
        }
        for (int i = 0; i < 200; i++) {
            int idx = rnd.nextInt(users);
            assertThat(sync.zrevrank("board", sorted.get(idx).getKey())).isEqualTo((long) idx);
        }
    }
}
