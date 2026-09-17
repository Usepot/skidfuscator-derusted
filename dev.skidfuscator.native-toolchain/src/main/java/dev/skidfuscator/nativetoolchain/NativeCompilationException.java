package dev.skidfuscator.nativetoolchain;

import java.io.IOException;

public final class NativeCompilationException extends IOException {
    public NativeCompilationException(final String message) {
        super(message);
    }
}
