package com.jredis.server.db;

import java.util.Arrays;

/**
 * A list as a growable ring buffer: O(1) push/pop at both ends and O(1) indexed access; inserting
 * or removing in the middle shifts elements (O(n)).
 */
public final class ListValue extends TrackedValue {

    private static final int MIN_CAPACITY = 4;

    private byte[][] buf = new byte[MIN_CAPACITY][];
    private int head;
    private int size;

    public ListValue() {
        super(MemoryEstimator.CONTAINER);
    }

    @Override
    public int size() {
        return size;
    }

    private static long cost(byte[] v) {
        return MemoryEstimator.byteArray(v.length) + 2L * MemoryEstimator.REF;
    }

    public byte[] get(int index) {
        return buf[(head + index) & (buf.length - 1)];
    }

    public void set(int index, byte[] value) {
        int slot = (head + index) & (buf.length - 1);
        adjust(cost(value) - cost(buf[slot]));
        buf[slot] = value;
    }

    public void addFirst(byte[] v) {
        grow();
        head = (head - 1) & (buf.length - 1);
        buf[head] = v;
        size++;
        adjust(cost(v));
    }

    public void addLast(byte[] v) {
        grow();
        buf[(head + size) & (buf.length - 1)] = v;
        size++;
        adjust(cost(v));
    }

    public byte[] pollFirst() {
        if (size == 0) {
            return null;
        }
        byte[] v = buf[head];
        buf[head] = null;
        head = (head + 1) & (buf.length - 1);
        size--;
        adjust(-cost(v));
        shrink();
        return v;
    }

    public byte[] pollLast() {
        if (size == 0) {
            return null;
        }
        int slot = (head + size - 1) & (buf.length - 1);
        byte[] v = buf[slot];
        buf[slot] = null;
        size--;
        adjust(-cost(v));
        shrink();
        return v;
    }

    /** Inserts before position {@code index} (0..size). */
    public void insert(int index, byte[] v) {
        if (index == 0) {
            addFirst(v);
            return;
        }
        if (index == size) {
            addLast(v);
            return;
        }
        byte[][] copy = toArray();
        byte[][] next = new byte[capacityFor(size + 1)][];
        System.arraycopy(copy, 0, next, 0, index);
        next[index] = v;
        System.arraycopy(copy, index, next, index + 1, size - index);
        buf = next;
        head = 0;
        size++;
        adjust(cost(v));
    }

    public byte[] removeAt(int index) {
        if (index == 0) {
            return pollFirst();
        }
        if (index == size - 1) {
            return pollLast();
        }
        byte[][] copy = toArray();
        byte[] removed = copy[index];
        System.arraycopy(copy, index + 1, copy, index, size - index - 1);
        size--;
        replaceWith(Arrays.copyOf(copy, size));
        adjust(-cost(removed));
        return removed;
    }

    /** Keeps elements [start, stop] (already normalised, inclusive); removes the rest. */
    public void trim(int start, int stop) {
        if (start > stop || start >= size) {
            clearAll();
            return;
        }
        byte[][] copy = toArray();
        long removed = 0;
        for (int i = 0; i < size; i++) {
            if (i < start || i > stop) {
                removed += cost(copy[i]);
            }
        }
        replaceWith(Arrays.copyOfRange(copy, start, stop + 1));
        adjust(-removed);
    }

    /**
     * LREM semantics: count &gt; 0 removes from the head, &lt; 0 from the tail, 0 removes all.
     *
     * @return number removed
     */
    public int removeMatching(byte[] value, long count) {
        byte[][] copy = toArray();
        boolean[] drop = new boolean[size];
        long limit = count == 0 || count == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(count);   // abs(MIN_VALUE) < 0
        int removed = 0;
        if (count >= 0) {
            for (int i = 0; i < size && removed < limit; i++) {
                if (Arrays.equals(copy[i], value)) {
                    drop[i] = true;
                    removed++;
                }
            }
        } else {
            for (int i = size - 1; i >= 0 && removed < limit; i--) {
                if (Arrays.equals(copy[i], value)) {
                    drop[i] = true;
                    removed++;
                }
            }
        }
        if (removed == 0) {
            return 0;
        }
        byte[][] kept = new byte[size - removed][];
        long freed = 0;
        int k = 0;
        for (int i = 0; i < size; i++) {
            if (drop[i]) {
                freed += cost(copy[i]);
            } else {
                kept[k++] = copy[i];
            }
        }
        replaceWith(kept);
        adjust(-freed);
        return removed;
    }

    /** Index of the first element equal to {@code value} scanning from {@code from}, or -1. */
    public int indexOf(byte[] value, int from) {
        for (int i = from; i < size; i++) {
            if (Arrays.equals(get(i), value)) {
                return i;
            }
        }
        return -1;
    }

    public byte[][] toArray() {
        byte[][] out = new byte[size][];
        for (int i = 0; i < size; i++) {
            out[i] = get(i);
        }
        return out;
    }

    private void clearAll() {
        long freed = 0;
        for (int i = 0; i < size; i++) {
            freed += cost(get(i));
        }
        buf = new byte[MIN_CAPACITY][];
        head = 0;
        size = 0;
        adjust(-freed);
    }

    private void replaceWith(byte[][] elements) {
        byte[][] next = new byte[capacityFor(elements.length)][];
        System.arraycopy(elements, 0, next, 0, elements.length);
        buf = next;
        head = 0;
        size = elements.length;
    }

    private void grow() {
        if (size < buf.length) {
            return;
        }
        byte[][] next = new byte[buf.length * 2][];
        for (int i = 0; i < size; i++) {
            next[i] = get(i);
        }
        buf = next;
        head = 0;
    }

    /** Gives memory back after a queue drains: halve when a quarter full. */
    private void shrink() {
        if (buf.length > 16 && size < buf.length / 4) {
            byte[][] next = new byte[buf.length / 2][];
            for (int i = 0; i < size; i++) {
                next[i] = get(i);
            }
            buf = next;
            head = 0;
        }
    }

    private static int capacityFor(int n) {
        int c = MIN_CAPACITY;
        while (c < n) {
            c <<= 1;
        }
        return c;
    }

    /** A detached deep copy. */
    public ListValue copy() {
        ListValue c = new ListValue();
        for (int i = 0; i < size; i++) {
            c.addLast(get(i));
        }
        return c;
    }
}
