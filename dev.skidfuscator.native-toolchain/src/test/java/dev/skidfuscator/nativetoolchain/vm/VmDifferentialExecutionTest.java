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

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Differentially executes a pure Java function and the independently decoded,
 * encrypted register-VM representation generated from the equivalent Native
 * IR. This deliberately does not pretend to replace the native interpreter
 * acceptance job: it catches encoder, operand masking, branch, opcode
 * randomization, and authenticated-block regressions before SkidLLVM is used.
 */
class VmDifferentialExecutionTest {
    @Test
    void encryptedRegisterProgramMatchesJavaAcrossEdgesAndRandomInputs() throws Exception {
        final VmProtectionSettings settings = new VmProtectionSettings(
                VmProtectionSettings.Profile.STANDARD,
                VmProtectionSettings.Response.THROW,
                true, false, false, false, true,
                2, 0, 8, 0);
        final VmBytecodeCompiler compiler = new VmBytecodeCompiler();
        final VmProgram program = compiler.compile(module(), "differential-vm", seededRandom(), settings);
        final ReferenceVm interpreter = new ReferenceVm(compiler, program, program.methods().get(0));

        final int[] edges = {
                Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -65536, -1, 0, 1,
                65535, Integer.MAX_VALUE - 1, Integer.MAX_VALUE
        };
        for (final int left : edges) {
            for (final int right : edges) {
                assertEquals(javaImplementation(left, right), interpreter.execute(left, right),
                        () -> "differential mismatch for (" + left + ", " + right + ")");
            }
        }

        final SplittableRandom random = new SplittableRandom(0x5A17D1FFL);
        for (int iteration = 0; iteration < 2_000; iteration++) {
            final int left = random.nextInt();
            final int right = random.nextInt();
            assertEquals(javaImplementation(left, right), interpreter.execute(left, right),
                    () -> "differential mismatch for (" + left + ", " + right + ")");
        }
    }

    private static int javaImplementation(final int left, final int right) {
        return left < right ? (left + right) * 3 : (left - right) ^ 0x5A5A;
    }

    private static NativeModule module() {
        final NativeType.Primitive i1 = NativeType.Primitive.I1;
        final NativeType.Primitive i32 = NativeType.Primitive.I32;
        final NativeFunction function = new NativeFunction(
                "differential_symbol", "acceptance/Pure", "compute", "(II)I", i32,
                List.of(new NativeParameter("left", i32, 0), new NativeParameter("right", i32, 1)),
                "entry", NativeBackend.VM, false, true, Map.of("suite", "differential"));
        function.addBlock(new NativeBlock("entry")
                .addInstruction(operation("less", i1, NativeOpcode.ICMP_SLT,
                        value("left", i32), value("right", i32)))
                .terminate(new NativeTerminator.ConditionalBranch(value("less", i1), "less", "greater")));
        function.addBlock(new NativeBlock("less")
                .addInstruction(operation("sum", i32, NativeOpcode.ADD,
                        value("left", i32), value("right", i32)))
                .addInstruction(operation("scaled", i32, NativeOpcode.MUL,
                        value("sum", i32), constant(3)))
                .terminate(new NativeTerminator.Return(value("scaled", i32))));
        function.addBlock(new NativeBlock("greater")
                .addInstruction(operation("difference", i32, NativeOpcode.SUB,
                        value("left", i32), value("right", i32)))
                .addInstruction(operation("mixed", i32, NativeOpcode.BIT_XOR,
                        value("difference", i32), constant(0x5A5A)))
                .terminate(new NativeTerminator.Return(value("mixed", i32))));
        return new NativeModule("vm-differential").addFunction(function);
    }

    private static NativeInstruction.Operation operation(
            final String id,
            final NativeType type,
            final NativeOpcode opcode,
            final NativeOperand... operands
    ) {
        return new NativeInstruction.Operation(
                id, type, opcode, List.of(operands), Map.of(), SourceLocation.UNKNOWN);
    }

    private static NativeOperand.Value value(final String id, final NativeType type) {
        return new NativeOperand.Value(id, type);
    }

    private static NativeOperand.Constant constant(final int value) {
        return new NativeOperand.Constant(NativeType.Primitive.I32, value);
    }

    private static SecureRandom seededRandom() throws Exception {
        final SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
        random.setSeed(new byte[]{0x53, 0x4b, 0x49, 0x44, 0x56, 0x4d});
        return random;
    }

    private static final class ReferenceVm {
        private static final int BLOCK_MAGIC = 0x534B564D;
        private final VmBytecodeCompiler compiler;
        private final VmProgram program;
        private final VmProgram.VmMethod method;
        private final Map<Integer, String> operations;

        private ReferenceVm(
                final VmBytecodeCompiler compiler,
                final VmProgram program,
                final VmProgram.VmMethod method
        ) {
            this.compiler = compiler;
            this.program = program;
            this.method = method;
            this.operations = new HashMap<>();
            program.opcodes().values().forEach((name, opcode) -> operations.put(opcode, name));
        }

        private int execute(final int left, final int right) throws Exception {
            final int[] registers = new int[method.registerCount()];
            registers[0] = left;
            registers[1] = right;
            int blockIndex = method.entryBlock();
            for (int steps = 0; steps < 16; steps++) {
                final VmProgram.EncryptedBlock block = method.blocks().get(blockIndex);
                final byte[] plaintext = compiler.decryptBlock(program, method, block);
                try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(plaintext))) {
                    assertEquals(BLOCK_MAGIC, input.readInt());
                    assertEquals(VmProgram.CURRENT_FORMAT, input.readUnsignedByte());
                    final int instructionCount = input.readInt();
                    for (int index = 0; index < instructionCount; index++) {
                        executeOperation(input, registers);
                    }
                    final int exceptionEdges = input.readUnsignedShort();
                    if (exceptionEdges != 0) {
                        throw new IOException("Pure differential fixture unexpectedly has exception edges");
                    }
                    final String terminator = operation(input.readUnsignedByte());
                    if ("RETURN".equals(terminator)) {
                        if (!input.readBoolean()) throw new IOException("Expected a return value");
                        return readOperand(input, registers);
                    }
                    if ("CBRANCH".equals(terminator)) {
                        final int condition = readOperand(input, registers);
                        final int trueBlock = decodeBlock(input.readInt());
                        final int falseBlock = decodeBlock(input.readInt());
                        blockIndex = condition != 0 ? trueBlock : falseBlock;
                    } else if ("BRANCH".equals(terminator)) {
                        blockIndex = decodeBlock(input.readInt());
                    } else {
                        throw new IOException("Unsupported fixture terminator " + terminator);
                    }
                    if (input.available() != 0) throw new IOException("Trailing bytes in decoded block");
                }
            }
            throw new IOException("VM fixture exceeded its bounded step count");
        }

        private void executeOperation(final DataInputStream input, final int[] registers) throws IOException {
            final String operation = operation(input.readUnsignedByte());
            final int destination = decodeRegister(input.readInt());
            readString(input); // result type
            final int operandCount = input.readUnsignedShort();
            final int[] operands = new int[operandCount];
            for (int index = 0; index < operandCount; index++) operands[index] = readOperand(input, registers);
            final int attributes = input.readUnsignedShort();
            for (int index = 0; index < attributes; index++) {
                readString(input);
                readString(input);
            }
            registers[destination] = switch (operation) {
                case "ADD" -> operands[0] + operands[1];
                case "SUB" -> operands[0] - operands[1];
                case "MUL" -> operands[0] * operands[1];
                case "BIT_XOR" -> operands[0] ^ operands[1];
                case "ICMP_SLT" -> operands[0] < operands[1] ? 1 : 0;
                default -> throw new IOException("Unsupported fixture operation " + operation);
            };
        }

        private int readOperand(final DataInputStream input, final int[] registers) throws IOException {
            final int kind = input.readUnsignedByte();
            if (kind == 0) {
                final int register = decodeRegister(input.readInt());
                readString(input);
                return registers[register];
            }
            if (kind != 1) throw new IOException("Unknown operand kind " + kind);
            readString(input);
            final int constantKind = input.readUnsignedByte();
            if (constantKind != 4) throw new IOException("Expected an i32 constant, got " + constantKind);
            return input.readInt();
        }

        private int decodeRegister(final int encoded) throws IOException {
            final int decoded = encoded ^ method.operandMask();
            if (decoded < 0 || decoded >= method.registerCount()) {
                throw new IOException("Decoded register is outside the method frame");
            }
            return decoded;
        }

        private int decodeBlock(final int encoded) throws IOException {
            final int decoded = encoded ^ Integer.rotateLeft(method.operandMask(), 13);
            if (decoded < 0 || decoded >= method.blocks().size()) {
                throw new IOException("Decoded branch target is outside the method");
            }
            return decoded;
        }

        private String operation(final int opcode) throws IOException {
            final String operation = operations.get(opcode);
            if (operation == null) throw new IOException("Unknown randomized opcode " + opcode);
            return operation;
        }

        private static String readString(final DataInputStream input) throws IOException {
            final int length = input.readInt();
            if (length < 0 || length > 1_048_576) throw new IOException("Invalid VM string length");
            return new String(input.readNBytes(length), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
