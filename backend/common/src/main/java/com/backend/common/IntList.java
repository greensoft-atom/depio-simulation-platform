package com.backend.common;

/**
 * Growable int array with no boxing and no iterator allocation.
 * Deliberately exposes its backing array: the tick loop indexes it directly
 * (see docs detailed-design/07 §2).
 */
public final class IntList {

    public int[] items;
    public int size;

    public IntList(int capacity) {
        items = new int[Math.max(1, capacity)];
    }

    public void add(int v) {
        if (size == items.length) {
            int[] bigger = new int[items.length << 1];
            System.arraycopy(items, 0, bigger, 0, size);
            items = bigger;
        }
        items[size++] = v;
    }

    public int get(int i) {
        return items[i];
    }

    /** O(1) removal that does not preserve order — the usual case in a tick loop. */
    public void removeAtSwap(int i) {
        items[i] = items[--size];
    }

    public void clear() {
        size = 0;
    }

    public boolean isEmpty() {
        return size == 0;
    }
}
