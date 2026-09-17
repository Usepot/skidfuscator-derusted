package dev.skidfuscator.nativetoolchain;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Internal deterministic encoding used as the signature input. */
final class CanonicalProperties {
    private CanonicalProperties() {
    }

    static byte[] encode(final Map<String, String> properties) {
        final StringBuilder builder = new StringBuilder();
        properties.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> builder
                .append(entry.getKey()).append('=').append(encodeValue(entry.getValue())).append('\n'));
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    static Map<String, String> decode(final byte[] bytes) {
        final String text = new String(bytes, StandardCharsets.UTF_8);
        final Map<String, String> values = new LinkedHashMap<>();
        for (final String line : text.split("\\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            final int separator = line.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException("Malformed canonical manifest line");
            }
            final String key = line.substring(0, separator);
            if (!key.matches("[A-Za-z0-9_.-]+")) {
                throw new IllegalArgumentException("Malformed canonical manifest key: " + key);
            }
            if (values.putIfAbsent(key, decodeValue(line.substring(separator + 1))) != null) {
                throw new IllegalArgumentException("Duplicate canonical manifest key: " + key);
            }
        }
        return values;
    }

    private static String encodeValue(final String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeValue(final String value) {
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (final IllegalArgumentException exception) {
            throw new IllegalArgumentException("Malformed canonical manifest value", exception);
        }
    }
}
