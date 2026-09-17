package dev.skidfuscator.obfuscator.creator;

import dev.skidfuscator.obfuscator.util.ConstantPoolBudget;
import dev.skidfuscator.obfuscator.util.NumericConstantSpiller;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConstantPoolRegressionTest {
    @Test void spillsAnOtherwiseUnserializablePoolWithoutChangingExecutableResults() throws Exception {
        ClassNode node = owner("fixture/HugeConstants");
        for (int m = 0; m < 70; m++) {
            MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "m" + m, "()J", null, null);
            for (int i = 0; i < 1000; i++) {
                method.instructions.add(new LdcInsnNode((1L << 40) + 1000L * m + i));
                if (i != 999) method.instructions.add(new InsnNode(Opcodes.POP2));
            }
            method.instructions.add(new InsnNode(Opcodes.LRETURN));
            method.maxStack = 2;
            node.methods.add(method);
        }
        assertTrue(ConstantPoolBudget.count(node) > 65535);
        AtomicInteger names = new AtomicInteger();
        NumericConstantSpiller.SplitResult result = NumericConstantSpiller.split(node,
                () -> "fixture/Page" + names.getAndIncrement());
        assertEquals(70000, result.movedLoads());
        assertTrue(result.sizeGuardedMethods().isEmpty());
        assertTrue(ConstantPoolBudget.count(node) < 2000);
        for (ClassNode helper : result.helpers()) {
            assertTrue(ConstantPoolBudget.count(helper) < 3000);
            assertTrue(helper.fields.isEmpty());
            assertTrue(helper.methods.stream().noneMatch(m -> m.name.equals("<clinit>")));
        }
        Class<?> type = load(node, result.helpers());
        for (int m = 0; m < 70; m++) {
            assertEquals((1L << 40) + 1000L * m + 999, type.getMethod("m" + m).invoke(null));
        }
    }

    @Test void preservesEveryBitOfSignedIntAndLongBoundaryValues() throws Exception {
        ClassNode node = owner("fixture/BoundaryConstants");
        List<Object> values = Arrays.asList(Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE,
                Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE, 0xFEDCBA9876543210L);
        for (int i = 0; i < values.size(); i++) {
            boolean wide = values.get(i) instanceof Long;
            MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "m" + i, wide ? "()J" : "()I", null, null);
            method.instructions.add(new LdcInsnNode(values.get(i)));
            method.instructions.add(new InsnNode(wide ? Opcodes.LRETURN : Opcodes.IRETURN));
            method.maxStack = 2;
            node.methods.add(method);
        }
        AtomicInteger names = new AtomicInteger();
        NumericConstantSpiller.SplitResult result = NumericConstantSpiller.split(node,
                () -> "fixture/BoundaryPage" + names.getAndIncrement());
        Class<?> type = load(node, result.helpers());
        for (int i = 0; i < values.size(); i++) assertEquals(values.get(i), type.getMethod("m" + i).invoke(null));
    }

    @Test void refusesToInflateANearLimitMethod() {
        ClassNode node = owner("fixture/NearMethodLimit");
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "m", "()I", null, null);
        for (int i = 0; i < 61000; i++) method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(new LdcInsnNode(123456));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(method);
        NumericConstantSpiller.SplitResult result = NumericConstantSpiller.split(node, () -> "fixture/Unused");
        assertEquals(0, result.movedLoads());
        assertEquals(Collections.singletonList("m()I"), result.sizeGuardedMethods());
        assertTrue(result.helpers().isEmpty());
    }

    @Test void budgetsRemainBoundedEvenForInvalidOrAlreadyOversizedInputs() {
        assertEquals(0, ConstantPoolBudget.allowance(100000, 1024, 16));
        int sites = ConstantPoolBudget.allowance(10000, 1024, 16);
        assertTrue(10000L + 1024 + 16L * sites <= ConstantPoolBudget.SOFT_LIMIT);
        assertThrows(IllegalArgumentException.class, () -> ConstantPoolBudget.allowance(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ConstantPoolBudget.allowance(-1, 0, 16));
    }

    private static ClassNode owner(String name) {
        ClassNode node = new ClassNode();
        node.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        return node;
    }

    private static Class<?> load(ClassNode owner, List<ClassNode> helpers) throws ClassNotFoundException {
        Map<String, byte[]> classes = new HashMap<>();
        List<ClassNode> all = new ArrayList<>(helpers);
        all.add(owner);
        for (ClassNode node : all) {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
            node.accept(writer);
            classes.put(node.name.replace('/', '.'), writer.toByteArray());
        }
        ClassLoader loader = new ClassLoader(ConstantPoolRegressionTest.class.getClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        return loader.loadClass(owner.name.replace('/', '.'));
    }
}
