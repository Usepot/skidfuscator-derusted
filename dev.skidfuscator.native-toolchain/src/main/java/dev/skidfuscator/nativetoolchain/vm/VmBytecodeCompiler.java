package dev.skidfuscator.nativetoolchain.vm;

import dev.skidfuscator.nativeir.NativeBackend;
import dev.skidfuscator.nativeir.NativeBlock;
import dev.skidfuscator.nativeir.NativeExceptionEdge;
import dev.skidfuscator.nativeir.NativeFunction;
import dev.skidfuscator.nativeir.NativeInstruction;
import dev.skidfuscator.nativeir.NativeIrVerifier;
import dev.skidfuscator.nativeir.NativeModule;
import dev.skidfuscator.nativeir.NativeOpcode;
import dev.skidfuscator.nativeir.NativeOperand;
import dev.skidfuscator.nativeir.NativeParameter;
import dev.skidfuscator.nativeir.NativeTerminator;
import dev.skidfuscator.nativeir.NativeType;
import dev.skidfuscator.nativetoolchain.VmProtectionSettings;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Lowers VM-marked Native IR functions to a typed register program. Each basic
 * block is independently XChaCha20-Poly1305 authenticated so dispatch only
 * exposes the bounded block currently being executed.
 */
public final class VmBytecodeCompiler {
    private static final byte[] BLOCK_MAGIC = {'S', 'K', 'V', 'M'};
    private final NativeIrVerifier verifier;
    private final XChaCha20Poly1305 cipher;

    public VmBytecodeCompiler() {
        this(new NativeIrVerifier(), new XChaCha20Poly1305());
    }

    VmBytecodeCompiler(final NativeIrVerifier verifier, final XChaCha20Poly1305 cipher) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
    }

    public VmProgram compile(final NativeModule module, final String buildId, final SecureRandom random) {
        return compile(module, buildId, random, VmProtectionSettings.aggressive());
    }

    public VmProgram compile(final NativeModule module, final String buildId, final SecureRandom random,
                             final VmProtectionSettings protection) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(buildId, "buildId");
        Objects.requireNonNull(random, "random");
        Objects.requireNonNull(protection, "protection");
        verifier.verifyOrThrow(module);

        final List<VmProgram.VmSuperinstruction> superinstructions = selectSuperinstructions(
                module, protection.superinstructionBudget());
        final VmOpcodeTable opcodes = VmOpcodeTable.randomized(random,
                superinstructions.stream().map(VmProgram.VmSuperinstruction::name).toList());
        final byte[] key = new byte[XChaCha20Poly1305.KEY_BYTES];
        random.nextBytes(key);
        try {
            final java.util.Set<String> usedNonces = new java.util.HashSet<>();
            final List<VmProgram.VmMethod> methods = module.functions().stream()
                    .filter(function -> function.backend() == NativeBackend.VM)
                    .sorted(Comparator.comparing(NativeFunction::symbol))
                    .map(function -> lower(function, buildId, opcodes, superinstructions,
                            key, random, usedNonces, protection))
                    .toList();
            if (methods.isEmpty()) throw new IllegalArgumentException("The native module has no VM functions");
            return new VmProgram(VmProgram.CURRENT_FORMAT, buildId, opcodes, superinstructions, methods,
                    fragmentKey(key, protection.handlerCloneCount(), random), protection);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** Reassembles generated fragments into a caller-owned key buffer. */
    public static byte[] reassembleKey(final List<byte[]> fragments) {
        Objects.requireNonNull(fragments, "fragments");
        if (fragments.size() < 2) throw new IllegalArgumentException("At least two key fragments are required");
        final byte[] key = new byte[XChaCha20Poly1305.KEY_BYTES];
        for (final byte[] fragment : fragments) {
            if (fragment.length != key.length) throw new IllegalArgumentException("Invalid key fragment length");
            for (int index = 0; index < key.length; index++) key[index] ^= fragment[index];
        }
        return key;
    }

    public byte[] decryptBlock(final VmProgram program, final VmProgram.VmMethod method,
                               final VmProgram.EncryptedBlock block) throws javax.crypto.AEADBadTagException {
        final byte[] key = reassembleKey(program.keyFragments());
        try {
            return cipher.open(key, block.nonce(), aad(program.buildId(), program.opcodes(),
                            program.superinstructions(), program.protection(),
                            method.symbol(), method.javaOwner(), method.javaName(), method.javaDescriptor(),
                            method.registerCount(), method.entryBlock(), method.operandMask(), method.metadata(),
                            method.blocks().size(), block.index()),
                    block.ciphertext());
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private VmProgram.VmMethod lower(
            final NativeFunction function,
            final String buildId,
            final VmOpcodeTable opcodes,
            final List<VmProgram.VmSuperinstruction> superinstructions,
            final byte[] key,
            final SecureRandom random,
            final java.util.Set<String> usedNonces,
            final VmProtectionSettings protection
    ) {
        final List<NativeBlock> blocks = function.blocks().stream()
                .sorted(Comparator.comparing(NativeBlock::id)).toList();
        final Map<String, Integer> blockIndexes = new HashMap<>();
        for (int index = 0; index < blocks.size(); index++) blockIndexes.put(blocks.get(index).id(), index);

        final Map<String, Integer> registers = new LinkedHashMap<>();
        function.parameters().stream().sorted(Comparator.comparingInt(NativeParameter::index))
                .forEach(parameter -> registers.put(parameter.id(), registers.size()));
        for (final NativeBlock block : blocks) {
            for (final NativeInstruction instruction : block.instructions()) {
                registers.put(instruction.id(), registers.size());
            }
        }

        final int operandMask = random.nextInt();
        final int entryBlock = blockIndexes.get(function.entryBlock());
        final List<VmProgram.EncryptedBlock> encryptedBlocks = new ArrayList<>();
        for (int index = 0; index < blocks.size(); index++) {
            final byte[] plaintext = encodeBlock(blocks.get(index), registers, blockIndexes,
                    operandMask, opcodes, superinstructions);
            final byte[] nonce = new byte[XChaCha20Poly1305.NONCE_BYTES];
            boolean unique = false;
            for (int attempt = 0; attempt < 16 && !unique; attempt++) {
                random.nextBytes(nonce);
                unique = usedNonces.add(java.util.HexFormat.of().formatHex(nonce));
            }
            if (!unique) {
                Arrays.fill(plaintext, (byte) 0);
                throw new IllegalStateException("SecureRandom repeatedly produced a duplicate XChaCha nonce");
            }
            final byte[] encrypted;
            try {
                encrypted = cipher.seal(key, nonce,
                        aad(buildId, opcodes, superinstructions, protection,
                                function.symbol(), function.javaOwner(), function.javaName(),
                                function.javaDescriptor(), registers.size(), entryBlock, operandMask,
                                function.metadata(), blocks.size(), index), plaintext);
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
            encryptedBlocks.add(new VmProgram.EncryptedBlock(index, nonce, encrypted));
        }
        return new VmProgram.VmMethod(function.symbol(), function.javaOwner(), function.javaName(),
                function.javaDescriptor(), registers.size(), blockIndexes.get(function.entryBlock()), operandMask,
                function.metadata(), encryptedBlocks);
    }

    private static byte[] encodeBlock(
            final NativeBlock block,
            final Map<String, Integer> registers,
            final Map<String, Integer> blocks,
            final int mask,
            final VmOpcodeTable opcodes,
            final List<VmProgram.VmSuperinstruction> superinstructions
    ) {
        try {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final DataOutputStream output = new DataOutputStream(bytes);
            output.write(BLOCK_MAGIC);
            output.writeByte(VmProgram.CURRENT_FORMAT);
            final Map<String, VmProgram.VmSuperinstruction> pairs = new HashMap<>();
            for (final VmProgram.VmSuperinstruction item : superinstructions) {
                pairs.put(pairKey(item.firstOperation(), item.secondOperation()), item);
            }
            int encodedInstructionCount = 0;
            for (int index = 0; index < block.instructions().size(); index++) {
                encodedInstructionCount++;
                if (fusedAt(block.instructions(), index, pairs) != null) index++;
            }
            output.writeInt(encodedInstructionCount);
            for (int instructionIndex = 0; instructionIndex < block.instructions().size(); instructionIndex++) {
                final NativeInstruction instruction = block.instructions().get(instructionIndex);
                final VmProgram.VmSuperinstruction fused = fusedAt(block.instructions(), instructionIndex, pairs);
                if (fused != null) {
                    output.writeByte(opcodes.opcode(fused.name()));
                    writeOperationBody(output, (NativeInstruction.Operation) instruction, registers, mask);
                    writeOperationBody(output, (NativeInstruction.Operation) block.instructions().get(++instructionIndex),
                            registers, mask);
                    continue;
                }
                if (instruction instanceof NativeInstruction.Operation operation) {
                    output.writeByte(opcodes.opcode(operation.opcode().name()));
                    writeOperationBody(output, operation, registers, mask);
                } else if (instruction instanceof NativeInstruction.Phi phi) {
                    output.writeByte(opcodes.opcode("PHI"));
                    output.writeInt(encodedRegister(registers, phi.id(), mask));
                    writeType(output, phi.type());
                    output.writeShort(phi.incoming().size());
                    phi.incoming().stream().sorted(Comparator.comparing(NativeInstruction.Phi.Incoming::predecessorBlock))
                            .forEach(incoming -> {
                                writeIntUnchecked(output, encodedBlock(blocks, incoming.predecessorBlock(), mask));
                                writeOperandUnchecked(output, incoming.value(), registers, mask);
                            });
                }
            }
            output.writeShort(block.exceptionEdges().size());
            block.exceptionEdges().stream().sorted(Comparator.comparingInt(NativeExceptionEdge::priority))
                    .forEach(edge -> {
                        writeIntUnchecked(output, encodedBlock(blocks, edge.handlerBlock(), mask));
                        writeStringUnchecked(output, edge.catchType().orElse(""));
                        writeIntUnchecked(output, edge.priority());
                    });
            writeTerminator(output, block.terminator().orElseThrow(), registers, blocks, mask, opcodes);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode in-memory VM block", exception);
        }
    }

    private static void writeOperationBody(final DataOutputStream output,
                                           final NativeInstruction.Operation operation,
                                           final Map<String, Integer> registers, final int mask) throws IOException {
        output.writeInt(encodedRegister(registers, operation.id(), mask));
        writeType(output, operation.type());
        writeOperands(output, operation.operands(), registers, mask);
        output.writeShort(operation.attributes().size());
        operation.attributes().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            writeStringUnchecked(output, entry.getKey());
            writeStringUnchecked(output, entry.getValue());
        });
    }

    private static VmProgram.VmSuperinstruction fusedAt(
            final List<NativeInstruction> instructions,
            final int index,
            final Map<String, VmProgram.VmSuperinstruction> pairs
    ) {
        if (index + 1 >= instructions.size()
                || !(instructions.get(index) instanceof NativeInstruction.Operation first)
                || !(instructions.get(index + 1) instanceof NativeInstruction.Operation second)) return null;
        return pairs.get(pairKey(first.opcode().name(), second.opcode().name()));
    }

    private static String pairKey(final String first, final String second) {
        return first + '\0' + second;
    }

    private static List<VmProgram.VmSuperinstruction> selectSuperinstructions(
            final NativeModule module,
            final int requestedBudget
    ) {
        final Map<String, Integer> frequencies = new HashMap<>();
        for (final NativeFunction function : module.functions()) {
            if (function.backend() != NativeBackend.VM) continue;
            for (final NativeBlock block : function.blocks()) {
                for (int index = 0; index + 1 < block.instructions().size(); index++) {
                    if (block.instructions().get(index) instanceof NativeInstruction.Operation first
                            && block.instructions().get(index + 1) instanceof NativeInstruction.Operation second) {
                        frequencies.merge(pairKey(first.opcode().name(), second.opcode().name()), 1, Integer::sum);
                    }
                }
            }
        }
        final int baseOpcodeCount = NativeOpcode.values().length + 6;
        final int limit = Math.min(requestedBudget, 256 - baseOpcodeCount);
        return frequencies.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(entry -> {
                    final int separator = entry.getKey().indexOf('\0');
                    final String first = entry.getKey().substring(0, separator);
                    final String second = entry.getKey().substring(separator + 1);
                    return new VmProgram.VmSuperinstruction("SUPER$" + first + "$" + second, first, second);
                })
                .toList();
    }

    private static void writeTerminator(final DataOutputStream output, final NativeTerminator terminator,
                                        final Map<String, Integer> registers, final Map<String, Integer> blocks,
                                        final int mask, final VmOpcodeTable opcodes) throws IOException {
        if (terminator instanceof NativeTerminator.Return value) {
            output.writeByte(opcodes.opcode("RETURN"));
            output.writeBoolean(value.value().isPresent());
            if (value.value().isPresent()) writeOperand(output, value.value().get(), registers, mask);
        } else if (terminator instanceof NativeTerminator.Branch value) {
            output.writeByte(opcodes.opcode("BRANCH"));
            output.writeInt(encodedBlock(blocks, value.target(), mask));
        } else if (terminator instanceof NativeTerminator.ConditionalBranch value) {
            output.writeByte(opcodes.opcode("CBRANCH"));
            writeOperand(output, value.condition(), registers, mask);
            output.writeInt(encodedBlock(blocks, value.trueTarget(), mask));
            output.writeInt(encodedBlock(blocks, value.falseTarget(), mask));
        } else if (terminator instanceof NativeTerminator.Switch value) {
            output.writeByte(opcodes.opcode("SWITCH"));
            writeOperand(output, value.selector(), registers, mask);
            output.writeInt(value.cases().size());
            value.cases().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                writeLongUnchecked(output, entry.getKey());
                writeIntUnchecked(output, encodedBlock(blocks, entry.getValue(), mask));
            });
            output.writeInt(encodedBlock(blocks, value.defaultTarget(), mask));
        } else if (terminator instanceof NativeTerminator.Throw value) {
            output.writeByte(opcodes.opcode("THROW"));
            writeOperand(output, value.throwable(), registers, mask);
        } else {
            throw new IllegalArgumentException("Unsupported VM terminator " + terminator.getClass().getName());
        }
    }

    private static void writeOperands(final DataOutputStream output, final List<NativeOperand> operands,
                                      final Map<String, Integer> registers, final int mask) throws IOException {
        output.writeShort(operands.size());
        for (final NativeOperand operand : operands) writeOperand(output, operand, registers, mask);
    }

    private static void writeOperand(final DataOutputStream output, final NativeOperand operand,
                                     final Map<String, Integer> registers, final int mask) throws IOException {
        if (operand instanceof NativeOperand.Value value) {
            output.writeByte(0);
            output.writeInt(encodedRegister(registers, value.id(), mask));
            writeType(output, value.type());
            return;
        }
        final NativeOperand.Constant constant = (NativeOperand.Constant) operand;
        output.writeByte(1);
        writeType(output, constant.type());
        final Object value = constant.value();
        if (value == null) output.writeByte(0);
        else if (value instanceof Boolean item) { output.writeByte(1); output.writeBoolean(item); }
        else if (value instanceof Byte item) { output.writeByte(2); output.writeByte(item); }
        else if (value instanceof Short item) { output.writeByte(3); output.writeShort(item); }
        else if (value instanceof Integer item) { output.writeByte(4); output.writeInt(item); }
        else if (value instanceof Long item) { output.writeByte(5); output.writeLong(item); }
        else if (value instanceof Float item) { output.writeByte(6); output.writeInt(Float.floatToRawIntBits(item)); }
        else if (value instanceof Double item) { output.writeByte(7); output.writeLong(Double.doubleToRawLongBits(item)); }
        else if (value instanceof Character item) { output.writeByte(8); output.writeChar(item); }
        else if (value instanceof String item) { output.writeByte(9); writeString(output, item); }
        else throw new IllegalArgumentException("Unsupported VM constant " + value.getClass().getName());
    }

    private static void writeType(final DataOutputStream output, final NativeType type) throws IOException {
        writeString(output, type.displayName());
    }

    private static void writeString(final DataOutputStream output, final String value) throws IOException {
        final byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > 1_048_576) throw new IllegalArgumentException("VM string exceeds 1 MiB");
        output.writeInt(utf8.length);
        output.write(utf8);
    }

    private static int encodedRegister(final Map<String, Integer> registers, final String id, final int mask) {
        final Integer register = registers.get(id);
        if (register == null) throw new IllegalArgumentException("Unknown VM register " + id);
        return register ^ mask;
    }

    private static int encodedBlock(final Map<String, Integer> blocks, final String id, final int mask) {
        final Integer block = blocks.get(id);
        if (block == null) throw new IllegalArgumentException("Unknown VM block " + id);
        return block ^ Integer.rotateLeft(mask, 13);
    }

    private static byte[] aad(
            final String buildId,
            final VmOpcodeTable opcodes,
            final List<VmProgram.VmSuperinstruction> superinstructions,
            final VmProtectionSettings protection,
            final String symbol,
            final String owner,
            final String name,
            final String descriptor,
            final int registers,
            final int entryBlock,
            final int operandMask,
            final Map<String, String> metadata,
            final int blockCount,
            final int block
    ) {
        try {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final DataOutputStream output = new DataOutputStream(bytes);
            writeString(output, "skid-vm-binding-v1");
            writeString(output, buildId);
            writeString(output, protection.profile().name());
            writeString(output, protection.response().name());
            output.writeBoolean(protection.integrity());
            output.writeBoolean(protection.antiDebug());
            output.writeBoolean(protection.antiInstrumentation());
            output.writeBoolean(protection.timingChecks());
            output.writeBoolean(protection.diversifiedDispatch());
            output.writeInt(protection.handlerCloneCount());
            output.writeInt(protection.superinstructionBudget());
            output.writeInt(protection.decodedBlockCacheEntries());
            output.writeInt(protection.delayedHaltMinimumMillis());
            opcodes.values().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                writeStringUnchecked(output, entry.getKey());
                writeIntUnchecked(output, entry.getValue());
            });
            for (final VmProgram.VmSuperinstruction item : superinstructions) {
                writeString(output, item.name());
                writeString(output, item.firstOperation());
                writeString(output, item.secondOperation());
            }
            writeString(output, symbol);
            writeString(output, owner);
            writeString(output, name);
            writeString(output, descriptor);
            output.writeInt(registers);
            output.writeInt(entryBlock);
            output.writeInt(operandMask);
            metadata.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                writeStringUnchecked(output, entry.getKey());
                writeStringUnchecked(output, entry.getValue());
            });
            output.writeInt(blockCount);
            output.writeInt(block);
            output.flush();
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to construct VM block binding", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static List<byte[]> fragmentKey(final byte[] key, final int count, final SecureRandom random) {
        final List<byte[]> fragments = new ArrayList<>(count);
        final byte[] last = key.clone();
        for (int part = 1; part < count; part++) {
            final byte[] fragment = new byte[key.length];
            random.nextBytes(fragment);
            for (int index = 0; index < key.length; index++) last[index] ^= fragment[index];
            fragments.add(fragment);
        }
        fragments.add(last);
        return fragments;
    }

    private static void writeStringUnchecked(final DataOutputStream output, final String value) {
        try { writeString(output, value); } catch (IOException exception) { throw new IllegalStateException(exception); }
    }
    private static void writeIntUnchecked(final DataOutputStream output, final int value) {
        try { output.writeInt(value); } catch (IOException exception) { throw new IllegalStateException(exception); }
    }
    private static void writeLongUnchecked(final DataOutputStream output, final long value) {
        try { output.writeLong(value); } catch (IOException exception) { throw new IllegalStateException(exception); }
    }
    private static void writeOperandUnchecked(final DataOutputStream output, final NativeOperand operand,
                                              final Map<String, Integer> registers, final int mask) {
        try { writeOperand(output, operand, registers, mask); }
        catch (IOException exception) { throw new IllegalStateException(exception); }
    }
}
