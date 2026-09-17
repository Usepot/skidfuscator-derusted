package dev.skidfuscator.nativeir;

import java.util.Objects;

/** An instruction operand, either an SSA use or an inline constant. */
public sealed interface NativeOperand permits NativeOperand.Value, NativeOperand.Constant {
    NativeType type();

    record Value(String id, NativeType type) implements NativeOperand {
        public Value {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            if (id.isBlank()) {
                throw new IllegalArgumentException("id cannot be blank");
            }
        }
    }

    /** value may be null only for nullable reference-like constants. */
    record Constant(NativeType type, Object value) implements NativeOperand {
        public Constant {
            Objects.requireNonNull(type, "type");
        }
    }
}
