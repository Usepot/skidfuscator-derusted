package dev.skidfuscator.obfuscator.compatibility;

import dev.skidfuscator.obfuscator.Skidfuscator;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Generated obfuscation support must not re-enter the application transformer
 * that is currently loading that support. Forge's transformer exclusions only
 * control its LOAD-TIME transformer chain; they do not exempt any bytecode from
 * Skidfuscator. We declare individual generated support names, never an input
 * client package, and retain the fully transformed input methods.
 */
public final class BootstrapRuntimeContracts {
    private static final String FORGE = "net/minecraftforge/fml/relauncher/IFMLLoadingPlugin";
    private static final String LEGACY = "cpw/mods/fml/relauncher/IFMLLoadingPlugin";
    private final Set<ClassNode> originalNodes = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<String> originalNames;

    public BootstrapRuntimeContracts(Collection<ClassNode> input) {
        originalNodes.addAll(input);
        // Tree identity alone is insufficient: a pass may replace an input
        // ClassNode with an equivalent tree. That is still application code.
        originalNames = input.stream().map(node -> node.name).collect(Collectors.toUnmodifiableSet());
    }

    private static List<ClassNode> nodes(Skidfuscator skid) {
        return skid.getJarContents().getClassContents().stream()
                .map(entry -> entry.getClassNode().node).collect(Collectors.toList());
    }

    public static BootstrapRuntimeContracts capture(Skidfuscator skid) {
        return new BootstrapRuntimeContracts(nodes(skid));
    }

    private Predicate<ClassNode> generated(UnaryOperator<String> finalName) {
        Set<String> finalInputNames = originalNames.stream().map(finalName).collect(Collectors.toSet());
        return node -> !originalNodes.contains(node) && !originalNames.contains(node.name)
                && !finalInputNames.contains(finalName.apply(node.name));
    }

    public void install(Skidfuscator skid) {
        List<ClassNode> emitted = nodes(skid);
        int plugins = install(emitted, name -> {
            org.mapleir.asm.ClassNode node = skid.getClassSource().findClassNode(name);
            return node == null ? null : node.node;
        }, skid.getClassRemapper()::mapType);
        if (plugins != 0) {
            long support = emitted.stream().filter(generated(skid.getClassRemapper()::mapType)).count();
            Skidfuscator.LOGGER.warn("BOOTSTRAP_RUNTIME_CONTRACTS: " + plugins
                    + " Forge plugin(s), " + support + " generated support classes isolated from load-time re-entry;"
                    + " input client bodies remain obfuscated, no input-package exclusion added.");
        }
    }

    public int install(Collection<ClassNode> emitted, Function<String, ClassNode> resolve,
                       UnaryOperator<String> finalName) {
        Map<String, ClassNode> byName = emitted.stream().collect(Collectors.toMap(
                node -> node.name, node -> node, (a, b) -> {
                    throw new IllegalStateException("Duplicate output class: " + a.name);
                }, LinkedHashMap::new));
        Function<String, ClassNode> lookup = name -> byName.containsKey(name) ? byName.get(name) : resolve.apply(name);
        Map<ClassNode, String> plugins = new LinkedHashMap<>();
        for (ClassNode node : emitted) {
            String contract = implementsType(node, FORGE, lookup, new HashSet<>()) ? FORGE
                    : implementsType(node, LEGACY, lookup, new HashSet<>()) ? LEGACY : null;
            if (contract != null) plugins.put(node, contract);
        }
        // Do not impose Forge's prefix restrictions on unrelated Java programs.
        if (plugins.isEmpty()) return 0;
        Predicate<ClassNode> isGenerated = generated(finalName);
        SortedSet<String> support = emitted.stream().filter(isGenerated)
                .map(node -> finalName.apply(node.name)).collect(Collectors.toCollection(TreeSet::new));
        if (support.isEmpty()) return 0;
        // LaunchWrapper matches prefixes. Refuse accidental overlap with input
        // classes rather than silently granting a broad input-class exclusion.
        for (ClassNode input : emitted) {
            if (isGenerated.test(input)) continue;
            String name = finalName.apply(input.name);
            for (String helper : support) {
                if (name.startsWith(helper)) {
                    throw new IllegalStateException("Generated bootstrap exclusion overlaps input class: "
                            + helper + " / " + name);
                }
            }
        }
        for (Map.Entry<ClassNode, String> plugin : plugins.entrySet()) {
            ClassNode node = plugin.getKey();
            if (node.visibleAnnotations == null) node.visibleAnnotations = new ArrayList<>();
            String descriptor = "L" + plugin.getValue() + "$TransformerExclusions;";
            AnnotationNode annotation = node.visibleAnnotations.stream().filter(a -> a.desc.equals(descriptor))
                    .findFirst().orElse(null);
            if (annotation == null) {
                annotation = new AnnotationNode(descriptor);
                node.visibleAnnotations.add(annotation);
            }
            if (annotation.values == null) annotation.values = new ArrayList<>();
            else annotation.values = new ArrayList<>(annotation.values);
            List<String> values = new ArrayList<>();
            int valueIndex = -1;
            for (int i = 0; i < annotation.values.size(); i += 2) {
                if (!"value".equals(annotation.values.get(i))) continue;
                if (i + 1 >= annotation.values.size() || valueIndex >= 0
                        || !(annotation.values.get(i + 1) instanceof List<?> existing)) {
                    throw new IllegalStateException("Invalid TransformerExclusions on " + node.name);
                }
                for (Object value : existing) {
                    if (!(value instanceof String name))
                        throw new IllegalStateException("Non-string TransformerExclusions on " + node.name);
                    values.add(name);
                }
                valueIndex = i + 1;
            }
            for (String name : support) {
                String dotted = name.replace('/', '.');
                if (!values.contains(dotted)) values.add(dotted);
            }
            if (valueIndex < 0) {
                annotation.values.add("value");
                annotation.values.add(values);
            } else annotation.values.set(valueIndex, values);
        }
        return plugins.size();
    }

    private static boolean implementsType(ClassNode node, String target, Function<String, ClassNode> lookup,
                                          Set<String> seen) {
        if (node == null || !seen.add(node.name)) return false;
        if (target.equals(node.name) || node.interfaces.contains(target)) return true;
        if (node.superName != null && !"java/lang/Object".equals(node.superName)
                && implementsType(lookup.apply(node.superName), target, lookup, seen)) return true;
        for (String iface : node.interfaces)
            if (implementsType(lookup.apply(iface), target, lookup, seen)) return true;
        return false;
    }
}
