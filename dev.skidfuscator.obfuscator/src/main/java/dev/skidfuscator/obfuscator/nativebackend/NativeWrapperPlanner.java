package dev.skidfuscator.obfuscator.nativebackend;

import dev.skidfuscator.nativeir.NativeFunction;
import dev.skidfuscator.obfuscator.nativebackend.commit.NativeMethodCommitTransaction;
import dev.skidfuscator.obfuscator.nativebackend.lowering.ConstructorTailAnalyzer;
import org.mapleir.asm.ClassHelper;
import org.mapleir.asm.ClassNode;
import org.mapleir.asm.MethodNode;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.topdank.byteengineer.commons.data.JarClassData;
import org.topdank.byteengineer.commons.data.JarContents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds verifier-valid Java 8 wrappers and code-free native helpers after lowering. */
final class NativeWrapperPlanner {
    private final JarContents contents;
    private final String randomId;
    private final Remapper remapper;
    private final Map<ClassNode, Companion> companions = new LinkedHashMap<>();

    NativeWrapperPlanner(final JarContents contents, final String randomId, final Remapper remapper) {
        this.contents = Objects.requireNonNull(contents, "contents");
        this.randomId = Objects.requireNonNull(randomId, "randomId");
        this.remapper = Objects.requireNonNull(remapper, "remapper");
    }

    Prepared prepare(
            final NativeCompilationPlan.Candidate candidate,
            final NativeFunction lowered,
            final int ordinal
    ) {
        final MethodNode method = candidate.selection().getMethod();
        if (method.isInit()) throw new IllegalArgumentException("Use prepareConstructor for constructors");
        final boolean ownerInterface = (method.owner.node.access & Opcodes.ACC_INTERFACE) != 0;
        final String helperName = helperName(ordinal);
        final ClassNode helperOwner;
        final String helperDescriptor;
        if (ownerInterface) {
            helperOwner = companion(method.owner).node();
            helperDescriptor = method.isStatic()
                    ? method.getDesc() : prependReceiver(method.owner.getName(), method.getDesc());
        } else if (method.isClinit()) {
            helperOwner = method.owner;
            helperDescriptor = "()V";
        } else {
            throw new IllegalArgumentException("Unexpected wrapper conversion for " + method);
        }

        final org.objectweb.asm.tree.MethodNode helper = new org.objectweb.asm.tree.MethodNode(
                Opcodes.ASM9, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_NATIVE,
                helperName, helperDescriptor, null, null);
        final org.objectweb.asm.tree.MethodNode wrapper = wrapper(method, helperOwner.getName(),
                helperName, helperDescriptor);
        final Map<String, String> metadata = new LinkedHashMap<>(lowered.metadata());
        metadata.put("java.static", "true");
        metadata.put("wrapper", ownerInterface ? "interface-companion" : "class-initializer");
        final NativeFunction registered = new NativeFunction(
                lowered.symbol(), remapper.mapType(helperOwner.getName()),
                remapper.mapMethodName(helperOwner.getName(), helperName, helperDescriptor),
                remapper.mapMethodDesc(helperDescriptor),
                lowered.returnType(), lowered.parameters(), lowered.entryBlock(), lowered.backend(),
                lowered.synchronizedMethod(), lowered.requiresSemanticContext(), metadata);
        lowered.blocks().forEach(registered::addBlock);
        return new Prepared(registered,
                new NativeMethodCommitTransaction.Wrapper(method, helperOwner, helper, wrapper));
    }

    String helperName(final int ordinal) {
        if (ordinal < 0) throw new IllegalArgumentException("ordinal must be non-negative");
        return "skid$native$" + randomId + "$" + ordinal;
    }

    Prepared prepareConstructor(
            final NativeCompilationPlan.Candidate candidate,
            final NativeFunction lowered,
            final ConstructorTailAnalyzer.Result plan,
            final int ordinal
    ) {
        final MethodNode constructor = candidate.selection().getMethod();
        if (!constructor.isInit() || !plan.supported()) {
            throw new IllegalArgumentException("A proven constructor tail is required");
        }
        if (!lowered.javaDescriptor().equals(remapper.mapMethodDesc(plan.helperDescriptor()))) {
            throw new IllegalArgumentException("Lowered constructor helper descriptor does not match its split proof");
        }
        final String helperName = helperName(ordinal);
        final org.objectweb.asm.tree.MethodNode helper = new org.objectweb.asm.tree.MethodNode(
                Opcodes.ASM9, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_NATIVE,
                helperName, plan.helperDescriptor(), null, null);
        return new Prepared(lowered, new NativeMethodCommitTransaction.Wrapper(
                constructor, constructor.owner, helper,
                constructorWrapper(constructor, helperName, plan)));
    }

    List<JarClassData> generatedCompanions() {
        return companions.values().stream().map(Companion::data).toList();
    }

    private Companion companion(final ClassNode interfaceOwner) {
        return companions.computeIfAbsent(interfaceOwner, ignored -> {
            final String name = uniqueCompanionName(interfaceOwner.getName());
            final ClassWriter writer = new ClassWriter(0);
            writer.visit(Opcodes.V1_8,
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC,
                    name, null, "java/lang/Object", null);
            final GeneratorAdapter constructor = new GeneratorAdapter(Opcodes.ACC_PRIVATE,
                    new Method("<init>", "()V"), null, null, writer);
            constructor.loadThis();
            constructor.invokeConstructor(Type.getType(Object.class), new Method("<init>", "()V"));
            constructor.returnValue();
            constructor.endMethod();
            writer.visitEnd();
            final byte[] bytecode = writer.toByteArray();
            final ClassNode node = ClassHelper.create(bytecode);
            return new Companion(node, new JarClassData(name + ".class", bytecode, node));
        });
    }

    private String uniqueCompanionName(final String interfaceName) {
        final String base = interfaceName + "$SkidNative$" + randomId;
        String candidate = base;
        int suffix = 0;
        while (containsClass(candidate)) {
            candidate = base + "$" + ++suffix;
        }
        return candidate;
    }

    private boolean containsClass(final String internalName) {
        return contents.getClassContents().stream().anyMatch(value ->
                value.getClassNode().getName().equals(internalName)
                        || value.getName().equals(internalName + ".class"));
    }

    private static String prependReceiver(final String owner, final String descriptor) {
        final Type[] original = Type.getArgumentTypes(descriptor);
        final Type[] arguments = new Type[original.length + 1];
        arguments[0] = Type.getObjectType(owner);
        System.arraycopy(original, 0, arguments, 1, original.length);
        return Type.getMethodDescriptor(Type.getReturnType(descriptor), arguments);
    }

    private static org.objectweb.asm.tree.MethodNode wrapper(
            final MethodNode method,
            final String helperOwner,
            final String helperName,
            final String helperDescriptor
    ) {
        final org.objectweb.asm.tree.MethodNode result = new org.objectweb.asm.tree.MethodNode(
                Opcodes.ASM9, method.node.access & ~(Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT),
                method.getName(), method.getDesc(), method.node.signature,
                method.node.exceptions == null ? null : method.node.exceptions.toArray(String[]::new));
        int local = 0;
        if (!method.isStatic()) {
            result.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, local++));
        }
        for (final Type argument : Type.getArgumentTypes(method.getDesc())) {
            result.instructions.add(new org.objectweb.asm.tree.VarInsnNode(argument.getOpcode(Opcodes.ILOAD), local));
            local += argument.getSize();
        }
        result.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(
                Opcodes.INVOKESTATIC, helperOwner, helperName, helperDescriptor, false));
        result.instructions.add(new org.objectweb.asm.tree.InsnNode(
                Type.getReturnType(method.getDesc()).getOpcode(Opcodes.IRETURN)));
        result.maxLocals = local;
        result.maxStack = Math.max(1, local);
        return result;
    }

    private static org.objectweb.asm.tree.MethodNode constructorWrapper(
            final MethodNode constructor,
            final String helperName,
            final ConstructorTailAnalyzer.Result plan
    ) {
        if (constructor.node.tryCatchBlocks != null && !constructor.node.tryCatchBlocks.isEmpty()) {
            throw new IllegalArgumentException(
                    "constructor prefix/tail splitting with exception ranges is not yet supported");
        }
        final org.objectweb.asm.tree.MethodNode result = cloneMethod(constructor.node);
        final org.objectweb.asm.tree.MethodInsnNode initialization = mandatoryInitialization(
                constructor.owner.getName(), result, plan);
        org.objectweb.asm.tree.AbstractInsnNode instruction = initialization.getNext();
        while (instruction != null) {
            final org.objectweb.asm.tree.AbstractInsnNode next = instruction.getNext();
            result.instructions.remove(instruction);
            instruction = next;
        }
        int local = 0;
        result.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, local++));
        for (final Type argument : Type.getArgumentTypes(constructor.getDesc())) {
            result.instructions.add(new org.objectweb.asm.tree.VarInsnNode(argument.getOpcode(Opcodes.ILOAD), local));
            local += argument.getSize();
        }
        result.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(
                Opcodes.INVOKESTATIC, constructor.owner.getName(), helperName,
                plan.helperDescriptor(), false));
        result.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
        result.localVariables = null;
        result.visibleLocalVariableAnnotations = null;
        result.invisibleLocalVariableAnnotations = null;
        result.maxLocals = Math.max(result.maxLocals, local);
        result.maxStack = Math.max(result.maxStack, local);
        return result;
    }

    /**
     * Locates the raw instruction proven by the MapleIR split plan. Matching only
     * the invoked owner is unsafe because a constructor can allocate another
     * instance of its class or superclass before initializing {@code this}.
     */
    private static org.objectweb.asm.tree.MethodInsnNode mandatoryInitialization(
            final String owner,
            final org.objectweb.asm.tree.MethodNode method,
            final ConstructorTailAnalyzer.Result plan
    ) {
        final UninitializedThisInterpreter interpreter = new UninitializedThisInterpreter();
        final Frame<BasicValue>[] frames;
        try {
            frames = new Analyzer<>(interpreter).analyze(owner, method);
        } catch (AnalyzerException exception) {
            throw new IllegalArgumentException(
                    "cannot prove mandatory initialization in final constructor bytecode", exception);
        }
        org.objectweb.asm.tree.MethodInsnNode matched = null;
        for (int index = 0; index < method.instructions.size(); index++) {
            final org.objectweb.asm.tree.AbstractInsnNode instruction = method.instructions.get(index);
            if (!(instruction instanceof org.objectweb.asm.tree.MethodInsnNode invocation)
                    || invocation.getOpcode() != Opcodes.INVOKESPECIAL
                    || !"<init>".equals(invocation.name)
                    || !plan.initializationOwner().equals(invocation.owner)
                    || !plan.initializationDescriptor().equals(invocation.desc)) {
                continue;
            }
            final Frame<BasicValue> frame = frames[index];
            if (frame == null) continue;
            final int receiverIndex = frame.getStackSize()
                    - Type.getArgumentTypes(invocation.desc).length - 1;
            if (receiverIndex < 0
                    || frame.getStack(receiverIndex) != interpreter.uninitializedThis()) {
                continue;
            }
            if (matched != null) {
                throw new IllegalArgumentException(
                        "final constructor bytecode has an ambiguous uninitialized-this initialization");
            }
            matched = invocation;
        }
        if (matched == null) {
            throw new IllegalArgumentException(
                    "final constructor bytecode does not match the proven MapleIR initialization boundary");
        }
        return matched;
    }

    private static final class UninitializedThisInterpreter extends BasicInterpreter {
        private final BasicValue uninitializedThis =
                new BasicValue(Type.getObjectType("skidfuscator/internal/UninitializedThis"));

        private UninitializedThisInterpreter() {
            super(Opcodes.ASM9);
        }

        @Override
        public BasicValue newParameterValue(
                final boolean isInstanceMethod,
                final int local,
                final Type type
        ) {
            return isInstanceMethod && local == 0
                    ? uninitializedThis : super.newParameterValue(isInstanceMethod, local, type);
        }

        private BasicValue uninitializedThis() {
            return uninitializedThis;
        }
    }

    private static org.objectweb.asm.tree.MethodNode cloneMethod(
            final org.objectweb.asm.tree.MethodNode source
    ) {
        final org.objectweb.asm.tree.MethodNode copy = new org.objectweb.asm.tree.MethodNode(
                Opcodes.ASM9, source.access, source.name, source.desc, source.signature,
                source.exceptions == null ? null : source.exceptions.toArray(String[]::new));
        source.accept(copy);
        return copy;
    }

    record Prepared(NativeFunction function, NativeMethodCommitTransaction.Wrapper mutation) {
    }

    private record Companion(ClassNode node, JarClassData data) {
    }
}
