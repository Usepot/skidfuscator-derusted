package dev.skidfuscator.obfuscator.creator;

import dev.skidfuscator.obfuscator.compatibility.MixinInitializerBridge;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Modifier;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MixinInitializerBridgeTest implements Opcodes {
    @Test void retainsTransformedScratchDataFlowAndPresentsALineMarkedEnvelope() throws Exception {
        ClassNode donor = donor("fixture/MutableInitializer", "java/lang/Object");
        donor.fields.add(new FieldNode(ACC_PUBLIC, "value", "I", null, null));
        MethodNode ctor = constructor(donor, "()V");
        prelude(ctor);
        superCall(ctor, "java/lang/Object", "()V");
        ctor.instructions.add(new VarInsnNode(ALOAD, 0));
        ctor.instructions.add(new VarInsnNode(LLOAD, 4));
        ctor.instructions.add(new InsnNode(L2I));
        ctor.instructions.add(new FieldInsnNode(PUTFIELD, donor.name, "value", "I"));
        ctor.instructions.add(new InsnNode(RETURN));
        assertEquals(1, MixinInitializerBridge.lower(donor).constructors());
        assertEnvelope(ctor);
        MethodNode helper = donor.methods.stream().filter(m -> m.name.startsWith("skid$initializer$")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(helper.instructions.toArray()).anyMatch(i -> i instanceof VarInsnNode v && v.getOpcode() == LSTORE));
        Class<?> type = load(donor);
        Object object = type.getConstructor().newInstance();
        assertEquals(42, type.getField("value").get(object));
        int count = donor.methods.size();
        assertEquals(0, MixinInitializerBridge.lower(donor).constructors());
        assertEquals(count, donor.methods.size());
    }

    @Test void keepsFinalReferenceAssignmentInsideTheRealConstructor() throws Exception {
        ClassNode donor = donor("fixture/FinalReferenceInitializer", "java/lang/Object");
        donor.fields.add(new FieldNode(ACC_PUBLIC | ACC_FINAL, "value", "Ljava/util/LinkedList;", null, null));
        MethodNode ctor = constructor(donor, "()V");
        prelude(ctor); superCall(ctor, "java/lang/Object", "()V");
        ctor.instructions.add(new VarInsnNode(ALOAD, 0));
        ctor.instructions.add(new TypeInsnNode(NEW, "java/util/LinkedList"));
        ctor.instructions.add(new InsnNode(DUP));
        ctor.instructions.add(new MethodInsnNode(INVOKESPECIAL, "java/util/LinkedList", "<init>", "()V", false));
        ctor.instructions.add(new FieldInsnNode(PUTFIELD, donor.name, "value", "Ljava/util/LinkedList;"));
        ctor.instructions.add(new InsnNode(RETURN));
        assertEquals(1, MixinInitializerBridge.lower(donor).finalAssignments());
        assertEnvelope(ctor);
        for (MethodNode m : donor.methods) if (!m.name.equals("<init>"))
            assertFalse(Arrays.stream(m.instructions.toArray()).anyMatch(i -> i instanceof FieldInsnNode f && f.getOpcode() == PUTFIELD && f.name.equals("value")));
        Class<?> type = load(donor);
        assertTrue(Modifier.isFinal(type.getField("value").getModifiers()));
        Object first = type.getField("value").get(type.getConstructor().newInstance());
        Object second = type.getField("value").get(type.getConstructor().newInstance());
        assertInstanceOf(LinkedList.class, first);
        assertNotSame(first, second);
    }

    @Test void preservesFinalPrimitiveAndCategoryTwoResults() throws Exception {
        Object[][] cases = {{"I", 73}, {"J", 1234567890123L}, {"F", 1.25F}, {"D", 4.75D}, {"Ljava/lang/String;", "intact"}};
        for (int index = 0; index < cases.length; index++) {
            String desc = (String) cases[index][0]; Object expected = cases[index][1];
            ClassNode donor = donor("fixture/FinalValue" + index, "java/lang/Object");
            donor.fields.add(new FieldNode(ACC_PUBLIC | ACC_FINAL, "value", desc, null, null));
            MethodNode ctor = constructor(donor, "()V");
            superCall(ctor, "java/lang/Object", "()V");
            ctor.instructions.add(new VarInsnNode(ALOAD, 0));
            ctor.instructions.add(new LdcInsnNode(expected));
            ctor.instructions.add(new FieldInsnNode(PUTFIELD, donor.name, "value", desc));
            ctor.instructions.add(new InsnNode(RETURN));
            MixinInitializerBridge.lower(donor);
            Class<?> type = load(donor);
            assertEquals(expected, type.getField("value").get(type.getConstructor().newInstance()));
        }
    }

    @Test void preservesDirectSuperclassArgumentsWithoutLeakingThemIntoTheInitializer() throws Exception {
        ClassNode donor = donor("fixture/SuperArgumentInitializer", "java/util/ArrayList");
        donor.fields.add(new FieldNode(ACC_PUBLIC, "value", "I", null, null));
        MethodNode ctor = constructor(donor, "(I)V");
        prelude(ctor); superCall(ctor, "java/util/ArrayList", "(I)V");
        ctor.instructions.add(new VarInsnNode(ALOAD, 0));
        ctor.instructions.add(new VarInsnNode(LLOAD, 4));
        ctor.instructions.add(new InsnNode(L2I));
        ctor.instructions.add(new FieldInsnNode(PUTFIELD, donor.name, "value", "I"));
        ctor.instructions.add(new InsnNode(RETURN));
        MixinInitializerBridge.lower(donor);
        Class<?> type = load(donor);
        Object result = type.getConstructor(int.class).newInstance(7);
        assertInstanceOf(ArrayList.class, result);
        assertEquals(42, type.getField("value").get(result));
    }

    @Test void rejectsConstructorArgumentDependentInitializationWithoutMutatingTheInput() {
        ClassNode donor = donor("fixture/ArgumentDependentInitializer", "java/lang/Object");
        donor.fields.add(new FieldNode(ACC_PUBLIC, "value", "I", null, null));
        MethodNode ctor = constructor(donor, "(I)V"); superCall(ctor, "java/lang/Object", "()V");
        ctor.instructions.add(new VarInsnNode(ALOAD, 0)); ctor.instructions.add(new VarInsnNode(ILOAD, 1));
        ctor.instructions.add(new FieldInsnNode(PUTFIELD, donor.name, "value", "I")); ctor.instructions.add(new InsnNode(RETURN));
        int count = ctor.instructions.size();
        assertThrows(IllegalStateException.class, () -> MixinInitializerBridge.lower(donor));
        assertEquals(count, ctor.instructions.size()); assertEquals(1, donor.methods.size());
    }

    @Test void rejectsNonterminalFinalStoresAndEffectfulSuperclassPrefixes() {
        ClassNode donor = donor("fixture/NonterminalFinalInitializer", "java/lang/Object");
        donor.fields.add(new FieldNode(ACC_PUBLIC | ACC_FINAL, "value", "I", null, null));
        MethodNode ctor = constructor(donor, "()V"); superCall(ctor, "java/lang/Object", "()V");
        ctor.instructions.add(new VarInsnNode(ALOAD, 0)); ctor.instructions.add(new InsnNode(ICONST_1));
        ctor.instructions.add(new FieldInsnNode(PUTFIELD, donor.name, "value", "I"));
        ctor.instructions.add(new InsnNode(NOP)); ctor.instructions.add(new InsnNode(RETURN));
        assertThrows(IllegalStateException.class, () -> MixinInitializerBridge.lower(donor));
        ClassNode effect = donor("fixture/EffectfulPrefix", "java/lang/Object");
        MethodNode e = constructor(effect, "()V");
        e.instructions.add(new MethodInsnNode(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false));
        e.instructions.add(new InsnNode(POP2)); superCall(e, "java/lang/Object", "()V"); e.instructions.add(new InsnNode(RETURN));
        assertThrows(IllegalStateException.class, () -> MixinInitializerBridge.lower(effect));
    }

    private static ClassNode donor(String name, String parent) {
        ClassNode owner = new ClassNode(); owner.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, parent, null);
        owner.invisibleAnnotations = new ArrayList<>(List.of(new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;")));
        return owner;
    }
    private static MethodNode constructor(ClassNode owner, String desc) {
        MethodNode ctor = new MethodNode(ACC_PUBLIC, "<init>", desc, null, null);
        ctor.maxStack = 8; ctor.maxLocals = 8; owner.methods.add(ctor); return ctor;
    }
    private static void prelude(MethodNode ctor) {
        ctor.instructions.add(new LdcInsnNode(19L)); ctor.instructions.add(new LdcInsnNode(23L));
        ctor.instructions.add(new InsnNode(LADD)); ctor.instructions.add(new VarInsnNode(LSTORE, 4));
    }
    private static void superCall(MethodNode ctor, String parent, String desc) {
        ctor.instructions.add(new VarInsnNode(ALOAD, 0));
        int slot = 1;
        for (Type type : Type.getArgumentTypes(desc)) { ctor.instructions.add(new VarInsnNode(type.getOpcode(ILOAD), slot)); slot += type.getSize(); }
        ctor.instructions.add(new MethodInsnNode(INVOKESPECIAL, parent, "<init>", desc, false));
    }
    private static void assertEnvelope(MethodNode ctor) {
        List<Integer> lines = new ArrayList<>();
        for (AbstractInsnNode i : ctor.instructions) {
            if (i instanceof LineNumberNode line) lines.add(line.line);
            assertFalse(i instanceof JumpInsnNode);
            assertFalse(i instanceof VarInsnNode v && v.getOpcode() >= ISTORE && v.getOpcode() <= ASTORE);
        }
        assertEquals(List.of(1, 2, 1), lines);
    }
    private static Class<?> load(ClassNode owner) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS); owner.accept(writer);
        byte[] bytes = writer.toByteArray();
        return new ClassLoader(MixinInitializerBridgeTest.class.getClassLoader()) {
            Class<?> define() { return defineClass(owner.name.replace('/', '.'), bytes, 0, bytes.length); }
        }.define();
    }
}
