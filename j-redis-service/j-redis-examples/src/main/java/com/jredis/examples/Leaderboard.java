package com.jredis.examples;

import com.jredis.client.JRedisClient;
import com.jredis.client.JRedisSync;
import com.jredis.client.ScoredMember;
import com.jredis.client.ZAround;
import com.jredis.common.Reply;

import java.io.PrintStream;
import java.util.List;

/**
 * Leaderboards with sorted sets: add points, keep a best score, read the top N, a user's rank, and
 * the rows around a user (J.ZAROUND) in one call. A daily board gets its TTL on first write.
 */
public final class Leaderboard implements Example {

    private static final String BOARD = "lb:weekly:points";
    private static final String BEST = "lb:weekly:best";

    @Override
    public String name() {
        return "leaderboard";
    }

    @Override
    public String summary() {
        return "sorted-set rankings: top N, my rank, rows around me";
    }

    @Override
    public void run(JRedisClient client, PrintStream out) {
        JRedisSync redis = client.sync();
        redis.del(BOARD, BEST, "lb:daily:20260921:points");

        // Points accumulate with ZINCRBY (atomic, creates the member when needed).
        String[] users = {"alice", "bob", "carol", "dave", "erin", "frank", "grace"};
        int[] points = {120, 340, 90, 340, 510, 75, 260};
        for (int i = 0; i < users.length; i++) {
            client.zincrby(BOARD, points[i], "user:" + users[i]);   // async: all pipelined together
        }
        redis.zincrby(BOARD, 30, "user:carol");                    // the sync call also waits for the ones before it

        // Top 3, highest first. Equal scores are ordered by member name (descending in REV order).
        List<ScoredMember> top = redis.zrevrangeWithScores(BOARD, 0, 2);
        out.println("top 3:");
        for (int i = 0; i < top.size(); i++) {
            out.printf("  #%d %-12s %.0f%n", i + 1, top.get(i).member, top.get(i).score);
        }

        // A user's position: ZREVRANK is 0-based; show it 1-based.
        Long rank = redis.zrevrank(BOARD, "user:carol");
        out.println("carol's position       -> #" + (rank + 1) + " with " + redis.zscore(BOARD, "user:carol") + " points");
        out.println("unknown user's rank    -> " + redis.zrevrank(BOARD, "user:nobody"));   // null

        // "Around me": the user plus 2 neighbours on each side, in one round trip.
        ZAround around = redis.zaround(BOARD, "user:carol", 2, true);
        out.println("around carol:");
        for (int i = 0; i < around.window.size(); i++) {
            ScoredMember m = around.window.get(i);
            out.printf("  #%d %-12s %.0f%s%n", around.firstRank + i + 1, m.member, m.score,
                    m.member.equals("user:carol") ? "   <- you" : "");
        }

        // Best score only: ZADD GT raises the score but never lowers it.
        redis.zadd(BEST, 500, "user:alice", "GT");
        redis.zadd(BEST, 450, "user:alice", "GT");                 // ignored: lower
        redis.zadd(BEST, 620, "user:alice", "GT");                 // raised
        out.println("alice's best score     -> " + redis.zscore(BEST, "user:alice"));

        // A daily board that cleans itself up: set the TTL only the first time (EXPIRE ... NX).
        List<Reply> r = redis.exec(client.multi()
                .send("ZINCRBY", "lb:daily:20260921:points", 15, "user:bob")
                .send("EXPIRE", "lb:daily:20260921:points", 3 * 24 * 3600, "NX"));
        out.println("daily board            -> bob has " + r.get(0).asString() + ", TTL "
                + redis.ttl("lb:daily:20260921:points") + " s");
        out.println("members on the board   -> " + redis.zcard(BOARD));
    }
}
