package dev.skidfuscator.obfuscator.number.pure;

import org.mapleir.asm.MethodNode;

import java.util.Set;

/**
 * Runtime-safe targets for VM-evaluated integer hashing.
 *
 * Evaluating an arbitrary application/library method in SSVM does not prove
 * that a generated caller can link to it: subsequent descriptor threading can
 * change its ABI, it may live behind a child class loader, and its owner may be
 * inaccessible. Obfuscating a hash target with hashes of other application
 * targets can also create initialization/recursion cycles.
 *
 * These public Java 8 bootstrap methods are deterministic integer permutations
 * (sum with either argument fixed; rotates with the distance fixed). They stay
 * available to every application loader without pinning ANY application body.
 * Selection still evaluates the actual target method in SSVM, uses the normal
 * base hasher, and rotates targets and constants per call site.
 */
public final class VmHashTargetPolicy {
    private static final Set<String> UNARY = Set.of(
            "reverse(I)I", "reverseBytes(I)I", "hashCode(I)I");
    private static final Set<String> BINARY = Set.of(
            "rotateLeft(II)I", "rotateRight(II)I", "sum(II)I");

    private VmHashTargetPolicy() { }

    public static boolean permits(MethodNode method) {
        if (method == null || method.owner == null || !method.owner.isPublic()
                || !"java/lang/Integer".equals(method.getOwner())
                || !method.isPublic() || !method.isStatic()
                || method.isAbstract() || method.isNative()) return false;
        String key = method.getName() + method.getDesc();
        return UNARY.contains(key) || BINARY.contains(key);
    }

    public static boolean permitsParameter(MethodNode method, int parameter) {
        if (!permits(method) || parameter < 0) return false;
        // A varying rotate distance is NOT injective; its value argument is.
        return parameter == 0 || (parameter == 1 && "sum".equals(method.getName()));
    }
}
