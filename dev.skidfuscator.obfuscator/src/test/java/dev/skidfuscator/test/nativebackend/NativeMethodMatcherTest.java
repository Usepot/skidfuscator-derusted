package dev.skidfuscator.test.nativebackend;

import dev.skidfuscator.obfuscator.nativebackend.selection.NativeMethodMatcher;
import org.junit.jupiter.api.Test;
import org.mapleir.asm.ClassNode;
import org.mapleir.asm.MethodNode;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeMethodMatcherTest {
    @Test
    void combinedClassAndMethodClausesUseAndSemantics() {
        MethodNode selected = method("demo/Selected", "compute", "(I)I", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        MethodNode wrongClass = method("demo/Other", "compute", "(I)I", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        MethodNode wrongMethod = method("demo/Selected", "other", "(I)I", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);

        NativeMethodMatcher matcher = NativeMethodMatcher.compile(
                "class{^demo/Selected$} method{public static compute#\\(I\\)I}"
        );

        assertTrue(matcher.matches(selected));
        assertFalse(matcher.matches(wrongClass));
        assertFalse(matcher.matches(wrongMethod));
    }

    @Test
    void supportsClassOnlyMethodOnlyAndQualifiedRawPatterns() {
        MethodNode method = method("demo/Selected", "compute", "(J)J", Opcodes.ACC_PRIVATE);

        assertTrue(NativeMethodMatcher.compile("class{^demo/}").matches(method));
        assertTrue(NativeMethodMatcher.compile("method{private compute}").matches(method));
        assertTrue(NativeMethodMatcher.compile("demo/Selected#compute\\(J\\)J").matches(method));
        assertFalse(NativeMethodMatcher.compile("method{public compute}").matches(method));
    }

    @Test
    void preservesRegexQuantifiersInsideClauses() {
        MethodNode method = method("demo/Selected12", "compute", "()V", Opcodes.ACC_PUBLIC);
        assertTrue(NativeMethodMatcher.compile("class{^demo/Selected[0-9]{2}$}").matches(method));
        assertTrue(NativeMethodMatcher.compile("method{compute{1}}").matches(method));
    }

    @Test
    void rejectsMalformedMatchers() {
        assertThrows(IllegalArgumentException.class, () -> NativeMethodMatcher.compile(""));
        assertThrows(IllegalArgumentException.class, () -> NativeMethodMatcher.compile("class{}"));
        assertThrows(IllegalArgumentException.class, () -> NativeMethodMatcher.compile("class{foo} junk"));
        assertThrows(IllegalArgumentException.class, () -> NativeMethodMatcher.compile("method{interface foo}"));
        assertThrows(IllegalArgumentException.class, () -> NativeMethodMatcher.compile("class{synchronized foo}"));
    }

    private static MethodNode method(String owner, String name, String descriptor, int access) {
        ClassNode classNode = new ClassNode();
        classNode.node.name = owner;
        classNode.node.access = Opcodes.ACC_PUBLIC;
        org.objectweb.asm.tree.MethodNode asm = new org.objectweb.asm.tree.MethodNode(
                access,
                name,
                descriptor,
                null,
                null
        );
        MethodNode method = new MethodNode(asm, classNode);
        classNode.addMethod(method);
        return method;
    }
}
