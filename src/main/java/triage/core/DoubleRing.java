package triage.core;

import java.util.Arrays;

/** A fixed-capacity ring of primitive doubles with allocation-free order statistics on recent values. */
final class DoubleRing {
    private final double[] data;
    private final double[] scratch;
    private int head;
    private int size;

    DoubleRing(int capacity) {
        this.data = new double[capacity];
        this.scratch = new double[capacity];
    }

    void add(double value) {
        data[head] = value;
        head = (head + 1) % data.length;
        if (size < data.length) {
            size++;
        }
    }

    int size() {
        return size;
    }

    /** The value {@code back} samples ago; {@code back = 0} is the newest. */
    double get(int back) {
        if (back < 0 || back >= size) {
            throw new IndexOutOfBoundsException(back);
        }
        int index = Math.floorMod(head - 1 - back, data.length);
        return data[index];
    }

    /** Median of the {@code count} values ending {@code offset} samples ago. */
    double median(int count, int offset) {
        int n = Math.min(count, size - offset);
        if (n <= 0) {
            return Double.NaN;
        }
        for (int i = 0; i < n; i++) {
            scratch[i] = get(offset + i);
        }
        Arrays.sort(scratch, 0, n);
        return (n % 2 == 1) ? scratch[n / 2] : 0.5 * (scratch[n / 2 - 1] + scratch[n / 2]);
    }

    /** Least-squares slope per sample of the newest {@code count} values. */
    double slope(int count) {
        int n = Math.min(count, size);
        if (n < 3) {
            return 0.0;
        }
        double meanT = (n - 1) / 2.0;
        double meanY = 0.0;
        for (int i = 0; i < n; i++) {
            meanY += get(n - 1 - i);
        }
        meanY /= n;
        double num = 0.0;
        double den = 0.0;
        for (int i = 0; i < n; i++) {
            double dt = i - meanT;
            num += dt * (get(n - 1 - i) - meanY);
            den += dt * dt;
        }
        return num / den;
    }

    /** Copies the newest {@code count} values, oldest first, into {@code target}; returns the count copied. */
    int copyRecent(int count, double[] target) {
        int n = Math.min(count, size);
        for (int i = 0; i < n; i++) {
            target[i] = get(n - 1 - i);
        }
        return n;
    }

    /** True when {@code value} equals, exactly, one of the values from {@code fromBack} back to the oldest kept. */
    boolean containsExactly(double value, int fromBack) {
        for (int back = fromBack; back < size; back++) {
            if (Double.compare(get(back), value) == 0) {
                return true;
            }
        }
        return false;
    }

    /** Number of exact occurrences of {@code value} among the newest {@code count} values. */
    int countExactly(double value, int count) {
        int n = Math.min(count, size);
        int c = 0;
        for (int i = 0; i < n; i++) {
            if (Double.compare(get(i), value) == 0) {
                c++;
            }
        }
        return c;
    }

    void clear() {
        head = 0;
        size = 0;
    }
}
