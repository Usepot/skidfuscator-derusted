package dev.skidfuscator.config.nativeobfuscation;

/**
 * Source used to locate a compatible SkidLLVM toolchain.
 */
public enum NativeToolchainDelivery {
    AUTO,
    BUNDLED,
    DOWNLOAD,
    EXTERNAL,
    DISABLED
}
