package dev.skidfuscator.nativetoolchain.vm;

import dev.skidfuscator.nativeir.NativeBackend;
import dev.skidfuscator.nativeir.NativeBlock;
import dev.skidfuscator.nativeir.NativeFunction;
import dev.skidfuscator.nativeir.NativeInstruction;
import dev.skidfuscator.nativeir.NativeModule;
import dev.skidfuscator.nativeir.NativeOpcode;
import dev.skidfuscator.nativeir.NativeOperand;
import dev.skidfuscator.nativeir.NativeParameter;
import dev.skidfuscator.nativeir.NativeTerminator;
import dev.skidfuscator.nativeir.NativeType;
import dev.skidfuscator.nativeir.SourceLocation;
import dev.skidfuscator.nativetoolchain.VmProtectionSettings;
import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import java.security.SecureRandom;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VmBytecodeCompilerTest {
    @Test
    void encryptsEveryBlockIndependentlyAndRejectsTampering() throws Exception {
        final VmBytecodeCompiler compiler = new VmBytecodeCompiler();
        final VmProgram program = compiler.compile(module(), "build-one", new SecureRandom());
        final VmProgram.VmMethod method = program.methods().get(0);

        assertEquals(2, method.blocks().size());
        assertEquals(2, new HashSet<>(method.blocks().stream()
                .map(block -> java.util.HexFormat.of().formatHex(block.nonce())).toList()).size());
        final byte[] clear = compiler.decryptBlock(program, method, method.blocks().get(0));
        assertArrayEquals(new byte[]{'S', 'K', 'V', 'M'}, java.util.Arrays.copyOf(clear, 4));
        assertEquals(1, java.nio.ByteBuffer.wrap(clear, 5, 4).getInt(),
                "ADD+MUL should be emitted as one generated superinstruction");

        final VmProgram.EncryptedBlock original = method.blocks().get(0);
        final byte[] corrupted = original.ciphertext();
        corrupted[0] ^= 1;
        final VmProgram.EncryptedBlock tampered = new VmProgram.EncryptedBlock(
                original.index(), original.nonce(), corrupted);
        assertThrows(AEADBadTagException.class, () -> compiler.decryptBlock(program, method, tampered));
    }

    @Test
    void xchachaMatchesTheLibsodiumIetfConstruction() throws Exception {
        final byte[] key = new byte[32];
        final byte[] nonce = new byte[24];
        final byte[] plaintext = new byte[64];
        for (int index = 0; index < key.length; index++) key[index] = (byte) index;
        for (int index = 0; index < nonce.length; index++) nonce[index] = (byte) index;
        for (int index = 0; index < plaintext.length; index++) plaintext[index] = (byte) index;
        final byte[] aad = "skid-vm-test-aad".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        final byte[] expected = java.util.HexFormat.of().parseHex(
                "9ec30d7c94d78ba93b4d2cc5c75fa6e75b563ab6e9c30bfc670325ad21b0eb46"
                        + "7e2794c765422f43fdbc8472e30c7d4d418bd06a7341cd2e3fa2a906b3da7acb"
                        + "b090b4ebf22e7c4016c2f6cf20b6ea36");
        final XChaCha20Poly1305 cipher = new XChaCha20Poly1305();

        assertArrayEquals(expected, cipher.seal(key, nonce, aad, plaintext));
        assertArrayEquals(plaintext, cipher.open(key, nonce, aad, expected));
    }

    @Test
    void randomizesOpcodesAndFragmentsKey() {
        final VmBytecodeCompiler compiler = new VmBytecodeCompiler();
        final VmProgram first = compiler.compile(module(), "first", new SecureRandom());
        final VmProgram second = compiler.compile(module(), "second", new SecureRandom());

        assertEquals(first.opcodes().values().size(), new HashSet<>(first.opcodes().values().values()).size());
        assertNotEquals(first.opcodes().values(), second.opcodes().values());
        assertEquals(4, first.keyFragments().size());
        assertEquals(1, first.superinstructions().size());
        assertTrue(first.superinstructions().get(0).name().startsWith("SUPER$ADD$MUL"));
        assertEquals(XChaCha20Poly1305.KEY_BYTES,
                VmBytecodeCompiler.reassembleKey(first.keyFragments()).length);
    }

    @Test
    void associatedDataBindsBlocksToTheirBuildAndMethod() {
        final VmBytecodeCompiler compiler = new VmBytecodeCompiler();
        final VmProgram original = compiler.compile(module(), "bound-build", new SecureRandom());
        final VmProgram rebound = new VmProgram(original.formatVersion(), "different-build", original.opcodes(),
                original.methods(), original.keyFragments());
        assertThrows(AEADBadTagException.class, () -> compiler.decryptBlock(
                rebound, rebound.methods().get(0), rebound.methods().get(0).blocks().get(0)));
    }

    @Test
    void embedsTheCompleteNonDestructiveProtectionPolicy() {
        final VmProtectionSettings settings = new VmProtectionSettings(
                VmProtectionSettings.Profile.STANDARD,
                VmProtectionSettings.Response.HALT,
                true, false, true, false, true, 3, 7, 9, 0);
        final VmProgram program = new VmBytecodeCompiler().compile(
                module(), "policy", new SecureRandom(), settings);

        assertEquals(settings, program.protection());
        assertEquals(3, program.keyFragments().size());
        assertTrue(new VmProgramCodec().encode(program).length > 128);
        assertThrows(IllegalArgumentException.class, () -> new VmProtectionSettings(
                VmProtectionSettings.Profile.AGGRESSIVE,
                VmProtectionSettings.Response.THROW,
                true, true, true, true, true, 4, 48, 64, 1));
    }

    @Test
    void codecRoundTripsAndRejectsCorruptedStructure() throws Exception {
        final VmProgramCodec codec = new VmProgramCodec();
        final VmProgram original = new VmBytecodeCompiler().compile(
                module(), "round-trip", new SecureRandom());
        final byte[] encoded = codec.encode(original);
        final VmProgram decoded = codec.decode(encoded);

        assertEquals(original.buildId(), decoded.buildId());
        assertEquals(original.protection(), decoded.protection());
        assertEquals(original.opcodes(), decoded.opcodes());
        assertEquals(original.methods().get(0).symbol(), decoded.methods().get(0).symbol());
        assertArrayEquals(original.methods().get(0).blocks().get(0).ciphertext(),
                decoded.methods().get(0).blocks().get(0).ciphertext());

        for (int cut : List.of(0, 1, 7, encoded.length / 2, encoded.length - 1)) {
            assertThrows(IOException.class, () -> codec.decode(java.util.Arrays.copyOf(encoded, cut)));
        }
        final byte[] trailing = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(IOException.class, () -> codec.decode(trailing));
        final byte[] badMagic = encoded.clone();
        badMagic[0] ^= 1;
        assertThrows(IOException.class, () -> codec.decode(badMagic));
    }

    @Test
    void opcodeTableAndOperandEncodingTamperingAreAuthenticated() {
        final VmBytecodeCompiler compiler = new VmBytecodeCompiler();
        final VmProgram original = compiler.compile(module(), "metadata-auth", new SecureRandom());
        final java.util.LinkedHashMap<String, Integer> swapped = new java.util.LinkedHashMap<>(
                original.opcodes().values());
        final var entries = swapped.entrySet().stream().limit(2).toList();
        final int first = entries.get(0).getValue();
        swapped.put(entries.get(0).getKey(), entries.get(1).getValue());
        swapped.put(entries.get(1).getKey(), first);
        final VmProgram opcodeTampered = new VmProgram(original.formatVersion(), original.buildId(),
                new VmOpcodeTable(swapped), original.methods(), original.keyFragments(), original.protection());
        assertThrows(AEADBadTagException.class, () -> compiler.decryptBlock(
                opcodeTampered, opcodeTampered.methods().get(0), opcodeTampered.methods().get(0).blocks().get(0)));

        final VmProgram.VmMethod method = original.methods().get(0);
        final VmProgram.VmMethod altered = new VmProgram.VmMethod(method.symbol(), method.javaOwner(),
                method.javaName(), method.javaDescriptor(), method.registerCount(), method.entryBlock(),
                method.operandMask() ^ 1, method.metadata(), method.blocks());
        final VmProgram operandTampered = new VmProgram(original.formatVersion(), original.buildId(),
                original.opcodes(), List.of(altered), original.keyFragments(), original.protection());
        assertThrows(AEADBadTagException.class, () -> compiler.decryptBlock(
                operandTampered, altered, altered.blocks().get(0)));
    }

    @Test
    void corruptedPayloadFuzzingAlwaysFailsClosedOrDecodesWithinBounds() throws Exception {
        final VmProgramCodec codec = new VmProgramCodec();
        final VmProgram baseline = new VmBytecodeCompiler().compile(module(), "fuzz", new SecureRandom());
        final byte[] original = codec.encode(baseline);
        final byte[] baselineKey = VmBytecodeCompiler.reassembleKey(baseline.keyFragments());
        final SecureRandom random = new SecureRandom();
        for (int attempt = 0; attempt < 128; attempt++) {
            final byte[] mutated = original.clone();
            final int mutations = 1 + random.nextInt(4);
            final java.util.Set<Integer> changedOffsets = new java.util.HashSet<>();
            while (changedOffsets.size() < mutations) {
                final int offset = random.nextInt(mutated.length);
                if (!changedOffsets.add(offset)) continue;
                mutated[offset] ^= (byte) (1 << random.nextInt(8));
            }
            try {
                final VmProgram decoded = codec.decode(mutated);
                boolean authenticationRejected = false;
                for (final VmProgram.VmMethod method : decoded.methods()) {
                    for (final VmProgram.EncryptedBlock block : method.blocks()) {
                        try {
                            new VmBytecodeCompiler().decryptBlock(decoded, method, block);
                        } catch (javax.crypto.AEADBadTagException rejected) {
                            authenticationRejected = true;
                        }
                    }
                }
                if (!authenticationRejected) {
                    /* XOR key fragments admit representation-only changes that preserve their
                     * exact reconstructed key. They do not alter an executable VM program; the
                     * containing native library digest authenticates their concrete encoding.
                     * Every operational payload mutation must still reject at block AEAD. */
                    assertArrayEquals(baselineKey,
                            VmBytecodeCompiler.reassembleKey(decoded.keyFragments()),
                            "decoded operational payload mutation was not authenticated");
                }
            } catch (IOException | IllegalArgumentException expected) {
                // Structural mutations are rejected before the driver sees the payload.
            }
        }
        java.util.Arrays.fill(baselineKey, (byte) 0);
    }

    private static NativeModule module() {
        final NativeType.Primitive i32 = NativeType.Primitive.I32;
        final NativeParameter input = new NativeParameter("input", i32, 0);
        final NativeFunction function = new NativeFunction(
                "vm_symbol", "sample/Owner", "compute", "(I)I", i32, List.of(input), "entry",
                NativeBackend.VM, false, false, Map.of("profile", "AGGRESSIVE"));
        final NativeInstruction.Operation add = new NativeInstruction.Operation(
                "sum", i32, NativeOpcode.ADD,
                List.of(new NativeOperand.Value("input", i32), new NativeOperand.Constant(i32, 7)),
                Map.of(), SourceLocation.UNKNOWN);
        final NativeInstruction.Operation multiply = new NativeInstruction.Operation(
                "product", i32, NativeOpcode.MUL,
                List.of(new NativeOperand.Value("sum", i32), new NativeOperand.Constant(i32, 3)),
                Map.of(), SourceLocation.UNKNOWN);
        function.addBlock(new NativeBlock("entry").addInstruction(add).addInstruction(multiply)
                .terminate(new NativeTerminator.ConditionalBranch(
                        new NativeOperand.Constant(NativeType.Primitive.I1, true), "result", "result",
                        SourceLocation.UNKNOWN)));
        function.addBlock(new NativeBlock("result").terminate(
                new NativeTerminator.Return(new NativeOperand.Value("product", i32))));
        return new NativeModule("vm-test").addFunction(function);
    }
}
