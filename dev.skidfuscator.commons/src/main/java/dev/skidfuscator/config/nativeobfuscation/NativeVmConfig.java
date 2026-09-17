package dev.skidfuscator.config.nativeobfuscation;

import com.typesafe.config.Config;
import dev.skidfuscator.config.DefaultConfig;

/**
 * Runtime protection options for the native VM backend.
 */
public final class NativeVmConfig extends DefaultConfig {
    public NativeVmConfig(final Config config, final String path) {
        super(config, path);
    }

    public NativeVmProfile getProfile() {
        return getEnum("profile", NativeVmProfile.AGGRESSIVE);
    }

    public NativeVmResponse getResponse() {
        return getEnum("response", NativeVmResponse.DELAYED_HALT);
    }

    public boolean isIntegrityEnabled() {
        return getBoolean("integrity", true);
    }

    public boolean isAntiDebugEnabled() {
        return getBoolean("antiDebug", true);
    }

    public boolean isAntiInstrumentationEnabled() {
        return getBoolean("antiInstrumentation", true);
    }

    public boolean isTimingChecksEnabled() {
        return getBoolean("timingChecks", true);
    }
}
