package dev.skidfuscator.obfuscator.nativebackend.lowering;

import org.mapleir.asm.MethodNode;
import org.mapleir.flowgraph.edges.FlowEdge;
import org.mapleir.flowgraph.edges.TryCatchEdge;
import org.mapleir.ir.cfg.BasicBlock;
import org.mapleir.ir.cfg.ControlFlowGraph;
import org.mapleir.ir.code.CodeUnit;
import org.mapleir.ir.code.Expr;
import org.mapleir.ir.code.Stmt;
import org.mapleir.ir.code.expr.VarExpr;
import org.mapleir.ir.code.expr.invoke.InvocationExpr;
import org.mapleir.ir.code.stmt.copy.AbstractCopyStmt;
import org.mapleir.ir.locals.Local;
import org.mapleir.ir.locals.impl.VersionedLocal;
import org.objectweb.asm.Type;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only proof that a constructor can be split immediately after its mandatory
 * {@code this(...)} or {@code super(...)} invocation.
 *
 * <p>This deliberately does not rewrite MapleIR.  It establishes the exact boundary that a
 * wrapper generator must preserve and rejects tails whose SSA live-ins cannot be reconstructed
 * from {@code this} and the original constructor parameters.  In particular, it never passes an
 * uninitialized JVM object reference through a native call.</p>
 */
public final class ConstructorTailAnalyzer {
    public Result analyze(final MethodNode method, final ControlFlowGraph cfg) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(cfg, "cfg");
        if (!"<init>".equals(method.getName())) {
            return Result.unsupported("method is not a constructor");
        }
        if (cfg.getMethodNode() != method) {
            return Result.unsupported("the supplied Maple graph belongs to another method");
        }
        if (cfg.getEntries().size() != 1) {
            return Result.unsupported("constructor graph must have exactly one entry");
        }

        final List<InitializationSite> sites = cfg.vertices().stream()
                .flatMap(block -> block.stream().map(stmt -> new BlockStatement(block, stmt)))
                .flatMap(value -> java.util.stream.StreamSupport.stream(
                        value.statement().enumerateWithSelf().spliterator(), false))
                .filter(InvocationExpr.class::isInstance)
                .map(InvocationExpr.class::cast)
                .filter(this::isConstructorInitialization)
                .filter(invocation -> isUninitializedThis(method, invocation))
                .map(invocation -> new InitializationSite(
                        invocation.getBlock(), invocation.getRootParent(), invocation))
                .toList();
        if (sites.size() != 1) {
            return Result.unsupported("expected exactly one mandatory this()/super() initialization call, found "
                    + sites.size());
        }

        final InitializationSite site = sites.get(0);
        final int statementIndex = site.block().indexOf(site.statement());
        if (statementIndex < 0) {
            return Result.unsupported("initialization call is detached from its containing block");
        }
        if (statementIndex + 1 >= site.block().size()) {
            return Result.unsupported("constructor tail begins in a successor block; finalized split boundary "
                    + "is not explicit");
        }

        final Set<BasicBlock> tailBlocks = reachableNormally(cfg, site.block());
        final Set<Local> allowedLiveIns = parameterLocals(method);
        final Map<Local, DefinitionSite> definitions = definitions(cfg);
        final Set<Local> illegalLiveIns = new LinkedHashSet<>();
        final Set<Local> usedLocals = new LinkedHashSet<>();
        for (final BasicBlock block : tailBlocks) {
            final int firstStatement = block == site.block() ? statementIndex + 1 : 0;
            for (int index = firstStatement; index < block.size(); index++) {
                for (final CodeUnit unit : block.get(index).enumerateWithSelf()) {
                    if (!(unit instanceof VarExpr variable)) continue;
                    if (unit == block.get(index)
                            || block.get(index) instanceof AbstractCopyStmt copy
                                && copy.getVariable() == variable) {
                        continue;
                    }
                    usedLocals.add(variable.getLocal());
                    final DefinitionSite definition = definitions.get(variable.getLocal());
                    final boolean definitionInPrefix = definition != null
                            && (definition.block() != site.block()
                                ? !tailBlocks.contains(definition.block())
                                : definition.statementIndex() <= statementIndex);
                    if (definitionInPrefix
                            && !allowedLiveIns.contains(parameterIdentity(variable.getLocal()))) {
                        illegalLiveIns.add(variable.getLocal());
                    }
                }
            }
        }
        for (final Map.Entry<Local, DefinitionSite> entry : definitions.entrySet()) {
            final DefinitionSite definition = entry.getValue();
            if (definition.block() == site.block()
                    && definition.statementIndex() <= statementIndex
                    && usedLocals.contains(entry.getKey())
                    && !allowedLiveIns.contains(parameterIdentity(entry.getKey()))) {
                illegalLiveIns.add(entry.getKey());
            }
        }
        if (!illegalLiveIns.isEmpty()) {
            return Result.unsupported("constructor tail depends on prefix SSA values that cannot be marshalled: "
                    + illegalLiveIns.stream().sorted().toList());
        }

        final List<String> orderedBlocks = tailBlocks.stream()
                .sorted(Comparator.comparingInt(BasicBlock::getNumericId))
                .map(block -> "b" + block.getNumericId())
                .toList();
        return new Result(true, "", "b" + site.block().getNumericId(), statementIndex + 1,
                orderedBlocks, helperDescriptor(method), site.invocation().getOwner(),
                site.invocation().getDesc());
    }

    private boolean isConstructorInitialization(final InvocationExpr invocation) {
        return invocation.getCallType() == InvocationExpr.CallType.SPECIAL
                && "<init>".equals(invocation.getName());
    }

    private boolean isUninitializedThis(final MethodNode method, final InvocationExpr invocation) {
        final Expr[] arguments = invocation.getArgumentExprs();
        if (arguments.length == 0 || !(arguments[0] instanceof VarExpr receiver)) return false;
        final Local local = receiver.getLocal();
        final boolean zeroVersion = !(local instanceof VersionedLocal versioned)
                || versioned.getSubscript() == 0;
        if (local.isStack() || local.getIndex() != 0 || !zeroVersion) return false;
        final String owner = method.owner.getName();
        final String superName = method.owner.node.superName;
        return invocation.getOwner().equals(owner)
                || superName != null && invocation.getOwner().equals(superName);
    }

    private Set<BasicBlock> reachableNormally(final ControlFlowGraph cfg, final BasicBlock start) {
        final Set<BasicBlock> reachable = Collections.newSetFromMap(new IdentityHashMap<>());
        final Deque<BasicBlock> work = new ArrayDeque<>();
        work.add(start);
        while (!work.isEmpty()) {
            final BasicBlock block = work.removeFirst();
            if (!reachable.add(block)) continue;
            cfg.getEdges(block).stream()
                    .filter(edge -> !(edge instanceof TryCatchEdge<?>))
                    .map(FlowEdge::dst)
                    .forEach(work::addLast);
        }
        return reachable;
    }

    private Map<Local, DefinitionSite> definitions(final ControlFlowGraph cfg) {
        final Map<Local, DefinitionSite> definitions = new HashMap<>();
        for (final BasicBlock block : cfg.vertices()) {
            for (int index = 0; index < block.size(); index++) {
                final Stmt statement = block.get(index);
                if (statement instanceof AbstractCopyStmt copy && !copy.isSynthetic()) {
                    definitions.put(copy.getVariable().getLocal(), new DefinitionSite(block, index));
                }
            }
        }
        return definitions;
    }

    private Set<Local> parameterLocals(final MethodNode method) {
        final Set<Local> parameters = new LinkedHashSet<>();
        int slot = 0;
        if (!method.isStatic()) parameters.add(new VersionedLocal(slot++, 0, false));
        for (final Type argument : Type.getArgumentTypes(method.getDesc())) {
            parameters.add(new VersionedLocal(slot, 0, false));
            slot += argument.getSize();
        }
        return parameters;
    }

    private Local parameterIdentity(final Local local) {
        return local instanceof VersionedLocal versioned
                ? new VersionedLocal(versioned.getIndex(), 0, versioned.isStack())
                : local;
    }

    /** Native helper receives initialized {@code this} followed by original constructor args. */
    private String helperDescriptor(final MethodNode method) {
        final Type[] original = Type.getArgumentTypes(method.getDesc());
        final Type[] helper = new Type[original.length + 1];
        helper[0] = Type.getObjectType(method.owner.getName());
        System.arraycopy(original, 0, helper, 1, original.length);
        return Type.getMethodDescriptor(Type.VOID_TYPE, helper);
    }

    public record Result(
            boolean supported,
            String reason,
            String boundaryBlock,
            int firstTailStatement,
            List<String> tailBlocks,
            String helperDescriptor,
            String initializationOwner,
            String initializationDescriptor
    ) {
        public Result {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(boundaryBlock, "boundaryBlock");
            tailBlocks = List.copyOf(Objects.requireNonNull(tailBlocks, "tailBlocks"));
            Objects.requireNonNull(helperDescriptor, "helperDescriptor");
            Objects.requireNonNull(initializationOwner, "initializationOwner");
            Objects.requireNonNull(initializationDescriptor, "initializationDescriptor");
            if (supported && (!reason.isEmpty() || boundaryBlock.isBlank()
                    || firstTailStatement < 0 || tailBlocks.isEmpty() || helperDescriptor.isBlank()
                    || initializationOwner.isBlank() || initializationDescriptor.isBlank())) {
                throw new IllegalArgumentException("Supported constructor tail requires a complete split plan");
            }
            if (!supported && reason.isBlank()) {
                throw new IllegalArgumentException("Unsupported constructor tail requires a reason");
            }
        }

        private static Result unsupported(final String reason) {
            return new Result(false, reason, "", -1, List.of(), "", "", "");
        }
    }

    private record InitializationSite(BasicBlock block, Stmt statement, InvocationExpr invocation) {
    }

    private record BlockStatement(BasicBlock block, Stmt statement) {
    }

    private record DefinitionSite(BasicBlock block, int statementIndex) {
    }
}
