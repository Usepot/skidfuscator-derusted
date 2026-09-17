package dev.skidfuscator.test.nativebackend;

import com.typesafe.config.ConfigFactory;
import dev.skidfuscator.config.nativeobfuscation.NativeConfig;
import dev.skidfuscator.obfuscator.nativebackend.NativeCompilationPlan;
import dev.skidfuscator.obfuscator.nativebackend.NativeCompilationPlanner;
import dev.skidfuscator.obfuscator.nativebackend.NativeEligibility;
import dev.skidfuscator.obfuscator.nativebackend.NativeSelectionException;
import org.junit.jupiter.api.Test;
import org.mapleir.asm.ClassNode;
import org.mapleir.asm.MethodNode;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.InsnNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeCompilationPlannerTest {
    private static final String ANNOTATION = "Ldev/skidfuscator/annotations/NativeObfuscation;";

    @Test
    void warnsAndKeepsMatcherSelectedUnsupportedMethodsAsJava() {
        NativeCompilationPlanner planner = planner("""
                native {
                  enabled = true
                  "include" = ["method{missingBody}"]
                }
                """);

        NativeCompilationPlan plan = planner.plan(List.of(method("missingBody", false)));
        assertEquals(0, plan.candidates().size());
        assertEquals(1, plan.skipped().size());
    }

    @Test
    void failsUnsupportedExplicitAnnotations() {
        MethodNode method = method("missingBody", false);
        method.node.invisibleAnnotations = new ArrayList<>();
        method.node.invisibleAnnotations.add(new AnnotationNode(ANNOTATION));

        NativeCompilationPlanner planner = planner("native.enabled = true");
        assertThrows(NativeSelectionException.class, () -> planner.plan(List.of(method)));
    }

    @Test
    void recordsWrapperConversionBeforeLowering() {
        NativeCompilationPlanner planner = planner("""
                native {
                  enabled = true
                  "include" = ["method{<init>}"]
                }
                """);

        NativeCompilationPlan plan = planner.plan(List.of(method("<init>", true)));
        assertEquals(1, plan.candidates().size());
        assertEquals(NativeEligibility.Conversion.WRAPPER_REQUIRED, plan.candidates().get(0).conversion());
    }

    @Test
    void failsWhenAnExplicitReservationBecomesUnsupported() {
        MethodNode method = method("protected", true);
        method.node.invisibleAnnotations = new ArrayList<>();
        method.node.invisibleAnnotations.add(new AnnotationNode(ANNOTATION));
        NativeCompilationPlanner planner = planner("native.enabled = true");
        NativeCompilationPlan reservation = planner.plan(List.of(method));

        method.node.access |= Opcodes.ACC_NATIVE;
        assertThrows(
                NativeSelectionException.class,
                () -> planner.revalidate(reservation, List.of(method))
        );
    }

    private static NativeCompilationPlanner planner(final String hocon) {
        return new NativeCompilationPlanner(new NativeConfig(ConfigFactory.parseString(hocon), "native"));
    }

    private static MethodNode method(final String name, final boolean withBody) {
        ClassNode owner = new ClassNode();
        owner.node.name = "example/Owner";
        org.objectweb.asm.tree.MethodNode raw = new org.objectweb.asm.tree.MethodNode(
                Opcodes.ACC_PUBLIC,
                name,
                "()V",
                null,
                null
        );
        if (withBody) {
            raw.instructions.add(new InsnNode(Opcodes.RETURN));
        }
        MethodNode method = new MethodNode(raw, owner);
        owner.addMethod(method);
        return method;
    }
}
