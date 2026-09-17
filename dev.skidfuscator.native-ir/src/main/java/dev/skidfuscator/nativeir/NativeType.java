package dev.skidfuscator.nativeir;

import java.util.Objects;

/** A target-neutral value type understood by both native backends. */
public sealed interface NativeType permits NativeType.Primitive, NativeType.Reference, NativeType.Array {
    String displayName();

    default boolean isVoid() {
        return this == Primitive.VOID;
    }

    default boolean isReferenceLike() {
        return this instanceof Reference || this instanceof Array;
    }

    enum Primitive implements NativeType {
        VOID("void", 0, false),
        I1("i1", 1, false),
        I8("i8", 8, true),
        I16("i16", 16, true),
        I32("i32", 32, true),
        I64("i64", 64, true),
        F32("f32", 32, false),
        F64("f64", 64, false);

        private final String displayName;
        private final int bits;
        private final boolean integer;

        Primitive(final String displayName, final int bits, final boolean integer) {
            this.displayName = displayName;
            this.bits = bits;
            this.integer = integer;
        }

        @Override
        public String displayName() {
            return displayName;
        }

        public int bits() {
            return bits;
        }

        public boolean isInteger() {
            return this == I1 || integer;
        }

        public boolean isFloatingPoint() {
            return this == F32 || this == F64;
        }

        public boolean isNumeric() {
            return isInteger() || isFloatingPoint();
        }
    }

    /** A JVM reference represented by a JNI reference at the native boundary. */
    record Reference(String internalName, boolean nullable) implements NativeType {
        public Reference {
            Objects.requireNonNull(internalName, "internalName");
            if (internalName.isBlank()
                    || internalName.startsWith("[")
                    || internalName.startsWith("/")
                    || internalName.endsWith("/")
                    || internalName.contains("//")
                    || internalName.indexOf('.') >= 0
                    || internalName.indexOf(';') >= 0
                    || internalName.indexOf('(') >= 0
                    || internalName.indexOf(')') >= 0) {
                throw new IllegalArgumentException("Expected a non-array JVM internal name: " + internalName);
            }
        }

        @Override
        public String displayName() {
            return (nullable ? "ref?" : "ref") + "<" + internalName + ">";
        }
    }

    /** A JVM array represented by a JNI array reference at the native boundary. */
    record Array(NativeType componentType, boolean nullable) implements NativeType {
        public Array {
            Objects.requireNonNull(componentType, "componentType");
            if (componentType.isVoid()) {
                throw new IllegalArgumentException("An array component cannot be void");
            }
        }

        @Override
        public String displayName() {
            return (nullable ? "array?" : "array") + "<" + componentType.displayName() + ">";
        }
    }
}
