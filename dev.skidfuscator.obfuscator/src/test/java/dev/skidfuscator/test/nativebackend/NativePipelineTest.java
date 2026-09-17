package dev.skidfuscator.test.nativebackend;

import com.typesafe.config.ConfigFactory;
import dev.skidfuscator.config.DefaultSkidConfig;
import dev.skidfuscator.config.nativeobfuscation.NativeMode;
import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.SkidfuscatorSession;
import dev.skidfuscator.obfuscator.creator.SkidApplicationClassSource;
import dev.skidfuscator.obfuscator.hierarchy.Hierarchy;
import dev.skidfuscator.obfuscator.nativebackend.NativeBackendUnavailableException;
import dev.skidfuscator.obfuscator.nativebackend.NativeCompilationPlan;
import dev.skidfuscator.obfuscator.nativebackend.NativeEligibility;
import dev.skidfuscator.obfuscator.nativebackend.NativePipeline;
import dev.skidfuscator.obfuscator.nativebackend.NativeSelectionException;
import dev.skidfuscator.obfuscator.nativebackend.selection.NativeSelection;
import dev.skidfuscator.obfuscator.nativebackend.selection.NativeSelectionSource;
import dev.skidfuscator.obfuscator.skidasm.SkidClassNode;
import dev.skidfuscator.obfuscator.skidasm.SkidGroup;
import dev.skidfuscator.obfuscator.skidasm.SkidMethodNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnNode;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NativePipelineTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void disabledNativeModeIsANoOp() {
        Skidfuscator skidfuscator = mock(Skidfuscator.class);
        when(skidfuscator.getConfig()).thenReturn(config("native.enabled = false"));

        NativeCompilationPlan plan = new NativePipeline(skidfuscator).prepare();
        assertTrue(plan.candidates().isEmpty());
        assertTrue(plan.skipped().isEmpty());
    }

    @Test
    void rejectsDexBeforeTouchingMethods() {
        Skidfuscator skidfuscator = mock(Skidfuscator.class);
        SkidfuscatorSession session = mock(SkidfuscatorSession.class);
        when(skidfuscator.getConfig()).thenReturn(config("native.enabled = true"));
        when(skidfuscator.getSession()).thenReturn(session);
        when(session.isDex()).thenReturn(true);

        assertThrows(NativeSelectionException.class, () -> new NativePipeline(skidfuscator).prepare());
    }

    @Test
    void neverSilentlyEmitsJavaForAnEligibleSelectedMethod() {
        Skidfuscator skidfuscator = mock(Skidfuscator.class);
        SkidfuscatorSession session = mock(SkidfuscatorSession.class);
        Hierarchy hierarchy = mock(Hierarchy.class);
        SkidApplicationClassSource source = mock(SkidApplicationClassSource.class);
        SkidMethodNode method = method();
        when(skidfuscator.getConfig()).thenReturn(config("""
                native {
                  enabled = true
                  "include" = ["method{run}"]
                }
                """));
        when(skidfuscator.getSession()).thenReturn(session);
        when(skidfuscator.getHierarchy()).thenReturn(hierarchy);
        when(skidfuscator.getClassSource()).thenReturn(source);
        when(hierarchy.getMethods()).thenReturn(List.of(method));
        when(source.isApplicationClass("example/Owner")).thenReturn(true);

        assertThrows(
                NativeBackendUnavailableException.class,
                () -> new NativePipeline(skidfuscator).prepare()
        );
        assertTrue((method.node.access & Opcodes.ACC_NATIVE) == 0);
        assertTrue(method.node.instructions.size() > 0);
    }

    @Test
    void explicitBackendFailsWhenEveryMatcherSelectedMethodIsUnsupported() throws IOException {
        Skidfuscator skidfuscator = mock(Skidfuscator.class);
        SkidfuscatorSession session = mock(SkidfuscatorSession.class);
        Hierarchy hierarchy = mock(Hierarchy.class);
        SkidApplicationClassSource source = mock(SkidApplicationClassSource.class);
        SkidMethodNode method = method();
        method.node.access = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC;
        Path compiler = Files.createFile(temporaryDirectory.resolve("zig.exe"));
        when(skidfuscator.getConfig()).thenReturn(config("""
                native {
                  enabled = true
                  "include" = ["method{run}"]
                  targets = ["windows-x86_64"]
                  toolchain.delivery = EXTERNAL
                }
                """));
        when(skidfuscator.getSession()).thenReturn(session);
        when(session.getNativeToolchainPath()).thenReturn(compiler.toFile());
        when(skidfuscator.getHierarchy()).thenReturn(hierarchy);
        when(skidfuscator.getClassSource()).thenReturn(source);
        when(hierarchy.getMethods()).thenReturn(List.of(method));
        when(source.isApplicationClass("example/Owner")).thenReturn(true);

        NativeBackendUnavailableException failure = assertThrows(
                NativeBackendUnavailableException.class,
                () -> new NativePipeline(skidfuscator).prepare()
        );
        assertTrue(failure.getMessage().contains("None of the 1 selected method(s)"));
        assertTrue(failure.getMessage().contains("descriptor must be ()Ljava/lang/String;"));
        assertTrue((method.node.access & Opcodes.ACC_NATIVE) == 0);
        assertTrue(method.node.instructions.size() > 0);
    }

    @Test
    void reservesBeforeTransformsAndRevalidatesTheSameMethodAfterward() {
        Skidfuscator skidfuscator = mock(Skidfuscator.class);
        SkidfuscatorSession session = mock(SkidfuscatorSession.class);
        Hierarchy hierarchy = mock(Hierarchy.class);
        SkidApplicationClassSource source = mock(SkidApplicationClassSource.class);
        SkidMethodNode method = method();
        when(skidfuscator.getConfig()).thenReturn(config("""
                native {
                  enabled = true
                  "include" = ["method{run}"]
                }
                """));
        when(skidfuscator.getSession()).thenReturn(session);
        when(skidfuscator.getHierarchy()).thenReturn(hierarchy);
        when(skidfuscator.getClassSource()).thenReturn(source);
        when(hierarchy.getMethods()).thenReturn(List.of(method));
        when(source.isApplicationClass("example/Owner")).thenReturn(true);

        NativePipeline pipeline = new NativePipeline(skidfuscator);
        NativeCompilationPlan reservation = pipeline.reserve();
        assertEquals(1, reservation.candidates().size());
        assertTrue(reservation.candidates().get(0).selection().getMethod() == method);

        // Simulates a structural transformer making the reserved method invalid.
        method.node.access |= Opcodes.ACC_NATIVE;
        NativeCompilationPlan finalized = pipeline.prepare(reservation);
        assertTrue(finalized.candidates().isEmpty());
        assertEquals(1, finalized.skipped().size());
        assertTrue(finalized.skipped().get(0).reason().contains("already native"));
    }

    @Test
    void reservedNativeIdentityProtectsItsMethodAndEntireTransformGroup() throws Exception {
        Skidfuscator skidfuscator = new Skidfuscator(mock(SkidfuscatorSession.class));
        SkidMethodNode reserved = method("reserved");
        SkidMethodNode ordinary = method("ordinary");
        NativeSelection selection = new NativeSelection(
                reserved,
                NativeMode.AOT,
                NativeSelectionSource.INCLUDE,
                false
        );
        NativeCompilationPlan plan = new NativeCompilationPlan(
                List.of(new NativeCompilationPlan.Candidate(
                        selection,
                        NativeEligibility.Conversion.DIRECT
                )),
                List.of()
        );
        Field planField = Skidfuscator.class.getDeclaredField("nativeCompilationPlan");
        planField.setAccessible(true);
        planField.set(skidfuscator, plan);

        SkidGroup reservedGroup = mock(SkidGroup.class);
        when(reservedGroup.getMethodNodeList()).thenReturn(List.of(ordinary, reserved));
        SkidGroup ordinaryGroup = mock(SkidGroup.class);
        when(ordinaryGroup.getMethodNodeList()).thenReturn(List.of(ordinary));

        assertTrue(skidfuscator.isNativeCandidate(reserved));
        assertTrue(skidfuscator.isNativeCandidate(reservedGroup));
        assertFalse(skidfuscator.isNativeCandidate(ordinary));
        assertFalse(skidfuscator.isNativeCandidate(ordinaryGroup));
    }

    private static DefaultSkidConfig config(final String hocon) {
        return new DefaultSkidConfig(ConfigFactory.parseString(hocon), "");
    }

    private static SkidMethodNode method() {
        return method("run");
    }

    private static SkidMethodNode method(final String name) {
        org.objectweb.asm.tree.ClassNode rawOwner = new org.objectweb.asm.tree.ClassNode();
        rawOwner.name = "example/Owner";
        SkidClassNode owner = new SkidClassNode(rawOwner, null);
        org.objectweb.asm.tree.MethodNode raw = new org.objectweb.asm.tree.MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                name,
                "()V",
                null,
                null
        );
        raw.instructions.add(new InsnNode(Opcodes.RETURN));
        SkidMethodNode method = new SkidMethodNode(raw, owner, null);
        owner.addMethod(method);
        return method;
    }
}
