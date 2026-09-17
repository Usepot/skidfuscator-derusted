package dev.skidfuscator.obfuscator.nativebackend.lowering;

import dev.skidfuscator.obfuscator.nativebackend.JavaLinkageHelper;
import org.mapleir.asm.MethodNode;
import org.mapleir.ir.code.expr.invoke.DynamicInvocationExpr;
import org.mapleir.ir.code.expr.invoke.InvocationExpr;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Stages small Java methods that keep linkage-sensitive JVM operations in their original class.
 * Native code calls these helpers through the semantic ABI instead of reimplementing JVM linkage.
 */
public final class JavaLinkageBridgeRegistry {
    public static final String ABI = "java.helper.v1";

    private static final Set<String> CALLER_SENSITIVE = Set.of(
            key("java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;"),
            key("java/lang/Class", "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;"),
            key("java/lang/ClassLoader", "getSystemClassLoader", "()Ljava/lang/ClassLoader;"),
            key("java/lang/ClassLoader", "getPlatformClassLoader", "()Ljava/lang/ClassLoader;"),
            key("java/lang/Runtime", "load", "(Ljava/lang/String;)V"),
            key("java/lang/Runtime", "loadLibrary", "(Ljava/lang/String;)V"),
            key("java/lang/System", "load", "(Ljava/lang/String;)V"),
            key("java/lang/System", "loadLibrary", "(Ljava/lang/String;)V"),
            key("java/lang/invoke/MethodHandles", "lookup", "()Ljava/lang/invoke/MethodHandles$Lookup;"),
            key("java/lang/reflect/Method", "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"),
            key("java/lang/reflect/Constructor", "newInstance", "([Ljava/lang/Object;)Ljava/lang/Object;"),
            key("java/lang/reflect/Field", "get", "(Ljava/lang/Object;)Ljava/lang/Object;"),
            key("java/lang/reflect/Field", "set", "(Ljava/lang/Object;Ljava/lang/Object;)V"),
            key("java/lang/reflect/Proxy", "getProxyClass", "(Ljava/lang/ClassLoader;[Ljava/lang/Class;)Ljava/lang/Class;"),
            key("java/lang/reflect/Proxy", "newProxyInstance",
                    "(Ljava/lang/ClassLoader;[Ljava/lang/Class;Ljava/lang/reflect/InvocationHandler;)Ljava/lang/Object;")
    );

    private final String namespace;
    private final CallerSensitiveResolver callerSensitiveResolver;
    private final List<JavaLinkageHelper> helpers = new ArrayList<>();
    private int counter;

    public JavaLinkageBridgeRegistry(final String namespace) {
        this(namespace, (owner, name, descriptor) -> false);
    }

    public JavaLinkageBridgeRegistry(
            final String namespace,
            final CallerSensitiveResolver callerSensitiveResolver
    ) {
        Objects.requireNonNull(namespace, "namespace");
        if (!namespace.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("namespace must be an identifier fragment");
        }
        this.namespace = namespace;
        this.callerSensitiveResolver = Objects.requireNonNull(
                callerSensitiveResolver, "callerSensitiveResolver");
    }

    public int checkpoint() {
        return helpers.size();
    }

    public void rollback(final int checkpoint) {
        if (checkpoint < 0 || checkpoint > helpers.size()) {
            throw new IllegalArgumentException("invalid linkage-helper checkpoint");
        }
        helpers.subList(checkpoint, helpers.size()).clear();
    }

    public List<JavaLinkageHelper> generatedHelpers() {
        return List.copyOf(helpers);
    }

    public Bridge invokedynamic(final MethodNode caller, final DynamicInvocationExpr invocation) {
        final String descriptor = invocation.getBootstrapDesc();
        final String kind = "invokedynamic";
        final org.objectweb.asm.tree.MethodNode helper = helper(caller, descriptor, kind);
        loadArguments(helper.instructions, Type.getArgumentTypes(descriptor));
        helper.instructions.add(new InvokeDynamicInsnNode(
                invocation.getBoundName(), descriptor, invocation.getBootstrapMethod(),
                invocation.getBootstrapArgs().clone()));
        finish(helper, descriptor);
        return register(caller, helper, kind);
    }

    public Bridge constant(final MethodNode caller, final Object constant, final String descriptor) {
        if (!(constant instanceof Type || constant instanceof Handle || constant instanceof ConstantDynamic)) {
            throw new IllegalArgumentException("constant does not require a JVM linkage bridge");
        }
        final String kind = constant instanceof ConstantDynamic ? "constant-dynamic"
                : constant instanceof Handle ? "method-handle" : "method-type-or-class";
        final org.objectweb.asm.tree.MethodNode helper = helper(caller, descriptor, kind);
        helper.instructions.add(new LdcInsnNode(constant));
        finish(helper, descriptor);
        return register(caller, helper, kind);
    }

    public Optional<Bridge> callerSensitive(final MethodNode caller, final InvocationExpr invocation) {
        if (!CALLER_SENSITIVE.contains(key(invocation.getOwner(), invocation.getName(), invocation.getDesc()))
                && !callerSensitiveResolver.isCallerSensitive(
                invocation.getOwner(), invocation.getName(), invocation.getDesc())) {
            return Optional.empty();
        }
        final String descriptor = helperDescriptor(caller, invocation);
        final String kind = "caller-sensitive";
        final org.objectweb.asm.tree.MethodNode helper = helper(caller, descriptor, kind);
        loadArguments(helper.instructions, Type.getArgumentTypes(descriptor));
        helper.instructions.add(new MethodInsnNode(
                invocation.getCallType().getOpcode(), invocation.getOwner(), invocation.getName(),
                invocation.getDesc(), invocation.getCallType() == InvocationExpr.CallType.INTERFACE));
        finish(helper, descriptor);
        return Optional.of(register(caller, helper, kind));
    }

    private org.objectweb.asm.tree.MethodNode helper(
            final MethodNode caller,
            final String descriptor,
            final String kind
    ) {
        final int visibility = (caller.owner.node.access & Opcodes.ACC_INTERFACE) != 0
                ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE;
        String name;
        do {
            name = "skid$link$" + namespace + "$" + counter++;
        } while (hasMethod(caller, name, descriptor) || hasStagedMethod(caller, name, descriptor));
        return new org.objectweb.asm.tree.MethodNode(
                Opcodes.ASM9, visibility | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name, descriptor, null, null);
    }

    private boolean hasStagedMethod(
            final MethodNode caller,
            final String name,
            final String descriptor
    ) {
        return helpers.stream().anyMatch(helper -> helper.owner() == caller.owner
                && helper.method().name.equals(name) && helper.method().desc.equals(descriptor));
    }

    private static boolean hasMethod(
            final MethodNode caller,
            final String name,
            final String descriptor
    ) {
        for (final org.objectweb.asm.tree.MethodNode method : caller.owner.node.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) return true;
        }
        return false;
    }

    private Bridge register(
            final MethodNode caller,
            final org.objectweb.asm.tree.MethodNode helper,
            final String kind
    ) {
        helpers.add(new JavaLinkageHelper(caller.owner, helper, kind));
        final Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("bridge", ABI);
        attributes.put("descriptor", helper.desc);
        attributes.put("helper.owner", caller.owner.getName());
        attributes.put("helper.name", helper.name);
        attributes.put("helper.descriptor", helper.desc);
        attributes.put("helper.invoke", "static");
        attributes.put("helper.kind", kind);
        return new Bridge(helper.desc, Map.copyOf(attributes));
    }

    private static String helperDescriptor(final MethodNode caller, final InvocationExpr invocation) {
        if (invocation.isStatic()) return invocation.getDesc();
        final String receiver = invocation.getCallType() == InvocationExpr.CallType.SPECIAL
                ? "L" + caller.owner.getName() + ";"
                : "L" + invocation.getOwner() + ";";
        final Type method = Type.getMethodType(invocation.getDesc());
        final Type[] original = method.getArgumentTypes();
        final Type[] bridged = new Type[original.length + 1];
        bridged[0] = Type.getType(receiver);
        System.arraycopy(original, 0, bridged, 1, original.length);
        return Type.getMethodDescriptor(method.getReturnType(), bridged);
    }

    private static void loadArguments(final InsnList instructions, final Type[] arguments) {
        int local = 0;
        for (final Type argument : arguments) {
            instructions.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), local));
            local += argument.getSize();
        }
    }

    private static void finish(final org.objectweb.asm.tree.MethodNode helper, final String descriptor) {
        final Type method = Type.getMethodType(descriptor);
        helper.instructions.add(new org.objectweb.asm.tree.InsnNode(
                method.getReturnType().getOpcode(Opcodes.IRETURN)));
        helper.maxLocals = java.util.Arrays.stream(method.getArgumentTypes()).mapToInt(Type::getSize).sum();
        helper.maxStack = Math.max(2, helper.maxLocals + 1);
    }

    private static String key(final String owner, final String name, final String descriptor) {
        return owner + '\0' + name + '\0' + descriptor;
    }

    public record Bridge(String descriptor, Map<String, String> attributes) {
        public Bridge {
            Objects.requireNonNull(descriptor, "descriptor");
            attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
        }
    }

    @FunctionalInterface
    public interface CallerSensitiveResolver {
        boolean isCallerSensitive(String owner, String name, String descriptor);
    }
}
