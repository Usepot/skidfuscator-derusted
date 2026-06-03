package dev.skidfuscator.obfuscator.transform.impl.integrity;

import com.typesafe.config.Config;
import dev.skidfuscator.config.DefaultTransformerConfig;

public class TamperProtectionConfig extends DefaultTransformerConfig {

    public TamperProtectionConfig(final Config config, final String path) {
        super(config, path);
    }

    /**
     * Off by default. Tamper protection is a hardening feature with runtime
     * implications, so — unlike the default transformer — an absent config key
     * means disabled, not enabled.
     */
    @Override
    public boolean isEnabled() {
        return this.getBoolean("enabled", false);
    }

    /**
     * Reaction on a checksum mismatch: {@code THROW} (raise an error from the
     * verifying class) or {@code EXIT} (halt the JVM).
     */
    public String getAction() {
        return this.getString("action", "THROW");
    }
}
