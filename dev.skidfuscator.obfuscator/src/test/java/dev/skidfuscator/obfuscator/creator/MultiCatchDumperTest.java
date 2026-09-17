package dev.skidfuscator.obfuscator.creator;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapleir.asm.ClassNode;
import org.mapleir.asm.MethodNode;
import org.mapleir.flowgraph.ExceptionRange;
import org.mapleir.flowgraph.edges.TryCatchEdge;
import org.mapleir.ir.cfg.BasicBlock;
import org.mapleir.ir.cfg.ControlFlowGraph;
import org.mapleir.ir.code.expr.CaughtExceptionExpr;
import org.mapleir.ir.code.expr.ConstantExpr;
import org.mapleir.ir.code.expr.VarExpr;
import org.mapleir.ir.code.stmt.PopStmt;
import org.mapleir.ir.code.stmt.ReturnStmt;
import org.mapleir.ir.code.stmt.ThrowStmt;
import org.mapleir.ir.codegen.ControlFlowGraphDumper;
import org.mapleir.ir.locals.impl.StaticMethodLocalsPool;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class MultiCatchDumperTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void preservesExactCatchAlternativesAndLetsUnrelatedExceptionsEscape(boolean skid) throws Exception {
        ClassNode owner = new ClassNode();
        owner.node.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "fixture/ExactMultiCatch", null,
                "java/lang/Object", null);
        MethodNode method = new MethodNode(new org.objectweb.asm.tree.MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "test", "(Ljava/lang/Throwable;)I", null, null), owner);
        owner.addMethod(method);
        StaticMethodLocalsPool locals = new StaticMethodLocalsPool();
        ControlFlowGraph graph = new ControlFlowGraph(locals, method);
        BasicBlock entry = new BasicBlock(graph);
        BasicBlock handler = new BasicBlock(graph);
        graph.addVertex(entry);
        graph.addVertex(handler);
        graph.getEntries().add(entry);
        entry.add(new ThrowStmt(new VarExpr(locals.get(0), Type.getType(Throwable.class))));
        handler.add(new PopStmt(new CaughtExceptionExpr(Type.getType(RuntimeException.class))));
        handler.add(new ReturnStmt(Type.INT_TYPE, new ConstantExpr(1)));
        ExceptionRange<BasicBlock> range = new ExceptionRange<>();
        range.setHandler(handler);
        range.addVertex(entry);
        range.addType(Type.getType(IllegalArgumentException.class));
        range.addType(Type.getType(ArithmeticException.class));
        graph.addRange(range);
        graph.addEdge(new TryCatchEdge<>(entry, range));

        if (skid) new SkidFlowGraphDumper(null, graph, method).dump();
        else new ControlFlowGraphDumper(graph, method).dump();

        Set<String> actual = method.node.tryCatchBlocks.stream().map(block -> block.type).collect(Collectors.toSet());
        assertEquals(new HashSet<>(Arrays.asList("java/lang/IllegalArgumentException", "java/lang/ArithmeticException")), actual);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        owner.node.accept(writer);
        byte[] bytes = writer.toByteArray();
        Class<?> type = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        }.define();
        Method test = type.getMethod("test", Throwable.class);
        assertEquals(1, test.invoke(null, new IllegalArgumentException()));
        assertEquals(1, test.invoke(null, new ArithmeticException()));
        NullPointerException unrelated = new NullPointerException("must escape unchanged");
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> test.invoke(null, unrelated));
        assertSame(unrelated, thrown.getCause());
    }
}
