package dev.skidfuscator.nativeir;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A Java-derived function in typed, target-neutral native SSA form. */
public final class NativeFunction {
    private final String symbol;
    private final String javaOwner;
    private final String javaName;
    private final String javaDescriptor;
    private final NativeType returnType;
    private final List<NativeParameter> parameters;
    private final String entryBlock;
    private final NativeBackend backend;
    private final boolean synchronizedMethod;
    private final boolean requiresSemanticContext;
    private final Map<String, String> metadata;
    private final List<NativeBlock> blocks = new ArrayList<>();

    public NativeFunction(
            final String symbol,
            final String javaOwner,
            final String javaName,
            final String javaDescriptor,
            final NativeType returnType,
            final List<NativeParameter> parameters,
            final String entryBlock,
            final NativeBackend backend,
            final boolean synchronizedMethod,
            final boolean requiresSemanticContext,
            final Map<String, String> metadata
    ) {
        this.symbol = requireText(symbol, "symbol");
        this.javaOwner = requireText(javaOwner, "javaOwner");
        this.javaName = requireText(javaName, "javaName");
        this.javaDescriptor = requireText(javaDescriptor, "javaDescriptor");
        this.returnType = Objects.requireNonNull(returnType, "returnType");
        this.parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
        this.entryBlock = requireText(entryBlock, "entryBlock");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.synchronizedMethod = synchronizedMethod;
        this.requiresSemanticContext = requiresSemanticContext;
        this.metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
    }

    public NativeFunction(
            final String symbol,
            final String javaOwner,
            final String javaName,
            final String javaDescriptor,
            final NativeType returnType,
            final List<NativeParameter> parameters,
            final String entryBlock,
            final NativeBackend backend,
            final boolean synchronizedMethod,
            final Map<String, String> metadata
    ) {
        this(symbol, javaOwner, javaName, javaDescriptor, returnType, parameters, entryBlock,
                backend, synchronizedMethod, false, metadata);
    }

    public NativeFunction(
            final String symbol,
            final String javaOwner,
            final String javaName,
            final String javaDescriptor,
            final NativeType returnType,
            final List<NativeParameter> parameters,
            final String entryBlock,
            final boolean synchronizedMethod,
            final Map<String, String> metadata
    ) {
        this(symbol, javaOwner, javaName, javaDescriptor, returnType, parameters, entryBlock,
                NativeBackend.AOT, synchronizedMethod, false, metadata);
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }

    public String symbol() { return symbol; }
    public String javaOwner() { return javaOwner; }
    public String javaName() { return javaName; }
    public String javaDescriptor() { return javaDescriptor; }
    public NativeType returnType() { return returnType; }
    public List<NativeParameter> parameters() { return parameters; }
    public String entryBlock() { return entryBlock; }
    public NativeBackend backend() { return backend; }
    public boolean synchronizedMethod() { return synchronizedMethod; }
    public boolean requiresSemanticContext() { return requiresSemanticContext; }
    public Map<String, String> metadata() { return metadata; }
    public List<NativeBlock> blocks() { return List.copyOf(blocks); }

    public NativeFunction addBlock(final NativeBlock block) {
        blocks.add(Objects.requireNonNull(block, "block"));
        return this;
    }
}
