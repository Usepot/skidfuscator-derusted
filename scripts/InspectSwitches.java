import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Inspects JVM-required switch key ordering without loading client dependencies. */
public final class InspectSwitches {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: InspectSwitches candidate.jar");
        int methods = 0, switches = 0, invalid = 0;
        try (JarFile jar = new JarFile(args[0])) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                ClassNode owner = new ClassNode();
                try (java.io.InputStream in = jar.getInputStream(entry)) { new ClassReader(in).accept(owner, ClassReader.SKIP_FRAMES); }
                for (MethodNode method : owner.methods) {
                    methods++;
                    for (AbstractInsnNode i : method.instructions) {
                        if (i instanceof LookupSwitchInsnNode) {
                            switches++;
                            LookupSwitchInsnNode s = (LookupSwitchInsnNode)i;
                            for (int p = 1; p < s.keys.size(); p++) if (s.keys.get(p) <= s.keys.get(p - 1)) {
                                invalid++;
                                System.out.println("UNSORTED_SWITCH " + owner.name + "." + method.name + method.desc + " keys=" + s.keys);
                                break;
                            }
                        } else if (i instanceof TableSwitchInsnNode) {
                            switches++;
                            TableSwitchInsnNode s = (TableSwitchInsnNode)i;
                            if (s.max < s.min || (long)s.max - s.min + 1 != s.labels.size()) {
                                invalid++; System.out.println("INVALID_TABLE " + owner.name + "." + method.name + method.desc);
                            }
                        }
                    }
                }
            }
        }
        System.out.println("SWITCH_AUDIT methods=" + methods + " switches=" + switches + " invalid=" + invalid);
        if (invalid != 0) throw new IllegalStateException("Invalid JVM switch encoding");
    }
}
