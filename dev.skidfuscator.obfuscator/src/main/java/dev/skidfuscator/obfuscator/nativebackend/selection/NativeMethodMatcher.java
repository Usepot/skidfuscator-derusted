package dev.skidfuscator.obfuscator.nativebackend.selection;

import org.mapleir.asm.ClassNode;
import org.mapleir.asm.MethodNode;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Matches native-obfuscation rules using the familiar {@code class{...}}
 * and {@code method{...}} syntax.
 *
 * <p>Unlike the legacy exemption matcher, a rule containing both clauses
 * requires both clauses to match. This prevents a class qualifier from
 * accidentally selecting an identically named method in every class.</p>
 */
public final class NativeMethodMatcher {
    private static final Set<String> MODIFIERS = Set.of(
            "public", "private", "protected", "static", "final", "abstract",
            "synchronized", "native", "strictfp", "synthetic", "bridge", "interface",
            "annotation", "enum"
    );
    private static final Set<String> CLASS_MODIFIERS = Set.of(
            "public", "private", "protected", "static", "final", "abstract",
            "synthetic", "interface", "annotation", "enum"
    );
    private static final Set<String> METHOD_MODIFIERS = Set.of(
            "public", "private", "protected", "static", "final", "abstract",
            "synchronized", "native", "strictfp", "synthetic", "bridge"
    );

    private final String source;
    private final Clause classClause;
    private final Clause methodClause;
    private final Pattern rawPattern;

    private NativeMethodMatcher(String source, Clause classClause, Clause methodClause, Pattern rawPattern) {
        this.source = source;
        this.classClause = classClause;
        this.methodClause = methodClause;
        this.rawPattern = rawPattern;
    }

    public static NativeMethodMatcher compile(final String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            throw new IllegalArgumentException("Native matcher cannot be empty");
        }

        final String trimmed = expression.trim();
        final List<ParsedClause> clauses = parseClauses(trimmed);
        Clause classClause = null;
        Clause methodClause = null;

        for (ParsedClause parsed : clauses) {
            final Clause clause = parseClause(trimmed, parsed.body, parsed.kind);
            if (parsed.kind.equals("class")) {
                if (classClause != null) {
                    throw invalid(trimmed, "Duplicate class clause");
                }
                classClause = clause;
            } else {
                if (methodClause != null) {
                    throw invalid(trimmed, "Duplicate method clause");
                }
                methodClause = clause;
            }
        }

        if (classClause == null && methodClause == null) {
            try {
                return new NativeMethodMatcher(trimmed, null, null, Pattern.compile(trimmed));
            } catch (PatternSyntaxException ex) {
                throw invalid(trimmed, "Invalid raw method regular expression", ex);
            }
        }
        return new NativeMethodMatcher(trimmed, classClause, methodClause, null);
    }

    /** Parses balanced matcher braces so regular-expression quantifiers remain valid. */
    private static List<ParsedClause> parseClauses(final String expression) {
        final List<ParsedClause> clauses = new ArrayList<>();
        int cursor = 0;
        while (cursor < expression.length()) {
            while (cursor < expression.length() && Character.isWhitespace(expression.charAt(cursor))) {
                cursor++;
            }
            if (cursor == expression.length()) {
                break;
            }

            final int kindStart = cursor;
            while (cursor < expression.length() && Character.isLetter(expression.charAt(cursor))) {
                cursor++;
            }
            final String kind = expression.substring(kindStart, cursor).toLowerCase(Locale.ROOT);
            while (cursor < expression.length() && Character.isWhitespace(expression.charAt(cursor))) {
                cursor++;
            }
            if (!(kind.equals("class") || kind.equals("method"))
                    || cursor >= expression.length()
                    || expression.charAt(cursor) != '{') {
                if (clauses.isEmpty()) {
                    return List.of();
                }
                throw invalid(expression, "Unexpected text outside matcher clause: "
                        + expression.substring(kindStart));
            }

            final int bodyStart = ++cursor;
            int depth = 1;
            boolean escaped = false;
            boolean characterClass = false;
            while (cursor < expression.length() && depth > 0) {
                final char current = expression.charAt(cursor);
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '[') {
                    characterClass = true;
                } else if (current == ']' && characterClass) {
                    characterClass = false;
                } else if (!characterClass && current == '{') {
                    depth++;
                } else if (!characterClass && current == '}') {
                    depth--;
                }
                cursor++;
            }
            if (depth != 0) {
                throw invalid(expression, "Unclosed " + kind + " clause");
            }
            clauses.add(new ParsedClause(kind, expression.substring(bodyStart, cursor - 1)));
        }
        return clauses;
    }

    public boolean matches(final MethodNode method) {
        if (method == null || method.getOwnerClass() == null) {
            return false;
        }
        if (rawPattern != null) {
            final String qualified = method.getOwner() + "#" + method.getName() + method.getDesc();
            return rawPattern.matcher(qualified).matches();
        }
        return (classClause == null || matchesClass(classClause, method.getOwnerClass()))
                && (methodClause == null || matchesMethod(methodClause, method));
    }

    public String getSource() {
        return source;
    }

    private static Clause parseClause(final String expression, final String body, final String kind) {
        final String trimmed = body.trim();
        if (trimmed.isEmpty()) {
            throw invalid(expression, "Matcher clause cannot be empty");
        }

        final List<String> tokens = new ArrayList<>(Arrays.asList(trimmed.split("\\s+")));
        final Set<String> modifiers = new HashSet<>();
        while (tokens.size() > 1 && MODIFIERS.contains(tokens.get(0).toLowerCase(Locale.ROOT))) {
            modifiers.add(tokens.remove(0).toLowerCase(Locale.ROOT));
        }
        final Set<String> allowed = kind.equals("class") ? CLASS_MODIFIERS : METHOD_MODIFIERS;
        if (!allowed.containsAll(modifiers)) {
            final Set<String> invalidModifiers = new HashSet<>(modifiers);
            invalidModifiers.removeAll(allowed);
            throw invalid(expression, "Invalid " + kind + " modifier(s): " + invalidModifiers);
        }
        final String patternText = String.join(" ", tokens);
        try {
            return new Clause(Pattern.compile(patternText), modifiers);
        } catch (PatternSyntaxException ex) {
            throw invalid(expression, "Invalid clause regular expression: " + patternText, ex);
        }
    }

    private static boolean matchesClass(final Clause clause, final ClassNode owner) {
        if (!clause.pattern.matcher(owner.getName()).find()) {
            return false;
        }
        for (String modifier : clause.modifiers) {
            final boolean matches = switch (modifier) {
                case "public" -> owner.isPublic();
                case "private" -> owner.isPrivate();
                case "protected" -> owner.isProtected();
                case "static" -> owner.isStatic();
                case "final" -> owner.isFinal();
                case "abstract" -> owner.isAbstract();
                case "interface" -> owner.isInterface();
                case "annotation" -> owner.isAnnotation();
                case "enum" -> owner.isEnum();
                case "synthetic" -> owner.isSynthetic();
                default -> true;
            };
            if (!matches) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesMethod(final Clause clause, final MethodNode method) {
        final String patternText = clause.pattern.pattern();
        final int descriptorSeparator = patternText.indexOf('#');
        final Pattern namePattern;
        final Pattern descriptorPattern;
        try {
            namePattern = descriptorSeparator < 0
                    ? clause.pattern
                    : Pattern.compile(patternText.substring(0, descriptorSeparator));
            descriptorPattern = descriptorSeparator < 0
                    ? null
                    : Pattern.compile(patternText.substring(descriptorSeparator + 1));
        } catch (PatternSyntaxException ex) {
            throw invalid(patternText, "Invalid method name or descriptor expression", ex);
        }

        if (!namePattern.matcher(method.getName()).matches()
                || descriptorPattern != null && !descriptorPattern.matcher(method.getDesc()).matches()) {
            return false;
        }
        for (String modifier : clause.modifiers) {
            final boolean matches = switch (modifier) {
                case "public" -> method.isPublic();
                case "private" -> method.isPrivate();
                case "protected" -> method.isProtected();
                case "static" -> method.isStatic();
                case "final" -> method.isFinal();
                case "abstract" -> method.isAbstract();
                case "synchronized" -> (method.node.access & Opcodes.ACC_SYNCHRONIZED) != 0;
                case "native" -> method.isNative();
                case "strictfp" -> (method.node.access & Opcodes.ACC_STRICT) != 0;
                case "synthetic" -> method.isSynthetic();
                case "bridge" -> method.isBridge();
                default -> true;
            };
            if (!matches) {
                return false;
            }
        }
        return true;
    }

    private static IllegalArgumentException invalid(final String expression, final String message) {
        return new IllegalArgumentException(message + " [" + expression + "]");
    }

    private static IllegalArgumentException invalid(final String expression,
                                                    final String message,
                                                    final Exception cause) {
        return new IllegalArgumentException(message + " [" + expression + "]", cause);
    }

    private record Clause(Pattern pattern, Set<String> modifiers) {
    }

    private record ParsedClause(String kind, String body) {
    }
}
