package dev.skidfuscator.obfuscator.creator;

import dev.skidfuscator.obfuscator.compatibility.BootstrapRuntimeContracts;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.util.*;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class BootstrapRuntimeContractsTest {
    private static final String API = "net/minecraftforge/fml/relauncher/IFMLLoadingPlugin";
    private static ClassNode type(String name, String... interfaces) {
        ClassNode node = new ClassNode();
        node.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", interfaces);
        return node;
    }
    @SuppressWarnings("unchecked") private static List<String> values(ClassNode plugin) {
        return (List<String>) plugin.visibleAnnotations.get(0).values.get(1);
    }

    @Test void declaresOnlyGeneratedFinalNamesAndPreservesInputBodies() {
        ClassNode plugin = type("client/Core", API), client = type("client/Feature");
        BootstrapRuntimeContracts contract = new BootstrapRuntimeContracts(Arrays.asList(plugin, client));
        ClassNode driver = type("skid/Driver"), sdk = type("sdk/Hasher");
        int plugins = contract.install(Arrays.asList(plugin, client, driver, sdk), n -> null,
                n -> n.equals("skid/Driver") ? "opaque/Driver123" : n);
        assertEquals(1, plugins);
        assertEquals(Arrays.asList("opaque.Driver123", "sdk.Hasher"), values(plugin));
        assertNull(client.visibleAnnotations);
        assertEquals(0, plugin.methods.size());
    }

    @Test void preservesExistingExclusionsAndIsIdempotentForInheritedPlugin() {
        ClassNode base = type("client/Base", API), plugin = type("client/Core");
        plugin.superName = base.name;
        AnnotationNode annotation = new AnnotationNode("L" + API + "$TransformerExclusions;");
        annotation.values = new ArrayList<>(Arrays.asList("value", new ArrayList<>(Arrays.asList("existing.Helper"))));
        plugin.visibleAnnotations = new ArrayList<>(Arrays.asList(annotation));
        BootstrapRuntimeContracts contract = new BootstrapRuntimeContracts(Arrays.asList(base, plugin));
        ClassNode helper = type("sdk/Hasher");
        List<ClassNode> all = Arrays.asList(base, plugin, helper);
        assertEquals(2, contract.install(all, n -> null, UnaryOperator.identity()));
        contract.install(all, n -> null, UnaryOperator.identity());
        assertEquals(Arrays.asList("existing.Helper", "sdk.Hasher"), values(plugin));
    }

    @Test void inputNodeReplacementDoesNotBecomeAHelperExemption() {
        ClassNode plugin = type("client/Core", API), original = type("client/Feature");
        BootstrapRuntimeContracts contract = new BootstrapRuntimeContracts(Arrays.asList(plugin, original));
        ClassNode rewritten = type("client/Feature"), helper = type("sdk/Hasher");
        contract.install(Arrays.asList(plugin, rewritten, helper), n -> null, UnaryOperator.identity());
        assertEquals(Collections.singletonList("sdk.Hasher"), values(plugin));
    }

    @Test void refusesPrefixOverlapInsteadOfExemptingAnInputClass() {
        ClassNode plugin = type("client/Core", API), input = type("sdk/HelperUserCode");
        BootstrapRuntimeContracts contract = new BootstrapRuntimeContracts(Arrays.asList(plugin, input));
        ClassNode helper = type("sdk/Helper");
        assertThrows(IllegalStateException.class, () -> contract.install(
                Arrays.asList(plugin, input, helper), n -> null, UnaryOperator.identity()));
    }

    @Test void remappedReplacementIsStillInputCode() {
        ClassNode plugin = type("client/Core", API), original = type("client/Feature");
        BootstrapRuntimeContracts contract = new BootstrapRuntimeContracts(Arrays.asList(plugin, original));
        ClassNode rewritten = type("renamed/Feature"), helper = type("sdk/Hasher");
        contract.install(Arrays.asList(plugin, rewritten, helper), n -> null,
                n -> n.equals("client/Feature") ? "renamed/Feature" : n);
        assertEquals(Collections.singletonList("sdk.Hasher"), values(plugin));
    }

    @Test void immutableExistingAnnotationListsArePreserved() {
        ClassNode plugin = type("client/Core", API);
        AnnotationNode annotation = new AnnotationNode("L" + API + "$TransformerExclusions;");
        annotation.values = List.of("value", List.of("existing.Helper"));
        plugin.visibleAnnotations = List.of(annotation);
        BootstrapRuntimeContracts contract = new BootstrapRuntimeContracts(List.of(plugin));
        contract.install(List.of(plugin, type("sdk/Hasher")), n -> null, UnaryOperator.identity());
        assertEquals(List.of("existing.Helper", "sdk.Hasher"), values(plugin));
    }

    @Test void nonForgePrefixOverlapDoesNotImposeForgeRestrictions() {
        ClassNode input = type("sdk/HelperUserCode");
        BootstrapRuntimeContracts contract = new BootstrapRuntimeContracts(List.of(input));
        assertEquals(0, contract.install(List.of(input, type("sdk/Helper")), n -> null, UnaryOperator.identity()));
        assertNull(input.visibleAnnotations);
    }

    @Test void supportsLegacyForgeNamespace() {
        String legacy = "cpw/mods/fml/relauncher/IFMLLoadingPlugin";
        ClassNode plugin = type("client/Core", legacy);
        BootstrapRuntimeContracts contract = new BootstrapRuntimeContracts(List.of(plugin));
        assertEquals(1, contract.install(List.of(plugin, type("sdk/Hasher")), n -> null, UnaryOperator.identity()));
        assertEquals("L" + legacy + "$TransformerExclusions;", plugin.visibleAnnotations.get(0).desc);
        assertEquals(List.of("sdk.Hasher"), values(plugin));
    }

    @Test void nonForgeProgramsGetNoLoaderMetadata() {
        ClassNode input = type("client/Main");
        BootstrapRuntimeContracts contract = new BootstrapRuntimeContracts(Collections.singletonList(input));
        assertEquals(0, contract.install(Arrays.asList(input, type("sdk/Helper")), n -> null, UnaryOperator.identity()));
        assertNull(input.visibleAnnotations);
    }
}
