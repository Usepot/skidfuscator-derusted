package dev.skidfuscator.obfuscator.compatibility;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Linkage-sensitive sites keep typed, relocatable constant-pool handles instead
 * of encrypted owner/member-name strings. Mixin can relocate those handles when
 * it transplants and renames a helper. Other sites retain the encrypted path.
 *
 * Array clone is special: Java 8 Lookup.findVirtual can resolve Object.clone as
 * protected and narrow its receiver to the caller. A tiny direct-bytecode bridge
 * keeps the JVM's actual public array-clone semantics behind an obfuscated indy
 * callsite. No enum, array-using method or Mixin body is exempted.
 */
public final class RelocatableInvokeDynamic implements Opcodes {
    private static final String BOOTSTRAP_DESC = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
            + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;)Ljava/lang/invoke/CallSite;";
    private final ClassNode owner;
    private final boolean interfaceOwner;
    private final Map<String, Handle> cloneBridges = new HashMap<>();
    private Handle bootstrap;

    public RelocatableInvokeDynamic(ClassNode owner) {
        this.owner = owner;
        this.interfaceOwner = (owner.access & ACC_INTERFACE) != 0;
    }

    public static boolean isMixin(ClassNode owner) {
        return containsMixin(owner.visibleAnnotations) || containsMixin(owner.invisibleAnnotations);
    }
    private static boolean containsMixin(List<AnnotationNode> annotations) {
        return annotations != null && annotations.stream().anyMatch(a -> "Lorg/spongepowered/asm/mixin/Mixin;".equals(a.desc));
    }
    /**
     * Mixin also transplants anonymous/member classes owned by a donor. Those
     * classes normally have no @Mixin annotation of their own, but encrypted
     * owner strings inside them would still refer to the now-unloadable donor
     * namespace. Follow actual EnclosingMethod/InnerClasses ownership instead
     * of excluding the entire Mixin package or depending on '$' name spelling.
     */
    public static java.util.Set<String> relocationFamily(java.util.Collection<ClassNode> classes) {
        java.util.Set<String> present = new java.util.HashSet<>();
        java.util.Set<String> family = new java.util.HashSet<>();
        Map<String, java.util.Set<String>> children = new HashMap<>();
        for (ClassNode node : classes) {
            present.add(node.name);
            if (isMixin(node)) family.add(node.name);
            if (node.outerClass != null)
                children.computeIfAbsent(node.outerClass, key -> new java.util.HashSet<>()).add(node.name);
            if (node.innerClasses != null) for (InnerClassNode inner : node.innerClasses) {
                if (inner.outerName != null)
                    children.computeIfAbsent(inner.outerName, key -> new java.util.HashSet<>()).add(inner.name);
            }
        }
        java.util.Deque<String> pending = new java.util.ArrayDeque<>(family);
        while (!pending.isEmpty()) {
            for (String child : children.getOrDefault(pending.removeFirst(), java.util.Set.of()))
                if (present.contains(child) && family.add(child)) pending.addLast(child);
        }
        return java.util.Set.copyOf(family);
    }

    public static boolean isArrayClone(MethodInsnNode call) {
        return call.getOpcode() == INVOKEVIRTUAL && call.owner.startsWith("[")
                && call.name.equals("clone") && call.desc.equals("()Ljava/lang/Object;");
    }
    public static boolean requiresSymbolicLinkage(ClassNode owner, MethodInsnNode call) {
        return isMixin(owner) || isArrayClone(call);
    }

    public InvokeDynamicInsnNode rewrite(MethodInsnNode original, String callSiteDescriptor) {
        if (original.name.equals("<init>")) throw new IllegalArgumentException("Constructor allocation cannot become an ordinary method handle");
        Handle target = isArrayClone(original) ? cloneBridge(original.owner) : symbolicTarget(original);
        return new InvokeDynamicInsnNode(unique("skid$call$"), callSiteDescriptor, bootstrap(), target);
    }

    private Handle symbolicTarget(MethodInsnNode call) {
        final int tag;
        switch (call.getOpcode()) {
            case INVOKESTATIC: tag = H_INVOKESTATIC; break;
            case INVOKEVIRTUAL: tag = H_INVOKEVIRTUAL; break;
            case INVOKEINTERFACE: tag = H_INVOKEINTERFACE; break;
            case INVOKESPECIAL: tag = H_INVOKESPECIAL; break;
            default: throw new IllegalArgumentException("Unsupported invocation opcode " + call.getOpcode());
        }
        return new Handle(tag, call.owner, call.name, call.desc, call.itf);
    }

    private int helperAccess() {
        return ACC_STATIC | ACC_SYNTHETIC | (interfaceOwner ? ACC_PUBLIC : ACC_PRIVATE);
    }
    private String unique(String prefix) {
        String value;
        do { value = prefix + UUID.randomUUID().toString().replace("-", ""); }
        while (hasMethodNamed(value));
        return value;
    }
    private boolean hasMethodNamed(String name) {
        return owner.methods.stream().anyMatch(method -> method.name.equals(name));
    }
    private Handle cloneBridge(String arrayDescriptor) {
        Handle cached = cloneBridges.get(arrayDescriptor);
        if (cached != null) return cached;
        String descriptor = Type.getMethodDescriptor(Type.getType(Object.class), Type.getType(arrayDescriptor));
        String name = unique("skid$clone$");
        MethodNode method = new MethodNode(helperAccess(), name, descriptor, null, null);
        method.instructions.add(new VarInsnNode(ALOAD, 0));
        method.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, arrayDescriptor, "clone", "()Ljava/lang/Object;", false));
        method.instructions.add(new InsnNode(ARETURN));
        method.maxStack = 1;
        method.maxLocals = 1;
        owner.methods.add(method);
        Handle handle = new Handle(H_INVOKESTATIC, owner.name, name, descriptor, interfaceOwner);
        cloneBridges.put(arrayDescriptor, handle);
        return handle;
    }
    private Handle bootstrap() {
        if (bootstrap != null) return bootstrap;
        String name = unique("skid$symbolic$");
        MethodNode method = new MethodNode(helperAccess(), name, BOOTSTRAP_DESC, null, null);
        method.instructions.add(new TypeInsnNode(NEW, "java/lang/invoke/ConstantCallSite"));
        method.instructions.add(new InsnNode(DUP));
        method.instructions.add(new VarInsnNode(ALOAD, 3));
        method.instructions.add(new VarInsnNode(ALOAD, 2));
        method.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "asType",
                "(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false));
        method.instructions.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/invoke/ConstantCallSite", "<init>",
                "(Ljava/lang/invoke/MethodHandle;)V", false));
        method.instructions.add(new InsnNode(ARETURN));
        method.maxStack = 4;
        method.maxLocals = 4;
        owner.methods.add(method);
        bootstrap = new Handle(H_INVOKESTATIC, owner.name, name, BOOTSTRAP_DESC, interfaceOwner);
        return bootstrap;
    }
}
