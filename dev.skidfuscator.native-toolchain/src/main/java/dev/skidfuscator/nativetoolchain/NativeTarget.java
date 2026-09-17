package dev.skidfuscator.nativetoolchain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** Supported compilation target and host platform identifiers. */
public enum NativeTarget {
    WINDOWS_X86_64("windows-x86_64", "x86_64-pc-windows-msvc", "dll"),
    WINDOWS_AARCH64("windows-aarch64", "aarch64-pc-windows-msvc", "dll"),
    LINUX_X86_64("linux-x86_64", "x86_64-unknown-linux-gnu", "so"),
    LINUX_AARCH64("linux-aarch64", "aarch64-unknown-linux-gnu", "so"),
    MACOS_X86_64("macos-x86_64", "x86_64-apple-darwin", "dylib"),
    MACOS_AARCH64("macos-aarch64", "aarch64-apple-darwin", "dylib");

    private final String id;
    private final String llvmTriple;
    private final String libraryExtension;

    NativeTarget(final String id, final String llvmTriple, final String libraryExtension) {
        this.id = id;
        this.llvmTriple = llvmTriple;
        this.libraryExtension = libraryExtension;
    }

    public String id() {
        return id;
    }

    public String llvmTriple() {
        return llvmTriple;
    }

    public String libraryExtension() {
        return libraryExtension;
    }

    public String libraryFileName(final String baseName) {
        if (baseName == null || baseName.isBlank() || baseName.contains("/") || baseName.contains("\\")) {
            throw new IllegalArgumentException("Invalid library base name: " + baseName);
        }
        return (this == WINDOWS_X86_64 || this == WINDOWS_AARCH64 ? "" : "lib")
                + baseName + "." + libraryExtension;
    }

    public static NativeTarget parse(final String value) {
        if (value == null) {
            throw new IllegalArgumentException("Native target cannot be null");
        }
        final String normalized = value.trim().toLowerCase(Locale.ROOT)
                .replace("windows_", "windows-")
                .replace("linux_", "linux-")
                .replace("macos_", "macos-")
                .replace("x86-64", "x86_64");
        return Arrays.stream(values())
                .filter(target -> target.id.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported native target: " + value));
    }

    public static Optional<NativeTarget> currentHost() {
        final String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        final String archName = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        final String os;
        if (osName.contains("win")) {
            os = "windows";
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            os = "macos";
        } else if (osName.contains("linux")) {
            os = "linux";
        } else {
            return Optional.empty();
        }

        final String arch;
        if (archName.equals("amd64") || archName.equals("x86_64") || archName.equals("x64")) {
            arch = "x86_64";
        } else if (archName.equals("aarch64") || archName.equals("arm64")) {
            arch = "aarch64";
        } else {
            return Optional.empty();
        }
        return Optional.of(parse(os + "-" + arch));
    }
}
