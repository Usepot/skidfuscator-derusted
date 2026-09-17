package dev.skidfuscator.obfuscator.creator;

import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.number.hash.impl.BitwiseHashTransformer;
import dev.skidfuscator.obfuscator.skidasm.SkidClassNode;
import dev.skidfuscator.obfuscator.skidasm.SkidMethodNode;
import dev.skidfuscator.obfuscator.skidasm.cfg.SkidControlFlowGraph;
import org.junit.jupiter.api.Test;
import org.mapleir.ir.cfg.BasicBlock;
import org.mapleir.ir.codegen.ControlFlowGraphDumper;
import org.mapleir.ir.locals.impl.StaticMethodLocalsPool;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BitwiseHashRotationTest {
    @Test
    void evaluatorAndEmittedBytecodeAreInvertibleRotations() throws Exception {
        Skidfuscator skidfuscator = mock(Skidfuscator.class, RETURNS_DEEP_STUBS);
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        node.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "fixture/BitwiseRotation", null,
                "java/lang/Object", null);
        SkidClassNode owner = new SkidClassNode(node, skidfuscator);
        when(skidfuscator.getFactoryNode()).thenReturn(owner);
        SkidControlFlowGraph[] generated = new SkidControlFlowGraph[1];
        SkidMethodNode[] generatedMethod = new SkidMethodNode[1];
        when(skidfuscator.getIrFactory().getFor(any())).thenAnswer(invocation -> {
            SkidMethodNode method = invocation.getArgument(0);
            SkidControlFlowGraph graph = new SkidControlFlowGraph(new StaticMethodLocalsPool(), method);
            BasicBlock entry = new BasicBlock(graph);
            graph.addVertex(entry);
            graph.getEntries().add(entry);
            generated[0] = graph;
            generatedMethod[0] = method;
            return graph;
        });
        BitwiseHashTransformer transformer = new BitwiseHashTransformer(skidfuscator);
        new ControlFlowGraphDumper(generated[0], generatedMethod[0]).dump();
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.node.accept(writer);
        byte[] bytes = writer.toByteArray();
        Class<?> type = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        }.define();
        Method emitted = type.getMethod(generatedMethod[0].getName(), int.class);

        // These negative inputs collided under the old arithmetic right shift.
        assertNotEquals(transformer.hash(Integer.MIN_VALUE), transformer.hash(Integer.MIN_VALUE + 1));
        int[] boundaries = {0, 1, -1, Integer.MIN_VALUE, Integer.MIN_VALUE + 1, Integer.MAX_VALUE};
        for (int value : boundaries) check(transformer, emitted, value);
        for (int bit = 0; bit < 32; bit++) {
            check(transformer, emitted, 1 << bit);
            check(transformer, emitted, ~(1 << bit));
        }
        // Exercise each possible upper-three-bit pattern with varied low bits.
        Random random = new Random(0xB17);
        for (int upper = 0; upper < 8; upper++) {
            for (int sample = 0; sample < 1024; sample++) {
                check(transformer, emitted, (upper << 29) | (random.nextInt() & 0x1fffffff));
            }
        }
    }

    private static void check(BitwiseHashTransformer transformer, Method emitted, int value) throws Exception {
        int expected = Integer.rotateLeft(value, 3);
        assertEquals(expected, transformer.hash(value), "evaluator input " + value);
        int actual = (Integer) emitted.invoke(null, value);
        assertEquals(expected, actual, "bytecode input " + value);
        assertEquals(value, Integer.rotateRight(actual, 3), "inverse input " + value);
    }
}
