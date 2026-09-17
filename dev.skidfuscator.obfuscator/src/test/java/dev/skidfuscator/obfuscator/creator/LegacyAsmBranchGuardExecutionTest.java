package dev.skidfuscator.obfuscator.creator;

import dev.skidfuscator.obfuscator.compatibility.LegacyAsmBranchGuard;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.lang.invoke.*;
import java.lang.reflect.Method;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class LegacyAsmBranchGuardExecutionTest implements Opcodes {
    public static CallSite bootstrap(MethodHandles.Lookup lookup, String name, MethodType type) {
        return new ConstantCallSite(MethodHandles.constant(int.class, 42).asType(type));
    }
    private static ClassNode type() {
        ClassNode node = new ClassNode();
        node.visit(V1_8, ACC_PUBLIC, "fixture/LongBranches", null, "java/lang/Object", null);
        MethodNode marker = new MethodNode(ACC_PUBLIC | ACC_STATIC, "marker", "()I", null, null);
        marker.instructions.add(new InvokeDynamicInsnNode("value", "()I", new Handle(H_INVOKESTATIC,
                Type.getInternalName(LegacyAsmBranchGuardExecutionTest.class), "bootstrap",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false)));
        marker.instructions.add(new InsnNode(IRETURN));
        node.methods.add(marker);
        return node;
    }
    private static Class<?> load(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        byte[] bytes = writer.toByteArray();
        return new ClassLoader(LegacyAsmBranchGuardExecutionTest.class.getClassLoader()) {
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        }.define();
    }
    private static void gap(InsnList code) { for(int i=0;i<34000;i++) code.add(new InsnNode(NOP)); }
    private static void result(InsnList code, int value) {
        code.add(new IntInsnNode(BIPUSH, value)); code.add(new InsnNode(IRETURN));
    }

    @Test void allIntegerComparisonsPreserveBothOutcomesAndIndyStillExecutes() throws Exception {
        int[] opcodes={IFEQ,IFNE,IFLT,IFGE,IFGT,IFLE,IF_ICMPEQ,IF_ICMPNE,IF_ICMPLT,IF_ICMPGE,IF_ICMPGT,IF_ICMPLE};
        for(int opcode:opcodes) {
            ClassNode owner=type(); boolean binary=opcode>=IF_ICMPEQ;
            MethodNode method=new MethodNode(ACC_PUBLIC|ACC_STATIC,"run",binary?"(II)I":"(I)I",null,null);
            LabelNode target=new LabelNode();
            method.instructions.add(new VarInsnNode(ILOAD,0));
            if(binary) method.instructions.add(new VarInsnNode(ILOAD,1));
            method.instructions.add(new JumpInsnNode(opcode,target)); gap(method.instructions);
            result(method.instructions,11); method.instructions.add(target); result(method.instructions,29);
            owner.methods.add(method);
            assertEquals(1,LegacyAsmBranchGuard.apply(owner));
            assertEquals(0,LegacyAsmBranchGuard.apply(owner),"Must be idempotent");
            Class<?> loaded=load(owner);
            assertEquals(42,loaded.getMethod("marker").invoke(null));
            Method run=binary?loaded.getMethod("run",int.class,int.class):loaded.getMethod("run",int.class);
            for(int value:new int[]{-1,0,1}) {
                boolean taken=switch(opcode) {
                    case IFEQ,IF_ICMPEQ -> value==0;
                    case IFNE,IF_ICMPNE -> value!=0;
                    case IFLT,IF_ICMPLT -> value<0;
                    case IFGE,IF_ICMPGE -> value>=0;
                    case IFGT,IF_ICMPGT -> value>0;
                    default -> value<=0;
                };
                assertEquals(taken?29:11,binary?run.invoke(null,value,0):run.invoke(null,value),"opcode="+opcode);
            }
        }
    }

    @Test void referenceAndNullComparisonsPreserveBothOutcomes() throws Exception {
        for(int opcode:new int[]{IF_ACMPEQ,IF_ACMPNE,IFNULL,IFNONNULL}) {
            boolean binary=opcode==IF_ACMPEQ||opcode==IF_ACMPNE;
            ClassNode owner=type();
            MethodNode method=new MethodNode(ACC_PUBLIC|ACC_STATIC,"run",binary?"(Ljava/lang/Object;Ljava/lang/Object;)I":"(Ljava/lang/Object;)I",null,null);
            LabelNode target=new LabelNode(); method.instructions.add(new VarInsnNode(ALOAD,0));
            if(binary) method.instructions.add(new VarInsnNode(ALOAD,1));
            method.instructions.add(new JumpInsnNode(opcode,target)); gap(method.instructions);
            result(method.instructions,11); method.instructions.add(target); result(method.instructions,29);
            owner.methods.add(method); assertEquals(1,LegacyAsmBranchGuard.apply(owner)); Class<?> loaded=load(owner);
            Method run=binary?loaded.getMethod("run",Object.class,Object.class):loaded.getMethod("run",Object.class);
            Object object=new Object();
            for(Object value:new Object[]{null,object}) {
                boolean taken=(opcode==IFNULL||opcode==IF_ACMPEQ)?value==null:value!=null;
                assertEquals(taken?29:11,binary?run.invoke(null,value,null):run.invoke(null,new Object[]{value}));
            }
        }
    }

    @Test void backwardTransfersPreserveLiveOperandStackAndFiniteLoop() throws Exception {
        ClassNode owner=type(); MethodNode method=new MethodNode(ACC_PUBLIC|ACC_STATIC,"run","(I)I",null,null);
        LabelNode loop=new LabelNode(), done=new LabelNode();
        method.instructions.add(new IntInsnNode(BIPUSH,42)); // live value across all inserted switches
        method.instructions.add(loop); method.instructions.add(new VarInsnNode(ILOAD,0));
        method.instructions.add(new JumpInsnNode(IFEQ,done)); gap(method.instructions);
        method.instructions.add(new IincInsnNode(0,-1)); method.instructions.add(new JumpInsnNode(GOTO,loop));
        method.instructions.add(done); method.instructions.add(new InsnNode(IRETURN)); owner.methods.add(method);
        assertEquals(2,LegacyAsmBranchGuard.apply(owner));
        assertEquals(42,load(owner).getMethod("run",int.class).invoke(null,3));
    }

    @Test void terminalBackwardTransfersUseNonEmptySwitchesAtEveryAlignment() throws Exception {
        for (int padding = 0; padding < 4; padding++) {
            ClassNode owner = type();
            MethodNode method = new MethodNode(ACC_PUBLIC | ACC_STATIC, "run", "(I)I", null, null);
            LabelNode loop = new LabelNode(), body = new LabelNode();
            method.instructions.add(loop);
            method.instructions.add(new VarInsnNode(ILOAD, 0));
            method.instructions.add(new JumpInsnNode(IFNE, body));
            result(method.instructions, 42);
            method.instructions.add(body);
            gap(method.instructions);
            for (int i = 0; i < padding; i++) method.instructions.add(new InsnNode(NOP));
            method.instructions.add(new IincInsnNode(0, -1));
            method.instructions.add(new JumpInsnNode(GOTO, loop));
            owner.methods.add(method);
            assertEquals(1, LegacyAsmBranchGuard.apply(owner));
            LookupSwitchInsnNode transfer = (LookupSwitchInsnNode) method.instructions.getLast();
            assertEquals(List.of(0), transfer.keys);
            assertEquals(1, transfer.labels.size());
            assertSame(transfer.dflt, transfer.labels.get(0));
            assertEquals(42, load(owner).getMethod("run", int.class).invoke(null, 3));
        }
    }

    @Test void nonBootstrapClassesRemainUntouched() {
        ClassNode owner=type(); owner.methods.clear();
        MethodNode method=new MethodNode(ACC_PUBLIC|ACC_STATIC,"run","()V",null,null);
        LabelNode target=new LabelNode(); JumpInsnNode original=new JumpInsnNode(GOTO,target);
        method.instructions.add(original); gap(method.instructions); method.instructions.add(target);
        method.instructions.add(new InsnNode(RETURN)); owner.methods.add(method);
        assertEquals(0,LegacyAsmBranchGuard.apply(owner)); assertSame(original,method.instructions.getFirst());
    }
}
