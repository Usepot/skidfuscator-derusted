package dev.skidfuscator.nativetoolchain.vm;

import dev.skidfuscator.nativeir.NativeOpcode;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Per-build randomized opcode assignment, including VM control operations. */
public record VmOpcodeTable(Map<String, Integer> values) {
    private static final List<String> CONTROL = List.of("PHI", "RETURN", "BRANCH", "CBRANCH", "SWITCH", "THROW");

    public VmOpcodeTable {
        values = Map.copyOf(Objects.requireNonNull(values, "values"));
        if (values.size() > 256 || values.values().stream().distinct().count() != values.size()
                || values.values().stream().anyMatch(value -> value == null || value < 0 || value > 255)) {
            throw new IllegalArgumentException("VM opcodes must be unique unsigned bytes");
        }
        final List<String> required = new ArrayList<>();
        for (final NativeOpcode opcode : NativeOpcode.values()) required.add(opcode.name());
        required.addAll(CONTROL);
        if (!values.keySet().containsAll(required)) {
            required.removeAll(values.keySet());
            throw new IllegalArgumentException("VM opcode table is missing required operations " + required);
        }
    }

    public static VmOpcodeTable randomized(final SecureRandom random) {
        return randomized(random, List.of());
    }

    public static VmOpcodeTable randomized(final SecureRandom random, final List<String> generatedOperations) {
        Objects.requireNonNull(random, "random");
        Objects.requireNonNull(generatedOperations, "generatedOperations");
        final List<String> names = new ArrayList<>();
        for (final NativeOpcode opcode : NativeOpcode.values()) names.add(opcode.name());
        names.addAll(CONTROL);
        names.addAll(generatedOperations);
        if (names.size() > 256 || names.stream().distinct().count() != names.size()) {
            throw new IllegalArgumentException("VM operation names must be unique and fit in one byte");
        }
        final List<Integer> slots = new ArrayList<>(256);
        for (int value = 0; value < 256; value++) slots.add(value);
        Collections.shuffle(slots, random);
        final Map<String, Integer> result = new LinkedHashMap<>();
        for (int index = 0; index < names.size(); index++) result.put(names.get(index), slots.get(index));
        return new VmOpcodeTable(result);
    }

    public int opcode(final String operation) {
        final Integer value = values.get(operation);
        if (value == null) throw new IllegalArgumentException("No randomized opcode for " + operation);
        return value;
    }
}
