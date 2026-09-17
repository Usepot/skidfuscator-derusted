package dev.skidfuscator.obfuscator.creator;

import com.typesafe.config.ConfigFactory;
import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.renamer.SkidRemapper;
import dev.skidfuscator.obfuscator.skidasm.SkidGroup;
import dev.skidfuscator.obfuscator.transform.impl.method.InvokeDynamicMethodTransformer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.CodeSizeEvaluator;
import org.objectweb.asm.tree.*;
import org.topdank.byteengineer.commons.data.JarClassData;
import org.topdank.byteengineer.commons.data.JarContents;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IndyCorrectnessRegressionTest {
    @Test void java8InterfaceDefaultAndStaticMethodsLinkWithNarrowAndWideHelpers() throws Exception {
        for (boolean wide : new boolean[] {false, true}) {
            for (boolean seeded : new boolean[] {false, true}) {
                ClassNode owner = owner("fixture/IndyInterface", true);
                String desc = seeded ? (wide ? "(J)I" : "(I)I") : "()I";
                MethodNode target = method(owner, "target", desc, true);
                target.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 42));
                target.instructions.add(new InsnNode(Opcodes.IRETURN));
                for (boolean staticCaller : new boolean[] {false, true}) {
                    MethodNode caller = method(owner, staticCaller ? "staticCall" : "defaultCall", "()I", staticCaller);
                    if (seeded) caller.instructions.add(new LdcInsnNode(wide ? (Object) Long.valueOf(0x123456789ABCDEFL) : Integer.valueOf(123)));
                    caller.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner.name, target.name, desc, true));
                    caller.instructions.add(new InsnNode(Opcodes.IRETURN));
                }
                transform(owner, wide, seeded ? target : null);
                assertEquals(Opcodes.V1_8, owner.version);
                assertEquals(2, indyCount(owner));
                for (MethodNode method : owner.methods) {
                    if (method.name.startsWith("skid$")) {
                        assertEquals(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, method.access);
                    }
                    for (AbstractInsnNode insn : method.instructions) {
                        if (insn instanceof InvokeDynamicInsnNode) assertTrue(((InvokeDynamicInsnNode) insn).bsm.isInterface());
                        if (insn instanceof MethodInsnNode && ((MethodInsnNode) insn).owner.equals(owner.name)) {
                            assertTrue(((MethodInsnNode) insn).itf);
                        }
                    }
                }
                ClassNode impl = owner("fixture/IndyImpl", false);
                impl.interfaces.add(owner.name);
                MethodNode constructor = method(impl, "<init>", "()V", false);
                constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
                constructor.instructions.add(new InsnNode(Opcodes.RETURN));
                ClassLoader loader = loader(owner, impl);
                Class<?> api = loader.loadClass(owner.name.replace('/', '.'));
                Object instance = loader.loadClass(impl.name.replace('/', '.')).getConstructor().newInstance();
                // Second invocation also exercises the seed bootstrap's relinked target.
                for (int i = 0; i < 2; i++) {
                    assertEquals(42, api.getMethod("staticCall").invoke(null));
                    assertEquals(42, api.getMethod("defaultCall").invoke(instance));
                }
            }
        }
    }

    @Test void nearLimitMethodReservesBranchWideningAndSwitchPaddingBeforeReplacingCalls() throws Exception {
        ClassNode owner = owner("fixture/IndyNearLimit", false);
        MethodNode target = method(owner, "target", "()V", true);
        target.instructions.add(new InsnNode(Opcodes.RETURN));
        MethodNode caller = method(owner, "call", "(I)V", true);
        LabelNode end = new LabelNode();
        LabelNode body = new LabelNode();
        caller.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        caller.instructions.add(new JumpInsnNode(Opcodes.IFEQ, end));
        caller.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        caller.instructions.add(new LookupSwitchInsnNode(body, new int[] {1}, new LabelNode[] {body}));
        caller.instructions.add(body);
        for (int i = 0; i < 3; i++) caller.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner.name, "target", "()V", false));
        caller.instructions.add(end);
        caller.instructions.add(new InsnNode(Opcodes.RETURN));
        CodeSizeEvaluator initial = new CodeSizeEvaluator(null);
        caller.accept(initial);
        for (int i = initial.getMaxSize(); i < 65533; i++) caller.instructions.insert(body, new InsnNode(Opcodes.NOP));
        transform(owner, false, null);
        assertEquals(1, indyCount(owner));
        long guarded = Arrays.stream(caller.instructions.toArray()).filter(i -> i instanceof MethodInsnNode).count();
        assertEquals(2, guarded);
        CodeSizeEvaluator after = new CodeSizeEvaluator(null);
        caller.accept(after);
        assertEquals(65535, after.getMaxSize());
        Class<?> type = loader(owner).loadClass(owner.name.replace('/', '.'));
        type.getMethod("call", int.class).invoke(null, 0);
        type.getMethod("call", int.class).invoke(null, 1);
        type.getMethod("call", int.class).invoke(null, 2);
    }

    @Test void fullMethodKeepsItsCallAndDoesNotGenerateUnusedHelpers() throws Exception {
        ClassNode owner = owner("fixture/IndyFullMethod", false);
        MethodNode target = method(owner, "target", "()V", true);
        target.instructions.add(new InsnNode(Opcodes.RETURN));
        MethodNode caller = method(owner, "call", "()V", true);
        for (int i = 0; i < 65531; i++) caller.instructions.add(new InsnNode(Opcodes.NOP));
        MethodInsnNode original = new MethodInsnNode(Opcodes.INVOKESTATIC, owner.name, "target", "()V", false);
        caller.instructions.add(original);
        caller.instructions.add(new InsnNode(Opcodes.RETURN));
        transform(owner, false, null);
        assertEquals(0, indyCount(owner));
        assertEquals(2, owner.methods.size());
        assertSame(original, caller.instructions.get(65531));
        loader(owner).loadClass(owner.name.replace('/', '.')).getMethod("call").invoke(null);
    }

    @Test void preIndyClassVersionIsPreserved() {
        ClassNode owner = owner("fixture/IndyOldVersion", false);
        owner.version = Opcodes.V1_6;
        MethodNode target = method(owner, "target", "()V", true);
        target.instructions.add(new InsnNode(Opcodes.RETURN));
        MethodNode caller = method(owner, "call", "()V", true);
        caller.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner.name, "target", "()V", false));
        caller.instructions.add(new InsnNode(Opcodes.RETURN));
        transform(owner, false, null);
        assertEquals(Opcodes.V1_6, owner.version);
        assertEquals(0, indyCount(owner));
        assertEquals(2, owner.methods.size());
    }

    @Test void arrayCloneKeepsJvmArraySemanticsWithEncryptedCalls() throws Exception {
        Object[] inputs = {new int[] {3, 7}, new String[] {"a", "b"},
                new Object[][] {{"nested"}}, new Thread.State[] {Thread.State.NEW}};
        for (boolean wide : new boolean[] {false, true}) {
            for (boolean itf : new boolean[] {false, true}) {
                ClassNode owner = owner("fixture/ArrayCloneCalls", itf);
                for (int i = 0; i < inputs.length; i++) {
                    String array = org.objectweb.asm.Type.getDescriptor(inputs[i].getClass());
                    MethodNode call = method(owner, "copy" + i, "(" + array + ")Ljava/lang/Object;", true);
                    call.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    call.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, array,
                            "clone", "()Ljava/lang/Object;", false));
                    call.instructions.add(new InsnNode(Opcodes.ARETURN));
                }
                transform(owner, wide, null);
                assertEquals(inputs.length, indyCount(owner), "Clone calls must remain obfuscated");
                Class<?> type = loader(owner).loadClass(owner.name.replace('/', '.'));
                for (int i = 0; i < inputs.length; i++) {
                    Object input = inputs[i];
                    java.lang.reflect.Method call = type.getMethod("copy" + i, input.getClass());
                    for (int repeat = 0; repeat < 2; repeat++) {
                        Object copy = call.invoke(null, input);
                        assertNotSame(input, copy);
                        assertSame(input.getClass(), copy.getClass());
                        assertEquals(java.lang.reflect.Array.getLength(input), java.lang.reflect.Array.getLength(copy));
                        for (int n = 0; n < java.lang.reflect.Array.getLength(input); n++)
                            assertEquals(java.lang.reflect.Array.get(input, n), java.lang.reflect.Array.get(copy, n));
                    }
                    java.lang.reflect.InvocationTargetException failure = assertThrows(
                            java.lang.reflect.InvocationTargetException.class, () -> call.invoke(null, new Object[] {null}));
                    assertInstanceOf(NullPointerException.class, failure.getCause());
                }
            }
        }
    }

    private static void transform(ClassNode owner, boolean wide, MethodNode seededTarget) {
        Skidfuscator skid = mock(Skidfuscator.class, RETURNS_DEEP_STUBS);
        when(skid.getTsConfig()).thenReturn(ConfigFactory.empty());
        when(skid.getConfig().isSeedWide()).thenReturn(wide);
        when(skid.getClassRemapper()).thenReturn(new SkidRemapper(Collections.emptyMap()));
        org.mapleir.asm.ClassNode wrapper = new org.mapleir.asm.ClassNode();
        wrapper.node = owner;
        JarContents contents = new JarContents();
        contents.getClassContents().add(new JarClassData(owner.name + ".class", new byte[0], wrapper));
        when(skid.getJarContents()).thenReturn(contents);
        if (seededTarget == null) {
            when(skid.getHierarchy().getGroups()).thenReturn(Collections.emptyList());
        } else {
            SkidGroup group = mock(SkidGroup.class, RETURNS_DEEP_STUBS);
            when(group.isInjectedMethodPredicate()).thenReturn(true);
            when(group.getPredicate().getPublic()).thenReturn(123);
            when(group.getPredicate().getPublicLong()).thenReturn(0x123456789ABCDEFL);
            when(group.getMethodNodeList()).thenReturn(Collections.singletonList(new org.mapleir.asm.MethodNode(seededTarget, wrapper)));
            when(skid.getHierarchy().getGroups()).thenReturn(Collections.singletonList(group));
        }
        new InvokeDynamicMethodTransformer(skid).apply();
    }

    private static ClassNode owner(String name, boolean itf) {
        ClassNode node = new ClassNode();
        node.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | (itf ? Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT : 0), name, null, "java/lang/Object", null);
        return node;
    }

    private static MethodNode method(ClassNode owner, String name, String desc, boolean isStatic) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | (isStatic ? Opcodes.ACC_STATIC : 0), name, desc, null, null);
        owner.methods.add(method);
        return method;
    }

    private static long indyCount(ClassNode owner) {
        return owner.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray()))
                .filter(i -> i instanceof InvokeDynamicInsnNode).count();
    }

    private static ClassLoader loader(ClassNode... nodes) {
        Map<String, byte[]> bytes = new HashMap<>();
        for (ClassNode node : nodes) {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            bytes.put(node.name.replace('/', '.'), writer.toByteArray());
        }
        return new ClassLoader(IndyCorrectnessRegressionTest.class.getClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] data = bytes.get(name);
                if (data == null) throw new ClassNotFoundException(name);
                return defineClass(name, data, 0, data.length);
            }
        };
    }
}
