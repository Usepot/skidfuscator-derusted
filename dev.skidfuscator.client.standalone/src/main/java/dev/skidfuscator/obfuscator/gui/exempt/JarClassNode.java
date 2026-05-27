package dev.skidfuscator.obfuscator.gui.exempt;

/**
 * Node data attached to {@code DefaultMutableTreeNode} entries in the
 * exemption tree. Represents either an intermediate package directory or a
 * leaf {@code .class} entry inside a jar.
 */
public class JarClassNode {

    public enum State { UNCHECKED, CHECKED, PARTIAL }

    private final String label;       // simple name shown in the tree
    private final String path;        // internal path (foo/bar or foo/bar/Baz)
    private final boolean classLeaf;  // true for actual .class entries
    private State state = State.UNCHECKED;

    public JarClassNode(String label, String path, boolean classLeaf) {
        this.label = label;
        this.path = path;
        this.classLeaf = classLeaf;
    }

    public String getLabel() { return label; }
    public String getPath()  { return path; }
    public boolean isClassLeaf() { return classLeaf; }
    public State getState() { return state; }
    public void setState(State state) { this.state = state; }

    @Override
    public String toString() { return label; }
}
