package dev.skidfuscator.nativetoolchain;

/** Raised when verified native IR cannot be represented by the canonical LLVM emitter. */
public final class LlvmEmissionException extends IllegalArgumentException {
    public LlvmEmissionException(final String message) {
        super(message);
    }

    public LlvmEmissionException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
