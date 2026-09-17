package dev.skidfuscator.nativeir;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A versioned native-IR compilation unit. */
public final class NativeModule {
    private final String name;
    private final int abiVersion;
    private final Map<String, String> metadata;
    private final List<NativeFunction> functions = new ArrayList<>();

    public NativeModule(final String name) {
        this(name, NativeIrVersions.CURRENT_ABI, Map.of());
    }

    public NativeModule(final String name, final int abiVersion, final Map<String, String> metadata) {
        this.name = Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name cannot be blank");
        }
        if (abiVersion <= 0) {
            throw new IllegalArgumentException("abiVersion must be positive");
        }
        this.abiVersion = abiVersion;
        this.metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
    }

    public String name() { return name; }
    public int abiVersion() { return abiVersion; }
    public Map<String, String> metadata() { return metadata; }
    public List<NativeFunction> functions() { return List.copyOf(functions); }

    public NativeModule addFunction(final NativeFunction function) {
        functions.add(Objects.requireNonNull(function, "function"));
        return this;
    }
}
