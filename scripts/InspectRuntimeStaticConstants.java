import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Compares static int ConstantValue attributes with initialized Java 8 runtime fields. */
public final class InspectRuntimeStaticConstants {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("candidate.jar libraries.txt binary.class.Name");
        Map<String,Integer> expected = new TreeMap<>();
        try (JarFile jar = new JarFile(args[0])) {
            JarEntry e = jar.getJarEntry(args[2].replace('.', '/') + ".class");
            ClassNode n = new ClassNode();
            try (InputStream in = jar.getInputStream(e)) { new ClassReader(in).accept(n, ClassReader.SKIP_CODE); }
            for (FieldNode f : n.fields) if ((f.access & Opcodes.ACC_STATIC) != 0 && "I".equals(f.desc) && f.value instanceof Integer)
                expected.put(f.name, (Integer) f.value);
        }
        List<URL> urls = new ArrayList<>(); urls.add(new File(args[0]).toURI().toURL());
        for (String row : Files.readAllLines(Paths.get(args[1]))) if (!row.trim().isEmpty()) urls.add(new File(row.trim()).toURI().toURL());
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), null)) {
            Class<?> c = Class.forName(args[2], true, loader);
            int mismatch = 0;
            for (Map.Entry<String,Integer> entry : expected.entrySet()) {
                Field f = c.getDeclaredField(entry.getKey()); f.setAccessible(true);
                int actual = f.getInt(null);
                System.out.println(entry.getKey() + " expected=" + entry.getValue() + " actual=" + actual);
                if (actual != entry.getValue()) mismatch++;
            }
            System.out.println("STATIC_CONSTANT_AUDIT fields=" + expected.size() + " mismatches=" + mismatch);
            if (mismatch != 0) System.exit(1);
        }
    }
}
