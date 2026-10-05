package com.jredis.server.db;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class ListValueTest {

    @Test
    void matchesAnArrayListUnderRandomOperations() {
        Random rnd = new Random(5);
        ListValue l = new ListValue();
        List<String> model = new ArrayList<>();
        for (int i = 0; i < 100_000; i++) {
            String v = "v" + rnd.nextInt(50);
            switch (rnd.nextInt(9)) {
                case 0: l.addFirst(v.getBytes()); model.add(0, v); break;
                case 1: l.addLast(v.getBytes()); model.add(v); break;
                case 2: check(l.pollFirst(), model.isEmpty() ? null : model.remove(0)); break;
                case 3: check(l.pollLast(), model.isEmpty() ? null : model.remove(model.size() - 1)); break;
                case 4:
                    if (!model.isEmpty()) {
                        int idx = rnd.nextInt(model.size() + 1);
                        l.insert(idx, v.getBytes());
                        model.add(idx, v);
                    }
                    break;
                case 5:
                    if (!model.isEmpty()) {
                        int idx = rnd.nextInt(model.size());
                        check(l.removeAt(idx), model.remove(idx));
                    }
                    break;
                case 6: {
                    long count = rnd.nextInt(5) - 2;
                    int removed = l.removeMatching(v.getBytes(), count);
                    assertThat(removed).isEqualTo(removeMatching(model, v, count));
                    break;
                }
                case 7:
                    if (!model.isEmpty() && rnd.nextInt(20) == 0) {
                        int a = rnd.nextInt(model.size());
                        int b = a + rnd.nextInt(model.size() - a);
                        l.trim(a, b);
                        List<String> kept = new ArrayList<>(model.subList(a, b + 1));
                        model.clear();
                        model.addAll(kept);
                    }
                    break;
                default:
                    if (!model.isEmpty()) {
                        int idx = rnd.nextInt(model.size());
                        l.set(idx, v.getBytes());
                        model.set(idx, v);
                    }
            }
            assertThat(l.size()).isEqualTo(model.size());
            if (i % 997 == 0) {
                for (int j = 0; j < model.size(); j++) {
                    assertThat(new String(l.get(j))).isEqualTo(model.get(j));
                }
            }
        }
    }

    private static void check(byte[] actual, String expected) {
        assertThat(actual == null ? null : new String(actual)).isEqualTo(expected);
    }

    private static int removeMatching(List<String> model, String v, long count) {
        int removed = 0;
        long limit = count == 0 ? Long.MAX_VALUE : Math.abs(count);
        if (count >= 0) {
            for (int i = 0; i < model.size() && removed < limit; ) {
                if (model.get(i).equals(v)) {
                    model.remove(i);
                    removed++;
                } else {
                    i++;
                }
            }
        } else {
            for (int i = model.size() - 1; i >= 0 && removed < limit; i--) {
                if (model.get(i).equals(v)) {
                    model.remove(i);
                    removed++;
                }
            }
        }
        return removed;
    }

    /** Math.abs(Long.MIN_VALUE) is negative; Redis removes every match (from the tail). */
    @Test
    void lremWithTheMostNegativeCountRemovesAllMatches() {
        ListValue l = new ListValue();
        for (String v : new String[] {"x", "a", "x", "x", "b", "x"}) {
            l.addLast(v.getBytes());
        }
        assertThat(l.removeMatching("x".getBytes(), Long.MIN_VALUE)).isEqualTo(4);
        assertThat(l.size()).isEqualTo(2);
    }

    @Test
    void memoryEstimateReturnsToBaseWhenEmptied() {
        ListValue l = new ListValue();
        long base = l.bytes();
        for (int i = 0; i < 1_000; i++) {
            l.addLast(new byte[i % 64]);
        }
        assertThat(l.bytes()).isGreaterThan(base);
        while (l.size() > 0) {
            l.pollFirst();
        }
        assertThat(l.bytes()).isEqualTo(base);
    }
}
