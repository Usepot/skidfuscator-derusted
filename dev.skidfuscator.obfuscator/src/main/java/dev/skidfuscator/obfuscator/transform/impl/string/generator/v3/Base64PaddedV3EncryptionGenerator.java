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
        final byte[] encrypted = input.getBytes(StandardCharsets.UTF_8);
        final byte[] keyBytes = Integer.toString(getThreadedStringSeed(node, block)).getBytes(StandardCharsets.UTF_8);

        for (int i = 0; i < encrypted.length; i++) {
            encrypted[i] ^= keyBytes[i % keyBytes.length];
            encrypted[i] ^= pad[i % pad.length];
        }

        final String encoded = Base64.getEncoder().encodeToString(encrypted);
        final int salt = RandomUtil.nextInt() | 1;
        final int mask = salt ^ encoded.hashCode() ^ padHash;
        final int guard = ((salt * 31) >>> 4) ^ (encoded.length() * 17) ^ (padHash >>> 3);

        return callInjectMethod(
                node.getParent(),
                "decryptor",
                "(Ljava/lang/String;III)Ljava/lang/String;",
                new ConstantExpr(encoded, TypeUtil.STRING_TYPE),
                new ArithmeticExpr(
                        getThreadedStringSeedExpr(node, block),
                        new ConstantExpr(mask, Type.INT_TYPE),
                        ArithmeticExpr.Operator.XOR
                ),
                new ConstantExpr(salt, Type.INT_TYPE),
                new ConstantExpr(guard, Type.INT_TYPE)
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
            tags = InjectMethodTag.RANDOM_NAME
    )
    private static String decryptBase64Padded(final String input, final int maskedKey, final int salt, final int guard) {
        final byte[] decoded = Base64.getDecoder().decode(input.getBytes(StandardCharsets.UTF_8));

        int padHash = 0x6D2B79F5;

        for (byte value : localPad) {
            padHash ^= value & 0xFF;
            padHash *= 0x45D9F3B;
            padHash ^= padHash >>> 16;
        }

        int noise = ((salt * 31) >>> 4) ^ (input.length() * 17) ^ (padHash >>> 3) ^ guard;
        noise = ((noise * 31) >>> 4) ^ (noise >>> 16);
        if (((noise ^ noise) | (localPad.length - localPad.length)) != 0) {
            throw new IllegalStateException();
        }

        final int key = maskedKey ^ salt ^ input.hashCode() ^ padHash;
        final byte[] keyBytes = Integer.toString(key).getBytes(StandardCharsets.UTF_8);

        for (int i = 0; i < decoded.length; i++) {
            decoded[i] ^= keyBytes[i % keyBytes.length];
            decoded[i] ^= localPad[i % localPad.length];
        }

        return new String(decoded, StandardCharsets.UTF_8);
    }
}
