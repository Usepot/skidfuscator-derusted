package dev.skidfuscator.config.nativeobfuscation;

/**
 * Controls which native-protected jar artifacts are emitted.
 */
public enum NativeArtifactMode {
    /** Emit one jar containing all requested native targets. */
    UNIVERSAL,
    /** Emit one jar for each requested native target. */
    PLATFORM_SPECIFIC,
    /** Emit both the universal jar and platform-specific jars. */
    BOTH
}
