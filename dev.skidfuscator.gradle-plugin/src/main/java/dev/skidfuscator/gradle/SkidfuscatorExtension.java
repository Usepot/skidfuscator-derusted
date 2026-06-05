package dev.skidfuscator.gradle;

import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SkidfuscatorExtension {
    private final Project project;
    private final ConfigurableFileCollection extraLibraries;
    private final List<String> exemptions = new ArrayList<String>();
    private final Map<String, Boolean> transformers = new LinkedHashMap<String, Boolean>();

    private Object inputJar;
    private Object outputJar;
    private String sourceSet = "main";
    private Object configFile;
    private boolean minimizeDependencies;
    private boolean phantom;
    private boolean debug;
    private boolean fuckit;
    private boolean analytics;
    private Object runtimePath;
    private Object skidfuscatorJar;
    private Object javaExecutable;
    private Object skidfuscatorProjectDir;
    private boolean autoBuildSkidfuscator = true;
    private String skidfuscatorBuildTask;

    public SkidfuscatorExtension(Project project) {
        this.project = project;
        this.extraLibraries = project.files();
    }

    public Object getInputJar() {
        return inputJar;
    }

    public void setInputJar(Object inputJar) {
        this.inputJar = inputJar;
    }

    public Object getOutputJar() {
        return outputJar;
    }

    public void setOutputJar(Object outputJar) {
        this.outputJar = outputJar;
    }

    public String getSourceSet() {
        return sourceSet;
    }

    public void setSourceSet(String sourceSet) {
        this.sourceSet = sourceSet;
    }

    public Object getConfigFile() {
        return configFile;
    }

    public void setConfigFile(Object configFile) {
        this.configFile = configFile;
    }

    public ConfigurableFileCollection getExtraLibraries() {
        return extraLibraries;
    }

    public boolean isMinimizeDependencies() {
        return minimizeDependencies;
    }

    public void setMinimizeDependencies(boolean minimizeDependencies) {
        this.minimizeDependencies = minimizeDependencies;
    }

    public boolean isPhantom() {
        return phantom;
    }

    public void setPhantom(boolean phantom) {
        this.phantom = phantom;
    }

    public boolean isDebug() {
        return debug;
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
    }

    public boolean isFuckit() {
        return fuckit;
    }

    public void setFuckit(boolean fuckit) {
        this.fuckit = fuckit;
    }

    public boolean isAnalytics() {
        return analytics;
    }

    public void setAnalytics(boolean analytics) {
        this.analytics = analytics;
    }

    public Object getRuntimePath() {
        return runtimePath;
    }

    public void setRuntimePath(Object runtimePath) {
        this.runtimePath = runtimePath;
    }

    public Object getSkidfuscatorJar() {
        return skidfuscatorJar;
    }

    public void setSkidfuscatorJar(Object skidfuscatorJar) {
        this.skidfuscatorJar = skidfuscatorJar;
    }

    public Object getJavaExecutable() {
        return javaExecutable;
    }

    public void setJavaExecutable(Object javaExecutable) {
        this.javaExecutable = javaExecutable;
    }

    public Object getSkidfuscatorProjectDir() {
        return skidfuscatorProjectDir;
    }

    public void setSkidfuscatorProjectDir(Object skidfuscatorProjectDir) {
        this.skidfuscatorProjectDir = skidfuscatorProjectDir;
    }

    public boolean isAutoBuildSkidfuscator() {
        return autoBuildSkidfuscator;
    }

    public void setAutoBuildSkidfuscator(boolean autoBuildSkidfuscator) {
        this.autoBuildSkidfuscator = autoBuildSkidfuscator;
    }

    public String getSkidfuscatorBuildTask() {
        return skidfuscatorBuildTask;
    }

    public void setSkidfuscatorBuildTask(String skidfuscatorBuildTask) {
        this.skidfuscatorBuildTask = skidfuscatorBuildTask;
    }

    public List<String> getExemptions() {
        return exemptions;
    }

    public Map<String, Boolean> getTransformers() {
        return transformers;
    }

    public void runtimePath(Object path) {
        setRuntimePath(path);
    }

    public void skidfuscatorJar(Object path) {
        setSkidfuscatorJar(path);
    }

    public void javaExecutable(Object path) {
        setJavaExecutable(path);
    }

    public void skidfuscatorProjectDir(Object path) {
        setSkidfuscatorProjectDir(path);
    }

    public void autoBuildSkidfuscator(boolean enabled) {
        setAutoBuildSkidfuscator(enabled);
    }

    public void skidfuscatorBuildTask(String task) {
        setSkidfuscatorBuildTask(task);
    }

    public void library(Object path) {
        extraLibraries.from(path);
    }

    public void libraries(Object... paths) {
        extraLibraries.from(paths);
    }

    public void exempt(String pattern) {
        exemptions.add(pattern);
    }

    public void transformer(String name, boolean enabled) {
        transformers.put(name, Boolean.valueOf(enabled));
    }

    public void enable(String name) {
        transformer(name, true);
    }

    public void disable(String name) {
        transformer(name, false);
    }

    Project getProject() {
        return project;
    }
}
