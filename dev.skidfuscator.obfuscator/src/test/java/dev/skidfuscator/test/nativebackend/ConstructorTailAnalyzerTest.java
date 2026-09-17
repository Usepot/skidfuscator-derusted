package dev.skidfuscator.test.nativebackend;

import dev.skidfuscator.nativeir.NativeBackend;
import dev.skidfuscator.nativeir.NativeFunction;
import dev.skidfuscator.nativeir.NativeInstruction;
import dev.skidfuscator.nativeir.NativeOpcode;
import dev.skidfuscator.nativeir.NativeType;
import dev.skidfuscator.obfuscator.nativebackend.lowering.ConstructorTailAnalyzer;
import dev.skidfuscator.obfuscator.nativebackend.lowering.MapleNativeIrLowerer;
import org.junit.jupiter.api.Test;
import org.mapleir.asm.ClassNode;
import org.mapleir.asm.MethodNode;
import org.mapleir.ir.cfg.ControlFlowGraph;
import org.mapleir.ir.cfg.builder.ControlFlowGraphBuilder;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructorTailAnalyzerTest {
    private final ConstructorTailAnalyzer analyzer = new ConstructorTailAnalyzer();

    @Test
    void provesSimplePostSuperTailAndHelperBoundary() {
        final MethodNode constructor = constructor("(I)V");
        constructor.node.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.node.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        constructor.node.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.node.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        constructor.node.instructions.add(new FieldInsnNode(
                Opcodes.PUTFIELD, "example/Tail", "value", "I"));
        constructor.node.instructions.add(new InsnNode(Opcodes.RETURN));
        constructor.node.maxLocals = 2;
        constructor.node.maxStack = 2;
        final ControlFlowGraph cfg = ControlFlowGraphBuilder.build(constructor);

        final ConstructorTailAnalyzer.Result result = analyzer.analyze(constructor, cfg);

        assertTrue(result.supported(), result.reason());
        assertEquals("(Lexample/Tail;I)V", result.helperDescriptor());
        assertFalse(result.boundaryBlock().isBlank());
        assertFalse(result.tailBlocks().isEmpty());
        assertTrue(result.firstTailStatement() > 0);

        final NativeFunction function = new MapleNativeIrLowerer().lowerConstructorTail(
                "constructor", "skid_ctor_tail", "skid$native$ctor",
                constructor, cfg, result, NativeBackend.AOT).functions().get(0);
        assertEquals("skid$native$ctor", function.javaName());
        assertEquals("(Lexample/Tail;I)V", function.javaDescriptor());
        assertEquals(NativeType.Primitive.VOID, function.returnType());
        assertEquals("true", function.metadata().get("constructor.tail"));
        assertTrue(function.blocks().stream().flatMap(block -> block.instructions().stream())
                .filter(NativeInstruction.Operation.class::isInstance)
                .map(NativeInstruction.Operation.class::cast)
                .map(NativeInstruction.Operation::opcode)
                .noneMatch(opcode -> opcode == NativeOpcode.JAVA_CALL),
                "the native tail must not repeat the mandatory super constructor call");
    }

    @Test
    void rejectsTailThatConsumesValueComputedBeforeSuper() {
        final MethodNode constructor = constructor("(I)V");
        constructor.node.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false));
        constructor.node.instructions.add(new InsnNode(Opcodes.L2I));
        constructor.node.instructions.add(new VarInsnNode(Opcodes.ISTORE, 2));
        constructor.node.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.node.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        constructor.node.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.node.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        constructor.node.instructions.add(new FieldInsnNode(
                Opcodes.PUTFIELD, "example/Tail", "value", "I"));
        constructor.node.instructions.add(new InsnNode(Opcodes.RETURN));
        constructor.node.maxLocals = 3;
        constructor.node.maxStack = 2;
        final ControlFlowGraph cfg = ControlFlowGraphBuilder.build(constructor);

        final ConstructorTailAnalyzer.Result result = analyzer.analyze(constructor, cfg);

        assertFalse(result.supported(), () -> result + "\n" + cfg);
        assertTrue(result.reason().contains("prefix SSA values"), result.reason());
    }

    @Test
    void rejectsAmbiguousDoubleInitializationAndNonConstructors() {
        final MethodNode constructor = constructor("()V");
        constructor.node.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.node.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        constructor.node.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.node.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        constructor.node.instructions.add(new InsnNode(Opcodes.RETURN));
        constructor.node.maxLocals = 1;
        constructor.node.maxStack = 1;
        final ConstructorTailAnalyzer.Result ambiguous = analyzer.analyze(
                constructor, ControlFlowGraphBuilder.build(constructor));
        assertFalse(ambiguous.supported());
        assertTrue(ambiguous.reason().contains("exactly one"), ambiguous.reason());

        final MethodNode ordinary = method("run", "()V");
        ordinary.node.instructions.add(new InsnNode(Opcodes.RETURN));
        final ConstructorTailAnalyzer.Result notConstructor = analyzer.analyze(
                ordinary, ControlFlowGraphBuilder.build(ordinary));
        assertFalse(notConstructor.supported());
        assertTrue(notConstructor.reason().contains("not a constructor"));
    }

    private static MethodNode constructor(final String descriptor) {
        return method("<init>", descriptor);
    }

    private static MethodNode method(final String name, final String descriptor) {
        final ClassNode owner = new ClassNode();
        owner.node.version = Opcodes.V1_8;
        owner.node.access = Opcodes.ACC_PUBLIC;
        owner.node.name = "example/Tail";
        owner.node.superName = "java/lang/Object";
        final org.objectweb.asm.tree.MethodNode raw = new org.objectweb.asm.tree.MethodNode(
                Opcodes.ASM9, Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        final MethodNode method = new MethodNode(raw, owner);
        owner.addMethod(method);
        return method;
    }
}
