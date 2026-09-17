package dev.skidfuscator.nativeir;

/** Raised when native IR cannot safely be consumed by a backend. */
public final class NativeIrVerificationException extends IllegalArgumentException {
    private final NativeIrVerifier.Result result;

    public NativeIrVerificationException(final NativeIrVerifier.Result result) {
        super(result.format());
        this.result = result;
    }

    public NativeIrVerifier.Result result() {
        return result;
    }
}
