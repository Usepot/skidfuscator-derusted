import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.InputStream;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class FindCtorMismatch {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: FindCtorMismatch <jar>");
        Map<String, ClassNode> classes = new TreeMap<>();
        try (JarFile jar = new JarFile(args[0])) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (!e.getName().contains(".class")) continue;
                try (InputStream in = jar.getInputStream(e)) {
                    ClassNode cn = new ClassNode();
                    new ClassReader(in).accept(cn, 0);
                    classes.put(cn.name, cn);
                } catch (Throwable ignored) {
                }
            }
        }

        Map<String, Set<String>> ctors = new TreeMap<>();
        for (ClassNode cn : classes.values()) {
            for (MethodNode mn : cn.methods) {
                if ("<init>".equals(mn.name)) {
                    ctors.computeIfAbsent(cn.name, k -> new TreeSet<>()).add(mn.desc);
                }
            }
        }

        int missing = 0;
        for (ClassNode cn : classes.values()) {
            for (MethodNode mn : cn.methods) {
                for (AbstractInsnNode insn = mn.instructions == null ? null : mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof MethodInsnNode)) continue;
                    MethodInsnNode mi = (MethodInsnNode) insn;
                    if (mi.getOpcode() != Opcodes.INVOKESPECIAL || !"<init>".equals(mi.name)) continue;
                    Set<String> descs = ctors.get(mi.owner);
                    if (descs != null && !descs.contains(mi.desc)) {
                        System.out.println("MISSING_CTOR_CALL caller=" + cn.name + "." + mn.name + mn.desc
                                + " target=" + mi.owner + ".<init>" + mi.desc
                                + " available=" + descs);
                        missing++;
                    }
                }
            }
        }

        System.out.println("classes=" + classes.size());
        System.out.println("missingCtorCalls=" + missing);
        for (String key : new String[]{
                "me/ambassator/clicker/Clicker$1",
                "me/ambassator/clicker/ClickerFrame$5",
                "me/ambassator/clicker/ClickerFrame$4"
        }) {
            System.out.println("ctors[" + key + "]=" + ctors.get(key));
        }
    }
}
