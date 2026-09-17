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

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Regression for a relocated CrashReportHook Callable failing in its clinit. */
class MixinInnerIndyExecutionTest implements Opcodes {
    private static ClassNode type(String name) {
        ClassNode node = new ClassNode();
        node.visit(V1_8, ACC_PUBLIC, name, null, "java/lang/Object", null);
        return node;
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void ownedInnerClassInitializesAfterRelocationWithoutLoadingDonor(boolean anonymous) throws Exception {
        ClassNode donor = type("fixture/EnclosingMixin");
        donor.invisibleAnnotations = List.of(new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;"));
        // Deliberately not a '$' spelling: ownership metadata is authoritative.
        ClassNode inner = type("fixture/MovedCallable");
        if (anonymous) inner.outerClass = donor.name;
        else donor.innerClasses.add(new InnerClassNode(inner.name, donor.name, "Callable", ACC_STATIC));
        inner.fields.add(new FieldNode(ACC_PRIVATE | ACC_STATIC, "value", "I", null, null));
        MethodNode helper = new MethodNode(ACC_PRIVATE | ACC_STATIC, "initialize", "()I", null, null);
        helper.instructions.add(new IntInsnNode(BIPUSH, 42));
        helper.instructions.add(new InsnNode(IRETURN));
        helper.maxStack = 1;
        inner.methods.add(helper);
        MethodNode clinit = new MethodNode(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.instructions.add(new MethodInsnNode(INVOKESTATIC, inner.name, helper.name, helper.desc, false));
        clinit.instructions.add(new FieldInsnNode(PUTSTATIC, inner.name, "value", "I"));
        clinit.instructions.add(new InsnNode(RETURN));
        clinit.maxStack = 1;
        inner.methods.add(clinit);
        MethodNode read = new MethodNode(ACC_PUBLIC | ACC_STATIC, "read", "()I", null, null);
        read.instructions.add(new FieldInsnNode(GETSTATIC, inner.name, "value", "I"));
        read.instructions.add(new InsnNode(IRETURN));
        read.maxStack = 1;
        inner.methods.add(read);
        transform(donor, inner);
        assertFalse(RelocatableInvokeDynamic.isMixin(inner));
        InvokeDynamicInsnNode site = assertInstanceOf(InvokeDynamicInsnNode.class, clinit.instructions.getFirst());
        assertInstanceOf(Handle.class, site.bsmArgs[0]);
        Map<String, String> mapping = Map.of(donor.name, "fixture/RealTarget",
                inner.name, "fixture/RealTarget$Callable",
                inner.name + ".initialize()I", "relocated$initialize");
        ClassNode target = new ClassNode();
        inner.accept(new ClassRemapper(target, new SimpleRemapper(mapping)));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        target.accept(writer);
        byte[] bytes = writer.toByteArray();
        Class<?> loaded = new ClassLoader(getClass().getClassLoader()) {
            Class<?> load() { return defineClass(null, bytes, 0, bytes.length); }
        }.load();
        assertEquals(42, loaded.getMethod("read").invoke(null));
        assertThrows(ClassNotFoundException.class, () -> loaded.getClassLoader().loadClass("fixture.MovedCallable"));
        assertThrows(ClassNotFoundException.class, () -> loaded.getClassLoader().loadClass("fixture.EnclosingMixin"));
    }

    @Test void ownershipClosureIsCycleSafeAndDoesNotSelectPackageOrNameLookalikes() {
        ClassNode donor = type("fixture/Donor");
        donor.visibleAnnotations = List.of(new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;"));
        ClassNode child = type("fixture/Child"), nested = type("fixture/Nested");
        child.outerClass = donor.name;
        child.innerClasses.add(new InnerClassNode(nested.name, child.name, "Nested", ACC_STATIC));
        nested.innerClasses.add(new InnerClassNode(child.name, nested.name, "Cycle", ACC_STATIC));
        ClassNode ordinary = type("fixture/Donor$NameLookalike");
        assertEquals(Set.of(donor.name, child.name, nested.name),
                RelocatableInvokeDynamic.relocationFamily(List.of(donor, child, nested, ordinary)));
    }

    private static void transform(ClassNode... nodes) {
        Skidfuscator skid = mock(Skidfuscator.class, RETURNS_DEEP_STUBS);
        when(skid.getTsConfig()).thenReturn(ConfigFactory.empty());
        when(skid.getHierarchy().getGroups()).thenReturn(List.of());
        when(skid.getClassRemapper().map(anyString())).thenAnswer(call -> call.getArgument(0));
        JarContents contents = new JarContents();
        for (ClassNode node : nodes) {
            org.mapleir.asm.ClassNode wrapper = new org.mapleir.asm.ClassNode();
            wrapper.node = node;
            for (MethodNode method : node.methods) wrapper.getMethods().add(new org.mapleir.asm.MethodNode(method, wrapper));
            contents.getClassContents().add(new JarClassData(node.name + ".class", new byte[0], wrapper));
        }
        when(skid.getJarContents()).thenReturn(contents);
        new InvokeDynamicMethodTransformer(skid).apply();
    }
}
