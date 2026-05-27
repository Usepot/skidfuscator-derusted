package dev.skidfuscator.obfuscator.gui.exempt;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads a jar's class entries and produces a checkbox-tree model that mirrors
 * the package hierarchy.
 */
public final class JarTreeLoader {

    private JarTreeLoader() {}

    public static DefaultTreeModel loadJar(File jar) throws IOException {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(
                new JarClassNode(jar == null ? "(no jar)" : jar.getName(), "", false));
        if (jar == null || !jar.isFile()) {
            return new DefaultTreeModel(root);
        }
        // package path -> directory node
        Map<String, DefaultMutableTreeNode> dirs = new LinkedHashMap<>();
        dirs.put("", root);

        try (ZipFile zip = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (!name.endsWith(".class")) continue;
                if (name.startsWith("META-INF/")) continue;

                int slash = name.lastIndexOf('/');
                String packagePath = slash < 0 ? "" : name.substring(0, slash);
                String simple = slash < 0 ? name : name.substring(slash + 1);
                simple = simple.substring(0, simple.length() - ".class".length());

                DefaultMutableTreeNode parent = ensurePackage(dirs, packagePath);
                DefaultMutableTreeNode leaf = new DefaultMutableTreeNode(
                        new JarClassNode(simple, packagePath.isEmpty() ? simple : packagePath + "/" + simple, true));
                parent.add(leaf);
            }
        }

        sortRecursively(root);
        return new DefaultTreeModel(root);
    }

    private static DefaultMutableTreeNode ensurePackage(Map<String, DefaultMutableTreeNode> dirs, String path) {
        DefaultMutableTreeNode cached = dirs.get(path);
        if (cached != null) return cached;
        int slash = path.lastIndexOf('/');
        String parentPath = slash < 0 ? "" : path.substring(0, slash);
        String simple = slash < 0 ? path : path.substring(slash + 1);
        DefaultMutableTreeNode parent = ensurePackage(dirs, parentPath);
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(new JarClassNode(simple, path, false));
        parent.add(node);
        dirs.put(path, node);
        return node;
    }

    @SuppressWarnings("unchecked")
    private static void sortRecursively(DefaultMutableTreeNode node) {
        if (node.getChildCount() == 0) return;
        java.util.List<DefaultMutableTreeNode> children = new java.util.ArrayList<>();
        for (Enumeration<?> e = node.children(); e.hasMoreElements(); ) {
            children.add((DefaultMutableTreeNode) e.nextElement());
        }
        // Packages first, then classes, both alphabetical.
        Collections.sort(children, (a, b) -> {
            JarClassNode na = (JarClassNode) a.getUserObject();
            JarClassNode nb = (JarClassNode) b.getUserObject();
            if (na.isClassLeaf() != nb.isClassLeaf()) return na.isClassLeaf() ? 1 : -1;
            return na.getLabel().compareToIgnoreCase(nb.getLabel());
        });
        node.removeAllChildren();
        for (DefaultMutableTreeNode c : children) {
            node.add(c);
            sortRecursively(c);
        }
    }
}
