package dev.skidfuscator.obfuscator.nativebackend;

import org.mapleir.asm.ClassNode;

import java.util.Objects;

/** A staged Java-owned linkage bridge installed atomically with its native caller. */
public record JavaLinkageHelper(
        ClassNode owner,
        org.objectweb.asm.tree.MethodNode method,
        String kind
) {
    public JavaLinkageHelper {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(kind, "kind");
        if (kind.isBlank()) throw new IllegalArgumentException("kind cannot be blank");
    }
}
