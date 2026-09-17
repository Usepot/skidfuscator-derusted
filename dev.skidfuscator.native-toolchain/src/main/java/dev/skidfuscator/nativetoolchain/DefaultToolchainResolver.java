package dev.skidfuscator.nativetoolchain;

import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Implements AUTO precedence: explicit, bundled, verified cache, download. */
public final class DefaultToolchainResolver implements ToolchainResolver {
    private static final List<ToolchainSource> AUTO_ORDER = List.of(
            ToolchainSource.EXTERNAL,
            ToolchainSource.BUNDLED,
            ToolchainSource.CACHE,
            ToolchainSource.DOWNLOAD
    );

    private final Map<ToolchainSource, ToolchainLocator> locators;
    private final ToolchainManifestVerifier verifier;

    public DefaultToolchainResolver(
            final List<ToolchainLocator> locators,
            final ToolchainManifestVerifier verifier
    ) {
        Objects.requireNonNull(locators, "locators");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        final Map<ToolchainSource, ToolchainLocator> bySource = new EnumMap<>(ToolchainSource.class);
        for (final ToolchainLocator locator : locators) {
            if (bySource.put(locator.source(), locator) != null) {
                throw new IllegalArgumentException("Duplicate toolchain locator for " + locator.source());
            }
        }
        this.locators = Map.copyOf(bySource);
    }

    @Override
    public ResolvedToolchain resolve(final ToolchainRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        if (request.delivery() == ToolchainDelivery.DISABLED) {
            throw new ToolchainResolutionException("Native toolchain delivery is disabled");
        }
        for (final ToolchainSource source : sourcesFor(request)) {
            final ToolchainLocator locator = locators.get(source);
            if (locator == null) {
                continue;
            }
            final Optional<ToolchainCandidate> located = locator.locate(request);
            if (located.isEmpty()) {
                continue;
            }
            final ToolchainCandidate candidate = located.get();
            if (candidate.source() != source) {
                throw new ToolchainResolutionException("Toolchain locator returned the wrong source kind");
            }
            verifier.verify(candidate.signedManifest(), request.version(), request.nativeIrAbi(),
                    request.targets(), request.currentHost());
            ToolchainManifestVerifier.verifyInstalledDriver(
                    candidate.home(), candidate.signedManifest().manifest(), request.currentHost());
            return new ResolvedToolchain(candidate.home(), source, candidate.signedManifest().manifest());
        }
        throw new ToolchainResolutionException("No authenticated SkidLLVM toolchain is available for "
                + request.delivery());
    }

    private List<ToolchainSource> sourcesFor(final ToolchainRequest request) {
        return switch (request.delivery()) {
            case AUTO -> request.explicitPath().isPresent()
                    ? AUTO_ORDER
                    : AUTO_ORDER.subList(1, AUTO_ORDER.size());
            case BUNDLED -> List.of(ToolchainSource.BUNDLED);
            case DOWNLOAD -> List.of(ToolchainSource.CACHE, ToolchainSource.DOWNLOAD);
            case EXTERNAL -> List.of(ToolchainSource.EXTERNAL);
            case DISABLED -> List.of();
        };
    }
}
