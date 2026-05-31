package dev.skidfuscator.obfuscator.config;

import com.typesafe.config.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Configuration generator for Skidfuscator transformers.
 * Handles HOCON configuration generation using Lightbend Config library.
 */
public class SkidfuscatorConfig {
    private final Map<String, ConfigValue> configMap = new LinkedHashMap<>();
    
    /**
     * Adds a transformer configuration block.
     * @param name Transformer name
     * @param enabled Enabled state
     * @param options Additional transformer options
     * @param exemptions List of exemption patterns
     */
    public void addTransformer(String name, boolean enabled, Map<String, Object> options, List<String> exemptions) {
        Map<String, Object> transformerConfig = new HashMap<>();
        transformerConfig.put("enabled", enabled);
        
        if (options != null && !options.isEmpty()) {
            for (Map.Entry<String, Object> entry : options.entrySet()) {
                putOption(transformerConfig, entry.getKey(), entry.getValue());
            }
        }
        
        if (exemptions != null && !exemptions.isEmpty()) {
            transformerConfig.put("exempt", exemptions);
        }
        
        configMap.put(name, ConfigValueFactory.fromMap(transformerConfig));
    }

    @SuppressWarnings("unchecked")
    private void putOption(Map<String, Object> target, String key, Object value) {
        String[] parts = key.split("\\.");
        Map<String, Object> current = target;

        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (!(next instanceof Map)) {
                next = new HashMap<String, Object>();
                current.put(parts[i], next);
            }

            current = (Map<String, Object>) next;
        }

        current.put(parts[parts.length - 1], value);
    }
    
    /**
     * Sets global exemptions for the obfuscator.
     * @param exemptions List of global exemption patterns
     */
    public void setGlobalExemptions(List<String> exemptions) {
        if (exemptions != null && !exemptions.isEmpty()) {
            configMap.put("exempt", ConfigValueFactory.fromIterable(exemptions));
        }
    }

    /**
     * Sets v2 global exclusions for the obfuscator.
     * @param exclusions List of v2 exclusion patterns
     */
    public void setGlobalExclusions(List<String> exclusions) {
        if (exclusions != null && !exclusions.isEmpty()) {
            configMap.put("exclude", ConfigValueFactory.fromIterable(exclusions));
        }
    }
    
    /**
     * Sets library dependencies for the obfuscator.
     * @param libraries List of library paths
     */
    public void setLibraries(List<String> libraries) {
        if (libraries != null && !libraries.isEmpty()) {
            configMap.put("libraries", ConfigValueFactory.fromIterable(libraries));
        }
    }
    
    /**
     * Generates the final Config object.
     * @return Config object containing all settings
     */
    public Config generateConfig() {
        return ConfigFactory.parseMap(configMap);
    }
    
    /**
     * Renders the configuration as a HOCON string.
     * @return Formatted HOCON configuration string
     */
    public String renderConfig() {
        return generateConfig().root().render(
            ConfigRenderOptions.defaults()
                .setOriginComments(false)
                .setComments(true)
                .setFormatted(true)
                .setJson(false)
        );
    }
}
