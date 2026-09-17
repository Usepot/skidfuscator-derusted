package dev.skidfuscator.obfuscator.creator;

import dev.skidfuscator.obfuscator.compatibility.RuntimeContractRegistry;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BoundaryRuntimeContractTest implements Opcodes {
    private static ClassNode type(String name) {
        ClassNode node = new ClassNode();
        node.visit(V1_8, ACC_PUBLIC, name, null, "java/lang/Object", null);
        return node;
    }
    private static MethodNode method(ClassNode owner, String name, String desc) {
        MethodNode method = new MethodNode(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        owner.methods.add(method);
        return method;
    }
    private static RuntimeContractRegistry capture(Set<String> owned, ClassNode... nodes) {
        Map<String, ClassNode> classes = new LinkedHashMap<>();
        for (ClassNode node : nodes) classes.put(node.name, node);
        return new RuntimeContractRegistry(classes, owned, classes::get, RuntimeContractRegistry.Options.defaults());
    }

    @Test void unrewrittenCallerPinsTargetSignatureButNotTargetBodyOrOtherMethods() {
        ClassNode implementation = type("client/Feature"), library = type("thirdparty/Caller");
        MethodNode entry = method(implementation, "getInstance", "()Lclient/Feature;");
        MethodNode internal = method(implementation, "internalOnly", "()V");
        method(library, "invoke", "()V").instructions.add(new MethodInsnNode(INVOKESTATIC,
                implementation.name, entry.name, entry.desc, false));
        RuntimeContractRegistry registry = capture(Set.of(implementation.name), implementation, library);
        assertTrue(registry.isMethodContract(entry));
        assertFalse(registry.isMethodContract(internal));
        entry.instructions.add(new InsnNode(ACONST_NULL));
        entry.instructions.add(new InsnNode(ARETURN));
        assertDoesNotThrow(registry::validate, "Body obfuscation must remain eligible");
        entry.desc = "(I)Lclient/Feature;";
        assertThrows(IllegalStateException.class, registry::validate);
    }

    @Test void resolvesInheritedDeclarationsFromUnrewrittenSubclasses() {
        ClassNode base = type("client/Base"), child = type("library/Child"), caller = type("library/Caller");
        child.superName = base.name;
        MethodNode target = method(base, "entry", "()V");
        method(caller, "invoke", "()V").instructions.add(new MethodInsnNode(INVOKESTATIC,
                child.name, target.name, target.desc, false));
        RuntimeContractRegistry registry = capture(Set.of(base.name), base, child, caller);
        assertTrue(registry.isMethodContract(target));
    }

    @Test void retainsMethodHandlesAndNestedDynamicConstants() {
        ClassNode target = type("client/Target"), caller = type("library/Caller");
        MethodNode direct = method(target, "direct", "()V"), nested = method(target, "nested", "()V"),
                lambda = method(target, "lambda", "()V");
        MethodNode use = method(caller, "invoke", "()V");
        Handle externalBootstrap = new Handle(H_INVOKESTATIC, "external/Bootstrap", "link", "()V", false);
        use.instructions.add(new LdcInsnNode(new Handle(H_INVOKESTATIC, target.name, direct.name, direct.desc, false)));
        use.instructions.add(new LdcInsnNode(new ConstantDynamic("constant", "Ljava/lang/Object;", externalBootstrap,
                new Handle(H_INVOKESTATIC, target.name, nested.name, nested.desc, false))));
        use.instructions.add(new InvokeDynamicInsnNode("run", "()Ljava/lang/Runnable;", externalBootstrap,
                new Handle(H_INVOKESTATIC, target.name, lambda.name, lambda.desc, false)));
        RuntimeContractRegistry registry = capture(Set.of(target.name), target, caller);
        assertTrue(registry.isMethodContract(direct));
        assertTrue(registry.isMethodContract(nested));
        assertTrue(registry.isMethodContract(lambda));
    }

    @Test void ownedOnlyCallsAreNotBoundaryContractsUntilCallerIsExplicitlyRetained() {
        ClassNode target = type("client/Target"), caller = type("client/Caller");
        MethodNode entry = method(target, "entry", "()V"), use = method(caller, "invoke", "()V");
        use.instructions.add(new MethodInsnNode(INVOKESTATIC, target.name, entry.name, entry.desc, false));
        RuntimeContractRegistry registry = capture(Set.of(target.name, caller.name), target, caller);
        assertFalse(registry.isMethodContract(entry));
        registry.captureUnrewrittenCallers(List.of(use));
        assertTrue(registry.isMethodContract(entry));
    }

    @Test void constructorsAreNotInheritedAndResolutionCyclesTerminate() {
        ClassNode base = type("client/Base"), child = type("library/Child"), caller = type("library/Caller");
        child.superName = base.name;
        base.interfaces.add(child.name); // deliberately malformed graph, must still terminate
        MethodNode ctor = method(base, "<init>", "()V");
        MethodNode use = method(caller, "invoke", "()V");
        use.instructions.add(new MethodInsnNode(INVOKESPECIAL, child.name, "<init>", "()V", false));
        use.instructions.add(new MethodInsnNode(INVOKESTATIC, child.name, "absent", "()V", false));
        RuntimeContractRegistry registry = capture(Set.of(base.name), base, child, caller);
        assertFalse(registry.isMethodContract(ctor));
    }
}
