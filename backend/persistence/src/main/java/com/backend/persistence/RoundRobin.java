package com.backend.persistence;

import java.util.ArrayList;
import java.util.List;

/**
 * A round robin's schedule (04 §6, plan item 66), by the circle method: seed 1 stays put and the
 * others turn one place a round, so every pair meets once. An odd field adds a "nobody", 0, whose
 * opponent sits that round out: a bye is no match, so it is left out.
 */
final class RoundRobin {

    /** @return each round's pairs of seeds, 1 to {@code entries}, byes left out */
    static List<List<int[]>> schedule(int entries) {
        int places = entries % 2 == 0 ? entries : entries + 1;
        int[] circle = new int[places];
        for (int i = 0; i < places; i++) {
            circle[i] = i < entries ? i + 1 : 0;
        }
        List<List<int[]>> rounds = new ArrayList<>();
        for (int round = 0; round < places - 1; round++) {
            List<int[]> pairs = new ArrayList<>();
            for (int i = 0; i < places / 2; i++) {
                int a = circle[i];
                int b = circle[places - 1 - i];
                if (a != 0 && b != 0) {
                    pairs.add(new int[] {a, b});
                }
            }
            rounds.add(pairs);
            int last = circle[places - 1];                 // turn: all but the first, one place on
            System.arraycopy(circle, 1, circle, 2, places - 2);
            circle[1] = last;
        }
        return rounds;
    }

    private RoundRobin() {
    }
}
