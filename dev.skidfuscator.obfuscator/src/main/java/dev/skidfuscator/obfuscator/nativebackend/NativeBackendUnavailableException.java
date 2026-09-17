package dev.skidfuscator.obfuscator.nativebackend;

/** Native mode was requested but no complete lowering backend is installed. */
public final class NativeBackendUnavailableException extends IllegalStateException {
    public NativeBackendUnavailableException(final String message) {
        super(message);
    }

    public NativeBackendUnavailableException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
