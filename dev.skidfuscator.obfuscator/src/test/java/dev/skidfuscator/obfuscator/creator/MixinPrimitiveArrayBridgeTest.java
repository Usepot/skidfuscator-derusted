package dev.skidfuscator.obfuscator.creator;

import dev.skidfuscator.obfuscator.compatibility.MixinPrimitiveArrayBridge;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MixinPrimitiveArrayBridgeTest implements Opcodes {
    @Test void preservesEveryPrimitiveArrayCastTypeTestAndAllocationIncludingExceptions() throws Exception {
        String[] descriptors = {"[Z", "[B", "[C", "[S", "[I", "[J", "[F", "[D", "[[I", "[[[D"};
        ClassNode donor = owner("fixture/PrimitiveArrayMixin", true);
        for (int i = 0; i < descriptors.length; i++) {
            add(donor, "cast" + i, "(Ljava/lang/Object;)" + descriptors[i], CHECKCAST, descriptors[i], ALOAD, ARETURN);
            add(donor, "test" + i, "(Ljava/lang/Object;)Z", INSTANCEOF, descriptors[i], ALOAD, IRETURN);
            add(donor, "allocate" + i, "(I)[" + descriptors[i], ANEWARRAY, descriptors[i], ILOAD, ARETURN);
        }
        MixinPrimitiveArrayBridge.Result result = MixinPrimitiveArrayBridge.lower(donor, "fixture/PrimitiveOperations");
        assertEquals(descriptors.length * 3, result.operations());
        assertEquals(descriptors.length * 3, result.helper().methods.size());
        for (MethodNode method : donor.methods) {
            assertEquals(3, method.instructions.size());
            assertInstanceOf(MethodInsnNode.class, method.instructions.get(1));
            assertEquals(INVOKESTATIC, method.instructions.get(1).getOpcode());
            assertTrue(method.instructions.toArray().length > 0, "The hook body remains present");
        }
        ClassLoader loader = loader(donor, result.helper());
        Class<?> type = loader.loadClass(donor.name.replace('/', '.'));
        for (int i = 0; i < descriptors.length; i++) {
            Class<?> arrayClass = Class.forName(descriptors[i].replace('/', '.'));
            Object value = Array.newInstance(arrayClass.getComponentType(), 2);
            java.lang.reflect.Method cast = type.getMethod("cast" + i, Object.class);
            java.lang.reflect.Method test = type.getMethod("test" + i, Object.class);
            java.lang.reflect.Method allocation = type.getMethod("allocate" + i, int.class);
            assertSame(value, cast.invoke(null, value));
            assertNull(cast.invoke(null, new Object[]{null}));
            assertEquals(true, test.invoke(null, value));
            assertEquals(false, test.invoke(null, new Object[]{null}));
            assertEquals(false, test.invoke(null, "wrong type"));
            InvocationTargetException badCast = assertThrows(InvocationTargetException.class, () -> cast.invoke(null, "wrong type"));
            assertInstanceOf(ClassCastException.class, badCast.getCause());
            Object allocated = allocation.invoke(null, 3);
            assertEquals(arrayClass, allocated.getClass().getComponentType());
            assertEquals(3, Array.getLength(allocated));
            assertNull(Array.get(allocated, 0));
            InvocationTargetException negative = assertThrows(InvocationTargetException.class, () -> allocation.invoke(null, -1));
            assertInstanceOf(NegativeArraySizeException.class, negative.getCause());
        }
    }

    @Test void doesNotRewriteOrdinaryClassesReferenceArraysOrPrimitiveNewarray() {
        ClassNode ordinary = owner("fixture/Ordinary", false);
        add(ordinary, "cast", "(Ljava/lang/Object;)[B", CHECKCAST, "[B", ALOAD, ARETURN);
        assertNull(MixinPrimitiveArrayBridge.lower(ordinary, "fixture/Unused").helper());
        assertInstanceOf(TypeInsnNode.class, ordinary.methods.get(0).instructions.get(1));
        ClassNode mixin = owner("fixture/ReferenceMixin", true);
        add(mixin, "cast", "(Ljava/lang/Object;)[Ljava/lang/String;", CHECKCAST, "[Ljava/lang/String;", ALOAD, ARETURN);
        assertEquals(0, MixinPrimitiveArrayBridge.lower(mixin, "fixture/Unused").operations());
        assertInstanceOf(TypeInsnNode.class, mixin.methods.get(0).instructions.get(1));
        assertFalse(MixinPrimitiveArrayBridge.needsBridge(new TypeInsnNode(NEW, "java/lang/Object")));
    }

    @Test void repeatedOperationsShareAHelperAndPreserveOriginalHandlerCoverage() throws Exception {
        ClassNode donor = owner("fixture/CatchMixin", true);
        MethodNode method = new MethodNode(ACC_PUBLIC | ACC_STATIC, "safe", "(Ljava/lang/Object;)I", null, null);
        LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
        method.instructions.add(start);
        method.instructions.add(new VarInsnNode(ALOAD, 0));
        method.instructions.add(new TypeInsnNode(CHECKCAST, "[B"));
        method.instructions.add(new TypeInsnNode(CHECKCAST, "[B"));
        method.instructions.add(new InsnNode(ARRAYLENGTH));
        method.instructions.add(end);
        method.instructions.add(new InsnNode(IRETURN));
        method.instructions.add(handler);
        method.instructions.add(new InsnNode(POP));
        method.instructions.add(new InsnNode(ICONST_M1));
        method.instructions.add(new InsnNode(IRETURN));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/RuntimeException"));
        method.maxStack = 1;
        method.maxLocals = 1;
        donor.methods.add(method);
        MixinPrimitiveArrayBridge.Result result = MixinPrimitiveArrayBridge.lower(donor, "fixture/CastHelper");
        assertEquals(2, result.operations());
        assertEquals(1, result.helper().methods.size());
        assertSame(start, method.tryCatchBlocks.get(0).start);
        assertSame(end, method.tryCatchBlocks.get(0).end);
        Class<?> type = loader(donor, result.helper()).loadClass(donor.name.replace('/', '.'));
        assertEquals(7, type.getMethod("safe", Object.class).invoke(null, new byte[7]));
        assertEquals(-1, type.getMethod("safe", Object.class).invoke(null, "wrong"));
        assertEquals(-1, type.getMethod("safe", Object.class).invoke(null, new Object[]{null}));
    }

    private static ClassNode owner(String name, boolean mixin) {
        ClassNode node = new ClassNode();
        node.visit(V1_8, ACC_PUBLIC, name, null, "java/lang/Object", null);
        if (mixin) node.invisibleAnnotations = List.of(new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;"));
        return node;
    }
    private static void add(ClassNode owner, String name, String desc, int operation, String type, int load, int result) {
        MethodNode method = new MethodNode(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        method.instructions.add(new VarInsnNode(load, 0));
        method.instructions.add(new TypeInsnNode(operation, type));
        method.instructions.add(new InsnNode(result));
        method.maxStack = 1;
        method.maxLocals = 1;
        owner.methods.add(method);
    }
    private static ClassLoader loader(ClassNode... nodes) {
        Map<String, byte[]> classes = new HashMap<>();
        for (ClassNode node : nodes) {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            classes.put(node.name.replace('/', '.'), writer.toByteArray());
        }
        return new ClassLoader(MixinPrimitiveArrayBridgeTest.class.getClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }
}
