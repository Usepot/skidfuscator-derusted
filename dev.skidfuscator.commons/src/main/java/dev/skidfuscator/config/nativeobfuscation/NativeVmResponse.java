package dev.skidfuscator.config.nativeobfuscation;

/**
 * Non-destructive response used when a VM runtime check fails.
 */
public enum NativeVmResponse {
    THROW,
    HALT,
    DELAYED_HALT
}
