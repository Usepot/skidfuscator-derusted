import dev.skidfuscator.annotations.NativeObfuscation;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntUnaryOperator;

/** Broad Java 8 semantic fixture for signed AOT/VM differential runs. */
public final class NativeSemanticsFixture {
    private static final int CLASS_SEED;
    private static int synchronizedState;

    static {
        CLASS_SEED = 0x13579BDF;
    }

    public NativeSemanticsFixture() {
    }

    public static void main(String[] args) throws Throwable {
        System.out.println("digest=" + childDigest());
        System.out.println("loaders=" + loaderDigest());
        System.out.println("concurrency=" + concurrencyDigest());
    }

    public static long childDigest() throws Throwable {
        long digest = nativeIntegerEdges(Integer.MIN_VALUE, Integer.MAX_VALUE) ^ CLASS_SEED;
        digest = mix(digest, nativeFloatingEdges(Double.NaN, -0.0f));
        digest = mix(digest, nativeObjectsArraysAndDispatch(37));
        digest = mix(digest, nativeLambda(19));
        digest = mix(digest, nativeMethodHandle(23));
        digest = mix(digest, nativeExceptionsAndFinally(-4));
        digest = mix(digest, nativeRecursion(12));
        digest = mix(digest, nativeAllocationAndGc(256));
        digest = mix(digest, nativeReflection(29));
        digest = mix(digest, new NativeSemanticsFixture().nativeSynchronized(31));
        return digest;
    }

    @NativeObfuscation
    static long nativeIntegerEdges(int left, int right) {
        int sum = left + right;
        int shifted = (sum << 7) ^ (right >>> 5) ^ (left >> 3);
        long wide = ((long) left * (long) right) ^ 0x5A5A5A5AA5A5A5A5L;
        return wide + shifted + (right == 0 ? 0 : left / right) + (right == 0 ? 0 : left % right);
    }

    @NativeObfuscation
    static long nativeFloatingEdges(double value, float other) {
        double calculated = (value + other) * -3.25d;
        long nan = Double.doubleToRawLongBits(calculated);
        int negativeZero = Float.floatToRawIntBits(other);
        return nan ^ ((long) negativeZero << 17);
    }

    @NativeObfuscation
    static int nativeObjectsArraysAndDispatch(int seed) {
        Base value = new Derived(seed);
        Operation operation = (Operation) value;
        int[] data = new int[]{seed, value.field, operation.apply(seed + 1)};
        value.field = data[2] ^ data.length;
        Object boxed = value;
        return boxed instanceof Derived ? ((Derived) boxed).apply(data[0]) + value.field : -1;
    }

    @NativeObfuscation
    static int nativeLambda(final int seed) {
        IntUnaryOperator operator = value -> value * 17 + seed;
        return operator.applyAsInt(seed ^ 0x55);
    }

    @NativeObfuscation
    static long nativeMethodHandle(int seed) throws Throwable {
        MethodHandle handle = MethodHandles.lookup().findStatic(
                NativeSemanticsFixture.class, "nativeIntegerEdges",
                MethodType.methodType(long.class, int.class, int.class));
        return (long) handle.invokeExact(seed, seed + 1);
    }

    @NativeObfuscation
    static int nativeExceptionsAndFinally(int value) {
        int result = 7;
        try {
            if (value < 0) throw new IllegalArgumentException("negative:" + value);
            result = 100 / value;
        } catch (IllegalArgumentException exception) {
            result = exception.getMessage().length() * 13;
        } finally {
            result ^= 0x33;
        }
        return result;
    }

    @NativeObfuscation
    static int nativeRecursion(int depth) {
        return depth <= 1 ? 1 : depth + nativeRecursion(depth - 2);
    }

    @NativeObfuscation
    static int nativeAllocationAndGc(int count) {
        List<byte[]> values = new ArrayList<byte[]>();
        int digest = 0;
        for (int index = 0; index < count; index++) {
            byte[] value = new byte[128 + (index & 31)];
            value[index % value.length] = (byte) index;
            values.add(value);
            digest = digest * 31 + value[index % value.length];
            if (values.size() > 16) values.remove(0);
        }
        System.gc();
        return digest ^ values.size();
    }

    @NativeObfuscation
    static long nativeReflection(int value) throws Exception {
        Method target = NativeSemanticsFixture.class.getDeclaredMethod(
                "nativeIntegerEdges", int.class, int.class);
        target.setAccessible(true);
        return ((Long) target.invoke(null, value, value + 1)).longValue();
    }

    @NativeObfuscation
    synchronized int nativeSynchronized(int delta) {
        synchronizedState += delta;
        return synchronizedState;
    }

    private static long loaderDigest() throws Exception {
        URL location = NativeSemanticsFixture.class.getProtectionDomain().getCodeSource().getLocation();
        long digest = 0;
        for (int index = 0; index < 2; index++) {
            try (URLClassLoader loader = new URLClassLoader(new URL[]{location}, null)) {
                Class<?> child = Class.forName("NativeSemanticsFixture", true, loader);
                Method method = child.getDeclaredMethod("childDigest");
                method.setAccessible(true);
                digest = mix(digest, ((Long) method.invoke(null)).longValue());
            }
        }
        return digest;
    }

    private static long concurrencyDigest() throws Exception {
        final NativeSemanticsFixture fixture = new NativeSemanticsFixture();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> results = new ArrayList<Future<Integer>>();
            for (int index = 1; index <= 32; index++) {
                final int delta = index;
                results.add(executor.submit(new Callable<Integer>() {
                    @Override
                    public Integer call() {
                        return Integer.valueOf(fixture.nativeSynchronized(delta));
                    }
                }));
            }
            for (Future<Integer> result : results) result.get();
            return synchronizedState;
        } finally {
            executor.shutdownNow();
        }
    }

    private static long mix(long current, long value) {
        return (current * 0x9E3779B97F4A7C15L) ^ value;
    }

    private interface Operation {
        int apply(int value);
    }

    private static class Base {
        int field;

        Base(int field) {
            this.field = field;
        }
    }

    private static final class Derived extends Base implements Operation {
        @NativeObfuscation
        Derived(int field) {
            super(field);
        }

        @Override
        @NativeObfuscation
        public int apply(int value) {
            return value * 3 + field;
        }
    }
}
