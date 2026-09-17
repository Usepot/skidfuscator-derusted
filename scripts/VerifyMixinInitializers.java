import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Structural initializer gate; actual Mixin application and game execution remain required. */
public final class VerifyMixinInitializers {
    private static boolean mixin(ClassNode c) {
        for (List<AnnotationNode> annotations : Arrays.asList(c.visibleAnnotations, c.invisibleAnnotations))
            if (annotations != null) for (AnnotationNode a : annotations)
                if (a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) return true;
        return false;
    }
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("Usage: VerifyMixinInitializers jar [--audit]");
        boolean audit = args.length == 2 && args[1].equals("--audit");
        int classes = 0, constructors = 0, missing = 0, finalStores = 0;
        Set<String> shapes = new TreeSet<>();
        try (JarFile jar = new JarFile(args[0])) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (!e.getName().endsWith(".class")) continue;
                ClassNode c = new ClassNode();
                try (java.io.InputStream in = jar.getInputStream(e)) { new ClassReader(in).accept(c, ClassReader.SKIP_FRAMES); }
                if (!mixin(c)) continue;
                classes++;
                Map<String, FieldNode> fields = new HashMap<>();
                for (FieldNode f : c.fields) fields.put(f.name + f.desc, f);
                for (MethodNode m : c.methods) {
                    if (!m.name.equals("<init>")) continue;
                    constructors++;
                    int lines = 0, stores = 0;
                    List<String> chains = new ArrayList<>();
                    for (AbstractInsnNode i : m.instructions) {
                        if (i instanceof LineNumberNode) lines++;
                        if (i instanceof MethodInsnNode call && call.name.equals("<init>") &&
                                (call.owner.equals(c.superName) || call.owner.equals(c.name))) chains.add(call.owner + call.desc);
                        if (i instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && f.owner.equals(c.name)) {
                            FieldNode field = fields.get(f.name + f.desc);
                            if (field != null && (field.access & Opcodes.ACC_FINAL) != 0) { stores++; finalStores++; }
                        }
                    }
                    shapes.add(m.desc + " -> " + chains);
                    if (lines < 2) missing++;
                    if (audit && (stores > 0 || !m.desc.equals("()V") || !c.superName.equals("java/lang/Object")))
                        System.out.println("REVIEW " + c.name + m.desc + " finalStores=" + stores + " super=" + c.superName);
                    if (!audit && lines < 2) System.out.println("MISSING_INITIALIZER_LINE_CONTRACT " + c.name + m.desc);
                }
            }
        }
        System.out.println("Mixin initializer audit: classes=" + classes + " constructors=" + constructors
                + " missingLineContracts=" + missing + " finalStores=" + finalStores + " shapes=" + shapes);
        if (!audit && missing != 0) throw new IllegalStateException("Mixin initializer line contracts are missing; do not release");
    }
}
