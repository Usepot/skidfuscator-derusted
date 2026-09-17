package dev.skidfuscator.obfuscator.nativebackend;

import dev.skidfuscator.nativeir.NativeBlock;
import dev.skidfuscator.nativeir.NativeExceptionEdge;
import dev.skidfuscator.nativeir.NativeFunction;
import dev.skidfuscator.nativeir.NativeInstruction;
import dev.skidfuscator.nativeir.NativeOpcode;
import dev.skidfuscator.nativeir.NativeOperand;
import dev.skidfuscator.nativeir.NativeParameter;
import dev.skidfuscator.nativeir.NativeTerminator;
import dev.skidfuscator.nativeir.NativeType;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.Remapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/** Rewrites every JVM identity in native IR to the names used by the output ClassRemapper. */
final class NativeIrFinalNameRemapper {
    private final Remapper remapper;

    NativeIrFinalNameRemapper(final Remapper remapper) {
        this.remapper = Objects.requireNonNull(remapper, "remapper");
    }

    NativeFunction remap(final NativeFunction source) {
        final String oldOwner = source.javaOwner();
        final List<NativeParameter> parameters = source.parameters().stream()
                .map(value -> new NativeParameter(value.id(), type(value.type()), value.index()))
                .toList();
        final NativeFunction result = new NativeFunction(
                source.symbol(), remapper.mapType(oldOwner),
                remapper.mapMethodName(oldOwner, source.javaName(), source.javaDescriptor()),
                remapper.mapMethodDesc(source.javaDescriptor()), type(source.returnType()), parameters,
                source.entryBlock(), source.backend(), source.synchronizedMethod(),
                source.requiresSemanticContext(), source.metadata());
        source.blocks().stream().map(this::block).forEach(result::addBlock);
        return result;
    }

    private NativeBlock block(final NativeBlock source) {
        final NativeBlock result = new NativeBlock(source.id());
        source.instructions().stream().map(this::instruction).forEach(result::addInstruction);
        source.exceptionEdges().stream().map(edge -> new NativeExceptionEdge(
                edge.handlerBlock(), edge.catchType().map(remapper::mapType), edge.priority()))
                .forEach(result::addExceptionEdge);
        result.terminate(terminator(source.terminator().orElseThrow()));
        return result;
    }

    private NativeInstruction instruction(final NativeInstruction source) {
        if (source instanceof NativeInstruction.Phi phi) {
            return new NativeInstruction.Phi(phi.id(), type(phi.type()), phi.incoming().stream()
                    .map(incoming -> new NativeInstruction.Phi.Incoming(
                            incoming.predecessorBlock(), operand(incoming.value())))
                    .toList(), phi.sourceLocation());
        }
        final NativeInstruction.Operation operation = (NativeInstruction.Operation) source;
        return new NativeInstruction.Operation(operation.id(), type(operation.type()), operation.opcode(),
                operation.operands().stream().map(this::operand).toList(), attributes(operation),
                operation.sourceLocation());
    }

    private Map<String, String> attributes(final NativeInstruction.Operation operation) {
        final Map<String, String> values = new LinkedHashMap<>(operation.attributes());
        final String owner = values.get("owner");
        final String name = values.get("name");
        final String descriptor = values.get("descriptor");
        if (owner != null) {
            values.put("owner", remapper.mapType(owner));
        }
        if (descriptor != null) {
            values.put("descriptor", mapDescriptor(descriptor));
        }
        if (owner != null && name != null && descriptor != null) {
            if (operation.opcode() == NativeOpcode.FIELD_GET || operation.opcode() == NativeOpcode.FIELD_SET) {
                values.put("name", remapper.mapFieldName(owner, name, descriptor));
            } else if (operation.opcode() == NativeOpcode.JAVA_CALL) {
                values.put("name", remapper.mapMethodName(owner, name, descriptor));
            }
        }
        final String helperOwner = values.get("helper.owner");
        final String helperName = values.get("helper.name");
        final String helperDescriptor = values.get("helper.descriptor");
        if (helperOwner != null || helperName != null || helperDescriptor != null) {
            if (helperOwner == null || helperName == null || helperDescriptor == null) {
                throw new IllegalArgumentException("Dynamic linkage helper metadata must be complete");
            }
            values.put("helper.owner", remapper.mapType(helperOwner));
            values.put("helper.name", remapper.mapMethodName(helperOwner, helperName, helperDescriptor));
            values.put("helper.descriptor", remapper.mapMethodDesc(helperDescriptor));
            if (operation.opcode() == NativeOpcode.DYNAMIC_BRIDGE
                    && "java.helper.v1".equals(values.get("bridge"))) {
                values.put("descriptor", remapper.mapMethodDesc(helperDescriptor));
            }
        }
        final String checkedType = values.get("type");
        if (checkedType != null) {
            values.put("type", mapTypeAttribute(checkedType));
        }
        for (final Map.Entry<String, String> entry : new ArrayList<>(values.entrySet())) {
            if (entry.getKey().equals("bootstrap") || entry.getKey().startsWith("bootstrap.arg.")
                    || entry.getKey().equals("constant")) {
                values.put(entry.getKey(), linkageConstant(entry.getValue()));
            }
        }
        return values;
    }

    private NativeOperand operand(final NativeOperand source) {
        if (source instanceof NativeOperand.Value value) {
            return new NativeOperand.Value(value.id(), type(value.type()));
        }
        final NativeOperand.Constant constant = (NativeOperand.Constant) source;
        return new NativeOperand.Constant(type(constant.type()), constant.value());
    }

    private NativeTerminator terminator(final NativeTerminator source) {
        if (source instanceof NativeTerminator.Return value) {
            return new NativeTerminator.Return(value.value().map(this::operand), value.sourceLocation());
        }
        if (source instanceof NativeTerminator.Branch value) {
            return new NativeTerminator.Branch(value.target(), value.sourceLocation());
        }
        if (source instanceof NativeTerminator.ConditionalBranch value) {
            return new NativeTerminator.ConditionalBranch(operand(value.condition()), value.trueTarget(),
                    value.falseTarget(), value.sourceLocation());
        }
        if (source instanceof NativeTerminator.Switch value) {
            return new NativeTerminator.Switch(operand(value.selector()), value.cases(),
                    value.defaultTarget(), value.sourceLocation());
        }
        final NativeTerminator.Throw value = (NativeTerminator.Throw) source;
        return new NativeTerminator.Throw(operand(value.throwable()), value.sourceLocation());
    }

    private NativeType type(final NativeType source) {
        if (source instanceof NativeType.Reference reference) {
            return new NativeType.Reference(remapper.mapType(reference.internalName()), reference.nullable());
        }
        if (source instanceof NativeType.Array array) {
            return new NativeType.Array(type(array.componentType()), array.nullable());
        }
        return source;
    }

    private String mapTypeAttribute(final String value) {
        return value.startsWith("[") || value.startsWith("L")
                ? remapper.mapDesc(value) : remapper.mapType(value);
    }

    private String mapDescriptor(final String value) {
        return value.startsWith("(") ? remapper.mapMethodDesc(value) : remapper.mapDesc(value);
    }

    private String linkageConstant(final String encoded) {
        if (encoded.startsWith("t:")) {
            return "t:" + base64(remapper.mapDesc(unbase64(encoded.substring(2))));
        }
        if (encoded.startsWith("h:")) {
            final List<String> parts = unpack(encoded.substring(2));
            if (parts.size() != 5) return encoded;
            final int tag = Integer.parseInt(parts.get(0));
            final String owner = parts.get(2);
            final String name = parts.get(3);
            final String descriptor = parts.get(4);
            parts.set(2, remapper.mapType(owner));
            if (tag >= Opcodes.H_GETFIELD && tag <= Opcodes.H_PUTSTATIC) {
                parts.set(3, remapper.mapFieldName(owner, name, descriptor));
                parts.set(4, remapper.mapDesc(descriptor));
            } else {
                parts.set(3, remapper.mapMethodName(owner, name, descriptor));
                parts.set(4, remapper.mapMethodDesc(descriptor));
            }
            return "h:" + pack(parts);
        }
        if (encoded.startsWith("c:")) {
            final List<String> parts = unpack(encoded.substring(2));
            if (parts.size() < 4) return encoded;
            final String oldDescriptor = parts.get(1);
            parts.set(0, remapper.mapInvokeDynamicMethodName(parts.get(0), oldDescriptor));
            parts.set(1, remapper.mapDesc(oldDescriptor));
            parts.set(2, linkageConstant(parts.get(2)));
            final int argumentCount = Integer.parseInt(parts.get(3));
            if (parts.size() != 4 + argumentCount) return encoded;
            for (int index = 0; index < argumentCount; index++) {
                parts.set(4 + index, linkageConstant(parts.get(4 + index)));
            }
            return "c:" + pack(parts);
        }
        return encoded;
    }

    private static List<String> unpack(final String value) {
        return java.util.Arrays.stream(value.split("\\.", -1))
                .map(NativeIrFinalNameRemapper::unbase64)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static String pack(final List<String> values) {
        return values.stream().map(NativeIrFinalNameRemapper::base64).collect(Collectors.joining("."));
    }

    private static String base64(final String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String unbase64(final String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
