package dev.skidfuscator.nativeir;

import java.util.Objects;

/** Optional Java source/bytecode location retained for diagnostics. */
public record SourceLocation(String source, int line, int bytecodeOffset) {
    public static final SourceLocation UNKNOWN = new SourceLocation("<unknown>", -1, -1);

    public SourceLocation {
        Objects.requireNonNull(source, "source");
        if (line < -1 || bytecodeOffset < -1) {
            throw new IllegalArgumentException("line and bytecodeOffset must be -1 or non-negative");
        }
    }
}
