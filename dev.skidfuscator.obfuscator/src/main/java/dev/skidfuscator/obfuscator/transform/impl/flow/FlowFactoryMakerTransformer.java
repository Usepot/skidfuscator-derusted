package dev.skidfuscator.obfuscator.transform.impl.flow;

import dev.skidfuscator.config.DefaultTransformerConfig;
import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.event.EventPriority;
import dev.skidfuscator.obfuscator.event.annotation.Listen;
import dev.skidfuscator.obfuscator.event.impl.transform.clazz.InitClassTransformEvent;
import dev.skidfuscator.obfuscator.predicate.factory.PredicateFlowGetter;
import dev.skidfuscator.obfuscator.predicate.opaque.ClassOpaquePredicate;
import dev.skidfuscator.obfuscator.skidasm.SkidClassNode;
import dev.skidfuscator.obfuscator.transform.AbstractTransformer;
import dev.skidfuscator.obfuscator.util.RandomUtil;
import org.mapleir.ir.code.Expr;
import org.mapleir.ir.code.expr.invoke.StaticInvocationExpr;
import org.mapleir.ir.code.stmt.FieldStoreStmt;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

public class FlowFactoryMakerTransformer extends AbstractTransformer {
    private static final String CONFIG_PATH = "flowFactoryMaker";
    private static final String INT_DESCRIPTOR = "I";
    private static final String GETTER_DESCRIPTOR = "()I";
    private static final Map<SkidClassNode, Map<Integer, SeedAccess>> ACCESSORS =
            Collections.synchronizedMap(new WeakHashMap<>());

    public FlowFactoryMakerTransformer(final Skidfuscator skidfuscator) {
        super(skidfuscator, "Flow Factory Maker");
    }

    @Listen(EventPriority.MONITOR)
    void handle(final InitClassTransformEvent event) {
        final SkidClassNode classNode = event.getClassNode();

        if (!canMaterialize(event.getSkidfuscator(), classNode)) {
            this.skip();
            return;
        }

        materializePredicate(classNode, classNode.getClassPredicate());
        materializePredicate(classNode, classNode.getStaticPredicate());
        this.success();
    }

    public static PredicateFlowGetter materializeSeedGetter(final Skidfuscator skidfuscator,
                                                           final SkidClassNode classNode,
                                                           final int seed,
                                                           final PredicateFlowGetter fallback) {
        if (!canMaterialize(skidfuscator, classNode)) {
            return fallback;
        }

        final SeedAccess access = getOrCreateAccess(classNode, seed);
        return vertex -> access.createGetterExpr();
    }

    private static boolean canMaterialize(final Skidfuscator skidfuscator,
                                          final SkidClassNode classNode) {
        if (skidfuscator == null
                || classNode == null
                || skidfuscator.getTsConfig() == null
                || classNode.isInterface()
                || classNode.isAnnotation()) {
            return false;
        }

        final DefaultTransformerConfig config = new DefaultTransformerConfig(
                skidfuscator.getTsConfig(),
                CONFIG_PATH
        );
        return config.isEnabled()
                && !skidfuscator.getExemptAnalysis().isExempt(
                FlowFactoryMakerTransformer.class,
                classNode
        );
    }

    private static void materializePredicate(final SkidClassNode classNode,
                                             final ClassOpaquePredicate predicate) {
        if (predicate == null) {
            return;
        }

        final SeedAccess access = getOrCreateAccess(classNode, predicate.get());
        predicate.setGetter(vertex -> access.createGetterExpr());
        predicate.setSetter(expr -> new FieldStoreStmt(
                null,
                expr,
                classNode.node.name,
                access.fieldName,
                INT_DESCRIPTOR,
                true
        ));
    }

    private static SeedAccess getOrCreateAccess(final SkidClassNode classNode,
                                                final int seed) {
        synchronized (ACCESSORS) {
            return ACCESSORS
                    .computeIfAbsent(classNode, ignored -> new HashMap<>())
                    .computeIfAbsent(seed, ignored -> createAccess(classNode, seed));
        }
    }

    private static SeedAccess createAccess(final SkidClassNode classNode,
                                           final int seed) {
        final String fieldName = uniqueFieldName(classNode);
        final String getterName = uniqueMethodName(classNode);

        classNode.createField()
                .access(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC)
                .name(fieldName)
                .desc(INT_DESCRIPTOR)
                .value(seed)
                .build();

        final MethodNode methodNode = new MethodNode(
                Skidfuscator.ASM_VERSION,
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                getterName,
                GETTER_DESCRIPTOR,
                null,
                null
        );
        methodNode.instructions.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                classNode.node.name,
                fieldName,
                INT_DESCRIPTOR
        ));
        methodNode.instructions.add(new InsnNode(Opcodes.IRETURN));
        methodNode.maxStack = 1;
        methodNode.maxLocals = 0;
        classNode.node.methods.add(methodNode);

        return new SeedAccess(classNode.node.name, fieldName, getterName);
    }

    private static String uniqueFieldName(final SkidClassNode classNode) {
        String name;
        do {
            name = RandomUtil.randomAlphabeticalString(12);
        } while (hasField(classNode, name));
        return name;
    }

    private static boolean hasField(final SkidClassNode classNode, final String name) {
        return classNode.node.fields.stream().anyMatch(field -> field.name.equals(name));
    }

    private static String uniqueMethodName(final SkidClassNode classNode) {
        String name;
        do {
            name = RandomUtil.randomAlphabeticalString(12);
        } while (hasGetterMethod(classNode, name));
        return name;
    }

    private static boolean hasGetterMethod(final SkidClassNode classNode, final String name) {
        return classNode.node.methods.stream()
                .anyMatch(method -> method.name.equals(name)
                        && method.desc.equals(GETTER_DESCRIPTOR));
    }

    private static final class SeedAccess {
        private final String owner;
        private final String fieldName;
        private final String getterName;

        private SeedAccess(final String owner,
                           final String fieldName,
                           final String getterName) {
            this.owner = owner;
            this.fieldName = fieldName;
            this.getterName = getterName;
        }

        private Expr createGetterExpr() {
            return new StaticInvocationExpr(new Expr[0], owner, getterName, GETTER_DESCRIPTOR);
        }
    }
}
