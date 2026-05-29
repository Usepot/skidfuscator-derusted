import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DISPATCH = ROOT / "dev.skidfuscator.obfuscator" / "src" / "main" / "java" / "dev" / "skidfuscator" / "obfuscator" / "transform" / "impl" / "method" / "MethodDispatchTransformer.java"
MERGE = ROOT / "dev.skidfuscator.obfuscator" / "src" / "main" / "java" / "dev" / "skidfuscator" / "obfuscator" / "transform" / "impl" / "method" / "MethodMergeTransformer.java"
SKIDFUSCATOR = ROOT / "dev.skidfuscator.obfuscator" / "src" / "main" / "java" / "dev" / "skidfuscator" / "obfuscator" / "Skidfuscator.java"
DEFAULT_CONFIG = ROOT / "dev.skidfuscator.client.standalone" / "skidfuscator-config.conf"
TRANSFORMER_PANEL = ROOT / "dev.skidfuscator.client.standalone" / "src" / "main" / "java" / "dev" / "skidfuscator" / "obfuscator" / "gui" / "TransformerPanel.java"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def config_block(config: str, name: str) -> str:
    match = re.search(rf"(?ms)^{re.escape(name)}\s*\{{\s*(.*?)^\}}", config)
    if not match:
        raise AssertionError(f"Missing config block: {name}")
    return match.group(1)


class MethodDispatchTransformerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.source = read(DISPATCH)
        cls.config = read(DEFAULT_CONFIG)
        cls.panel = read(TRANSFORMER_PANEL)

    def test_disabled_by_default_and_exposed_with_expected_options(self) -> None:
        self.assertIn('super(skidfuscator, "Method Dispatch");', self.source)
        self.assertIn('return getConfig().getBoolean("enabled", false);', self.source)

        block = config_block(self.config, "methodDispatch")
        for expected in ("enabled=false", "scope=APP_ONLY", "maxPerDispatcher=64"):
            self.assertIn(expected, block)

        self.assertIn('addSection(host, "methodDispatch", "Method Dispatch"', self.panel)
        self.assertIn('.key("scope").label("Scope")', self.panel)
        self.assertIn('.enumValues(Arrays.asList("APP_ONLY", "INCLUDE_LIBRARY", "STATIC_ONLY"))', self.panel)
        self.assertIn('.key("maxPerDispatcher").label("Max targets per dispatcher")', self.panel)

    def test_rewrites_eligible_calls_to_per_class_byte_object_dispatchers(self) -> None:
        for expected in (
            'private static final String DISPATCH_DESC = "([B[Ljava/lang/Object;)Ljava/lang/Object;";',
            'private static final String DISPATCH_PREFIX = "skid$dispatch$";',
            'methodInsn.setOpcode(Opcodes.INVOKESTATIC);',
            'methodInsn.owner = classNode.name;',
            'methodInsn.name = target.dispatcherName;',
            'methodInsn.desc = DISPATCH_DESC;',
            'classNode.methods.addAll(dispatchers);',
        ):
            self.assertIn(expected, self.source)

    def test_dispatcher_uses_hashed_lookup_switch_and_direct_target_invocation(self) -> None:
        for expected in (
            'LOWBIAS_C1 = 0x7feb352d',
            'LOWBIAS_C2 = 0x846ca68b',
            'emitLowbias32(out);',
            'new LookupSwitchInsnNode(defaultLabel, keys, labels)',
            'keys[i] = lowbias32(targets.get(i).sigKey);',
            'out.add(new MethodInsnNode(target.opcode, target.owner, target.name, target.desc, target.itf));',
            'out.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));',
        ):
            self.assertIn(expected, self.source)

    def test_callsite_filtering_avoids_verifier_sensitive_cases(self) -> None:
        for expected in (
            'private enum Scope { APP_ONLY, INCLUDE_LIBRARY, STATIC_ONLY }',
            'return Scope.APP_ONLY;',
            'if ("<init>".equals(method.name))',
            'if ("<init>".equals(methodInsn.name) || "<clinit>".equals(methodInsn.name))',
            'owner.startsWith("[")',
            'isSignaturePolymorphic(owner)',
            '"java/lang/invoke/MethodHandle".equals(owner)',
            '"java/lang/invoke/VarHandle".equals(owner)',
            'if (!isStatic)',
        ):
            self.assertIn(expected, self.source)

    def test_argument_and_return_adaptation_covers_primitive_and_reference_types(self) -> None:
        for expected in (
            'primitiveByteSize(args)',
            'referenceCount(args)',
            'emitPrimitivePack(insns, byteArrayLocal, byteOffset, type, argLocals[i])',
            'emitPrimitiveUnpack(out, byteArrayLocal, byteOffset, arg)',
            'emitBox(out, ret);',
            'emitUnbox(insns, ret);',
            'emitCheckCast(insns, ret);',
            'method.maxLocals = Math.max(method.maxLocals, nextScratchLocal);',
            'method.localVariables = null;',
            'method.signature = null;',
        ):
            self.assertIn(expected, self.source)


class MethodMergeTransformerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.source = read(MERGE)
        cls.config = read(DEFAULT_CONFIG)
        cls.panel = read(TRANSFORMER_PANEL)

    def test_disabled_by_default_with_expected_limits(self) -> None:
        self.assertIn('super(skidfuscator, "Method Merge");', self.source)
        self.assertIn('return getConfig().getBoolean("enabled", false);', self.source)

        block = config_block(self.config, "methodMerge")
        for expected in ("enabled=false", "maxPerHost=16", "maxHostInsns=20000"):
            self.assertIn(expected, block)

        self.assertIn('addSection(host, "methodMerge", "Method Merge"', self.panel)
        self.assertIn('.key("maxPerHost").label("Max methods per host")', self.panel)
        self.assertIn('.defaultValue(16)', self.panel)
        self.assertIn('.key("maxHostInsns").label("Max host instructions")', self.panel)
        self.assertIn('.defaultValue(20000)', self.panel)

    def test_integer_options_support_large_defaults(self) -> None:
        self.assertIn('double max = Math.max(100d, v);', self.panel)
        self.assertIn('new SpinnerNumberModel(v, 0d, max, 1d)', self.panel)

    def test_candidate_collection_only_accepts_threaded_safe_app_methods(self) -> None:
        for expected in (
            'if (!group.isInjectedMethodPredicate())',
            'group.getMethodNodeList() == null || group.getMethodNodeList().size() != 1',
            'group.isAnnotation() || group.isEnumerator() || group.isMixin() || group.isNatived()',
            'if (!appClasses.containsKey(ownerName))',
            'if ((ownerRaw.access & Opcodes.ACC_INTERFACE) != 0)',
            '"<init>".equals(name) || "<clinit>".equals(name) || "main".equals(name)',
            'if (args.length == 0 || args[args.length - 1].getSort() != Type.INT)',
            'if (handleReferences.contains(key))',
            'overridable && (isOverriddenInHierarchy(key) || overridesExternalContract(appClasses, key, raw))',
        ):
            self.assertIn(expected, self.source)

    def test_hosts_are_built_from_compatible_buckets_and_keep_seed_dispatch(self) -> None:
        for expected in (
            'private static final String HOST_PREFIX = "skid$merge$";',
            'final String groupKey = candidate.ownerName + \'|\' + candidate.returnDesc + \'|\' + candidate.isStatic;',
            'Math.max(2, getConfig().getInt("maxPerHost", 16))',
            'Math.max(2000, getConfig().getInt("maxHostInsns", 20000))',
            'if (bucket.size() < 2)',
            'if (seenSeeds.add(member.publicSeed))',
            'final String hostDesc = "(" + BYTE_ARRAY_DESC + OBJECT_ARRAY_DESC + "I)" + head.returnDesc;',
            'out.add(new VarInsnNode(Opcodes.ILOAD, seedLocal));',
            'out.add(new LookupSwitchInsnNode(defaultLabel, keys, labels));',
            'out.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));',
        ):
            self.assertIn(expected, self.source)

    def test_callsites_are_rewritten_before_members_are_removed(self) -> None:
        order = [
            'rewriteCallsites(targets);',
            'buildHostMethod(host);',
            'host.ownerRaw.methods.add(host.node);',
            'host.ownerRaw.methods.remove(member.raw);',
        ]
        positions = [self.source.index(item) for item in order]
        self.assertEqual(positions, sorted(positions))

        for expected in (
            'final int seedScratch = nextScratchLocal++;',
            'replacement.add(new VarInsnNode(Opcodes.ISTORE, seedScratch));',
            'replacement.add(packArguments(realArgs, host.isStatic));',
            'replacement.add(new VarInsnNode(Opcodes.ILOAD, seedScratch));',
            'methodInsn.setOpcode(host.isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL);',
            'methodInsn.owner = host.ownerName;',
            'methodInsn.name = host.hostName;',
            'methodInsn.desc = host.hostDesc;',
        ):
            self.assertIn(expected, self.source)

    def test_cloned_bodies_drop_stale_debug_frames_and_shift_locals(self) -> None:
        for expected in (
            'if (insn instanceof FrameNode || insn instanceof LineNumberNode)',
            'insn.clone(labelMap)',
            '((VarInsnNode) cloned).var += bodyBase;',
            '((IincInsnNode) cloned).var += bodyBase;',
            'new TryCatchBlockNode(',
            'node.maxLocals = bodyBase + maxBodyLocals + 4;',
            'node.maxStack = Math.max(maxBodyStack, 8) + 4;',
        ):
            self.assertIn(expected, self.source)


class TransformerRegistrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.skidfuscator = read(SKIDFUSCATOR)

    def test_late_transformer_registration_order_is_preserved(self) -> None:
        for expected in (
            'import dev.skidfuscator.obfuscator.transform.impl.method.MethodDispatchTransformer;',
            'import dev.skidfuscator.obfuscator.transform.impl.method.MethodMergeTransformer;',
            'new MethodMergeTransformer(this)',
            'new MethodDispatchTransformer(this)',
            'LOGGER.post("Running late pass [Method Merge]...");',
            'LOGGER.post("Running late pass [Method Dispatch]...");',
        ):
            self.assertIn(expected, self.skidfuscator)

        merge = self.skidfuscator.index('final MethodMergeTransformer methodMerge')
        signature = self.skidfuscator.index('final SignatureObfuscationTransformer signatureObfuscation')
        dispatch = self.skidfuscator.index('final MethodDispatchTransformer methodDispatch')
        indy = self.skidfuscator.index('final InvokeDynamicMethodTransformer methodCallObfuscation')
        self.assertLess(merge, signature)
        self.assertLess(signature, dispatch)
        self.assertLess(dispatch, indy)

    def test_both_transformers_share_expected_app_class_safety_filters(self) -> None:
        for path in (DISPATCH, MERGE):
            with self.subTest(transformer=path.name):
                source = read(path)
                self.assertIn('loadApplicationClasses()', source)
                self.assertIn('loadAllClasses()', source)
                self.assertIn('isNativeSensitiveClass', source)
                self.assertIn('getExemptAnalysis().isExempt', source)
                self.assertIn('skidfuscator.getJarContents().getClassContents()', source)


if __name__ == "__main__":
    unittest.main()
