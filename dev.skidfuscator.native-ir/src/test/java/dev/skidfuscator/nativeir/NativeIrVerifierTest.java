package dev.skidfuscator.nativeir;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeIrVerifierTest {
    private static final NativeType.Primitive I32 = NativeType.Primitive.I32;

    @Test
    void acceptsTypedSsaFunction() {
        final NativeParameter left = new NativeParameter("left", I32, 0);
        final NativeParameter right = new NativeParameter("right", I32, 1);
        final NativeInstruction.Operation sum = new NativeInstruction.Operation(
                "sum",
                I32,
                NativeOpcode.ADD,
                List.of(new NativeOperand.Value("left", I32), new NativeOperand.Value("right", I32))
        );
        final NativeBlock entry = new NativeBlock("entry")
                .addInstruction(sum)
                .terminate(new NativeTerminator.Return(new NativeOperand.Value("sum", I32)));
        final NativeFunction function = function("add", I32, List.of(left, right), "entry").addBlock(entry);
        final NativeModule module = new NativeModule("math").addFunction(function);

        assertDoesNotThrow(() -> new NativeIrVerifier().verifyOrThrow(module));
    }

    @Test
    void rejectsUndefinedAndMistypedUsesBeforeBackendConsumption() {
        final NativeInstruction.Operation sum = new NativeInstruction.Operation(
                "sum",
                I32,
                NativeOpcode.ADD,
                List.of(
                        new NativeOperand.Value("missing", I32),
                        new NativeOperand.Constant(NativeType.Primitive.I64, 3L)
                )
        );
        final NativeBlock entry = new NativeBlock("entry")
                .addInstruction(sum)
                .terminate(new NativeTerminator.Return(new NativeOperand.Value("sum", I32)));
        final NativeModule module = new NativeModule("broken")
                .addFunction(function("broken", I32, List.of(), "entry").addBlock(entry));

        final NativeIrVerifier.Result result = new NativeIrVerifier().verify(module);

        assertFalse(result.valid());
        assertTrue(result.format().contains("undefined SSA value missing"));
        assertTrue(result.format().contains("matching integer operands"));
        assertThrows(NativeIrVerificationException.class,
                () -> new NativeIrVerifier().verifyOrThrow(module));
    }

    @Test
    void verifiesPhiPredecessorCoverage() {
        final NativeOperand.Constant one = new NativeOperand.Constant(I32, 1);
        final NativeBlock entry = new NativeBlock("entry").terminate(new NativeTerminator.ConditionalBranch(
                new NativeOperand.Constant(NativeType.Primitive.I1, true), "left", "right"));
        final NativeBlock left = new NativeBlock("left").terminate(new NativeTerminator.Branch("join"));
        final NativeBlock right = new NativeBlock("right").terminate(new NativeTerminator.Branch("join"));
        final NativeInstruction.Phi incomplete = new NativeInstruction.Phi(
                "result",
                I32,
                List.of(new NativeInstruction.Phi.Incoming("left", one)),
                SourceLocation.UNKNOWN
        );
        final NativeBlock join = new NativeBlock("join")
                .addInstruction(incomplete)
                .terminate(new NativeTerminator.Return(new NativeOperand.Value("result", I32)));
        final NativeModule module = new NativeModule("phi")
                .addFunction(function("phi", I32, List.of(), "entry")
                        .addBlock(entry).addBlock(left).addBlock(right).addBlock(join));

        final NativeIrVerifier.Result result = new NativeIrVerifier().verify(module);

        assertFalse(result.valid());
        assertTrue(result.format().contains("one incoming value for every predecessor"));
    }

    @Test
    void rejectsUnsupportedAbi() {
        final NativeModule module = new NativeModule("future", NativeIrVersions.CURRENT_ABI + 1, Map.of());
        final NativeIrVerifier.Result result = new NativeIrVerifier().verify(module);
        assertFalse(result.valid());
        assertTrue(result.format().contains("Unsupported native IR ABI"));
    }

    @Test
    void rejectsValueThatDoesNotDominateItsUse() {
        final NativeInstruction.Operation use = new NativeInstruction.Operation(
                "use", I32, NativeOpcode.ADD,
                List.of(new NativeOperand.Value("later", I32), new NativeOperand.Constant(I32, 1)));
        final NativeInstruction.Operation later = new NativeInstruction.Operation(
                "later", I32, NativeOpcode.ADD,
                List.of(new NativeOperand.Constant(I32, 2), new NativeOperand.Constant(I32, 3)));
        final NativeBlock entry = new NativeBlock("entry")
                .addInstruction(use)
                .addInstruction(later)
                .terminate(new NativeTerminator.Return(new NativeOperand.Value("use", I32)));
        final NativeModule module = new NativeModule("dominance")
                .addFunction(function("dominance", I32, List.of(), "entry").addBlock(entry));

        final NativeIrVerifier.Result result = new NativeIrVerifier().verify(module);

        assertFalse(result.valid());
        assertTrue(result.format().contains("does not dominate use"));
    }

    @Test
    void validatesJavaSemanticOperationDescriptors() {
        final NativeType.Reference receiver = new NativeType.Reference("example/Test", true);
        final NativeInstruction.Operation malformedCall = new NativeInstruction.Operation(
                "call",
                NativeType.Primitive.I64,
                NativeOpcode.JAVA_CALL,
                List.of(new NativeOperand.Constant(receiver, null), new NativeOperand.Constant(I32, 7)),
                Map.of(
                        "owner", "example/Test",
                        "name", "run",
                        "descriptor", "(Ljava/lang/String;)I",
                        "invoke", "virtual"
                ),
                SourceLocation.UNKNOWN
        );
        final NativeBlock entry = new NativeBlock("entry")
                .addInstruction(malformedCall)
                .terminate(new NativeTerminator.Return(new NativeOperand.Value("call", NativeType.Primitive.I64)));
        final NativeModule module = new NativeModule("java-call")
                .addFunction(function("javaCall", NativeType.Primitive.I64, List.of(), "entry").addBlock(entry));

        final NativeIrVerifier.Result result = new NativeIrVerifier().verify(module);

        assertFalse(result.valid());
        assertTrue(result.format().contains("operand 0 does not match"));
        assertTrue(result.format().contains("result does not match"));
    }

    @Test
    void validatesUtf16StringConstantAndItsSemanticContext() {
        final NativeType.Reference string = new NativeType.Reference("java/lang/String", false);
        final NativeInstruction.Operation literal = new NativeInstruction.Operation(
                "literal", string, NativeOpcode.STRING_CONSTANT, List.of(),
                Map.of("value", "flag\0\uD83D\uDE80"), SourceLocation.UNKNOWN);
        final NativeBlock entry = new NativeBlock("entry")
                .addInstruction(literal)
                .terminate(new NativeTerminator.Return(new NativeOperand.Value("literal", string)));
        final NativeFunction valid = new NativeFunction(
                "skid_literal", "example/Test", "literal", "()Ljava/lang/String;", string,
                List.of(), "entry", NativeBackend.AOT, false, true, Map.of())
                .addBlock(entry);

        assertTrue(new NativeIrVerifier().verify(new NativeModule("string").addFunction(valid)).valid());

        final NativeFunction missingContext = new NativeFunction(
                "skid_unsafe", "example/Test", "unsafe", "()Ljava/lang/String;", string,
                List.of(), "entry", NativeBackend.AOT, false, Map.of())
                .addBlock(new NativeBlock("entry")
                        .addInstruction(literal)
                        .terminate(new NativeTerminator.Return(new NativeOperand.Value("literal", string))));
        final NativeIrVerifier.Result invalid = new NativeIrVerifier().verify(
                new NativeModule("unsafe-string").addFunction(missingContext));
        assertFalse(invalid.valid());
        assertTrue(invalid.format().contains("requires a semantic context"));
    }

    @Test
    void acceptsReferencePhiAndExplicitNonCheckingReferenceCast() {
        final NativeType.Reference object = new NativeType.Reference("java/lang/Object", true);
        final NativeType.Reference string = new NativeType.Reference("java/lang/String", true);
        final NativeParameter argument = new NativeParameter("argument", string, 0);
        final NativeBlock entry = new NativeBlock("entry").terminate(new NativeTerminator.ConditionalBranch(
                new NativeOperand.Constant(NativeType.Primitive.I1, true), "left", "right"));
        final NativeBlock left = new NativeBlock("left").terminate(new NativeTerminator.Branch("join"));
        final NativeBlock right = new NativeBlock("right").terminate(new NativeTerminator.Branch("join"));
        final NativeInstruction.Phi phi = new NativeInstruction.Phi(
                "joined", object, List.of(
                        new NativeInstruction.Phi.Incoming("left", new NativeOperand.Value("argument", string)),
                        new NativeInstruction.Phi.Incoming("right", new NativeOperand.Constant(object, null))),
                SourceLocation.UNKNOWN);
        final NativeInstruction.Operation upcast = new NativeInstruction.Operation(
                "upcast", object, NativeOpcode.REF_CAST,
                List.of(new NativeOperand.Value("joined", object)));
        final NativeBlock join = new NativeBlock("join")
                .addInstruction(phi).addInstruction(upcast)
                .terminate(new NativeTerminator.Return(new NativeOperand.Value("upcast", object)));
        final NativeFunction function = new NativeFunction(
                "skid_ref", "example/Test", "ref", "(Ljava/lang/String;)Ljava/lang/Object;", object,
                List.of(argument), "entry", NativeBackend.AOT, false, Map.of())
                .addBlock(entry).addBlock(left).addBlock(right).addBlock(join);

        assertTrue(new NativeIrVerifier().verify(new NativeModule("refs").addFunction(function)).valid());
    }

    @Test
    void rejectsJavaBoundaryAndParameterIndexMismatches() {
        final NativeParameter receiver = new NativeParameter("receiver", I32, 1);
        final NativeFunction function = new NativeFunction(
                "skid_bad_boundary", "example/Test", "bad", "()I", I32,
                List.of(receiver), "entry", NativeBackend.AOT, false,
                Map.of("java.static", "false"))
                .addBlock(new NativeBlock("entry")
                        .terminate(new NativeTerminator.Return(new NativeOperand.Constant(I32, 1))));

        final NativeIrVerifier.Result result = new NativeIrVerifier().verify(
                new NativeModule("bad-boundary").addFunction(function));

        assertFalse(result.valid());
        assertTrue(result.format().contains("declaring owner type"));
        assertTrue(result.format().contains("non-contiguous parameter indexes"));
    }

    @Test
    void rejectsNominalReferenceAndArrayShapeMismatches() {
        final NativeType.Reference string = new NativeType.Reference("java/lang/String", true);
        final NativeType.Array ints = new NativeType.Array(I32, true);
        final NativeFunction wrongReceiver = new NativeFunction(
                "skid_wrong_receiver", "example/Test", "wrong", "([I)Ljava/lang/String;", string,
                List.of(new NativeParameter("receiver", string, 0),
                        new NativeParameter("array", string, 1)),
                "entry", NativeBackend.AOT, false, Map.of("java.static", "false"))
                .addBlock(new NativeBlock("entry")
                        .terminate(new NativeTerminator.Return(new NativeOperand.Constant(string, null))));

        final NativeIrVerifier.Result result = new NativeIrVerifier().verify(
                new NativeModule("nominal-mismatch").addFunction(wrongReceiver));

        assertFalse(result.valid());
        assertTrue(result.format().contains("declaring owner type"));
        assertTrue(result.format().contains("Native parameter 0 does not match"));
        assertFalse(JavaDescriptor.compatible(ints, string));
        assertFalse(JavaDescriptor.compatible(string, ints));
    }

    @Test
    void validatesJavaOwnedDynamicHelperContract() {
        final Map<String, String> validAttributes = new java.util.LinkedHashMap<>(Map.of(
                "bridge", "java.helper.v1",
                "descriptor", "(I)I",
                "helper.owner", "example/Test",
                "helper.name", "skid$link$0",
                "helper.descriptor", "(I)I",
                "helper.invoke", "static",
                "helper.kind", "invokedynamic"));
        assertTrue(dynamicHelperResult(validAttributes, I32, List.of(
                new NativeOperand.Constant(I32, 7))).valid());

        for (final String missing : List.of(
                "helper.owner", "helper.name", "helper.descriptor", "helper.invoke", "helper.kind")) {
            final Map<String, String> attributes = new java.util.LinkedHashMap<>(validAttributes);
            attributes.remove(missing);
            final NativeIrVerifier.Result result = dynamicHelperResult(attributes, I32,
                    List.of(new NativeOperand.Constant(I32, 7)));
            assertFalse(result.valid());
            assertTrue(result.format().contains("missing attribute " + missing));
        }

        final Map<String, String> mismatch = new java.util.LinkedHashMap<>(validAttributes);
        mismatch.put("helper.descriptor", "(J)I");
        assertTrue(dynamicHelperResult(mismatch, I32, List.of(new NativeOperand.Constant(I32, 7)))
                .format().contains("must equal"));

        final Map<String, String> nonStatic = new java.util.LinkedHashMap<>(validAttributes);
        nonStatic.put("helper.invoke", "virtual");
        assertTrue(dynamicHelperResult(nonStatic, I32, List.of(new NativeOperand.Constant(I32, 7)))
                .format().contains("helper.invoke=static"));

        final Map<String, String> badName = new java.util.LinkedHashMap<>(validAttributes);
        badName.put("helper.name", "<init>");
        assertTrue(dynamicHelperResult(badName, I32, List.of(new NativeOperand.Constant(I32, 7)))
                .format().contains("invalid helper.name"));

        assertTrue(dynamicHelperResult(validAttributes, NativeType.Primitive.I64,
                List.of(new NativeOperand.Constant(I32, 7))).format().contains("result does not match"));
        assertTrue(dynamicHelperResult(validAttributes, I32,
                List.of(new NativeOperand.Constant(NativeType.Primitive.I64, 7L)))
                .format().contains("operand 0 does not match"));
    }

    private NativeIrVerifier.Result dynamicHelperResult(
            final Map<String, String> attributes,
            final NativeType resultType,
            final List<NativeOperand> operands
    ) {
        final NativeInstruction.Operation operation = new NativeInstruction.Operation(
                "linked", resultType, NativeOpcode.DYNAMIC_BRIDGE, operands,
                attributes, SourceLocation.UNKNOWN);
        final NativeFunction function = new NativeFunction(
                "skid_dynamic", "example/Test", "dynamic", "()I", resultType,
                List.of(), "entry", NativeBackend.AOT, false, true,
                Map.of("java.static", "true"))
                .addBlock(new NativeBlock("entry").addInstruction(operation)
                        .terminate(new NativeTerminator.Return(new NativeOperand.Value("linked", resultType))));
        return new NativeIrVerifier().verify(new NativeModule("dynamic-helper").addFunction(function));
    }

    private NativeFunction function(
            final String name,
            final NativeType returnType,
            final List<NativeParameter> parameters,
            final String entry
    ) {
        final String descriptor = "(" + "I".repeat(parameters.size()) + ")"
                + (returnType == NativeType.Primitive.I64 ? "J" : "I");
        return new NativeFunction("skid_" + name, "example/Test", name, descriptor, returnType,
                parameters, entry, NativeBackend.AOT, false, Map.of());
    }
}
