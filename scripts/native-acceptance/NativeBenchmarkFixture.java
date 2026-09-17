/** Java 8 computational fixture used by the signed native performance matrix. */
public final class NativeBenchmarkFixture {
    private static volatile int sink;

    private NativeBenchmarkFixture() {
    }

    public static void main(String[] args) {
        for (int warmup = 0; warmup < 12; warmup++) sink ^= compute(warmup, 250_000);
        long[] samples = new long[31];
        int checksum = 0;
        for (int sample = 0; sample < samples.length; sample++) {
            long start = System.nanoTime();
            checksum ^= compute(sample * 31 + 7, 500_000);
            samples[sample] = System.nanoTime() - start;
        }
        sink = checksum;
        System.out.println("checksum=" + checksum);
        StringBuilder encoded = new StringBuilder("nanos=");
        for (int index = 0; index < samples.length; index++) {
            if (index != 0) encoded.append(',');
            encoded.append(samples[index]);
        }
        System.out.println(encoded.toString());
    }

    static int compute(int seed, int iterations) {
        int value = seed ^ 0x6D2B79F5;
        for (int index = 0; index < iterations; index++) {
            value += (index ^ value) * 31;
            value = ((value << 7) | (value >>> 25)) ^ (value >>> 11);
            if ((value & 15) == 3) value ^= 0x5A5A5A5A;
        }
        return value;
    }
}
