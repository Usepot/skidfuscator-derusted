package dev.skidfuscator.obfuscator.nativebackend;

import dev.skidfuscator.config.nativeobfuscation.NativeConfig;
import dev.skidfuscator.config.nativeobfuscation.NativeArtifactMode;
import dev.skidfuscator.config.nativeobfuscation.NativeMode;
import dev.skidfuscator.config.nativeobfuscation.NativeTarget;
import dev.skidfuscator.config.nativeobfuscation.NativeToolchainDelivery;
import dev.skidfuscator.nativeir.NativeBackend;
import dev.skidfuscator.nativeir.NativeFunction;
import dev.skidfuscator.nativeir.NativeInstruction;
import dev.skidfuscator.nativeir.NativeModule;
import dev.skidfuscator.nativetoolchain.AuthenticatedToolchainResolvers;
import dev.skidfuscator.nativetoolchain.CanonicalLlvmIrEmitter;
import dev.skidfuscator.nativetoolchain.NativeCompilationRequest;
import dev.skidfuscator.nativetoolchain.NativeCompilationResult;
import dev.skidfuscator.nativetoolchain.NativeStringProtection;
import dev.skidfuscator.nativetoolchain.ProcessNativeCompiler;
import dev.skidfuscator.nativetoolchain.ResolvedToolchain;
import dev.skidfuscator.nativetoolchain.ToolchainDelivery;
import dev.skidfuscator.nativetoolchain.ToolchainManifestUriResolver;
import dev.skidfuscator.nativetoolchain.ToolchainRequest;
import dev.skidfuscator.nativetoolchain.VmProtectionSettings;
import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.SkidfuscatorSession;
import dev.skidfuscator.obfuscator.nativebackend.artifact.NativeArtifactPackager;
import dev.skidfuscator.obfuscator.nativebackend.artifact.NativeCompilationInstaller;
import dev.skidfuscator.obfuscator.nativebackend.commit.NativeMethodCommitTransaction;
import dev.skidfuscator.obfuscator.nativebackend.directory.NativeToolchainDirectories;
import dev.skidfuscator.obfuscator.nativebackend.lowering.ConstructorTailAnalyzer;
import dev.skidfuscator.obfuscator.nativebackend.lowering.JavaLinkageBridgeRegistry;
import dev.skidfuscator.obfuscator.nativebackend.lowering.MapleNativeIrLowerer;
import dev.skidfuscator.obfuscator.nativebackend.runtime.NativeLoaderGenerator;
import dev.skidfuscator.obfuscator.nativebackend.runtime.NativeLoaderSpec;
import dev.skidfuscator.obfuscator.skidasm.SkidMethodNode;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Native lifecycle gate. It reserves candidates before structural transforms,
 * revalidates those exact methods at the lowering boundary, and prevents an
 * opt-in native build from silently emitting an unprotected Java jar.
 *
 * <p>The typed IR, verifier, authenticated toolchain resolver, compiler driver,
 * and artifact packager live in their dedicated modules. The MapleIR-to-native
 * lowerer and Java loader/commit transaction are intentionally required before
 * this gate may mutate a method.</p>
 */
public final class NativePipeline {
    static final URI DEFAULT_RELEASE_BASE = URI.create(
            "https://github.com/skidfuscatordev/SkidLLVM/releases/download/");
    private final Skidfuscator skidfuscator;
    private boolean nativeArtifactsInstalled;

    public NativePipeline(final Skidfuscator skidfuscator) {
        this.skidfuscator = Objects.requireNonNull(skidfuscator, "skidfuscator");
    }

    /** Reserves candidates before normal EventBus transformations begin. */
    public NativeCompilationPlan reserve() {
        final NativeConfig config = skidfuscator.getConfig().getNativeConfig();
        if (!config.isEnabled()) {
            return new NativeCompilationPlan(List.of(), List.of());
        }

        validateRequest(config);
        return new NativeCompilationPlanner(config).plan(applicationMethods());
    }

    /** Backward-compatible one-shot entry point used by callers without transform phases. */
    public NativeCompilationPlan prepare() {
        return prepare(reserve());
    }

    /** Revalidates a pre-transform reservation at the native lowering boundary. */
    public NativeCompilationPlan prepare(final NativeCompilationPlan reservation) {
        Objects.requireNonNull(reservation, "reservation");
        final NativeConfig config = skidfuscator.getConfig().getNativeConfig();
        if (!config.isEnabled()) {
            return new NativeCompilationPlan(List.of(), List.of());
        }

        final Request request = validateRequest(config);
        final NativeCompilationPlan plan = new NativeCompilationPlanner(config)
                .revalidate(reservation, applicationMethods());
        for (NativeCompilationPlan.Skipped skipped : plan.skipped()) {
            Skidfuscator.LOGGER.warn(
                    "Native selection skipped " + skipped.selection().getMethod() + ": " + skipped.reason()
            );
        }
        if (plan.candidates().isEmpty()) {
            return plan;
        }

        return execute(plan, request, config);
    }

    private NativeCompilationPlan execute(
            final NativeCompilationPlan plan,
            final Request request,
            final NativeConfig config
    ) {
        final List<NativeCompilationPlan.Skipped> skipped = new ArrayList<>(plan.skipped());
        final List<Lowered> lowered = new ArrayList<>();
        final String random = randomId();
        final JavaLinkageBridgeRegistry linkageBridges = new JavaLinkageBridgeRegistry(
                random, this::isCallerSensitive);
        final MapleNativeIrLowerer lowerer = new MapleNativeIrLowerer(linkageBridges);
        final String moduleName = "skid-native-" + random;
        final NativeStringProtection stringProtection = NativeStringProtection.randomized();
        final CanonicalLlvmIrEmitter llvmEmitter = new CanonicalLlvmIrEmitter(stringProtection);
        final org.objectweb.asm.commons.Remapper remapper = skidfuscator.getClassRemapper() == null
                ? new org.objectweb.asm.commons.Remapper() { }
                : skidfuscator.getClassRemapper();
        final NativeIrFinalNameRemapper finalNameRemapper = new NativeIrFinalNameRemapper(remapper);
        final NativeWrapperPlanner wrapperPlanner = new NativeWrapperPlanner(
                skidfuscator.getJarContents() == null
                        ? new org.topdank.byteengineer.commons.data.JarContents()
                        : skidfuscator.getJarContents(), random, remapper);
        int index = 0;
        for (final NativeCompilationPlan.Candidate candidate : plan.candidates()) {
            final int linkageCheckpoint = linkageBridges.checkpoint();
            try {
                final int ordinal = index;
                final NativeBackend backend = candidate.selection().getMode() == NativeMode.VM
                        ? NativeBackend.VM : NativeBackend.AOT;
                final SkidMethodNode method = candidate.selection().getMethod() instanceof SkidMethodNode skidMethod
                        ? skidMethod : null;
                final NativeModule single;
                final ConstructorTailAnalyzer.Result constructorPlan;
                if (candidate.selection().getMethod().isInit()) {
                    if (method == null) {
                        throw new IllegalArgumentException("constructor tail lowering requires a finalized MapleIR graph");
                    }
                    constructorPlan = new ConstructorTailAnalyzer().analyze(method, method.getCfg());
                    if (!constructorPlan.supported()) {
                        throw new IllegalArgumentException(constructorPlan.reason());
                    }
                    single = lowerer.lowerConstructorTail(
                            moduleName, "skid_fn_" + random + "_" + index++,
                            wrapperPlanner.helperName(ordinal), method, method.getCfg(), constructorPlan, backend);
                } else {
                    constructorPlan = null;
                    single = lowerer.lower(
                            moduleName, "skid_fn_" + random + "_" + index++,
                            candidate.selection().getMethod(), backend);
                }
                reserveNativeMemberReferences(single.functions().get(0));
                final NativeFunction remapped = finalNameRemapper.remap(single.functions().get(0));
                reserveNativeMemberReferences(remapped);
                if (candidate.conversion() == NativeEligibility.Conversion.DIRECT) {
                    final Lowered prepared = new Lowered(candidate, remapped,
                            new NativeMethodCommitTransaction.Direct(candidate.selection().getMethod()));
                    preflightEmission(moduleName, prepared.function(), llvmEmitter);
                    lowered.add(prepared);
                } else {
                    final NativeWrapperPlanner.Prepared wrapper = constructorPlan == null
                            ? wrapperPlanner.prepare(candidate, remapped, ordinal)
                            : wrapperPlanner.prepareConstructor(candidate, remapped, constructorPlan, ordinal);
                    final Lowered prepared = new Lowered(candidate, wrapper.function(), wrapper.mutation());
                    preflightEmission(moduleName, prepared.function(), llvmEmitter);
                    lowered.add(prepared);
                }
            } catch (RuntimeException failure) {
                linkageBridges.rollback(linkageCheckpoint);
                final String reason = failure instanceof NullPointerException || failure.getMessage() == null
                        ? "descriptor must be ()Ljava/lang/String; or a finalized MapleIR graph is required"
                        : failure.getMessage();
                rejectLowering(candidate, reason, skipped);
            }
        }
        if (lowered.isEmpty()) {
            final String rejectionSummary = skipped.stream()
                    .map(value -> value.selection().getMethod() + ": " + value.reason())
                    .collect(Collectors.joining("; "));
            throw new NativeBackendUnavailableException(
                    "None of the " + plan.candidates().size()
                            + " selected method(s) can be lowered by the native backend. "
                            + "No native artifact or modified Java method was committed. Rejections: "
                            + rejectionSummary
            );
        }
        final String buildId = "skid-" + random;
        final String loaderName = "skid/native/Loader_" + random;
        Path workDirectory = null;
        try {
            final NativeModule module = new NativeModule(moduleName);
            lowered.forEach(value -> module.addFunction(value.function()));
            final Set<dev.skidfuscator.nativetoolchain.NativeTarget> targets = toolchainTargets(request.targets());
            final dev.skidfuscator.nativetoolchain.NativeTarget currentHost =
                    dev.skidfuscator.nativetoolchain.NativeTarget.currentHost().orElseThrow(() ->
                            new NativeBackendUnavailableException(
                                    "SkidLLVM is not published for this host operating system/architecture"));
            final ResolvedToolchain toolchain = AuthenticatedToolchainResolvers.create(
                    NativeToolchainDirectories.cacheRoot(), NativePipeline.class.getClassLoader(),
                    releaseManifestUris()).resolve(new ToolchainRequest(
                    ToolchainDelivery.valueOf(request.delivery().name()),
                    java.util.Optional.ofNullable(explicitCompiler(config, skidfuscator.getSession())),
                    config.getToolchainConfig().getVersion(), config.getToolchainConfig().getNativeIrAbi(),
                    targets, currentHost));
            workDirectory = Files.createTempDirectory("skid-native-" + random + "-");
            final NativeCompilationResult compilation = new ProcessNativeCompiler(llvmEmitter)
                    .compile(new NativeCompilationRequest(module, toolchain, workDirectory, targets, currentHost,
                    buildId, vmSettings(config)));
            final NativeLoaderGenerator.GeneratedLoader loader = new NativeLoaderGenerator().generate(
                    NativeLoaderSpec.fromManifest(loaderName, compilation.manifest()));
            skidfuscator.reserveNativeGeneratedClass(loader.internalName());
            linkageBridges.generatedHelpers().forEach(helper ->
                    skidfuscator.reserveNativeGeneratedMethod(
                            helper.owner().getName(), helper.method().name, helper.method().desc));
            final List<NativeMethodCommitTransaction.Mutation> mutations = lowered.stream()
                    .map(Lowered::mutation).toList();
            final List<org.topdank.byteengineer.commons.data.JarClassData> companions =
                    wrapperPlanner.generatedCompanions();
            skidfuscator.getJarContents().getClassContents().addAll(companions);
            try {
                new NativeMethodCommitTransaction().commit(
                        skidfuscator.getJarContents(),
                        loader,
                        mutations,
                        linkageBridges.generatedHelpers(),
                        contents -> new NativeCompilationInstaller().install(contents, compilation, targets));
            } catch (IOException | RuntimeException | Error failure) {
                skidfuscator.getJarContents().getClassContents().removeAll(companions);
                throw failure;
            }
            nativeArtifactsInstalled = true;
            Skidfuscator.LOGGER.post("Native protection compiled " + lowered.size()
                    + " method(s) for " + targets.size() + " target(s) using authenticated "
                    + toolchain.source() + " SkidLLVM");
            return new NativeCompilationPlan(
                    lowered.stream().map(Lowered::candidate).toList(), skipped);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new NativeBackendUnavailableException("Native compilation was interrupted", exception);
        } catch (final IOException | RuntimeException exception) {
            throw new NativeBackendUnavailableException(
                    "Native compilation failed before a complete output could be committed: "
                            + exception.getMessage(), exception);
        } finally {
            if (workDirectory != null) {
                cleanupTemporaryDirectory(workDirectory);
            }
        }
    }

    private static void preflightEmission(
            final String moduleName,
            final NativeFunction function,
            final CanonicalLlvmIrEmitter emitter
    ) {
        emitter.emit(new NativeModule(moduleName).addFunction(function));
    }

    private void reserveNativeMemberReferences(final NativeFunction function) {
        function.blocks().stream().flatMap(block -> block.instructions().stream())
                .filter(NativeInstruction.Operation.class::isInstance)
                .map(NativeInstruction.Operation.class::cast)
                .filter(operation -> operation.opcode() == dev.skidfuscator.nativeir.NativeOpcode.JAVA_CALL)
                .forEach(operation -> skidfuscator.reserveNativeReferencedMember(
                        operation.attributes().get("owner"), operation.attributes().get("name"),
                        operation.attributes().get("descriptor")));
    }

    private boolean isCallerSensitive(final String owner, final String name, final String descriptor) {
        return isCallerSensitive(owner, name, descriptor, new java.util.HashSet<>());
    }

    private boolean isCallerSensitive(
            final String owner,
            final String name,
            final String descriptor,
            final Set<String> visited
    ) {
        if (owner == null || !visited.add(owner)) return false;
        final org.mapleir.asm.ClassNode node = skidfuscator.getClassSource().findClassNode(owner);
        if (node == null || node.node == null) return false;
        for (final org.objectweb.asm.tree.MethodNode method : node.node.methods) {
            if (!method.name.equals(name) || !method.desc.equals(descriptor)) continue;
            final java.util.stream.Stream<org.objectweb.asm.tree.AnnotationNode> annotations =
                    java.util.stream.Stream.concat(
                            method.visibleAnnotations == null ? java.util.stream.Stream.empty()
                                    : method.visibleAnnotations.stream(),
                            method.invisibleAnnotations == null ? java.util.stream.Stream.empty()
                                    : method.invisibleAnnotations.stream());
            if (annotations.anyMatch(annotation ->
                    "Ljdk/internal/reflect/CallerSensitive;".equals(annotation.desc)
                            || "Lsun/reflect/CallerSensitive;".equals(annotation.desc))) {
                return true;
            }
        }
        if (node.node.interfaces != null) {
            for (final String interfaceName : node.node.interfaces) {
                if (isCallerSensitive(interfaceName, name, descriptor, visited)) return true;
            }
        }
        return isCallerSensitive(node.node.superName, name, descriptor, visited);
    }

    static ToolchainManifestUriResolver releaseManifestUris() {
        return version -> {
            if (version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
                throw new IllegalArgumentException("Unsafe SkidLLVM release version: " + version);
            }
            return URI.create(DEFAULT_RELEASE_BASE + "v" + version + "/"
                    + dev.skidfuscator.nativetoolchain.ExternalToolchainLocator.MANIFEST_FILE_NAME);
        };
    }

    private static Set<dev.skidfuscator.nativetoolchain.NativeTarget> toolchainTargets(
            final Collection<NativeTarget> targets
    ) {
        return targets.stream().map(value ->
                dev.skidfuscator.nativetoolchain.NativeTarget.parse(value.getId()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static VmProtectionSettings vmSettings(final NativeConfig config) {
        final var vm = config.getVmConfig();
        final VmProtectionSettings.Profile profile = VmProtectionSettings.Profile.valueOf(vm.getProfile().name());
        final VmProtectionSettings.Response response = VmProtectionSettings.Response.valueOf(vm.getResponse().name());
        final boolean aggressive = profile == VmProtectionSettings.Profile.AGGRESSIVE;
        return new VmProtectionSettings(profile, response, vm.isIntegrityEnabled(), vm.isAntiDebugEnabled(),
                vm.isAntiInstrumentationEnabled(), vm.isTimingChecksEnabled(), true,
                aggressive ? 4 : 2, aggressive ? 48 : 16, aggressive ? 64 : 32,
                response == VmProtectionSettings.Response.DELAYED_HALT ? 250 : 0);
    }

    /** Publishes optional target-specific jars after the universal output jar exists. */
    public void publishPlatformArtifacts() {
        if (!nativeArtifactsInstalled) {
            return;
        }
        final NativeConfig config = skidfuscator.getConfig().getNativeConfig();
        final NativeArtifactMode mode = config.getArtifactMode();
        if (mode == NativeArtifactMode.UNIVERSAL) {
            return;
        }
        final Path universalJar = skidfuscator.getSession().getOutput().toPath().toAbsolutePath().normalize();
        final File configuredDirectory = skidfuscator.getSession().getNativeArtifactDirectory();
        final String outputName = universalJar.getFileName().toString();
        final String baseName = outputName.toLowerCase(Locale.ROOT).endsWith(".jar")
                ? outputName.substring(0, outputName.length() - 4) : outputName;
        final Path parent = universalJar.getParent() == null
                ? Path.of(".").toAbsolutePath().normalize() : universalJar.getParent();
        final Path artifactDirectory = configuredDirectory == null
                ? parent.resolve(baseName + "-native")
                : configuredDirectory.toPath().toAbsolutePath().normalize();
        try {
            final Set<NativeTarget> targets = effectiveTargets(config, skidfuscator.getSession());
            final var outputs = NativeArtifactPackager.packagePlatformJars(
                    universalJar, artifactDirectory, targets);
            Skidfuscator.LOGGER.post("Published " + outputs.size()
                    + " platform-specific native jar(s) to " + artifactDirectory);
        } catch (IOException exception) {
            throw new NativeBackendUnavailableException(
                    "The universal native jar was written, but platform artifact publication failed: "
                            + exception.getMessage(), exception);
        }
    }

    private static void rejectLowering(
            final NativeCompilationPlan.Candidate candidate,
            final String reason,
            final List<NativeCompilationPlan.Skipped> skipped
    ) {
        if (candidate.selection().isStrict()) {
            throw new NativeSelectionException("Explicit native selection cannot be lowered: "
                    + candidate.selection().getMethod() + ": " + reason);
        }
        skipped.add(new NativeCompilationPlan.Skipped(candidate.selection(), reason));
    }

    private static Path explicitCompiler(final NativeConfig config, final SkidfuscatorSession session) {
        final File sessionPath = session.getNativeToolchainPath();
        if (sessionPath != null) {
            return sessionPath.toPath().toAbsolutePath().normalize();
        }
        final String configured = config.getToolchainConfig().getPath();
        return configured == null || configured.isBlank()
                ? null
                : Path.of(configured).toAbsolutePath().normalize();
    }

    private static String randomId() {
        final byte[] bytes = new byte[12];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static void cleanupTemporaryDirectory(final Path directory) {
        final Path normalized = directory.toAbsolutePath().normalize();
        if (!normalized.getFileName().toString().startsWith("skid-native-")) {
            Skidfuscator.LOGGER.warn("Refusing to clean unexpected native work directory: " + normalized);
            return;
        }
        try (var paths = Files.walk(normalized)) {
            for (final Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            Skidfuscator.LOGGER.warn("Unable to clean native work directory " + normalized
                    + ": " + exception.getMessage());
        }
    }

    private Request validateRequest(final NativeConfig config) {
        final SkidfuscatorSession session = skidfuscator.getSession();
        if (session.isDex()) {
            throw new NativeSelectionException("Native AOT/VM mode is not supported for APK/DEX output");
        }
        final Set<NativeTarget> targets = effectiveTargets(config, session);
        final NativeToolchainDelivery delivery = effectiveDelivery(config, session);
        if (delivery == NativeToolchainDelivery.DISABLED) {
            throw new NativeBackendUnavailableException(
                    "native.enabled is true but native.toolchain.delivery is DISABLED"
            );
        }
        return new Request(targets, delivery);
    }

    private Collection<SkidMethodNode> applicationMethods() {
        return skidfuscator.getHierarchy().getMethods().stream()
                .filter(method -> skidfuscator.getClassSource().isApplicationClass(method.owner.getName()))
                .collect(Collectors.toList());
    }

    static Set<NativeTarget> effectiveTargets(
            final NativeConfig config,
            final SkidfuscatorSession session
    ) {
        final String[] override = session.getNativeTargets();
        if (override == null || override.length == 0) {
            return new LinkedHashSet<>(config.getTargets());
        }
        final LinkedHashSet<NativeTarget> targets = Arrays.stream(override)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(NativeTarget::fromConfigValue)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("At least one native target is required");
        }
        return targets;
    }

    static NativeToolchainDelivery effectiveDelivery(
            final NativeConfig config,
            final SkidfuscatorSession session
    ) {
        final String override = session.getNativeToolchainDelivery();
        if (override == null || override.isBlank()) {
            return config.getToolchainConfig().getDelivery();
        }
        try {
            return NativeToolchainDelivery.valueOf(override.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported native toolchain delivery: " + override, exception);
        }
    }

    private record Request(Set<NativeTarget> targets, NativeToolchainDelivery delivery) {
    }

    private record Lowered(
            NativeCompilationPlan.Candidate candidate,
            NativeFunction function,
            NativeMethodCommitTransaction.Mutation mutation
    ) {
    }
}
