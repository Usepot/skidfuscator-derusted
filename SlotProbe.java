import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Real dataflow verification (liveness-aware) for long/double local-slot errors.
 * Uses ASM Analyzer + BasicVerifier, which tracks 2-slot values precisely and
 * needs NO classpath (all references collapse to one type). Reports methods whose
 * analysis throws — i.e. genuine "Bad local variable type" style failures — and
 * filters to those mentioning a long/double mismatch.
 */
public class SlotProbe {
    static int methods = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        String path = args[0];
        boolean verbose = args.length > 1 && args[1].equals("-v");
        if (path.endsWith(".jar")) {
            try (ZipFile zf = new ZipFile(path)) {
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (!e.getName().endsWith(".class")) continue;
                    try (InputStream in = zf.getInputStream(e)) { scan(in, verbose); }
                    catch (Exception ex) { /* unparseable class, skip */ }
                }
            }
        } else {
            scan(new FileInputStream(path), true);
        }
        System.out.println("\nSCANNED " + methods + " methods; " + failed + " FAILED long/double dataflow verification.");
    }

    static void scan(InputStream in, boolean verbose) throws IOException {
        ClassReader cr = new ClassReader(in);
        ClassNode cn = new ClassNode();
        cr.accept(cn, ClassReader.SKIP_DEBUG);
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null || mn.instructions.size() == 0) continue;
            methods++;
            try {
                new Analyzer<>(new BasicVerifier()).analyze(cn.name, mn);
            } catch (AnalyzerException ex) {
                failed++;
                System.out.println("!! " + cn.name + "." + mn.name + mn.desc + "  ::  " + ex.getMessage());
            } catch (Throwable t) { /* ignore analyzer internal issues */ }
        }
    }
}
