package dev.skidfuscator.testclasses.claude;

import dev.skidfuscator.testclasses.TestRun;

/**
 * Exercises {@code MethodMergeTransformer} on (private) instance methods. The
 * host is non-static and invoked via INVOKEVIRTUAL, so {@code this} must be
 * threaded through the carrier and restored before each cloned body runs. The
 * bodies read an instance field to prove the receiver survives the merge.
 * Buckets: {plus,minus,times,squareDist}->int host, {label,tag}->String host.
 */
public class MergeInstanceClazz implements TestRun {

    private final int base = 100;

    @Override
    public void run() {
        check(plus(5) == 105, "plus");
        check(minus(5) == 95, "minus");
        check(times(3) == 300, "times");
        check(squareDist(3, 4) == 25, "squareDist");
        check("v=107".equals(label(7)), "label");
        check("v=108".equals(tag(8)), "tag");
    }

    private int plus(int x) {
        return base + x;
    }

    private int minus(int x) {
        return base - x;
    }

    private int times(int x) {
        return base * x;
    }

    private int squareDist(int a, int b) {
        return a * a + b * b;
    }

    private String label(int x) {
        return "v=" + (base + x);
    }

    private String tag(int x) {
        return "v=" + (base + x);
    }

    private void check(boolean cond, String label) {
        if (!cond) {
            throw new IllegalStateException("Merge instance failed: " + label);
        }
    }
}
