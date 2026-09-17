package dev.skidfuscator.obfuscator;

import dev.skidfuscator.jvm.Jvm;
import lombok.Builder;

import java.io.File;

/**
 * The Skidfuscator session object to be able to configure a session
 * with the obfuscator.
 */
@Builder
public class SkidfuscatorSession {
    private File input;
    private File output;
    private File[] libs;
    private File mappings;
    private File exempt;
    private File config;
    private File runtime;
    private boolean phantom;
    private boolean jmod;
    private boolean fuckit;
    private boolean analytics;
    private boolean renamer;
    private boolean c2j;

    /** Optional root of an externally supplied SkidLLVM toolchain. */
    private File nativeToolchainPath;
    /** Native toolchain resolution mode (AUTO, BUNDLED, DOWNLOAD, EXTERNAL, or DISABLED). */
    private String nativeToolchainDelivery;
    /** Optional native target override. An empty/null value lets configuration choose the targets. */
    private String[] nativeTargets;
    /** Directory used for platform-specific native artifact jars. */
    private File nativeArtifactDirectory;

    private boolean lowCon;
    private boolean dex;
    private boolean debug = false;

    /**
     *
     * @return the input
     */
    public File getInput() {
        return input;
    }

    public void setInput(File input) {
        this.input = input;
    }

    /**
     * @return the output
     */
    public File getOutput() {
        return output;
    }

    /**
     * @return the libs
     */
    public File[] getLibs() {
        return libs;
    }

    /**
     * @return the mappings file
     */
    public File getMappings() {
        return mappings;
    }

    /**
     * @return the config file
     */
    public File getConfig() {
        return config;
    }

    /**
     * @return the exempt
     */
    public File getExempt() {
        return exempt;
    }

    /**
     * @return the runtime
     */
    public File getRuntime() {
        if (runtime == null) {
            final String home = System.getProperty("java.home");
            return new File(
                    home,
                    Jvm.getJavaVersion() > 8
                            ? "jmods"
                            : "lib/rt.jar"
            );
        }
        return runtime;
    }

    /**
     * @return the boolean whether the execution uses JPhantom
     */
    public boolean isPhantom() {
        return phantom;
    }

    /**
     * @return the boolean whether the runtime lib is in JMod format
     */
    public boolean isJmod() {
        if (runtime == null)
            return Jvm.isJmod();

        return jmod;
    }

    /**
     * @return  the bool of whether the person is mentally ill and
     *          is willing to skip the forced phantom generation
     */
    public boolean isFuckIt() {
        return fuckit;
    }

    public boolean isAnalytics() {
        return analytics;
    }

    public boolean isDex() {
        return dex;
    }

    public boolean isDebug() {
        return debug;
    }

    public File getNativeToolchainPath() {
        return nativeToolchainPath;
    }

    public String getNativeToolchainDelivery() {
        return nativeToolchainDelivery;
    }

    public String[] getNativeTargets() {
        return nativeTargets == null ? null : nativeTargets.clone();
    }

    public File getNativeArtifactDirectory() {
        return nativeArtifactDirectory;
    }
}
