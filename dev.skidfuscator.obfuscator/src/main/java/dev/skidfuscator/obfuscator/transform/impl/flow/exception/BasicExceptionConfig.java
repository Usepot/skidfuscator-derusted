package dev.skidfuscator.obfuscator.transform.impl.flow.exception;

import com.typesafe.config.Config;
import dev.skidfuscator.config.DefaultTransformerConfig;

public class BasicExceptionConfig extends DefaultTransformerConfig {
    public BasicExceptionConfig(Config config, String path) {
        super(config, path);
    }

    public BasicExceptionStrength getStrength() {
        return getEnum("strength", BasicExceptionStrength.GOOD);
    }

    public boolean isDecoyCallsEnabled() {
        return getBoolean("decoyCalls.enabled", false);
    }

    public BasicExceptionDecoyScope getDecoyCallScope() {
        return getEnum("decoyCalls.scope", BasicExceptionDecoyScope.APPLICATION_AND_EXEMPT);
    }

    public boolean isDecoyCallLibrariesEnabled() {
        return getBoolean("decoyCalls.includeLibraries", false);
    }

    public int getDecoyCallMaxArgs() {
        return getInt("decoyCalls.maxArgs", 5);
    }
}
