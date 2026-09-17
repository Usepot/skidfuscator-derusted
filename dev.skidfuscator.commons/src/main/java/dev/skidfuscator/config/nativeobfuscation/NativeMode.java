package dev.skidfuscator.config.nativeobfuscation;

/**
 * Native compilation backend selected for a Java method.
 */
public enum NativeMode {
    /** Resolve to the configured default mode. */
    DEFAULT,
    /** Compile directly to a native function. */
    AOT,
    /** Compile to the protected native virtual machine. */
    VM
}
