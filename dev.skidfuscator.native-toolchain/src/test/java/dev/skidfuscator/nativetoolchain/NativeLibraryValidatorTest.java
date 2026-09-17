package dev.skidfuscator.nativetoolchain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeLibraryValidatorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsMatchingElfAndRejectsWrongArchitectureOrMissingJniEntry() throws Exception {
        final Path valid = temporaryDirectory.resolve("libvalid.so");
        final Path wrongArchitecture = temporaryDirectory.resolve("libarm.so");
        final Path missingEntry = temporaryDirectory.resolve("libmissing.so");
        Files.write(valid, elf(62, true));
        Files.write(wrongArchitecture, elf(183, true));
        Files.write(missingEntry, elf(62, false));
        final NativeLibraryValidator validator = new NativeLibraryValidator();

        assertDoesNotThrow(() -> validator.validate(valid, NativeTarget.LINUX_X86_64));
        assertThrows(NativeCompilationException.class,
                () -> validator.validate(wrongArchitecture, NativeTarget.LINUX_X86_64));
        assertThrows(NativeCompilationException.class,
                () -> validator.validate(missingEntry, NativeTarget.LINUX_X86_64));
    }

    @Test
    void acceptsAllSixContainerAndArchitectureCombinations() throws Exception {
        final NativeLibraryValidator validator = new NativeLibraryValidator();
        for (final NativeTarget target : NativeTarget.values()) {
            final byte[] library = switch (target) {
                case WINDOWS_X86_64 -> pe(0x8664, "JNI_OnLoad");
                case WINDOWS_AARCH64 -> pe(0xaa64, "JNI_OnLoad");
                case LINUX_X86_64 -> elf(62, true);
                case LINUX_AARCH64 -> elf(183, true);
                case MACOS_X86_64 -> machO(0x01000007);
                case MACOS_AARCH64 -> machO(0x0100000c);
            };
            final Path file = temporaryDirectory.resolve(target.id() + target.libraryExtension());
            Files.write(file, library);
            assertDoesNotThrow(() -> validator.validate(file, target));
        }
    }

    @Test
    void rejectsPeWhenJniNameIsOnlyDataOrAnotherExportIsPresent() throws Exception {
        final Path dataOnly = temporaryDirectory.resolve("data-only.dll");
        final Path extraExport = temporaryDirectory.resolve("extra-export.dll");
        Files.write(dataOnly, peWithoutExportDirectory(0x8664));
        Files.write(extraExport, pe(0x8664, "JNI_OnLoad", "accidental_export"));
        final NativeLibraryValidator validator = new NativeLibraryValidator();

        assertThrows(NativeCompilationException.class,
                () -> validator.validate(dataOnly, NativeTarget.WINDOWS_X86_64));
        assertThrows(NativeCompilationException.class,
                () -> validator.validate(extraExport, NativeTarget.WINDOWS_X86_64));
    }

    @Test
    void rejectsElfAndMachOWhenJniNameIsOnlyDataOrAnotherExportIsPresent() throws Exception {
        final NativeLibraryValidator validator = new NativeLibraryValidator();
        final Path elfData = temporaryDirectory.resolve("elf-data.so");
        final Path elfExtra = temporaryDirectory.resolve("elf-extra.so");
        final Path machData = temporaryDirectory.resolve("mach-data.dylib");
        final Path machExtra = temporaryDirectory.resolve("mach-extra.dylib");
        Files.write(elfData, elf(62, false));
        Files.write(elfExtra, elfExports(62, "JNI_OnLoad", "accidental_export"));
        Files.write(machData, machOExports(0x01000007));
        Files.write(machExtra, machOExports(0x01000007, "_JNI_OnLoad", "_accidental_export"));

        assertThrows(NativeCompilationException.class,
                () -> validator.validate(elfData, NativeTarget.LINUX_X86_64));
        assertThrows(NativeCompilationException.class,
                () -> validator.validate(elfExtra, NativeTarget.LINUX_X86_64));
        assertThrows(NativeCompilationException.class,
                () -> validator.validate(machData, NativeTarget.MACOS_X86_64));
        assertThrows(NativeCompilationException.class,
                () -> validator.validate(machExtra, NativeTarget.MACOS_X86_64));
    }

    static byte[] elf(final int machine, final boolean jniOnLoad) {
        return elfExports(machine, jniOnLoad ? new String[]{"JNI_OnLoad"} : new String[0]);
    }

    private static byte[] elfExports(final int machine, final String... exports) {
        final byte[] bytes = new byte[512];
        bytes[0] = 0x7f;
        bytes[1] = 'E';
        bytes[2] = 'L';
        bytes[3] = 'F';
        bytes[4] = 2;
        bytes[5] = 1;
        final ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort(16, (short) 3);
        header.putShort(18, (short) machine);
        header.putLong(40, 64);
        header.putShort(58, (short) 64);
        header.putShort(60, (short) 3);
        final int dynamic = 128;
        header.putInt(dynamic + 4, 11);
        header.putLong(dynamic + 24, 256);
        header.putLong(dynamic + 32, 24L * (exports.length + 1));
        header.putInt(dynamic + 40, 2);
        header.putLong(dynamic + 56, 24);
        final int strings = 192;
        header.putInt(strings + 4, 3);
        header.putLong(strings + 24, 320);
        header.putLong(strings + 32, 32);
        int stringCursor = 321;
        for (int index = 0; index < exports.length; index++) {
            final int symbol = 256 + 24 * (index + 1);
            header.putInt(symbol, stringCursor - 320);
            header.put(symbol + 4, (byte) 0x12);
            header.putShort(symbol + 6, (short) 1);
            final byte[] name = exports[index].getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            System.arraycopy(name, 0, bytes, stringCursor, name.length);
            stringCursor += name.length + 1;
        }
        return bytes;
    }

    private static byte[] pe(final int machine, final String... exports) {
        final byte[] bytes = new byte[1024];
        bytes[0] = 'M';
        bytes[1] = 'Z';
        final ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(0x3c, 64);
        header.putInt(64, 0x00004550);
        header.putShort(68, (short) machine);
        header.putShort(70, (short) 1);
        header.putShort(84, (short) 240);
        header.putShort(86, (short) 0x2000);
        final int optional = 88;
        header.putShort(optional, (short) 0x20b);
        header.putInt(optional + 108, 16);
        header.putInt(optional + 112, 0x1000);
        header.putInt(optional + 116, 0x180);
        final int section = optional + 240;
        header.putInt(section + 8, 0x200);
        header.putInt(section + 12, 0x1000);
        header.putInt(section + 16, 0x200);
        header.putInt(section + 20, 0x200);

        final int exportDirectory = 0x200;
        header.putInt(exportDirectory + 20, exports.length);
        header.putInt(exportDirectory + 24, exports.length);
        header.putInt(exportDirectory + 28, 0x1040);
        header.putInt(exportDirectory + 32, 0x1060);
        header.putInt(exportDirectory + 36, 0x1080);
        int stringOffset = 0x2a0;
        for (int index = 0; index < exports.length; index++) {
            header.putInt(0x240 + index * 4, 0x1200 + index * 4);
            header.putInt(0x260 + index * 4, 0x1000 + stringOffset - 0x200);
            header.putShort(0x280 + index * 2, (short) index);
            final byte[] name = exports[index].getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            System.arraycopy(name, 0, bytes, stringOffset, name.length);
            stringOffset += name.length + 1;
        }
        return bytes;
    }

    private static byte[] peWithoutExportDirectory(final int machine) {
        final byte[] bytes = pe(machine, "JNI_OnLoad");
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(88 + 112, 0);
        return bytes;
    }

    private static byte[] machO(final int cpu) {
        return machOExports(cpu, "_JNI_OnLoad");
    }

    private static byte[] machOExports(final int cpu, final String... exports) {
        final byte[] bytes = new byte[512];
        final ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(0, 0xfeedfacf);
        header.putInt(4, cpu);
        header.putInt(12, 6);
        header.putInt(16, 2);
        header.putInt(20, 104);
        header.putInt(32, 2);
        header.putInt(36, 24);
        header.putInt(40, 160);
        header.putInt(44, exports.length);
        header.putInt(48, 256);
        header.putInt(52, 128);
        header.putInt(56, 11);
        header.putInt(60, 80);
        header.putInt(72, 0);
        header.putInt(76, exports.length);
        int stringCursor = 257;
        for (int index = 0; index < exports.length; index++) {
            final int symbolOffset = 160 + 16 * index;
            header.putInt(symbolOffset, stringCursor - 256);
            header.put(symbolOffset + 4, (byte) 0x0f);
            header.put(symbolOffset + 5, (byte) 1);
            final byte[] symbol = exports[index].getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            System.arraycopy(symbol, 0, bytes, stringCursor, symbol.length);
            stringCursor += symbol.length + 1;
        }
        return bytes;
    }
}
