package dev.skidfuscator.test.nativebackend;

import dev.skidfuscator.nativeir.NativeBackend;
import dev.skidfuscator.nativeir.NativeFunction;
import dev.skidfuscator.nativeir.NativeInstruction;
import dev.skidfuscator.nativeir.NativeModule;
import dev.skidfuscator.nativeir.NativeOpcode;
import dev.skidfuscator.nativeir.NativeTerminator;
import dev.skidfuscator.nativetoolchain.CanonicalLlvmIrEmitter;
import dev.skidfuscator.obfuscator.nativebackend.lowering.MapleNativeIrLowerer;
import dev.skidfuscator.obfuscator.nativebackend.lowering.JavaLinkageBridgeRegistry;
import dev.skidfuscator.obfuscator.nativebackend.lowering.NativeLoweringException;
import org.junit.jupiter.api.Test;
import org.mapleir.asm.ClassNode;
import org.mapleir.asm.MethodNode;
import org.mapleir.ir.cfg.ControlFlowGraph;
import org.mapleir.ir.cfg.builder.ControlFlowGraphBuilder;
import org.mapleir.ir.code.stmt.LineNumberStmt;
import org.objectweb.asm.Handle;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapleNativeIrLowererTest {
    private final MapleNativeIrLowerer lowerer = new MapleNativeIrLowerer();

    @Test
    void lowersPrivateStaticStringLiteralWithoutMutatingAsm() {
        final String flag = "skid\0\uD83D\uDE80\uDFFF";
        final MethodNode method = method(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "()Ljava/lang/String;", flag);
        final AbstractInsnNode[] instructionsBefore = method.node.instructions.toArray();
        final int accessBefore = method.node.access;

        final NativeModule module = lowerer.lower("secrets", "native_revealFlag", method, NativeBackend.AOT);

        assertArrayEquals(instructionsBefore, method.node.instructions.toArray());
        assertEquals(accessBefore, method.node.access);
        assertEquals(1, module.functions().size());
        final NativeFunction function = module.functions().get(0);
        assertEquals("native_revealFlag", function.symbol());
        assertEquals("example/Secrets", function.javaOwner());
        assertEquals("revealFlag", function.javaName());
        assertTrue(function.requiresSemanticContext());
        assertEquals("jni", function.metadata().get("semanticContext"));
        assertEquals("true", function.metadata().get("java.static"));
        assertTrue(function.parameters().isEmpty());
        final NativeInstruction.Operation literal = assertInstanceOf(
                NativeInstruction.Operation.class, function.blocks().get(0).instructions().get(0));
        assertEquals(NativeOpcode.STRING_CONSTANT, literal.opcode());
        assertEquals(flag, literal.attributes().get("value"));
        assertInstanceOf(NativeTerminator.Return.class,
                function.blocks().get(0).terminator().orElseThrow());

        final String llvm = new String(new CanonicalLlvmIrEmitter().emit(module), StandardCharsets.UTF_8);
        assertTrue(llvm.contains("define hidden ptr @\"native_revealFlag\"(ptr %\"skid.semantic.context\")"));
        assertTrue(llvm.contains("[8 x i16] [i16 115, i16 107, i16 105, i16 100, i16 0, "
                + "i16 55357, i16 56960, i16 57343]"));
        assertTrue(llvm.contains("call ptr @\"skid.semantic.string_constant.v1\""));
    }

    @Test
    void rejectsEveryInstructionShapeOutsideLiteralReturnBeforeMutation() {
        final MethodNode method = method(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "()Ljava/lang/String;", "flag");
        method.node.instructions.insertBefore(
                method.node.instructions.getLast(), new InsnNode(Opcodes.NOP));
        final AbstractInsnNode[] instructionsBefore = method.node.instructions.toArray();

        final NativeLoweringException exception = assertThrows(
                NativeLoweringException.class,
                () -> lowerer.lower("bad", "bad_symbol", method, NativeBackend.AOT));

        assertTrue(exception.getMessage().contains("exactly LDC <String>; ARETURN"));
        assertArrayEquals(instructionsBefore, method.node.instructions.toArray());
        assertFalse(lowerer.check(method).supported());
    }

    @Test
    void asmCompatibilityPathAcceptsAnyStaticVisibilityButRejectsOtherShapes() {
        final MethodNode publicMethod = method(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "()Ljava/lang/String;", "flag");
        assertTrue(lowerer.check(publicMethod).supported());

        final List<MethodNode> unsupported = List.of(
                method(Opcodes.ACC_PRIVATE, "()Ljava/lang/String;", "flag"),
                method(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "(I)Ljava/lang/String;", "flag"),
                method(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "()Ljava/lang/Object;", "flag")
        );

        for (final MethodNode method : unsupported) {
            assertThrows(NativeLoweringException.class,
                    () -> lowerer.lower("bad", "bad_symbol", method, NativeBackend.AOT));
            assertFalse(lowerer.check(method).supported());
        }
    }

    @Test
    void lowersFinalizedSsaArithmeticBranchesPhiAndSourceLines() {
        final MethodNode method = rawMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "compute", "(II)I");
        final LabelNode start = new LabelNode();
        final LabelNode join = new LabelNode();
        method.node.instructions.add(start);
        method.node.instructions.add(new LineNumberNode(12, start));
        method.node.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.node.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.node.instructions.add(new InsnNode(Opcodes.IADD));
        method.node.instructions.add(new InsnNode(Opcodes.ICONST_3));
        method.node.instructions.add(new InsnNode(Opcodes.IMUL));
        method.node.instructions.add(new VarInsnNode(Opcodes.ISTORE, 2));
        method.node.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        method.node.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 10));
        method.node.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPLE, join));
        method.node.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        method.node.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.node.instructions.add(new InsnNode(Opcodes.IXOR));
        method.node.instructions.add(new VarInsnNode(Opcodes.ISTORE, 2));
        method.node.instructions.add(join);
        method.node.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        method.node.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.node.maxLocals = 3;
        method.node.maxStack = 2;

        final NativeFunction function = lower(method).functions().get(0);
        final Set<NativeOpcode> opcodes = opcodes(function);
        assertTrue(opcodes.containsAll(Set.of(NativeOpcode.ADD, NativeOpcode.MUL, NativeOpcode.BIT_XOR)));
        assertTrue(function.blocks().stream()
                .anyMatch(block -> block.terminator().orElseThrow() instanceof NativeTerminator.ConditionalBranch));
        assertTrue(function.blocks().stream().flatMap(block -> block.instructions().stream())
                .anyMatch(NativeInstruction.Phi.class::isInstance));
        assertTrue(function.blocks().stream().flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction.sourceLocation().line() == 12),
                () -> function.blocks().stream().flatMap(block -> block.instructions().stream())
                        .map(instruction -> instruction.sourceLocation().toString())
                        .collect(Collectors.joining(", ")));
    }

    @Test
    void representsArraysFieldsCallsAndMonitorsAsSemanticOperations() {
        final MethodNode method = rawMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "semantic", "([Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        method.node.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.node.instructions.add(new InsnNode(Opcodes.DUP));
        method.node.instructions.add(new InsnNode(Opcodes.MONITORENTER));
        method.node.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.node.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.node.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.node.instructions.add(new InsnNode(Opcodes.AASTORE));
        method.node.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.node.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.node.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.node.instructions.add(new InsnNode(Opcodes.ARRAYLENGTH));
        method.node.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/util/Objects", "checkIndex", "(II)I", false));
        method.node.instructions.add(new InsnNode(Opcodes.AALOAD));
        method.node.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC,
                "example/Secrets", "last", "Ljava/lang/Object;"));
        method.node.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC,
                "example/Secrets", "last", "Ljava/lang/Object;"));
        method.node.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/util/Objects", "requireNonNull",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false));
        method.node.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.node.instructions.add(new InsnNode(Opcodes.MONITOREXIT));
        method.node.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.node.maxLocals = 2;
        method.node.maxStack = 3;

        final NativeFunction function = lower(method).functions().get(0);
        assertTrue(function.requiresSemanticContext());
        assertTrue(opcodes(function).containsAll(Set.of(
                NativeOpcode.MONITOR_ENTER, NativeOpcode.MONITOR_EXIT,
                NativeOpcode.ARRAY_STORE, NativeOpcode.ARRAY_LENGTH, NativeOpcode.ARRAY_LOAD,
                NativeOpcode.FIELD_GET, NativeOpcode.FIELD_SET, NativeOpcode.JAVA_CALL)),
                () -> opcodes(function).toString());
    }

    @Test
    void preservesExceptionEdgesAndCaughtExceptionValues() {
        final MethodNode method = rawMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "parse", "(Ljava/lang/String;)I");
        final LabelNode start = new LabelNode();
        final LabelNode end = new LabelNode();
        final LabelNode handler = new LabelNode();
        method.node.instructions.add(start);
        method.node.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.node.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/Integer", "parseInt", "(Ljava/lang/String;)I", false));
        method.node.instructions.add(end);
        method.node.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.node.instructions.add(handler);
        method.node.instructions.add(new InsnNode(Opcodes.POP));
        method.node.instructions.add(new InsnNode(Opcodes.ICONST_M1));
        method.node.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.node.tryCatchBlocks.add(new TryCatchBlockNode(
                start, end, handler, "java/lang/NumberFormatException"));
        method.node.maxLocals = 1;
        method.node.maxStack = 1;

        final NativeFunction function = lower(method).functions().get(0);
        assertTrue(function.blocks().stream().mapToInt(block -> block.exceptionEdges().size()).sum() > 0);
        assertTrue(opcodes(function).contains(NativeOpcode.CATCH_EXCEPTION));
    }

    @Test
    void preservesCompleteInvokeDynamicLinkageMetadata() {
        final MethodNode method = rawMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "factory", "()Ljava/lang/Runnable;");
        final Handle bootstrap = new Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;",
                false);
        final Handle implementation = new Handle(
                Opcodes.H_INVOKESTATIC, "example/Secrets", "lambda$0", "()V", false);
        method.node.instructions.add(new InvokeDynamicInsnNode(
                "run", "()Ljava/lang/Runnable;", bootstrap,
                Type.getMethodType("()V"), implementation, Type.getMethodType("()V")));
        method.node.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.node.maxLocals = 0;
        method.node.maxStack = 1;

        final NativeInstruction.Operation dynamic = lower(method).functions().get(0).blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(NativeInstruction.Operation.class::isInstance)
                .map(NativeInstruction.Operation.class::cast)
                .filter(operation -> operation.opcode() == NativeOpcode.DYNAMIC_BRIDGE)
                .findFirst().orElseThrow();
        assertEquals("invokedynamic", dynamic.attributes().get("bridge"));
        assertEquals("3", dynamic.attributes().get("bootstrap.arg.count"));
        assertTrue(dynamic.attributes().get("bootstrap").startsWith("h:"));
        assertTrue(dynamic.attributes().get("bootstrap.arg.1").startsWith("h:"));
    }

    @Test
    void stagesJavaOwnedInvokeDynamicAndCallerSensitiveHelpers() {
        final MethodNode method = rawMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "factory", "()Ljava/lang/Runnable;");
        final Handle bootstrap = new Handle(
                Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        method.node.instructions.add(new InvokeDynamicInsnNode(
                "run", "()Ljava/lang/Runnable;", bootstrap,
                Type.getMethodType("()V"),
                new Handle(Opcodes.H_INVOKESTATIC, "example/Secrets", "lambda$0", "()V", false),
                Type.getMethodType("()V")));
        method.node.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.node.maxStack = 1;

        final JavaLinkageBridgeRegistry registry = new JavaLinkageBridgeRegistry("test");
        final ControlFlowGraph cfg = ControlFlowGraphBuilder.build(method);
        final NativeFunction function = new MapleNativeIrLowerer(registry)
                .lower("bridges", "native_factory", method, cfg, NativeBackend.AOT)
                .functions().get(0);
        final NativeInstruction.Operation dynamic = function.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(NativeInstruction.Operation.class::isInstance)
                .map(NativeInstruction.Operation.class::cast)
                .filter(operation -> operation.opcode() == NativeOpcode.DYNAMIC_BRIDGE)
                .findFirst().orElseThrow();

        assertEquals("java.helper.v1", dynamic.attributes().get("bridge"));
        assertEquals("invokedynamic", dynamic.attributes().get("helper.kind"));
        assertEquals("example/Secrets", dynamic.attributes().get("helper.owner"));
        assertEquals(dynamic.attributes().get("descriptor"), dynamic.attributes().get("helper.descriptor"));
        assertEquals(1, registry.generatedHelpers().size());
        assertTrue(registry.generatedHelpers().get(0).method().instructions.getFirst()
                instanceof InvokeDynamicInsnNode);

        final MethodNode lookup = rawMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "lookup", "()Ljava/lang/invoke/MethodHandles$Lookup;");
        lookup.node.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/invoke/MethodHandles", "lookup",
                "()Ljava/lang/invoke/MethodHandles$Lookup;", false));
        lookup.node.instructions.add(new InsnNode(Opcodes.ARETURN));
        lookup.node.maxStack = 1;
        final JavaLinkageBridgeRegistry callerRegistry = new JavaLinkageBridgeRegistry("caller");
        final NativeFunction callerFunction = new MapleNativeIrLowerer(callerRegistry)
                .lower("caller", "native_lookup", lookup,
                        ControlFlowGraphBuilder.build(lookup), NativeBackend.AOT)
                .functions().get(0);
        final NativeInstruction.Operation callerBridge = callerFunction.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(NativeInstruction.Operation.class::isInstance)
                .map(NativeInstruction.Operation.class::cast)
                .filter(operation -> operation.opcode() == NativeOpcode.DYNAMIC_BRIDGE)
                .findFirst().orElseThrow();
        assertEquals("caller-sensitive", callerBridge.attributes().get("helper.kind"));
        assertEquals(1, callerRegistry.generatedHelpers().size());
        assertTrue(callerRegistry.generatedHelpers().get(0).method().instructions.getFirst()
                instanceof MethodInsnNode);
    }

    @Test
    void stagesMethodTypeHandleAndConstantDynamicLdcHelpers() {
        final MethodNode caller = rawMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "constants", "()V");
        final JavaLinkageBridgeRegistry registry = new JavaLinkageBridgeRegistry("constants");
        final Type methodType = Type.getMethodType("(I)Ljava/lang/String;");
        final Handle methodHandle = new Handle(
                Opcodes.H_INVOKESTATIC, "example/Secrets", "target", "()V", false);
        final ConstantDynamic dynamic = new ConstantDynamic(
                "value", "Ljava/lang/Object;",
                new Handle(Opcodes.H_INVOKESTATIC,
                        "java/lang/invoke/ConstantBootstraps", "nullConstant",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)"
                                + "Ljava/lang/Object;", false));

        registry.constant(caller, methodType, "()Ljava/lang/invoke/MethodType;");
        registry.constant(caller, methodHandle, "()Ljava/lang/invoke/MethodHandle;");
        registry.constant(caller, dynamic, "()Ljava/lang/Object;");

        assertEquals(List.of("method-type-or-class", "method-handle", "constant-dynamic"),
                registry.generatedHelpers().stream().map(value -> value.kind()).toList());
        assertTrue(registry.generatedHelpers().stream().allMatch(helper ->
                helper.method().instructions.getFirst() instanceof LdcInsnNode));
    }

    @Test
    void keepsExplicitArrayCheckCastDescriptorDistinctFromReferenceCoercion() {
        final MethodNode method = rawMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "chars", "(Ljava/lang/Object;)[C");
        method.node.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.node.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "[C"));
        method.node.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.node.maxLocals = 1;
        method.node.maxStack = 1;

        final List<NativeInstruction.Operation> operations = lower(method).functions().get(0).blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(NativeInstruction.Operation.class::isInstance)
                .map(NativeInstruction.Operation.class::cast)
                .toList();
        final NativeInstruction.Operation check = operations.stream()
                .filter(operation -> operation.opcode() == NativeOpcode.CHECK_CAST)
                .findFirst().orElseThrow();
        assertEquals("[C", check.attributes().get("type"));
        assertTrue(operations.stream().noneMatch(operation -> operation.opcode() == NativeOpcode.CHECK_CAST
                && "[S".equals(operation.attributes().get("type"))));
    }

    @Test
    void lowersAllocationConstructorsNewArraysInstanceOfAndThrow() {
        final MethodNode construct = rawMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "construct", "()Ljava/lang/StringBuilder;");
        construct.node.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        construct.node.instructions.add(new InsnNode(Opcodes.DUP));
        construct.node.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/lang/StringBuilder", "<init>", "()V", false));
        construct.node.instructions.add(new InsnNode(Opcodes.ARETURN));
        construct.node.maxStack = 2;

        assertTrue(opcodes(lower(construct).functions().get(0)).containsAll(
                Set.of(NativeOpcode.NEW_OBJECT, NativeOpcode.JAVA_CALL)));

        final MethodNode array = rawMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "array", "(I)[I");
        array.node.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        array.node.instructions.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        array.node.instructions.add(new InsnNode(Opcodes.ARETURN));
        array.node.maxLocals = 1;
        array.node.maxStack = 1;
        assertTrue(opcodes(lower(array).functions().get(0)).contains(NativeOpcode.ARRAY_NEW));

        final MethodNode instanceOf = rawMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "isString", "(Ljava/lang/Object;)Z");
        instanceOf.node.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instanceOf.node.instructions.add(new TypeInsnNode(Opcodes.INSTANCEOF, "java/lang/String"));
        instanceOf.node.instructions.add(new InsnNode(Opcodes.IRETURN));
        instanceOf.node.maxLocals = 1;
        instanceOf.node.maxStack = 1;
        assertTrue(opcodes(lower(instanceOf).functions().get(0)).contains(NativeOpcode.INSTANCE_OF));

        final MethodNode throwing = rawMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "rethrow", "(Ljava/lang/Throwable;)V");
        throwing.node.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        throwing.node.instructions.add(new InsnNode(Opcodes.ATHROW));
        throwing.node.maxLocals = 1;
        throwing.node.maxStack = 1;
        assertTrue(lower(throwing).functions().get(0).blocks().stream()
                .anyMatch(block -> block.terminator().orElseThrow() instanceof NativeTerminator.Throw));
    }

    @Test
    void integerDivisionRequestsSemanticContextForArithmeticExceptionDispatch() {
        final MethodNode division = rawMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "divide", "(II)I");
        division.node.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        division.node.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        division.node.instructions.add(new InsnNode(Opcodes.IDIV));
        division.node.instructions.add(new InsnNode(Opcodes.IRETURN));
        division.node.maxLocals = 2;
        division.node.maxStack = 2;

        final NativeModule module = lower(division);
        final NativeFunction function = module.functions().get(0);

        assertTrue(function.requiresSemanticContext());
        assertEquals("jni", function.metadata().get("semanticContext"));
        assertTrue(opcodes(function).contains(NativeOpcode.SDIV));
        final String llvm = new String(new CanonicalLlvmIrEmitter().emit(module), StandardCharsets.UTF_8);
        assertTrue(llvm.contains("JAVA_SDIV_GUARD"));
        assertTrue(llvm.contains("skid.exception.escape"));
    }

    @Test
    void preservesJvmFloatingPointThenNarrowIntegerCastOrder() {
        final MethodNode byteCast = rawMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "byteCast", "()B");
        byteCast.node.instructions.add(new LdcInsnNode(1000.0d));
        byteCast.node.instructions.add(new InsnNode(Opcodes.D2I));
        byteCast.node.instructions.add(new InsnNode(Opcodes.I2B));
        byteCast.node.instructions.add(new InsnNode(Opcodes.IRETURN));
        byteCast.node.maxStack = 2;

        final String byteLlvm = new String(
                new CanonicalLlvmIrEmitter().emit(lower(byteCast)), StandardCharsets.UTF_8);
        assertTrue(byteLlvm.contains("fptosi double"));
        assertTrue(byteLlvm.contains(" to i32"));
        assertTrue(byteLlvm.contains("trunc i32"));
        assertTrue(byteLlvm.contains(" to i8"));

        final MethodNode shortCast = rawMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "shortCast", "()S");
        shortCast.node.instructions.add(new LdcInsnNode(1.0e9d));
        shortCast.node.instructions.add(new InsnNode(Opcodes.D2I));
        shortCast.node.instructions.add(new InsnNode(Opcodes.I2S));
        shortCast.node.instructions.add(new InsnNode(Opcodes.IRETURN));
        shortCast.node.maxStack = 2;

        final String shortLlvm = new String(
                new CanonicalLlvmIrEmitter().emit(lower(shortCast)), StandardCharsets.UTF_8);
        assertTrue(shortLlvm.contains("fptosi double"));
        assertTrue(shortLlvm.contains(" to i32"));
        assertTrue(shortLlvm.contains("trunc i32"));
        assertTrue(shortLlvm.contains(" to i16"));
        assertEquals((byte) 1000, -24);
        assertEquals((short) 1_000_000_000, -13824);
    }

    private NativeModule lower(final MethodNode method) {
        final ControlFlowGraph cfg = ControlFlowGraphBuilder.build(method);
        if (method.getName().equals("compute")) {
            // The generic builder's optimization pass currently drops line nodes at method entry;
            // finalized Skid graphs can and do retain LineNumberStmt metadata, which is what the
            // lowerer consumes. Add it directly so this test isolates the lowering contract.
            cfg.vertices().forEach(block -> block.add(0, new LineNumberStmt(12)));
        }
        return lowerer.lower("cfg-test", "native_" + method.getName(), method, cfg, NativeBackend.AOT);
    }

    private static Set<NativeOpcode> opcodes(final NativeFunction function) {
        return function.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(NativeInstruction.Operation.class::isInstance)
                .map(NativeInstruction.Operation.class::cast)
                .map(NativeInstruction.Operation::opcode)
                .collect(Collectors.toSet());
    }

    private static MethodNode rawMethod(final int access, final String name, final String descriptor) {
        final ClassNode owner = new ClassNode();
        owner.node.name = "example/Secrets";
        owner.node.sourceFile = "Secrets.java";
        owner.node.access = Opcodes.ACC_PUBLIC;
        final org.objectweb.asm.tree.MethodNode raw = new org.objectweb.asm.tree.MethodNode(
                access, name, descriptor, null, null);
        final MethodNode method = new MethodNode(raw, owner);
        owner.addMethod(method);
        return method;
    }

    private static MethodNode method(final int access, final String descriptor, final String value) {
        final ClassNode owner = new ClassNode();
        owner.node.name = "example/Secrets";
        owner.node.sourceFile = "Secrets.java";
        owner.node.access = Opcodes.ACC_PUBLIC;
        final org.objectweb.asm.tree.MethodNode raw = new org.objectweb.asm.tree.MethodNode(
                access, "revealFlag", descriptor, null, null);
        final LabelNode label = new LabelNode();
        raw.instructions.add(label);
        raw.instructions.add(new LineNumberNode(37, label));
        raw.instructions.add(new LdcInsnNode(value));
        raw.instructions.add(new InsnNode(Opcodes.ARETURN));
        final MethodNode method = new MethodNode(raw, owner);
        owner.addMethod(method);
        return method;
    }
}
