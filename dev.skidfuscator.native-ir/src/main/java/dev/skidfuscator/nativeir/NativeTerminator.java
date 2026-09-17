package dev.skidfuscator.nativeir;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** An explicit control-flow terminator; every block must have exactly one. */
public sealed interface NativeTerminator permits NativeTerminator.Return, NativeTerminator.Branch,
        NativeTerminator.ConditionalBranch, NativeTerminator.Switch, NativeTerminator.Throw {
    SourceLocation sourceLocation();

    default List<String> successors() {
        return List.of();
    }

    record Return(Optional<NativeOperand> value, SourceLocation sourceLocation) implements NativeTerminator {
        public Return {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(sourceLocation, "sourceLocation");
        }

        public Return(final NativeOperand value) {
            this(Optional.of(value), SourceLocation.UNKNOWN);
        }

        public static Return voidReturn() {
            return new Return(Optional.empty(), SourceLocation.UNKNOWN);
        }
    }

    record Branch(String target, SourceLocation sourceLocation) implements NativeTerminator {
        public Branch {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(sourceLocation, "sourceLocation");
        }

        public Branch(final String target) {
            this(target, SourceLocation.UNKNOWN);
        }

        @Override
        public List<String> successors() {
            return List.of(target);
        }
    }

    record ConditionalBranch(
            NativeOperand condition,
            String trueTarget,
            String falseTarget,
            SourceLocation sourceLocation
    ) implements NativeTerminator {
        public ConditionalBranch {
            Objects.requireNonNull(condition, "condition");
            Objects.requireNonNull(trueTarget, "trueTarget");
            Objects.requireNonNull(falseTarget, "falseTarget");
            Objects.requireNonNull(sourceLocation, "sourceLocation");
        }

        public ConditionalBranch(final NativeOperand condition, final String trueTarget, final String falseTarget) {
            this(condition, trueTarget, falseTarget, SourceLocation.UNKNOWN);
        }

        @Override
        public List<String> successors() {
            return List.of(trueTarget, falseTarget);
        }
    }

    record Switch(
            NativeOperand selector,
            Map<Long, String> cases,
            String defaultTarget,
            SourceLocation sourceLocation
    ) implements NativeTerminator {
        public Switch {
            Objects.requireNonNull(selector, "selector");
            Objects.requireNonNull(cases, "cases");
            Objects.requireNonNull(defaultTarget, "defaultTarget");
            Objects.requireNonNull(sourceLocation, "sourceLocation");
            cases = Map.copyOf(cases);
        }

        @Override
        public List<String> successors() {
            return java.util.stream.Stream.concat(cases.values().stream(), java.util.stream.Stream.of(defaultTarget))
                    .distinct()
                    .toList();
        }
    }

    record Throw(NativeOperand throwable, SourceLocation sourceLocation) implements NativeTerminator {
        public Throw {
            Objects.requireNonNull(throwable, "throwable");
            Objects.requireNonNull(sourceLocation, "sourceLocation");
        }

        public Throw(final NativeOperand throwable) {
            this(throwable, SourceLocation.UNKNOWN);
        }
    }
}
