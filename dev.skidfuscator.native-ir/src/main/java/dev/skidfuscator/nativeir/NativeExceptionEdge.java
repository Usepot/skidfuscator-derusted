package dev.skidfuscator.nativeir;

import java.util.Objects;
import java.util.Optional;

/** A block-level Java exception edge, ordered using {@code priority}. */
public record NativeExceptionEdge(String handlerBlock, Optional<String> catchType, int priority) {
    public NativeExceptionEdge {
        Objects.requireNonNull(handlerBlock, "handlerBlock");
        Objects.requireNonNull(catchType, "catchType");
        if (handlerBlock.isBlank()) {
            throw new IllegalArgumentException("handlerBlock cannot be blank");
        }
        if (priority < 0) {
            throw new IllegalArgumentException("priority cannot be negative");
        }
        catchType.ifPresent(type -> {
            if (type.isBlank() || type.indexOf('.') >= 0 || type.startsWith("[")) {
                throw new IllegalArgumentException("Invalid catch type internal name: " + type);
            }
        });
    }
}
