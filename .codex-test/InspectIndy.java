import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class InspectIndy {
    private static final String STATIC_BOOTSTRAP_DESC = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;ILjava/lang/String;Ljava/lang/invoke/MethodType;I)Ljava/lang/invoke/CallSite;";
    private static final String SEED_BOOTSTRAP_DESC = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;ILjava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;";
    private static final String RELINK_DESC = "(Ljava/lang/invoke/MutableCallSite;Ljava/lang/invoke/MethodHandles$Lookup;ILjava/lang/String;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String DECRYPT_DESC = "(Ljava/lang/String;I)Ljava/lang/String;";

    private static int classes;
    private static int totalIndy;
    private static int seedIndy;
    private static int staticIndy;
    private static int otherIndy;
    private static int skidNameIndy;
    private static int seedBadArgCount;
    private static int seedBadArgTypes;
    private static int seedBadName;
    private static int seedBadTrailingInt;
    private static int staticBadArgCount;
    private static int seedBootstrapMethods;
    private static int staticBootstrapMethods;
    private static int relinkMethods;
    private static int relinkReadsTailInteger;
    private static int decryptMethods;
    private static final List<String> samples = new ArrayList<>();
    private static final Map<String, Integer> byBootstrapOwner = new TreeMap<>();

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: InspectIndy <jar>");
        }

        try (JarFile jar = new JarFile(args[0])) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream input = jar.getInputStream(entry)) {
                    new ClassReader(input).accept(new InspectClassVisitor(), ClassReader.SKIP_FRAMES);
                }
            }
        }

        System.out.println("classes=" + classes);
        System.out.println("totalIndy=" + totalIndy);
        System.out.println("seedIndy=" + seedIndy);
        System.out.println("staticIndy=" + staticIndy);
        System.out.println("otherIndy=" + otherIndy);
        System.out.println("skidNameIndy=" + skidNameIndy);
        System.out.println("seedBadArgCount=" + seedBadArgCount);
        System.out.println("seedBadArgTypes=" + seedBadArgTypes);
        System.out.println("seedBadName=" + seedBadName);
        System.out.println("seedBadTrailingInt=" + seedBadTrailingInt);
        System.out.println("staticBadArgCount=" + staticBadArgCount);
        System.out.println("seedBootstrapMethods=" + seedBootstrapMethods);
        System.out.println("staticBootstrapMethods=" + staticBootstrapMethods);
        System.out.println("relinkMethods=" + relinkMethods);
        System.out.println("relinkReadsTailInteger=" + relinkReadsTailInteger);
        System.out.println("decryptMethods=" + decryptMethods);
        System.out.println("bootstrapOwners=" + byBootstrapOwner);
        System.out.println("samples:");
        for (String sample : samples) {
            System.out.println(sample);
        }
    }

    private static final class InspectClassVisitor extends ClassVisitor {
        private String className;

        private InspectClassVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            classes++;
            className = name;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            if (name.startsWith("skid$bootseed$") && SEED_BOOTSTRAP_DESC.equals(descriptor)) {
                seedBootstrapMethods++;
            }
            if (name.startsWith("skid$bootstrap$") && STATIC_BOOTSTRAP_DESC.equals(descriptor)) {
                staticBootstrapMethods++;
            }
            if (name.startsWith("skid$decrypt$") && DECRYPT_DESC.equals(descriptor)) {
                decryptMethods++;
            }

            return new InspectMethodVisitor(className, name, descriptor);
        }
    }

    private static final class InspectMethodVisitor extends MethodVisitor {
        private final String className;
        private final String methodName;
        private final String methodDesc;
        private boolean relink;
        private boolean sawArrayLength;
        private boolean sawAaload;
        private boolean sawIntegerCheckcast;
        private boolean sawIntValue;

        private InspectMethodVisitor(String className, String methodName, String methodDesc) {
            super(Opcodes.ASM9);
            this.className = className;
            this.methodName = methodName;
            this.methodDesc = methodDesc;
            this.relink = methodName.startsWith("skid$relink$") && RELINK_DESC.equals(methodDesc);
            if (relink) {
                relinkMethods++;
            }
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
            totalIndy++;
            boolean nameIsEncrypted = name.startsWith("skid$");
            if (nameIsEncrypted) {
                skidNameIndy++;
            }

            String bsmName = bootstrapMethodHandle.getName();
            String bsmDesc = bootstrapMethodHandle.getDesc();
            String bsmOwner = bootstrapMethodHandle.getOwner();
            byBootstrapOwner.merge(bsmOwner, 1, Integer::sum);

            boolean seed = bsmName.startsWith("skid$bootseed$") && SEED_BOOTSTRAP_DESC.equals(bsmDesc);
            boolean stat = bsmName.startsWith("skid$bootstrap$") && STATIC_BOOTSTRAP_DESC.equals(bsmDesc);

            if (seed) {
                seedIndy++;
                if (!nameIsEncrypted) {
                    seedBadName++;
                }
                if (bootstrapMethodArguments.length != 3) {
                    seedBadArgCount++;
                } else if (!(bootstrapMethodArguments[0] instanceof Integer)
                        || !(bootstrapMethodArguments[1] instanceof String)
                        || !(bootstrapMethodArguments[2] instanceof Type)) {
                    seedBadArgTypes++;
                }

                Type[] args = Type.getArgumentTypes(descriptor);
                if (args.length == 0 || args[args.length - 1].getSort() != Type.INT) {
                    seedBadTrailingInt++;
                }
            } else if (stat) {
                staticIndy++;
                if (bootstrapMethodArguments.length != 4) {
                    staticBadArgCount++;
                }
            } else {
                otherIndy++;
            }

            if (samples.size() < 25 && (seed || stat)) {
                samples.add(String.format(
                        "%s.%s%s indyName=%s indyDesc=%s bsm=%s.%s%s args=%d kind=%s",
                        className,
                        methodName,
                        methodDesc,
                        abbreviate(name),
                        descriptor,
                        bsmOwner,
                        abbreviate(bsmName),
                        bsmDesc,
                        bootstrapMethodArguments.length,
                        seed ? "seed" : "static"
                ));
            }
        }

        @Override
        public void visitInsn(int opcode) {
            if (relink) {
                if (opcode == Opcodes.ARRAYLENGTH) {
                    sawArrayLength = true;
                } else if (opcode == Opcodes.AALOAD) {
                    sawAaload = true;
                }
            }
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (relink && opcode == Opcodes.CHECKCAST && "java/lang/Integer".equals(type)) {
                sawIntegerCheckcast = true;
            }
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (relink && opcode == Opcodes.INVOKEVIRTUAL
                    && "java/lang/Integer".equals(owner)
                    && "intValue".equals(name)
                    && "()I".equals(descriptor)) {
                sawIntValue = true;
            }
        }

        @Override
        public void visitEnd() {
            if (relink && sawArrayLength && sawAaload && sawIntegerCheckcast && sawIntValue) {
                relinkReadsTailInteger++;
            }
        }
    }

    private static String abbreviate(String value) {
        if (value == null || value.length() <= 52) {
            return value;
        }
        return value.substring(0, 49) + "...";
    }
}
