package dev.skidfuscator.nativetoolchain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeTargetTest {
    @Test
    void normalizesIdsAndLibraryNames() {
        assertEquals(NativeTarget.WINDOWS_AARCH64, NativeTarget.parse("windows_aarch64"));
        assertEquals("skid.dll", NativeTarget.WINDOWS_X86_64.libraryFileName("skid"));
        assertEquals("libskid.so", NativeTarget.LINUX_AARCH64.libraryFileName("skid"));
        assertEquals("libskid.dylib", NativeTarget.MACOS_X86_64.libraryFileName("skid"));
    }

    @Test
    void rejectsUnknownTargets() {
        assertThrows(IllegalArgumentException.class, () -> NativeTarget.parse("solaris-sparc"));
    }
}
