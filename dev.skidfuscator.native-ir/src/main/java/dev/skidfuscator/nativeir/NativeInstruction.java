package dev.skidfuscator.nativeir;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A result-producing or effect-only SSA instruction. Effect-only instructions use {@code void}. */
public sealed interface NativeInstruction extends NativeValue
        permits NativeInstruction.Operation, NativeInstruction.Phi {
    List<NativeOperand> operands();

    SourceLocation sourceLocation();

    record Operation(
            String id,
            NativeType type,
            NativeOpcode opcode,
            List<NativeOperand> operands,
            Map<String, String> attributes,
            SourceLocation sourceLocation
    ) implements NativeInstruction {
        public Operation {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(opcode, "opcode");
            Objects.requireNonNull(operands, "operands");
            Objects.requireNonNull(attributes, "attributes");
            Objects.requireNonNull(sourceLocation, "sourceLocation");
            if (id.isBlank()) {
                throw new IllegalArgumentException("id cannot be blank");
            }
            operands = List.copyOf(operands);
            attributes = Map.copyOf(attributes);
        }

        public Operation(
                final String id,
                final NativeType type,
                final NativeOpcode opcode,
                final List<NativeOperand> operands
        ) {
            this(id, type, opcode, operands, Map.of(), SourceLocation.UNKNOWN);
        }
    }

    record Phi(
            String id,
            NativeType type,
            List<Incoming> incoming,
            SourceLocation sourceLocation
    ) implements NativeInstruction {
        public Phi {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(incoming, "incoming");
            Objects.requireNonNull(sourceLocation, "sourceLocation");
            if (id.isBlank()) {
                throw new IllegalArgumentException("id cannot be blank");
            }
            incoming = List.copyOf(incoming);
        }

        @Override
        public List<NativeOperand> operands() {
            return incoming.stream().map(Incoming::value).toList();
        }

        public record Incoming(String predecessorBlock, NativeOperand value) {
            public Incoming {
                Objects.requireNonNull(predecessorBlock, "predecessorBlock");
                Objects.requireNonNull(value, "value");
                if (predecessorBlock.isBlank()) {
                    throw new IllegalArgumentException("predecessorBlock cannot be blank");
                }
            }
        }
    }
}
