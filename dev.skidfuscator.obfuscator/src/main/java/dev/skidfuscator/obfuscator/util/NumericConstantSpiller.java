package dev.skidfuscator.obfuscator.util;

import dev.skidfuscator.obfuscator.Skidfuscator;
import org.mapleir.asm.ClassHelper;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.CodeSizeEvaluator;
import org.objectweb.asm.tree.*;
import org.topdank.byteengineer.commons.data.JarClassData;

import java.util.*;
import java.util.function.Supplier;

/**
 * Moves already-transformed numeric constants into bounded, side-effect-free
 * companion methods. It does not revert any obfuscation pass or original body.
 * Companions have no fields, initializer, reflection, native calls or app references.
 */
public final class NumericConstantSpiller {
    private static final int PAGE_SIZE = 1024;
    private static final int METHOD_BUDGET = 60000;
    private NumericConstantSpiller() { }

    public static void apply(Skidfuscator skid) {
        if (!skid.getConfig().getBoolean("constantPoolSplit.enabled", true)) return;
        int threshold = skid.getConfig().getInt("constantPoolSplit.threshold", 45000);
        if (threshold < 1000 || threshold > 60000) {
            throw new IllegalArgumentException("constantPoolSplit.threshold must be in [1000,60000]");
        }
        Set<String> names = new HashSet<>();
        for (JarClassData data : skid.getJarContents().getClassContents()) {
            names.add(data.getClassNode().getName());
        }
        for (JarClassData data : new ArrayList<>(skid.getJarContents().getClassContents())) {
            org.mapleir.asm.ClassNode wrapper = data.getClassNode();
            if (wrapper.isVirtual() || skid.getExemptAnalysis().isExempt(wrapper)
                    || skid.isNativeGeneratedClass(wrapper.getName())) continue;
            int before = ConstantPoolBudget.count(wrapper.node);
            if (before < threshold) continue;
            Supplier<String> uniqueName = () -> {
                String name;
                do { name = "skid/constant/C" + UUID.randomUUID().toString().replace("-", ""); }
                while (!names.add(name));
                return name;
            };
            SplitResult result = split(wrapper.node, uniqueName);
            for (ClassNode helper : result.helpers()) {
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
                helper.accept(writer);
                byte[] bytes = writer.toByteArray();
                // These late-generated companions may match the user's library
                // exemption regex. That output path preserves existing frames;
                // ClassHelper's default SKIP_FRAMES would silently discard them.
                org.mapleir.asm.ClassNode node = ClassHelper.create(bytes, 0);
                skid.getClassSource().add(node);
                skid.getJarContents().getClassContents().add(new JarClassData(helper.name + ".class", bytes, node));
            }
            Skidfuscator.LOGGER.warn("CONSTANT_POOL_SPLIT " + wrapper.getName() + " constants=" + before
                    + " -> " + ConstantPoolBudget.count(wrapper.node) + "; movedLoads=" + result.movedLoads()
                    + "; companions=" + result.helpers().size() + "; methodSizeGuards=" + result.sizeGuardedMethods());
        }
    }

    public record SplitResult(List<ClassNode> helpers, int movedLoads, List<String> sizeGuardedMethods) { }

    /** Public for executable bytecode regression tests and alternate packaging frontends. */
    public static SplitResult split(ClassNode owner, Supplier<String> uniqueName) {
        Map<Object, Slot> slots = new LinkedHashMap<>();
        List<Page> pages = new ArrayList<>();
        Map<String, Page> current = new HashMap<>();
        List<String> guarded = new ArrayList<>();
        int moved = 0;
        for (MethodNode method : owner.methods) {
            int loads = 0;
            for (AbstractInsnNode insn : method.instructions) {
                if (isNumericLoad(insn)) loads++;
            }
            if (loads == 0) continue;
            CodeSizeEvaluator size = new CodeSizeEvaluator(null);
            method.accept(size);
            // SIPUSH + INVOKESTATIC is at most four bytes larger than the old LDC.
            // Reserve branch-widening/frame headroom rather than making a large method invalid.
            if ((long) size.getMaxSize() + 4L * loads > METHOD_BUDGET) {
                guarded.add(method.name + method.desc);
                continue;
            }
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!isNumericLoad(insn)) continue;
                Object value = ((LdcInsnNode) insn).cst;
                Slot slot = slots.get(value);
                if (slot == null) {
                    String type = value instanceof Long ? "J" : "I";
                    Page page = current.get(type);
                    if (page == null || page.values.size() == PAGE_SIZE) {
                        page = new Page(uniqueName.get(), type, owner.version);
                        current.put(type, page);
                        pages.add(page);
                    }
                    slot = new Slot(page, page.values.size());
                    page.values.add(value);
                    slots.put(value, slot);
                }
                InsnList replacement = new InsnList();
                pushIndex(replacement, slot.index);
                replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, slot.page.name,
                        "v", "(I)" + slot.page.type, false));
                method.instructions.insertBefore(insn, replacement);
                method.instructions.remove(insn);
                moved++;
            }
        }
        List<ClassNode> helpers = new ArrayList<>();
        for (Page page : pages) helpers.add(page.emit());
        return new SplitResult(Collections.unmodifiableList(helpers), moved, Collections.unmodifiableList(guarded));
    }

    private static boolean isNumericLoad(AbstractInsnNode insn) {
        if (!(insn instanceof LdcInsnNode)) return false;
        Object value = ((LdcInsnNode) insn).cst;
        // Keep floating point raw bits/NaN payload behavior untouched.
        return value instanceof Integer || value instanceof Long;
    }

    private static void pushIndex(InsnList instructions, int value) {
        if (value <= 5) instructions.add(new InsnNode(Opcodes.ICONST_0 + value));
        else if (value <= Byte.MAX_VALUE) instructions.add(new IntInsnNode(Opcodes.BIPUSH, value));
        else instructions.add(new IntInsnNode(Opcodes.SIPUSH, value));
    }

    private record Slot(Page page, int index) { }

    private static final class Page {
        final String name;
        final String type;
        final int version;
        final List<Object> values = new ArrayList<>();
        Page(String name, String type, int version) {
            this.name = name; this.type = type; this.version = version;
        }
        ClassNode emit() {
            ClassNode node = new ClassNode();
            node.visit(version, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC,
                    name, null, "java/lang/Object", null);
            MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                    "v", "(I)" + type, null, null);
            LabelNode invalid = new LabelNode();
            LabelNode[] labels = new LabelNode[values.size()];
            for (int i = 0; i < labels.length; i++) labels[i] = new LabelNode();
            method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
            method.instructions.add(new TableSwitchInsnNode(0, labels.length - 1, invalid, labels));
            for (int i = 0; i < labels.length; i++) {
                method.instructions.add(labels[i]);
                method.instructions.add(new LdcInsnNode(values.get(i)));
                method.instructions.add(new InsnNode(type.equals("J") ? Opcodes.LRETURN : Opcodes.IRETURN));
            }
            method.instructions.add(invalid);
            method.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalArgumentException"));
            method.instructions.add(new InsnNode(Opcodes.DUP));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                    "java/lang/IllegalArgumentException", "<init>", "()V", false));
            method.instructions.add(new InsnNode(Opcodes.ATHROW));
            method.maxStack = 2;
            method.maxLocals = 1;
            node.methods.add(method);
            return node;
        }
    }
}
