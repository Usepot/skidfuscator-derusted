package dev.skidfuscator.obfuscator.nativebackend;

import org.mapleir.asm.MethodNode;
import org.objectweb.asm.Opcodes;

import java.util.Objects;

/**
 * Performs structural checks which are independent of a particular native
 * lowerer. Opcode support is checked by the lowerer and native-IR verifier.
 */
public final class NativeEligibility {
    private NativeEligibility() {
    }

    public static Result check(final MethodNode method) {
        Objects.requireNonNull(method, "method");

        final int access = method.node.access;
        if ((access & Opcodes.ACC_ABSTRACT) != 0) {
            return Result.unsupported("abstract methods have no implementation to lower");
        }
        if ((access & Opcodes.ACC_NATIVE) != 0) {
            return Result.unsupported("method is already native");
        }
        if ((access & Opcodes.ACC_BRIDGE) != 0) {
            return Result.unsupported("compiler-generated bridge methods are not stable native candidates");
        }
        if (method.node.instructions == null || method.node.instructions.size() == 0) {
            return Result.unsupported("method has no Code attribute");
        }

        final boolean ownerIsInterface = (method.owner.node.access & Opcodes.ACC_INTERFACE) != 0;
        if (ownerIsInterface || "<init>".equals(method.getName()) || "<clinit>".equals(method.getName())) {
            return Result.wrapperRequired();
        }
        return Result.direct();
    }

    public enum Conversion {
        DIRECT,
        WRAPPER_REQUIRED,
        UNSUPPORTED
    }

    public record Result(Conversion conversion, String reason) {
        public Result {
            Objects.requireNonNull(conversion, "conversion");
            Objects.requireNonNull(reason, "reason");
        }

        public boolean isSupported() {
            return conversion != Conversion.UNSUPPORTED;
        }

        private static Result direct() {
            return new Result(Conversion.DIRECT, "");
        }

        private static Result wrapperRequired() {
            return new Result(
                    Conversion.WRAPPER_REQUIRED,
                    "constructors, class initializers, and interface methods require a Java wrapper"
            );
        }

        private static Result unsupported(final String reason) {
            return new Result(Conversion.UNSUPPORTED, reason);
        }
    }
}
