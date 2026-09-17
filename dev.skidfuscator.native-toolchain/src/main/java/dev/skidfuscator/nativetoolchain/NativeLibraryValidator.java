package dev.skidfuscator.nativetoolchain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/** Validates native container kind, architecture, library type, and JNI entrypoint presence. */
public final class NativeLibraryValidator {
    private static final byte[] JNI_ON_LOAD = "JNI_OnLoad".getBytes(StandardCharsets.US_ASCII);

    public void validate(final Path library, final NativeTarget target) throws IOException {
        Objects.requireNonNull(library, "library");
        Objects.requireNonNull(target, "target");
        if (!Files.isRegularFile(library) || Files.size(library) < 32) {
            throw invalid(target, "file is missing or too small");
        }
        final byte[] header = readPrefix(library, 4096);
        switch (target) {
            case WINDOWS_X86_64, WINDOWS_AARCH64 -> {
                validatePe(library, header, target);
                return;
            }
            case LINUX_X86_64, LINUX_AARCH64 -> validateElf(library, header, target);
            case MACOS_X86_64, MACOS_AARCH64 -> validateMachO(library, header, target);
        }
    }

    private static void validatePe(final Path library, final byte[] header, final NativeTarget target)
            throws IOException {
        if (header.length < 64 || header[0] != 'M' || header[1] != 'Z') {
            throw invalid(target, "expected a PE DLL");
        }
        final int peOffset = little(header).getInt(0x3c);
        final ByteBuffer pe = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        try (FileChannel channel = FileChannel.open(library, StandardOpenOption.READ)) {
            channel.position(Integer.toUnsignedLong(peOffset));
            while (pe.hasRemaining() && channel.read(pe) >= 0) {
                // Fill the fixed PE/COFF header.
            }
        }
        if (pe.position() != pe.capacity()) {
            throw invalid(target, "truncated PE header");
        }
        pe.flip();
        if (pe.getInt() != 0x00004550) {
            throw invalid(target, "invalid PE signature");
        }
        final int machine = Short.toUnsignedInt(pe.getShort());
        final int sectionCount = Short.toUnsignedInt(pe.getShort());
        pe.position(20);
        final int optionalHeaderSize = Short.toUnsignedInt(pe.getShort());
        final int expectedMachine = target == NativeTarget.WINDOWS_X86_64 ? 0x8664 : 0xaa64;
        final int characteristics = Short.toUnsignedInt(pe.getShort());
        if (machine != expectedMachine || (characteristics & 0x2000) == 0) {
            throw invalid(target, "PE machine/library flags do not match the requested target");
        }
        if (sectionCount == 0 || optionalHeaderSize == 0) {
            throw invalid(target, "PE section or optional header is absent");
        }
        validatePeExports(library, Integer.toUnsignedLong(peOffset), sectionCount,
                optionalHeaderSize, target);
    }

    private static void validatePeExports(
            final Path library,
            final long peOffset,
            final int sectionCount,
            final int optionalHeaderSize,
            final NativeTarget target
    ) throws IOException {
        try (FileChannel channel = FileChannel.open(library, StandardOpenOption.READ)) {
            final long optionalOffset = checkedAdd(peOffset, 24, target);
            final ByteBuffer optional = readExactly(channel, optionalOffset, optionalHeaderSize, target,
                    "truncated PE optional header");
            final int magic = Short.toUnsignedInt(optional.getShort(0));
            final int directoryCountOffset;
            final int exportDirectoryOffset;
            if (magic == 0x20b) {
                directoryCountOffset = 108;
                exportDirectoryOffset = 112;
            } else if (magic == 0x10b) {
                directoryCountOffset = 92;
                exportDirectoryOffset = 96;
            } else {
                throw invalid(target, "unsupported PE optional-header format");
            }
            if (optionalHeaderSize < exportDirectoryOffset + 8
                    || Integer.toUnsignedLong(optional.getInt(directoryCountOffset)) < 1) {
                throw invalid(target, "PE export directory is absent");
            }
            final long exportRva = Integer.toUnsignedLong(optional.getInt(exportDirectoryOffset));
            final long exportSize = Integer.toUnsignedLong(optional.getInt(exportDirectoryOffset + 4));
            if (exportRva == 0 || exportSize < 40) {
                throw invalid(target, "PE export directory is absent or truncated");
            }

            final long sectionTableOffset = checkedAdd(optionalOffset, optionalHeaderSize, target);
            final Section[] sections = new Section[sectionCount];
            for (int index = 0; index < sectionCount; index++) {
                final long sectionOffset = checkedAdd(sectionTableOffset, (long) index * 40, target);
                final ByteBuffer section = readExactly(channel, sectionOffset, 40, target,
                        "truncated PE section table");
                sections[index] = new Section(
                        Integer.toUnsignedLong(section.getInt(12)),
                        Integer.toUnsignedLong(section.getInt(8)),
                        Integer.toUnsignedLong(section.getInt(16)),
                        Integer.toUnsignedLong(section.getInt(20)));
            }

            final long exportOffset = rvaToFileOffset(exportRva, sections, channel.size(), target);
            final ByteBuffer exports = readExactly(channel, exportOffset, 40, target,
                    "truncated PE export directory");
            final long functionCount = Integer.toUnsignedLong(exports.getInt(20));
            final long nameCount = Integer.toUnsignedLong(exports.getInt(24));
            if (functionCount != 1 || nameCount != 1) {
                throw invalid(target, "expected JNI_OnLoad to be the sole PE export");
            }
            final long functionTableRva = Integer.toUnsignedLong(exports.getInt(28));
            final long nameTableRva = Integer.toUnsignedLong(exports.getInt(32));
            final long ordinalTableRva = Integer.toUnsignedLong(exports.getInt(36));
            final ByteBuffer functionTable = readAtRva(channel, functionTableRva, 4, sections, target,
                    "invalid PE export function table");
            final ByteBuffer nameTable = readAtRva(channel, nameTableRva, 4, sections, target,
                    "invalid PE export name table");
            final ByteBuffer ordinalTable = readAtRva(channel, ordinalTableRva, 2, sections, target,
                    "invalid PE export ordinal table");
            if (functionTable.getInt(0) == 0 || Short.toUnsignedInt(ordinalTable.getShort(0)) != 0) {
                throw invalid(target, "invalid JNI_OnLoad PE export entry");
            }
            final long nameRva = Integer.toUnsignedLong(nameTable.getInt(0));
            final long nameOffset = rvaToFileOffset(nameRva, sections, channel.size(), target);
            final String exportName = readAsciiZ(channel, nameOffset, 256, target);
            if (!"JNI_OnLoad".equals(exportName)) {
                throw invalid(target, "expected JNI_OnLoad to be the sole PE export");
            }
        }
    }

    private static ByteBuffer readAtRva(
            final FileChannel channel,
            final long rva,
            final int length,
            final Section[] sections,
            final NativeTarget target,
            final String error
    ) throws IOException {
        if (rva == 0) throw invalid(target, error);
        return readExactly(channel, rvaToFileOffset(rva, sections, channel.size(), target),
                length, target, error);
    }

    private static long rvaToFileOffset(
            final long rva,
            final Section[] sections,
            final long fileSize,
            final NativeTarget target
    ) throws IOException {
        for (final Section section : sections) {
            final long span = Math.max(section.virtualSize(), section.rawSize());
            if (rva >= section.virtualAddress() && rva - section.virtualAddress() < span) {
                final long delta = rva - section.virtualAddress();
                if (delta >= section.rawSize()) break;
                final long offset = checkedAdd(section.rawOffset(), delta, target);
                if (offset >= fileSize) break;
                return offset;
            }
        }
        throw invalid(target, "PE export RVA is not backed by file data");
    }

    private static ByteBuffer readExactly(
            final FileChannel channel,
            final long offset,
            final int length,
            final NativeTarget target,
            final String error
    ) throws IOException {
        if (offset < 0 || length < 0 || offset > channel.size() || length > channel.size() - offset) {
            throw invalid(target, error);
        }
        final ByteBuffer result = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        channel.position(offset);
        while (result.hasRemaining()) {
            if (channel.read(result) < 0) throw invalid(target, error);
        }
        return result.flip();
    }

    private static String readAsciiZ(
            final FileChannel channel,
            final long offset,
            final int maximumLength,
            final NativeTarget target
    ) throws IOException {
        final StringBuilder result = new StringBuilder();
        final ByteBuffer next = ByteBuffer.allocate(1);
        channel.position(offset);
        for (int index = 0; index < maximumLength; index++) {
            next.clear();
            if (channel.read(next) != 1) throw invalid(target, "truncated PE export name");
            final int value = Byte.toUnsignedInt(next.array()[0]);
            if (value == 0) return result.toString();
            if (value > 0x7f) throw invalid(target, "non-ASCII PE export name");
            result.append((char) value);
        }
        throw invalid(target, "unterminated PE export name");
    }

    private static long checkedAdd(final long left, final long right, final NativeTarget target)
            throws IOException {
        if (left < 0 || right < 0 || left > Long.MAX_VALUE - right) {
            throw invalid(target, "PE offset overflow");
        }
        return left + right;
    }

    private record Section(long virtualAddress, long virtualSize, long rawSize, long rawOffset) {
    }

    private static void validateElf(final Path library, final byte[] header, final NativeTarget target)
            throws IOException {
        if (header.length < 64 || header[0] != 0x7f || header[1] != 'E'
                || header[2] != 'L' || header[3] != 'F' || header[4] != 2) {
            throw invalid(target, "expected a 64-bit ELF shared object");
        }
        final ByteOrder order = switch (header[5]) {
            case 1 -> ByteOrder.LITTLE_ENDIAN;
            case 2 -> ByteOrder.BIG_ENDIAN;
            default -> throw invalid(target, "invalid ELF byte order");
        };
        final ByteBuffer elf = ByteBuffer.wrap(header).order(order);
        final int type = Short.toUnsignedInt(elf.getShort(16));
        final int machine = Short.toUnsignedInt(elf.getShort(18));
        final int expectedMachine = target == NativeTarget.LINUX_X86_64 ? 62 : 183;
        if (type != 3 || machine != expectedMachine) {
            throw invalid(target, "ELF type/architecture does not match the requested target");
        }
        final long sectionOffset = elf.getLong(40);
        final int sectionEntrySize = Short.toUnsignedInt(elf.getShort(58));
        final int sectionCount = Short.toUnsignedInt(elf.getShort(60));
        if (sectionOffset <= 0 || sectionEntrySize < 64 || sectionCount == 0) {
            throw invalid(target, "ELF dynamic symbol table is absent");
        }
        try (FileChannel channel = FileChannel.open(library, StandardOpenOption.READ)) {
            int dynamicIndex = -1;
            ByteBuffer dynamic = null;
            for (int index = 0; index < sectionCount; index++) {
                final long offset = checkedTableOffset(sectionOffset, sectionEntrySize, index,
                        channel.size(), target, "ELF section table overflow");
                final ByteBuffer section = readExactly(channel, offset, sectionEntrySize, target,
                        "truncated ELF section table").order(order);
                if (section.getInt(4) == 11) {
                    if (dynamic != null) throw invalid(target, "ELF contains duplicate dynamic symbol tables");
                    dynamicIndex = index;
                    dynamic = section;
                }
            }
            if (dynamic == null) throw invalid(target, "ELF dynamic symbol table is absent");
            final long symbolOffset = dynamic.getLong(24);
            final long symbolSize = dynamic.getLong(32);
            final int stringIndex = dynamic.getInt(40);
            final long symbolEntrySize = dynamic.getLong(56);
            if (stringIndex < 0 || stringIndex >= sectionCount || symbolEntrySize < 24
                    || symbolSize <= 0 || symbolSize % symbolEntrySize != 0
                    || symbolSize / symbolEntrySize > 1_000_000) {
                throw invalid(target, "invalid ELF dynamic symbol table");
            }
            final long stringHeaderOffset = checkedTableOffset(sectionOffset, sectionEntrySize, stringIndex,
                    channel.size(), target, "ELF string table overflow");
            final ByteBuffer strings = readExactly(channel, stringHeaderOffset, sectionEntrySize, target,
                    "truncated ELF string-table header").order(order);
            if (strings.getInt(4) != 3) throw invalid(target, "ELF dynamic symbol string table is invalid");
            final long stringOffset = strings.getLong(24);
            final long stringSize = strings.getLong(32);
            requireRegion(stringOffset, stringSize, channel.size(), target, "ELF string table is out of bounds");
            requireRegion(symbolOffset, symbolSize, channel.size(), target, "ELF symbol table is out of bounds");
            final java.util.Set<String> exports = new java.util.LinkedHashSet<>();
            final int entries = Math.toIntExact(symbolSize / symbolEntrySize);
            for (int index = 0; index < entries; index++) {
                final ByteBuffer symbol = readExactly(channel, symbolOffset + index * symbolEntrySize,
                        24, target, "truncated ELF dynamic symbol").order(order);
                final long nameIndex = Integer.toUnsignedLong(symbol.getInt(0));
                final int info = Byte.toUnsignedInt(symbol.get(4));
                final int visibility = Byte.toUnsignedInt(symbol.get(5)) & 3;
                final int section = Short.toUnsignedInt(symbol.getShort(6));
                final int binding = info >>> 4;
                if ((binding == 1 || binding == 2) && (visibility == 0 || visibility == 3)
                        && section != 0 && nameIndex != 0) {
                    if (nameIndex >= stringSize) throw invalid(target, "ELF symbol name is out of bounds");
                    exports.add(readAsciiZ(channel, stringOffset + nameIndex,
                            Math.toIntExact(Math.min(4096, stringSize - nameIndex)), target));
                }
            }
            requireSoleExport(exports, "JNI_OnLoad", target, "ELF");
        }
    }

    private static void validateMachO(final Path library, final byte[] header, final NativeTarget target)
            throws IOException {
        if (header.length < 32) {
            throw invalid(target, "expected a 64-bit Mach-O dylib");
        }
        final ByteBuffer mach = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        if (mach.getInt(0) != 0xfeedfacf) {
            throw invalid(target, "expected a little-endian 64-bit Mach-O dylib");
        }
        final int expectedCpu = target == NativeTarget.MACOS_X86_64 ? 0x01000007 : 0x0100000c;
        if (mach.getInt(4) != expectedCpu || mach.getInt(12) != 6) {
            throw invalid(target, "Mach-O CPU/file type does not match the requested target");
        }
        final int commandCount = mach.getInt(16);
        final long commandBytes = Integer.toUnsignedLong(mach.getInt(20));
        if (commandCount <= 0 || commandCount > 100_000 || commandBytes < 8) {
            throw invalid(target, "Mach-O load commands are absent or invalid");
        }
        try (FileChannel channel = FileChannel.open(library, StandardOpenOption.READ)) {
            requireRegion(32, commandBytes, channel.size(), target, "Mach-O load commands are out of bounds");
            long cursor = 32;
            ByteBuffer symbolCommand = null;
            ByteBuffer dynamicCommand = null;
            for (int index = 0; index < commandCount; index++) {
                final ByteBuffer prefix = readExactly(channel, cursor, 8, target,
                        "truncated Mach-O load command").order(ByteOrder.LITTLE_ENDIAN);
                final int command = prefix.getInt(0);
                final int size = prefix.getInt(4);
                if (size < 8 || (size & 3) != 0 || size > commandBytes || cursor + size > 32 + commandBytes) {
                    throw invalid(target, "invalid Mach-O load-command size");
                }
                if (command == 2) {
                    if (symbolCommand != null) throw invalid(target, "duplicate Mach-O symbol table command");
                    symbolCommand = readExactly(channel, cursor, size, target,
                            "truncated Mach-O symbol table command").order(ByteOrder.LITTLE_ENDIAN);
                } else if (command == 11) {
                    if (dynamicCommand != null) throw invalid(target, "duplicate Mach-O dynamic symbol command");
                    dynamicCommand = readExactly(channel, cursor, size, target,
                            "truncated Mach-O dynamic symbol command").order(ByteOrder.LITTLE_ENDIAN);
                }
                cursor += size;
            }
            if (symbolCommand == null || symbolCommand.capacity() < 24
                    || dynamicCommand == null || dynamicCommand.capacity() < 24) {
                throw invalid(target, "Mach-O external symbol tables are absent");
            }
            final long symbolOffset = Integer.toUnsignedLong(symbolCommand.getInt(8));
            final long symbolCount = Integer.toUnsignedLong(symbolCommand.getInt(12));
            final long stringOffset = Integer.toUnsignedLong(symbolCommand.getInt(16));
            final long stringSize = Integer.toUnsignedLong(symbolCommand.getInt(20));
            final long firstExternal = Integer.toUnsignedLong(dynamicCommand.getInt(16));
            final long externalCount = Integer.toUnsignedLong(dynamicCommand.getInt(20));
            if (symbolCount > 1_000_000 || firstExternal > symbolCount
                    || externalCount > symbolCount - firstExternal) {
                throw invalid(target, "invalid Mach-O external symbol range");
            }
            requireRegion(symbolOffset, symbolCount * 16, channel.size(), target,
                    "Mach-O symbol table is out of bounds");
            requireRegion(stringOffset, stringSize, channel.size(), target,
                    "Mach-O string table is out of bounds");
            final java.util.Set<String> exports = new java.util.LinkedHashSet<>();
            for (long index = firstExternal; index < firstExternal + externalCount; index++) {
                final ByteBuffer symbol = readExactly(channel, symbolOffset + index * 16, 16, target,
                        "truncated Mach-O symbol").order(ByteOrder.LITTLE_ENDIAN);
                final long nameIndex = Integer.toUnsignedLong(symbol.getInt(0));
                final int symbolType = Byte.toUnsignedInt(symbol.get(4));
                if ((symbolType & 1) != 0 && (symbolType & 0x0e) != 0) {
                    if (nameIndex == 0 || nameIndex >= stringSize) {
                        throw invalid(target, "Mach-O symbol name is out of bounds");
                    }
                    exports.add(readAsciiZ(channel, stringOffset + nameIndex,
                            Math.toIntExact(Math.min(4096, stringSize - nameIndex)), target));
                }
            }
            requireSoleExport(exports, "_JNI_OnLoad", target, "Mach-O");
        }
    }

    private static long checkedTableOffset(final long base, final int entrySize, final int index,
                                           final long fileSize, final NativeTarget target, final String error)
            throws IOException {
        if (base < 0 || entrySize < 0 || index < 0 || (long) index > (Long.MAX_VALUE - base) / entrySize) {
            throw invalid(target, error);
        }
        final long result = base + (long) entrySize * index;
        if (result > fileSize) throw invalid(target, error);
        return result;
    }

    private static void requireRegion(final long offset, final long size, final long fileSize,
                                      final NativeTarget target, final String error) throws IOException {
        if (offset < 0 || size < 0 || offset > fileSize || size > fileSize - offset) {
            throw invalid(target, error);
        }
    }

    private static void requireSoleExport(final java.util.Set<String> exports, final String expected,
                                          final NativeTarget target, final String format) throws IOException {
        if (!exports.equals(java.util.Set.of(expected))) {
            throw invalid(target, "expected " + expected + " to be the sole " + format
                    + " export, found " + exports);
        }
    }

    private static byte[] readPrefix(final Path path, final int maximum) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return input.readNBytes(maximum);
        }
    }

    private static boolean contains(final Path path, final byte[] needle) throws IOException {
        int matched = 0;
        try (InputStream input = Files.newInputStream(path)) {
            int value;
            while ((value = input.read()) != -1) {
                if ((byte) value == needle[matched]) {
                    if (++matched == needle.length) return true;
                } else {
                    matched = (byte) value == needle[0] ? 1 : 0;
                }
            }
        }
        return false;
    }

    private static ByteBuffer little(final byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static NativeCompilationException invalid(final NativeTarget target, final String reason) {
        return new NativeCompilationException("Invalid native library for " + target.id() + ": " + reason);
    }
}
