package dev.skidfuscator.obfuscator.util;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassTooLargeException;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

/** Sizes the class actually being emitted, not its original input constant pool. */
public final class ConstantPoolBudget {
    public static final int SOFT_LIMIT = 64000;
    private ConstantPoolBudget() { }

    public static int count(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        // Avoid serializing partially transformed methods: branch widening would
        // attempt to repair stack maps before the final frame-computation stage.
        // A fresh UTF8 entry receives the previous constant_pool_count as its index.
        // This unique probe exists only in this discarded writer, never in output.
        return writer.newUTF8("SkidPoolProbe:" + java.util.UUID.randomUUID());
    }

    public static int allowance(int currentCount, int fixedReserve, int worstCasePerSite) {
        if (currentCount < 0 || fixedReserve < 0 || worstCasePerSite <= 0) {
            throw new IllegalArgumentException("Invalid constant-pool budget");
        }
        return (int) Math.max(0L, ((long) SOFT_LIMIT - currentCount - fixedReserve) / worstCasePerSite);
    }
}
