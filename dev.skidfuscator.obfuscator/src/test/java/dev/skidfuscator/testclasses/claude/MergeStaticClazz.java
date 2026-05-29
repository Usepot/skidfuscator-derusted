package dev.skidfuscator.testclasses.claude;

import dev.skidfuscator.testclasses.TestRun;

/**
 * Exercises {@code MethodMergeTransformer} on static methods. Each pair of
 * same-return-type statics is interprocedurally seed-threaded and then folded
 * into one seed-dispatched host: {addI,mulI}->int host, {addL,subL}->long host,
 * {scaleD,offsetD}->double host, {concat,repeat}->String host, {andB,orB}->
 * boolean host. The arguments span every packing path (int/long/double/boolean
 * into the byte[] carrier, String into the Object[] carrier) and the bodies
 * include object allocation (StringBuilder) cloned into the host.
 */
public class MergeStaticClazz implements TestRun {

    @Override
    public void run() {
        check(addI(2, 3) == 5, "addI");
        check(mulI(2, 3) == 6, "mulI");

        check(addL(2L, 3L) == 5L, "addL");
        check(subL(7L, 3L) == 4L, "subL");

        check(scaleD(2.0d, 1.5d) == 3.0d, "scaleD");
        check(offsetD(2.0d, 1.5d) == 3.5d, "offsetD");

        check("ab".equals(concat("a", "b")), "concat");
        check("xxx".equals(repeat("x", 3)), "repeat");

        check(andB(true, true), "andB");
        check(!orB(false, false), "orB");
    }

    private static int addI(int a, int b) {
        return a + b;
    }

    private static int mulI(int a, int b) {
        return a * b;
    }

    private static long addL(long a, long b) {
        return a + b;
    }

    private static long subL(long a, long b) {
        return a - b;
    }

    private static double scaleD(double a, double b) {
        return a * b;
    }

    private static double offsetD(double a, double b) {
        return a + b;
    }

    private static String concat(String a, String b) {
        return a + b;
    }

    private static String repeat(String s, int n) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    private static boolean andB(boolean a, boolean b) {
        return a && b;
    }

    private static boolean orB(boolean a, boolean b) {
        return a || b;
    }

    private static void check(boolean cond, String label) {
        if (!cond) {
            throw new IllegalStateException("Merge static failed: " + label);
        }
    }
}
