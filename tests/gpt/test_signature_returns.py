import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SIGNATURE = (
    ROOT
    / "dev.skidfuscator.obfuscator"
    / "src"
    / "main"
    / "java"
    / "dev"
    / "skidfuscator"
    / "obfuscator"
    / "transform"
    / "impl"
    / "signature"
    / "SignatureObfuscationTransformer.java"
)
DEFAULT_CONFIG = ROOT / "dev.skidfuscator.client.standalone" / "skidfuscator-config.conf"
TRANSFORMER_PANEL = (
    ROOT
    / "dev.skidfuscator.client.standalone"
    / "src"
    / "main"
    / "java"
    / "dev"
    / "skidfuscator"
    / "obfuscator"
    / "gui"
    / "TransformerPanel.java"
)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def config_block(config: str, name: str) -> str:
    match = re.search(rf"(?ms)^{re.escape(name)}\s*\{{\s*(.*?)^\}}", config)
    if not match:
        raise AssertionError(f"Missing config block: {name}")
    return match.group(1)


class SignatureReturnObfuscationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.source = read(SIGNATURE)
        cls.config = read(DEFAULT_CONFIG)
        cls.panel = read(TRANSFORMER_PANEL)

    def test_master_switch_and_independent_mode_flags(self) -> None:
        self.assertIn('super(skidfuscator, "Signature Obfuscation");', self.source)
        self.assertIn('return getConfig().getBoolean("enabled", false);', self.source)
        # arguments keeps the legacy behaviour (default true); returns/key opt-in.
        self.assertIn('getConfig().getBoolean("arguments", true)', self.source)
        self.assertIn('getConfig().getBoolean("returns", false)', self.source)
        self.assertIn('getConfig().getBoolean("returnThreadKey", false)', self.source)
        # returnThreadKey only matters when returns is on.
        self.assertIn(
            'obfuscateReturns && getConfig().getBoolean("returnThreadKey", false)',
            self.source,
        )

    def test_default_config_exposes_the_new_block(self) -> None:
        block = config_block(self.config, "signatureObfuscation")
        for expected in (
            "enabled=false",
            "arguments=true",
            "returns=false",
            "returnThreadKey=false",
        ):
            self.assertIn(expected, block.replace(" ", ""))

    def test_gui_panel_wires_the_three_toggles(self) -> None:
        self.assertIn('addSection(host, "signatureObfuscation", "Signature Obfuscation"', self.panel)
        self.assertIn('.key("arguments").label("Pack arguments")', self.panel)
        self.assertIn('.key("returns").label("Wrap returns")', self.panel)
        self.assertIn('.key("returnThreadKey").label("Thread key in return")', self.panel)

    def test_return_type_mapping_primitive_byte_reference_object(self) -> None:
        for expected in (
            "private String mappedReturnDesc(final Type returnType)",
            "return isPrimitive(returnType) ? BYTE_ARRAY_DESC : OBJECT_ARRAY_DESC;",
            "private boolean isWrappableReturn(final Type returnType)",
            "if (returnType.getSort() == Type.VOID)",
            "return !OBJECT_ARRAY_DESC.equals(returnType.getDescriptor());",
        ):
            self.assertIn(expected, self.source)

    def test_descriptor_builder_handles_each_mode(self) -> None:
        for expected in (
            "private String buildNewDesc(final String desc, final boolean rewriteArgs, final boolean rewriteReturn)",
            "rewriteReturn",
            "Type.getType(BYTE_ARRAY_DESC)",
            "Type.getType(OBJECT_ARRAY_DESC)",
            "Type.getMethodDescriptor(newReturn, Type.getArgumentTypes(desc))",
        ):
            self.assertIn(expected, self.source)

    def test_returns_wrapped_into_array_carrier_at_every_exit(self) -> None:
        for expected in (
            "private void emitReturnRewrites(final MethodNode method, final Type returnType,",
            "private boolean isValueReturn(final int opcode)",
            "new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE)",
            'new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object")',
            "emitPrimitivePack(pack, arrayLocal, 0, returnType, valueLocal)",
            "method.instructions.set(ret, new InsnNode(Opcodes.ARETURN));",
        ):
            self.assertIn(expected, self.source)

    def test_threaded_key_is_callee_side_and_caller_seed_is_xored(self) -> None:
        # Key only when hierarchy metadata says interprocedural really threaded the method.
        self.assertIn(
            "threadedSeedMethods.contains(key)",
            self.source,
        )
        # Seed captured at entry and appended (bytes for byte[], boxed for Object[]).
        self.assertIn("emitIntByteStores(pack, arrayLocal, primSize, seedLocal, 4)", self.source)
        self.assertIn(
            'new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",',
            self.source,
        )
        self.assertIn("private int seedParamLocal(final String oldDesc, final boolean isStatic)", self.source)
        # Threaded callers fold the returned key into their own seed before unpacking the value.
        self.assertIn("private InsnList unpackReturnValue(final Type returnType,", self.source)
        self.assertIn("private void emitReturnedKeyFold(final InsnList insns,", self.source)
        self.assertIn("insns.add(new InsnNode(Opcodes.IXOR));", self.source)
        self.assertIn("insns.add(new VarInsnNode(Opcodes.ISTORE, callerSeedLocal));", self.source)
        self.assertIn("emitPrimitiveUnpack(post, arrayLocal, 0, returnType);", self.source)
        self.assertIn("post.add(new InsnNode(Opcodes.ICONST_0));", self.source)

    def test_local_floor_keeps_scratch_above_carrier_parameters(self) -> None:
        # When arguments are packed, scratch must start above the (byte[],Object[])
        # carrier parameters so the prologue does not clobber the Object[] slot.
        self.assertIn("nextScratchLocal = Math.max(nextScratchLocal, base + 2);", self.source)

    def test_callsite_pass_conditionally_packs_and_unpacks(self) -> None:
        for expected in (
            "if (candidate.rewriteArgs) {",
            "method.instructions.insertBefore(methodInsn, packArguments(argumentTypes, isStatic));",
            "methodInsn.desc = candidate.newDesc;",
            "if (candidate.rewriteReturn) {",
            "unpackReturnValue(returnType, candidate.threadKey, callerSeedLocal)",
        ):
            self.assertIn(expected, self.source)
        # Original return type captured BEFORE the descriptor is overwritten.
        ret_capture = self.source.index("final Type returnType = Type.getReturnType(methodInsn.desc);")
        desc_overwrite = self.source.index("methodInsn.desc = candidate.newDesc;")
        self.assertLess(ret_capture, desc_overwrite)


if __name__ == "__main__":
    unittest.main()
