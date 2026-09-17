package dev.skidfuscator.nativetoolchain;

import dev.skidfuscator.nativeir.NativeBackend;
import dev.skidfuscator.nativeir.NativeBlock;
import dev.skidfuscator.nativeir.NativeExceptionEdge;
import dev.skidfuscator.nativeir.NativeFunction;
import dev.skidfuscator.nativeir.NativeInstruction;
import dev.skidfuscator.nativeir.NativeModule;
import dev.skidfuscator.nativeir.NativeOpcode;
import dev.skidfuscator.nativeir.NativeOperand;
import dev.skidfuscator.nativeir.NativeParameter;
import dev.skidfuscator.nativeir.NativeTerminator;
import dev.skidfuscator.nativeir.NativeType;
import dev.skidfuscator.nativeir.SourceLocation;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalLlvmIrEmitterTest {
    private static final NativeType.Primitive I1 = NativeType.Primitive.I1;
    private static final NativeType.Primitive I8 = NativeType.Primitive.I8;
    private static final NativeType.Primitive I32 = NativeType.Primitive.I32;
    private static final NativeType.Primitive I64 = NativeType.Primitive.I64;
    private static final NativeType.Primitive F32 = NativeType.Primitive.F32;
    private static final NativeType.Primitive F64 = NativeType.Primitive.F64;
    private final CanonicalLlvmIrEmitter emitter = new CanonicalLlvmIrEmitter();

    @Test
    void emitsEveryPureNumericOpcode() {
        final NativeFunction function = function(
                "numeric",
                I32,
                List.of(
                        parameter("right", I32, 1),
                        parameter("left", I32, 0),
                        parameter("fp32", F32, 3),
                        parameter("fp64", F64, 2),
                        parameter("wide", I64, 4)
                ),
                "entry"
        );
        final NativeBlock entry = new NativeBlock("entry");
        for (final NativeOpcode opcode : List.of(
                NativeOpcode.ADD, NativeOpcode.SUB, NativeOpcode.MUL)) {
            entry.addInstruction(operation(opcode.name(), I32, opcode, value("left", I32), value("right", I32)));
        }
        for (final NativeOpcode opcode : List.of(
                NativeOpcode.FADD, NativeOpcode.FSUB, NativeOpcode.FMUL, NativeOpcode.FDIV, NativeOpcode.FREM)) {
            entry.addInstruction(operation(opcode.name(), F64, opcode, value("fp64", F64), constant(F64, -0.0d)));
        }
        entry.addInstruction(operation("NEG", I32, NativeOpcode.NEG, value("left", I32)))
                .addInstruction(operation("FNEG", F64, NativeOpcode.FNEG, value("fp64", F64)))
                .addInstruction(operation("BIT_NOT", I32, NativeOpcode.BIT_NOT, value("left", I32)));
        for (final NativeOpcode opcode : List.of(
                NativeOpcode.ICMP_EQ, NativeOpcode.ICMP_NE, NativeOpcode.ICMP_SLT,
                NativeOpcode.ICMP_SLE, NativeOpcode.ICMP_SGT, NativeOpcode.ICMP_SGE)) {
            entry.addInstruction(operation(opcode.name(), I1, opcode, value("left", I32), value("right", I32)));
        }
        for (final NativeOpcode opcode : List.of(
                NativeOpcode.FCMP_EQ, NativeOpcode.FCMP_NE, NativeOpcode.FCMP_LT,
                NativeOpcode.FCMP_LE, NativeOpcode.FCMP_GT, NativeOpcode.FCMP_GE)) {
            entry.addInstruction(operation(opcode.name(), I1, opcode, value("fp64", F64), constant(F64, Double.NaN)));
        }
        entry.addInstruction(operation("SELECT", I32, NativeOpcode.SELECT,
                        value("ICMP_EQ", I1), value("left", I32), value("right", I32)))
                .addInstruction(operation("WIDEN", I64, NativeOpcode.CONVERT, value("left", I32)))
                .addInstruction(operation("NARROW", I8, NativeOpcode.CONVERT, value("wide", I64)))
                .addInstruction(operation("INT_TO_FP", F64, NativeOpcode.CONVERT, value("left", I32)))
                .addInstruction(operation("FP_EXT", F64, NativeOpcode.CONVERT, value("fp32", F32)))
                .addInstruction(operation("FP_TRUNC", F32, NativeOpcode.CONVERT, value("fp64", F64)))
                .addInstruction(operation("ALIAS", I32, NativeOpcode.CONVERT, value("ADD", I32)))
                .terminate(new NativeTerminator.Return(value("ALIAS", I32)));
        function.addBlock(entry);

        final String llvm = emit(new NativeModule("numeric-module").addFunction(function));

        for (final String instruction : List.of(
                " = add i32 ", " = sub i32 ", " = mul i32 ",
                " = fadd double ", " = fsub double ", " = fmul double ",
                " = fdiv double ", " = frem double ", " = fneg double ",
                " = xor i32 ", " = icmp eq i32 ", " = icmp ne i32 ",
                " = icmp slt i32 ", " = icmp sle i32 ", " = icmp sgt i32 ",
                " = icmp sge i32 ", " = fcmp oeq double ", " = fcmp une double ",
                " = fcmp olt double ", " = fcmp ole double ", " = fcmp ogt double ",
                " = fcmp oge double ", " = select i1 ", " = sext i32 ",
                " = trunc i64 ", " = sitofp i32 ",
                " = fpext float ", " = fptrunc double ")) {
            assertTrue(llvm.contains(instruction), () -> "Missing LLVM instruction: " + instruction);
        }
        assertFalse(llvm.contains("%\"v.ALIAS\" ="), "same-type conversion should be a canonical SSA alias");
        assertTrue(llvm.contains("ret i32 %\"v.ADD\""));
        assertTrue(llvm.contains("0x8000000000000000"), "floating constants preserve signed zero");
        assertTrue(llvm.contains("0x7FF8000000000000"), "floating constants support NaN");
    }

    @Test
    void emitsPhiBranchesSortedSwitchAndOpaqueReferences() {
        final NativeType.Reference object = new NativeType.Reference("java/lang/Object", true);
        final NativeFunction choose = function(
                "choose",
                object,
                List.of(parameter("selector", I32, 1), parameter("candidate", object, 0)),
                "entry"
        );
        final Map<Long, String> cases = new LinkedHashMap<>();
        cases.put(7L, "seven");
        cases.put(-1L, "negative");
        choose.addBlock(new NativeBlock("merge")
                        .addInstruction(new NativeInstruction.Phi(
                                "selected",
                                object,
                                List.of(
                                        new NativeInstruction.Phi.Incoming("seven", value("candidate", object)),
                                        new NativeInstruction.Phi.Incoming("negative", constant(object, null)),
                                        new NativeInstruction.Phi.Incoming("fallback", constant(object, null))
                                ),
                                SourceLocation.UNKNOWN
                        ))
                        .terminate(new NativeTerminator.Return(value("selected", object))))
                .addBlock(new NativeBlock("entry")
                        .addInstruction(operation("is-zero", I1, NativeOpcode.ICMP_EQ,
                                value("selector", I32), constant(I32, 0)))
                        .terminate(new NativeTerminator.ConditionalBranch(
                                value("is-zero", I1), "fallback", "dispatch")))
                .addBlock(new NativeBlock("dispatch")
                        .terminate(new NativeTerminator.Switch(
                                value("selector", I32), cases, "fallback", SourceLocation.UNKNOWN)))
                .addBlock(new NativeBlock("seven").terminate(new NativeTerminator.Branch("merge")))
                .addBlock(new NativeBlock("negative").terminate(new NativeTerminator.Branch("merge")))
                .addBlock(new NativeBlock("fallback").terminate(new NativeTerminator.Branch("merge")));

        final String llvm = emit(new NativeModule("references").addFunction(choose));

        assertTrue(llvm.contains("define hidden ptr @\"choose\"(ptr %\"v.candidate\", i32 %\"v.selector\")"));
        assertTrue(llvm.contains("br i1 %\"v.is-zero\", label %\"b.fallback\", label %\"b.dispatch\""));
        assertTrue(llvm.indexOf("i32 -1, label %\"b.negative\"")
                < llvm.indexOf("i32 7, label %\"b.seven\""));
        assertTrue(llvm.contains("%\"v.selected\" = phi ptr [ null, %\"b.fallback\" ], "
                + "[ null, %\"b.negative\" ], [ %\"v.candidate\", %\"b.seven\" ]"));
        assertTrue(llvm.contains("ret ptr %\"v.selected\""));
    }

    @Test
    void emitsReferenceUpcastAsAnOpaquePointerAlias() {
        final NativeType.Reference string = new NativeType.Reference("java/lang/String", true);
        final NativeType.Reference object = new NativeType.Reference("java/lang/Object", true);
        final NativeParameter argument = new NativeParameter("argument", string, 0);
        final NativeInstruction.Operation upcast = new NativeInstruction.Operation(
                "upcast", object, NativeOpcode.REF_CAST,
                List.of(new NativeOperand.Value("argument", string)));
        final NativeBlock entry = new NativeBlock("entry")
                .addInstruction(upcast)
                .terminate(new NativeTerminator.Return(new NativeOperand.Value("upcast", object)));
        final NativeFunction function = function(
                "reference_upcast", object, List.of(argument), "entry").addBlock(entry);

        final String llvm = emit(new NativeModule("reference-upcast").addFunction(function));

        assertTrue(llvm.contains("ret ptr %\"v.argument\""));
        assertFalse(llvm.contains("%\"v.upcast\" ="));
    }

    @Test
    void outputIsDeterministicAndEscapesSymbolsAndMetadataAsUtf8Bytes() {
        final NativeModule first = deterministicModule(false);
        final NativeModule second = deterministicModule(true);

        final String firstOutput = emit(first);
        final String secondOutput = emit(second);

        assertEquals(firstOutput, secondOutput);
        assertTrue(firstOutput.indexOf("define hidden void @\"a\\22\\0A")
                < firstOutput.indexOf("define hidden void @\"z\""));
        assertTrue(firstOutput.contains("metadata.key\\0Awith-newline"));
        assertTrue(firstOutput.contains("snowman-\\E2\\98\\83"));
        assertFalse(firstOutput.contains("key\nwith-newline"));
        for (final String helper : List.of("array_load", "java_call", "monitor_enter", "throw")) {
            assertTrue(firstOutput.contains("declare i32 @\"skid.semantic." + helper
                    + ".v1\"(ptr, ptr, ptr)"));
        }
    }

    @Test
    void emitsTypedSemanticAdapterSitesWithLinkageMetadata() {
        final NativeType.Reference object = new NativeType.Reference("java/lang/Object", true);
        final NativeFunction function = new NativeFunction(
                "monitor", "example/Test", "monitor", "(Ljava/lang/Object;)V",
                NativeType.Primitive.VOID, List.of(parameter("lock", object, 0)), "entry",
                NativeBackend.AOT, false, true, Map.of());
        function.addBlock(new NativeBlock("entry")
                .addInstruction(operation("enter", NativeType.Primitive.VOID,
                        NativeOpcode.MONITOR_ENTER, value("lock", object)))
                .terminate(NativeTerminator.Return.voidReturn()));

        final String llvm = emit(new NativeModule("jni").addFunction(function));

        assertTrue(llvm.contains("declare void @\"skid.semantic.site.v1.monitor.enter\"(ptr, ptr)"));
        assertTrue(llvm.contains("call void @\"skid.semantic.site.v1.monitor.enter\"(ptr "
                + "%\"skid.semantic.context\", ptr %\"v.lock\")"));
        assertTrue(llvm.contains("!skid.semantic.sites = !{"));
        assertTrue(llvm.contains("!\"opcode\", !\"MONITOR_ENTER\""));
    }

    @Test
    void emitsJavaThrowAsARequiredSemanticAdapterAndUnreachable() {
        final NativeType.Reference throwable = new NativeType.Reference("java/lang/Throwable", true);
        final NativeFunction function = new NativeFunction(
                "rethrow", "example/Test", "rethrow", "(Ljava/lang/Throwable;)V",
                NativeType.Primitive.VOID, List.of(parameter("error", throwable, 0)), "entry",
                NativeBackend.AOT, false, true, Map.of())
                .addBlock(new NativeBlock("entry")
                        .terminate(new NativeTerminator.Throw(value("error", throwable))));

        final String llvm = emit(new NativeModule("throw").addFunction(function));

        assertTrue(llvm.contains("declare void @\"skid.semantic.site.v1.rethrow.entry.throw\"(ptr, ptr)"));
        assertTrue(llvm.contains("call void @\"skid.semantic.site.v1.rethrow.entry.throw\""));
        assertTrue(llvm.contains("call i32 @\"skid.semantic.site.v1.rethrow.entry.exception-dispatch\""));
        assertTrue(llvm.contains("\"b.skid.exception.escape\":\n  ret void"));
        assertTrue(llvm.contains("!\"opcode\", !\"THROW\""));
    }

    @Test
    void emitsExactUtf16StringConstantsThroughTheSemanticContext() {
        final String text = "A\0\uD83D\uDE80\uDFFF";
        final NativeType.Reference string = new NativeType.Reference("java/lang/String", false);
        final NativeFunction function = new NativeFunction(
                "reveal.symbol", "example/Secrets", "revealFlag", "()Ljava/lang/String;", string,
                List.of(), "entry", NativeBackend.AOT, false, true, Map.of());
        function.addBlock(new NativeBlock("entry")
                .addInstruction(new NativeInstruction.Operation(
                        "literal", string, NativeOpcode.STRING_CONSTANT, List.of(), Map.of("value", text),
                        SourceLocation.UNKNOWN))
                .terminate(new NativeTerminator.Return(value("literal", string))));

        final String llvm = emit(new NativeModule("strings").addFunction(function));

        assertTrue(llvm.contains("declare ptr @\"skid.semantic.string_constant.v1\"(ptr, ptr, i32)"));
        assertTrue(llvm.contains("constant [5 x i16] [i16 65, i16 0, i16 55357, i16 56960, i16 57343]"));
        assertTrue(llvm.contains("define hidden ptr @\"reveal.symbol\"(ptr %\"skid.semantic.context\")"));
        assertTrue(llvm.contains("call ptr @\"skid.semantic.string_constant.v1\"(ptr "
                + "%\"skid.semantic.context\", ptr @\"skid.string."));
        assertTrue(llvm.contains("i32 5)"));
        assertEquals("reveal.symbol", emitter.emittedFunctionSymbol(function));
    }

    @Test
    void emitsGuardedJavaIntegerDivisionAndRemainder() {
        final NativeFunction division = semanticFunction(
                "division", I32, List.of(parameter("left", I32, 0), parameter("right", I32, 1)), "entry");
        division.addBlock(new NativeBlock("entry")
                .addInstruction(operation("quotient", I32, NativeOpcode.SDIV,
                        value("left", I32), value("right", I32)))
                .addInstruction(operation("remainder", I32, NativeOpcode.SREM,
                        value("left", I32), value("right", I32)))
                .terminate(new NativeTerminator.Return(value("remainder", I32))));

        final String llvm = emit(new NativeModule("division").addFunction(division));

        assertTrue(llvm.contains("!\"opcode\", !\"JAVA_SDIV_GUARD\""));
        assertTrue(llvm.contains("!\"opcode\", !\"JAVA_SREM_GUARD\""));
        assertTrue(llvm.contains(" = icmp eq i32 %\"v.right\", 0"));
        assertTrue(llvm.contains(" = select i1 %\"v.quotient.skid.invalid\", i32 1, i32 %\"v.right\""));
        assertTrue(llvm.contains(" = sdiv i32 %\"v.left\", %\"v.quotient.skid.safe-divisor\""));
        assertTrue(llvm.contains(" = select i1 %\"v.quotient.skid.overflow\", i32 -2147483648"));
        assertTrue(llvm.contains(" = srem i32 %\"v.left\", %\"v.remainder.skid.safe-divisor\""));
        assertTrue(llvm.contains(" = select i1 %\"v.remainder.skid.overflow\", i32 0"));
        assertTrue(llvm.contains("i32 -1, label %\"b.entry.skid.cont.1\""));
        assertTrue(llvm.contains("\"b.skid.exception.escape\":\n  ret i32 0"));
    }

    @Test
    void emitsJavaNanAndSaturatingFloatingPointConversions() {
        final NativeFunction conversion = function(
                "conversion", I64, List.of(parameter("value", F64, 0)), "entry");
        conversion.addBlock(new NativeBlock("entry")
                .addInstruction(operation("integer", I32, NativeOpcode.CONVERT, value("value", F64)))
                .addInstruction(operation("long", I64, NativeOpcode.CONVERT, value("value", F64)))
                .terminate(new NativeTerminator.Return(value("long", I64))));

        final String llvm = emit(new NativeModule("conversion").addFunction(conversion));

        assertTrue(llvm.contains("%\"v.integer.skid.nan\" = fcmp uno double"));
        assertTrue(llvm.contains("%\"v.integer.skid.high\" = fcmp oge double"));
        assertTrue(llvm.contains("%\"v.integer.skid.raw\" = fptosi double"));
        assertTrue(llvm.contains("select i1 %\"v.integer.skid.high\", i32 2147483647"));
        assertTrue(llvm.contains("%\"v.integer\" = select i1 %\"v.integer.skid.nan\", i32 0"));
        assertTrue(llvm.contains("select i1 %\"v.long.skid.low\", i64 -9223372036854775808"));
        assertTrue(llvm.contains("select i1 %\"v.long.skid.high\", i64 9223372036854775807"));
    }

    @Test
    void rejectsDirectFloatingPointNarrowIntegerConversion() {
        final NativeFunction conversion = function(
                "narrow_conversion", I8, List.of(parameter("value", F64, 0)), "entry");
        conversion.addBlock(new NativeBlock("entry")
                .addInstruction(operation("byte", I8, NativeOpcode.CONVERT, value("value", F64)))
                .terminate(new NativeTerminator.Return(value("byte", I8))));

        final RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> emitter.emit(new NativeModule("narrow-conversion").addFunction(conversion)));

        assertTrue(exception.getMessage().contains("must first convert to i32 and then truncate"));
    }

    @Test
    void rejectsIntegerDivisionWithoutTheRequiredSemanticContext() {
        final NativeFunction division = function(
                "division", I32, List.of(parameter("left", I32, 0), parameter("right", I32, 1)), "entry");
        division.addBlock(new NativeBlock("entry")
                .addInstruction(operation("quotient", I32, NativeOpcode.SDIV,
                        value("left", I32), value("right", I32)))
                .terminate(new NativeTerminator.Return(value("quotient", I32))));

        assertTrue(assertThrows(
                RuntimeException.class,
                () -> emitter.emit(new NativeModule("division").addFunction(division))
        ).getMessage().contains("semantic context"));
    }

    @Test
    void emitsTypedPendingExceptionDispatchIntoJavaCatchHandlers() {
        final NativeType.Reference object = new NativeType.Reference("java/lang/Object", true);
        final NativeType.Reference throwable = new NativeType.Reference("java/lang/Throwable", false);
        final NativeFunction function = semanticFunction(
                "exceptions", NativeType.Primitive.VOID, List.of(parameter("lock", object, 0)), "entry");
        function.addBlock(new NativeBlock("entry")
                        .addInstruction(operation("enter", NativeType.Primitive.VOID,
                                NativeOpcode.MONITOR_ENTER, value("lock", object)))
                        .addExceptionEdge(new NativeExceptionEdge(
                                "handler", Optional.of("java/lang/RuntimeException"), 4))
                        .addExceptionEdge(new NativeExceptionEdge("handler", Optional.empty(), 8))
                        .terminate(new NativeTerminator.Branch("exit")))
                .addBlock(new NativeBlock("handler")
                        .addInstruction(operation("caught", throwable, NativeOpcode.CATCH_EXCEPTION))
                        .terminate(new NativeTerminator.Branch("exit")))
                .addBlock(new NativeBlock("exit").terminate(NativeTerminator.Return.voidReturn()));

        final String llvm = emit(new NativeModule("exceptions").addFunction(function));

        assertTrue(llvm.contains("declare i32 @\"skid.semantic.site.v1.exceptions.entry.exception-dispatch\"(ptr)"));
        assertTrue(llvm.contains("i32 -1, label %\"b.entry.skid.cont.1\""));
        assertTrue(llvm.contains("i32 0, label %\"b.handler\""));
        assertTrue(llvm.contains("i32 1, label %\"b.handler\""));
        assertTrue(llvm.contains("!\"opcode\", !\"EXCEPTION_DISPATCH\""));
        assertTrue(llvm.contains("!\"catch\", !\"java/lang/RuntimeException\""));
        assertTrue(llvm.contains("!\"catch\", !\"*\""));
        assertTrue(llvm.contains("\"b.skid.exception.escape\":\n  ret void"));
    }

    @Test
    void rejectsAmbiguousParameterIndexes() {
        final NativeFunction function = function(
                "gap", NativeType.Primitive.VOID, List.of(parameter("only", I32, 1)), "entry");
        function.addBlock(new NativeBlock("entry").terminate(NativeTerminator.Return.voidReturn()));

        final LlvmEmissionException exception = assertThrows(
                LlvmEmissionException.class,
                () -> emitter.emit(new NativeModule("gap").addFunction(function))
        );
        assertTrue(exception.getMessage().contains("non-contiguous parameter indexes"));
    }

    private NativeModule deterministicModule(final boolean reversed) {
        final Map<String, String> metadata = new LinkedHashMap<>();
        if (reversed) {
            metadata.put("snowman-☃", "value");
            metadata.put("key\nwith-newline", "quote-\"");
        } else {
            metadata.put("key\nwith-newline", "quote-\"");
            metadata.put("snowman-☃", "value");
        }
        final NativeModule module = new NativeModule("module-\"\n", 1, metadata);
        final NativeFunction a = function("a\"\n", NativeType.Primitive.VOID, List.of(), "entry");
        a.addBlock(new NativeBlock("entry").terminate(NativeTerminator.Return.voidReturn()));
        final NativeFunction z = function("z", NativeType.Primitive.VOID, List.of(), "entry");
        z.addBlock(new NativeBlock("entry").terminate(NativeTerminator.Return.voidReturn()));
        if (reversed) {
            module.addFunction(a).addFunction(z);
        } else {
            module.addFunction(z).addFunction(a);
        }
        return module;
    }

    private static NativeFunction function(
            final String symbol,
            final NativeType returnType,
            final List<NativeParameter> parameters,
            final String entry
    ) {
        return new NativeFunction(
                symbol, "example/Test", symbol, "()V", returnType, parameters, entry,
                NativeBackend.AOT, false, Map.of()
        );
    }

    private static NativeFunction semanticFunction(
            final String symbol,
            final NativeType returnType,
            final List<NativeParameter> parameters,
            final String entry
    ) {
        return new NativeFunction(
                symbol, "example/Test", symbol, "()V", returnType, parameters, entry,
                NativeBackend.AOT, false, true, Map.of()
        );
    }

    private static NativeParameter parameter(final String id, final NativeType type, final int index) {
        return new NativeParameter(id, type, index);
    }

    private static NativeInstruction.Operation operation(
            final String id,
            final NativeType type,
            final NativeOpcode opcode,
            final NativeOperand... operands
    ) {
        return new NativeInstruction.Operation(id, type, opcode, List.of(operands));
    }

    private static NativeOperand.Value value(final String id, final NativeType type) {
        return new NativeOperand.Value(id, type);
    }

    private static NativeOperand.Constant constant(final NativeType type, final Object value) {
        return new NativeOperand.Constant(type, value);
    }

    private String emit(final NativeModule module) {
        return new String(emitter.emit(module), StandardCharsets.UTF_8);
    }
}
