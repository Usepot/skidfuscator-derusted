package dev.skidfuscator.obfuscator.util;

import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.phantom.jphantom.PhantomResolvingJarDumper;
import org.mapleir.asm.ClassNode;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.CodeSizeEvaluator;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Opt-in, non-serializing size diagnostics for intermediate transformation states. */
public final class ClassSizeDiagnostics {
    private ClassSizeDiagnostics() { }

    /**
     * Attribute-name entries added during final serialization may increase the pool
     * slightly. Code sizes are conservative upper bounds, not archive byte sizes.
     */
    public record SizeEstimate(int constantPoolEstimate, long codeBytesUpperBound,
                               int largestMethodBytesUpperBound, String largestMethod) { }

    public static SizeEstimate measure(org.objectweb.asm.tree.ClassNode node) {
        long total = 0;
        int largest = 0;
        String largestMethod = "";
        for (MethodNode method : node.methods) {
            CodeSizeEvaluator evaluator = new CodeSizeEvaluator(null);
            method.accept(evaluator);
            int size = evaluator.getMaxSize();
            total += size;
            if (size > largest) {
                largest = size;
                largestMethod = method.name + method.desc;
            }
        }
        // Never call ClassWriter.toByteArray here. Late passes invalidate old
        // frames until final COMPUTE_FRAMES. Branch widening during serialization
        // would otherwise try to repair those stale frames just to print a log.
        return new SizeEstimate(ConstantPoolBudget.count(node), total, largest, largestMethod);
    }

    public static void report(Skidfuscator skidfuscator, String phase) {
        String prefix = skidfuscator.getConfig().getString("diagnostics.classPrefix", "");
        if (prefix.isEmpty()) return;
        for (ClassNode node : skidfuscator.getClassSource().iterate()) {
            if (!node.getName().startsWith(prefix)) continue;
            SizeEstimate size = measure(node.node);
            Skidfuscator.LOGGER.warn("CLASS_SIZE " + phase + " " + node.getName()
                    + " constantsEstimate=" + size.constantPoolEstimate()
                    + " codeBytesUpperBound=" + size.codeBytesUpperBound()
                    + " methods=" + node.node.methods.size()
                    + " largestMethod=" + size.largestMethod()
                    + " largestMethodBytesUpperBound=" + size.largestMethodBytesUpperBound()
                    + (size.constantPoolEstimate() > 65535 ? " CONSTANT_POOL_OVERFLOW" : "")
                    + (size.largestMethodBytesUpperBound() > 65535 ? " METHOD_SIZE_RISK" : ""));
            snapshot(skidfuscator, node, phase);
        }
    }

    /**
     * Separately requested debugging snapshots use final-style frame computation.
     * They are never substituted into application output and cannot hide a final
     * serialization failure. Each phase directory can be placed first on a test
     * classpath to locate the earliest phase that fails JVM verification.
     */
    private static void snapshot(Skidfuscator skid, ClassNode node, String phase) {
        String directory = skid.getConfig().getString("diagnostics.snapshotDirectory", "");
        if (directory.isEmpty()) return;
        try {
            ClassWriter writer = new PhantomResolvingJarDumper(skid, skid.getJarContents(), skid.getClassSource())
                    .buildClassWriter(skid.getClassSource().getClassTree(), ClassWriter.COMPUTE_FRAMES);
            node.node.accept(new ClassRemapper(writer, skid.getClassRemapper()));
            byte[] bytes = writer.toByteArray();
            String name = new ClassReader(bytes).getClassName();
            Path root = Path.of(directory).toAbsolutePath().normalize()
                    .resolve(phase.replaceAll("[^A-Za-z0-9_.-]", "_"));
            Path destination = root.resolve(name + ".class").normalize();
            if (!destination.startsWith(root)) throw new IOException("Invalid snapshot class name: " + name);
            Files.createDirectories(destination.getParent());
            Files.write(destination, bytes);
            Skidfuscator.LOGGER.warn("CLASS_SNAPSHOT " + phase + " " + destination);
        } catch (RuntimeException | IOException failure) {
            Skidfuscator.LOGGER.warn("CLASS_SNAPSHOT_FAILED " + phase + " " + node.getName() + ": " + failure);
        }
    }
}
