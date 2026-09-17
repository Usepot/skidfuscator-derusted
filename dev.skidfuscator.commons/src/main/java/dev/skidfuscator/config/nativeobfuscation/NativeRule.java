package dev.skidfuscator.config.nativeobfuscation;

import java.util.Objects;

/**
 * An ordered native method-selection rule. When several rules match, the last
 * matching rule wins.
 */
public final class NativeRule {
    private final String match;
    private final NativeMode mode;

    public NativeRule(final String match, final NativeMode mode) {
        this.match = Objects.requireNonNull(match, "match");
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    public String getMatch() {
        return match;
    }

    public NativeMode getMode() {
        return mode;
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof NativeRule)) {
            return false;
        }
        final NativeRule that = (NativeRule) other;
        return match.equals(that.match) && mode == that.mode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(match, mode);
    }

    @Override
    public String toString() {
        return "NativeRule{" + "match='" + match + '\'' + ", mode=" + mode + '}';
    }
}
