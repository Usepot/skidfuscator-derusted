package dev.skidfuscator.obfuscator.gui.exempt;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Backs one row in the exemption builder. Holds either:
 *   - a structured class rule (kind + modifiers + name + extends/implements + members), or
 *   - a free-form raw pattern that the user typed directly.
 */
public class ExemptionRule {

    public enum ClassKind {
        CLASS("class"),
        INTERFACE("interface"),
        ABSTRACT("abstract"),
        ANNOTATION("annotation"),
        ENUM("enum");

        private final String token;
        ClassKind(String token) { this.token = token; }
        public String token() { return token; }
    }

    private boolean rawMode = false;
    private String raw = "";

    private ClassKind classKind = ClassKind.CLASS;
    private final List<String> modifiers = new ArrayList<>();
    private String name = "";              // e.g. com.example.* or com.example.Foo
    private String extendsClass = "";
    private final List<String> implementsList = new ArrayList<>();
    private final List<ExemptionMember> members = new ArrayList<>();

    public boolean isRawMode() { return rawMode; }
    public void setRawMode(boolean rawMode) { this.rawMode = rawMode; }

    public String getRaw() { return raw; }
    public void setRaw(String raw) { this.raw = raw == null ? "" : raw; }

    public ClassKind getClassKind() { return classKind; }
    public void setClassKind(ClassKind classKind) { this.classKind = classKind; }

    public List<String> getModifiers() { return modifiers; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name == null ? "" : name; }

    public String getExtendsClass() { return extendsClass; }
    public void setExtendsClass(String extendsClass) { this.extendsClass = extendsClass == null ? "" : extendsClass; }

    public List<String> getImplementsList() { return implementsList; }

    public List<ExemptionMember> getMembers() { return members; }

    /**
     * Render this rule into the @class .. { @method .. } form the parser accepts.
     * Raw rules are emitted verbatim.
     */
    public String render() {
        if (rawMode) return raw == null ? "" : raw.trim();

        StringBuilder sb = new StringBuilder();
        sb.append('@').append(classKind.token());
        for (String m : modifiers) {
            if (m != null && !m.isBlank()) sb.append(' ').append(m.trim());
        }
        if (name != null && !name.isBlank()) {
            sb.append(' ').append(name.trim());
        }
        if (extendsClass != null && !extendsClass.isBlank()) {
            sb.append(" extends ").append(extendsClass.trim());
        }
        if (!implementsList.isEmpty()) {
            String impls = implementsList.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(String::trim)
                    .collect(Collectors.joining(", "));
            if (!impls.isEmpty()) sb.append(" implements ").append(impls);
        }
        sb.append(" {");
        if (members.isEmpty()) {
            sb.append("\n}");
        } else {
            sb.append('\n');
            for (ExemptionMember m : members) {
                sb.append("    ").append(m.render()).append('\n');
            }
            sb.append('}');
        }
        return sb.toString();
    }

    /** Short single-line description used in the rules list. */
    public String summary() {
        if (rawMode) {
            String first = raw == null ? "" : raw.replace('\n', ' ').trim();
            if (first.length() > 80) first = first.substring(0, 77) + "…";
            return first.isEmpty() ? "(empty raw rule)" : first;
        }
        StringBuilder sb = new StringBuilder();
        sb.append('@').append(classKind.token()).append(' ');
        if (!modifiers.isEmpty()) sb.append(modifiers.stream().collect(Collectors.joining(" "))).append(' ');
        sb.append(name == null || name.isBlank() ? "*" : name.trim());
        if (!members.isEmpty()) sb.append("  (+").append(members.size()).append(members.size() == 1 ? " member)" : " members)");
        return sb.toString();
    }
}
