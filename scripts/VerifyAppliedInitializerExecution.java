import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.*;

/** Executes actual Mixin-produced target constructors without starting OpenGL. */
public final class VerifyAppliedInitializerExecution {
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
    private static String digest(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[32768]; int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder text = new StringBuilder();
        for (byte b : digest.digest()) text.append(String.format("%02x", b & 255));
        return text.toString();
    }
    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true);
        Object value = field.get(owner); require(value != null, owner.getClass().getName() + "." + name + " was not initialized");
        return value;
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("candidate.jar libraries.txt mixin-application-directory");
        Path candidate = Paths.get(args[0]).toAbsolutePath(), applied = Paths.get(args[2]).toAbsolutePath();
        List<String> report = Files.readAllLines(applied.resolve("application-report.tsv"), StandardCharsets.UTF_8);
        require(report.contains("CANDIDATE_SHA256\t" + digest(candidate)), "Applied targets do not belong to this candidate");
        List<URL> urls = new ArrayList<>();
        urls.add(applied.resolve("targets").toUri().toURL());
        urls.add(applied.resolve("srg-forge-verifier.jar").toUri().toURL());
        urls.add(candidate.toUri().toURL());
        for (String line : Files.readAllLines(Paths.get(args[1]), StandardCharsets.UTF_8))
            if (!line.trim().isEmpty()) urls.add(Paths.get(line.trim()).toUri().toURL());
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), null)) {
            Class<?> button = Class.forName("net.minecraft.client.gui.GuiButton", true, loader);
            Constructor<?> ctor = button.getConstructor(int.class, int.class, int.class, int.class, int.class, String.class);
            Object first = ctor.newInstance(1, 2, 3, 80, 20, "first");
            Object second = ctor.newInstance(2, 2, 3, 80, 20, "second");
            Supplier<Boolean> firstHovered = (Supplier<Boolean>) field(first, "getHovered");
            Supplier<Boolean> secondHovered = (Supplier<Boolean>) field(second, "getHovered");
            Consumer<Boolean> setHovered = (Consumer<Boolean>) field(first, "setHovered");
            require(field(first, "setMouseDragged") instanceof BiConsumer, "Mouse drag callback has wrong type");
            require(!firstHovered.get() && !secondHovered.get(), "Unexpected initial hover state");
            setHovered.accept(true);
            require(firstHovered.get() && !secondHovered.get(), "Callbacks did not capture the correct target instance");
            setHovered.accept(false);
            require(!firstHovered.get(), "Callback failed to update the real shadow field");
            System.out.println("APPLIED_GUI_BUTTON: 2 real target constructors; 3 callback fields initialized; supplier/consumer capture and shadow-field updates passed.");
            Class<?> chunk = Class.forName("net.minecraft.client.renderer.chunk.CompiledChunk", true, loader);
            Object a = chunk.getConstructor().newInstance(), b = chunk.getConstructor().newInstance();
            Object firstList = field(a, "list"), secondList = field(b, "list");
            require(firstList instanceof LinkedList && secondList instanceof LinkedList, "Final initializer did not produce LinkedList");
            require(firstList != secondList, "Mutable final initializer was shared between instances");
            require(Modifier.isFinal(chunk.getDeclaredField("list").getModifiers()), "Mixin final field modifier was weakened");
            System.out.println("APPLIED_COMPILED_CHUNK: 2 real target constructors; final LinkedList initialized, per-instance ownership and final modifier preserved.");
        }
    }
}
