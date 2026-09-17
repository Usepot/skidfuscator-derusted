package dev.skidfuscator.nativeir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Performs structural and type checks before bytecode methods are changed. */
public final class NativeIrVerifier {
    private static final Set<NativeOpcode> SEMANTIC_OPCODES = Set.of(
            NativeOpcode.STRING_CONSTANT, NativeOpcode.JAVA_CALL, NativeOpcode.DYNAMIC_BRIDGE,
            NativeOpcode.FIELD_GET, NativeOpcode.FIELD_SET, NativeOpcode.NEW_OBJECT,
            NativeOpcode.INSTANCE_OF, NativeOpcode.CHECK_CAST, NativeOpcode.ARRAY_NEW,
            NativeOpcode.ARRAY_LENGTH, NativeOpcode.ARRAY_LOAD, NativeOpcode.ARRAY_STORE,
            NativeOpcode.MONITOR_ENTER, NativeOpcode.MONITOR_EXIT, NativeOpcode.CATCH_EXCEPTION);

    public Result verify(final NativeModule module) {
        Objects.requireNonNull(module, "module");
        final List<Diagnostic> diagnostics = new ArrayList<>();
        if (module.abiVersion() != NativeIrVersions.CURRENT_ABI) {
            diagnostics.add(new Diagnostic(module.name(), "Unsupported native IR ABI " + module.abiVersion()
                    + "; expected " + NativeIrVersions.CURRENT_ABI));
        }

        final Set<String> symbols = new HashSet<>();
        for (final NativeFunction function : module.functions()) {
            if (!symbols.add(function.symbol())) {
                diagnostics.add(new Diagnostic(function.symbol(), "Duplicate function symbol"));
            }
            verifyFunction(function, diagnostics);
        }
        return new Result(diagnostics);
    }

    public void verifyOrThrow(final NativeModule module) {
        final Result result = verify(module);
        if (!result.valid()) {
            throw new NativeIrVerificationException(result);
        }
    }

    private void verifyFunction(final NativeFunction function, final List<Diagnostic> diagnostics) {
        verifyJavaBoundary(function, diagnostics);
        final Map<String, NativeType> definitions = new HashMap<>();
        final Map<String, DefinitionSite> definitionSites = new HashMap<>();
        final Set<Integer> parameterIndexes = new HashSet<>();
        for (final NativeParameter parameter : function.parameters()) {
            if (parameter.type().isVoid()) {
                error(diagnostics, function, "Parameter " + parameter.id() + " has void type");
            }
            if (definitions.putIfAbsent(parameter.id(), parameter.type()) != null) {
                error(diagnostics, function, "Duplicate SSA id " + parameter.id());
            } else {
                definitionSites.put(parameter.id(), DefinitionSite.forParameter());
            }
            if (!parameterIndexes.add(parameter.index())) {
                error(diagnostics, function, "Duplicate parameter index " + parameter.index());
            }
        }
        for (int index = 0; index < function.parameters().size(); index++) {
            if (!parameterIndexes.contains(index)) {
                error(diagnostics, function, "non-contiguous parameter indexes; missing " + index);
            }
        }

        final Map<String, NativeBlock> blocks = new HashMap<>();
        for (final NativeBlock block : function.blocks()) {
            if (blocks.putIfAbsent(block.id(), block) != null) {
                error(diagnostics, function, "Duplicate block " + block.id());
            }
            int instructionIndex = 0;
            for (final NativeInstruction instruction : block.instructions()) {
                if (definitions.putIfAbsent(instruction.id(), instruction.type()) != null) {
                    error(diagnostics, function, "Duplicate SSA id " + instruction.id());
                } else {
                    definitionSites.put(instruction.id(), new DefinitionSite(block.id(), instructionIndex));
                }
                instructionIndex++;
            }
        }

        if (!blocks.containsKey(function.entryBlock())) {
            error(diagnostics, function, "Entry block does not exist: " + function.entryBlock());
        }

        final Map<String, Set<String>> predecessors = new HashMap<>();
        for (final NativeBlock block : function.blocks()) {
            if (block.terminator().isEmpty()) {
                error(diagnostics, function, "Block " + block.id() + " has no terminator");
                continue;
            }
            for (final String target : block.terminator().get().successors()) {
                if (!blocks.containsKey(target)) {
                    error(diagnostics, function, "Block " + block.id() + " targets unknown block " + target);
                } else {
                    predecessors.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(block.id());
                }
            }
            for (final NativeExceptionEdge edge : block.exceptionEdges()) {
                if (!blocks.containsKey(edge.handlerBlock())) {
                    error(diagnostics, function, "Block " + block.id() + " has unknown exception handler "
                            + edge.handlerBlock());
                } else {
                    predecessors.computeIfAbsent(edge.handlerBlock(), ignored -> new LinkedHashSet<>()).add(block.id());
                }
            }
        }

        for (final NativeBlock block : function.blocks()) {
            boolean sawNonPhi = false;
            for (final NativeInstruction instruction : block.instructions()) {
                if (instruction.type().isVoid() && instruction instanceof NativeInstruction.Phi) {
                    error(diagnostics, function, "Phi " + instruction.id() + " cannot have void type");
                }
                if (instruction instanceof NativeInstruction.Phi phi) {
                    if (sawNonPhi) {
                        error(diagnostics, function, "Phi " + phi.id() + " appears after a non-phi instruction");
                    }
                    verifyPhi(function, block, phi, predecessors, definitions, diagnostics);
                } else {
                    sawNonPhi = true;
                    final NativeInstruction.Operation operation = (NativeInstruction.Operation) instruction;
                    operation.operands().forEach(operand -> verifyOperand(function, operand, definitions, diagnostics));
                    verifyOperation(function, operation, diagnostics);
                }
            }
            block.terminator().ifPresent(terminator ->
                    verifyTerminator(function, terminator, definitions, diagnostics));
        }
        verifyDominance(function, blocks, predecessors, definitionSites, diagnostics);
    }

    /** Verifies the exact Java/JNI shape before a registration trampoline is generated. */
    private void verifyJavaBoundary(
            final NativeFunction function,
            final List<Diagnostic> diagnostics
    ) {
        final String staticAttribute = function.metadata().get("java.static");
        // Hand-authored IR used by embedding clients may omit Java registration metadata. The
        // production Maple lowerer always supplies it, so exact JNI validation is mandatory there.
        if (staticAttribute == null) {
            return;
        }
        final JavaDescriptor.Method descriptor;
        try {
            descriptor = JavaDescriptor.method(function.javaDescriptor());
        } catch (IllegalArgumentException exception) {
            error(diagnostics, function, "Invalid Java method descriptor " + function.javaDescriptor());
            return;
        }
        if (!JavaDescriptor.compatible(descriptor.returnType(), function.returnType())) {
            error(diagnostics, function, "Native return type does not match the Java descriptor");
        }

        final boolean statical;
        if (staticAttribute.equals("true") || staticAttribute.equals("false")) {
            statical = Boolean.parseBoolean(staticAttribute);
        } else {
            error(diagnostics, function, "metadata.java.static must be true or false");
            return;
        }

        final List<NativeParameter> ordered = function.parameters().stream()
                .sorted(java.util.Comparator.comparingInt(NativeParameter::index))
                .toList();
        final int receiverCount = statical ? 0 : 1;
        if (ordered.size() != descriptor.parameters().size() + receiverCount) {
            error(diagnostics, function, "Native parameter count does not match the Java descriptor");
            return;
        }
        if (!statical && !JavaDescriptor.compatible(
                new NativeType.Reference(function.javaOwner(), true), ordered.get(0).type())) {
            error(diagnostics, function,
                    "An instance Java boundary requires a receiver of its declaring owner type");
        }
        for (int index = 0; index < descriptor.parameters().size(); index++) {
            if (!JavaDescriptor.compatible(
                    descriptor.parameters().get(index), ordered.get(index + receiverCount).type())) {
                error(diagnostics, function, "Native parameter " + index
                        + " does not match the Java descriptor");
            }
        }
    }

    private void verifyDominance(
            final NativeFunction function,
            final Map<String, NativeBlock> blocks,
            final Map<String, Set<String>> predecessors,
            final Map<String, DefinitionSite> definitions,
            final List<Diagnostic> diagnostics
    ) {
        if (!blocks.containsKey(function.entryBlock())) {
            return;
        }
        final Set<String> reachable = new LinkedHashSet<>();
        final Deque<String> work = new ArrayDeque<>();
        work.add(function.entryBlock());
        while (!work.isEmpty()) {
            final String blockId = work.removeFirst();
            if (!reachable.add(blockId)) {
                continue;
            }
            final NativeBlock block = blocks.get(blockId);
            if (block == null) {
                continue;
            }
            block.terminator().ifPresent(terminator -> terminator.successors().stream()
                    .filter(blocks::containsKey)
                    .forEach(work::add));
            block.exceptionEdges().stream()
                    .map(NativeExceptionEdge::handlerBlock)
                    .filter(blocks::containsKey)
                    .forEach(work::add);
        }
        for (final String blockId : blocks.keySet()) {
            if (!reachable.contains(blockId)) {
                error(diagnostics, function, "Block " + blockId + " is unreachable from the entry block");
            }
        }

        final Map<String, Set<String>> dominators = new HashMap<>();
        for (final String blockId : reachable) {
            dominators.put(blockId, blockId.equals(function.entryBlock())
                    ? new HashSet<>(Set.of(blockId))
                    : new HashSet<>(reachable));
        }
        boolean changed;
        do {
            changed = false;
            for (final String blockId : reachable) {
                if (blockId.equals(function.entryBlock())) {
                    continue;
                }
                final Set<String> reachablePredecessors = new HashSet<>(
                        predecessors.getOrDefault(blockId, Set.of()));
                reachablePredecessors.retainAll(reachable);
                final Set<String> updated = new HashSet<>();
                boolean first = true;
                for (final String predecessor : reachablePredecessors) {
                    if (first) {
                        updated.addAll(dominators.get(predecessor));
                        first = false;
                    } else {
                        updated.retainAll(dominators.get(predecessor));
                    }
                }
                updated.add(blockId);
                if (!updated.equals(dominators.get(blockId))) {
                    dominators.put(blockId, updated);
                    changed = true;
                }
            }
        } while (changed);

        for (final NativeBlock block : function.blocks()) {
            int instructionIndex = 0;
            for (final NativeInstruction instruction : block.instructions()) {
                if (instruction instanceof NativeInstruction.Phi phi) {
                    for (final NativeInstruction.Phi.Incoming incoming : phi.incoming()) {
                        verifyUseDominance(function, phi.id(), incoming.value(), incoming.predecessorBlock(),
                                Integer.MAX_VALUE, definitions, dominators, diagnostics);
                    }
                } else {
                    for (final NativeOperand operand : instruction.operands()) {
                        verifyUseDominance(function, instruction.id(), operand, block.id(), instructionIndex,
                                definitions, dominators, diagnostics);
                    }
                }
                instructionIndex++;
            }
            if (block.terminator().isPresent()) {
                for (final NativeOperand operand : terminatorOperands(block.terminator().get())) {
                    verifyUseDominance(function, "terminator in " + block.id(), operand, block.id(),
                            block.instructions().size(), definitions, dominators, diagnostics);
                }
            }
        }
    }

    private List<NativeOperand> terminatorOperands(final NativeTerminator terminator) {
        if (terminator instanceof NativeTerminator.Return ret) {
            return ret.value().map(List::of).orElseGet(List::of);
        }
        if (terminator instanceof NativeTerminator.ConditionalBranch branch) {
            return List.of(branch.condition());
        }
        if (terminator instanceof NativeTerminator.Switch switchTerminator) {
            return List.of(switchTerminator.selector());
        }
        if (terminator instanceof NativeTerminator.Throw throwing) {
            return List.of(throwing.throwable());
        }
        return List.of();
    }

    private void verifyUseDominance(
            final NativeFunction function,
            final String user,
            final NativeOperand operand,
            final String useBlock,
            final int useIndex,
            final Map<String, DefinitionSite> definitions,
            final Map<String, Set<String>> dominators,
            final List<Diagnostic> diagnostics
    ) {
        if (!(operand instanceof NativeOperand.Value value)) {
            return;
        }
        final DefinitionSite definition = definitions.get(value.id());
        if (definition == null || definition.isParameter()) {
            return;
        }
        if (definition.blockId().equals(useBlock)) {
            if (definition.instructionIndex() >= useIndex) {
                error(diagnostics, function, "SSA value " + value.id() + " does not dominate " + user);
            }
            return;
        }
        if (!dominators.getOrDefault(useBlock, Set.of()).contains(definition.blockId())) {
            error(diagnostics, function, "SSA value " + value.id() + " does not dominate " + user);
        }
    }

    private void verifyPhi(
            final NativeFunction function,
            final NativeBlock block,
            final NativeInstruction.Phi phi,
            final Map<String, Set<String>> predecessors,
            final Map<String, NativeType> definitions,
            final List<Diagnostic> diagnostics
    ) {
        final Set<String> actual = predecessors.getOrDefault(block.id(), Set.of());
        final Set<String> declared = new LinkedHashSet<>();
        for (final NativeInstruction.Phi.Incoming incoming : phi.incoming()) {
            if (!declared.add(incoming.predecessorBlock())) {
                error(diagnostics, function, "Phi " + phi.id() + " repeats predecessor "
                        + incoming.predecessorBlock());
            }
            if (!actual.contains(incoming.predecessorBlock())) {
                error(diagnostics, function, "Phi " + phi.id() + " names non-predecessor "
                        + incoming.predecessorBlock());
            }
            verifyOperand(function, incoming.value(), definitions, diagnostics);
            if (!compatibleValueTypes(phi.type(), incoming.value().type())) {
                error(diagnostics, function, "Phi " + phi.id() + " incoming type mismatch");
            }
        }
        if (!declared.equals(actual)) {
            error(diagnostics, function, "Phi " + phi.id() + " must have one incoming value for every predecessor");
        }
    }

    private void verifyTerminator(
            final NativeFunction function,
            final NativeTerminator terminator,
            final Map<String, NativeType> definitions,
            final List<Diagnostic> diagnostics
    ) {
        if (terminator instanceof NativeTerminator.Return ret) {
            if (function.returnType().isVoid() != ret.value().isEmpty()) {
                error(diagnostics, function, "Return value does not match function return type");
            }
            ret.value().ifPresent(value -> {
                verifyOperand(function, value, definitions, diagnostics);
                if (!value.type().equals(function.returnType())) {
                    error(diagnostics, function, "Return operand type does not match function return type");
                }
            });
        } else if (terminator instanceof NativeTerminator.ConditionalBranch branch) {
            verifyOperand(function, branch.condition(), definitions, diagnostics);
            if (branch.condition().type() != NativeType.Primitive.I1) {
                error(diagnostics, function, "Conditional branch condition must be i1");
            }
        } else if (terminator instanceof NativeTerminator.Switch switchTerminator) {
            verifyOperand(function, switchTerminator.selector(), definitions, diagnostics);
            if (!(switchTerminator.selector().type() instanceof NativeType.Primitive primitive)
                    || !primitive.isInteger()) {
                error(diagnostics, function, "Switch selector must be an integer");
            }
        } else if (terminator instanceof NativeTerminator.Throw throwing) {
            verifyOperand(function, throwing.throwable(), definitions, diagnostics);
            if (!throwing.throwable().type().isReferenceLike()) {
                error(diagnostics, function, "Thrown value must be a reference");
            }
            if (!function.requiresSemanticContext()) {
                error(diagnostics, function, "Throw requires a semantic context");
            }
        }
    }

    private void verifyOperand(
            final NativeFunction function,
            final NativeOperand operand,
            final Map<String, NativeType> definitions,
            final List<Diagnostic> diagnostics
    ) {
        if (operand.type().isVoid()) {
            error(diagnostics, function, "An operand cannot have void type");
        }
        if (operand instanceof NativeOperand.Value value) {
            final NativeType actual = definitions.get(value.id());
            if (actual == null) {
                error(diagnostics, function, "Use of undefined SSA value " + value.id());
            } else if (!actual.equals(value.type())) {
                error(diagnostics, function, "SSA value " + value.id() + " is declared as "
                        + actual.displayName() + " but used as " + value.type().displayName());
            }
        } else if (operand instanceof NativeOperand.Constant constant && !validConstant(constant)) {
            error(diagnostics, function, "Invalid constant for type " + constant.type().displayName());
        }
    }

    private boolean validConstant(final NativeOperand.Constant constant) {
        final Object value = constant.value();
        if (constant.type() instanceof NativeType.Reference reference) {
            return value == null ? reference.nullable() : value instanceof String;
        }
        if (constant.type() instanceof NativeType.Array array) {
            return value == null && array.nullable();
        }
        if (value == null || constant.type() == NativeType.Primitive.VOID) {
            return false;
        }
        if (constant.type() == NativeType.Primitive.I1) {
            return value instanceof Boolean;
        }
        if (constant.type() instanceof NativeType.Primitive primitive) {
            return primitive.isNumeric() && value instanceof Number;
        }
        return false;
    }

    private void verifyOperation(
            final NativeFunction function,
            final NativeInstruction.Operation operation,
            final List<Diagnostic> diagnostics
    ) {
        final int arity = operation.operands().size();
        if (SEMANTIC_OPCODES.contains(operation.opcode()) && !function.requiresSemanticContext()) {
            error(diagnostics, function, operation.opcode() + " requires a semantic context");
        }
        switch (operation.opcode()) {
            case ADD, SUB, MUL -> requireBinaryNumeric(function, operation, true, diagnostics);
            case SDIV, UDIV, SREM, UREM -> {
                requireBinaryNumeric(function, operation, true, diagnostics);
                if (!function.requiresSemanticContext()) {
                    error(diagnostics, function,
                            operation.opcode() + " requires a semantic context for Java divide-by-zero dispatch");
                }
            }
            case BIT_AND, BIT_OR, BIT_XOR ->
                    requireBinaryNumeric(function, operation, true, diagnostics);
            case SHL, ASHR, LSHR -> requireShift(function, operation, diagnostics);
            case FADD, FSUB, FMUL, FDIV, FREM ->
                    requireBinaryNumeric(function, operation, false, diagnostics);
            case NEG, BIT_NOT -> requireUnarySameType(function, operation, true, diagnostics);
            case FNEG -> requireUnarySameType(function, operation, false, diagnostics);
            case ICMP_EQ, ICMP_NE -> requireEqualityComparison(function, operation, diagnostics);
            case ICMP_SLT, ICMP_SLE, ICMP_SGT, ICMP_SGE ->
                    requireComparison(function, operation, true, diagnostics);
            case FCMP_EQ, FCMP_NE, FCMP_LT, FCMP_LE, FCMP_GT, FCMP_GE ->
                    requireComparison(function, operation, false, diagnostics);
            case REF_CAST -> {
                if (arity != 1 || !operation.type().isReferenceLike()
                        || !operation.operands().get(0).type().isReferenceLike()) {
                    error(diagnostics, function,
                            "REF_CAST requires one reference-like operand and a reference-like result");
                }
            }
            case SELECT -> {
                if (arity != 3 || operation.operands().get(0).type() != NativeType.Primitive.I1
                        || !operation.type().equals(operation.operands().get(1).type())
                        || !operation.type().equals(operation.operands().get(2).type())) {
                    error(diagnostics, function, "SELECT requires i1 and two operands matching its result type");
                }
            }
            case STRING_CONSTANT -> {
                final NativeType.Reference stringType = new NativeType.Reference("java/lang/String", false);
                if (arity != 0 || !operation.type().equals(stringType)) {
                    error(diagnostics, function,
                            "STRING_CONSTANT requires no operands and returns a non-null java/lang/String reference");
                }
                if (!operation.attributes().containsKey("value")) {
                    error(diagnostics, function, "STRING_CONSTANT is missing attribute value");
                }
                if (!function.requiresSemanticContext()) {
                    error(diagnostics, function, "STRING_CONSTANT requires a semantic context");
                }
            }
            case MONITOR_ENTER, MONITOR_EXIT -> {
                if (arity != 1 || !operation.operands().get(0).type().isReferenceLike() || !operation.type().isVoid()) {
                    error(diagnostics, function, operation.opcode() + " requires one reference and a void result");
                }
            }
            case ARRAY_LENGTH -> {
                if (arity != 1 || !(operation.operands().get(0).type() instanceof NativeType.Array)
                        || operation.type() != NativeType.Primitive.I32) {
                    error(diagnostics, function, "ARRAY_LENGTH requires an array and returns i32");
                }
            }
            case ARRAY_STORE -> {
                if (arity != 3 || !(operation.operands().get(0).type() instanceof NativeType.Array array)
                        || operation.operands().get(1).type() != NativeType.Primitive.I32
                        || !array.componentType().equals(operation.operands().get(2).type())
                        || !operation.type().isVoid()) {
                    error(diagnostics, function, "ARRAY_STORE requires array, i32 index, matching value, and void result");
                }
            }
            case FIELD_SET -> {
                if ((arity != 1 && arity != 2) || !operation.type().isVoid()) {
                    error(diagnostics, function, "FIELD_SET is effect-only and requires a value and optional receiver");
                }
                requireAttributes(function, operation, diagnostics, "owner", "name", "descriptor");
                verifyFieldOperation(function, operation, true, diagnostics);
            }
            case JAVA_CALL -> {
                requireAttributes(function, operation, diagnostics,
                        "owner", "name", "descriptor", "invoke");
                verifyJavaCall(function, operation, diagnostics);
            }
            case DYNAMIC_BRIDGE -> {
                requireAttributes(function, operation, diagnostics, "bridge", "descriptor");
                verifyDynamicBridge(function, operation, diagnostics);
            }
            case FIELD_GET -> {
                if ((arity != 0 && arity != 1) || operation.type().isVoid()) {
                    error(diagnostics, function, "FIELD_GET requires an optional receiver and non-void result");
                }
                requireAttributes(function, operation, diagnostics, "owner", "name", "descriptor");
                verifyFieldOperation(function, operation, false, diagnostics);
            }
            case NEW_OBJECT -> {
                if (arity != 0 || !(operation.type() instanceof NativeType.Reference reference) || reference.nullable()) {
                    error(diagnostics, function, "NEW_OBJECT requires no operands and returns a non-null reference");
                }
                requireAttributes(function, operation, diagnostics, "type");
            }
            case INSTANCE_OF -> {
                if (arity != 1 || !operation.operands().get(0).type().isReferenceLike()
                        || operation.type() != NativeType.Primitive.I1) {
                    error(diagnostics, function, "INSTANCE_OF requires one reference and returns i1");
                }
                requireAttributes(function, operation, diagnostics, "type");
            }
            case CHECK_CAST -> {
                if (arity != 1 || !operation.operands().get(0).type().isReferenceLike()
                        || !operation.type().isReferenceLike()) {
                    error(diagnostics, function, "CHECK_CAST requires and returns a reference-like value");
                }
                requireAttributes(function, operation, diagnostics, "type");
            }
            case CONVERT -> {
                if (arity != 1 || operation.type().isVoid()
                        || !(operation.operands().get(0).type() instanceof NativeType.Primitive source)
                        || !(operation.type() instanceof NativeType.Primitive target)
                        || !source.isNumeric() || !target.isNumeric()) {
                    error(diagnostics, function, "CONVERT requires one numeric operand and numeric result");
                }
            }
            case ARRAY_NEW -> {
                if (arity == 0 || !(operation.type() instanceof NativeType.Array)
                        || operation.operands().stream().anyMatch(operand -> operand.type() != NativeType.Primitive.I32)) {
                    error(diagnostics, function, "ARRAY_NEW requires i32 dimensions and returns an array");
                }
            }
            case ARRAY_LOAD -> {
                if (arity != 2 || !(operation.operands().get(0).type() instanceof NativeType.Array array)
                        || operation.operands().get(1).type() != NativeType.Primitive.I32
                        || !array.componentType().equals(operation.type())) {
                    error(diagnostics, function, "ARRAY_LOAD requires array and i32 index and returns its component type");
                }
            }
            case CATCH_EXCEPTION -> {
                if (arity != 0 || !operation.type().isReferenceLike()) {
                    error(diagnostics, function, "CATCH_EXCEPTION requires no operands and returns a reference");
                }
                if (!function.requiresSemanticContext()) {
                    error(diagnostics, function, "CATCH_EXCEPTION requires a semantic context");
                }
            }
        }
    }

    private void requireShift(final NativeFunction function, final NativeInstruction.Operation operation,
                              final List<Diagnostic> diagnostics) {
        if (operation.operands().size() != 2
                || !operation.type().equals(operation.operands().get(0).type())
                || !(operation.type() instanceof NativeType.Primitive primitive)
                || !primitive.isInteger()
                || operation.operands().get(1).type() != NativeType.Primitive.I32) {
            error(diagnostics, function, operation.opcode()
                    + " requires an integer value and an i32 shift distance");
        }
    }

    private void requireEqualityComparison(
            final NativeFunction function,
            final NativeInstruction.Operation operation,
            final List<Diagnostic> diagnostics
    ) {
        if (operation.operands().size() != 2 || operation.type() != NativeType.Primitive.I1
                || !compatibleValueTypes(operation.operands().get(0).type(),
                        operation.operands().get(1).type())) {
            error(diagnostics, function, operation.opcode() + " requires two matching operands and returns i1");
            return;
        }
        final NativeType operandType = operation.operands().get(0).type();
        if (!(operandType instanceof NativeType.Primitive primitive && primitive.isInteger())
                && !operandType.isReferenceLike()) {
            error(diagnostics, function, operation.opcode() + " requires integer or reference operands");
        }
    }

    private void requireBinaryNumeric(final NativeFunction function, final NativeInstruction.Operation operation,
                                      final boolean integer, final List<Diagnostic> diagnostics) {
        if (operation.operands().size() != 2 || !operation.type().equals(operation.operands().get(0).type())
                || !operation.type().equals(operation.operands().get(1).type()) || !numeric(operation.type(), integer)) {
            error(diagnostics, function, operation.opcode() + " requires two matching "
                    + (integer ? "integer" : "floating-point") + " operands");
        }
    }

    private void requireUnarySameType(final NativeFunction function, final NativeInstruction.Operation operation,
                                      final boolean integer, final List<Diagnostic> diagnostics) {
        if (operation.operands().size() != 1 || !operation.type().equals(operation.operands().get(0).type())
                || !numeric(operation.type(), integer)) {
            error(diagnostics, function, operation.opcode() + " requires one matching numeric operand");
        }
    }

    private void requireComparison(final NativeFunction function, final NativeInstruction.Operation operation,
                                   final boolean integer, final List<Diagnostic> diagnostics) {
        if (operation.operands().size() != 2 || operation.type() != NativeType.Primitive.I1
                || !operation.operands().get(0).type().equals(operation.operands().get(1).type())
                || !numeric(operation.operands().get(0).type(), integer)) {
            error(diagnostics, function, operation.opcode() + " requires two matching operands and returns i1");
        }
    }

    private boolean numeric(final NativeType type, final boolean integer) {
        return type instanceof NativeType.Primitive primitive
                && (integer ? primitive.isInteger() : primitive.isFloatingPoint());
    }

    private boolean compatibleValueTypes(final NativeType left, final NativeType right) {
        return left.equals(right) || left.isReferenceLike() && right.isReferenceLike();
    }

    private void requireAttributes(final NativeFunction function, final NativeInstruction.Operation operation,
                                   final List<Diagnostic> diagnostics, final String... keys) {
        for (final String key : keys) {
            if (operation.attributes().getOrDefault(key, "").isBlank()) {
                error(diagnostics, function, operation.opcode() + " is missing attribute " + key);
            }
        }
    }

    private void verifyFieldOperation(
            final NativeFunction function,
            final NativeInstruction.Operation operation,
            final boolean setter,
            final List<Diagnostic> diagnostics
    ) {
        verifyOwner(function, operation, diagnostics);
        final NativeType fieldType;
        try {
            fieldType = JavaDescriptor.fieldType(operation.attributes().getOrDefault("descriptor", ""));
        } catch (IllegalArgumentException exception) {
            error(diagnostics, function, operation.opcode() + " has an invalid field descriptor");
            return;
        }
        final int arity = operation.operands().size();
        if (setter ? (arity != 1 && arity != 2) : (arity != 0 && arity != 1)) {
            return;
        }
        final boolean instance = setter ? arity == 2 : arity == 1;
        if (instance && !ownerCompatible(operation, operation.operands().get(0).type())) {
            error(diagnostics, function, operation.opcode()
                    + " instance receiver must match its declaring owner type");
        }
        final NativeType actual = setter
                ? operation.operands().get(arity - 1).type()
                : operation.type();
        if (!JavaDescriptor.compatible(fieldType, actual)) {
            error(diagnostics, function, operation.opcode() + " type does not match its field descriptor");
        }
    }

    private void verifyJavaCall(
            final NativeFunction function,
            final NativeInstruction.Operation operation,
            final List<Diagnostic> diagnostics
    ) {
        verifyOwner(function, operation, diagnostics);
        final String invoke = operation.attributes().getOrDefault("invoke", "").toLowerCase(java.util.Locale.ROOT);
        final boolean statical = invoke.equals("static");
        if (!(statical || invoke.equals("virtual") || invoke.equals("interface") || invoke.equals("special"))) {
            error(diagnostics, function, "JAVA_CALL has an invalid invoke kind: " + invoke);
            return;
        }
        verifyCallSignature(function, operation, statical ? 0 : 1, diagnostics);
    }

    private void verifyDynamicBridge(
            final NativeFunction function,
            final NativeInstruction.Operation operation,
            final List<Diagnostic> diagnostics
    ) {
        if ("java.helper.v1".equals(operation.attributes().get("bridge"))) {
            requireAttributes(function, operation, diagnostics,
                    "helper.owner", "helper.name", "helper.descriptor", "helper.invoke", "helper.kind");
            final String helperOwner = operation.attributes().getOrDefault("helper.owner", "");
            try {
                new NativeType.Reference(helperOwner, true);
            } catch (IllegalArgumentException exception) {
                error(diagnostics, function, "DYNAMIC_BRIDGE has an invalid helper.owner internal name");
            }
            final String helperName = operation.attributes().getOrDefault("helper.name", "");
            if (!helperName.matches("[A-Za-z_$][A-Za-z0-9_$]*") || helperName.startsWith("<")) {
                error(diagnostics, function, "DYNAMIC_BRIDGE has an invalid helper.name");
            }
            if (!"static".equals(operation.attributes().get("helper.invoke"))) {
                error(diagnostics, function, "DYNAMIC_BRIDGE java.helper.v1 must use helper.invoke=static");
            }
            if (!operation.attributes().getOrDefault("descriptor", "").equals(
                    operation.attributes().getOrDefault("helper.descriptor", ""))) {
                error(diagnostics, function,
                        "DYNAMIC_BRIDGE helper.descriptor must equal the operation descriptor");
            }
            final String kind = operation.attributes().getOrDefault("helper.kind", "");
            if (!Set.of("invokedynamic", "constant-dynamic", "method-handle",
                    "method-type-or-class", "caller-sensitive").contains(kind)) {
                error(diagnostics, function, "DYNAMIC_BRIDGE has an invalid helper.kind: " + kind);
            }
        }
        verifyCallSignature(function, operation, 0, diagnostics);
    }

    private void verifyOwner(
            final NativeFunction function,
            final NativeInstruction.Operation operation,
            final List<Diagnostic> diagnostics
    ) {
        try {
            new NativeType.Reference(operation.attributes().getOrDefault("owner", ""), true);
        } catch (IllegalArgumentException exception) {
            error(diagnostics, function, operation.opcode() + " has an invalid owner internal name");
        }
    }

    private void verifyCallSignature(
            final NativeFunction function,
            final NativeInstruction.Operation operation,
            final int receiverCount,
            final List<Diagnostic> diagnostics
    ) {
        final JavaDescriptor.Method descriptor;
        try {
            descriptor = JavaDescriptor.method(operation.attributes().getOrDefault("descriptor", ""));
        } catch (IllegalArgumentException exception) {
            error(diagnostics, function, operation.opcode() + " has an invalid method descriptor");
            return;
        }
        if (receiverCount == 1 && (operation.operands().isEmpty()
                || !ownerCompatible(operation, operation.operands().get(0).type()))) {
            error(diagnostics, function, operation.opcode()
                    + " instance receiver must match its declaring owner type");
            return;
        }
        if (operation.operands().size() != descriptor.parameters().size() + receiverCount) {
            error(diagnostics, function, operation.opcode() + " operand count does not match its method descriptor");
            return;
        }
        for (int index = 0; index < descriptor.parameters().size(); index++) {
            if (!JavaDescriptor.compatible(
                    descriptor.parameters().get(index),
                    operation.operands().get(index + receiverCount).type()
            )) {
                error(diagnostics, function, operation.opcode() + " operand " + index
                        + " does not match its method descriptor");
            }
        }
        if (!JavaDescriptor.compatible(descriptor.returnType(), operation.type())) {
            error(diagnostics, function, operation.opcode() + " result does not match its method descriptor");
        }
    }

    private boolean ownerCompatible(
            final NativeInstruction.Operation operation,
            final NativeType type
    ) {
        try {
            return JavaDescriptor.compatible(
                    new NativeType.Reference(operation.attributes().getOrDefault("owner", ""), true), type);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private void error(final List<Diagnostic> diagnostics, final NativeFunction function, final String message) {
        diagnostics.add(new Diagnostic(function.symbol(), message));
    }

    public record Diagnostic(String context, String message) {
        public Diagnostic {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(message, "message");
        }
    }

    public record Result(List<Diagnostic> diagnostics) {
        public Result {
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        }

        public boolean valid() {
            return diagnostics.isEmpty();
        }

        public String format() {
            if (valid()) {
                return "Native IR is valid";
            }
            return "Native IR verification failed:\n" + diagnostics.stream()
                    .map(diagnostic -> " - [" + diagnostic.context() + "] " + diagnostic.message())
                    .collect(java.util.stream.Collectors.joining("\n"));
        }
    }

    private record DefinitionSite(String blockId, int instructionIndex, boolean isParameter) {
        private DefinitionSite(final String blockId, final int instructionIndex) {
            this(blockId, instructionIndex, false);
        }

        private static DefinitionSite forParameter() {
            return new DefinitionSite("", -1, true);
        }
    }
}
