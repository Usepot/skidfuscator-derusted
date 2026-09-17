package dev.skidfuscator.obfuscator.compatibility;

import dev.skidfuscator.obfuscator.Skidfuscator;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import org.topdank.byteengineer.commons.data.JarClassData;

import java.util.*;

/**
 * EXPERIMENTAL, opt-in adapter for field-initializer-only Mixin constructors.
 * Mixin 0.7 requires constructor line ranges and a straight-line initializer
 * ending in PUTFIELD. CFG output lacks that metadata and its scratch locals are
 * not transplantable. Keep the fully transformed work in a private donor method
 * and present a tiny, line-marked initializer envelope to Mixin instead.
 *
 * No final modifier is removed: a terminal final assignment stays in <init>;
 * its helper returns the computed value. Unsupported shapes fail closed. This
 * is NOT support for arbitrary explicit constructor bodies/constructor argument
 * dependent initializers; use only with an audited field-only input profile.
 */
public final class MixinInitializerBridge implements Opcodes {
    private static final String MARKER = "Ldev/skidfuscator/compatibility/MixinInitializerEnvelope;";
    private static final String UNIQUE = "Lorg/spongepowered/asm/mixin/Unique;";
    private MixinInitializerBridge() { }
    public record Result(int constructors, int finalAssignments) { }

    public static Result lower(ClassNode owner) {
        if (!RelocatableInvokeDynamic.isMixin(owner)) return new Result(0, 0);
        List<MethodNode> constructors = owner.methods.stream().filter(m -> m.name.equals("<init>")).toList();
        if (constructors.isEmpty()) return new Result(0, 0);
        if (constructors.size() != 1) throw unsupported(owner, "multiple constructor paths require an original-initializer plan");
        MethodNode ctor = constructors.get(0);
        if (ctor.invisibleAnnotations != null && ctor.invisibleAnnotations.stream().anyMatch(a -> MARKER.equals(a.desc)))
            return new Result(0, 0);
        if (!ctor.tryCatchBlocks.isEmpty()) throw unsupported(owner, "constructor exception regions require range-aware extraction");

        List<AbstractInsnNode> code = new ArrayList<>();
        for (AbstractInsnNode i : ctor.instructions) if (i.getOpcode() >= 0) code.add(i);
        int chainIndex = -1;
        for (int i = 0; i < code.size(); i++) {
            if (code.get(i) instanceof MethodInsnNode call && call.getOpcode() == INVOKESPECIAL
                    && call.name.equals("<init>") && (call.owner.equals(owner.superName) || call.owner.equals(owner.name))) {
                chainIndex = i;
                break;
            }
        }
        if (chainIndex < 0) throw unsupported(owner, "missing direct superclass constructor call");
        MethodInsnNode chain = (MethodInsnNode) code.get(chainIndex);
        if (!chain.owner.equals(owner.superName)) throw unsupported(owner, "delegating this(...) constructor");
        Type[] superArgs = Type.getArgumentTypes(chain.desc);
        int loadStart = chainIndex - superArgs.length - 1;
        if (loadStart < 0 || !(code.get(loadStart) instanceof VarInsnNode receiver)
                || receiver.getOpcode() != ALOAD || receiver.var != 0)
            throw unsupported(owner, "super receiver/arguments are not contiguous direct loads");
        Type[] ctorArgs = Type.getArgumentTypes(ctor.desc);
        Map<Integer, Type> parameters = new HashMap<>();
        for (int p = 0, slot = 1; p < ctorArgs.length; p++) {
            parameters.put(slot, ctorArgs[p]); slot += ctorArgs[p].getSize();
        }
        for (int p = 0; p < superArgs.length; p++) {
            AbstractInsnNode load = code.get(loadStart + 1 + p);
            if (!(load instanceof VarInsnNode var) || var.getOpcode() != superArgs[p].getOpcode(ILOAD)
                    || !superArgs[p].equals(parameters.get(var.var)))
                throw unsupported(owner, "computed or incompatible superclass argument");
        }
        // Moving the numeric prelude after super must not reorder exceptions,
        // object publication, field reads, calls or constructor argument effects.
        for (int i = 0; i < loadStart; i++) if (!purePrelude(code.get(i)))
            throw unsupported(owner, "effectful pre-super opcode " + code.get(i).getOpcode());
        Set<LabelNode> prefixLabels = Collections.newSetFromMap(new IdentityHashMap<>());
        for (AbstractInsnNode i : ctor.instructions) {
            if (i == chain) break;
            if (i instanceof LabelNode label) prefixLabels.add(label);
        }
        for (AbstractInsnNode i : ctor.instructions) {
            if (i instanceof JumpInsnNode j && prefixLabels.contains(j.label)) throw unsupported(owner, "jump enters superclass prefix");
            if (i instanceof TableSwitchInsnNode s && (prefixLabels.contains(s.dflt) || s.labels.stream().anyMatch(prefixLabels::contains)))
                throw unsupported(owner, "switch enters superclass prefix");
            if (i instanceof LookupSwitchInsnNode s && (prefixLabels.contains(s.dflt) || s.labels.stream().anyMatch(prefixLabels::contains)))
                throw unsupported(owner, "switch enters superclass prefix");
        }

        Map<String, FieldNode> fields = new HashMap<>();
        for (FieldNode field : owner.fields) fields.put(field.name + field.desc, field);
        List<FieldInsnNode> finals = new ArrayList<>();
        for (AbstractInsnNode i : code) if (i instanceof FieldInsnNode field && field.getOpcode() == PUTFIELD && field.owner.equals(owner.name)) {
            FieldNode declared = fields.get(field.name + field.desc);
            if (declared != null && (declared.access & ACC_FINAL) != 0) finals.add(field);
        }
        FieldInsnNode finalStore = finals.isEmpty() ? null : finals.get(0);
        if (finals.size() > 1 || (finalStore != null && (code.size() < 2 || code.get(code.size() - 2) != finalStore
                || code.get(code.size() - 1).getOpcode() != RETURN
                || code.stream().filter(i -> i.getOpcode() == RETURN).count() != 1)))
            throw unsupported(owner, "non-terminal/multiple final assignments require segmented extraction");
        if (finalStore != null) {
            try {
                Frame<SourceValue>[] frames = new Analyzer<>(new SourceInterpreter()).analyze(owner.name, ctor);
                Frame<SourceValue> frame = frames[ctor.instructions.indexOf(finalStore)];
                if (frame == null || frame.getStackSize() != 2) throw unsupported(owner, "final assignment stack is not isolated");
                Set<AbstractInsnNode> sources = frame.getStack(0).insns;
                if (sources.size() != 1 || !(sources.iterator().next() instanceof VarInsnNode load)
                        || load.getOpcode() != ALOAD || load.var != 0)
                    throw unsupported(owner, "final assignment receiver is not this");
            } catch (AnalyzerException failure) { throw new IllegalStateException("Cannot analyze Mixin final initializer " + owner.name, failure); }
        }

        String suffix = UUID.randomUUID().toString().replace("-", "");
        String helperName = "skid$initializer$" + suffix;
        String fieldName = finalStore == null ? "skid$initialized$" + suffix : finalStore.name;
        String fieldDesc = finalStore == null ? "Z" : finalStore.desc;
        Type result = finalStore == null ? Type.VOID_TYPE : Type.getType(finalStore.desc);
        MethodNode helper = new MethodNode(ASM9, ACC_PRIVATE | ACC_SYNTHETIC, helperName, "()" + result.getDescriptor(), null,
                ctor.exceptions.toArray(new String[0]));
        Map<LabelNode, LabelNode> clonedLabels = new IdentityHashMap<>();
        for (AbstractInsnNode i : ctor.instructions)
            if (i instanceof LabelNode label) clonedLabels.put(label, new LabelNode());
        for (AbstractInsnNode i : ctor.instructions) helper.instructions.add(i.clone(clonedLabels));
        helper.maxStack = ctor.maxStack;
        helper.maxLocals = ctor.maxLocals;
        helper.visibleAnnotations = null;
        helper.invisibleAnnotations = new ArrayList<>(List.of(new AnnotationNode(UNIQUE)));
        helper.visibleTypeAnnotations = null; helper.invisibleTypeAnnotations = null;
        helper.visibleParameterAnnotations = null; helper.invisibleParameterAnnotations = null;
        helper.visibleAnnotableParameterCount = 0; helper.invisibleAnnotableParameterCount = 0;
        helper.parameters = null; helper.annotationDefault = null;
        helper.localVariables = null; helper.visibleLocalVariableAnnotations = null; helper.invisibleLocalVariableAnnotations = null;
        List<AbstractInsnNode> moved = new ArrayList<>();
        for (AbstractInsnNode i : helper.instructions) if (i.getOpcode() >= 0) moved.add(i);
        for (int i = loadStart; i <= chainIndex; i++) helper.instructions.remove(moved.get(i));
        for (AbstractInsnNode i : helper.instructions.toArray())
            if (i instanceof FrameNode || i instanceof LineNumberNode) helper.instructions.remove(i);
        if (finalStore != null) {
            AbstractInsnNode store = moved.get(code.size() - 2), ret = moved.get(code.size() - 1);
            int valueLocal = helper.maxLocals;
            InsnList ending = new InsnList();
            ending.add(new VarInsnNode(result.getOpcode(ISTORE), valueLocal));
            ending.add(new InsnNode(POP)); // Receiver remains in the envelope, not in the helper.
            ending.add(new VarInsnNode(result.getOpcode(ILOAD), valueLocal));
            ending.add(new InsnNode(result.getOpcode(IRETURN)));
            helper.instructions.insertBefore(store, ending);
            helper.instructions.remove(store); helper.instructions.remove(ret);
            helper.maxLocals += result.getSize();
        }
        try {
            // The helper has no constructor parameters. This rejects leaked
            // argument dependencies/uninitialized scratch locals after cutting.
            new Analyzer<>(new BasicVerifier()).analyze(owner.name, helper);
        } catch (AnalyzerException failure) {
            throw new IllegalStateException("Unsupported Mixin initializer data flow in " + owner.name + ctor.desc, failure);
        }

        InsnList envelope = new InsnList();
        label(envelope, 1);
        for (int i = loadStart; i <= chainIndex; i++) envelope.add(code.get(i).clone(new HashMap<>()));
        label(envelope, 2); // Outside constructor range [1,1]: extracted by Mixin 0.7.
        envelope.add(new VarInsnNode(ALOAD, 0));
        envelope.add(new VarInsnNode(ALOAD, 0));
        envelope.add(new MethodInsnNode(INVOKESPECIAL, owner.name, helperName, helper.desc, false));
        if (finalStore == null) envelope.add(new InsnNode(ICONST_1));
        envelope.add(new FieldInsnNode(PUTFIELD, owner.name, fieldName, fieldDesc));
        label(envelope, 1);
        envelope.add(new InsnNode(RETURN));
        ctor.instructions = envelope;
        ctor.tryCatchBlocks.clear(); ctor.localVariables = null;
        ctor.visibleLocalVariableAnnotations = null; ctor.invisibleLocalVariableAnnotations = null;
        ctor.maxLocals = 1 + Arrays.stream(ctorArgs).mapToInt(Type::getSize).sum();
        ctor.maxStack = Math.max(3, 1 + Arrays.stream(superArgs).mapToInt(Type::getSize).sum());
        if (ctor.invisibleAnnotations == null) ctor.invisibleAnnotations = new ArrayList<>();
        else ctor.invisibleAnnotations = new ArrayList<>(ctor.invisibleAnnotations);
        ctor.invisibleAnnotations.add(new AnnotationNode(MARKER));
        if (finalStore == null) {
            FieldNode marker = new FieldNode(ACC_PRIVATE | ACC_SYNTHETIC | ACC_TRANSIENT, fieldName, fieldDesc, null, null);
            marker.invisibleAnnotations = new ArrayList<>(List.of(new AnnotationNode(UNIQUE)));
            owner.fields.add(marker);
        }
        owner.methods.add(helper);
        return new Result(1, finals.size());
    }

    private static void label(InsnList code, int line) {
        LabelNode label = new LabelNode(); code.add(label); code.add(new LineNumberNode(line, label));
    }
    private static boolean purePrelude(AbstractInsnNode i) {
        if (i instanceof LdcInsnNode ldc) return ldc.cst instanceof Number;
        if (i instanceof VarInsnNode var) return var.getOpcode() >= ILOAD && var.getOpcode() <= DLOAD
                || var.getOpcode() >= ISTORE && var.getOpcode() <= DSTORE;
        int op = i.getOpcode();
        return op == NOP || (op >= ICONST_M1 && op <= SIPUSH)
                || (op >= IADD && op <= DMUL) || (op >= INEG && op <= LXOR)
                || (op >= I2L && op <= DCMPG) || (op >= POP && op <= SWAP);
    }
    private static IllegalStateException unsupported(ClassNode owner, String reason) {
        return new IllegalStateException("UNSTABLE_MIXIN_INITIALIZER unsupported shape in " + owner.name + ": " + reason
                + "; refusing output rather than dropping initialization");
    }
    /** Output-time only: do not re-obfuscate the protocol envelope afterwards. */
    public static void apply(Skidfuscator skid) {
        if (!skid.getConfig().getBoolean("compatibility.experimentalMixinInitializers", false)) return;
        int constructors = 0, finals = 0;
        for (JarClassData data : skid.getJarContents().getClassContents()) {
            org.mapleir.asm.ClassNode owner = data.getClassNode();
            if (skid.getExemptAnalysis().isExempt(owner)) continue;
            Result result = lower(owner.node);
            constructors += result.constructors(); finals += result.finalAssignments();
        }
        Skidfuscator.LOGGER.warn("EXPERIMENTAL_MIXIN_INITIALIZERS constructors=" + constructors + " finalAssignments=" + finals
                + "; transformed bodies retained; audited field-only constructor shapes required");
    }
}
