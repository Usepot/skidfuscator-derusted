package dev.skidfuscator.obfuscator.nativebackend;

import dev.skidfuscator.config.nativeobfuscation.NativeMode;
import dev.skidfuscator.nativeir.NativeBackend;
import dev.skidfuscator.nativeir.NativeBlock;
import dev.skidfuscator.nativeir.NativeFunction;
import dev.skidfuscator.nativeir.NativeParameter;
import dev.skidfuscator.nativeir.NativeTerminator;
import dev.skidfuscator.nativeir.NativeType;
import dev.skidfuscator.obfuscator.nativebackend.selection.NativeSelection;
import dev.skidfuscator.obfuscator.nativebackend.selection.NativeSelectionSource;
import dev.skidfuscator.obfuscator.nativebackend.lowering.ConstructorTailAnalyzer;
import dev.skidfuscator.obfuscator.renamer.SkidRemapper;
import org.junit.jupiter.api.Test;
import org.mapleir.asm.ClassHelper;
import org.mapleir.asm.ClassNode;
import org.mapleir.asm.MethodNode;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.topdank.byteengineer.commons.data.JarClassData;
import org.topdank.byteengineer.commons.data.JarContents;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NativeWrapperPlannerTest {
    @Test
    void constructorWrapperKeepsInitializationAndCallsOnlyTheLoweredTail() {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "sample/Constructed", null,
                "java/lang/Object", null);
        final org.objectweb.asm.MethodVisitor visitor = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "(I)V", null, null);
        visitor.visitCode();
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        visitor.visitVarInsn(Opcodes.ILOAD, 1);
        visitor.visitFieldInsn(Opcodes.PUTFIELD, "sample/Constructed", "value", "I");
        visitor.visitInsn(Opcodes.RETURN);
        visitor.visitMaxs(2, 2);
        visitor.visitEnd();
        writer.visitEnd();
        final byte[] bytes = writer.toByteArray();
        final ClassNode owner = ClassHelper.create(bytes);
        final MethodNode constructor = owner.getMethods().stream().filter(MethodNode::isInit)
                .findFirst().orElseThrow();
        final JarContents contents = new JarContents();
        contents.getClassContents().add(new JarClassData("sample/Constructed.class", bytes, owner));
        final NativeCompilationPlan.Candidate candidate = new NativeCompilationPlan.Candidate(
                new NativeSelection(constructor, NativeMode.AOT,
                        NativeSelectionSource.ANNOTATION_EXPLICIT, true),
                NativeEligibility.Conversion.WRAPPER_REQUIRED);
        final String helperDescriptor = "(Lsample/Constructed;I)V";
        final NativeFunction function = new NativeFunction(
                "symbol", "sample/Constructed", "skid$native$abc$0", helperDescriptor,
                NativeType.Primitive.VOID,
                List.of(new NativeParameter("arg0", new NativeType.Reference("sample/Constructed", true), 0),
                        new NativeParameter("arg1", NativeType.Primitive.I32, 1)),
                "entry", NativeBackend.AOT, false, false, Map.of())
                .addBlock(new NativeBlock("entry").terminate(NativeTerminator.Return.voidReturn()));
        final ConstructorTailAnalyzer.Result proof = new ConstructorTailAnalyzer.Result(
                true, "", "b1", 1, List.of("b1"), helperDescriptor,
                "java/lang/Object", "()V");

        final NativeWrapperPlanner.Prepared prepared = new NativeWrapperPlanner(
                contents, "abc", new SkidRemapper(new HashMap<>()))
                .prepareConstructor(candidate, function, proof, 0);

        final List<MethodInsnNode> calls = java.util.stream.StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(
                                prepared.mutation().wrapperTemplate().instructions.iterator(), 0), false)
                .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).toList();
        assertEquals(2, calls.size());
        assertEquals("<init>", calls.get(0).name);
        assertEquals(Opcodes.INVOKESTATIC, calls.get(1).getOpcode());
        assertEquals("skid$native$abc$0", calls.get(1).name);
        assertEquals(helperDescriptor, calls.get(1).desc);
    }

    @Test
    void constructorWrapperIgnoresAnUnrelatedSameOwnerInitialization() {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "sample/Delegating", null,
                "java/lang/Object", null);
        final org.objectweb.asm.MethodVisitor visitor = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "(I)V", null, null);
        visitor.visitCode();
        visitor.visitTypeInsn(Opcodes.NEW, "sample/Delegating");
        visitor.visitInsn(Opcodes.DUP);
        visitor.visitInsn(Opcodes.ICONST_0);
        visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "sample/Delegating", "<init>", "(Z)V", false);
        visitor.visitInsn(Opcodes.POP);
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        visitor.visitVarInsn(Opcodes.ILOAD, 1);
        visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "sample/Delegating", "<init>", "(Z)V", false);
        visitor.visitInsn(Opcodes.RETURN);
        visitor.visitMaxs(3, 2);
        visitor.visitEnd();
        final org.objectweb.asm.MethodVisitor delegated = writer.visitMethod(
                Opcodes.ACC_PRIVATE, "<init>", "(Z)V", null, null);
        delegated.visitCode();
        delegated.visitVarInsn(Opcodes.ALOAD, 0);
        delegated.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        delegated.visitInsn(Opcodes.RETURN);
        delegated.visitMaxs(1, 2);
        delegated.visitEnd();
        writer.visitEnd();
        final byte[] bytes = writer.toByteArray();
        final ClassNode owner = ClassHelper.create(bytes);
        final MethodNode constructor = owner.getMethods().stream()
                .filter(MethodNode::isInit).filter(value -> value.getDesc().equals("(I)V"))
                .findFirst().orElseThrow();
        final JarContents contents = new JarContents();
        contents.getClassContents().add(new JarClassData("sample/Delegating.class", bytes, owner));
        final NativeCompilationPlan.Candidate candidate = new NativeCompilationPlan.Candidate(
                new NativeSelection(constructor, NativeMode.AOT,
                        NativeSelectionSource.ANNOTATION_EXPLICIT, true),
                NativeEligibility.Conversion.WRAPPER_REQUIRED);
        final String helperDescriptor = "(Lsample/Delegating;I)V";
        final NativeFunction function = new NativeFunction(
                "symbol", "sample/Delegating", "skid$native$abc$0", helperDescriptor,
                NativeType.Primitive.VOID,
                List.of(new NativeParameter("arg0", new NativeType.Reference("sample/Delegating", true), 0),
                        new NativeParameter("arg1", NativeType.Primitive.I32, 1)),
                "entry", NativeBackend.AOT, false, false, Map.of())
                .addBlock(new NativeBlock("entry").terminate(NativeTerminator.Return.voidReturn()));
        final ConstructorTailAnalyzer.Result proof = new ConstructorTailAnalyzer.Result(
                true, "", "b1", 1, List.of("b1"), helperDescriptor,
                "sample/Delegating", "(Z)V");

        final NativeWrapperPlanner.Prepared prepared = new NativeWrapperPlanner(
                contents, "abc", new SkidRemapper(new HashMap<>()))
                .prepareConstructor(candidate, function, proof, 0);

        final List<MethodInsnNode> calls = java.util.stream.StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(
                                prepared.mutation().wrapperTemplate().instructions.iterator(), 0), false)
                .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).toList();
        assertEquals(3, calls.size());
        assertEquals("sample/Delegating", calls.get(0).owner);
        assertEquals("(Z)V", calls.get(0).desc);
        assertEquals("sample/Delegating", calls.get(1).owner);
        assertEquals("(Z)V", calls.get(1).desc);
        assertEquals(Opcodes.INVOKESTATIC, calls.get(2).getOpcode());
    }

    @Test
    void routesDefaultInterfaceMethodsThroughANonInterfaceNativeCompanion() {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                "sample/Service", null, "java/lang/Object", null);
        final org.objectweb.asm.MethodVisitor visitor = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "value", "(I)I", null, null);
        visitor.visitCode();
        visitor.visitVarInsn(Opcodes.ILOAD, 1);
        visitor.visitInsn(Opcodes.IRETURN);
        visitor.visitMaxs(1, 2);
        visitor.visitEnd();
        writer.visitEnd();
        final byte[] bytes = writer.toByteArray();
        final ClassNode owner = ClassHelper.create(bytes);
        final MethodNode method = owner.getMethods().stream().filter(value -> value.getName().equals("value"))
                .findFirst().orElseThrow();
        final JarContents contents = new JarContents();
        contents.getClassContents().add(new JarClassData("sample/Service.class", bytes, owner));
        final NativeCompilationPlan.Candidate candidate = new NativeCompilationPlan.Candidate(
                new NativeSelection(method, NativeMode.AOT, NativeSelectionSource.ANNOTATION_EXPLICIT, true),
                NativeEligibility.Conversion.WRAPPER_REQUIRED);
        final NativeType.Reference receiver = new NativeType.Reference("sample/Service", true);
        final NativeFunction function = new NativeFunction(
                "symbol", "sample/Service", "value", "(I)I", NativeType.Primitive.I32,
                List.of(new NativeParameter("arg0", receiver, 0),
                        new NativeParameter("arg1", NativeType.Primitive.I32, 1)),
                "entry", NativeBackend.AOT, false, false, Map.of())
                .addBlock(new NativeBlock("entry").terminate(NativeTerminator.Return.voidReturn()));

        final NativeWrapperPlanner planner = new NativeWrapperPlanner(
                contents, "abc", new SkidRemapper(new HashMap<>()));
        final NativeWrapperPlanner.Prepared prepared = planner.prepare(candidate, function, 0);

        assertFalse((prepared.mutation().helperOwner().node.access & Opcodes.ACC_INTERFACE) != 0);
        assertEquals("(Lsample/Service;I)I", prepared.mutation().nativeHelper().desc);
        assertEquals(prepared.mutation().nativeHelper().desc, prepared.function().javaDescriptor());
        final MethodInsnNode invocation = (MethodInsnNode)
                prepared.mutation().wrapperTemplate().instructions.get(2);
        assertEquals(Opcodes.INVOKESTATIC, invocation.getOpcode());
        assertEquals(prepared.mutation().helperOwner().getName(), invocation.owner);
        assertEquals(1, planner.generatedCompanions().size());
    }
}
