package dev.skidfuscator.nativetoolchain;

import dev.skidfuscator.nativeir.NativeBackend;
import dev.skidfuscator.nativeir.NativeBlock;
import dev.skidfuscator.nativeir.NativeFunction;
import dev.skidfuscator.nativeir.NativeInstruction;
import dev.skidfuscator.nativeir.NativeModule;
import dev.skidfuscator.nativeir.NativeOpcode;
import dev.skidfuscator.nativeir.NativeOperand;
import dev.skidfuscator.nativeir.NativeTerminator;
import dev.skidfuscator.nativeir.NativeType;
import dev.skidfuscator.nativeir.SourceLocation;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeStringProtectionTest {
    private static final String SECRET = "SKID{native_secret_42}";

    @Test
    void matchesTheFixedUtf16ProtectionVectorAndRoundTripsExactCodeUnits() {
        final String value = "A\0\uD83D\uDE80\uDFFF";
        final NativeStringProtection protection = NativeStringProtection.fromBuildMaterial(seed(0));

        final NativeStringProtection.ProtectedString protectedString = protection.protect(value, 7);

        assertEquals(7, protectedString.cacheId());
        assertEquals(-2886333048262405942L, protectedString.fragmentA());
        assertEquals(-1053431178672522848L, protectedString.fragmentB());
        assertArrayEquals(new int[] {1158, 64897, 9248, 50510, 23019},
                protectedString.ciphertext());
        assertArrayEquals(value.chars().toArray(), NativeStringProtection.decode(protectedString));
    }

    @Test
    void fixedBuildMaterialIsDeterministicAndDifferentMaterialDiversifiesEveryPayloadPart() {
        final NativeStringProtection first = NativeStringProtection.fromBuildMaterial(seed(0));
        final NativeStringProtection same = NativeStringProtection.fromBuildMaterial(seed(0));
        final NativeStringProtection different = NativeStringProtection.fromBuildMaterial(seed(31));

        final NativeStringProtection.ProtectedString firstValue = first.protect(SECRET, 0);
        final NativeStringProtection.ProtectedString sameValue = same.protect(SECRET, 0);
        final NativeStringProtection.ProtectedString differentValue = different.protect(SECRET, 0);

        assertEquals(firstValue.fragmentA(), sameValue.fragmentA());
        assertEquals(firstValue.fragmentB(), sameValue.fragmentB());
        assertArrayEquals(firstValue.ciphertext(), sameValue.ciphertext());
        assertNotEquals(firstValue.fragmentA(), differentValue.fragmentA());
        assertNotEquals(firstValue.fragmentB(), differentValue.fragmentB());
        assertFalse(java.util.Arrays.equals(firstValue.ciphertext(), differentValue.ciphertext()));
    }

    @Test
    void protectedEmitterUsesFragmentedAbiAndContainsNoDirectPlaintextRepresentation() {
        final byte[] material = seed(0);
        final NativeModule module = stringModule(SECRET);
        final CanonicalLlvmIrEmitter firstEmitter = new CanonicalLlvmIrEmitter(
                NativeStringProtection.fromBuildMaterial(material));
        final CanonicalLlvmIrEmitter sameEmitter = new CanonicalLlvmIrEmitter(
                NativeStringProtection.fromBuildMaterial(material));
        final CanonicalLlvmIrEmitter differentEmitter = new CanonicalLlvmIrEmitter(
                NativeStringProtection.fromBuildMaterial(seed(31)));

        final byte[] first = firstEmitter.emit(module);
        final byte[] same = sameEmitter.emit(module);
        final byte[] different = differentEmitter.emit(module);
        final String llvm = new String(first, StandardCharsets.UTF_8);
        final NativeStringProtection.ProtectedString protectedString =
                NativeStringProtection.fromBuildMaterial(material).protect(SECRET, 0);
        final long rawState = protectedString.fragmentA()
                ^ Long.rotateLeft(protectedString.fragmentB(), 17)
                ^ NativeStringProtection.FRAGMENT_CONSTANT;

        assertArrayEquals(first, same);
        assertFalse(java.util.Arrays.equals(first, different));
        assertTrue(llvm.contains("declare ptr @\"skid.semantic.string_constant.protected.v1\""
                + "(ptr, ptr, i32, i32, i64, i64)"));
        assertFalse(llvm.contains("declare ptr @\"skid.semantic.string_constant.v1\""));
        assertTrue(llvm.contains(", i32 " + SECRET.length() + ", i32 0, i64 "
                + protectedString.fragmentA() + ", i64 " + protectedString.fragmentB() + ")"));
        assertFalse(llvm.contains(Long.toString(rawState)));
        assertFalse(llvm.contains(Long.toUnsignedString(rawState)));
        assertFalse(contains(first, SECRET.getBytes(StandardCharsets.UTF_8)));
        assertFalse(contains(first, SECRET.getBytes(StandardCharsets.UTF_16LE)));
        assertFalse(contains(first, SECRET.getBytes(StandardCharsets.UTF_16BE)));
    }

    @Test
    void rejectsInsufficientOrMutableBuildMaterial() {
        assertThrows(IllegalArgumentException.class,
                () -> NativeStringProtection.fromBuildMaterial(new byte[15]));
        final byte[] material = seed(0);
        final NativeStringProtection protection = NativeStringProtection.fromBuildMaterial(material);
        final NativeStringProtection.ProtectedString before = protection.protect(SECRET, 0);
        java.util.Arrays.fill(material, (byte) 0x7f);
        final NativeStringProtection.ProtectedString after = protection.protect(SECRET, 0);
        assertArrayEquals(before.ciphertext(), after.ciphertext());
        assertEquals(before.fragmentA(), after.fragmentA());
        assertEquals(before.fragmentB(), after.fragmentB());
    }

    private static NativeModule stringModule(final String value) {
        final NativeType.Reference stringType = new NativeType.Reference("java/lang/String", false);
        final NativeInstruction.Operation literal = new NativeInstruction.Operation(
                "literal", stringType, NativeOpcode.STRING_CONSTANT, List.of(),
                Map.of("value", value), SourceLocation.UNKNOWN);
        final NativeFunction function = new NativeFunction(
                "protected_string", "example/Secrets", "reveal", "()Ljava/lang/String;",
                stringType, List.of(), "entry", NativeBackend.AOT, false, true,
                Map.of("java.static", "true", "semanticContext", "jni"));
        function.addBlock(new NativeBlock("entry")
                .addInstruction(literal)
                .terminate(new NativeTerminator.Return(
                        java.util.Optional.of(new NativeOperand.Value("literal", stringType)),
                        SourceLocation.UNKNOWN)));
        return new NativeModule("protected-strings").addFunction(function);
    }

    private static byte[] seed(final int xor) {
        final byte[] seed = new byte[32];
        for (int index = 0; index < seed.length; index++) {
            seed[index] = (byte) (index ^ xor);
        }
        return seed;
    }

    private static boolean contains(final byte[] haystack, final byte[] needle) {
        outer:
        for (int start = 0; start <= haystack.length - needle.length; start++) {
            for (int index = 0; index < needle.length; index++) {
                if (haystack[start + index] != needle[index]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
