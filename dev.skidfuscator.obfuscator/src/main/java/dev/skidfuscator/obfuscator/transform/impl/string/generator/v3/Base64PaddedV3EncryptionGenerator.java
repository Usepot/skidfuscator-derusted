package dev.skidfuscator.obfuscator.transform.impl.string.generator.v3;

import dev.skidfuscator.obfuscator.skidasm.SkidClassNode;
import dev.skidfuscator.obfuscator.skidasm.SkidMethodNode;
import dev.skidfuscator.obfuscator.skidasm.cfg.SkidBlock;
import dev.skidfuscator.obfuscator.util.RandomUtil;
import dev.skidfuscator.obfuscator.util.TypeUtil;
import org.mapleir.ir.code.Expr;
import org.mapleir.ir.code.expr.ArithmeticExpr;
import org.mapleir.ir.code.expr.ConstantExpr;
import org.objectweb.asm.Type;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

public class Base64PaddedV3EncryptionGenerator extends AbstractEncryptionGeneratorV3 {
    private final byte[] pad;
    private final int padHash;

    public Base64PaddedV3EncryptionGenerator(byte[] pad) {
        super("Base64 Padded Generator");
        this.pad = pad;
        this.padHash = foldPadForGenerator(pad);
    }

    @Override
    public void visitPre(SkidClassNode node) {
        super.visitPre(node);

        node.getClassInit().getEntryBlock().add(0, storeInjectField(
                node,
                "pad",
                "[B",
                generateByteArrayGenerator(node, pad)
        ));
    }

    @Override
    public Expr encrypt(String input, SkidMethodNode node, SkidBlock block) {
        if (isSeedWide(node)) {
            return encryptWide(input, node, block);
        }

        final byte[] encrypted = input.getBytes(StandardCharsets.UTF_8);
        final int salt = RandomUtil.nextInt() | 1;
        final int site = getStringSiteHash(node, block, salt);
        final int guard = ((salt * 31) >>> 4) ^ (encrypted.length * 17) ^ (padHash >>> 3);
        final int key = deriveStringKey(getThreadedStringSeed(node, block), site, salt, guard, padHash);
        final byte[] keyBytes = Integer.toString(key).getBytes(StandardCharsets.UTF_8);

        for (int i = 0; i < encrypted.length; i++) {
            encrypted[i] ^= keyBytes[i % keyBytes.length];
            encrypted[i] ^= pad[i % pad.length];
        }

        final byte[] packed = new byte[encrypted.length + 12];
        packed[0] = (byte) (salt >>> 24);
        packed[1] = (byte) (salt >>> 16);
        packed[2] = (byte) (salt >>> 8);
        packed[3] = (byte) salt;
        packed[4] = (byte) (guard >>> 24);
        packed[5] = (byte) (guard >>> 16);
        packed[6] = (byte) (guard >>> 8);
        packed[7] = (byte) guard;
        packed[8] = (byte) (site >>> 24);
        packed[9] = (byte) (site >>> 16);
        packed[10] = (byte) (site >>> 8);
        packed[11] = (byte) site;
        System.arraycopy(encrypted, 0, packed, 12, encrypted.length);

        final String encoded = Base64.getEncoder().encodeToString(packed);
        final int common = encoded.hashCode() ^ padHash;
        final int mask = salt ^ common ^ guard ^ site;

        return callInjectMethod(
                node.getParent(),
                "decryptor",
                "(Ljava/lang/String;I)Ljava/lang/String;",
                new ConstantExpr(encoded, TypeUtil.STRING_TYPE),
                new ArithmeticExpr(
                        getThreadedStringSeedExpr(node, block),
                        new ConstantExpr(mask, Type.INT_TYPE),
                        ArithmeticExpr.Operator.XOR
                )
        );
    }

    /**
     * seed.wide variant. Derives a per-site long key from the full 64-bit block
     * predicate and widens the seed mask so the high 32 bits of entropy are not
     * left bare. The salt/guard integrity header still guards the packed data.
     */
    private Expr encryptWide(String input, SkidMethodNode node, SkidBlock block) {
        final byte[] encrypted = input.getBytes(StandardCharsets.UTF_8);
        final int salt = RandomUtil.nextInt() | 1;
        final int site = getStringSiteHash(node, block, salt);
        final int guard = ((salt * 31) >>> 4) ^ (encrypted.length * 17) ^ (padHash >>> 3);
        final long key = deriveStringKeyLong(getThreadedStringSeedLong(node, block), site, salt, guard, padHash);
        final byte[] keyBytes = Long.toString(key).getBytes(StandardCharsets.UTF_8);

        for (int i = 0; i < encrypted.length; i++) {
            encrypted[i] ^= keyBytes[i % keyBytes.length];
            encrypted[i] ^= pad[i % pad.length];
        }

        final byte[] packed = new byte[encrypted.length + 12];
        packed[0] = (byte) (salt >>> 24);
        packed[1] = (byte) (salt >>> 16);
        packed[2] = (byte) (salt >>> 8);
        packed[3] = (byte) salt;
        packed[4] = (byte) (guard >>> 24);
        packed[5] = (byte) (guard >>> 16);
        packed[6] = (byte) (guard >>> 8);
        packed[7] = (byte) guard;
        packed[8] = (byte) (site >>> 24);
        packed[9] = (byte) (site >>> 16);
        packed[10] = (byte) (site >>> 8);
        packed[11] = (byte) site;
        System.arraycopy(encrypted, 0, packed, 12, encrypted.length);

        final String encoded = Base64.getEncoder().encodeToString(packed);
        final int common = encoded.hashCode() ^ padHash;
        final int mask = salt ^ common ^ guard ^ site;
        final long maskLong = widenMask(mask);

        return callInjectMethod(
                node.getParent(),
                "decryptorWide",
                "(Ljava/lang/String;J)Ljava/lang/String;",
                new ConstantExpr(encoded, TypeUtil.STRING_TYPE),
                new ArithmeticExpr(
                        getThreadedStringSeedExprWide(node, block),
                        new ConstantExpr(maskLong, Type.LONG_TYPE),
                        ArithmeticExpr.Operator.XOR
                )
        );
    }

    @Override
    public String decrypt(DecryptorDictionary dictionary, int key) {
        final String encoded = dictionary.get("encrypted");
        final byte[] input = Base64.getDecoder().decode(encoded.getBytes(StandardCharsets.UTF_8));
        final byte[] keyBytes = Integer.toString(key).getBytes(StandardCharsets.UTF_8);

        for (int i = 0; i < input.length; i++) {
            input[i] ^= keyBytes[i % keyBytes.length];
            input[i] ^= pad[i % pad.length];
        }

        return new String(input, StandardCharsets.UTF_8);
    }

    private int foldPadForGenerator(final byte[] input) {
        int hash = 0x6D2B79F5;

        for (byte value : input) {
            hash ^= value & 0xFF;
            hash *= 0x45D9F3B;
            hash ^= hash >>> 16;
        }

        return hash;
    }

    @InjectField(
            value = "pad",
            tags = {InjectFieldTag.RANDOM_NAME}
    )
    private static byte[] localPad;

    @InjectMethod(
            value = "decryptor",
            tags = {InjectMethodTag.RANDOM_NAME, InjectMethodTag.NARROW_ONLY}
    )
    private static String decryptBase64Padded(final String input, final int maskedKey) {
        final byte[] packed = Base64.getDecoder().decode(input.getBytes(StandardCharsets.UTF_8));

        if (packed.length < 12) {
            throw new IllegalStateException();
        }

        final int salt = ((packed[0] & 0xFF) << 24)
                | ((packed[1] & 0xFF) << 16)
                | ((packed[2] & 0xFF) << 8)
                | (packed[3] & 0xFF);
        final int guard = ((packed[4] & 0xFF) << 24)
                | ((packed[5] & 0xFF) << 16)
                | ((packed[6] & 0xFF) << 8)
                | (packed[7] & 0xFF);
        final int site = ((packed[8] & 0xFF) << 24)
                | ((packed[9] & 0xFF) << 16)
                | ((packed[10] & 0xFF) << 8)
                | (packed[11] & 0xFF);
        final byte[] decoded = Arrays.copyOfRange(packed, 12, packed.length);

        int padHash = 0x6D2B79F5;

        for (byte value : localPad) {
            padHash ^= value & 0xFF;
            padHash *= 0x45D9F3B;
            padHash ^= padHash >>> 16;
        }

        final int common = input.hashCode() ^ padHash;

        final int noise = ((salt * 31) >>> 4) ^ (decoded.length * 17) ^ (padHash >>> 3) ^ guard;
        if (noise != 0) {
            throw new IllegalStateException();
        }

        long tamper = 0L;
        try {
            final Object value = Class.forName("sdk.Tamper").getMethod("poison").invoke(null);
            if (value instanceof Long) {
                tamper = ((Long) value).longValue();
            }
        } catch (Throwable ignored) {
        }
        final int kdfContext = padHash ^ (int) tamper ^ (int) (tamper >>> 32);
        final int seed = maskedKey ^ salt ^ common ^ guard ^ site;
        long state = (seed & 0xFFFFFFFFL) ^ 0xD6E8FEB86659FD93L;
        state ^= ((long) site << 32) ^ (salt & 0xFFFFFFFFL);
        state ^= state >>> 33;
        state *= 0xff51afd7ed558ccdL;
        state ^= state >>> 33;
        state *= 0xc4ceb9fe1a85ec53L;
        state ^= state >>> 33;
        state ^= Integer.toUnsignedLong(guard) * 0x9E3779B97F4A7C15L;
        state = Long.rotateLeft(state, 29) ^ Integer.toUnsignedLong(kdfContext);
        state ^= state >>> 33;
        state *= 0xff51afd7ed558ccdL;
        state ^= state >>> 33;
        state *= 0xc4ceb9fe1a85ec53L;
        state ^= state >>> 33;
        final int key = (int) (state ^ (state >>> 32));
        final byte[] keyBytes = Integer.toString(key).getBytes(StandardCharsets.UTF_8);

        for (int i = 0; i < decoded.length; i++) {
            decoded[i] ^= keyBytes[i % keyBytes.length];
            decoded[i] ^= localPad[i % localPad.length];
        }

        return new String(decoded, StandardCharsets.UTF_8);
    }

    @InjectMethod(
            value = "decryptorWide",
            tags = {InjectMethodTag.RANDOM_NAME, InjectMethodTag.WIDE_ONLY}
    )
    private static String decryptBase64PaddedWide(final String input, final long maskedKey) {
        final byte[] packed = Base64.getDecoder().decode(input.getBytes(StandardCharsets.UTF_8));

        if (packed.length < 12) {
            throw new IllegalStateException();
        }

        final int salt = ((packed[0] & 0xFF) << 24)
                | ((packed[1] & 0xFF) << 16)
                | ((packed[2] & 0xFF) << 8)
                | (packed[3] & 0xFF);
        final int guard = ((packed[4] & 0xFF) << 24)
                | ((packed[5] & 0xFF) << 16)
                | ((packed[6] & 0xFF) << 8)
                | (packed[7] & 0xFF);
        final int site = ((packed[8] & 0xFF) << 24)
                | ((packed[9] & 0xFF) << 16)
                | ((packed[10] & 0xFF) << 8)
                | (packed[11] & 0xFF);
        final byte[] decoded = Arrays.copyOfRange(packed, 12, packed.length);

        int padHash = 0x6D2B79F5;

        for (byte value : localPad) {
            padHash ^= value & 0xFF;
            padHash *= 0x45D9F3B;
            padHash ^= padHash >>> 16;
        }

        final int common = input.hashCode() ^ padHash;

        final int noise = ((salt * 31) >>> 4) ^ (decoded.length * 17) ^ (padHash >>> 3) ^ guard;
        if (noise != 0) {
            throw new IllegalStateException();
        }

        long tamper = 0L;
        try {
            final Object value = Class.forName("sdk.Tamper").getMethod("poison").invoke(null);
            if (value instanceof Long) {
                tamper = ((Long) value).longValue();
            }
        } catch (Throwable ignored) {
        }
        final int kdfContext = padHash ^ (int) tamper ^ (int) (tamper >>> 32);
        final int mask = salt ^ common ^ guard ^ site;
        final long seed = maskedKey ^ (((long) mask << 32) ^ (mask & 0xFFFFFFFFL));
        long state = seed ^ 0xD6E8FEB86659FD93L;
        state ^= ((long) site << 32) ^ (salt & 0xFFFFFFFFL);
        state ^= state >>> 33;
        state *= 0xff51afd7ed558ccdL;
        state ^= state >>> 33;
        state *= 0xc4ceb9fe1a85ec53L;
        state ^= state >>> 33;
        state ^= Integer.toUnsignedLong(guard) * 0x9E3779B97F4A7C15L;
        state = Long.rotateLeft(state, 29) ^ Integer.toUnsignedLong(kdfContext);
        state ^= state >>> 33;
        state *= 0xff51afd7ed558ccdL;
        state ^= state >>> 33;
        state *= 0xc4ceb9fe1a85ec53L;
        state ^= state >>> 33;
        final long key = state;
        final byte[] keyBytes = Long.toString(key).getBytes(StandardCharsets.UTF_8);

        for (int i = 0; i < decoded.length; i++) {
            decoded[i] ^= keyBytes[i % keyBytes.length];
            decoded[i] ^= localPad[i % localPad.length];
        }

        return new String(decoded, StandardCharsets.UTF_8);
    }
}
