package com.jredis.server.db;

import com.jredis.common.Bytes;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class SkipListTest {

    private static final class Item {
        final double score;
        final byte[] member;

        Item(double score, byte[] member) {
            this.score = score;
            this.member = member;
        }
    }

    private static final Comparator<Item> ORDER = (a, b) -> {
        int c = Double.compare(a.score, b.score);
        return c != 0 ? c : Bytes.compare(a.member, b.member);
    };

    @Test
    void matchesASortedListUnderRandomOperations() {
        Random rnd = new Random(7);
        SkipList list = new SkipList();
        Map<String, Double> scores = new HashMap<>();
        for (int i = 0; i < 50_000; i++) {
            String m = "m" + rnd.nextInt(2_000);
            byte[] mb = m.getBytes(StandardCharsets.UTF_8);
            double score = rnd.nextInt(10) == 0 ? rnd.nextInt(3) : rnd.nextInt(500) / 4.0;   // many ties
            Double old = scores.get(m);
            int op = rnd.nextInt(10);
            if (old == null && op < 6) {
                list.insert(score, mb);
                scores.put(m, score);
            } else if (old != null && op < 8) {
                list.updateScore(old, mb, score);
                scores.put(m, score);
            } else if (old != null) {
                assertThat(list.delete(old, mb)).isTrue();
                scores.remove(m);
            }
            if (i % 5_000 == 0) {
                verify(list, scores);
            }
        }
        verify(list, scores);
    }

    private static void verify(SkipList list, Map<String, Double> scores) {
        assertThat(list.checkInvariants()).isNull();
        List<Item> sorted = new ArrayList<>();
        scores.forEach((m, s) -> sorted.add(new Item(s, m.getBytes(StandardCharsets.UTF_8))));
        sorted.sort(ORDER);
        assertThat(list.length()).isEqualTo(sorted.size());
        int rank = 1;
        SkipList.Node n = list.first();
        for (Item it : sorted) {
            assertThat(n).isNotNull();
            assertThat(n.member()).isEqualTo(it.member);
            assertThat(n.score()).isEqualTo(it.score);
            assertThat(list.rank(it.score, it.member)).isEqualTo(rank);
            assertThat(list.byRank(rank)).isSameAs(n);
            n = n.next();
            rank++;
        }
        assertThat(n).isNull();
        assertThat(list.byRank(0)).isNull();
        assertThat(list.byRank(sorted.size() + 1)).isNull();
    }

    @Test
    void rangesMatchALinearScan() {
        Random rnd = new Random(11);
        SkipList list = new SkipList();
        List<Item> sorted = new ArrayList<>();
        for (int i = 0; i < 3_000; i++) {
            Item it = new Item(rnd.nextInt(1_000), ("m" + i).getBytes(StandardCharsets.UTF_8));
            list.insert(it.score, it.member);
            sorted.add(it);
        }
        sorted.sort(ORDER);
        for (int q = 0; q < 2_000; q++) {
            double a = rnd.nextInt(1_100) - 50;
            double b = a + rnd.nextInt(200);
            ScoreRange r = new ScoreRange(a, rnd.nextBoolean(), b, rnd.nextBoolean());
            Item first = null;
            Item last = null;
            for (Item it : sorted) {
                if (r.gteMin(it.score) && r.lteMax(it.score)) {
                    if (first == null) {
                        first = it;
                    }
                    last = it;
                }
            }
            SkipList.Node f = list.firstInRange(r);
            SkipList.Node l = list.lastInRange(r);
            if (first == null) {
                assertThat(f).isNull();
                assertThat(l).isNull();
            } else {
                assertThat(f.member()).isEqualTo(first.member);
                assertThat(l.member()).isEqualTo(last.member);
            }
        }
    }

    @Test
    void deleteRangesKeepInvariants() {
        SkipList list = new SkipList();
        for (int i = 0; i < 1_000; i++) {
            list.insert(i, ("m" + i).getBytes(StandardCharsets.UTF_8));
        }
        List<SkipList.Node> removed = new ArrayList<>();
        assertThat(list.deleteRangeByScore(new ScoreRange(100, false, 199, false), removed::add)).isEqualTo(100);
        assertThat(removed).hasSize(100);
        assertThat(list.checkInvariants()).isNull();
        assertThat(list.deleteRangeByRank(1, 10, n -> { })).isEqualTo(10);
        assertThat(list.first().score()).isEqualTo(10.0);
        assertThat(list.checkInvariants()).isNull();
        assertThat(list.length()).isEqualTo(890);
    }

    @Test
    void handlesAHundredThousandMemberLeaderboard() {
        SkipList list = new SkipList();
        Random rnd = new Random(3);
        for (int i = 0; i < 100_000; i++) {
            list.insert(rnd.nextInt(1_000_000), ("user:" + i).getBytes(StandardCharsets.UTF_8));
        }
        assertThat(list.checkInvariants()).isNull();
        long prevRank = 0;
        for (SkipList.Node n = list.first(); n != null; n = n.next()) {
            long r = list.rank(n.score(), n.member());
            assertThat(r).isEqualTo(prevRank + 1);
            prevRank = r;
        }
    }
}
