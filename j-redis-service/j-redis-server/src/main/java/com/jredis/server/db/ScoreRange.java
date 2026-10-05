package com.jredis.server.db;

import com.jredis.common.NumberCodec;

/** A score interval as used by ZRANGEBYSCORE / ZCOUNT: "(1.5" is exclusive, "-inf"/"+inf" allowed. */
public final class ScoreRange {

    public final double min;
    public final double max;
    public final boolean minExclusive;
    public final boolean maxExclusive;

    public ScoreRange(double min, boolean minExclusive, double max, boolean maxExclusive) {
        this.min = min;
        this.minExclusive = minExclusive;
        this.max = max;
        this.maxExclusive = maxExclusive;
    }

    /**
     * @throws NumberFormatException if either bound is not a valid float
     */
    public static ScoreRange parse(byte[] min, byte[] max) {
        boolean minEx = min.length > 0 && min[0] == '(';
        boolean maxEx = max.length > 0 && max[0] == '(';
        double lo = NumberCodec.parseDouble(minEx ? java.util.Arrays.copyOfRange(min, 1, min.length) : min);
        double hi = NumberCodec.parseDouble(maxEx ? java.util.Arrays.copyOfRange(max, 1, max.length) : max);
        return new ScoreRange(lo, minEx, hi, maxEx);
    }

    public boolean gteMin(double v) {
        return minExclusive ? v > min : v >= min;
    }

    public boolean lteMax(double v) {
        return maxExclusive ? v < max : v <= max;
    }

    public boolean isEmpty() {
        return min > max || (min == max && (minExclusive || maxExclusive));
    }
}
