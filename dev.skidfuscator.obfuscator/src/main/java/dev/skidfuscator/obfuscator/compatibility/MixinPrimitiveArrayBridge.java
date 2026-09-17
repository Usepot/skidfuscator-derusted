package dev.skidfuscator.obfuscator.compatibility;

import dev.skidfuscator.obfuscator.Skidfuscator;
import org.mapleir.asm.ClassHelper;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.topdank.byteengineer.commons.data.JarClassData;

import java.util.*;

/**
 * Mixin 0.7's TypeInsn descriptor remapper strips '[' and then looks primitive
 * descriptor letters up as class names. This operation-level lowering preserves
 * real JVM casts, type tests and reference-array allocations in an ordinary
 * companion, where Mixin never tries to reinterpret them. The donor calls a
 * strongly typed method; method descriptor parsing correctly handles arrays.
 *
 * No hook, class or original method body is exempted from obfuscation. The late
 * bridge is intentionally straight-line bytecode with exactly the original JVM
 * instruction and exception behavior. Generated companions must be packaged.
 */
public final class MixinPrimitiveArrayBridge implements Opcodes {
    private MixinPrimitiveArrayBridge() { }

    public record Result(ClassNode helper, int operations) { }
    private record Operation(int opcode, String type) { }

    public static boolean needsBridge(TypeInsnNode instruction) {
        int opcode = instruction.getOpcode();
        if (opcode != CHECKCAST && opcode != INSTANCEOF && opcode != ANEWARRAY) return false;
        if (!instruction.desc.startsWith("[")) return false;
        Type type = Type.getType(instruction.desc);
        return type.getElementType().getSort() >= Type.BOOLEAN
                && type.getElementType().getSort() <= Type.DOUBLE;
    }

    public static Result lower(ClassNode donor, String companionName) {
        if (!RelocatableInvokeDynamic.isMixin(donor)) return new Result(null, 0);
        ClassNode companion = new ClassNode();
        companion.visit(V1_8, ACC_PUBLIC | ACC_FINAL | ACC_SUPER | ACC_SYNTHETIC,
                companionName, null, "java/lang/Object", null);
        Map<Operation, MethodNode> bridges = new LinkedHashMap<>();
        int changed = 0;
        for (MethodNode method : donor.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;) {
                AbstractInsnNode next = instruction.getNext();
                if (instruction instanceof TypeInsnNode type && needsBridge(type)) {
                    Operation operation = new Operation(type.getOpcode(), type.desc);
                    MethodNode bridge = bridges.computeIfAbsent(operation, key -> create(companion, key, bridges.size()));
                    method.instructions.set(type, new MethodInsnNode(INVOKESTATIC, companionName, bridge.name, bridge.desc, false));
                    changed++;
                }
                instruction = next;
            }
        }
        return new Result(changed == 0 ? null : companion, changed);
    }

    private static MethodNode create(ClassNode companion, Operation operation, int index) {
        boolean allocation = operation.opcode == ANEWARRAY;
        String desc = allocation ? "(I)[" + operation.type
                : "(Ljava/lang/Object;)" + (operation.opcode == INSTANCEOF ? "Z" : operation.type);
        MethodNode method = new MethodNode(ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC,
                "a" + Integer.toString(index, 36), desc, null, null);
        method.instructions.add(new VarInsnNode(allocation ? ILOAD : ALOAD, 0));
        method.instructions.add(new TypeInsnNode(operation.opcode, operation.type));
        method.instructions.add(new InsnNode(operation.opcode == INSTANCEOF ? IRETURN : ARETURN));
        method.maxLocals = 1;
        method.maxStack = 1;
        companion.methods.add(method);
        return method;
    }

    /** Run after body passes, before output and before contract validation. */
    public static void apply(Skidfuscator skid) {
        if (!skid.getConfig().getBoolean("compatibility.mixinLegacyPrimitiveArrays", true)) return;
        List<JarClassData> generated = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (JarClassData data : skid.getJarContents().getClassContents()) names.add(data.getClassNode().getName());
        for (JarClassData data : new ArrayList<>(skid.getJarContents().getClassContents())) {
            org.mapleir.asm.ClassNode original = data.getClassNode();
            if (skid.getExemptAnalysis().isExempt(original) || !RelocatableInvokeDynamic.isMixin(original.node)) continue;
            String name;
            do { name = "skid/mixin/array/Bridge_" + UUID.randomUUID().toString().replace("-", ""); }
            while (!names.add(name));
            Result result = lower(original.node, name);
            if (result.operations == 0) continue;
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            result.helper.accept(writer);
            byte[] bytes = writer.toByteArray();
            // Preserve frames even if the generated namespace matches a library
            // exclusion and therefore uses the pass-through output writer.
            org.mapleir.asm.ClassNode helper = ClassHelper.create(bytes, 0);
            skid.getClassSource().add(helper);
            generated.add(new JarClassData(helper.getName() + ".class", bytes, helper));
            Skidfuscator.LOGGER.warn("MIXIN_ARRAY_BRIDGE " + original.getName() + " operations="
                    + result.operations + " helper=" + helper.getName());
        }
        skid.getJarContents().getClassContents().addAll(generated);
    }
}
