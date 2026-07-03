package dev.skidfuscator.obfuscator.predicate.renderer;

import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.event.EventPriority;
import dev.skidfuscator.obfuscator.event.annotation.Listen;
import dev.skidfuscator.obfuscator.event.impl.transform.clazz.InitClassTransformEvent;
import dev.skidfuscator.obfuscator.event.impl.transform.group.InitGroupTransformEvent;
import dev.skidfuscator.obfuscator.event.impl.transform.method.InitMethodTransformEvent;
import dev.skidfuscator.obfuscator.event.impl.transform.method.PostMethodTransformEvent;
import dev.skidfuscator.obfuscator.event.impl.transform.skid.InitSkidTransformEvent;
import dev.skidfuscator.obfuscator.hierarchy.matching.ClassMethodHash;
import dev.skidfuscator.obfuscator.number.NumberManager;
import dev.skidfuscator.obfuscator.number.hash.impl.BitwiseHashTransformer;
import dev.skidfuscator.obfuscator.number.hash.impl.LegacyHashTransformer;
import dev.skidfuscator.obfuscator.number.pure.VmHashTransformer;
import dev.skidfuscator.obfuscator.predicate.factory.PredicateFlowGetter;
import dev.skidfuscator.obfuscator.predicate.factory.PredicateFlowSetter;
import dev.skidfuscator.obfuscator.predicate.opaque.BlockOpaquePredicate;
import dev.skidfuscator.obfuscator.predicate.opaque.ClassOpaquePredicate;
import dev.skidfuscator.obfuscator.predicate.opaque.MethodOpaquePredicate;
import dev.skidfuscator.obfuscator.predicate.renderer.impl.ConditionalJumpRenderer;
import dev.skidfuscator.obfuscator.predicate.renderer.impl.ExceptionRenderer;
import dev.skidfuscator.obfuscator.predicate.renderer.impl.SwitchJumpRenderer;
import dev.skidfuscator.obfuscator.predicate.renderer.impl.UnconditionalJumpRenderer;
import dev.skidfuscator.obfuscator.skidasm.*;
import dev.skidfuscator.obfuscator.skidasm.builder.SkidClassNodeBuilder;
import dev.skidfuscator.obfuscator.skidasm.cfg.SkidBlock;
import dev.skidfuscator.obfuscator.skidasm.cfg.SkidControlFlowGraph;
import dev.skidfuscator.obfuscator.skidasm.expr.SkidIntegerParseStaticInvocationExpr;
import dev.skidfuscator.obfuscator.skidasm.fake.FakeArithmeticExpr;
import dev.skidfuscator.obfuscator.skidasm.fake.FakeBlock;
import dev.skidfuscator.obfuscator.skidasm.fake.FakeConditionalJumpStmt;
import dev.skidfuscator.obfuscator.skidasm.fake.FakeUnconditionalJumpStmt;
import dev.skidfuscator.obfuscator.skidasm.stmt.SkidCopyVarStmt;
import dev.skidfuscator.obfuscator.skidasm.stmt.SkidSwitchStmt;
import dev.skidfuscator.obfuscator.transform.AbstractTransformer;
import dev.skidfuscator.obfuscator.transform.Transformer;
import dev.skidfuscator.obfuscator.transform.impl.flow.FlowFactoryMakerTransformer;
import dev.skidfuscator.obfuscator.util.OpcodeUtil;
import dev.skidfuscator.obfuscator.util.RandomUtil;
import dev.skidfuscator.obfuscator.util.cfg.Blocks;
import dev.skidfuscator.obfuscator.util.misc.Parameter;
import org.mapleir.asm.MethodNode;
import org.mapleir.flowgraph.ExceptionRange;
import org.mapleir.flowgraph.edges.*;
import org.mapleir.ir.cfg.BasicBlock;
import org.mapleir.ir.cfg.ControlFlowGraph;
import org.mapleir.ir.code.Expr;
import org.mapleir.ir.code.Stmt;
import org.mapleir.ir.code.expr.ArithmeticExpr;
import org.mapleir.ir.code.expr.CastExpr;
import org.mapleir.ir.code.expr.ConstantExpr;
import org.mapleir.ir.code.expr.FieldLoadExpr;
import org.mapleir.ir.code.expr.VarExpr;
import org.mapleir.ir.code.expr.invoke.*;
import org.mapleir.ir.code.stmt.*;
import org.mapleir.ir.code.stmt.copy.CopyVarStmt;
import org.mapleir.ir.locals.Local;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.topdank.byteengineer.commons.data.JarClassData;

import java.util.*;
import java.util.stream.Collectors;

    public class IntegerBlockPredicateRenderer extends AbstractTransformer {
    private static final String DYNAMIC_CLASS_PREDICATES_KEY = "dynamicClassPredicates";

    public IntegerBlockPredicateRenderer(Skidfuscator skidfuscator, List<Transformer> children) {
        super(skidfuscator,"Interprocedural Predicate", children);
    }

    public static boolean DEBUG = false;

    @Listen(EventPriority.LOWEST)
    void handle(final InitSkidTransformEvent event) {
        final String factoryName = RandomUtil.randomAlphabeticalString(16) + "/" + RandomUtil.randomAlphabeticalString(16);
        final SkidClassNode factory = new SkidClassNodeBuilder(skidfuscator)
                .name(factoryName)
                .access(Opcodes.ACC_PUBLIC)
                .phantom(true)
                .virtual(true)
                .build();

        skidfuscator.setFactoryNode(factory);
        /*skidfuscator.getClassRemapper().add(
                Type.getObjectType(factory.getName()).getInternalName(),
                RandomUtil.randomAlphabeticalString(16)
        );*/
        skidfuscator
                .getJarContents()
                .getClassContents()
                .add(
                        new JarClassData(
                                factoryName + ".class",
                                factory.toByteArray(),
                                factory
                        )
                );

        skidfuscator.setLegacyHasher(new LegacyHashTransformer(skidfuscator));
        skidfuscator.setBitwiseHasher(new BitwiseHashTransformer(skidfuscator));

        if (skidfuscator.getVmHasher() != null) {
            throw new IllegalStateException("VmHasher is already initialized");
        }
        skidfuscator.setVmHasher(new BitwiseHashTransformer(skidfuscator));
    }

    /**
     * Method called when the class methods are iterated over and initialized.
     * In this we'll set the flow obfuscation opaque predicate getter and setter.
     *
     * @param event Method initializer event
     */
    @Listen(EventPriority.LOWEST)
    void handle(final InitMethodTransformEvent event) {
        final SkidMethodNode methodNode = event.getMethodNode();
        final BlockOpaquePredicate flowPredicate = methodNode.getFlowPredicate();

        if (methodNode.getName().equals("lambda$null$5") && methodNode.owner.getName().contains("PlayerListener")) {
            System.out.println(methodNode.getCfg().toString());
        }

        /*
         * This local is the initial local which stores the opaque predicate.
         * This will constantly be called and will perpetually store the
         * opaque predicate's value which changes each block.
         *
         * The reason for the +3 is simple:
         * +1 -> Get the next local
         * +2 -> Get the next long local
         * +3 -> Safety net to allow for "long" to be used (I don't really know
         *       this fixed all my issues)
         *
         * seed.wide: the flow local itself carries a 64-bit long, so it occupies
         * TWO slots (S, S+1). At the legacy +3 placement its low half (+3) lands
         * in the "+2 long scratch" slot's high half (a long scratch at +2 spans
         * +2,+3), producing overlapping longs -> VerifyError "Bad local variable
         * type". Under wide we move it one slot up (+4 so it spans +4,+5, clear of
         * the +2,+3 scratch) and explicitly reserve its high half so maxLocals
         * (which LocalsPool tracks as the highest INDEX, not index+size) reflects
         * the long's full 2-slot footprint and later index-relative allocations
         * cannot reuse it.
         */
        final boolean seedWideLocal = skidfuscator.getConfig().isSeedWide();
        final int flowLocalSlot = methodNode.getCfg().getLocals().getMaxLocals()
                + (seedWideLocal ? 4 : 3);
        final Local local = methodNode
                .getCfg()
                .getLocals()
                .get(flowLocalSlot);
        if (seedWideLocal) {
            // Touch the long's high half so updateMaxs records it as occupied.
            methodNode.getCfg().getLocals().get(flowLocalSlot + 1);
        }
        local.setFlag(SkidBlock.FLAG_PROXY, true);
        methodNode.getEntryBlock();

        /*
         * The getter right now is quite simple:
         *
         * [if] the block is exempted (eg: <init> method calls before super())
         * -->  Return a constant expression with the predicate
         *
         * [else]
         * -->  Return a variable expression which calls the local storing the
         *      predicate
         */
        if (skidfuscator.getConfig().isSeedWide()) {
            /*
             * seed.wide: the flow local carries a 64-bit seed. The default getter
             * narrows it to the (int) low-32 projection (L2I) so every legacy
             * consumer behaves exactly as before, while getWide() exposes the full
             * long for the guard. The setter stores the long.
             */
            flowPredicate.setGetter(new PredicateFlowGetter() {
                @Override
                public Expr get(final BasicBlock block) {
                    if (block.isFlagSet(SkidBlock.FLAG_NO_OPAQUE)) {
                        return new ConstantExpr(flowPredicate.get((SkidBlock) block), Type.INT_TYPE);
                    }
                    return new CastExpr(new VarExpr(local, Type.LONG_TYPE), Type.INT_TYPE);
                }

                @Override
                public Expr getWide(final BasicBlock block) {
                    if (block.isFlagSet(SkidBlock.FLAG_NO_OPAQUE)) {
                        return new ConstantExpr(flowPredicate.getLong((SkidBlock) block), Type.LONG_TYPE);
                    }
                    return new VarExpr(local, Type.LONG_TYPE);
                }
            });

            flowPredicate.setSetter(expr -> new CopyVarStmt(new VarExpr(local, Type.LONG_TYPE), expr));
        } else {
            flowPredicate.setGetter(block -> {
                if (block.isFlagSet(SkidBlock.FLAG_NO_OPAQUE)) {
                    return new ConstantExpr(flowPredicate.get((SkidBlock) block), Type.INT_TYPE);
                }

                return new VarExpr(local, Type.INT_TYPE);
            });

            flowPredicate.setSetter(expr -> new CopyVarStmt(new VarExpr(local, Type.INT_TYPE), expr));
        }

        final MethodOpaquePredicate methodPredicate = methodNode.getPredicate();

        if (methodPredicate == null)
            return;

        methodPredicate.setGetter(vertex -> {
            final SkidMethodNode skidMethodNode = (SkidMethodNode) vertex.cfg.getMethodNode();
            final SkidClassNode skidClassNode = (SkidClassNode) skidMethodNode.owner;

            final ClassOpaquePredicate classPredicate = skidMethodNode.isStatic()
                            ? skidMethodNode.getParent().getStaticPredicate()
                            : skidMethodNode.getParent().getClassPredicate();
            int seed;
            PredicateFlowGetter expr;
            if (skidMethodNode.isClinit() || skidMethodNode.isInit()) {
                final int randomSeed = skidClassNode.getRandomInt();
                seed = randomSeed;

                expr = FlowFactoryMakerTransformer.materializeSeedGetter(
                        skidMethodNode.getSkidfuscator(),
                        skidClassNode,
                        randomSeed,
                        vertex1 -> new SkidIntegerParseStaticInvocationExpr(randomSeed)
                );
            } else {
                seed = classPredicate.get();
                expr = classPredicate.getGetter();
            }

            return NumberManager.encrypt(
                    methodPredicate.getPrivate(),
                    seed,
                    vertex,
                    expr
            );
        });
    }

    /**
     * This listener handles every class before they are transformed. The objective
     * here is to prepare any sort of opaque predicate stuff before it is used by
     * the transformers.
     *
     * @param event InitClassTransformEvent event containing any necessary ref
     */
    @Listen(EventPriority.HIGHEST)
    void handle(final InitClassTransformEvent event) {
        final SkidClassNode classNode = event.getClassNode();

        final ClassOpaquePredicate clazzInstancePredicate = classNode.getClassPredicate();
        final ClassOpaquePredicate clazzStaticPredicate = classNode.getStaticPredicate();

        if (getConfig().getBoolean(DYNAMIC_CLASS_PREDICATES_KEY, false)
                && !classNode.isInterface()
                && !classNode.isAnnotation()) {
            createDynamicClassPredicate(classNode, clazzInstancePredicate);
            createDynamicClassPredicate(classNode, clazzStaticPredicate);
            return;
        }

        /*
         * Here the getter to access the value is a constant expression loading the
         * constant expr.
         *
         * The setter, on the other hand, throws an exception as it expresses a risk
         * that should be circumvented.
         */
        clazzInstancePredicate.setGetter(vertex -> {
            // TODO: Do class instance opaque predicates
            return new ConstantExpr(
                    clazzInstancePredicate.get(),
                    Type.INT_TYPE
            );
        });
        clazzInstancePredicate.setSetter(PopStmt::new);

        /*
         * Both methods down below are designed to create a specific way to access
         * a STATIC class opaque predicate. To ensure the design is as compact and
         * feasible as humanly possible, we need to exempt some scenarios:
         *
         * if no methods are static             -> access directly via constant expression
         * if class is interface or annotation  -> access directly via constant expression
         * else                                 -> access by loading the value of a field
         */
        clazzStaticPredicate.setGetter(vertex -> {
            // TODO: Do class instance opaque predicates
            return new ConstantExpr(
                    clazzStaticPredicate.get(),
                    Type.INT_TYPE
            );
        });
        //throw new IllegalStateException("Cannot set value for a constant getter");
        clazzStaticPredicate.setSetter(PopStmt::new);
    }

    private void createDynamicClassPredicate(final SkidClassNode classNode,
                                             final ClassOpaquePredicate predicate) {
        final SkidFieldNode staticFieldNode = classNode.createField()
                .access(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)
                .name(RandomUtil.randomIsoString(10))
                .desc("I")
                .value(predicate.get())
                .build();

        predicate.setGetter(vertex -> new FieldLoadExpr(
                null,
                classNode.node.name,
                staticFieldNode.node.name,
                staticFieldNode.node.desc,
                true
        ));

        predicate.setSetter(expr -> new FieldStoreStmt(
                null,
                expr,
                classNode.node.name,
                staticFieldNode.node.name,
                staticFieldNode.node.desc,
                true
        ));
    }


    @Listen
    void handle(final PostMethodTransformEvent event) {
        final SkidMethodNode methodNode = event.getMethodNode();
        final ControlFlowGraph cfg = methodNode.getCfg();
        final BasicBlock entryPoint = methodNode.getEntryBlock();
        final SkidBlock seedEntry = (SkidBlock) entryPoint;
        try {
            cfg.recomputeEdges();
        } catch (RuntimeException ex) {
            // Degenerate input (e.g. a char-vs-Null comparison in mapwriter MwChunk)
            // makes ConditionalJumpStmt#toOpcode throw an illegal-binop error. Match
            // the warn-and-skip every other recomputeEdges() call site already uses
            // rather than letting it bubble up as a raw stack trace through the EventBus.
            Skidfuscator.LOGGER.warn(
                    "Skipping CFG edge recomputation for "
                            + methodNode.getOwner() + "#"
                            + methodNode.getName() + methodNode.getDesc()
                            + ": " + ex.getMessage()
            );
            return;
        }

        verifyWithoutDump(event, methodNode, cfg, "pre-render");

        /*
         *    ____     __
         *   / __/__  / /_______ __
         *  / _// _ \/ __/ __/ // /
         * /___/_//_/\__/_/  \_, /
         *                  /___/
         */
        /*
         * Create a stack local to temporarily store the seed getter
         */
        final MethodOpaquePredicate predicate = methodNode.getPredicate();
        PredicateFlowGetter getter = predicate.getGetter();

        assert getter != null : String.format(
                "Getter for method %s is null!",
                methodNode.getName()
        );

        PredicateFlowGetter localGetterT = methodNode.getFlowPredicate().getGetter();
        PredicateFlowSetter localSetterT = methodNode.getFlowPredicate().getSetter();

        // TODO: Figure out why this is happening too
        assert localGetterT != null : "Local getter for flow is absent";
        assert localSetterT != null : "Local setter for flow is absent";

        final PredicateFlowGetter localGetter = localGetterT;
        final PredicateFlowSetter localSetter = localSetterT;

        /*
         * Here we transition the method
         */
        final boolean seedWide = skidfuscator.getConfig().isSeedWide();
        final Expr loadedChanged = seedWide
                /*
                 * Wide entry init: seed the long flow local with P_long(entry). The
                 * method-seed getter's getWide() evaluates to getPrivateLong() at
                 * runtime, so (P_long(entry) ^ getPrivateLong) ^ getPrivateLong
                 * collapses to P_long(entry).
                 */
                ? NumberManager.encryptLong(
                        methodNode.getBlockPredicateLong(seedEntry), // Outcome (64-bit)
                        methodNode.getPredicate().getPrivateLong(),  // Entry (64-bit)
                        entryPoint,
                        getter
                )
                : NumberManager.encrypt(
                        methodNode.getBlockPredicate(seedEntry), // Outcome
                        methodNode.getPredicate().getPrivate(), // Entry
                        entryPoint,
                        getter
                );

        final Stmt copyVarStmt = localSetter.apply(loadedChanged);
        entryPoint.add(0, copyVarStmt);

        if (DEBUG) {
            final int seed = methodNode.getBlockPredicate(seedEntry);
            final BasicBlock exception = Blocks.exception(cfg, "Failed to match seed of value " + seed);
            final Stmt jumpStmt = new FakeConditionalJumpStmt(
                    methodNode.getFlowPredicate().getGetter().get(entryPoint),
                    new ConstantExpr(seed, Type.INT_TYPE),
                    exception,
                    ConditionalJumpStmt.ComparisonType.NE
            );
            cfg.addEdge(new ConditionalJumpEdge<>(entryPoint, exception, Opcodes.IFNE));
            entryPoint.add(1, jumpStmt);
        }

        if (DEBUG) {
            final int seed = methodNode.getPredicate().getPrivate();
            final BasicBlock exception = Blocks.exception(cfg,
                    new VirtualInvocationExpr(
                            InvocationExpr.CallType.VIRTUAL,
                            new Expr[] {
                                    new VirtualInvocationExpr(
                                            InvocationExpr.CallType.VIRTUAL,
                                            new Expr[]{
                                                    new InitialisedObjectExpr(
                                                            "java/lang/StringBuilder",
                                                            "(Ljava/lang/String;)V",
                                                            new Expr[]{
                                                                    new ConstantExpr(
                                                                            "Failed to match entry seed of value "
                                                                                    + seed + " of entry public: "
                                                                                    + methodNode.getPredicate().getPublic()
                                                                                    + " and private: "
                                                                                    + methodNode.getPredicate().getPrivate()
                                                                                    + " and value: ",
                                                                            Type.getType(String.class)
                                                                    )
                                                            }
                                                    ),
                                                    methodNode.getPredicate().getGetter().get(entryPoint)
                                            },
                                            "java/lang/StringBuilder",
                                            "append",
                                            "(I)Ljava/lang/StringBuilder;"
                                    )
                            },
                            "java/lang/StringBuilder",
                            "toString",
                            "()Ljava/lang/String;"
                    )
            );
            final Stmt jumpStmt = new FakeConditionalJumpStmt(
                    methodNode.getPredicate().getGetter().get(entryPoint),
                    new ConstantExpr(seed, Type.INT_TYPE),
                    exception,
                    ConditionalJumpStmt.ComparisonType.NE
            );
            cfg.addEdge(new ConditionalJumpEdge<>(entryPoint, exception, Opcodes.IFNE));
            entryPoint.add(0, jumpStmt);
        }


        /*
         *    __  __                           ___ __  _                   __
         *   / / / /___  _________  ____  ____/ (_) /_(_)___  ____  ____ _/ /
         *  / / / / __ \/ ___/ __ \/ __ \/ __  / / __/ / __ \/ __ \/ __ `/ /
         * / /_/ / / / / /__/ /_/ / / / / /_/ / / /_/ / /_/ / / / / /_/ / /
         * \____/_/ /_/\___/\____/_/ /_/\__,_/_/\__/_/\____/_/ /_/\__,_/_/
         *
         */
        for (BasicBlock vertex : new HashSet<>(cfg.vertices())) {
            if (vertex instanceof FakeBlock)
                continue;

            new HashSet<>(cfg.getEdges(vertex))
                    .stream()
                    .filter(e -> e instanceof ImmediateEdge)
                    .forEach(e -> {
                        if (seedWide) {
                            this.addSeedLoaderLong(
                                    e.src(),
                                    e.dst(),
                                    e.src().size(),
                                    localGetter,
                                    localSetter,
                                    methodNode.getBlockPredicateLong((SkidBlock) e.src()),
                                    methodNode.getBlockPredicateLong((SkidBlock) e.dst()),
                                    "Immediate"
                            );
                        } else {
                            this.addSeedLoader(
                                    e.src(),
                                    e.dst(),
                                    e.src().size(),
                                    localGetter,
                                    localSetter,
                                    methodNode.getBlockPredicate((SkidBlock) e.src()),
                                    methodNode.getBlockPredicate((SkidBlock) e.dst()),
                                    "Immediate"
                            );
                        }
                    });
        }

        final InstructionRenderer<UnconditionalJumpStmt> unconditionalRenderer = new UnconditionalJumpRenderer();
        for (BasicBlock block : new HashSet<>(cfg.vertices())) {
            new HashSet<>(block)
                    .stream()
                    .filter(e -> e instanceof UnconditionalJumpStmt && !(e instanceof FakeUnconditionalJumpStmt))
                    .map(e -> (UnconditionalJumpStmt) e)
                    .forEach(stmt -> unconditionalRenderer.transform(skidfuscator, cfg, stmt));
        }

        /*
         *    ______                ___ __  _                   __
         *   / ____/___  ____  ____/ (_) /_(_)___  ____  ____ _/ /
         *  / /   / __ \/ __ \/ __  / / __/ / __ \/ __ \/ __ `/ /
         * / /___/ /_/ / / / / /_/ / / /_/ / /_/ / / / / /_/ / /
         * \____/\____/_/ /_/\__,_/_/\__/_/\____/_/ /_/\__,_/_/
         *
         */
        final InstructionRenderer<ConditionalJumpStmt> conditionRenderer = new ConditionalJumpRenderer();
        for (BasicBlock block : new HashSet<>(cfg.vertices())) {
            new HashSet<>(block)
                    .stream()
                    .filter(e -> e instanceof ConditionalJumpStmt && !(e instanceof FakeConditionalJumpStmt))
                    .map(e -> (ConditionalJumpStmt) e)
                    .forEach(stmt -> conditionRenderer.transform(skidfuscator, cfg, stmt));
        }
        /*
         *    _____         _ __       __
         *   / ___/      __(_) /______/ /_
         *   \__ \ | /| / / / __/ ___/ __ \
         *  ___/ / |/ |/ / / /_/ /__/ / / /
         * /____/|__/|__/_/\__/\___/_/ /_/
         *
         */
        final InstructionRenderer<SwitchStmt> switchRenderer = new SwitchJumpRenderer();
        for (BasicBlock vertex : new HashSet<>(cfg.vertices())) {
            new HashSet<>(vertex)
                    .stream()
                    .filter(e -> e instanceof SkidSwitchStmt)
                    .map(e -> (SwitchStmt) e)
                    .forEach(stmt -> switchRenderer.transform(skidfuscator, cfg, stmt));
        }

        /*
         *     ______                     __  _
         *    / ____/  __________  ____  / /_(_)___  ____
         *   / __/ | |/_/ ___/ _ \/ __ \/ __/ / __ \/ __ \
         *  / /____>  </ /__/  __/ /_/ / /_/ / /_/ / / / /
         * /_____/_/|_|\___/\___/ .___/\__/_/\____/_/ /_/
         *                     /_/
         */
        final ExceptionRenderer exceptionRenderer = new ExceptionRenderer();
        for (ExceptionRange<BasicBlock> blockRange : cfg.getRanges()) {
            exceptionRenderer.transform(skidfuscator, cfg, blockRange);
        }

        if (methodNode.getName().equals("lambda$null$5") && methodNode.owner.getName().contains("PlayerListener")) {
            System.out.println(cfg.toString());
        }
        cfg.recomputeEdges();
        verifyWithoutDump(event, methodNode, cfg, "post-render");

        return;
    }

    private void verifyWithoutDump(final PostMethodTransformEvent event,
                                   final SkidMethodNode methodNode,
                                   final ControlFlowGraph cfg,
                                   final String stage) {
        try {
            if (cfg instanceof SkidControlFlowGraph) {
                ((SkidControlFlowGraph) cfg).verifyNoDump();
            } else {
                cfg.verify();
            }
        } catch (Exception e) {
            event.warn(
                    "Failed to verify " + stage + " CFG for method "
                            + methodNode.owner.getName() + "#"
                            + methodNode.getName()
                            + methodNode.getDesc()
                            + ": " + e.getMessage()
            );
        }
    }

    private void addSeedLoader(final BasicBlock block,
                               final BasicBlock targetBlock,
                               final int index,
                               final PredicateFlowGetter getter,
                               final PredicateFlowSetter local,
                               final int value,
                               final int target,
                               final String type
    ) {
        final Expr load = NumberManager.encrypt(
                target,
                value,
                block,
                getter
        );
        final Stmt set = local.apply(load);

        block.add(
                index < 0 ? block.size() : index,
                set
        );
        if (DEBUG) {
            final BasicBlock exception = Blocks.exception(
                    block.cfg,
                    new VirtualInvocationExpr(
                            InvocationExpr.CallType.VIRTUAL,
                            new Expr[] {
                                new VirtualInvocationExpr(
                                        InvocationExpr.CallType.VIRTUAL,
                                        new Expr[]{
                                                new InitialisedObjectExpr(
                                                        "java/lang/StringBuilder",
                                                        "(Ljava/lang/String;)V",
                                                        new Expr[]{
                                                                new ConstantExpr(
                                                                        block.getDisplayName() + " --> "
                                                                                + targetBlock.getDisplayName()
                                                                                + " # Failed to match seed of type "
                                                                                + type
                                                                                + " and value "
                                                                                + target
                                                                                + " and got ",
                                                                        Type.getType(String.class)
                                                                )
                                                        }
                                                ),
                                                getter.get(block)
                                        },
                                        "java/lang/StringBuilder",
                                        "append",
                                        "(I)Ljava/lang/StringBuilder;"
                                )
                            },
                            "java/lang/StringBuilder",
                            "toString",
                            "()Ljava/lang/String;"
                    )
            );

            final Stmt jumpStmt = new FakeConditionalJumpStmt(
                    getter.get(targetBlock),
                    new ConstantExpr(
                            target,
                            Type.INT_TYPE
                    ),
                    exception,
                    ConditionalJumpStmt.ComparisonType.NE
            );
            block.cfg.addEdge(
                    new ConditionalJumpEdge<>(
                            block,
                            exception,
                            Opcodes.GOTO
                    )
            );
            block.add(
                    index < 0 ? block.size() : index + 1,
                    jumpStmt
            );
        }
    }

    /**
     * 64-bit counterpart of {@link #addSeedLoader}: advances the long flow local
     * from {@code value} (the current block predicate) to {@code target} (the next
     * block predicate) via {@code (target ^ value) ^ seed}. Used on the seed.wide
     * path. The DEBUG self-check block is intentionally omitted for the wide path.
     */
    private void addSeedLoaderLong(final BasicBlock block,
                                   final BasicBlock targetBlock,
                                   final int index,
                                   final PredicateFlowGetter getter,
                                   final PredicateFlowSetter local,
                                   final long value,
                                   final long target,
                                   final String type
    ) {
        final Expr load = NumberManager.encryptLong(
                target,
                value,
                block,
                getter
        );
        final Stmt set = local.apply(load);

        block.add(
                index < 0 ? block.size() : index,
                set
        );
    }
}
