package dev.skidfuscator.testclasses.claude;

import dev.skidfuscator.testclasses.TestRun;

public class OutlinerRuntimeClazz implements TestRun {
    @Override
    public void run() {
        if (compute(9, 5) != 125) {
            throw new AssertionError();
        }
        if (wide(11L, 2) != 28L) {
            throw new AssertionError();
        }
        if (stackValue(6) != 69) {
            throw new AssertionError();
        }
        if (exceptional(2) != 8) {
            throw new AssertionError();
        }
        if (exceptional(0) != -7) {
            throw new AssertionError();
        }
    }

    private int compute(final int left, final int right) {
        int x = left + right;
        int y = x * 7;
        int z = y - left;
        int q = z ^ right;
        return q + 33;
    }

    private long wide(final long seed, final int delta) {
        long x = seed + delta;
        long y = x * 3L;
        return y - seed;
    }

    private int stackValue(final int value) {
        return square(value + 2) + 5;
    }

    private int square(final int value) {
        return value * value;
    }

    private int exceptional(final int divisor) {
        try {
            int divided = 30 / divisor;
            int adjusted = divided - 7;
            return adjusted;
        } catch (ArithmeticException ex) {
            return -7;
        }
    }
}
