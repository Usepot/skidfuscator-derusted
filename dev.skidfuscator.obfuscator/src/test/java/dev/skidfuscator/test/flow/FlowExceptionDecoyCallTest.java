package dev.skidfuscator.test.flow;

import dev.skidfuscator.core.SkidTest;
import dev.skidfuscator.testclasses.TestRun;
import dev.skidfuscator.testclasses.flow.FlowExceptionDecoyCall;
import org.junit.jupiter.api.Assertions;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class FlowExceptionDecoyCallTest extends SkidTest {
    private static final String OWNER = "dev/skidfuscator/testclasses/flow/FlowExceptionDecoyCall";

    @Override
    public Class<? extends TestRun> getMainClass() {
        return FlowExceptionDecoyCall.class;
    }

    @Override
    public Class<?>[] getClasses() {
        return new Class[]{
                FlowExceptionDecoyCall.class
        };
    }

    @Override
    public String getConfigPath() {
        return "/config/decoy_calls.hocon";
    }

    @Override
    public void receiveAndExecute(List<Map.Entry<String, byte[]>> output) {
        final byte[] bytes = output.stream()
                .filter(entry -> OWNER.equals(entry.getKey()))
                .findFirst()
                .map(Map.Entry::getValue)
                .orElseThrow(IllegalStateException::new);

        final org.objectweb.asm.tree.ClassNode classNode = new org.objectweb.asm.tree.ClassNode();
        new ClassReader(bytes).accept(classNode, 0);

        int sameOwnerCalls = 0;
        final List<String> exceptionAllocations = new ArrayList<>();

        for (MethodNode method : classNode.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                 instruction != null;
                 instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode) {
                    final MethodInsnNode methodInsn = (MethodInsnNode) instruction;
                    if (OWNER.equals(methodInsn.owner) && !"<init>".equals(methodInsn.name)) {
                        sameOwnerCalls++;
                    }
                }

                if (instruction.getOpcode() == Opcodes.NEW
                        && instruction instanceof TypeInsnNode) {
                    final TypeInsnNode typeInsn = (TypeInsnNode) instruction;
                    if (typeInsn.desc.endsWith("Exception")
                            || typeInsn.desc.endsWith("Error")
                            || "java/lang/Throwable".equals(typeInsn.desc)) {
                        exceptionAllocations.add(method.name + method.desc + " -> " + typeInsn.desc);
                    }
                }
            }
        }

        Assertions.assertTrue(sameOwnerCalls > 3, "same-owner calls: " + sameOwnerCalls);
        Assertions.assertTrue(exceptionAllocations.isEmpty(), exceptionAllocations.toString());

        super.receiveAndExecute(output);
    }
}
