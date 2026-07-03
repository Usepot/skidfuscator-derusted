package dev.skidfuscator.config;

import com.typesafe.config.Config;

import java.io.File;
import java.util.Collections;

public class DefaultSkidConfig extends DefaultConfig {
    public DefaultSkidConfig(Config config, String path) {
        super(config, path);
    }

    public boolean isDriver() {
        return this.getBoolean("driver.enabled", true);
    }

    /**
     * Thread the per-block flow seed as a 64-bit {@code long} instead of a 32-bit
     * {@code int}. Default on for stronger serious/default protection; disabling
     * it restores the legacy int-seed pipeline.
     */
    public boolean isSeedWide() {
        return this.getBoolean("seed.wide", true);
    }

    /**
     * Whether flow-condition guard compression was <i>requested</i> in the config,
     * irrespective of whether its {@link #isSeedWide() seed.wide} prerequisite is met.
     * Used at wiring time to warn when the request had to be degraded.
     */
    public boolean isFlowConditionCompressingRequested() {
        return this.getBoolean("flowCondition.compressing.enabled", true);
    }

    /**
     * Effective flow-condition guard compression. The guard hashes the full 64-bit
     * seed through a non-injective {@code (J)I} pool so the baked constant {@code K}
     * no longer inverts to a unique seed. This is only meaningful once the seed
     * actually carries 64 bits of entropy, so it is force-disabled (silently here,
     * with a warning at wiring time) unless {@link #isSeedWide() seed.wide} is on.
     */
    public boolean isFlowConditionCompressing() {
        return isFlowConditionCompressingRequested() && isSeedWide();
    }

    /**
     * Optional extra input diffusion for the compressing guard: hash {@code seed ^ runtimeVal}
     * instead of the bare seed. Requires {@link #isFlowConditionCompressing() compression}.
     */
    public boolean isFlowConditionCompressingSalt() {
        return this.getBoolean("flowCondition.compressing.salt", true) && isFlowConditionCompressing();
    }

    public File[] getLibs() {
        return this.getStringList("libraries",
                        // [failsafe] i fucking made this mistake too
                        this.getStringList("libs", Collections.emptyList())
                )
                .stream()
                .map(File::new)
                .distinct()
                .toArray(File[]::new);
    }
}
