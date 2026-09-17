package dev.skidfuscator.test.nativebackend;

import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.SkidfuscatorSession;
import dev.skidfuscator.obfuscator.skidasm.SkidGroup;
import dev.skidfuscator.obfuscator.transform.impl.method.MethodMergeTransformer;
import dev.skidfuscator.obfuscator.transform.impl.signature.SignatureObfuscationTransformer;
import org.junit.jupiter.api.Test;
import org.mapleir.asm.ClassHelper;
import org.mapleir.asm.ClassNode;
import org.mapleir.asm.MethodNode;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NativeLateTransformPreservationTest {
    @Test
    void nativeReferencedCalleeIsRejectedByMergeAndSignatureCandidates() throws Exception {
        final Skidfuscator skidfuscator = new Skidfuscator(mock(SkidfuscatorSession.class));
        final ClassNode owner = owner("sample/Callee");
        final MethodNode callee = owner.getMethods().get(0);
        skidfuscator.reserveNativeReferencedMember(
                owner.getName(), callee.getName(), callee.getDesc());

        final SkidGroup group = mock(SkidGroup.class);
        when(group.isInjectedMethodPredicate()).thenReturn(true);
        when(group.getMethodNodeList()).thenReturn(List.of(callee));
        when(group.isAnnotation()).thenReturn(false);
        when(group.isEnumerator()).thenReturn(false);
        when(group.isMixin()).thenReturn(false);
        when(group.isNatived()).thenReturn(false);
        when(group.first()).thenReturn(callee);

        final MethodMergeTransformer merge = new MethodMergeTransformer(skidfuscator);
        final Method mergeCandidate = MethodMergeTransformer.class.getDeclaredMethod(
                "toCandidate", SkidGroup.class, Set.class);
        mergeCandidate.setAccessible(true);
        assertNull(mergeCandidate.invoke(merge, group, Set.of()),
                "native-referenced member must never become a merge candidate");

        final SignatureObfuscationTransformer signature =
                new SignatureObfuscationTransformer(skidfuscator);
        final Class<?> keyClass = java.util.Arrays.stream(
                        SignatureObfuscationTransformer.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("MethodKey"))
                .findFirst().orElseThrow();
        final Constructor<?> constructor = keyClass.getDeclaredConstructor(
                String.class, String.class, String.class);
        constructor.setAccessible(true);
        final Object key = constructor.newInstance(owner.getName(), callee.getName(), callee.getDesc());
        final Method signatureCandidate = SignatureObfuscationTransformer.class.getDeclaredMethod(
                "buildCandidate", keyClass, org.objectweb.asm.tree.MethodNode.class,
                Map.class, Set.class, Map.class, boolean.class, boolean.class, boolean.class);
        signatureCandidate.setAccessible(true);
        final Map<Object, Integer> internalCalls = new HashMap<>();
        internalCalls.put(key, 1);
        assertNull(signatureCandidate.invoke(signature, key, callee.node,
                        Map.of(owner.getName(), owner.node), Set.of(), internalCalls,
                        true, true, false),
                "native-referenced member must never become a signature candidate");
    }

    private static ClassNode owner(final String name) {
        final org.objectweb.asm.tree.ClassNode raw = new org.objectweb.asm.tree.ClassNode();
        raw.version = Opcodes.V1_8;
        raw.access = Opcodes.ACC_PUBLIC;
        raw.name = name;
        raw.superName = "java/lang/Object";
        final org.objectweb.asm.tree.MethodNode method = new org.objectweb.asm.tree.MethodNode(
                Opcodes.ASM9, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "target", "(I)I", null, null);
        method.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.IRETURN));
        method.maxLocals = 1;
        method.maxStack = 1;
        raw.methods.add(method);
        return ClassHelper.create(raw);
    }
}
