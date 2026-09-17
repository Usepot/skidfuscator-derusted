package dev.skidfuscator.obfuscator.nativebackend.artifact;

import dev.skidfuscator.config.nativeobfuscation.NativeTarget;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

/**
 * Produces target-specific jars from a universal jar containing native resources.
 *
 * <p>Native resources must use the following layout:</p>
 *
 * <pre>
 * META-INF/skidfuscator/native/&lt;build-id&gt;/&lt;target-id&gt;/...
 * </pre>
 *
 * <p>Every non-native entry is copied to each platform jar. Native entries are copied
 * when their target segment matches that platform jar; build-level metadata is retained
 * for each included build so the library can still authenticate its compiler manifest.
 * Output entries are sorted by name and retain their safe ZIP metadata, making repeated
 * packaging deterministic for an unchanged universal jar.</p>
 */
public final class NativeArtifactPackager {
    public static final String NATIVE_RESOURCE_PREFIX = "META-INF/skidfuscator/native/";

    private NativeArtifactPackager() {
    }

    /**
     * Creates one platform jar for each requested target.
     *
     * <p>Before creating output, the complete request is validated against the universal
     * jar. If any target has no native file, no platform jar is written. Each jar is
     * written to a temporary file in {@code artifactDirectory} and moved into place only
     * after the ZIP stream closes successfully.</p>
     *
     * @param universalJar the universal jar containing all native targets
     * @param artifactDirectory destination directory for platform jars
     * @param targets targets to package
     * @return an immutable, declaration-order map from target to created jar
     * @throws IOException if input validation, copying, or publication fails
     */
    public static Map<NativeTarget, Path> packagePlatformJars(
            final Path universalJar,
            final Path artifactDirectory,
            final Collection<NativeTarget> targets
    ) throws IOException {
        final Path source = normalizeUniversalJar(universalJar);
        final Path destination = Objects.requireNonNull(artifactDirectory, "artifactDirectory")
                .toAbsolutePath()
                .normalize();
        final EnumSet<NativeTarget> requestedTargets = normalizeTargets(targets);

        validateRequestedTargets(source, requestedTargets);
        Files.createDirectories(destination);

        final Map<NativeTarget, Path> artifacts = new EnumMap<>(NativeTarget.class);
        final Map<NativeTarget, Path> staged = new EnumMap<>(NativeTarget.class);
        final Map<NativeTarget, Path> backups = new EnumMap<>(NativeTarget.class);
        final EnumSet<NativeTarget> published = EnumSet.noneOf(NativeTarget.class);
        try {
            // Finish every fallible ZIP read/write before publishing any platform output.
            for (NativeTarget target : NativeTarget.values()) {
                if (!requestedTargets.contains(target)) {
                    continue;
                }
                final Path output = platformJarPath(source, destination, target);
                final Path temporary = Files.createTempFile(destination,
                        "." + output.getFileName() + ".", ".staged");
                try {
                    writePlatformJar(source, temporary, target);
                } catch (IOException | RuntimeException failure) {
                    Files.deleteIfExists(temporary);
                    throw failure;
                }
                artifacts.put(target, output);
                staged.put(target, temporary);
            }

            // Back up replaced outputs so a publication failure can restore the entire set.
            for (final Map.Entry<NativeTarget, Path> entry : artifacts.entrySet()) {
                if (Files.exists(entry.getValue())) {
                    final Path backup = Files.createTempFile(destination,
                            "." + entry.getValue().getFileName() + ".", ".backup");
                    Files.deleteIfExists(backup);
                    backups.put(entry.getKey(), backup);
                    moveIntoPlace(entry.getValue(), backup);
                }
            }
            for (final Map.Entry<NativeTarget, Path> entry : artifacts.entrySet()) {
                moveIntoPlace(staged.get(entry.getKey()), entry.getValue());
                published.add(entry.getKey());
            }
        } catch (IOException | RuntimeException failure) {
            for (final NativeTarget target : published) {
                Files.deleteIfExists(artifacts.get(target));
            }
            for (final Map.Entry<NativeTarget, Path> backup : backups.entrySet()) {
                if (Files.exists(backup.getValue())) {
                    moveIntoPlace(backup.getValue(), artifacts.get(backup.getKey()));
                }
            }
            throw failure;
        } finally {
            for (final Path temporary : staged.values()) {
                Files.deleteIfExists(temporary);
            }
            for (final Path backup : backups.values()) {
                Files.deleteIfExists(backup);
            }
        }
        return Collections.unmodifiableMap(artifacts);
    }

    /**
     * Computes the deterministic platform jar path without creating it.
     */
    public static Path platformJarPath(
            final Path universalJar,
            final Path artifactDirectory,
            final NativeTarget target
    ) {
        Objects.requireNonNull(target, "target");
        final Path sourceName = Objects.requireNonNull(universalJar, "universalJar").getFileName();
        if (sourceName == null) {
            throw new IllegalArgumentException("Universal jar must have a file name: " + universalJar);
        }

        final String filename = sourceName.toString();
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".jar") || filename.length() == 4) {
            throw new IllegalArgumentException("Universal artifact must be a named .jar file: " + universalJar);
        }

        final String baseName = filename.substring(0, filename.length() - 4);
        return Objects.requireNonNull(artifactDirectory, "artifactDirectory")
                .toAbsolutePath()
                .normalize()
                .resolve(baseName + "-" + target.getId() + ".jar");
    }

    private static Path normalizeUniversalJar(final Path universalJar) throws IOException {
        final Path source = Objects.requireNonNull(universalJar, "universalJar")
                .toAbsolutePath()
                .normalize();
        // Validate the artifact name separately so callers get a useful error before ZIP IO.
        platformJarPath(source, source.getParent(), NativeTarget.WINDOWS_X86_64);
        if (!Files.isRegularFile(source)) {
            throw new IOException("Universal jar is not a regular file: " + source);
        }
        return source;
    }

    private static EnumSet<NativeTarget> normalizeTargets(final Collection<NativeTarget> targets) {
        Objects.requireNonNull(targets, "targets");
        final EnumSet<NativeTarget> normalized = EnumSet.noneOf(NativeTarget.class);
        for (NativeTarget target : targets) {
            normalized.add(Objects.requireNonNull(target, "targets contains null"));
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("At least one native target is required");
        }
        return normalized;
    }

    private static void validateRequestedTargets(
            final Path source,
            final EnumSet<NativeTarget> requestedTargets
    ) throws IOException {
        final EnumSet<NativeTarget> foundTargets = EnumSet.noneOf(NativeTarget.class);
        try (JarFile input = new JarFile(source.toFile(), false)) {
            final Enumeration<JarEntry> entries = input.entries();
            while (entries.hasMoreElements()) {
                final JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                for (NativeTarget target : requestedTargets) {
                    if (isTargetNativeEntry(entry.getName(), target)) {
                        foundTargets.add(target);
                    }
                }
            }
        }

        final EnumSet<NativeTarget> missingTargets = EnumSet.copyOf(requestedTargets);
        missingTargets.removeAll(foundTargets);
        if (!missingTargets.isEmpty()) {
            throw new IOException("Universal jar contains no native resources for: " + missingTargets);
        }
    }

    private static void writePlatformJar(
            final Path source,
            final Path temporary,
            final NativeTarget target
    ) throws IOException {
        try (JarFile input = new JarFile(source.toFile(), false);
             OutputStream fileOutput = Files.newOutputStream(temporary);
             JarOutputStream output = new JarOutputStream(new BufferedOutputStream(fileOutput))) {
            final List<JarEntry> entries = includedEntries(input, target);
            final Set<String> writtenNames = new HashSet<>();
            for (JarEntry sourceEntry : entries) {
                if (!writtenNames.add(sourceEntry.getName())) {
                    throw new IOException("Universal jar contains duplicate entry: " + sourceEntry.getName());
                }

                final JarEntry outputEntry = copyMetadata(sourceEntry);
                output.putNextEntry(outputEntry);
                if (!sourceEntry.isDirectory()) {
                    try (InputStream entryInput = new BufferedInputStream(input.getInputStream(sourceEntry))) {
                        copy(entryInput, output);
                    }
                }
                output.closeEntry();
            }
        }
    }

    private static List<JarEntry> includedEntries(final JarFile input, final NativeTarget target) {
        final Set<String> targetBuilds = targetBuildIds(input, target);
        final List<JarEntry> entries = new ArrayList<>();
        final Enumeration<JarEntry> enumeration = input.entries();
        while (enumeration.hasMoreElements()) {
            final JarEntry entry = enumeration.nextElement();
            if (isIncluded(entry.getName(), target, targetBuilds)) {
                entries.add(entry);
            }
        }
        entries.sort(Comparator.comparing(ZipEntry::getName));
        return entries;
    }

    private static boolean isIncluded(
            final String entryName,
            final NativeTarget target,
            final Set<String> targetBuilds
    ) {
        if (!entryName.startsWith(NATIVE_RESOURCE_PREFIX)) {
            return true;
        }
        if (isTargetNativeEntry(entryName, target)) {
            return true;
        }

        // Keep build-level authenticated metadata (for example the compiler
        // manifest), but only for builds which contain the selected target.
        final String remainder = entryName.substring(NATIVE_RESOURCE_PREFIX.length());
        final int buildSeparator = remainder.indexOf('/');
        if (buildSeparator <= 0) {
            return false;
        }
        final String buildId = remainder.substring(0, buildSeparator);
        final String buildResource = remainder.substring(buildSeparator + 1);
        return !buildResource.isEmpty()
                && buildResource.indexOf('/') < 0
                && targetBuilds.contains(buildId);
    }

    private static Set<String> targetBuildIds(final JarFile input, final NativeTarget target) {
        final Set<String> buildIds = new HashSet<>();
        final Enumeration<JarEntry> entries = input.entries();
        while (entries.hasMoreElements()) {
            final String name = entries.nextElement().getName();
            if (!isTargetNativeEntry(name, target)) {
                continue;
            }
            final String remainder = name.substring(NATIVE_RESOURCE_PREFIX.length());
            buildIds.add(remainder.substring(0, remainder.indexOf('/')));
        }
        return buildIds;
    }

    private static boolean isTargetNativeEntry(final String entryName, final NativeTarget target) {
        if (!entryName.startsWith(NATIVE_RESOURCE_PREFIX)) {
            return false;
        }

        final String remainder = entryName.substring(NATIVE_RESOURCE_PREFIX.length());
        final int buildSeparator = remainder.indexOf('/');
        if (buildSeparator <= 0) {
            return false;
        }

        final String targetAndResource = remainder.substring(buildSeparator + 1);
        final String targetPrefix = target.getId() + "/";
        return targetAndResource.startsWith(targetPrefix)
                && targetAndResource.length() > targetPrefix.length();
    }

    private static JarEntry copyMetadata(final JarEntry source) throws IOException {
        final int method = source.getMethod();
        if (method != ZipEntry.STORED && method != ZipEntry.DEFLATED) {
            throw new IOException("Unsupported ZIP method " + method + " for entry " + source.getName());
        }

        final JarEntry copy = new JarEntry(source.getName());
        copy.setMethod(method);
        if (source.getComment() != null) {
            copy.setComment(source.getComment());
        }
        if (source.getExtra() != null) {
            copy.setExtra(source.getExtra().clone());
        }
        if (source.getTime() >= 0) {
            copy.setTime(source.getTime());
        } else {
            // ZipOutputStream otherwise supplies the current time, breaking reproducibility.
            copy.setTime(0L);
        }
        if (source.getCreationTime() != null) {
            copy.setCreationTime(source.getCreationTime());
        }
        if (source.getLastAccessTime() != null) {
            copy.setLastAccessTime(source.getLastAccessTime());
        }
        if (source.getLastModifiedTime() != null) {
            copy.setLastModifiedTime(source.getLastModifiedTime());
        }
        if (method == ZipEntry.STORED) {
            copy.setSize(source.getSize());
            copy.setCompressedSize(source.getSize());
            copy.setCrc(source.getCrc());
        }
        return copy;
    }

    private static void copy(final InputStream input, final OutputStream output) throws IOException {
        final byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
    }

    private static void moveIntoPlace(final Path temporary, final Path output) throws IOException {
        try {
            Files.move(
                    temporary,
                    output,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
