package dev.skidfuscator.nativeir;

import java.util.Objects;

/** An SSA value supplied by a native function's Java/JNI trampoline. */
public record NativeParameter(String id, NativeType type, int index) implements NativeValue {
    public NativeParameter {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id cannot be blank");
        }
        if (index < 0) {
            throw new IllegalArgumentException("index cannot be negative");
        }
    }
}
