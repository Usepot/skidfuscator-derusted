package dev.skidfuscator.obfuscator.gui.proguard;

import proguard.Configuration;
import proguard.ConfigurationParser;
import proguard.ProGuard;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ProGuardClassRenamer {
    private ProGuardClassRenamer() {
    }

    public static void renameClasses(File inputJar, File outputJar, File runtimePath, List<String> exemptionRules, Map<String, Object> options) throws IOException {
        File config = File.createTempFile("skidfuscator-proguard-", ".pro");
        config.deleteOnExit();

        try (PrintWriter writer = new PrintWriter(config, "UTF-8")) {
            writer.println("-injars " + quote(inputJar));
            writer.println("-outjars " + quote(outputJar));
            writer.println();
            if (option(options, "dontshrink", true)) writer.println("-dontshrink");
            if (option(options, "dontoptimize", true)) writer.println("-dontoptimize");
            if (option(options, "dontpreverify", true)) writer.println("-dontpreverify");
            if (option(options, "ignorewarnings", true)) writer.println("-ignorewarnings");
            if (option(options, "overloadaggressively", true)) writer.println("-overloadaggressively");
            if (option(options, "adaptclassstrings", true)) writer.println("-adaptclassstrings");

            String resourceFilter = option(options, "resourcefilter", "**.properties,**.xml,**.yml,**.yaml,**.json,META-INF/MANIFEST.MF");
            if (option(options, "adaptresourcefilenames", true) && !resourceFilter.isEmpty()) {
                writer.println("-adaptresourcefilenames " + resourceFilter);
            }
            if (option(options, "adaptresourcefilecontents", true) && !resourceFilter.isEmpty()) {
                writer.println("-adaptresourcefilecontents " + resourceFilter);
            }

            String keepAttributes = option(options, "keepattributes", "Signature,*Annotation*,InnerClasses,EnclosingMethod,Exceptions");
            if (!keepAttributes.isEmpty()) {
                writer.println("-keepattributes " + keepAttributes);
            }
            if (option(options, "keepmain", true)) {
                writer.println("-keepclassmembers class * { public static void main(java.lang.String[]); }");
            }

            for (String keepRule : keepRules(exemptionRules)) {
                writer.println(keepRule);
            }

            String extraRules = option(options, "extrarules", "");
            if (!extraRules.isEmpty()) {
                writer.println();
                for (String line : extraRules.split("\\R")) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        writer.println(trimmed);
                    }
                }
            }

            writer.println();
            for (String libraryJar : libraryJars(runtimePath)) {
                writer.println(libraryJar);
            }
        }

        Configuration configuration = new Configuration();
        try (ConfigurationParser parser = new ConfigurationParser(
                new String[]{"@" + config.getAbsolutePath()},
                System.getProperties())) {
            parser.parse(configuration);
        } catch (Exception e) {
            throw new IOException("Failed to parse generated ProGuard config", e);
        }

        try {
            new ProGuard(configuration).execute();
        } catch (Exception e) {
            throw new IOException("ProGuard class renaming failed", e);
        }
    }

    private static boolean option(Map<String, Object> options, String key, boolean defaultValue) {
        if (options == null || !options.containsKey(key)) {
            return defaultValue;
        }

        Object value = options.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static String option(Map<String, Object> options, String key, String defaultValue) {
        if (options == null || !options.containsKey(key)) {
            return defaultValue;
        }

        Object value = options.get(key);
        if (value == null) {
            return "";
        }
        return String.valueOf(value).trim();
    }

    private static List<String> keepRules(List<String> exemptionRules) {
        Set<String> keeps = new LinkedHashSet<>();
        keeps.add("com.sun.jna");
        keeps.add("org.jnativehook");

        if (exemptionRules != null) {
            for (String rule : exemptionRules) {
                String prefix = classPrefix(rule);
                if (prefix != null && !prefix.isEmpty() && !prefix.equals("*")) {
                    keeps.add(prefix);
                }
            }
        }

        List<String> result = new ArrayList<>();
        for (String keep : keeps) {
            result.add("-keep class " + keep + ".** { *; }");
        }
        return result;
    }

    private static String classPrefix(String rule) {
        if (rule == null) {
            return null;
        }

        String value = rule.trim();
        if (!value.startsWith("@class ")) {
            return null;
        }

        value = value.substring("@class ".length()).trim();
        if (value.endsWith(".**")) {
            value = value.substring(0, value.length() - 3);
        } else if (value.endsWith(".*")) {
            value = value.substring(0, value.length() - 2);
        } else {
            int lastDot = value.lastIndexOf('.');
            if (lastDot > 0) {
                value = value.substring(0, lastDot);
            }
        }

        return value.replace('/', '.');
    }

    private static List<String> libraryJars(File runtimePath) {
        List<String> libraries = new ArrayList<>();
        File runtime = runtimePath;
        if (runtime == null || !runtime.exists()) {
            String javaHome = System.getProperty("java.home");
            runtime = javaHome == null ? null : new File(javaHome);
        }

        if (runtime == null || !runtime.exists()) {
            return libraries;
        }

        if (runtime.isDirectory() && "jmods".equals(runtime.getName())) {
            addJmods(libraries, runtime);
        } else if (runtime.isDirectory()) {
            File jmods = new File(runtime, "jmods");
            File rtJar = new File(runtime, "jre/lib/rt.jar");
            if (jmods.isDirectory()) {
                addJmods(libraries, jmods);
            } else if (rtJar.isFile()) {
                libraries.add("-libraryjars " + quote(rtJar));
            }
        } else if (runtime.isFile()) {
            libraries.add("-libraryjars " + quote(runtime));
        }

        return libraries;
    }

    private static void addJmods(List<String> libraries, File jmods) {
        File[] files = jmods.listFiles((dir, name) -> name.endsWith(".jmod"));
        if (files == null) {
            return;
        }

        for (File file : files) {
            libraries.add("-libraryjars " + quote(file) + "(!**.jar;!module-info.class)");
        }
    }

    private static String quote(File file) {
        return "\"" + file.getAbsolutePath().replace('\\', '/') + "\"";
    }
}
