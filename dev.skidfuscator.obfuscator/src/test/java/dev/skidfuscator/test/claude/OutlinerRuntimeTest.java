package dev.skidfuscator.test.claude;

import dev.skidfuscator.core.SkidTest;
import dev.skidfuscator.testclasses.TestRun;
import dev.skidfuscator.testclasses.claude.OutlinerRuntimeClazz;
import org.junit.jupiter.api.Assertions;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Map;

public class OutlinerRuntimeTest extends SkidTest {
    @Override
    public Class<? extends TestRun> getMainClass() {
        return OutlinerRuntimeClazz.class;
    }

    @Override
    public Class<?>[] getClasses() {
        return new Class[]{
                OutlinerRuntimeClazz.class
        };
    }

    @Override
    public String getConfigPath() {
        return "/config/claude/outliner.hocon";
    }

    @Override
    public void receiveAndExecute(final List<Map.Entry<String, byte[]>> output) {
        super.receiveAndExecute(output);
        Assertions.assertTrue(hasSyntheticOutlineHelper(output, OutlinerRuntimeClazz.class),
                "expected the outliner to emit at least one synthetic helper method");
    }

    static boolean hasSyntheticOutlineHelper(final List<Map.Entry<String, byte[]>> output,
                                             final Class<?> clazz) {
        final String internalName = clazz.getName().replace('.', '/');
        for (Map.Entry<String, byte[]> entry : output) {
            if (!internalName.equals(entry.getKey())) {
                continue;
            }
            final ClassNode classNode = new ClassNode();
            new ClassReader(entry.getValue()).accept(classNode, 0);
            for (MethodNode method : classNode.methods) {
                if (method.name.startsWith("skid$outline$")
                        && (method.access & Opcodes.ACC_SYNTHETIC) != 0
                        && (method.access & Opcodes.ACC_STATIC) != 0
                        && "([Ljava/lang/Object;)[Ljava/lang/Object;".equals(method.desc)) {
                    return true;
                }
            }
        }
        return false;
    }
}
