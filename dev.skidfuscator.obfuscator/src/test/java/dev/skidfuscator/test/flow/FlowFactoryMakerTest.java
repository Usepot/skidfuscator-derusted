package dev.skidfuscator.test.flow;

import dev.skidfuscator.core.SkidTest;
import dev.skidfuscator.testclasses.TestRun;
import dev.skidfuscator.testclasses.flow.FlowFactoryMakerSample;
import org.junit.jupiter.api.Assertions;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class FlowFactoryMakerTest extends SkidTest {
    private static final String OWNER = "dev/skidfuscator/testclasses/flow/FlowFactoryMakerSample";

    @Override
    public Class<? extends TestRun> getMainClass() {
        return FlowFactoryMakerSample.class;
    }

    @Override
    public Class<?>[] getClasses() {
        return new Class[]{
                FlowFactoryMakerSample.class
        };
    }

    @Override
    public String getConfigPath() {
        return "/config/flow_factory_maker.hocon";
    }

    @Override
    public void receiveAndExecute(final List<Map.Entry<String, byte[]>> output) {
        final ClassNode classNode = readOutputClass(output);

        final Set<String> generatedFields = classNode.fields.stream()
                .filter(field -> (field.access & Opcodes.ACC_SYNTHETIC) != 0)
                .filter(field -> (field.access & Opcodes.ACC_STATIC) != 0)
                .filter(field -> "I".equals(field.desc))
                .map(field -> field.name)
                .collect(Collectors.toSet());

        final Set<String> generatedGetters = classNode.methods.stream()
                .filter(method -> (method.access & Opcodes.ACC_SYNTHETIC) != 0)
                .filter(method -> (method.access & Opcodes.ACC_STATIC) != 0)
                .filter(method -> "()I".equals(method.desc))
                .filter(method -> loadsGeneratedField(method, generatedFields))
                .map(method -> method.name)
                .collect(Collectors.toSet());

        Assertions.assertFalse(generatedFields.isEmpty(), "No synthetic int seed fields were materialized");
        Assertions.assertFalse(generatedGetters.isEmpty(), "No synthetic int seed getter factories were materialized");
        Assertions.assertTrue(callsGeneratedGetter(classNode, generatedGetters),
                "Generated seed getter was not used by transformed predicate flow");
        Assertions.assertFalse(callsIntegerParseInt(classNode),
                "Opaque predicate seed creation still calls Integer.parseInt directly");

        super.receiveAndExecute(output);
    }

    private ClassNode readOutputClass(final List<Map.Entry<String, byte[]>> output) {
        final byte[] bytes = output.stream()
                .filter(entry -> OWNER.equals(entry.getKey()))
                .findFirst()
                .map(Map.Entry::getValue)
                .orElseThrow(IllegalStateException::new);

        final ClassNode classNode = new ClassNode();
        new ClassReader(bytes).accept(classNode, 0);
        return classNode;
    }

    private boolean loadsGeneratedField(final MethodNode method,
                                        final Set<String> generatedFields) {
        for (AbstractInsnNode instruction = method.instructions.getFirst();
             instruction != null;
             instruction = instruction.getNext()) {
            if (instruction.getOpcode() != Opcodes.GETSTATIC
                    || !(instruction instanceof FieldInsnNode)) {
                continue;
            }

            final FieldInsnNode fieldInsn = (FieldInsnNode) instruction;
            if (OWNER.equals(fieldInsn.owner) && generatedFields.contains(fieldInsn.name)) {
                return true;
            }
        }

        return false;
    }

    private boolean callsGeneratedGetter(final ClassNode classNode,
                                         final Set<String> generatedGetters) {
        for (MethodNode method : classNode.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                 instruction != null;
                 instruction = instruction.getNext()) {
                if (instruction.getOpcode() != Opcodes.INVOKESTATIC
                        || !(instruction instanceof MethodInsnNode)) {
                    continue;
                }

                final MethodInsnNode methodInsn = (MethodInsnNode) instruction;
                if (OWNER.equals(methodInsn.owner)
                        && generatedGetters.contains(methodInsn.name)
                        && "()I".equals(methodInsn.desc)) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean callsIntegerParseInt(final ClassNode classNode) {
        for (MethodNode method : classNode.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                 instruction != null;
                 instruction = instruction.getNext()) {
                if (instruction.getOpcode() != Opcodes.INVOKESTATIC
                        || !(instruction instanceof MethodInsnNode)) {
                    continue;
                }

                final MethodInsnNode methodInsn = (MethodInsnNode) instruction;
                if ("java/lang/Integer".equals(methodInsn.owner)
                        && "parseInt".equals(methodInsn.name)
                        && "(Ljava/lang/String;)I".equals(methodInsn.desc)) {
                    return true;
                }
            }
        }

        return false;
    }
}
