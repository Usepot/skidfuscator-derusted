package sdk;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SDK {
    /**
     * Exact comparison for encoded UTF-16 literals. Flags: bit 0 means the
     * original receiver was constant; bit 1 selects equalsIgnoreCase.
     * No case conversion is performed by the obfuscator: the executing JDK
     * supplies its own String comparison semantics (including supplementary chars).
     */
    public static boolean compareEncoded(Object dynamicOperand, String encodedLiteral, int key, int flags) {
        char[] chars = encodedLiteral.toCharArray();
        int state = key;
        for (int i = 0; i < chars.length; i++) {
            state = state * 1664525 + 1013904223;
            chars[i] ^= (char) (state >>> 16);
        }
        String literal = new String(chars);
        boolean constantReceiver = (flags & 1) != 0;
        if ((flags & 2) != 0) {
            return constantReceiver
                    ? literal.equalsIgnoreCase((String) dynamicOperand)
                    : ((String) dynamicOperand).equalsIgnoreCase(literal);
        }
        return constantReceiver
                ? literal.equals(dynamicOperand)
                : ((String) dynamicOperand).equals(literal);
    }

    public static String hash(String s) {
        return LongHashFunction.xx3().hashChars(s) + "";
    }

    private static final Map<Class<?>, String> TYPE_HASHES = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Set<String>> HIERARCHY_HASHES = new ConcurrentHashMap<>();

    public static boolean checkType(Object obj, String typeHash, int seed) {
        if (obj == null) {
            return false;
        }

        Class<?> objClass = obj.getClass();

        // Get or compute the full hierarchy hash set for this class
        Set<String> hierarchyHashes = HIERARCHY_HASHES.computeIfAbsent(
                objClass,
                e -> computeHierarchyHashes(objClass, seed)
        );

        return hierarchyHashes.contains(typeHash);
    }

    private static Set<String> computeHierarchyHashes(Class<?> cls, int seed) {
        Set<String> hashes = new HashSet<>();
        Queue<Class<?>> toProcess = new LinkedList<>();
        Set<Class<?>> processed = new HashSet<>();

        toProcess.add(cls);

        while (!toProcess.isEmpty()) {
            Class<?> current = toProcess.poll();

            if (current == null || !processed.add(current)) {
                continue;
            }

            // Add hash for current class
            hashes.add(TYPE_HASHES.computeIfAbsent(current,
                    c -> "" + LongHashFunction.xx3(seed)
                            .hashChars(c.getName().replace('.', '/'))));

            // Add superclass to process
            Class<?> superClass = current.getSuperclass();
            if (superClass != null) {
                toProcess.add(superClass);
            }

            // Add all interfaces to process (including inherited interfaces)
            toProcess.addAll(Arrays.asList(current.getInterfaces()));
        }

        return Collections.unmodifiableSet(hashes);
    }

    public static boolean checkEnumName(Enum<?> enumValue, String hashedName, int seed) {
        if (enumValue == null) {
            return false;
        }

        // Hash the enum's name and compare with the provided hash
        String actualHash = LongHashFunction.xx3(seed).hashChars(enumValue.name()) + "";
        return actualHash.equals(hashedName);
    }
}
