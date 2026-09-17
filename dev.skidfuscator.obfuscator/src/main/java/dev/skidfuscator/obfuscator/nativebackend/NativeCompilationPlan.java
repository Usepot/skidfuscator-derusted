package dev.skidfuscator.obfuscator.nativebackend;

import dev.skidfuscator.obfuscator.nativebackend.selection.NativeSelection;

import java.util.List;
import java.util.Objects;

/** Immutable result of native selection and structural eligibility checks. */
public record NativeCompilationPlan(
        List<Candidate> candidates,
        List<Skipped> skipped
) {
    public NativeCompilationPlan {
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        skipped = List.copyOf(Objects.requireNonNull(skipped, "skipped"));
    }

    public record Candidate(NativeSelection selection, NativeEligibility.Conversion conversion) {
        public Candidate {
            Objects.requireNonNull(selection, "selection");
            Objects.requireNonNull(conversion, "conversion");
            if (conversion == NativeEligibility.Conversion.UNSUPPORTED) {
                throw new IllegalArgumentException("A candidate cannot be unsupported");
            }
        }
    }

    public record Skipped(NativeSelection selection, String reason) {
        public Skipped {
            Objects.requireNonNull(selection, "selection");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
