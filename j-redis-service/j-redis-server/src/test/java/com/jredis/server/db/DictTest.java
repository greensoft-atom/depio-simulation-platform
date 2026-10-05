package com.jredis.server.db;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DictTest {

    private final SipHash hasher = new SipHash(1, 2);

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private MemberEntry entry(Dict<MemberEntry> d, String key) {
        byte[] k = b(key);
        return new MemberEntry(k, d.hashOf(k));
    }

    @Test
    void randomOperationsMatchAHashSet() {
        Random rnd = new Random(42);
        Dict<MemberEntry> d = new Dict<>(hasher);
        Set<String> model = new HashSet<>();
        for (int i = 0; i < 200_000; i++) {
            String k = "k" + rnd.nextInt(5_000);
            int op = rnd.nextInt(10);
            if (op < 5) {
                if (d.get(b(k)) == null) {
                    d.add(entry(d, k));
                }
                model.add(k);
            } else if (op < 8) {
                MemberEntry removed = d.remove(b(k));
                assertThat(removed != null).isEqualTo(model.remove(k));
            } else {
                assertThat(d.get(b(k)) != null).isEqualTo(model.contains(k));
            }
            if (rnd.nextInt(100) == 0) {
                d.rehashFor(rnd.nextInt(2) == 0 ? 0 : 1_000_000);
            }
            assertThat(d.size()).isEqualTo(model.size());
        }
        for (String k : model) {
            assertThat(d.get(b(k))).as(k).isNotNull();
        }
        Set<String> seen = new HashSet<>();
        d.forEach(e -> assertThat(seen.add(new String(e.key, StandardCharsets.UTF_8))).isTrue());
        assertThat(seen).isEqualTo(model);
    }

    @Test
    void growsAndShrinks() {
        Dict<MemberEntry> d = new Dict<>(hasher);
        for (int i = 0; i < 100_000; i++) {
            d.add(entry(d, "k" + i));
        }
        d.rehashFor(Long.MAX_VALUE);
        assertThat(d.buckets()).isGreaterThanOrEqualTo(100_000);
        for (int i = 0; i < 99_990; i++) {
            assertThat(d.remove(b("k" + i))).isNotNull();
        }
        d.rehashFor(Long.MAX_VALUE);
        assertThat(d.size()).isEqualTo(10);
        assertThat(d.buckets()).isLessThan(1_000);
        for (int i = 99_990; i < 100_000; i++) {
            assertThat(d.get(b("k" + i))).isNotNull();
        }
    }

    /** The SCAN guarantee: every element present for the whole scan is returned at least once. */
    @Test
    void scanReturnsEveryStableKeyDespiteGrowthAndShrink() {
        for (int seed = 0; seed < 8; seed++) {
            Random rnd = new Random(seed);
            Dict<MemberEntry> d = new Dict<>(hasher);
            Set<String> stable = new HashSet<>();
            for (int i = 0; i < 2_000; i++) {
                d.add(entry(d, "stable" + i));
                stable.add("stable" + i);
            }
            List<String> churn = new ArrayList<>();
            Set<String> returned = new HashSet<>();
            long cursor = 0;
            int steps = 0;
            do {
                cursor = d.scan(cursor, e -> returned.add(new String(e.key, StandardCharsets.UTF_8)));
                steps++;
                // alternate between heavy growth and heavy shrinking while the scan runs
                boolean growing = (steps / 40) % 2 == 0;
                for (int j = 0; j < 100; j++) {
                    if (growing) {
                        String k = "churn" + rnd.nextInt(1 << 30);
                        if (d.get(b(k)) == null) {
                            d.add(entry(d, k));
                            churn.add(k);
                        }
                    } else if (!churn.isEmpty()) {
                        int idx = rnd.nextInt(churn.size());
                        String k = churn.get(idx);
                        churn.set(idx, churn.get(churn.size() - 1));
                        churn.remove(churn.size() - 1);
                        assertThat(d.remove(b(k))).isNotNull();
                    }
                }
                if (rnd.nextBoolean()) {
                    d.rehashFor(rnd.nextInt(3) == 0 ? 1_000_000 : 0);
                }
                assertThat(steps).as("scan must terminate").isLessThan(1_000_000);
            } while (cursor != 0);
            assertThat(returned).as("seed " + seed).containsAll(stable);
        }
    }

    @Test
    void scanOfEmptyDictIsComplete() {
        Dict<MemberEntry> d = new Dict<>(hasher);
        assertThat(d.scan(0, e -> { })).isZero();
    }
}
