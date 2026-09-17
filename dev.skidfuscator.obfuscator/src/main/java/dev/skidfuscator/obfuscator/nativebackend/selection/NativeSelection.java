package dev.skidfuscator.obfuscator.nativebackend.selection;

import dev.skidfuscator.config.nativeobfuscation.NativeMode;
import org.mapleir.asm.MethodNode;

import java.util.Objects;

/** A resolved native-protection request for one application method. */
public final class NativeSelection {
    private final MethodNode method;
    private final NativeMode mode;
    private final NativeSelectionSource source;
    private final boolean strict;

    public NativeSelection(final MethodNode method,
                           final NativeMode mode,
                           final NativeSelectionSource source,
                           final boolean strict) {
        this.method = Objects.requireNonNull(method, "method");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.source = Objects.requireNonNull(source, "source");
        this.strict = strict;
        if (mode == NativeMode.DEFAULT) {
            throw new IllegalArgumentException("Native selection mode must be resolved");
        }
    }

    public MethodNode getMethod() {
        return method;
    }

    public NativeMode getMode() {
        return mode;
    }

    public NativeSelectionSource getSource() {
        return source;
    }

    public boolean isStrict() {
        return strict;
    }

    @Override
    public String toString() {
        return method + " -> " + mode + " (" + source + (strict ? ", strict" : "") + ")";
    }
}
