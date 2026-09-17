package dev.skidfuscator.obfuscator.creator;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapleir.asm.ClassNode;
import org.mapleir.asm.MethodNode;
import org.mapleir.flowgraph.ExceptionRange;
import org.mapleir.ir.cfg.BasicBlock;
import org.mapleir.ir.cfg.ControlFlowGraph;
import org.mapleir.ir.cfg.builder.ControlFlowGraphBuilder;
import org.mapleir.ir.cfg.builder.GenerationPass;
import org.mapleir.ir.cfg.builder.GenerationPassV2;
import org.mapleir.ir.codegen.ControlFlowGraphDumper;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExceptionTablePriorityTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void interleavedHandlersKeepFirstMatchPriority(boolean v2) throws Exception {
        for (boolean skidDumper : new boolean[] {false, true}) {
            Fixture fixture = new Fixture(false);
            assertBehavior(fixture.owner, fixture.method); // Establish original JVM semantics.
            ControlFlowGraph graph = generate(fixture.method, v2);
            List<ExceptionRange<BasicBlock>> ranges = new ArrayList<>(graph.getRanges());
            assertEquals(3, ranges.size());
            assertSame(ranges.get(0).getHandler(), ranges.get(2).getHandler());
            assertNotSame(ranges.get(0).getHandler(), ranges.get(1).getHandler());
            assertEquals(new HashSet<>(Arrays.asList(Type.getType(IllegalArgumentException.class))),
                    ranges.get(0).getTypes());
            dump(graph, fixture.method, skidDumper);
            assertBehavior(fixture.owner, fixture.method);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void adjacentMulticatchStillSharesOneRange(boolean v2) throws Exception {
        for (boolean skidDumper : new boolean[] {false, true}) {
            Fixture fixture = new Fixture(true);
            ControlFlowGraph graph = generate(fixture.method, v2);
            List<ExceptionRange<BasicBlock>> ranges = new ArrayList<>(graph.getRanges());
            assertEquals(2, ranges.size());
            assertEquals(new HashSet<>(Arrays.asList(Type.getType(IllegalArgumentException.class),
                    Type.getType(ArithmeticException.class))), ranges.get(0).getTypes());
            dump(graph, fixture.method, skidDumper);
            Method test = load(fixture.owner, fixture.method);
            assertEquals(1, test.invoke(null, new IllegalArgumentException()));
            assertEquals(1, test.invoke(null, new ArithmeticException()));
            assertEquals(2, test.invoke(null, new NullPointerException()));
        }
    }

    private static ControlFlowGraph generate(MethodNode method, boolean v2) {
        // Run the real generation phase without SSA conversion, so its output
        // can be dumped directly and its range grouping can be inspected.
        ControlFlowGraphBuilder builder = new ControlFlowGraphBuilder(method) {
            @Override protected BuilderPass[] resolvePasses() {
                return new BuilderPass[] {v2 ? new GenerationPassV2(this) : new GenerationPass(this)};
            }
        };
        return builder.buildImpl();
    }

    private static void dump(ControlFlowGraph graph, MethodNode method, boolean skid) {
        if (skid) new SkidFlowGraphDumper(null, graph, method).dump();
        else new ControlFlowGraphDumper(graph, method).dump();
    }

    private static void assertBehavior(ClassNode owner, MethodNode method) throws Exception {
        Method test = load(owner, method);
        assertEquals(1, test.invoke(null, new IllegalArgumentException()));
        // The middle RuntimeException entry must win over the later
        // ArithmeticException entry that reuses the first handler.
        assertEquals(2, test.invoke(null, new ArithmeticException()));
        assertEquals(2, test.invoke(null, new NullPointerException()));
        Error uncaught = new AssertionError("must escape unchanged");
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> test.invoke(null, uncaught));
        assertSame(uncaught, failure.getCause());
    }

    private static Method load(ClassNode owner, MethodNode method) throws Exception {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.node.accept(writer);
        byte[] bytes = writer.toByteArray();
        Class<?> type = new ClassLoader(ExceptionTablePriorityTest.class.getClassLoader()) {
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        }.define();
        return type.getMethod(method.getName(), Throwable.class);
    }

    private static final class Fixture {
        final ClassNode owner = new ClassNode();
        final MethodNode method;

        Fixture(boolean adjacent) {
            owner.node.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "fixture/ExceptionPriority", null,
                    "java/lang/Object", null);
            method = new MethodNode(new org.objectweb.asm.tree.MethodNode(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "test", "(Ljava/lang/Throwable;)I", null, null), owner);
            owner.addMethod(method);
            Label start = new Label(), end = new Label(), second = new Label();
            Label first = end;
            method.node.visitCode();
            method.node.visitTryCatchBlock(start, end, first, "java/lang/IllegalArgumentException");
            if (adjacent) {
                method.node.visitTryCatchBlock(start, end, first, "java/lang/ArithmeticException");
                method.node.visitTryCatchBlock(start, end, second, "java/lang/RuntimeException");
            } else {
                method.node.visitTryCatchBlock(start, end, second, "java/lang/RuntimeException");
                method.node.visitTryCatchBlock(start, end, first, "java/lang/ArithmeticException");
            }
            method.node.visitLabel(start);
            method.node.visitVarInsn(Opcodes.ALOAD, 0);
            method.node.visitInsn(Opcodes.ATHROW);
            method.node.visitLabel(end);
            method.node.visitInsn(Opcodes.POP);
            method.node.visitInsn(Opcodes.ICONST_1);
            method.node.visitInsn(Opcodes.IRETURN);
            method.node.visitLabel(second);
            method.node.visitInsn(Opcodes.POP);
            method.node.visitInsn(Opcodes.ICONST_2);
            method.node.visitInsn(Opcodes.IRETURN);
            method.node.visitMaxs(1, 1);
            method.node.visitEnd();
        }
    }
}
