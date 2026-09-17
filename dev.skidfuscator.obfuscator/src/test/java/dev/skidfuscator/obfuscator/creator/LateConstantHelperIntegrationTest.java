package dev.skidfuscator.obfuscator.creator;

import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.util.NumericConstantSpiller;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.topdank.byteengineer.commons.data.JarClassData;
import org.topdank.byteengineer.commons.data.JarContents;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LateConstantHelperIntegrationTest {
    @Test void registeredHelpersRetainFramesWhenTheirNamespaceIsExemptFromFurtherPasses() throws Exception {
        ClassNode owner = new ClassNode();
        owner.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "fixture/SpillRegistration", null,
                "java/lang/Object", null);
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "value", "()J", null, null);
        for (int i = 0; i < 700; i++) {
            method.instructions.add(new LdcInsnNode((1L << 40) + i));
            if (i != 699) method.instructions.add(new InsnNode(Opcodes.POP2));
        }
        method.instructions.add(new InsnNode(Opcodes.LRETURN));
        method.maxStack = 2;
        owner.methods.add(method);
        org.mapleir.asm.ClassNode wrapper = new org.mapleir.asm.ClassNode();
        wrapper.node = owner;
        JarContents contents = new JarContents();
        contents.getClassContents().add(new JarClassData(owner.name + ".class", new byte[0], wrapper));
        Skidfuscator skid = mock(Skidfuscator.class, RETURNS_DEEP_STUBS);
        when(skid.getJarContents()).thenReturn(contents);
        when(skid.getConfig().getBoolean("constantPoolSplit.enabled", true)).thenReturn(true);
        when(skid.getConfig().getInt("constantPoolSplit.threshold", 45000)).thenReturn(1000);

        // Exercise real helper creation/registration, not just split() in isolation.
        NumericConstantSpiller.apply(skid);
        assertEquals(2, contents.getClassContents().size());
        Map<String, byte[]> classes = new HashMap<>();
        for (JarClassData data : contents.getClassContents()) {
            ClassNode node = data.getClassNode().node;
            boolean generated = node != owner;
            if (generated) {
                assertTrue(node.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray()))
                        .anyMatch(i -> i instanceof FrameNode), "Registration discarded generated stack maps");
            }
            // Mirrors output: application bodies recompute frames, but helpers
            // matching the user's library exclusion use a pass-through writer.
            ClassWriter writer = new ClassWriter(generated ? 0 : ClassWriter.COMPUTE_FRAMES);
            node.accept(writer);
            classes.put(node.name.replace('/', '.'), writer.toByteArray());
        }
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        for (String name : classes.keySet()) loader.loadClass(name).getDeclaredMethods();
        assertEquals((1L << 40) + 699, loader.loadClass(owner.name.replace('/', '.')).getMethod("value").invoke(null));
    }
}
