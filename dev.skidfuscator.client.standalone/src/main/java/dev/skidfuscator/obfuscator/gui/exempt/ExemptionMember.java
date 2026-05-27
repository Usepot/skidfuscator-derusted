package dev.skidfuscator.obfuscator.gui.exempt;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a single member row (method or field) under a class rule.
 * Renders to a single @method/@field line in the exclusion grammar.
 */
public class ExemptionMember {
    public enum Kind { METHOD, FIELD }

    private Kind kind = Kind.METHOD;
    private final List<String> modifiers = new ArrayList<>();
    private String returnType = "";   // for methods, with optional `#` prefix in grammar
    private String name = "";
    private String parameters = "";   // raw, comma-separated, no parens

    public ExemptionMember() {}

    public ExemptionMember(Kind kind, List<String> modifiers, String returnType, String name, String parameters) {
        this.kind = kind;
        if (modifiers != null) this.modifiers.addAll(modifiers);
        this.returnType = returnType == null ? "" : returnType;
        this.name = name == null ? "" : name;
        this.parameters = parameters == null ? "" : parameters;
    }

    public Kind getKind() { return kind; }
    public void setKind(Kind kind) { this.kind = kind; }

    public List<String> getModifiers() { return modifiers; }

    public String getReturnType() { return returnType; }
    public void setReturnType(String returnType) { this.returnType = returnType == null ? "" : returnType; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name == null ? "" : name; }

    public String getParameters() { return parameters; }
    public void setParameters(String parameters) { this.parameters = parameters == null ? "" : parameters; }

    /**
     * Render to a single line of the exclusion grammar, e.g.:
     *   @method public static #void run(String, int)
     *   @field private static final #int COUNT
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append(kind == Kind.FIELD ? "@field" : "@method");
        for (String m : modifiers) {
            if (m != null && !m.isBlank()) sb.append(' ').append(m.trim());
        }
        if (returnType != null && !returnType.isBlank()) {
            String r = returnType.trim();
            sb.append(' ').append(r.startsWith("#") ? r : "#" + r);
        }
        if (name != null && !name.isBlank()) sb.append(' ').append(name.trim());
        if (kind == Kind.METHOD) {
            String p = parameters == null ? "" : parameters.trim();
            sb.append('(').append(p).append(')');
        }
        return sb.toString();
    }

    public String summary() {
        if (name == null || name.isBlank()) return kind == Kind.FIELD ? "(unnamed field)" : "(unnamed method)";
        StringBuilder sb = new StringBuilder();
        if (!modifiers.isEmpty()) sb.append(modifiers.stream().collect(Collectors.joining(" "))).append(' ');
        if (returnType != null && !returnType.isBlank()) sb.append(returnType.trim()).append(' ');
        sb.append(name.trim());
        if (kind == Kind.METHOD) sb.append('(').append(parameters == null ? "" : parameters.trim()).append(')');
        return sb.toString();
    }
}
