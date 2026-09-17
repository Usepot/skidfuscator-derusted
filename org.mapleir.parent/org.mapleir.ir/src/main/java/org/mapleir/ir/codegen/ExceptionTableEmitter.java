package org.mapleir.ir.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;

/** Emits exception filters without confusing their union with a handler's stack type. */
public final class ExceptionTableEmitter {
    private ExceptionTableEmitter() { }

    public static void emit(final MethodVisitor method, final Label start, final Label end,
                            final Label handler, final Collection<Type> catchTypes) {
        Objects.requireNonNull(catchTypes, "catchTypes");
        if (catchTypes.isEmpty()) {
            throw new IllegalArgumentException("Exception range has no catch types");
        }
        // Dumpers emit ranges after instructions. Naturalisation can remove the
        // last goto in a protected block, leaving different labels at one offset.
        // Drop only that empty interval; never merge or reorder surviving entries.
        if (isEmptyInterval(method, start, end)) {
            return;
        }
        // Sorting only alternatives sharing this exact handler cannot change handler priority.
        // A common superclass is valid for the handler's frame, NOT for its exception filter:
        // replacing IOException | IllegalArgumentException with Exception catches too much.
        catchTypes.stream().distinct().sorted(Comparator.comparing(Type::getDescriptor)).forEach(type -> {
            if (type.getSort() != Type.OBJECT) {
                throw new IllegalArgumentException("Invalid exception catch type: " + type);
            }
            method.visitTryCatchBlock(start, end, handler, type.getInternalName());
        });
    }

    private static boolean isEmptyInterval(MethodVisitor method, Label start, Label end) {
        if (start == end) {
            return true;
        }
        if (!(method instanceof MethodNode)) {
            return false;
        }
        boolean inside = false;
        boolean hasInstruction = false;
        for (AbstractInsnNode instruction : ((MethodNode) method).instructions) {
            if (instruction instanceof LabelNode) {
                LabelNode label = (LabelNode) instruction;
                // MethodNode.visitLabel stores its node in the caller's Label.info;
                // that node's own getLabel() need not return the caller's Label.
                // Accept both visitor-created and directly assembled instruction lists.
                if (label == end.info || label.getLabel() == end) {
                    return inside && !hasInstruction;
                }
                if (label == start.info || label.getLabel() == start) {
                    inside = true;
                }
            }
            if (inside && instruction.getOpcode() >= 0) {
                hasInstruction = true;
            }
        }
        // Forward/unresolved labels cannot be classified before code is emitted.
        return false;
    }
}
