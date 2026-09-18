package dev.skidfuscator.testclasses.claude;

import dev.skidfuscator.testclasses.TestRun;

public class PrimitiveNegationClazz implements TestRun {
    private static float flip(float value) { return -value; }
    private static double flip(double value) { return -value; }
    private static long flip(long value) { return -value; }
    private static int flip(int value) { return -value; }

    @Override public void run() {
        for (float value : new float[]{5f, -5f, 0f, -0f, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.MIN_VALUE, Float.MAX_VALUE}) {
            int expected = Float.floatToRawIntBits(value) ^ Integer.MIN_VALUE;
            if (Float.floatToRawIntBits(flip(value)) != expected)
                throw new IllegalStateException("Float negation lost for " + value);
        }
        for (double value : new double[]{5d, -5d, 0d, -0d, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.MIN_VALUE, Double.MAX_VALUE}) {
            long expected = Double.doubleToRawLongBits(value) ^ Long.MIN_VALUE;
            if (Double.doubleToRawLongBits(flip(value)) != expected)
                throw new IllegalStateException("Double negation lost for " + value);
        }
        for (long value : new long[]{5L, -5L, 0L, Long.MIN_VALUE, Long.MAX_VALUE}) {
            if (flip(value) != ~value + 1L)
                throw new IllegalStateException("Long negation lost for " + value);
        }
        for (int value : new int[]{5, -5, 0, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            if (flip(value) != ~value + 1)
                throw new IllegalStateException("Int negation lost for " + value);
        }
    }
}
