package dev.skidfuscator.obfuscator.transform.impl.string.generator.v3;

import dev.skidfuscator.obfuscator.skidasm.SkidClassNode;
import dev.skidfuscator.obfuscator.skidasm.SkidMethodNode;
import dev.skidfuscator.obfuscator.skidasm.cfg.SkidBlock;
import dev.skidfuscator.obfuscator.util.RandomUtil;
import org.mapleir.ir.code.Expr;
import org.mapleir.ir.code.expr.ArithmeticExpr;
import org.mapleir.ir.code.expr.ConstantExpr;
import org.mapleir.ir.code.expr.invoke.InvocationExpr;
import org.mapleir.ir.code.expr.invoke.StaticInvocationExpr;
import org.mapleir.ir.code.expr.invoke.VirtualInvocationExpr;
import org.objectweb.asm.Type;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class ByteBufferClinitV3EncryptionGenerator extends AbstractEncryptionGeneratorV3 {
    public ByteBufferClinitV3EncryptionGenerator() {
        super("Bytes Generator");
    }

    private final StringBuilder buffer = new StringBuilder();
    private int bufferIndex = 0;

    @Override
    public void visitPost(SkidClassNode node) {
        super.visitPost(node);
        node.getClassInit().getEntryBlock().add(0, storeInjectField(
                node,
                "keys",
                "Ljava/lang/String;",
                new VirtualInvocationExpr(
                        InvocationExpr.CallType.VIRTUAL,
                        new Expr[] {
                                new VirtualInvocationExpr(
                                        InvocationExpr.CallType.VIRTUAL,
                                        new Expr[]{
                                                new StaticInvocationExpr(
                                                        new Expr[] {
                                                                this.generateByteArrayGenerator(
                                                                        node,
                                                                        buffer.toString().getBytes(StandardCharsets.UTF_16BE)
                                                                )
                                                        },
                                                        "java/nio/ByteBuffer",
                                                        "wrap",
                                                        "([B)Ljava/nio/ByteBuffer;"
                                                )
                                        },
                                        "java/nio/ByteBuffer",
                                        "asCharBuffer",
                                        "()Ljava/nio/CharBuffer;"
                                )
                        },
                        "java/nio/CharBuffer",
                        "toString",
                        "()Ljava/lang/String;"
                )

        ));

        /*System.out.println(String.format(
                "Generated buffer with %d chars and %d index in %s",
                buffer.length(),
                bufferIndex,
                node.getName()
        ));*/
    }

    @Override
    public Expr encrypt(String input, SkidMethodNode node, SkidBlock block) {
        final byte[] encrypted = input.getBytes(StandardCharsets.UTF_16BE);
        final int offset = buffer.length();
        final int salt = RandomUtil.nextInt() | 1;
        final int site = getStringSiteHash(node, block, salt);
        final int context = encrypted.length ^ offset;
        final int key = deriveStringKey(getThreadedStringSeed(node, block), site, salt, encrypted.length, context);
        final int mask = salt ^ site ^ context;
        final byte[] keyBytes = Integer.toString(key).getBytes(StandardCharsets.UTF_8);

        // Super simple XOR
        for (int i = 0; i < encrypted.length; i++) {
            encrypted[i] ^= keyBytes[i % keyBytes.length];
        }

        final byte[] encryptedByteBuffer = new byte[8];

        encryptedByteBuffer[4] = (byte) (offset >> 24);
        encryptedByteBuffer[5] = (byte) (offset >> 16);
        encryptedByteBuffer[6] = (byte) (offset >> 8);
        encryptedByteBuffer[7] = (byte) offset;

        final String values = new String(encrypted, StandardCharsets.UTF_16BE);

        final int length = values.length();
        // Encode length of the buffer
        encryptedByteBuffer[0] = (byte) (length >> 24);
        encryptedByteBuffer[1] = (byte) (length >> 16);
        encryptedByteBuffer[2] = (byte) (length >> 8);
        encryptedByteBuffer[3] = (byte) length;

        buffer.append(values);
        bufferIndex++;

        // Base64 encode it for testing
        return callInjectMethod(
                node.getParent(),
                "decryptor",
                "([BIII)Ljava/lang/String;",
                generateByteArrayGenerator(node.getParent(), encryptedByteBuffer),
                new ArithmeticExpr(
                        getThreadedStringSeedExpr(node, block),
                        new ConstantExpr(mask, Type.INT_TYPE),
                        ArithmeticExpr.Operator.XOR
                ),
                new ConstantExpr(site, Type.INT_TYPE),
                new ConstantExpr(salt, Type.INT_TYPE)
        );
    }

    @Override
    public String decrypt(DecryptorDictionary dictionary, int key) {
        throw new IllegalStateException("Not implemented");
    }

    @InjectField(
            value = "keys",
            tags = {InjectFieldTag.RANDOM_NAME}
    )
    private static String localBuffer;

    @InjectMethod(
            value = "decryptor",
            tags = InjectMethodTag.RANDOM_NAME
    )
    private static String decryptMeBitch(final byte[] index, final int maskedSeed, final int site, final int salt) {
        final int size = ((index[0] & 0xFF) << 24) | ((index[1] & 0xFF) << 16) | ((index[2] & 0xFF) << 8) | (index[3] & 0xFF);
        final int offset = ((index[4] & 0xFF) << 24) | ((index[5] & 0xFF) << 16) | ((index[6] & 0xFF) << 8) | (index[7] & 0xFF);

        final byte[] input = localBuffer
                .substring(offset, offset + size)
                .getBytes(StandardCharsets.UTF_16BE);
        int context = input.length ^ offset;
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
        }

        // Base64 encode it for testing
        return new String(input, StandardCharsets.UTF_16BE);
    }
}
