package dev.skidfuscator.nativetoolchain.vm;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import dev.skidfuscator.nativetoolchain.VmProtectionSettings;

/** Canonical bounded binary exchange format consumed by SkidVirtualizePass. */
public final class VmProgramCodec {
    private static final byte[] MAGIC = {'S', 'K', 'V', 'M', 'P', 'R', 'O', 'G'};
    public static final int MAX_PAYLOAD_BYTES = 64 * 1024 * 1024;
    private static final int MAX_STRING_BYTES = 1024 * 1024;
    private static final int MAX_METHODS = 100_000;
    private static final int MAX_BLOCKS_PER_METHOD = 1_000_000;

    public byte[] encode(final VmProgram program) {
        Objects.requireNonNull(program, "program");
        try {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final DataOutputStream output = new DataOutputStream(bytes);
            output.write(MAGIC);
            output.writeInt(program.formatVersion());
            writeString(output, program.buildId());
            writeString(output, program.protection().profile().name());
            writeString(output, program.protection().response().name());
            output.writeBoolean(program.protection().integrity());
            output.writeBoolean(program.protection().antiDebug());
            output.writeBoolean(program.protection().antiInstrumentation());
            output.writeBoolean(program.protection().timingChecks());
            output.writeBoolean(program.protection().diversifiedDispatch());
            output.writeInt(program.protection().handlerCloneCount());
            output.writeInt(program.protection().superinstructionBudget());
            output.writeInt(program.protection().decodedBlockCacheEntries());
            output.writeInt(program.protection().delayedHaltMinimumMillis());
            output.writeInt(program.opcodes().values().size());
            program.opcodes().values().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        writeStringUnchecked(output, entry.getKey());
                        writeByteUnchecked(output, entry.getValue());
                    });
            output.writeInt(program.superinstructions().size());
            for (final VmProgram.VmSuperinstruction item : program.superinstructions()) {
                writeString(output, item.name());
                writeString(output, item.firstOperation());
                writeString(output, item.secondOperation());
            }
            output.writeInt(program.keyFragments().size());
            for (final byte[] fragment : program.keyFragments()) {
                output.writeInt(fragment.length);
                output.write(fragment);
            }
            output.writeInt(program.methods().size());
            for (final VmProgram.VmMethod method : program.methods().stream()
                    .sorted(Comparator.comparing(VmProgram.VmMethod::symbol)).toList()) {
                writeString(output, method.symbol());
                writeString(output, method.javaOwner());
                writeString(output, method.javaName());
                writeString(output, method.javaDescriptor());
                output.writeInt(method.registerCount());
                output.writeInt(method.entryBlock());
                output.writeInt(method.operandMask());
                output.writeInt(method.metadata().size());
                method.metadata().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                    writeStringUnchecked(output, entry.getKey());
                    writeStringUnchecked(output, entry.getValue());
                });
                output.writeInt(method.blocks().size());
                for (final VmProgram.EncryptedBlock block : method.blocks()) {
                    output.writeInt(block.index());
                    writeBytes(output, block.nonce());
                    writeBytes(output, block.ciphertext());
                }
            }
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode in-memory VM program", exception);
        }
    }

    /** Decodes and structurally validates a payload before native embedding. */
    public VmProgram decode(final byte[] encoded) throws IOException {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length > MAX_PAYLOAD_BYTES) {
            throw new IOException("VM payload exceeds the 64 MiB safety limit");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            final byte[] magic = input.readNBytes(MAGIC.length);
            if (!java.util.Arrays.equals(MAGIC, magic)) throw new IOException("Invalid VM payload magic");
            final int version = input.readInt();
            if (version != VmProgram.CURRENT_FORMAT) throw new IOException("Unsupported VM payload version " + version);
            final String buildId = readString(input);
            final VmProtectionSettings protection;
            try {
                final VmProtectionSettings.Profile profile = VmProtectionSettings.Profile.valueOf(readString(input));
                final VmProtectionSettings.Response response = VmProtectionSettings.Response.valueOf(readString(input));
                protection = new VmProtectionSettings(profile, response,
                        input.readBoolean(), input.readBoolean(), input.readBoolean(), input.readBoolean(),
                        input.readBoolean(), input.readInt(), input.readInt(), input.readInt(), input.readInt());
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid VM protection policy", invalid);
            }

            final int opcodeCount = boundedCount(input.readInt(), 1, 256, "opcode count");
            final Map<String, Integer> opcodeValues = new LinkedHashMap<>();
            for (int index = 0; index < opcodeCount; index++) {
                final String name = readString(input);
                final int value = input.readUnsignedByte();
                if (opcodeValues.put(name, value) != null) throw new IOException("Duplicate VM opcode name " + name);
            }
            final VmOpcodeTable opcodes;
            try {
                opcodes = new VmOpcodeTable(opcodeValues);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid VM opcode table", invalid);
            }

            final int superCount = boundedCount(input.readInt(), 0, 256 - opcodeCount,
                    "superinstruction count");
            final List<VmProgram.VmSuperinstruction> superinstructions = new ArrayList<>(superCount);
            final java.util.Set<String> superNames = new java.util.HashSet<>();
            for (int index = 0; index < superCount; index++) {
                try {
                    final VmProgram.VmSuperinstruction item = new VmProgram.VmSuperinstruction(
                            readString(input), readString(input), readString(input));
                    if (!superNames.add(item.name())) throw new IOException(
                            "Duplicate VM superinstruction " + item.name());
                    superinstructions.add(item);
                } catch (IllegalArgumentException invalid) {
                    throw new IOException("Invalid VM superinstruction", invalid);
                }
            }

            final int fragmentCount = boundedCount(input.readInt(), 2, 32, "key fragment count");
            final List<byte[]> fragments = new ArrayList<>(fragmentCount);
            for (int index = 0; index < fragmentCount; index++) {
                final byte[] fragment = readBytes(input, XChaCha20Poly1305.KEY_BYTES,
                        XChaCha20Poly1305.KEY_BYTES, "key fragment");
                fragments.add(fragment);
            }

            final int methodCount = boundedCount(input.readInt(), 1, MAX_METHODS, "method count");
            final List<VmProgram.VmMethod> methods = new ArrayList<>(methodCount);
            final java.util.Set<String> symbols = new java.util.HashSet<>();
            for (int methodIndex = 0; methodIndex < methodCount; methodIndex++) {
                final String symbol = readString(input);
                if (!symbols.add(symbol)) throw new IOException("Duplicate VM method symbol " + symbol);
                final String owner = readString(input);
                final String name = readString(input);
                final String descriptor = readString(input);
                final int registerCount = input.readInt();
                final int entryBlock = input.readInt();
                final int operandMask = input.readInt();
                if (registerCount < 0 || registerCount > 16_777_216) {
                    throw new IOException("Invalid VM register count " + registerCount);
                }
                final int metadataCount = boundedCount(input.readInt(), 0, 65_536, "metadata count");
                final Map<String, String> metadata = new LinkedHashMap<>();
                for (int index = 0; index < metadataCount; index++) {
                    final String key = readString(input);
                    if (metadata.put(key, readString(input)) != null) {
                        throw new IOException("Duplicate VM metadata key " + key);
                    }
                }
                final int blockCount = boundedCount(input.readInt(), 1, MAX_BLOCKS_PER_METHOD, "block count");
                final List<VmProgram.EncryptedBlock> blocks = new ArrayList<>(blockCount);
                final boolean[] indexes = new boolean[blockCount];
                for (int index = 0; index < blockCount; index++) {
                    final int blockIndex = input.readInt();
                    if (blockIndex < 0 || blockIndex >= blockCount || indexes[blockIndex]) {
                        throw new IOException("Invalid or duplicate VM block index " + blockIndex);
                    }
                    indexes[blockIndex] = true;
                    final byte[] nonce = readBytes(input, XChaCha20Poly1305.NONCE_BYTES,
                            XChaCha20Poly1305.NONCE_BYTES, "block nonce");
                    final byte[] ciphertext = readBytes(input, XChaCha20Poly1305.TAG_BYTES,
                            MAX_PAYLOAD_BYTES, "block ciphertext");
                    blocks.add(new VmProgram.EncryptedBlock(blockIndex, nonce, ciphertext));
                }
                blocks.sort(Comparator.comparingInt(VmProgram.EncryptedBlock::index));
                try {
                    methods.add(new VmProgram.VmMethod(symbol, owner, name, descriptor, registerCount,
                            entryBlock, operandMask, metadata, blocks));
                } catch (IllegalArgumentException invalid) {
                    throw new IOException("Invalid VM method layout for " + symbol, invalid);
                }
            }
            if (input.read() != -1) throw new IOException("Trailing bytes after VM payload");
            try {
                return new VmProgram(version, buildId, opcodes, superinstructions, methods, fragments, protection);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid VM program", invalid);
            }
        } catch (EOFException truncated) {
            throw new IOException("Truncated VM payload", truncated);
        }
    }

    private static void writeBytes(final DataOutputStream output, final byte[] value) throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }

    private static byte[] readBytes(final DataInputStream input, final int minimum, final int maximum,
                                    final String label) throws IOException {
        final int length = input.readInt();
        if (length < minimum || length > maximum || length > input.available()) {
            throw new IOException("Invalid " + label + " length " + length);
        }
        final byte[] value = input.readNBytes(length);
        if (value.length != length) throw new IOException("Truncated " + label);
        return value;
    }

    private static String readString(final DataInputStream input) throws IOException {
        final byte[] value = readBytes(input, 0, MAX_STRING_BYTES, "string");
        final String decoded = new String(value, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(value, decoded.getBytes(StandardCharsets.UTF_8))) {
            throw new IOException("VM payload contains malformed UTF-8");
        }
        return decoded;
    }

    private static int boundedCount(final int value, final int minimum, final int maximum,
                                    final String label) throws IOException {
        if (value < minimum || value > maximum) throw new IOException("Invalid " + label + " " + value);
        return value;
    }

    private static void writeString(final DataOutputStream output, final String value) throws IOException {
        final byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > 1_048_576) throw new IllegalArgumentException("VM metadata exceeds 1 MiB");
        writeBytes(output, utf8);
    }

    private static void writeStringUnchecked(final DataOutputStream output, final String value) {
        try { writeString(output, value); } catch (IOException exception) { throw new IllegalStateException(exception); }
    }

    private static void writeByteUnchecked(final DataOutputStream output, final int value) {
        try { output.writeByte(value); } catch (IOException exception) { throw new IllegalStateException(exception); }
    }
}
