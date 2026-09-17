package dev.skidfuscator.nativeir;

import java.util.ArrayList;
import java.util.List;

/** Dependency-free JVM field and method descriptor parser used by native-IR validation. */
public final class JavaDescriptor {
    private JavaDescriptor() {
    }

    public static NativeType fieldType(final String descriptor) {
        final Cursor cursor = new Cursor(descriptor);
        final NativeType type = cursor.type(false);
        cursor.requireEnd();
        return type;
    }

    public static Method method(final String descriptor) {
        final Cursor cursor = new Cursor(descriptor);
        cursor.expect('(');
        final List<NativeType> parameters = new ArrayList<>();
        while (cursor.peek() != ')') {
            parameters.add(cursor.type(false));
        }
        cursor.expect(')');
        final NativeType returnType = cursor.type(true);
        cursor.requireEnd();
        return new Method(parameters, returnType);
    }

    public static boolean compatible(final NativeType descriptorType, final NativeType irType) {
        if (descriptorType instanceof NativeType.Reference expected
                && irType instanceof NativeType.Reference actual) {
            return expected.internalName().equals(actual.internalName());
        }
        if (descriptorType instanceof NativeType.Array expected
                && irType instanceof NativeType.Array actual) {
            return compatible(expected.componentType(), actual.componentType());
        }
        return descriptorType.equals(irType);
    }

    public record Method(List<NativeType> parameters, NativeType returnType) {
        public Method {
            parameters = List.copyOf(parameters);
        }
    }

    private static final class Cursor {
        private final String descriptor;
        private int index;

        private Cursor(final String descriptor) {
            if (descriptor == null || descriptor.isEmpty()) {
                throw new IllegalArgumentException("JVM descriptor cannot be empty");
            }
            this.descriptor = descriptor;
        }

        private NativeType type(final boolean allowVoid) {
            if (index >= descriptor.length()) {
                throw invalid();
            }
            return switch (descriptor.charAt(index++)) {
                case 'V' -> {
                    if (!allowVoid) throw invalid();
                    yield NativeType.Primitive.VOID;
                }
                case 'Z' -> NativeType.Primitive.I1;
                case 'B' -> NativeType.Primitive.I8;
                case 'C', 'S' -> NativeType.Primitive.I16;
                case 'I' -> NativeType.Primitive.I32;
                case 'J' -> NativeType.Primitive.I64;
                case 'F' -> NativeType.Primitive.F32;
                case 'D' -> NativeType.Primitive.F64;
                case 'L' -> objectType();
                case '[' -> new NativeType.Array(type(false), true);
                default -> throw invalid();
            };
        }

        private NativeType objectType() {
            final int end = descriptor.indexOf(';', index);
            if (end <= index) {
                throw invalid();
            }
            final String internalName = descriptor.substring(index, end);
            index = end + 1;
            try {
                return new NativeType.Reference(internalName, true);
            } catch (IllegalArgumentException exception) {
                throw invalid();
            }
        }

        private char peek() {
            if (index >= descriptor.length()) throw invalid();
            return descriptor.charAt(index);
        }

        private void expect(final char expected) {
            if (peek() != expected) throw invalid();
            index++;
        }

        private void requireEnd() {
            if (index != descriptor.length()) throw invalid();
        }

        private IllegalArgumentException invalid() {
            return new IllegalArgumentException("Malformed JVM descriptor: " + descriptor);
        }
    }
}
