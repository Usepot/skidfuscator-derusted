package dev.skidfuscator.nativetoolchain.vm;

import dev.skidfuscator.nativetoolchain.VmProtectionSettings;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable encrypted register-VM program ready for embedding by SkidVirtualizePass. */
public record VmProgram(
        int formatVersion,
        String buildId,
        VmOpcodeTable opcodes,
        List<VmSuperinstruction> superinstructions,
        List<VmMethod> methods,
        List<byte[]> keyFragments,
        VmProtectionSettings protection
) {
    public static final int CURRENT_FORMAT = 1;

    public VmProgram {
        if (formatVersion != CURRENT_FORMAT) throw new IllegalArgumentException("Unsupported VM format");
        Objects.requireNonNull(buildId, "buildId");
        Objects.requireNonNull(opcodes, "opcodes");
        Objects.requireNonNull(protection, "protection");
        superinstructions = List.copyOf(Objects.requireNonNull(superinstructions, "superinstructions"));
        methods = List.copyOf(Objects.requireNonNull(methods, "methods"));
        keyFragments = Objects.requireNonNull(keyFragments, "keyFragments").stream()
                .map(value -> value.clone()).toList();
        if (methods.isEmpty()) throw new IllegalArgumentException("A VM program needs at least one method");
        if (keyFragments.size() < 2) throw new IllegalArgumentException("The VM key must be fragmented");
        if (keyFragments.size() != protection.handlerCloneCount()) {
            throw new IllegalArgumentException("One VM key fragment is required per handler clone");
        }
        if (keyFragments.stream().anyMatch(value -> value.length != XChaCha20Poly1305.KEY_BYTES)) {
            throw new IllegalArgumentException("Every VM key fragment must contain 32 bytes");
        }
        final java.util.Set<String> superNames = new java.util.HashSet<>();
        for (final VmSuperinstruction item : superinstructions) {
            if (!superNames.add(item.name()) || !opcodes.values().containsKey(item.name())
                    || !opcodes.values().containsKey(item.firstOperation())
                    || !opcodes.values().containsKey(item.secondOperation())) {
                throw new IllegalArgumentException("Invalid VM superinstruction definition " + item.name());
            }
        }
    }

    public VmProgram(
            final int formatVersion,
            final String buildId,
            final VmOpcodeTable opcodes,
            final List<VmMethod> methods,
            final List<byte[]> keyFragments
    ) {
        this(formatVersion, buildId, opcodes, List.of(), methods, keyFragments,
                VmProtectionSettings.aggressive());
    }

    public VmProgram(
            final int formatVersion,
            final String buildId,
            final VmOpcodeTable opcodes,
            final List<VmMethod> methods,
            final List<byte[]> keyFragments,
            final VmProtectionSettings protection
    ) {
        this(formatVersion, buildId, opcodes, List.of(), methods, keyFragments, protection);
    }

    @Override public List<byte[]> keyFragments() { return keyFragments.stream().map(byte[]::clone).toList(); }

    public record VmSuperinstruction(String name, String firstOperation, String secondOperation) {
        public VmSuperinstruction {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(firstOperation, "firstOperation");
            Objects.requireNonNull(secondOperation, "secondOperation");
            if (!name.matches("SUPER\\$[A-Z0-9_$]+") || firstOperation.isBlank() || secondOperation.isBlank()) {
                throw new IllegalArgumentException("Invalid VM superinstruction");
            }
        }
    }

    public record VmMethod(
            String symbol,
            String javaOwner,
            String javaName,
            String javaDescriptor,
            int registerCount,
            int entryBlock,
            int operandMask,
            Map<String, String> metadata,
            List<EncryptedBlock> blocks
    ) {
        public VmMethod {
            Objects.requireNonNull(symbol, "symbol");
            Objects.requireNonNull(javaOwner, "javaOwner");
            Objects.requireNonNull(javaName, "javaName");
            Objects.requireNonNull(javaDescriptor, "javaDescriptor");
            metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
            blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
            if (registerCount < 0 || entryBlock < 0 || entryBlock >= blocks.size()) {
                throw new IllegalArgumentException("Invalid VM register/block layout");
            }
            final boolean[] seen = new boolean[blocks.size()];
            for (final EncryptedBlock block : blocks) {
                if (block.index() >= seen.length || seen[block.index()]) {
                    throw new IllegalArgumentException("VM block indexes must be unique and contiguous");
                }
                seen[block.index()] = true;
            }
        }
    }

    public record EncryptedBlock(int index, byte[] nonce, byte[] ciphertext) {
        public EncryptedBlock {
            if (index < 0) throw new IllegalArgumentException("Block index cannot be negative");
            nonce = Objects.requireNonNull(nonce, "nonce").clone();
            ciphertext = Objects.requireNonNull(ciphertext, "ciphertext").clone();
            if (nonce.length != XChaCha20Poly1305.NONCE_BYTES || ciphertext.length < XChaCha20Poly1305.TAG_BYTES) {
                throw new IllegalArgumentException("Invalid encrypted VM block");
            }
        }

        @Override public byte[] nonce() { return nonce.clone(); }
        @Override public byte[] ciphertext() { return ciphertext.clone(); }
    }
}
