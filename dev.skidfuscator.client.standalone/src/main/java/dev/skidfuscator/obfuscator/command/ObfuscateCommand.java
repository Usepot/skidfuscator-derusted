package dev.skidfuscator.obfuscator.command;

import dev.skidfuscator.migration.ExemptToConfigMigration;
import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.SkidfuscatorSession;
import dev.skidfuscator.obfuscator.util.ConsoleColors;
import dev.skidfuscator.obfuscator.util.LogoUtil;
import dev.skidfuscator.obfuscator.util.MiscUtil;
import picocli.CommandLine;

import java.io.File;
import java.text.DateFormat;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * @author Ghast
 * @since 06/03/2021
 * SkidfuscatorV2 © 2021
 */

@CommandLine.Command(
        aliases = "obfuscate",
        mixinStandardHelpOptions = true,
        version = "obfuscate 1.0.0",
    description = "Obfuscates and runs a specific jar"
)
public class ObfuscateCommand implements Callable<Integer> {
    private static final Set<String> NATIVE_TOOLCHAIN_DELIVERY_MODES = new HashSet<String>(Arrays.asList(
            "AUTO", "BUNDLED", "DOWNLOAD", "EXTERNAL", "DISABLED"
    ));
    private static final Set<String> NATIVE_TARGETS = new HashSet<String>(Arrays.asList(
            "windows-x86_64", "windows-aarch64",
            "linux-x86_64", "linux-aarch64",
            "macos-x86_64", "macos-aarch64"
    ));

    @CommandLine.Parameters(
            index = "0",
            description = "The file which will be obfuscated."
    )
    public File input;

    @CommandLine.Option(
            names = {"-rt", "--runtime"},
            description = "Path to the runtime jar"
    )
    public File runtime;

    @CommandLine.Option(
            names = {"-li", "--libs"},
            description = "Path to the libs folder"
    )
    public File libFolder;

    @CommandLine.Option(
            names = {"-ex", "--exempt"},
            description = "Path to the exempt file"
    )
    public File exempt;

    @CommandLine.Option(
            names = {"-o", "--output"},
            description = "Path to the output jar location"
    )
    public File output;

    @CommandLine.Option(
            names = {"-cfg", "--config"},
            description = "Path to the config file"
    )
    public File config;

    @CommandLine.Option(
            names = {"-ph", "--phantom"},
            description = "Declare if phantom computation should be used"
    )
    public boolean phantom;

    @CommandLine.Option(
            names = {"-fuckit", "--fuckit"},
            description = "Do not use!"
    )
    public boolean fuckit;

    @CommandLine.Option(
            names = {"-dbg", "--debug"},
            description = "Do not use!"
    )
    public boolean debug;

    @CommandLine.Option(
            names = {"-notrack", "--notrack"},
            description = "If you do not wish to be part of analytics!"
    )
    public boolean notrack;

    @CommandLine.Option(
            names = {"--native-toolchain-path"},
            description = "Path to an installed SkidLLVM toolchain"
    )
    public File nativeToolchainPath;

    @CommandLine.Option(
            names = {"--native-toolchain-delivery"},
            description = "SkidLLVM delivery mode: AUTO, BUNDLED, DOWNLOAD, EXTERNAL, or DISABLED"
    )
    public String nativeToolchainDelivery;

    @CommandLine.Option(
            names = {"--native-targets"},
            split = ",",
            description = "Comma-separated native target override"
    )
    public String[] nativeTargets;

    @CommandLine.Option(
            names = {"--native-artifact-dir"},
            description = "Directory for platform-specific native artifact jars"
    )
    public File nativeArtifactDirectory;


    @Override
    public Integer call()  {

        if (input == null) {
            return -1;
        }

        if (output == null) {
            output = new File(input.getPath() + "-out.jar");
        }

        if (nativeToolchainDelivery != null) {
            nativeToolchainDelivery = nativeToolchainDelivery.trim().toUpperCase(Locale.ROOT);
            if (!NATIVE_TOOLCHAIN_DELIVERY_MODES.contains(nativeToolchainDelivery)) {
                throw new CommandLine.ParameterException(
                        new CommandLine(this),
                        "Invalid --native-toolchain-delivery value '" + nativeToolchainDelivery
                                + "'. Expected AUTO, BUNDLED, DOWNLOAD, EXTERNAL, or DISABLED."
                );
            }
        }

        nativeTargets = normalizeNativeTargets(nativeTargets);

        if (runtime == null) {
            final String home = System.getProperty("java.home");
            runtime = new File(
                    home,
                    MiscUtil.getJavaVersion() > 8
                            ? "jmods"
                            : "lib/rt.jar"
            );
        }

        if (exempt != null) {
            final File converted = new File(new File(exempt.getAbsolutePath()).getParentFile().getAbsolutePath(), "config.hocon");
            final String warning = "\n" + ConsoleColors.YELLOW
                    + "██╗    ██╗ █████╗ ██████╗ ███╗   ██╗██╗███╗   ██╗ ██████╗ \n"
                    + "██║    ██║██╔══██╗██╔══██╗████╗  ██║██║████╗  ██║██╔════╝ \n"
                    + "██║ █╗ ██║███████║██████╔╝██╔██╗ ██║██║██╔██╗ ██║██║  ███╗\n"
                    + "██║███╗██║██╔══██║██╔══██╗██║╚██╗██║██║██║╚██╗██║██║   ██║\n"
                    + "╚███╔███╔╝██║  ██║██║  ██║██║ ╚████║██║██║ ╚████║╚██████╔╝\n"
                    + " ╚══╝╚══╝ ╚═╝  ╚═╝╚═╝  ╚═╝╚═╝  ╚═══╝╚═╝╚═╝  ╚═══╝ ╚═════╝ \n"
                    + "\n"
                    + "⚠️  Warning! Skidfuscator has deprecated the exempt file!\n"
                    + ConsoleColors.RESET
                    + "\n  Launching migrator service..."
                    + "\n  Config will be found at " + converted
                    + "\n";
            Skidfuscator.LOGGER.post(warning);
            new ExemptToConfigMigration().migrate(exempt, converted);

            config = converted;
        }

        final File[] libs;
        if (libFolder != null) {
            libs = libFolder.listFiles();
        } else {
            libs = new File[0];
        }

        final SkidfuscatorSession skidInstance = SkidfuscatorSession.builder()
                .input(input)
                .output(output)
                .libs(libs)
                .runtime(runtime)
                .exempt(exempt)
                .phantom(phantom)
                .jmod(MiscUtil.getJavaVersion() > 8)
                .fuckit(fuckit)
                .config(config)
                .debug(debug)
                .renamer(false)
                .analytics(!notrack)
                .nativeToolchainPath(nativeToolchainPath)
                .nativeToolchainDelivery(nativeToolchainDelivery)
                .nativeTargets(nativeTargets)
                .nativeArtifactDirectory(nativeArtifactDirectory)
                .build();

        final Skidfuscator skidfuscator = new Skidfuscator(skidInstance);
        skidfuscator.run();

        return 0;
    }

    private String[] normalizeNativeTargets(String[] targets) {
        if (targets == null || targets.length == 0) {
            return null;
        }

        final String[] normalized = new String[targets.length];
        int count = 0;
        for (String target : targets) {
            if (target == null || target.trim().isEmpty()) {
                continue;
            }
            final String normalizedTarget = target.trim().toLowerCase(Locale.ROOT);
            if (!NATIVE_TARGETS.contains(normalizedTarget)) {
                throw new CommandLine.ParameterException(
                        new CommandLine(this),
                        "Invalid --native-targets value '" + target + "'. Supported targets: "
                                + "windows-x86_64, windows-aarch64, linux-x86_64, linux-aarch64, "
                                + "macos-x86_64, macos-aarch64."
                );
            }
            normalized[count++] = normalizedTarget;
        }
        return count == 0 ? null : Arrays.copyOf(normalized, count);
    }


}
