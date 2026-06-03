package dev.skidfuscator.obfuscator.transform.impl.integrity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Build-time model of the tamper-protection mesh.
 *
 * <p>An edge <b>A&nbsp;&rarr;&nbsp;B</b> means "class A verifies class B": A's
 * bytecode holds B's expected checksum. Nodes are keyed by <em>final</em> (post
 * remap) internal names so the stamping pass can resolve target bytes directly.</p>
 *
 * <p>Tier A requires the check-graph to be a DAG (whole-file hashing forbids
 * cycles), so {@link #dependencyOrder()} returns the order in which classes must
 * be frozen — every target before the holder that hashes it — or {@code null} if
 * a cycle is present.</p>
 */
public class IntegrityGraph {

    /** holder -> targets it verifies. */
    private final Map<String, List<String>> edges = new LinkedHashMap<>();
    private final Set<String> nodes = new LinkedHashSet<>();

    public void addNode(final String name) {
        nodes.add(name);
    }

    public void addEdge(final String holder, final String target) {
        nodes.add(holder);
        nodes.add(target);
        edges.computeIfAbsent(holder, k -> new ArrayList<>()).add(target);
    }

    public List<String> targetsOf(final String holder) {
        return edges.getOrDefault(holder, Collections.emptyList());
    }

    public Set<String> nodes() {
        return Collections.unmodifiableSet(nodes);
    }

    public boolean isEmpty() {
        return edges.isEmpty();
    }

    /** Classes verified by at least one sibling (they appear as a target). */
    public Set<String> checkedNodes() {
        final Set<String> checked = new LinkedHashSet<>();
        for (final List<String> targets : edges.values()) {
            checked.addAll(targets);
        }
        return checked;
    }

    /**
     * Eligible classes nobody verifies — the unavoidable structural weak point(s)
     * of a Tier A DAG. Keep these low-value / decoys and never the entrypoint.
     */
    public Set<String> exposedSources() {
        final Set<String> exposed = new LinkedHashSet<>(nodes);
        exposed.removeAll(checkedNodes());
        return exposed;
    }

    /**
     * Dependency order: each holder appears <em>after</em> all of its targets, so
     * a holder can be stamped once every class it hashes is frozen. Post-order DFS
     * over the check edges. Returns {@code null} on a cycle (not a DAG).
     */
    public List<String> dependencyOrder() {
        final List<String> order = new ArrayList<>();
        final Set<String> done = new HashSet<>();
        final Set<String> stack = new HashSet<>();
        for (final String node : nodes) {
            if (!visit(node, done, stack, order)) {
                return null;
            }
        }
        return order;
    }

    private boolean visit(final String node, final Set<String> done,
                          final Set<String> stack, final List<String> order) {
        if (done.contains(node)) {
            return true;
        }
        if (!stack.add(node)) {
            return false; // back-edge => cycle
        }
        for (final String target : targetsOf(node)) {
            if (!visit(target, done, stack, order)) {
                return false;
            }
        }
        stack.remove(node);
        done.add(node);
        order.add(node);
        return true;
    }
}
