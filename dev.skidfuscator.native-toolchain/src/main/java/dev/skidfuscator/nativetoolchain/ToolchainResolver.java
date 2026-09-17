package dev.skidfuscator.nativetoolchain;

import java.io.IOException;

public interface ToolchainResolver {
    ResolvedToolchain resolve(ToolchainRequest request) throws IOException;
}
