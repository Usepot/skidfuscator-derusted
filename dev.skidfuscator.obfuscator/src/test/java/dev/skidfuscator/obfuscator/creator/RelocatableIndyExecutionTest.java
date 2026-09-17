package dev.skidfuscator.obfuscator.creator;

import com.typesafe.config.ConfigFactory;
import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.compatibility.RelocatableInvokeDynamic;
import dev.skidfuscator.obfuscator.transform.impl.method.InvokeDynamicMethodTransformer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;
import org.topdank.byteengineer.commons.data.JarClassData;
import org.topdank.byteengineer.commons.data.JarContents;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real production-pass output is loaded and invoked; no hand-patched callsite. */
class RelocatableIndyExecutionTest implements Opcodes {

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void clonesAllArraySortsExactlyAndKeepsTheCallsitesObfuscated(boolean interfaceOwner) throws Exception {
        ClassNode owner = owner(interfaceOwner ? "fixture/ArrayCloneInterface" : "fixture/ArrayCloneClass");
        if (interfaceOwner) owner.access |= ACC_INTERFACE | ACC_ABSTRACT;
        Object[] inputs = {new boolean[]{true, false}, new byte[]{1, -8}, new char[]{'x', '\ud800'},
                new short[]{1, -2}, new int[]{1, -7}, new long[]{Long.MIN_VALUE, 9},
                new float[]{Float.NaN, -0.0f}, new double[]{Double.NaN, Double.NEGATIVE_INFINITY},
                new String[]{"a", null}, new Object[]{new Object(), null},
                new int[][]{{1, 2}, null}, new Thread.State[]{Thread.State.NEW, Thread.State.RUNNABLE}};
        for (int index = 0; index < inputs.length; index++) {
            String array = Type.getDescriptor(inputs[index].getClass());
            MethodNode method = new MethodNode(ACC_PUBLIC | ACC_STATIC, "clone" + index,
                    "(" + array + ")Ljava/lang/Object;", null, null);
            method.instructions.add(new VarInsnNode(ALOAD, 0));
            method.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, array, "clone", "()Ljava/lang/Object;", false));
            method.instructions.add(new InsnNode(ARETURN));
            method.maxLocals = 1;
            method.maxStack = 1;
            owner.methods.add(method);
        }
        transform(owner);
        assertEquals(inputs.length, owner.methods.stream().filter(m -> m.name.matches("clone\\d+"))
                .flatMap(m -> Arrays.stream(m.instructions.toArray())).filter(i -> i instanceof InvokeDynamicInsnNode).count());
        Class<?> type = define(owner);
        for (int index = 0; index < inputs.length; index++) {
            Object input = inputs[index];
            java.lang.reflect.Method method = type.getMethod("clone" + index, input.getClass());
            Object cloned = method.invoke(null, new Object[]{input});
            assertNotSame(input, cloned);
            assertEquals(input.getClass(), cloned.getClass());
            assertEquals(Array.getLength(input), Array.getLength(cloned));
            for (int item = 0; item < Array.getLength(input); item++) {
                if (input.getClass().getComponentType().isPrimitive()) assertEquals(Array.get(input, item), Array.get(cloned, item));
                else assertSame(Array.get(input, item), Array.get(cloned, item), "Array clone must be shallow");
            }
            InvocationTargetException failure = assertThrows(InvocationTargetException.class, () -> method.invoke(null, new Object[]{null}));
            assertInstanceOf(NullPointerException.class, failure.getCause());
        }
        owner.methods.stream().filter(m -> m.name.startsWith("skid$")).forEach(method -> {
            assertTrue((method.access & ACC_SYNTHETIC) != 0);
            assertTrue((method.access & (interfaceOwner ? ACC_PUBLIC : ACC_PRIVATE)) != 0);
        });
    }

    @Test void mixinStyleTransplantAndHelperRenamingDoNotInvalidateLinkage() throws Exception {
        ClassNode donor = owner("fixture/DonorMixin");
        donor.invisibleAnnotations = List.of(new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;"));
        MethodNode helper = new MethodNode(ACC_PRIVATE | ACC_STATIC, "helper", "(I)I", null, null);
        helper.instructions.add(new VarInsnNode(ILOAD, 0));
        helper.instructions.add(new IntInsnNode(BIPUSH, 17));
        helper.instructions.add(new InsnNode(IADD));
        helper.instructions.add(new InsnNode(IRETURN));
        helper.maxLocals = 1;
        helper.maxStack = 2;
        donor.methods.add(helper);
        MethodNode call = new MethodNode(ACC_PUBLIC | ACC_STATIC, "invoke", "(I)I", null, null);
        call.instructions.add(new VarInsnNode(ILOAD, 0));
        call.instructions.add(new MethodInsnNode(INVOKESTATIC, donor.name, helper.name, helper.desc, false));
        call.instructions.add(new InsnNode(IRETURN));
        call.maxLocals = 1;
        call.maxStack = 1;
        donor.methods.add(call);
        MethodNode nullable = new MethodNode(ACC_PUBLIC | ACC_STATIC, "length", "(Ljava/lang/String;)I", null, null);
        nullable.instructions.add(new VarInsnNode(ALOAD, 0));
        nullable.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false));
        nullable.instructions.add(new InsnNode(IRETURN));
        nullable.maxLocals = 1;
        nullable.maxStack = 1;
        donor.methods.add(nullable);
        transform(donor);
        assertTrue(RelocatableInvokeDynamic.isMixin(donor));
        InvokeDynamicInsnNode site = Arrays.stream(call.instructions.toArray()).filter(i -> i instanceof InvokeDynamicInsnNode)
                .map(i -> (InvokeDynamicInsnNode) i).findFirst().orElseThrow();
        assertInstanceOf(Handle.class, site.bsmArgs[0]);

        // Model donor -> Minecraft owner transplantation AND collision-based
        // helper renaming. The runtime must not need any donor class definition.
        Map<String, String> mapping = new HashMap<>();
        mapping.put(donor.name, "fixture/RealTarget");
        for (MethodNode method : donor.methods) if (method.name.equals("helper") || method.name.startsWith("skid$"))
            mapping.put(donor.name + "." + method.name + method.desc, "mixin$collision$" + method.name);
        ClassNode target = new ClassNode();
        donor.accept(new ClassRemapper(target, new SimpleRemapper(mapping)));
        Class<?> type = define(target);
        assertEquals(42, type.getMethod("invoke", int.class).invoke(null, 25));
        assertEquals(4, type.getMethod("length", String.class).invoke(null, "test"));
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> type.getMethod("length", String.class).invoke(null, new Object[]{null}));
        assertInstanceOf(NullPointerException.class, failure.getCause());
        assertThrows(ClassNotFoundException.class, () -> type.getClassLoader().loadClass("fixture.DonorMixin"));
    }

    @Test void ordinaryCallsStillUseEncryptedLinkageInsteadOfTheMixinCompatibilityPath() {
        ClassNode owner = owner("fixture/OrdinaryCalls");
        MethodNode method = new MethodNode(ACC_PUBLIC | ACC_STATIC, "length", "(Ljava/lang/String;)I", null, null);
        method.instructions.add(new VarInsnNode(ALOAD, 0));
        method.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false));
        method.instructions.add(new InsnNode(IRETURN));
        method.maxLocals = 1;
        method.maxStack = 1;
        owner.methods.add(method);
        transform(owner);
        InvokeDynamicInsnNode site = (InvokeDynamicInsnNode) method.instructions.get(1);
        assertTrue(site.bsm.getName().startsWith("skid$bootstrap$"));
        assertInstanceOf(String.class, site.bsmArgs[1]);
        assertNotEquals("java.lang.String", site.bsmArgs[1]);
    }

    private static ClassNode owner(String name) {
        ClassNode node = new ClassNode();
        node.visit(V1_8, ACC_PUBLIC, name, null, "java/lang/Object", null);
        return node;
    }
    private static Class<?> define(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        byte[] bytes = writer.toByteArray();
        return new ClassLoader(RelocatableIndyExecutionTest.class.getClassLoader()) {
            Class<?> load() { return defineClass(null, bytes, 0, bytes.length); }
        }.load();
    }
    private static void transform(ClassNode owner) {
        Skidfuscator skid = mock(Skidfuscator.class, RETURNS_DEEP_STUBS);
        when(skid.getTsConfig()).thenReturn(ConfigFactory.empty());
        when(skid.getHierarchy().getGroups()).thenReturn(List.of());
        when(skid.getClassRemapper().map(anyString())).thenAnswer(call -> call.getArgument(0));
        JarContents contents = new JarContents();
        org.mapleir.asm.ClassNode wrapper = new org.mapleir.asm.ClassNode();
        wrapper.node = owner;
        for (MethodNode method : owner.methods) wrapper.getMethods().add(new org.mapleir.asm.MethodNode(method, wrapper));
        contents.getClassContents().add(new JarClassData(owner.name + ".class", new byte[0], wrapper));
        when(skid.getJarContents()).thenReturn(contents);
        ClassNode string = owner("java/lang/String");
        string.methods.add(new MethodNode(ACC_PUBLIC, "length", "()I", null, null));
        org.mapleir.asm.ClassNode stringWrapper = new org.mapleir.asm.ClassNode();
        stringWrapper.node = string;
        when(skid.getClassSource().findClassNode("java/lang/String")).thenReturn(stringWrapper);
        new InvokeDynamicMethodTransformer(skid).apply();
    }
}
