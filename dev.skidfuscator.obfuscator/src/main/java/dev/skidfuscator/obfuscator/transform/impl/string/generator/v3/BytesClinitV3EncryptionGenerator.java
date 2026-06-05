package dev.skidfuscator.obfuscator.transform.impl.string.generator.v3;

import dev.skidfuscator.obfuscator.skidasm.SkidClassNode;
import dev.skidfuscator.obfuscator.skidasm.SkidMethodNode;
import dev.skidfuscator.obfuscator.skidasm.cfg.SkidBlock;
import org.mapleir.ir.code.Expr;

import java.nio.charset.StandardCharsets;

public class BytesClinitV3EncryptionGenerator extends AbstractEncryptionGeneratorV3 {
    private final byte[] keys;

    public BytesClinitV3EncryptionGenerator(byte[] keys) {
        super("Bytes Generator");
        this.keys = keys;
    }

    @Override
    public void visitPre(SkidClassNode node) {
        super.visitPre(node);

        node.getClassInit().getEntryBlock().add(0, storeInjectField(
                node,
                "keys",
                "[B",
                generateByteArrayGenerator(node, keys)
        ));
    }

    @Override
    public Expr encrypt(String input, SkidMethodNode node, SkidBlock block) {
        if (isSeedWide(node)) {
            return encryptWide(input, node, block);
        }

        final byte[] encrypted = input.getBytes(StandardCharsets.UTF_16);

        // Super simple converting our integer to string, and getting bytes.
        final byte[] keyBytes = Integer.toString(getThreadedStringSeed(node, block)).getBytes();

        // Super simple XOR
        for (int i = 0; i < encrypted.length; i++) {
            encrypted[i] ^= keyBytes[i % keyBytes.length];
            encrypted[i] ^= keys[i % keys.length];
        }

        // Base64 encode it for testing
        return callInjectMethod(
                node.getParent(),
                "decryptor",
                "([BI)Ljava/lang/String;",
                generateByteArrayGenerator(node.getParent(), encrypted),
                getThreadedStringSeedExpr(node, block)
        );
    }

    /**
     * seed.wide variant: keys on the full 64-bit block predicate
     * ({@code Long.toString}) and threads the wide flow value through a
     * {@code long} decryptor parameter.
     */
    private Expr encryptWide(String input, SkidMethodNode node, SkidBlock block) {
        final byte[] encrypted = input.getBytes(StandardCharsets.UTF_16);

        final byte[] keyBytes = Long.toString(getThreadedStringSeedLong(node, block)).getBytes();

        for (int i = 0; i < encrypted.length; i++) {
            encrypted[i] ^= keyBytes[i % keyBytes.length];
            encrypted[i] ^= keys[i % keys.length];
        }

        return callInjectMethod(
                node.getParent(),
                "decryptorWide",
                "([BJ)Ljava/lang/String;",
                generateByteArrayGenerator(node.getParent(), encrypted),
                getThreadedStringSeedExprWide(node, block)
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

    @InjectField(
            value = "keys",
            tags = {InjectFieldTag.RANDOM_NAME}
    )
    private static byte[] localKeys;

    @InjectMethod(
            value = "decryptor",
            tags = {InjectMethodTag.RANDOM_NAME, InjectMethodTag.NARROW_ONLY}
    )
    private static String decryptMeBitch(final byte[] input, final int key) {
        final byte[] keyBytes = Integer.toString(key).getBytes();

        // Super simple XOR
        for (int i = 0; i < input.length; i++) {
            input[i] ^= keyBytes[i % keyBytes.length];
            input[i] ^= localKeys[i % localKeys.length];
        }

        // Base64 encode it for testing
        return new String(input, StandardCharsets.UTF_16);
    }

    @InjectMethod(
            value = "decryptorWide",
            tags = {InjectMethodTag.RANDOM_NAME, InjectMethodTag.WIDE_ONLY}
    )
    private static String decryptMeBitchWide(final byte[] input, final long key) {
        final byte[] keyBytes = Long.toString(key).getBytes();

        for (int i = 0; i < input.length; i++) {
            input[i] ^= keyBytes[i % keyBytes.length];
            input[i] ^= localKeys[i % localKeys.length];
        }

        return new String(input, StandardCharsets.UTF_16);
    }
}
