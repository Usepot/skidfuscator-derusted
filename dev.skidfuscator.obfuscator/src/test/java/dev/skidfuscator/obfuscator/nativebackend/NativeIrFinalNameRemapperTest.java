package dev.skidfuscator.obfuscator.nativebackend;

import dev.skidfuscator.nativeir.NativeBackend;
import dev.skidfuscator.nativeir.NativeBlock;
import dev.skidfuscator.nativeir.NativeFunction;
import dev.skidfuscator.nativeir.NativeInstruction;
import dev.skidfuscator.nativeir.NativeOpcode;
import dev.skidfuscator.nativeir.NativeOperand;
import dev.skidfuscator.nativeir.NativeParameter;
import dev.skidfuscator.nativeir.NativeTerminator;
import dev.skidfuscator.nativeir.NativeType;
import dev.skidfuscator.nativeir.SourceLocation;
import dev.skidfuscator.obfuscator.renamer.SkidRemapper;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NativeIrFinalNameRemapperTest {
    @Test
    void remapsRegistrationTypesAndSemanticMemberIdentitiesTogether() {
        final Map<String, String> mappings = new HashMap<>();
        mappings.put("demo/Owner", "a/A");
        mappings.put("demo/Value", "a/B");
        mappings.put("demo/Owner.run(Ldemo/Value;)Ldemo/Value;", "x");
        mappings.put("demo/Owner.value", "y");
        final NativeType.Reference ownerType = new NativeType.Reference("demo/Owner", false);
        final NativeType.Reference valueType = new NativeType.Reference("demo/Value", true);
        final NativeBlock entry = new NativeBlock("entry").addInstruction(new NativeInstruction.Operation(
                "loaded", valueType, NativeOpcode.FIELD_GET,
                List.of(new NativeOperand.Value("arg0", ownerType)),
                Map.of("owner", "demo/Owner", "name", "value",
                        "descriptor", "Ldemo/Value;", "static", "false"),
                SourceLocation.UNKNOWN)).terminate(new NativeTerminator.Return(
                java.util.Optional.of(new NativeOperand.Value("loaded", valueType)), SourceLocation.UNKNOWN));
        final NativeFunction source = new NativeFunction(
                "native_symbol", "demo/Owner", "run", "(Ldemo/Value;)Ldemo/Value;", valueType,
                List.of(new NativeParameter("arg0", ownerType, 0),
                        new NativeParameter("arg1", valueType, 1)),
                "entry", NativeBackend.AOT, false, true, Map.of()).addBlock(entry);

        final NativeFunction result = new NativeIrFinalNameRemapper(new SkidRemapper(mappings)).remap(source);

        assertEquals("a/A", result.javaOwner());
        assertEquals("x", result.javaName());
        assertEquals("(La/B;)La/B;", result.javaDescriptor());
        assertEquals(new NativeType.Reference("a/B", true), result.returnType());
        final NativeInstruction.Operation operation = (NativeInstruction.Operation)
                result.blocks().get(0).instructions().get(0);
        assertEquals("a/A", operation.attributes().get("owner"));
        assertEquals("y", operation.attributes().get("name"));
        assertEquals("La/B;", operation.attributes().get("descriptor"));
    }

    @Test
    void remapsDynamicBootstrapHandlesTypesAndConstantDynamicArguments() {
        final Map<String, String> mappings = new HashMap<>();
        mappings.put("demo/Owner", "a/A");
        mappings.put("demo/Value", "a/B");
        mappings.put("demo/Owner.bootstrap(Ldemo/Value;)Ldemo/Value;", "b");
        final String handle = "h:" + pack(List.of(
                Integer.toString(Opcodes.H_INVOKESTATIC), "false", "demo/Owner", "bootstrap",
                "(Ldemo/Value;)Ldemo/Value;"));
        final String type = "t:" + base64("Ldemo/Value;");
        final String dynamic = "c:" + pack(List.of("constant", "Ldemo/Value;", handle, "1", type));
        final NativeBlock entry = new NativeBlock("entry").addInstruction(new NativeInstruction.Operation(
                "dynamic", new NativeType.Reference("demo/Value", true), NativeOpcode.DYNAMIC_BRIDGE,
                List.of(), Map.of("bootstrap", handle, "bootstrap.arg.0", type, "constant", dynamic),
                SourceLocation.UNKNOWN)).terminate(new NativeTerminator.Return(
                java.util.Optional.of(new NativeOperand.Value(
                        "dynamic", new NativeType.Reference("demo/Value", true))), SourceLocation.UNKNOWN));
        final NativeFunction source = new NativeFunction(
                "native_symbol", "demo/Owner", "run", "()Ldemo/Value;",
                new NativeType.Reference("demo/Value", true), List.of(), "entry", NativeBackend.AOT,
                false, true, Map.of()).addBlock(entry);

        final NativeFunction result = new NativeIrFinalNameRemapper(new SkidRemapper(mappings)).remap(source);
        final NativeInstruction.Operation operation = (NativeInstruction.Operation)
                result.blocks().get(0).instructions().get(0);

        assertEquals(List.of(Integer.toString(Opcodes.H_INVOKESTATIC), "false", "a/A", "b",
                "(La/B;)La/B;"), unpack(operation.attributes().get("bootstrap").substring(2)));
        assertEquals("La/B;", unbase64(operation.attributes().get("bootstrap.arg.0").substring(2)));
        final List<String> constant = unpack(operation.attributes().get("constant").substring(2));
        assertEquals("La/B;", constant.get(1));
        assertEquals("La/B;", unbase64(constant.get(4).substring(2)));
    }

    @Test
    void remapsJavaOwnedDynamicHelperIdentityAndDescriptor() {
        final Map<String, String> mappings = new HashMap<>();
        mappings.put("demo/Owner", "a/A");
        mappings.put("demo/Value", "a/B");
        mappings.put("demo/Owner.skid$link$0(Ldemo/Value;)Ldemo/Value;", "h");
        final NativeType.Reference value = new NativeType.Reference("demo/Value", true);
        final NativeFunction source = new NativeFunction(
                "native_symbol", "demo/Owner", "run", "(Ldemo/Value;)Ldemo/Value;", value,
                List.of(new NativeParameter("arg", value, 0)), "entry", NativeBackend.AOT,
                false, true, Map.of()).addBlock(new NativeBlock("entry")
                .addInstruction(new NativeInstruction.Operation(
                        "linked", value, NativeOpcode.DYNAMIC_BRIDGE,
                        List.of(new NativeOperand.Value("arg", value)),
                        Map.of(
                                "bridge", "java.helper.v1",
                                "descriptor", "(Ldemo/Value;)Ldemo/Value;",
                                "helper.owner", "demo/Owner",
                                "helper.name", "skid$link$0",
                                "helper.descriptor", "(Ldemo/Value;)Ldemo/Value;",
                                "helper.invoke", "static",
                                "helper.kind", "invokedynamic"),
                        SourceLocation.UNKNOWN))
                .terminate(new NativeTerminator.Return(
                        java.util.Optional.of(new NativeOperand.Value("linked", value)),
                        SourceLocation.UNKNOWN)));

        final NativeInstruction.Operation operation = (NativeInstruction.Operation)
                new NativeIrFinalNameRemapper(new SkidRemapper(mappings)).remap(source)
                        .blocks().get(0).instructions().get(0);

        assertEquals("a/A", operation.attributes().get("helper.owner"));
        assertEquals("h", operation.attributes().get("helper.name"));
        assertEquals("(La/B;)La/B;", operation.attributes().get("helper.descriptor"));
        assertEquals("(La/B;)La/B;", operation.attributes().get("descriptor"));
    }

    private static String pack(final List<String> values) {
        return values.stream().map(NativeIrFinalNameRemapperTest::base64).collect(Collectors.joining("."));
    }

    private static List<String> unpack(final String value) {
        return java.util.Arrays.stream(value.split("\\.", -1)).map(NativeIrFinalNameRemapperTest::unbase64)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static String base64(final String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String unbase64(final String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
