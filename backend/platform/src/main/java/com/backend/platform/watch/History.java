package com.backend.platform.watch;

/**
 * The last hour of one series, as the watch read it: times and values in a ring, oldest dropped. What the
 * rules ask of a window: the value at a moment, the rise of a counter (its resets allowed for), how often a
 * gauge fell, and the slope of a straight line through the window's points.
 */
public final class History {

    /** An hour at 15 s, and one more, so a window of an hour has a sample at its start. */
    static final int CAPACITY = 241;

    private final long[] times = new long[CAPACITY];
    private final double[] values = new double[CAPACITY];
    private int start;
    private int size;

    public void add(long timeMillis, double value) {
        int at = (start + size) % CAPACITY;
        if (size == CAPACITY) {
            start = (start + 1) % CAPACITY;
        } else {
            size++;
        }
        times[at] = timeMillis;
        values[at] = value;
    }

    public int size() {
        return size;
    }

    public double latest() {
        return size == 0 ? Double.NaN : value(size - 1);
    }

    public long latestTime() {
        return size == 0 ? Long.MIN_VALUE : time(size - 1);
    }

    /** Whether the history reaches back to {@code timeMillis}: a window can be asked about only when it does. */
    public boolean covers(long timeMillis) {
        return size > 0 && time(0) <= timeMillis;
    }

    /** The value of the last sample at or before {@code timeMillis}; NaN when none is that old. */
    public double valueAt(long timeMillis) {
        for (int i = size - 1; i >= 0; i--) {
            if (time(i) <= timeMillis) {
                return value(i);
            }
        }
        return Double.NaN;
    }

    /**
     * How much a counter rose over the window ending now: the sum of its rises between consecutive samples
     * from the last one at or before the window's start, a fall between two (a restart) counting as the
     * later value, as a counter's reader reads it. A window the history does not yet reach back to is read
     * from the oldest sample, as Prometheus reads a range: a rise seen five minutes after a start alerts
     * then, not an hour later.
     */
    public double increase(long windowMillis, long nowMillis) {
        int base = Math.max(0, indexAtOrBefore(nowMillis - windowMillis));
        double sum = 0;
        for (int i = base + 1; i < size; i++) {
            double d = value(i) - value(i - 1);
            sum += d >= 0 ? d : value(i);
        }
        return sum;
    }

    /** How many times the value fell between consecutive samples within the window. */
    public int falls(long windowMillis, long nowMillis) {
        int base = Math.max(0, indexAtOrBefore(nowMillis - windowMillis));
        int falls = 0;
        for (int i = base + 1; i < size; i++) {
            if (value(i) < value(i - 1)) {
                falls++;
            }
        }
        return falls;
    }

    /**
     * The slope, a unit of value per millisecond, of the least-squares line through the window's samples;
     * NaN when the window is not covered or holds fewer than two samples.
     */
    public double slopePerMillis(long windowMillis, long nowMillis) {
        long from = nowMillis - windowMillis;
        if (!covers(from)) {
            return Double.NaN;
        }
        int first = Math.max(0, indexAtOrBefore(from));
        int n = size - first;
        if (n < 2) {
            return Double.NaN;
        }
        double meanT = 0;
        double meanV = 0;
        for (int i = first; i < size; i++) {
            meanT += (time(i) - from);
            meanV += value(i);
        }
        meanT /= n;
        meanV /= n;
        double num = 0;
        double den = 0;
        for (int i = first; i < size; i++) {
            double dt = (time(i) - from) - meanT;
            num += dt * (value(i) - meanV);
            den += dt * dt;
        }
        return den == 0 ? Double.NaN : num / den;
    }

    private int indexAtOrBefore(long timeMillis) {
        for (int i = size - 1; i >= 0; i--) {
            if (time(i) <= timeMillis) {
                return i;
            }
        }
        return -1;
    }

    private long time(int i) {
        return times[(start + i) % CAPACITY];
    }

    private double value(int i) {
        return values[(start + i) % CAPACITY];
    }
}
