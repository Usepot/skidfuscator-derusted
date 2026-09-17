import org.objectweb.asm.*;

/** Run under the actual supported Java 8 VM as well as a modern VM. */
public final class ProbeTerminalSwitch implements Opcodes {
    private static byte[] generate(int padding, boolean explicitCase) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V1_8, ACC_PUBLIC, "fixture/TerminalSwitch", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "(I)I", null, null);
        method.visitCode();
        Label loop = new Label(), body = new Label();
        method.visitLabel(loop);
        method.visitVarInsn(ILOAD, 0); method.visitJumpInsn(IFNE, body);
        method.visitIntInsn(BIPUSH, 42); method.visitInsn(IRETURN);
        method.visitLabel(body);
        for (int i = 0; i < padding; i++) method.visitInsn(NOP);
        method.visitIincInsn(0, -1); method.visitInsn(ICONST_0);
        method.visitLookupSwitchInsn(loop, explicitCase ? new int[]{0} : new int[0],
                explicitCase ? new Label[]{loop} : new Label[0]);
        method.visitMaxs(0, 0); method.visitEnd(); writer.visitEnd();
        return writer.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        int emptyRejected = 0, repairedPassed = 0;
        for (int padding = 0; padding < 4; padding++) for (boolean explicit : new boolean[]{false, true}) {
            final byte[] bytes = generate(padding, explicit);
            try {
                Class<?> type = new ClassLoader(ProbeTerminalSwitch.class.getClassLoader()) {
                    Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
                }.define();
                Object value = type.getMethod("run", int.class).invoke(null, 3);
                if (!Integer.valueOf(42).equals(value)) throw new AssertionError("Wrong result " + value);
                if (explicit) repairedPassed++;
                System.out.println("PASS explicit=" + explicit + " padding=" + padding);
            } catch (VerifyError failure) {
                if (explicit) throw failure;
                emptyRejected++;
                System.out.println("BASELINE_REJECTED padding=" + padding + " " + failure.toString().split("\\n")[0]);
            }
        }
        System.out.println("TERMINAL_SWITCH_PROBE java=" + System.getProperty("java.version")
                + " emptyRejected=" + emptyRejected + " repairedPassed=" + repairedPassed + "/4");
        if (repairedPassed != 4) throw new AssertionError("Incomplete repair coverage");
    }
}
