package dev.skidfuscator.obfuscator.nativebackend.aot;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Generates a self-contained Java 8 loader for one embedded Windows x86-64 DLL. */
public final class WindowsX64NativeLoaderGenerator {
    private static final Pattern INTERNAL_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(/[A-Za-z_$][A-Za-z0-9_$]*)*");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final String WINDOWS_RESOURCE_SEGMENT = "/windows-x86_64/";

    private static final Type OWNER_OBJECT = Type.getType(Object.class);
    private static final Type OWNER_CLASS = Type.getType(Class.class);
    private static final Type OWNER_CLASS_LOADER = Type.getType(ClassLoader.class);
    private static final Type OWNER_SYSTEM = Type.getType(System.class);
    private static final Type OWNER_STRING = Type.getType(String.class);
    private static final Type OWNER_STRING_BUILDER = Type.getType(StringBuilder.class);
    private static final Type OWNER_INTEGER = Type.getType(Integer.class);
    private static final Type OWNER_LOCALE = Type.getType(Locale.class);
    private static final Type OWNER_INPUT_STREAM = Type.getType(java.io.InputStream.class);
    private static final Type OWNER_BYTE_OUTPUT = Type.getType(java.io.ByteArrayOutputStream.class);
    private static final Type OWNER_FILE = Type.getType(java.io.File.class);
    private static final Type OWNER_FILE_OUTPUT = Type.getType(java.io.FileOutputStream.class);
    private static final Type OWNER_FILE_DESCRIPTOR = Type.getType(java.io.FileDescriptor.class);
    private static final Type OWNER_FILES = Type.getType(java.nio.file.Files.class);
    private static final Type OWNER_PATH = Type.getType(java.nio.file.Path.class);
    private static final Type OWNER_COPY_OPTION = Type.getType(java.nio.file.CopyOption.class);
    private static final Type OWNER_STANDARD_COPY_OPTION = Type.getType(java.nio.file.StandardCopyOption.class);
    private static final Type OWNER_ATOMIC_MOVE_UNSUPPORTED = Type.getType(java.nio.file.AtomicMoveNotSupportedException.class);
    private static final Type OWNER_MESSAGE_DIGEST = Type.getType(java.security.MessageDigest.class);
    private static final Type OWNER_THROWABLE = Type.getType(Throwable.class);
    private static final Type OWNER_UNSATISFIED_LINK = Type.getType(UnsatisfiedLinkError.class);
    private static final Type OWNER_IO_EXCEPTION = Type.getType(java.io.IOException.class);

    public GeneratedLoader generate(
            final String internalName,
            final String manifestResourcePath,
            final String manifestSha256,
            final long manifestSize,
            final String resourcePath,
            final String sha256,
            final long librarySize,
            final String libraryFileName
    ) {
        validateInputs(internalName, manifestResourcePath, manifestSha256, manifestSize,
                resourcePath, sha256, librarySize, libraryFileName);

        final Type owner = Type.getObjectType(internalName);
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                Opcodes.V1_8,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC,
                internalName,
                null,
                "java/lang/Object",
                null
        );
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                "loaded", "Z", null, null).visitEnd();

        emitConstructor(writer);
        emitReadFully(writer);
        emitSha256(writer);
        emitEnsureLoaded(writer, owner, manifestResourcePath, manifestSha256, (int) manifestSize,
                resourcePath, sha256, (int) librarySize, libraryFileName);
        writer.visitEnd();
        return new GeneratedLoader(internalName, writer.toByteArray());
    }

    private static void emitConstructor(final ClassWriter writer) {
        final GeneratorAdapter method = new GeneratorAdapter(
                Opcodes.ACC_PRIVATE,
                new Method("<init>", "()V"),
                null,
                null,
                writer
        );
        method.loadThis();
        method.invokeConstructor(OWNER_OBJECT, new Method("<init>", "()V"));
        method.returnValue();
        method.endMethod();
    }

    private static void emitReadFully(final ClassWriter writer) {
        final GeneratorAdapter method = new GeneratorAdapter(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                new Method("readFully", "(Ljava/io/InputStream;I)[B"),
                null,
                new Type[]{OWNER_THROWABLE},
                writer
        );
        final int output = method.newLocal(OWNER_BYTE_OUTPUT);
        final int buffer = method.newLocal(Type.getType(byte[].class));
        final int count = method.newLocal(Type.INT_TYPE);
        final int total = method.newLocal(Type.INT_TYPE);
        final int failure = method.newLocal(OWNER_THROWABLE);

        method.newInstance(OWNER_BYTE_OUTPUT);
        method.dup();
        method.loadArg(1);
        method.invokeConstructor(OWNER_BYTE_OUTPUT, new Method("<init>", "(I)V"));
        method.storeLocal(output);
        method.push(8192);
        method.newArray(Type.BYTE_TYPE);
        method.storeLocal(buffer);
        method.push(0);
        method.storeLocal(total);

        final Label start = method.mark();
        final Label loop = method.mark();
        method.loadArg(0);
        method.loadLocal(buffer);
        method.invokeVirtual(OWNER_INPUT_STREAM, new Method("read", "([B)I"));
        method.storeLocal(count);
        method.loadLocal(count);
        method.push(-1);
        final Label complete = method.newLabel();
        method.ifICmp(GeneratorAdapter.EQ, complete);
        method.loadLocal(count);
        method.loadArg(1);
        method.loadLocal(total);
        method.math(GeneratorAdapter.SUB, Type.INT_TYPE);
        final Label withinBound = method.newLabel();
        method.ifICmp(GeneratorAdapter.LE, withinBound);
        throwIoException(method, "Embedded resource exceeds its declared size");
        method.mark(withinBound);
        method.loadLocal(output);
        method.loadLocal(buffer);
        method.push(0);
        method.loadLocal(count);
        method.invokeVirtual(OWNER_BYTE_OUTPUT, new Method("write", "([BII)V"));
        method.loadLocal(total);
        method.loadLocal(count);
        method.math(GeneratorAdapter.ADD, Type.INT_TYPE);
        method.storeLocal(total);
        method.goTo(loop);

        method.mark(complete);
        method.loadLocal(total);
        method.loadArg(1);
        final Label exactSize = method.newLabel();
        method.ifICmp(GeneratorAdapter.EQ, exactSize);
        throwIoException(method, "Embedded resource is shorter than its declared size");
        method.mark(exactSize);
        method.loadArg(0);
        method.invokeVirtual(OWNER_INPUT_STREAM, new Method("close", "()V"));
        method.loadLocal(output);
        method.invokeVirtual(OWNER_BYTE_OUTPUT, new Method("toByteArray", "()[B"));
        method.returnValue();
        final Label end = method.mark();

        method.catchException(start, end, OWNER_THROWABLE);
        method.storeLocal(failure);
        method.loadArg(0);
        method.invokeVirtual(OWNER_INPUT_STREAM, new Method("close", "()V"));
        method.loadLocal(failure);
        method.throwException();
        method.endMethod();
    }

    private static void emitSha256(final ClassWriter writer) {
        final GeneratorAdapter method = new GeneratorAdapter(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                new Method("sha256", "([B)Ljava/lang/String;"),
                null,
                new Type[]{Type.getType(java.security.NoSuchAlgorithmException.class)},
                writer
        );
        final int digest = method.newLocal(Type.getType(byte[].class));
        final int encoded = method.newLocal(Type.getType(char[].class));
        final int index = method.newLocal(Type.INT_TYPE);
        final int value = method.newLocal(Type.INT_TYPE);

        method.push("SHA-256");
        method.invokeStatic(OWNER_MESSAGE_DIGEST,
                new Method("getInstance", "(Ljava/lang/String;)Ljava/security/MessageDigest;"));
        method.loadArg(0);
        method.invokeVirtual(OWNER_MESSAGE_DIGEST, new Method("digest", "([B)[B"));
        method.storeLocal(digest);

        method.loadLocal(digest);
        method.arrayLength();
        method.push(2);
        method.math(GeneratorAdapter.MUL, Type.INT_TYPE);
        method.newArray(Type.CHAR_TYPE);
        method.storeLocal(encoded);
        method.push(0);
        method.storeLocal(index);

        final Label loop = method.mark();
        method.loadLocal(index);
        method.loadLocal(digest);
        method.arrayLength();
        final Label complete = method.newLabel();
        method.ifICmp(GeneratorAdapter.GE, complete);
        method.loadLocal(digest);
        method.loadLocal(index);
        method.arrayLoad(Type.BYTE_TYPE);
        method.push(255);
        method.math(GeneratorAdapter.AND, Type.INT_TYPE);
        method.storeLocal(value);

        method.loadLocal(encoded);
        method.loadLocal(index);
        method.push(2);
        method.math(GeneratorAdapter.MUL, Type.INT_TYPE);
        method.push("0123456789abcdef");
        method.loadLocal(value);
        method.push(4);
        method.math(GeneratorAdapter.USHR, Type.INT_TYPE);
        method.invokeVirtual(OWNER_STRING, new Method("charAt", "(I)C"));
        method.arrayStore(Type.CHAR_TYPE);

        method.loadLocal(encoded);
        method.loadLocal(index);
        method.push(2);
        method.math(GeneratorAdapter.MUL, Type.INT_TYPE);
        method.push(1);
        method.math(GeneratorAdapter.ADD, Type.INT_TYPE);
        method.push("0123456789abcdef");
        method.loadLocal(value);
        method.push(15);
        method.math(GeneratorAdapter.AND, Type.INT_TYPE);
        method.invokeVirtual(OWNER_STRING, new Method("charAt", "(I)C"));
        method.arrayStore(Type.CHAR_TYPE);

        method.iinc(index, 1);
        method.goTo(loop);
        method.mark(complete);
        method.newInstance(OWNER_STRING);
        method.dup();
        method.loadLocal(encoded);
        method.invokeConstructor(OWNER_STRING, new Method("<init>", "([C)V"));
        method.returnValue();
        method.endMethod();
    }

    private static void emitEnsureLoaded(
            final ClassWriter writer,
            final Type owner,
            final String manifestResourcePath,
            final String manifestSha256,
            final int manifestSize,
            final String resourcePath,
            final String sha256,
            final int librarySize,
            final String libraryFileName
    ) {
        final GeneratorAdapter method = new GeneratorAdapter(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNCHRONIZED,
                new Method("ensureLoaded", "()V"),
                null,
                null,
                writer
        );
        method.getStatic(owner, "loaded", Type.BOOLEAN_TYPE);
        final Label notLoaded = method.newLabel();
        method.ifZCmp(GeneratorAdapter.EQ, notLoaded);
        method.returnValue();
        method.mark(notLoaded);

        final int os = method.newLocal(OWNER_STRING);
        final int arch = method.newLocal(OWNER_STRING);
        final int input = method.newLocal(OWNER_INPUT_STREAM);
        final int manifest = method.newLocal(Type.getType(byte[].class));
        final int library = method.newLocal(Type.getType(byte[].class));
        final int directory = method.newLocal(OWNER_FILE);
        final int target = method.newLocal(OWNER_FILE);
        final int temporary = method.newLocal(OWNER_FILE);
        final int output = method.newLocal(OWNER_FILE_OUTPUT);
        final int failure = method.newLocal(OWNER_THROWABLE);

        final Label protectedStart = method.mark();
        emitPlatformCheck(method, os, arch);

        emitOpenResource(method, owner, input, manifestResourcePath,
                "Embedded compiler manifest is missing");
        method.loadLocal(input);
        method.push(manifestSize);
        method.invokeStatic(owner, new Method("readFully", "(Ljava/io/InputStream;I)[B"));
        method.storeLocal(manifest);
        emitDigestCheck(method, owner, manifest, manifestSha256,
                "Embedded compiler manifest digest mismatch");

        emitOpenResource(method, owner, input, resourcePath, "Embedded native library is missing");

        method.loadLocal(input);
        method.push(librarySize);
        method.invokeStatic(owner, new Method("readFully", "(Ljava/io/InputStream;I)[B"));
        method.storeLocal(library);
        emitDigestCheck(method, owner, library, sha256, "Embedded native library digest mismatch");

        emitExtractionDirectory(method, owner, sha256, directory);
        method.newInstance(OWNER_FILE);
        method.dup();
        method.loadLocal(directory);
        method.push(libraryFileName);
        method.invokeConstructor(OWNER_FILE, new Method("<init>", "(Ljava/io/File;Ljava/lang/String;)V"));
        method.invokeVirtual(OWNER_FILE, new Method("getAbsoluteFile", "()Ljava/io/File;"));
        method.storeLocal(target);

        final Label writeLibrary = method.newLabel();
        final Label libraryReady = method.newLabel();
        method.loadLocal(target);
        method.invokeVirtual(OWNER_FILE, new Method("isFile", "()Z"));
        method.ifZCmp(GeneratorAdapter.EQ, writeLibrary);
        method.loadLocal(target);
        method.invokeVirtual(OWNER_FILE, new Method("toPath", "()Ljava/nio/file/Path;"));
        method.invokeStatic(OWNER_FILES, new Method("readAllBytes", "(Ljava/nio/file/Path;)[B"));
        method.invokeStatic(owner, new Method("sha256", "([B)Ljava/lang/String;"));
        method.push(sha256);
        method.invokeVirtual(OWNER_STRING, new Method("equals", "(Ljava/lang/Object;)Z"));
        method.ifZCmp(GeneratorAdapter.NE, libraryReady);

        method.mark(writeLibrary);
        method.push("skid-");
        method.push(".tmp");
        method.loadLocal(directory);
        method.invokeStatic(OWNER_FILE,
                new Method("createTempFile", "(Ljava/lang/String;Ljava/lang/String;Ljava/io/File;)Ljava/io/File;"));
        method.storeLocal(temporary);
        method.newInstance(OWNER_FILE_OUTPUT);
        method.dup();
        method.loadLocal(temporary);
        method.invokeConstructor(OWNER_FILE_OUTPUT, new Method("<init>", "(Ljava/io/File;)V"));
        method.storeLocal(output);
        method.loadLocal(output);
        method.loadLocal(library);
        method.invokeVirtual(OWNER_FILE_OUTPUT, new Method("write", "([B)V"));
        method.loadLocal(output);
        method.invokeVirtual(OWNER_FILE_OUTPUT, new Method("getFD", "()Ljava/io/FileDescriptor;"));
        method.invokeVirtual(OWNER_FILE_DESCRIPTOR, new Method("sync", "()V"));
        method.loadLocal(output);
        method.invokeVirtual(OWNER_FILE_OUTPUT, new Method("close", "()V"));
        emitAtomicMove(method, temporary, target);
        method.loadLocal(temporary);
        method.invokeVirtual(OWNER_FILE, new Method("delete", "()Z"));
        method.pop();

        method.mark(libraryReady);
        method.loadLocal(target);
        method.push(false);
        method.push(false);
        method.invokeVirtual(OWNER_FILE, new Method("setWritable", "(ZZ)Z"));
        method.pop();
        method.loadLocal(target);
        method.push(true);
        method.push(true);
        method.invokeVirtual(OWNER_FILE, new Method("setReadable", "(ZZ)Z"));
        method.pop();
        method.loadLocal(target);
        method.invokeVirtual(OWNER_FILE, new Method("getAbsolutePath", "()Ljava/lang/String;"));
        method.invokeStatic(OWNER_SYSTEM, new Method("load", "(Ljava/lang/String;)V"));
        method.push(true);
        method.putStatic(owner, "loaded", Type.BOOLEAN_TYPE);
        method.returnValue();
        final Label protectedEnd = method.mark();

        method.catchException(protectedStart, protectedEnd, OWNER_THROWABLE);
        method.storeLocal(failure);
        method.newInstance(OWNER_UNSATISFIED_LINK);
        method.dup();
        method.push("Failed to load protected native library");
        method.invokeConstructor(OWNER_UNSATISFIED_LINK, new Method("<init>", "(Ljava/lang/String;)V"));
        method.dup();
        method.loadLocal(failure);
        method.invokeVirtual(OWNER_THROWABLE,
                new Method("initCause", "(Ljava/lang/Throwable;)Ljava/lang/Throwable;"));
        method.pop();
        method.throwException();
        method.endMethod();
    }

    private static void emitPlatformCheck(final GeneratorAdapter method, final int os, final int arch) {
        method.push("os.name");
        method.push("");
        method.invokeStatic(OWNER_SYSTEM,
                new Method("getProperty", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"));
        method.getStatic(OWNER_LOCALE, "ROOT", OWNER_LOCALE);
        method.invokeVirtual(OWNER_STRING, new Method("toLowerCase", "(Ljava/util/Locale;)Ljava/lang/String;"));
        method.storeLocal(os);
        method.push("os.arch");
        method.push("");
        method.invokeStatic(OWNER_SYSTEM,
                new Method("getProperty", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"));
        method.getStatic(OWNER_LOCALE, "ROOT", OWNER_LOCALE);
        method.invokeVirtual(OWNER_STRING, new Method("toLowerCase", "(Ljava/util/Locale;)Ljava/lang/String;"));
        method.storeLocal(arch);

        method.loadLocal(os);
        method.push("win");
        method.invokeVirtual(OWNER_STRING, new Method("contains", "(Ljava/lang/CharSequence;)Z"));
        final Label unsupported = method.newLabel();
        method.ifZCmp(GeneratorAdapter.EQ, unsupported);
        final Label supported = method.newLabel();
        for (final String supportedArch : new String[]{"amd64", "x86_64", "x64"}) {
            method.loadLocal(arch);
            method.push(supportedArch);
            method.invokeVirtual(OWNER_STRING, new Method("equals", "(Ljava/lang/Object;)Z"));
            method.ifZCmp(GeneratorAdapter.NE, supported);
        }
        method.mark(unsupported);
        throwUnsatisfiedLink(method, "Native artifact supports only Windows x86-64");
        method.mark(supported);
    }

    private static void emitOpenResource(
            final GeneratorAdapter method,
            final Type owner,
            final int input,
            final String resourcePath,
            final String missingMessage
    ) {
        method.push(owner);
        method.push("/" + resourcePath);
        method.invokeVirtual(OWNER_CLASS,
                new Method("getResourceAsStream", "(Ljava/lang/String;)Ljava/io/InputStream;"));
        method.storeLocal(input);
        method.loadLocal(input);
        final Label resourcePresent = method.newLabel();
        method.ifNonNull(resourcePresent);
        throwUnsatisfiedLink(method, missingMessage);
        method.mark(resourcePresent);
    }

    private static void emitDigestCheck(
            final GeneratorAdapter method,
            final Type owner,
            final int bytes,
            final String sha256,
            final String message
    ) {
        method.loadLocal(bytes);
        method.invokeStatic(owner, new Method("sha256", "([B)Ljava/lang/String;"));
        method.push(sha256);
        method.invokeVirtual(OWNER_STRING, new Method("equals", "(Ljava/lang/Object;)Z"));
        final Label valid = method.newLabel();
        method.ifZCmp(GeneratorAdapter.NE, valid);
        throwUnsatisfiedLink(method, message);
        method.mark(valid);
    }

    private static void emitExtractionDirectory(
            final GeneratorAdapter method,
            final Type owner,
            final String sha256,
            final int directory
    ) {
        method.newInstance(OWNER_FILE);
        method.dup();
        method.push("java.io.tmpdir");
        method.invokeStatic(OWNER_SYSTEM, new Method("getProperty", "(Ljava/lang/String;)Ljava/lang/String;"));
        method.newInstance(OWNER_STRING_BUILDER);
        method.dup();
        method.invokeConstructor(OWNER_STRING_BUILDER, new Method("<init>", "()V"));
        method.push("skidfuscator-" + sha256.substring(0, 12) + "-");
        method.invokeVirtual(OWNER_STRING_BUILDER,
                new Method("append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;"));
        method.push(owner);
        method.invokeVirtual(OWNER_CLASS, new Method("getClassLoader", "()Ljava/lang/ClassLoader;"));
        method.invokeStatic(OWNER_SYSTEM, new Method("identityHashCode", "(Ljava/lang/Object;)I"));
        method.invokeStatic(OWNER_INTEGER, new Method("toHexString", "(I)Ljava/lang/String;"));
        method.invokeVirtual(OWNER_STRING_BUILDER,
                new Method("append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;"));
        method.invokeVirtual(OWNER_STRING_BUILDER, new Method("toString", "()Ljava/lang/String;"));
        method.invokeConstructor(OWNER_FILE, new Method("<init>", "(Ljava/lang/String;Ljava/lang/String;)V"));
        method.invokeVirtual(OWNER_FILE, new Method("getAbsoluteFile", "()Ljava/io/File;"));
        method.storeLocal(directory);

        method.loadLocal(directory);
        method.invokeVirtual(OWNER_FILE, new Method("isDirectory", "()Z"));
        final Label ready = method.newLabel();
        method.ifZCmp(GeneratorAdapter.NE, ready);
        method.loadLocal(directory);
        method.invokeVirtual(OWNER_FILE, new Method("mkdirs", "()Z"));
        method.ifZCmp(GeneratorAdapter.NE, ready);
        method.newInstance(OWNER_IO_EXCEPTION);
        method.dup();
        method.push("Unable to create native extraction directory");
        method.invokeConstructor(OWNER_IO_EXCEPTION, new Method("<init>", "(Ljava/lang/String;)V"));
        method.throwException();
        method.mark(ready);
        method.loadLocal(directory);
        method.push(true);
        method.push(true);
        method.invokeVirtual(OWNER_FILE, new Method("setReadable", "(ZZ)Z"));
        method.pop();
        method.loadLocal(directory);
        method.push(true);
        method.push(true);
        method.invokeVirtual(OWNER_FILE, new Method("setWritable", "(ZZ)Z"));
        method.pop();
    }

    private static void emitAtomicMove(final GeneratorAdapter method, final int temporary, final int target) {
        final Label start = method.mark();
        emitMove(method, temporary, target, true);
        final Label end = method.mark();
        final Label complete = method.newLabel();
        method.goTo(complete);
        method.catchException(start, end, OWNER_ATOMIC_MOVE_UNSUPPORTED);
        method.pop();
        emitMove(method, temporary, target, false);
        method.mark(complete);
    }

    private static void emitMove(
            final GeneratorAdapter method,
            final int temporary,
            final int target,
            final boolean atomic
    ) {
        method.loadLocal(temporary);
        method.invokeVirtual(OWNER_FILE, new Method("toPath", "()Ljava/nio/file/Path;"));
        method.loadLocal(target);
        method.invokeVirtual(OWNER_FILE, new Method("toPath", "()Ljava/nio/file/Path;"));
        method.push(atomic ? 2 : 1);
        method.newArray(OWNER_COPY_OPTION);
        method.dup();
        method.push(0);
        method.getStatic(OWNER_STANDARD_COPY_OPTION, "REPLACE_EXISTING", OWNER_STANDARD_COPY_OPTION);
        method.arrayStore(OWNER_COPY_OPTION);
        if (atomic) {
            method.dup();
            method.push(1);
            method.getStatic(OWNER_STANDARD_COPY_OPTION, "ATOMIC_MOVE", OWNER_STANDARD_COPY_OPTION);
            method.arrayStore(OWNER_COPY_OPTION);
        }
        method.invokeStatic(OWNER_FILES,
                new Method("move", "(Ljava/nio/file/Path;Ljava/nio/file/Path;[Ljava/nio/file/CopyOption;)Ljava/nio/file/Path;"));
        method.pop();
    }

    private static void throwUnsatisfiedLink(final GeneratorAdapter method, final String message) {
        method.newInstance(OWNER_UNSATISFIED_LINK);
        method.dup();
        method.push(message);
        method.invokeConstructor(OWNER_UNSATISFIED_LINK, new Method("<init>", "(Ljava/lang/String;)V"));
        method.throwException();
    }

    private static void throwIoException(final GeneratorAdapter method, final String message) {
        method.newInstance(OWNER_IO_EXCEPTION);
        method.dup();
        method.push(message);
        method.invokeConstructor(OWNER_IO_EXCEPTION, new Method("<init>", "(Ljava/lang/String;)V"));
        method.throwException();
    }

    private static void validateInputs(
            final String internalName,
            final String manifestResourcePath,
            final String manifestSha256,
            final long manifestSize,
            final String resourcePath,
            final String sha256,
            final long librarySize,
            final String libraryFileName
    ) {
        Objects.requireNonNull(internalName, "internalName");
        Objects.requireNonNull(manifestResourcePath, "manifestResourcePath");
        Objects.requireNonNull(manifestSha256, "manifestSha256");
        Objects.requireNonNull(resourcePath, "resourcePath");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(libraryFileName, "libraryFileName");
        if (!INTERNAL_NAME.matcher(internalName).matches()) {
            throw new IllegalArgumentException("Invalid loader internal name: " + internalName);
        }
        if (!manifestResourcePath.startsWith("META-INF/skidfuscator/native/")
                || !manifestResourcePath.endsWith("/manifest.properties")
                || manifestResourcePath.startsWith("/")
                || manifestResourcePath.contains("..")
                || manifestResourcePath.contains("\\")) {
            throw new IllegalArgumentException("Invalid native manifest resource path: " + manifestResourcePath);
        }
        if (!SHA_256.matcher(manifestSha256).matches()) {
            throw new IllegalArgumentException("Invalid manifest SHA-256: " + manifestSha256);
        }
        validateResourceSize(manifestSize, "manifest");
        if (!resourcePath.startsWith("META-INF/skidfuscator/native/")
                || !resourcePath.contains(WINDOWS_RESOURCE_SEGMENT)
                || resourcePath.startsWith("/")
                || resourcePath.contains("..")
                || resourcePath.contains("\\")) {
            throw new IllegalArgumentException("Invalid Windows native resource path: " + resourcePath);
        }
        if (!SHA_256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("Invalid SHA-256: " + sha256);
        }
        validateResourceSize(librarySize, "native library");
        if (!libraryFileName.endsWith(".dll")
                || libraryFileName.contains("/")
                || libraryFileName.contains("\\")) {
            throw new IllegalArgumentException("Invalid DLL file name: " + libraryFileName);
        }
    }

    private static void validateResourceSize(final long size, final String label) {
        if (size <= 0 || size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid " + label + " size: " + size);
        }
    }

    public record GeneratedLoader(String internalName, byte[] bytecode) {
        public GeneratedLoader {
            Objects.requireNonNull(internalName, "internalName");
            bytecode = Objects.requireNonNull(bytecode, "bytecode").clone();
        }

        @Override
        public byte[] bytecode() {
            return bytecode.clone();
        }
    }
}
