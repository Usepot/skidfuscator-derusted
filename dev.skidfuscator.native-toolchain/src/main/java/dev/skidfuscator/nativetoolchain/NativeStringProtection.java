package dev.skidfuscator.nativetoolchain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/**
 * Per-build reversible encoding for native UTF-16 string payloads.
 *
 * <p>This is obfuscation, not cryptographic encryption: the emitted program necessarily contains
 * enough fragmented material to recover its strings. It prevents direct ASCII/UTF-16 extraction
 * and gives every build different payloads, while the native runtime reconstructs the short-lived
 * decoding state and clears its plaintext buffer.</p>
 */
public final class NativeStringProtection {
    static final long FRAGMENT_CONSTANT = 0xD6E8FEB86659FD93L;
    static final long STREAM_INCREMENT = 0x9E3779B97F4A7C15L;
    static final long MIX_MULTIPLIER_1 = 0xBF58476D1CE4E5B9L;
    static final long MIX_MULTIPLIER_2 = 0x94D049BB133111EBL;
    private static final byte[] DOMAIN = "skid.native.string.protection.v1"
            .getBytes(StandardCharsets.US_ASCII);
    private static final int BUILD_MATERIAL_BYTES = 32;

    private final byte[] buildMaterial;

    private NativeStringProtection(final byte[] buildMaterial) {
        if (buildMaterial.length < 16) {
            throw new IllegalArgumentException("Native string build material must contain at least 16 bytes");
        }
        this.buildMaterial = buildMaterial.clone();
    }

    /** Creates a reproducible protected mode from explicit per-build material. */
    public static NativeStringProtection fromBuildMaterial(final byte[] buildMaterial) {
        return new NativeStringProtection(Objects.requireNonNull(buildMaterial, "buildMaterial"));
    }

    /** Creates an operational protected mode using 256 bits from the supplied secure generator. */
    public static NativeStringProtection randomized(final SecureRandom random) {
        Objects.requireNonNull(random, "random");
        final byte[] material = new byte[BUILD_MATERIAL_BYTES];
        random.nextBytes(material);
        return new NativeStringProtection(material);
    }

    /** Creates an operational protected mode using the platform secure generator. */
    public static NativeStringProtection randomized() {
        return randomized(new SecureRandom());
    }

    ProtectedString protect(final String value, final int cacheId) {
        Objects.requireNonNull(value, "value");
        if (cacheId < 0) {
            throw new IllegalArgumentException("cacheId must be non-negative");
        }
        for (int attempt = 0; attempt < 1_000_000; attempt++) {
            final byte[] derived = derive(value, cacheId, attempt);
            final long initialState = readLong(derived, 0);
            final long fragmentB = readLong(derived, 8);
            final long fragmentA = initialState ^ Long.rotateLeft(fragmentB, 17) ^ FRAGMENT_CONSTANT;
            final int[] ciphertext = encode(value, initialState);
            if (!containsDirectPlaintext(value, ciphertext, fragmentA, fragmentB)) {
                return new ProtectedString(cacheId, fragmentA, fragmentB, ciphertext);
            }
        }
        throw new IllegalStateException("Unable to diversify protected native string material");
    }

    private byte[] derive(final String value, final int cacheId, final int attempt) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        digest.update(DOMAIN);
        digest.update(intBytes(buildMaterial.length));
        digest.update(buildMaterial);
        digest.update(intBytes(cacheId));
        digest.update(intBytes(attempt));
        digest.update(intBytes(value.length()));
        for (int index = 0; index < value.length(); index++) {
            final char unit = value.charAt(index);
            digest.update((byte) (unit >>> 8));
            digest.update((byte) unit);
        }
        return digest.digest();
    }

    private static int[] encode(final String value, final long initialState) {
        final int[] ciphertext = new int[value.length()];
        long state = initialState;
        for (int index = 0; index < value.length(); index++) {
            state += STREAM_INCREMENT;
            final long mixed = mix(state);
            ciphertext[index] = value.charAt(index) ^ ((int) mixed & 0xffff);
        }
        return ciphertext;
    }

    static int[] decode(final ProtectedString protectedString) {
        final int[] ciphertext = protectedString.ciphertext();
        final int[] plaintext = new int[ciphertext.length];
        long state = protectedString.fragmentA()
                ^ Long.rotateLeft(protectedString.fragmentB(), 17)
                ^ FRAGMENT_CONSTANT;
        for (int index = 0; index < plaintext.length; index++) {
            state += STREAM_INCREMENT;
            plaintext[index] = ciphertext[index] ^ ((int) mix(state) & 0xffff);
        }
        return plaintext;
    }

    private static long mix(final long state) {
        long mixed = state;
        mixed = (mixed ^ (mixed >>> 30)) * MIX_MULTIPLIER_1;
        mixed = (mixed ^ (mixed >>> 27)) * MIX_MULTIPLIER_2;
        return mixed ^ (mixed >>> 31);
    }

    private static boolean containsDirectPlaintext(
            final String value,
            final int[] ciphertext,
            final long fragmentA,
            final long fragmentB
    ) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (ciphertext[index] == value.charAt(index)) {
                return true;
            }
        }
        final byte[] ciphertextBytes = new byte[ciphertext.length * 2];
        for (int index = 0; index < ciphertext.length; index++) {
            ciphertextBytes[index * 2] = (byte) ciphertext[index];
            ciphertextBytes[index * 2 + 1] = (byte) (ciphertext[index] >>> 8);
        }
        final byte[] fragments = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(fragmentA).putLong(fragmentB)
                .order(ByteOrder.BIG_ENDIAN).putLong(fragmentA).putLong(fragmentB)
                .array();
        final byte[][] forbidden = {
                value.getBytes(StandardCharsets.UTF_8),
                value.getBytes(StandardCharsets.UTF_16LE),
                value.getBytes(StandardCharsets.UTF_16BE)
        };
        for (final byte[] needle : forbidden) {
            if (contains(ciphertextBytes, needle) || contains(fragments, needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(final byte[] haystack, final byte[] needle) {
        if (needle.length == 0 || needle.length > haystack.length) {
            return false;
        }
        outer:
        for (int start = 0; start <= haystack.length - needle.length; start++) {
            for (int index = 0; index < needle.length; index++) {
                if (haystack[start + index] != needle[index]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static byte[] intBytes(final int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array();
    }

    private static long readLong(final byte[] bytes, final int offset) {
        return ByteBuffer.wrap(bytes, offset, Long.BYTES).order(ByteOrder.BIG_ENDIAN).getLong();
    }

    record ProtectedString(int cacheId, long fragmentA, long fragmentB, int[] ciphertext) {
        ProtectedString {
            if (cacheId < 0) throw new IllegalArgumentException("cacheId must be non-negative");
            ciphertext = Arrays.copyOf(ciphertext, ciphertext.length);
        }

        @Override
        public int[] ciphertext() {
            return Arrays.copyOf(ciphertext, ciphertext.length);
        }
    }
}
