package dev.skidfuscator.obfuscator.nativebackend.directory;

import dev.skidfuscator.obfuscator.directory.SkiddedDirectory;

import java.nio.file.Path;

/** Stable cache location for authenticated SkidLLVM installations. */
public final class NativeToolchainDirectories {
    private NativeToolchainDirectories() {
    }

    public static Path cacheRoot() {
        return SkiddedDirectory.getCache().toPath().toAbsolutePath().normalize()
                .resolve("native-toolchains");
    }
}
