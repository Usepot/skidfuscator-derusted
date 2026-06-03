package sdk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Runtime self-integrity helper injected into obfuscated output.
 *
 * <p>Each protected class A contains, in its {@code <clinit>}, a call to
 * {@link #verify(Class, long)} (or {@link #verifyExit(Class, long)}) naming a
 * <em>different</em> class B and B's expected whole-file checksum. At runtime A
 * reads B's on-disk {@code .class} bytes, hashes them, and reacts if the hash no
 * longer matches — so patching B trips a checker that lives in A (a cross-class
 * mesh).</p>
 *
 * <p>The hash is {@link LongHashFunction#xx3()} over the entire class file, the
 * same primitive the rest of the SDK already relies on for build/runtime hash
 * agreement. The helper is deliberately lenient when the target resource cannot
 * be read (exploded runs, agents, odd class loaders): it never raises a false
 * positive in those cases.</p>
 */
public final class Tamper {

    private Tamper() {
    }

    /**
     * Hard-fail by throwing when {@code target}'s checksum does not match
     * {@code expected}. Thrown from a {@code <clinit>} this surfaces as an
     * {@link ExceptionInInitializerError}, poisoning the tampered program.
     */
    public static void verify(final Class<?> target, final long expected) {
        if (!matches(target, expected)) {
            throw new IllegalStateException();
        }
    }

    /**
     * Hard-fail by halting the JVM when {@code target}'s checksum does not match
     * {@code expected}. {@link Runtime#halt(int)} skips shutdown hooks so the
     * exit cannot be trivially intercepted.
     */
    public static void verifyExit(final Class<?> target, final long expected) {
        if (!matches(target, expected)) {
            Runtime.getRuntime().halt(0x5C);
        }
    }

    private static boolean matches(final Class<?> target, final long expected) {
        if (target == null) {
            return true;
        }

        // Absolute resource path from the binary name (e.g. "/a/b/C.class"), resolved
        // against the class loader root. This matches the jar entry exactly and is
        // package- and inner-class-safe, unlike a package-relative simple name.
        final String resource = "/" + target.getName().replace('.', '/') + ".class";

        InputStream in = null;
        try {
            in = target.getResourceAsStream(resource);
            if (in == null) {
                // Cannot read our own bytes (exploded dir, instrumentation, custom
                // loader). Out of scope for v1 — never false-positive on a clean jar.
                return true;
            }
            final long actual = LongHashFunction.xx3().hashBytes(readFully(in));
            return actual == expected;
        } catch (final Throwable t) {
            return true;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (final IOException ignored) {
                }
            }
        }
    }

    private static byte[] readFully(final InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        final byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
