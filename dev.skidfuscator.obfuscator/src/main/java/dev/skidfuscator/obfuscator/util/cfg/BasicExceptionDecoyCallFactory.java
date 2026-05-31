package dev.skidfuscator.obfuscator.util.cfg;

import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.skidasm.SkidGroup;
import dev.skidfuscator.obfuscator.skidasm.SkidMethodNode;
import dev.skidfuscator.obfuscator.skidasm.cfg.SkidBlock;
import dev.skidfuscator.obfuscator.skidasm.cfg.SkidControlFlowGraph;
import dev.skidfuscator.obfuscator.skidasm.expr.SkidConstantExpr;
import dev.skidfuscator.obfuscator.transform.impl.flow.exception.BasicExceptionConfig;
import dev.skidfuscator.obfuscator.transform.impl.flow.exception.BasicExceptionDecoyScope;
import dev.skidfuscator.obfuscator.util.RandomUtil;
import org.mapleir.asm.ClassNode;
import org.mapleir.asm.MethodNode;
import org.mapleir.ir.cfg.ControlFlowGraph;
import org.mapleir.ir.code.Expr;
import org.mapleir.ir.code.expr.ConstantExpr;
import org.mapleir.ir.code.expr.VarExpr;
import org.mapleir.ir.code.expr.invoke.InvocationExpr;
import org.mapleir.ir.code.expr.invoke.StaticInvocationExpr;
import org.mapleir.ir.code.expr.invoke.VirtualInvocationExpr;
import org.mapleir.ir.code.stmt.PopStmt;
import org.mapleir.ir.code.stmt.ReturnStmt;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.stream.Collectors;

public final class BasicExceptionDecoyCallFactory {
    private static final Map<Skidfuscator, List<DecoyTarget>> TARGET_CACHE = new WeakHashMap<>();

    private BasicExceptionDecoyCallFactory() {
    }

    public static Optional<SkidBlock> create(final ControlFlowGraph cfg) {
        if (!(cfg instanceof SkidControlFlowGraph)) {
            return Optional.empty();
        }

        if (!(cfg.getMethodNode() instanceof SkidMethodNode)) {
            return Optional.empty();
        }

        final SkidControlFlowGraph skidCfg = (SkidControlFlowGraph) cfg;
        final SkidMethodNode caller = (SkidMethodNode) cfg.getMethodNode();

        final Skidfuscator skidfuscator = caller.getSkidfuscator();
        if (skidfuscator == null || skidfuscator.getTsConfig() == null) {
            return Optional.empty();
        }

        final BasicExceptionConfig config = new BasicExceptionConfig(
                skidfuscator.getTsConfig(),
                "flowException"
        );

        if (!config.isDecoyCallsEnabled()) {
            return Optional.empty();
        }

        final DecoyTarget target = selectTarget(skidfuscator, caller, config);
        if (target == null) {
            return Optional.empty();
        }

        final Expr invocation = target.createInvocation(skidCfg, caller);
        if (invocation == null) {
            return Optional.empty();
        }

        final SkidBlock block = new SkidBlock(cfg);
        block.add(new PopStmt(invocation));
        block.add(createReturn(caller));
        cfg.addVertex(block);

        return Optional.of(block);
    }

    private static DecoyTarget selectTarget(
            final Skidfuscator skidfuscator,
            final SkidMethodNode caller,
            final BasicExceptionConfig config
    ) {
        final List<DecoyTarget> targets = TARGET_CACHE.computeIfAbsent(
                skidfuscator,
                ignored -> collectTargets(skidfuscator, config)
        );

        if (targets.isEmpty()) {
            return null;
        }

        final List<DecoyTarget> callable = targets
                .stream()
                .filter(target -> target.canCallFrom(caller))
                .collect(Collectors.toList());

        if (callable.isEmpty()) {
            return null;
        }

        final List<DecoyTarget> seeded = callable
                .stream()
                .filter(DecoyTarget::hasSeedArgument)
                .collect(Collectors.toList());

        final List<DecoyTarget> pool = seeded.isEmpty() ? callable : seeded;
        return pool.get(RandomUtil.nextInt(pool.size()));
    }

    private static List<DecoyTarget> collectTargets(
            final Skidfuscator skidfuscator,
            final BasicExceptionConfig config
    ) {
        final Map<String, DecoyTarget> targets = new LinkedHashMap<>();
        final int maxArgs = Math.max(0, config.getDecoyCallMaxArgs());
        final boolean includeExempt = config.getDecoyCallScope() == BasicExceptionDecoyScope.APPLICATION_AND_EXEMPT;

        if (skidfuscator.getHierarchy() != null) {
            for (ClassNode classNode : skidfuscator.getHierarchy().getClasses()) {
                collectClassTargets(skidfuscator, classNode, targets, maxArgs, includeExempt, false);
            }
        }

        if (config.isDecoyCallLibrariesEnabled() && skidfuscator.getClassSource() != null) {
            for (ClassNode classNode : skidfuscator.getClassSource().iterateWithLibraries(true)) {
                if (skidfuscator.getClassSource().isApplicationClass(classNode.getName())) {
                    continue;
                }

                collectClassTargets(skidfuscator, classNode, targets, maxArgs, true, true);
            }
        }

        return new ArrayList<>(targets.values());
    }

    private static void collectClassTargets(
            final Skidfuscator skidfuscator,
            final ClassNode classNode,
            final Map<String, DecoyTarget> targets,
            final int maxArgs,
            final boolean includeExempt,
            final boolean library
    ) {
        if (classNode == null || classNode.node == null) {
            return;
        }

        if (!library
                && !includeExempt
                && skidfuscator.getExemptAnalysis().isExempt(classNode)) {
            return;
        }

        for (MethodNode methodNode : classNode.getMethods()) {
            if (!isCandidateMethod(skidfuscator, methodNode, maxArgs, includeExempt, library)) {
                continue;
            }

            final DecoyTarget target = new DecoyTarget(methodNode, classNode, library);
            targets.putIfAbsent(target.key(), target);
        }
    }

    private static boolean isCandidateMethod(
            final Skidfuscator skidfuscator,
            final MethodNode methodNode,
            final int maxArgs,
            final boolean includeExempt,
            final boolean library
    ) {
        if (methodNode == null
                || methodNode.node == null
                || methodNode.owner == null
                || methodNode.isAbstract()
                || methodNode.isNative()
                || methodNode.isBridge()
                || methodNode.isInit()
                || methodNode.isClinit()) {
            return false;
        }

        if (methodNode.getName().contains("<")) {
            return false;
        }

        if (!library
                && !includeExempt
                && skidfuscator.getExemptAnalysis().isExempt(methodNode)) {
            return false;
        }

        final Type[] argumentTypes = Type.getArgumentTypes(methodNode.getDesc());
        if (argumentTypes.length > maxArgs) {
            return false;
        }

        for (Type argumentType : argumentTypes) {
            if (argumentType.getSort() == Type.METHOD) {
                return false;
            }
        }

        return true;
    }

    private static ReturnStmt createReturn(final SkidMethodNode caller) {
        final Type returnType = Type.getReturnType(caller.getDesc());
        if (returnType.equals(Type.VOID_TYPE)) {
            return new ReturnStmt();
        }

        return new ReturnStmt(returnType, randomValue(returnType, false));
    }

    private static Expr randomValue(final Type type, final boolean seed) {
        if (seed) {
            return new SkidConstantExpr(RandomUtil.nextInt(), Type.INT_TYPE);
        }

        switch (type.getSort()) {
            case Type.BOOLEAN:
                return new ConstantExpr(RandomUtil.nextBoolean() ? 1 : 0, Type.INT_TYPE);
            case Type.BYTE:
            case Type.CHAR:
            case Type.SHORT:
            case Type.INT:
                return new ConstantExpr(RandomUtil.nextInt(), Type.INT_TYPE);
            case Type.FLOAT:
                return new ConstantExpr((float) RandomUtil.nextInt(), Type.FLOAT_TYPE);
            case Type.LONG:
                return new ConstantExpr(RandomUtil.nextLong(), Type.LONG_TYPE);
            case Type.DOUBLE:
                return new ConstantExpr((double) RandomUtil.nextInt(), Type.DOUBLE_TYPE);
            case Type.ARRAY:
            case Type.OBJECT:
                return new ConstantExpr(null, type);
            default:
                throw new IllegalArgumentException("Unsupported decoy type: " + type);
        }
    }

    private static String packageName(final String owner) {
        final int index = owner.lastIndexOf('/');
        return index < 0 ? "" : owner.substring(0, index);
    }

    private static boolean samePackage(final ClassNode left, final ClassNode right) {
        return packageName(left.getName()).equals(packageName(right.getName()));
    }

    private static final class DecoyTarget {
        private final MethodNode methodNode;
        private final ClassNode owner;
        private final boolean library;
        private final Type[] argumentTypes;
        private final int seedArgumentIndex;

        private DecoyTarget(final MethodNode methodNode, final ClassNode owner, final boolean library) {
            this.methodNode = methodNode;
            this.owner = owner;
            this.library = library;
            this.argumentTypes = Type.getArgumentTypes(methodNode.getDesc());
            this.seedArgumentIndex = computeSeedArgumentIndex(methodNode);
        }

        private String key() {
            return methodNode.getOwner() + "#" + methodNode.getName() + methodNode.getDesc();
        }

        private boolean hasSeedArgument() {
            return seedArgumentIndex >= 0;
        }

        private boolean canCallFrom(final SkidMethodNode caller) {
            if (caller == methodNode
                    || caller.getOwner().equals(methodNode.getOwner())
                    && caller.getName().equals(methodNode.getName())
                    && caller.getDesc().equals(methodNode.getDesc())) {
                return false;
            }

            if (!methodNode.isStatic()) {
                return !caller.isStatic()
                        && !caller.isInit()
                        && !methodNode.isPrivate()
                        && !owner.isInterface()
                        && caller.getOwner().equals(methodNode.getOwner());
            }

            if (caller.getOwner().equals(methodNode.getOwner())) {
                return true;
            }

            if (methodNode.isPrivate()) {
                return false;
            }

            if (!owner.isPublic() && !samePackage(caller.getParent(), owner)) {
                return false;
            }

            if (methodNode.isPublic()) {
                return true;
            }

            if (methodNode.isProtected()) {
                return samePackage(caller.getParent(), owner);
            }

            return samePackage(caller.getParent(), owner) && !library;
        }

        private Expr createInvocation(final SkidControlFlowGraph cfg, final SkidMethodNode caller) {
            final Expr[] args = new Expr[argumentTypes.length + (methodNode.isStatic() ? 0 : 1)];
            int offset = 0;

            if (!methodNode.isStatic()) {
                args[offset++] = new VarExpr(
                        cfg.getSelfLocal(),
                        Type.getObjectType(methodNode.getOwner())
                );
            }

            for (int i = 0; i < argumentTypes.length; i++) {
                args[offset + i] = randomValue(argumentTypes[i], i == seedArgumentIndex);
            }

            if (methodNode.isStatic()) {
                return new StaticInvocationExpr(
                        owner.isInterface()
                                ? InvocationExpr.CallType.INTERFACE
                                : InvocationExpr.CallType.STATIC,
                        args,
                        methodNode.getOwner(),
                        methodNode.getName(),
                        methodNode.getDesc()
                );
            }

            return new VirtualInvocationExpr(
                    InvocationExpr.CallType.VIRTUAL,
                    args,
                    methodNode.getOwner(),
                    methodNode.getName(),
                    methodNode.getDesc()
            );
        }

        private static int computeSeedArgumentIndex(final MethodNode methodNode) {
            if (!(methodNode instanceof SkidMethodNode)) {
                return -1;
            }

            final SkidMethodNode skidMethodNode = (SkidMethodNode) methodNode;
            final SkidGroup group = skidMethodNode.getGroup();
            if (group == null || !group.isInjectedMethodPredicate()) {
                return -1;
            }

            int localIndex = methodNode.isStatic() ? 0 : 1;
            final Type[] arguments = Type.getArgumentTypes(methodNode.getDesc());
            for (int i = 0; i < arguments.length; i++) {
                if (localIndex == group.getStackHeight()) {
                    return i;
                }
                localIndex += arguments[i].getSize();
            }

            return -1;
        }
    }
}
