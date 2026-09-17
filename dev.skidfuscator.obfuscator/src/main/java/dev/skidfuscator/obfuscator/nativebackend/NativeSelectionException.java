package dev.skidfuscator.obfuscator.nativebackend;

/** A strict native selection could not be honored. */
public final class NativeSelectionException extends IllegalStateException {
    public NativeSelectionException(final String message) {
        super(message);
    }
}
