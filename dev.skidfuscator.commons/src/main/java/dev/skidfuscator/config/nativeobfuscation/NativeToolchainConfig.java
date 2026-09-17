package dev.skidfuscator.config.nativeobfuscation;

import com.typesafe.config.Config;
import dev.skidfuscator.config.DefaultConfig;

/**
 * Configuration for resolving and validating SkidLLVM.
 */
public final class NativeToolchainConfig extends DefaultConfig {
    public static final String DEFAULT_VERSION = "1.0.0-alpha.1";
    public static final int NATIVE_IR_ABI = 1;

    public NativeToolchainConfig(final Config config, final String path) {
        super(config, path);
    }

    public NativeToolchainDelivery getDelivery() {
        return getEnum("delivery", NativeToolchainDelivery.AUTO);
    }

    /**
     * Returns an explicitly configured toolchain path, or an empty string when
     * normal delivery resolution should be used.
     */
    public String getPath() {
        return getString("path", "");
    }

    public String getVersion() {
        return getString("version", DEFAULT_VERSION);
    }

    public int getNativeIrAbi() {
        return getInt("nativeIrAbi", NATIVE_IR_ABI);
    }
}
