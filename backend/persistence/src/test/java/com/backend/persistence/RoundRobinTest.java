package com.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A round robin's schedule by the circle method (04 §6, plan item 66): every pair once. */
class RoundRobinTest {

    /** Every pair of seeds in the schedule, as "low-high", each seed's rounds checked as it goes. */
    private static Set<String> pairs(List<List<int[]>> rounds, int n) {
        Set<String> seen = new HashSet<>();
        for (List<int[]> round : rounds) {
            Set<Integer> playing = new HashSet<>();
            for (int[] pair : round) {
                assertThat(pair[0]).isBetween(1, n);
                assertThat(pair[1]).isBetween(1, n);
                assertThat(playing.add(pair[0]) && playing.add(pair[1])).as("once a round").isTrue();
                assertThat(seen.add(Math.min(pair[0], pair[1]) + "-" + Math.max(pair[0], pair[1]))).as("once a pair").isTrue();
            }
        }
        return seen;
    }

    @Test
    @DisplayName("an even field: n - 1 rounds of n / 2 matches, every pair once")
    void evenField() {
        List<List<int[]>> rounds = RoundRobin.schedule(4);
        assertThat(rounds).hasSize(3).allSatisfy(r -> assertThat(r).hasSize(2));
        assertThat(pairs(rounds, 4)).hasSize(6);
        List<List<int[]>> eight = RoundRobin.schedule(8);
        assertThat(eight).hasSize(7).allSatisfy(r -> assertThat(r).hasSize(4));
        assertThat(pairs(eight, 8)).hasSize(28);
        assertThat(RoundRobin.schedule(2)).hasSize(1);
    }

    @Test
    @DisplayName("an odd field: n rounds, each seed sitting one of them out, every pair once")
    void oddField() {
        List<List<int[]>> rounds = RoundRobin.schedule(3);
        assertThat(rounds).hasSize(3).allSatisfy(r -> assertThat(r).hasSize(1));
        assertThat(pairs(rounds, 3)).hasSize(3);
        List<List<int[]>> five = RoundRobin.schedule(5);
        assertThat(five).hasSize(5).allSatisfy(r -> assertThat(r).hasSize(2));
        assertThat(pairs(five, 5)).hasSize(10);
        for (int seed = 1; seed <= 5; seed++) {
            int sat = 0;
            for (List<int[]> r : five) {
                int s = seed;
                sat += r.stream().noneMatch(p -> p[0] == s || p[1] == s) ? 1 : 0;
            }
            assertThat(sat).as("seed " + seed + " sits out once").isEqualTo(1);
        }
    }
}
