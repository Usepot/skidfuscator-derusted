package dev.skidfuscator.nativetoolchain;

import java.io.IOException;
import java.util.Optional;

/** Pluggable source for external, bundled, cached, or downloaded toolchains. */
public interface ToolchainLocator {
    ToolchainSource source();

    Optional<ToolchainCandidate> locate(ToolchainRequest request) throws IOException;
}
