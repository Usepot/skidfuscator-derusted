package dev.skidfuscator.testclasses.claude;

import dev.skidfuscator.testclasses.TestRun;

/**
 * Exercises {@code MethodDispatchTransformer}'s {@code (byte[], Object[])}
 * pack/unpack across every primitive width plus references, arrays and a
 * mixed-signature method. Every value is round-tripped through a funnelled call
 * and compared against the known-good result; a divergence (or a VerifyError in
 * the synthesised dispatcher) throws and fails the test.
 */
public class DispatchSignaturesClazz implements TestRun {

    private static int sinkValue;

    @Override
    public void run() {
        check(idBool(true), "idBool");
        check(idByte((byte) -7) == (byte) -7, "idByte");
        check(idChar('Z') == 'Z', "idChar");
        check(idShort((short) -1234) == (short) -1234, "idShort");
        check(idInt(0x7fffffff) == 0x7fffffff, "idInt");
        check(idLong(-9_000_000_000L) == -9_000_000_000L, "idLong");
        check(idFloat(3.5f) == 3.5f, "idFloat");
        check(idDouble(-2.71828d) == -2.71828d, "idDouble");
        check("abc".equals(idStr("abc")), "idStr");

        final int[] arr = {1, 2, 3};
        check(idArr(arr) == arr, "idArr");
        check(idObj(arr) == arr, "idObj");

        // boolean=1, byte=2, char='A'=65, short=100, int=7, long=8, float 1.5->1,
        // double 2.5->2, "k".length()=1  =>  1+2+65+100+7+8+1+2+1 = 187
        check(mix(true, (byte) 2, 'A', (short) 100, 7, 8L, 1.5f, 2.5d, "k") == 187L, "mix");

        check(sum(1, 2, 3, 4, 5) == 15, "sum");

        voidSink(42);
        check(sinkValue == 42, "voidSink");
    }

    static boolean idBool(boolean v) { return v; }
    static byte idByte(byte v) { return v; }
    static char idChar(char v) { return v; }
    static short idShort(short v) { return v; }
    static int idInt(int v) { return v; }
    static long idLong(long v) { return v; }
    static float idFloat(float v) { return v; }
    static double idDouble(double v) { return v; }
    static String idStr(String v) { return v; }
    static int[] idArr(int[] v) { return v; }
    static Object idObj(Object v) { return v; }

    static long mix(boolean a, byte b, char c, short d, int e, long f, float g, double h, String s) {
        return (a ? 1 : 0) + b + c + d + e + f + (long) g + (long) h + (long) s.length();
    }

    static int sum(int a, int b, int c, int d, int e) {
        return a + b + c + d + e;
    }

    static void voidSink(int v) {
        sinkValue = v;
    }

    private static void check(boolean cond, String label) {
        if (!cond) {
            throw new IllegalStateException("Signature dispatch failed: " + label);
        }
    }
}
