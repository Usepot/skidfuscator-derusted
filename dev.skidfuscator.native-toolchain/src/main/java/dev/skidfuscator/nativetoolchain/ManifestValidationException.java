package dev.skidfuscator.nativetoolchain;

import java.util.List;

/** Indicates that a compiler or SkidLLVM manifest violates its contract. */
public final class ManifestValidationException extends IllegalArgumentException {
    private final List<String> violations;

    public ManifestValidationException(final List<String> violations) {
        super("Manifest validation failed:\n - " + String.join("\n - ", violations));
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
        return violations;
    }
}
