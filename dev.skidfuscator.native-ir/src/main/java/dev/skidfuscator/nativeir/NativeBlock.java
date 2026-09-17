package dev.skidfuscator.nativeir;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A mutable construction-time basic block with immutable public views. */
public final class NativeBlock {
    private final String id;
    private final List<NativeInstruction> instructions = new ArrayList<>();
    private final List<NativeExceptionEdge> exceptionEdges = new ArrayList<>();
    private NativeTerminator terminator;

    public NativeBlock(final String id) {
        this.id = Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id cannot be blank");
        }
    }

    public String id() {
        return id;
    }

    public List<NativeInstruction> instructions() {
        return List.copyOf(instructions);
    }

    public List<NativeExceptionEdge> exceptionEdges() {
        return List.copyOf(exceptionEdges);
    }

    public Optional<NativeTerminator> terminator() {
        return Optional.ofNullable(terminator);
    }

    public NativeBlock addInstruction(final NativeInstruction instruction) {
        if (terminator != null) {
            throw new IllegalStateException("Cannot add an instruction after the block terminator");
        }
        instructions.add(Objects.requireNonNull(instruction, "instruction"));
        return this;
    }

    public NativeBlock addExceptionEdge(final NativeExceptionEdge edge) {
        exceptionEdges.add(Objects.requireNonNull(edge, "edge"));
        return this;
    }

    public NativeBlock terminate(final NativeTerminator terminator) {
        if (this.terminator != null) {
            throw new IllegalStateException("Block already has a terminator");
        }
        this.terminator = Objects.requireNonNull(terminator, "terminator");
        return this;
    }
}
