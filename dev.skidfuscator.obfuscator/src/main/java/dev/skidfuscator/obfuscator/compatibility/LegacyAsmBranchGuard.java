package dev.skidfuscator.obfuscator.compatibility;

import dev.skidfuscator.obfuscator.Skidfuscator;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.CodeSizeEvaluator;
import org.objectweb.asm.tree.*;
import org.topdank.byteengineer.commons.data.JarClassData;

import java.util.*;

/**
 * ASM 5.0.x can corrupt BootstrapMethods when resizing branches while remapping
 * an otherwise valid Java 8 class (ASM bug 317748 / JaCoCo issue 462).
 * Lower only potentially long jumps into equivalent 32-bit switch transfers.
 * All original body transformations and invokedynamic sites remain intact.
 *
 * This is an explicit legacy-loader compatibility mode, not a JVM limitation.
 * Run LAST, before frame recomputation. Bounds include wide local-variable
 * operands and wide constant-pool loads, so an identity/descriptor remapper
 * cannot silently grow a surviving branch past its signed-short range.
 */
public final class LegacyAsmBranchGuard implements Opcodes {
    // Leave 2767 bytes of headroom for small load-time instrumentation in
    // addition to the conservative, remapper-independent instruction sizes.
    static final int MAX_BRANCH_SPAN = 30000;
    private LegacyAsmBranchGuard() { }

    public static void apply(Skidfuscator skid) {
        if (!skid.getConfig().getBoolean("compatibility.legacyAsmBranches", false)) return;
        for (JarClassData entry : skid.getJarContents().getClassContents()) {
            org.mapleir.asm.ClassNode wrapper = entry.getClassNode();
            if (wrapper == null || wrapper.node == null) continue;
            if (!wrapper.isVirtual() && skid.getExemptAnalysis().isExempt(wrapper)) continue;
            int changed = apply(wrapper.node);
            if (changed != 0) Skidfuscator.LOGGER.warn("LEGACY_ASM_BRANCH_GUARD " + wrapper.getName()
                    + " loweredBranches=" + changed + "; no method bodies or callsites exempted");
        }
    }

    public static int apply(ClassNode owner) {
        boolean bootstrap = owner.methods.stream().flatMap(method -> Arrays.stream(method.instructions.toArray()))
                .anyMatch(instruction -> instruction instanceof InvokeDynamicInsnNode
                        || instruction instanceof LdcInsnNode ldc && ldc.cst instanceof org.objectweb.asm.ConstantDynamic);
        if (!bootstrap) return 0;
        int total = 0;
        for (MethodNode method : owner.methods) total += lower(owner.name, method);
        return total;
    }

    private static int lower(String owner, MethodNode method) {
        int lowered = 0;
        while (true) {
            Map<AbstractInsnNode, Integer> offsets = new IdentityHashMap<>();
            CodeSizeEvaluator size = new CodeSizeEvaluator(null) {
                @Override public void visitVarInsn(int opcode, int slot) { super.visitVarInsn(opcode, 65534); }
                @Override public void visitIincInsn(int slot, int increment) { super.visitIincInsn(65534, 32767); }
            };
            for (AbstractInsnNode instruction : method.instructions) {
                offsets.put(instruction, size.getMaxSize());
                instruction.accept(size);
            }
            List<JumpInsnNode> far = new ArrayList<>();
            for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof JumpInsnNode jump) {
                Integer target = offsets.get(jump.label);
                if (target == null) throw new IllegalStateException("Unbound jump in " + owner + "." + method.name + method.desc);
                if (Math.abs((long) offsets.get(jump) - target) > MAX_BRANCH_SPAN) far.add(jump);
            }
            if (far.isEmpty()) return lowered;
            // Each iteration removes original long jumps. The newly introduced
            // conditional reaches only the adjacent switch continuation.
            for (JumpInsnNode jump : far) {
                int opcode = jump.getOpcode();
                if (opcode != GOTO && !conditional(opcode))
                    throw new IllegalStateException("Unsupported long jump opcode " + opcode + " in " + owner + "." + method.name);
                InsnList replacement = new InsnList();
                LabelNode continuation = null;
                if (opcode != GOTO) {
                    continuation = new LabelNode();
                    replacement.add(new JumpInsnNode(inverse(opcode), continuation));
                }
                replacement.add(new InsnNode(ICONST_0));
                // A terminal zero-pair lookupswitch is rejected as "Bad instruction"
                // by the supported Java 8 verifier at some instruction alignments.
                // A single explicit case keeps the same 32-bit unconditional transfer
                // without relying on that empty-switch edge case.
                replacement.add(new LookupSwitchInsnNode(jump.label, new int[]{0}, new LabelNode[]{jump.label}));
                if (continuation != null) replacement.add(continuation);
                method.instructions.insertBefore(jump, replacement);
                method.instructions.remove(jump);
                lowered++;
            }
            CodeSizeEvaluator actual = new CodeSizeEvaluator(null);
            method.accept(actual);
            if (actual.getMaxSize() >= 65536)
                throw new IllegalStateException("Legacy ASM branch repair exceeds Code budget for " + owner + "."
                        + method.name + method.desc + "; refusing unsafe output (reduce upstream growth)");
        }
    }

    private static boolean conditional(int opcode) {
        return opcode >= IFEQ && opcode <= IF_ACMPNE || opcode == IFNULL || opcode == IFNONNULL;
    }
    static int inverse(int opcode) {
        if (opcode == IFNULL) return IFNONNULL;
        if (opcode == IFNONNULL) return IFNULL;
        if (!conditional(opcode)) throw new IllegalArgumentException("Not a conditional branch: " + opcode);
        return ((opcode + 1) ^ 1) - 1;
    }
}
