package dev.skidfuscator.obfuscator.transform.impl.string.generator.v3;

import dev.skidfuscator.obfuscator.skidasm.SkidClassNode;
import dev.skidfuscator.obfuscator.skidasm.SkidMethodNode;
import dev.skidfuscator.obfuscator.skidasm.cfg.SkidBlock;
import dev.skidfuscator.obfuscator.util.RandomUtil;
import org.mapleir.ir.code.Expr;
import org.mapleir.ir.code.expr.ArithmeticExpr;
import org.mapleir.ir.code.expr.ConstantExpr;
import org.objectweb.asm.Type;

import java.nio.charset.StandardCharsets;

public class BytesV3EncryptionGenerator extends AbstractEncryptionGeneratorV3 {
    private final byte[] keys;

    public BytesV3EncryptionGenerator(byte[] keys) {
        super("Bytes Generator");
        this.keys = keys;
    }

    private Expr internalKeys;

    @Override
    public void visitPre(SkidClassNode node) {
        super.visitPre(node);

        this.internalKeys = generateByteArrayGenerator(node, keys);
    }

    @Override
    public Expr encrypt(String input, SkidMethodNode node, SkidBlock block) {
        if (isSeedWide(node)) {
            return encryptWide(input, node, block);
        }

        final byte[] encrypted = input.getBytes(StandardCharsets.UTF_16);
        final int salt = RandomUtil.nextInt() | 1;
        final int site = getStringSiteHash(node, block, salt);
        final int context = foldBytesForStringKey(keys) ^ encrypted.length;
        final int key = deriveStringKey(getThreadedStringSeed(node, block), site, salt, encrypted.length, context);
        final int mask = salt ^ site ^ context;
        final byte[] keyBytes = Integer.toString(key).getBytes(StandardCharsets.UTF_8);

        // Super simple XOR
        for (int i = 0; i < encrypted.length; i++) {
            encrypted[i] ^= keyBytes[i % keyBytes.length];
            encrypted[i] ^= keys[i % keys.length];
        }

        // Base64 encode it for testing
        return callInjectMethod(
                node.getParent(),
                "decryptor",
                "([B[BIII)Ljava/lang/String;",
                generateByteArrayGenerator(node.getParent(), encrypted),
                internalKeys.copy(),
                new ArithmeticExpr(
                        getThreadedStringSeedExpr(node, block),
                        new ConstantExpr(mask, Type.INT_TYPE),
                        ArithmeticExpr.Operator.XOR
                ),
                new ConstantExpr(site, Type.INT_TYPE),
                new ConstantExpr(salt, Type.INT_TYPE)
        );
    }

    /**
     * seed.wide variant: derives a per-site long key from the full 64-bit block
     * predicate and threads only a masked seed into the decryptor.
     */
    private Expr encryptWide(String input, SkidMethodNode node, SkidBlock block) {
        final byte[] encrypted = input.getBytes(StandardCharsets.UTF_16);
        final int salt = RandomUtil.nextInt() | 1;
        final int site = getStringSiteHash(node, block, salt);
        final int context = foldBytesForStringKey(keys) ^ encrypted.length;
        final long key = deriveStringKeyLong(getThreadedStringSeedLong(node, block), site, salt, encrypted.length, context);
        final long mask = widenMask(salt ^ site ^ context);
        final byte[] keyBytes = Long.toString(key).getBytes(StandardCharsets.UTF_8);

        for (int i = 0; i < encrypted.length; i++) {
            encrypted[i] ^= keyBytes[i % keyBytes.length];
            encrypted[i] ^= keys[i % keys.length];
        }

        return callInjectMethod(
                node.getParent(),
                "decryptorWide",
                "([B[BJII)Ljava/lang/String;",
                generateByteArrayGenerator(node.getParent(), encrypted),
                internalKeys.copy(),
                new ArithmeticExpr(
                        getThreadedStringSeedExprWide(node, block),
                        new ConstantExpr(mask, Type.LONG_TYPE),
                        ArithmeticExpr.Operator.XOR
                ),
                new ConstantExpr(site, Type.INT_TYPE),
                new ConstantExpr(salt, Type.INT_TYPE)
        );
    }

    @Override
    public String decrypt(DecryptorDictionary dictionary, int key) {
        final byte[] input = dictionary.get("encrypted");

        // Super simple converting our integer to string, and getting bytes.
        final byte[] keyBytes = Integer.toString(key).getBytes();

        // Super simple XOR
        for (int i = 0; i < input.length; i++) {
            input[i] ^= keyBytes[i % keyBytes.length];
            input[i] ^= keys[i % keys.length];
        }

        // Base64 encode it for testing
        return new String(input, StandardCharsets.UTF_16);
    }

    @InjectMethod(value = "decryptor", tags = {InjectMethodTag.RANDOM_NAME, InjectMethodTag.NARROW_ONLY})
    private static String decryptMeBitch(final byte[] input, final byte[] keys, final int maskedSeed, final int site, final int salt) {
        int context = 0x6D2B79F5;
        for (byte value : keys) {
            context ^= value & 0xFF;
            context *= 0x45D9F3B;
            context ^= context >>> 16;
        }
        context ^= input.length;
        long tamper = 0L;
        try {
            final Object value = Class.forName("sdk.Tamper").getMethod("poison").invoke(null);
            if (value instanceof Long) {
                tamper = ((Long) value).longValue();
            }
        } catch (Throwable ignored) {
        }
        context ^= (int) tamper ^ (int) (tamper >>> 32);

        final int seed = maskedSeed ^ salt ^ site ^ context;
        long state = (seed & 0xFFFFFFFFL) ^ 0xD6E8FEB86659FD93L;
        state ^= ((long) site << 32) ^ (salt & 0xFFFFFFFFL);
        state ^= state >>> 33;
        state *= 0xff51afd7ed558ccdL;
        state ^= state >>> 33;
        state *= 0xc4ceb9fe1a85ec53L;
        state ^= state >>> 33;
        state ^= Integer.toUnsignedLong(input.length) * 0x9E3779B97F4A7C15L;
        state = Long.rotateLeft(state, 29) ^ Integer.toUnsignedLong(context);
        state ^= state >>> 33;
        state *= 0xff51afd7ed558ccdL;
        state ^= state >>> 33;
        state *= 0xc4ceb9fe1a85ec53L;
        state ^= state >>> 33;
        final int key = (int) (state ^ (state >>> 32));
        final byte[] keyBytes = Integer.toString(key).getBytes(StandardCharsets.UTF_8);

        // Super simple XOR
        for (int i = 0; i < input.length; i++) {
            input[i] ^= keyBytes[i % keyBytes.length];
            input[i] ^= keys[i % keys.length];
        }

        // Base64 encode it for testing
        return new String(input, StandardCharsets.UTF_16);
    }

    @InjectMethod(value = "decryptorWide", tags = {InjectMethodTag.RANDOM_NAME, InjectMethodTag.WIDE_ONLY})
    private static String decryptMeBitchWide(final byte[] input, final byte[] keys, final long maskedSeed, final int site, final int salt) {
        int context = 0x6D2B79F5;
        for (byte value : keys) {
            context ^= value & 0xFF;
            context *= 0x45D9F3B;
            context ^= context >>> 16;
        }
        context ^= input.length;
        long tamper = 0L;
        try {
            final Object value = Class.forName("sdk.Tamper").getMethod("poison").invoke(null);
            if (value instanceof Long) {
                tamper = ((Long) value).longValue();
            }
        } catch (Throwable ignored) {
        }
        context ^= (int) tamper ^ (int) (tamper >>> 32);

        final int mask = salt ^ site ^ context;
        final long seed = maskedSeed ^ (((long) mask << 32) ^ (mask & 0xFFFFFFFFL));
        long state = seed ^ 0xD6E8FEB86659FD93L;
        state ^= ((long) site << 32) ^ (salt & 0xFFFFFFFFL);
        state ^= state >>> 33;
        state *= 0xff51afd7ed558ccdL;
        state ^= state >>> 33;
        state *= 0xc4ceb9fe1a85ec53L;
        state ^= state >>> 33;
        state ^= Integer.toUnsignedLong(input.length) * 0x9E3779B97F4A7C15L;
        state = Long.rotateLeft(state, 29) ^ Integer.toUnsignedLong(context);
        state ^= state >>> 33;
        state *= 0xff51afd7ed558ccdL;
        state ^= state >>> 33;
        state *= 0xc4ceb9fe1a85ec53L;
        state ^= state >>> 33;
        final byte[] keyBytes = Long.toString(state).getBytes(StandardCharsets.UTF_8);

        for (int i = 0; i < input.length; i++) {
            input[i] ^= keyBytes[i % keyBytes.length];
            input[i] ^= keys[i % keys.length];
        }

        return new String(input, StandardCharsets.UTF_16);
    }
}
