package dev.skidfuscator.config.nativeobfuscation;

import java.util.Arrays;

/**
 * Supported operating-system and architecture combinations for native output.
 */
public enum NativeTarget {
    WINDOWS_X86_64("windows-x86_64", "windows", "x86_64", ".dll"),
    WINDOWS_AARCH64("windows-aarch64", "windows", "aarch64", ".dll"),
    LINUX_X86_64("linux-x86_64", "linux", "x86_64", ".so"),
    LINUX_AARCH64("linux-aarch64", "linux", "aarch64", ".so"),
    MACOS_X86_64("macos-x86_64", "macos", "x86_64", ".dylib"),
    MACOS_AARCH64("macos-aarch64", "macos", "aarch64", ".dylib");

    private final String id;
    private final String operatingSystem;
    private final String architecture;
    private final String libraryExtension;

    NativeTarget(
            final String id,
            final String operatingSystem,
            final String architecture,
            final String libraryExtension
    ) {
        this.id = id;
        this.operatingSystem = operatingSystem;
        this.architecture = architecture;
        this.libraryExtension = libraryExtension;
    }

    public String getId() {
        return id;
    }

    public String getOperatingSystem() {
        return operatingSystem;
    }

    public String getArchitecture() {
        return architecture;
    }

    public String getLibraryExtension() {
        return libraryExtension;
    }

    /**
     * Resolves the stable, hyphenated value used in HOCON and artifact paths.
     */
    public static NativeTarget fromConfigValue(final String value) {
        return Arrays.stream(values())
                .filter(target -> target.id.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported native target: " + value));
    }

    @Override
    public String toString() {
        return id;
    }
}
