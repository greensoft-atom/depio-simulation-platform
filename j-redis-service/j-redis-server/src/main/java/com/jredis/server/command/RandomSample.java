package com.jredis.server.command;

import com.jredis.server.core.ByteKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The count semantics of SRANDMEMBER / HRANDFIELD / ZRANDMEMBER: a positive count returns up to
 * that many distinct elements, a negative count returns exactly |count| elements with repeats.
 */
final class RandomSample {

    static final long MAX_COUNT = 10_000_000L;

    private RandomSample() {
    }

    static <T> List<T> pick(int size, long count, Random rnd, Function<Random, T> randomOne,
                            Supplier<List<T>> all, Function<T, byte[]> identity) {
        if (count < -MAX_COUNT) {                   // only repeats can be unbounded; a big positive count means "all"
            throw new CommandException("ERR value is out of range");
        }
        List<T> out = new ArrayList<>();
        if (count < 0) {
            for (long i = 0; i < -count; i++) {
                out.add(randomOne.apply(rnd));
            }
            return out;
        }
        if (count >= size) {
            return all.get();
        }
        if (count * 3 > size) {
            List<T> everything = all.get();
            Collections.shuffle(everything, rnd);
            return new ArrayList<>(everything.subList(0, (int) count));
        }
        Set<ByteKey> seen = new HashSet<>();
        while (out.size() < count) {
            T t = randomOne.apply(rnd);
            if (seen.add(new ByteKey(identity.apply(t)))) {
                out.add(t);
            }
        }
        return out;
    }
}
