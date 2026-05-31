package dev.skidfuscator.test.annotation;

import dev.skidfuscator.core.TestSkidfuscator;
import dev.skidfuscator.core.classloader.SkidClassLoader;
import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.SkidfuscatorSession;
import dev.skidfuscator.obfuscator.transform.Transformer;
import dev.skidfuscator.obfuscator.transform.impl.annotation.IntAnnotationEncryptionTransformer;
import dev.skidfuscator.testclasses.TestRun;
import dev.skidfuscator.testclasses.annotation.IntAnnotationTarget;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IntAnnotationEncryptionTransformerTest {
    private static final String TARGET = "dev/skidfuscator/testclasses/annotation/IntAnnotationTarget";
    private static final String SECRET = "Ldev/skidfuscator/testclasses/annotation/IntAnnotationTarget$Secret;";

    @Test
    public void testTransformerIsWiredIntoDefaultList() {
        final ConfigOnlySkidfuscator skidfuscator = new ConfigOnlySkidfuscator();
        skidfuscator._importConfig();

        assertTrue(skidfuscator.getTransformers()
                .stream()
                .anyMatch(IntAnnotationEncryptionTransformer.class::isInstance));
    }

    @Test
    public void testRemapsStoredIntsAndDecryptsDirectAccessors() throws Exception {
        final AtomicReference<List<Map.Entry<String, byte[]>>> output = new AtomicReference<>();
        final Skidfuscator skidfuscator = new IntAnnotationOnlySkidfuscator(
                new Class<?>[] { IntAnnotationTarget.class, IntAnnotationTarget.Secret.class },
                output::set
        );

        skidfuscator.run();

        final List<Map.Entry<String, byte[]>> classes = output.get();
        assertNotNull(classes);
        assertRemappedAnnotationConstants(classes);
        executeTransformedTarget(classes);
    }

    private static void assertRemappedAnnotationConstants(final List<Map.Entry<String, byte[]>> classes) {
        final org.objectweb.asm.tree.ClassNode target = readClass(classes, TARGET);
        final AnnotationNode classAnnotation = findAnnotation(target.visibleAnnotations, SECRET);
        assertNotNull(classAnnotation);
        assertNotEquals(1337, intValue(classAnnotation, "value"));

        final FieldNode field = target.fields.stream()
                .filter(node -> node.name.equals("field"))
                .findFirst()
                .orElseThrow();
        final AnnotationNode fieldAnnotation = findAnnotation(field.visibleAnnotations, SECRET);
        assertNotNull(fieldAnnotation);
        assertNotEquals(99, intValue(fieldAnnotation, "value"));

        final MethodNode method = target.methods.stream()
                .filter(node -> node.name.equals("annotatedMethod"))
                .findFirst()
                .orElseThrow();
        final AnnotationNode methodAnnotation = findAnnotation(method.visibleAnnotations, SECRET);
        assertNotNull(methodAnnotation);
        assertNotEquals(123, intValue(methodAnnotation, "value"));

        final org.objectweb.asm.tree.ClassNode annotation = readClass(classes, TARGET + "$Secret");
        final MethodNode defaulted = annotation.methods.stream()
                .filter(node -> node.name.equals("defaulted"))
                .findFirst()
                .orElseThrow();
        assertNotEquals(7331, defaulted.annotationDefault);
    }

    private static void executeTransformedTarget(final List<Map.Entry<String, byte[]>> output) throws Exception {
        try (SkidClassLoader classLoader = new SkidClassLoader(new URL[0])) {
            for (Map.Entry<String, byte[]> entry : output) {
                classLoader.defineClass(entry.getKey(), entry.getValue());
            }

            final Class<?> clazz = classLoader.loadClass(IntAnnotationTarget.class.getName());
            final TestRun run = (TestRun) clazz.newInstance();
            run.run();
        }
    }

    private static org.objectweb.asm.tree.ClassNode readClass(final List<Map.Entry<String, byte[]>> classes,
                                                             final String internalName) {
        final byte[] bytes = classes.stream()
                .filter(entry -> entry.getKey().equals(internalName))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow();
        final org.objectweb.asm.tree.ClassNode classNode = new org.objectweb.asm.tree.ClassNode();
        new ClassReader(bytes).accept(classNode, 0);
        return classNode;
    }

    private static AnnotationNode findAnnotation(final List<AnnotationNode> annotations, final String descriptor) {
        if (annotations == null) {
            return null;
        }
        return annotations.stream()
                .filter(annotation -> descriptor.equals(annotation.desc))
                .findFirst()
                .orElse(null);
    }

    private static int intValue(final AnnotationNode annotation, final String name) {
        for (int i = 0; i < annotation.values.size() - 1; i += 2) {
            if (name.equals(annotation.values.get(i))) {
                return (Integer) annotation.values.get(i + 1);
            }
        }
        throw new IllegalStateException("Missing annotation value " + name);
    }

    private static final class IntAnnotationOnlySkidfuscator extends TestSkidfuscator {
        private IntAnnotationOnlySkidfuscator(final Class<?>[] test,
                                             final java.util.function.Consumer<List<Map.Entry<String, byte[]>>> callback) {
            super(test, callback, "/config/runtime_new.hocon");
        }

        @Override
        public List<Transformer> getTransformers() {
            return Collections.singletonList(new IntAnnotationEncryptionTransformer(this));
        }
    }

    private static final class ConfigOnlySkidfuscator extends Skidfuscator {
        private ConfigOnlySkidfuscator() {
            super(SkidfuscatorSession.builder()
                    .config(new File(IntAnnotationEncryptionTransformerTest.class
                            .getResource("/config/runtime_new.hocon")
                            .getFile()))
                    .build());
        }
    }
}
