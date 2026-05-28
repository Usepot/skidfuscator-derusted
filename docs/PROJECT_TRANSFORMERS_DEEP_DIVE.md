# Skidfuscator Java Obfuscator: Project and Transformer Deep Dive

This document describes how this repository is structured, how an obfuscation run flows through the system, and how each transformer works individually. It is based on the local source tree under `dev.skidfuscator.obfuscator`, `dev.skidfuscator.commons`, `dev.skidfuscator.sdk`, the Gradle build files, and the transformer implementations present in this checkout.

## 1. High-level purpose

Skidfuscator is a Java bytecode obfuscator built around ASM and MapleIR. The important design idea is that most transformations do not directly edit raw JVM instructions. Instead, the project imports classes into MapleIR, constructs control-flow graphs, adds Skidfuscator-specific metadata such as opaque predicates, applies event-driven transformers, dumps the modified CFGs back into bytecode, then optionally runs late ASM-only passes before writing the output archive.

In simplified form:

```text
input jar/apk/dex
  -> import config, exemptions, libraries, JDK classes
  -> build application class source
  -> build MapleIR cache and analysis context
  -> build hierarchy and method groups
  -> initialize predicate analysis
  -> register enabled transformers on EventBus
  -> run Init / Pre / Transform / Post / Final phases
  -> dump MapleIR CFGs back to ASM instructions
  -> run late raw ASM transformers
  -> remap and write output archive
```

The obfuscator is not just a renamer. Most of the interesting work revolves around hidden integer predicates. Class, method, and block predicates are threaded through real code and then used to rewrite constants, branch guards, switch keys, string decryptors, and fake exception routes. The goal is to make static reasoning hard because the generated code often depends on values that are known to the obfuscator but are reconstructed through runtime-looking expressions.

## 2. Repository/module map

The Gradle root is `skidfuscator-master`. The repository is multi-module and targets Java 17 at compile time, with a downgrade task available in the obfuscator module for Java 8 output compatibility.

Important modules:

| Module | Role |
| --- | --- |
| `dev.skidfuscator.obfuscator` | Core obfuscation engine, event system, Skid ASM wrappers, predicates, transformers, verification, dumping, dependency/JDK import, SDK injection. |
| `dev.skidfuscator.commons` | Shared config wrappers, default config/exemption defaults, and common utilities used outside the core module. |
| `dev.skidfuscator.sdk` | Runtime helper jar injected into protected applications when SDK-backed transforms are enabled. Used for string/type/hash helper calls. |
| `dev.skidfuscator.client.standalone` | Standalone CLI/GUI launcher layer. |
| `dev.skidfuscator.gradle-plugin` | Gradle plugin integration. |
| `dev.skidfuscator.obfuscator.pureanalysis` | Predicate/pure analysis support used by the obfuscator. |
| `dev.skidfuscator.obfuscator.dependanalysis` | Dependency analysis support. |
| `org.mapleir.parent` and submodules | Vendored MapleIR/ASM service stack: CFGs, IR, flowgraph, app-services, bytecode service layers. |
| `dev.xdark.ssvm` | Embedded SSVM runtime used by `VmHashTransformer` to evaluate selected real methods during obfuscation. |
| `docs` | Existing diagrams/assets. This document lives here. |

The core obfuscator module depends on the commons and pure-analysis modules, embeds the SDK jar as `resources/sdk.jar`, uses `jphantom` for phantom classes, imports JAR/APK/DEX inputs, and contains the transformer implementations.

## 3. Core runtime concepts

### `Skidfuscator`

`dev.skidfuscator.obfuscator.Skidfuscator` is the main coordinator. It owns the session, imported jar contents, class source, MapleIR cache, hierarchy, analysis context, exemption manager, config, remapper, dependency downloader, and hash transformers.

During construction, it sets up `SimplePredicateAnalysis` with four renderer families:

- integer block predicates,
- integer method predicates,
- integer class predicates,
- integer static class predicates.

Those predicates are the backbone for later flow, number, string, and hash obfuscation.

### Class/method/block wrappers

The obfuscator wraps MapleIR/ASM nodes in Skid-specific classes:

- `SkidClassNode` wraps a class and exposes class/static predicates, generated fields, generated methods, class initializer helpers, random class seed data, and remapping helpers.
- `SkidMethodNode` wraps a method, CFG, method group, method predicate, flow predicate, block predicate lookup, constructor/static checks, and dump/recompute behavior.
- `SkidBlock` wraps a CFG basic block and carries Skid-specific flags/exemptions such as `FLAG_NO_OPAQUE`, `FLAG_NO_EXCEPT`, `FLAG_PROXY`, and block seeds.
- `SkidGroup` represents a method family/group in the hierarchy. Interprocedural obfuscation uses groups to inject a hidden int parameter across methods and call sites.

### EventBus phases

Transformers are listeners. A transformer method annotated with `@Listen` receives one or more event types. `Skidfuscator.run(phaseName, caller)` fires a phase event in this order:

1. Skid-level event.
2. Class-level events for hierarchy classes.
3. Group-level events for hierarchy method groups.
4. Method-level events for sorted methods in each class.

The major phases are:

| Phase | Event family | What usually happens |
| --- | --- | --- |
| Init | `InitSkidTransformEvent`, `InitClassTransformEvent`, `InitGroupTransformEvent`, `InitMethodTransformEvent` | Seed predicates, inject SDK/helper classes, mutate method descriptors/groups, normalize CFGs. |
| Pre | `Pre...TransformEvent` | Available for pre-run preparation. |
| Transform | `Run...TransformEvent` | Main CFG/expression/control-flow transformations. |
| Post | `Post...TransformEvent` | Late IR-level transformations such as number constant rewriting and generator post-work. |
| Final | `Final...TransformEvent` | Final class-level IR changes, e.g. Ahegao payload field insertion. |

After every method-level event, CFG edges are recomputed. After all phases, CFGs are dumped back to raw bytecode. Two important transformers, `SignatureObfuscationTransformer` and `InvokeDynamicMethodTransformer`, run after this dump because they work directly on ASM instruction lists.

### Config and exemptions

Every normal transformer extends `AbstractTransformer`. Its config path is generated from the display name using camelCase. For example:

- `Number Encryption` -> `numberEncryption.enabled`
- `Flow Exception` -> `flowException.enabled`
- `String Encryption` -> `stringEncryption.enabled`
- `SDK` -> `sdk.enabled`

`DefaultTransformerConfig.isEnabled()` defaults to `true` unless a transformer overrides it. Some transformers call `requiresSdk()`, which means they only enable when their own config is enabled and global `sdk.enabled` is also true.

`AbstractTransformer.shouldSkipMethod()` skips abstract methods and skips constructors unless `constructorObfuscation.enabled` is true. Many transformers also skip huge methods, null CFGs, flagged blocks, class initializers, or methods/classes marked exempt by the exemption system.

## 4. Default transformer registration order

`Skidfuscator.getTransformers()` builds the default EventBus transformer list. In this checkout, the default list is:

1. `RandomInitTransformer`
2. `InterproceduralTransformer`
3. `NumberTransformer` (`transform.impl.number.NumberTransformer`)
4. `SwitchTransformer`
5. `BasicConditionTransformer`
6. `BasicExceptionTransformer`
7. `BasicRangeTransformer`
8. `PureHashTransformer`
9. `SdkInjectorTransformer`
10. `StringEqualsHashTransformer`
11. `StringEqualsIgnoreCaseHashTransformer`
12. `InstanceOfHashTransformer`
13. `AhegaoTransformer`
14. `StringTransformerV2` when `stringEncryption.type` is `STANDARD` or when the type path falls through to the default branch.

`LoopConditionTransformer` is present but commented out in the default list. `DriverTransformer`, `BasicSimplifierTransformer`, `NegationTransformer`, legacy `StringTransformer`, and both `ObjectDefinalizer` copies are implemented but not part of the default list shown in `Skidfuscator.getTransformers()`.

The late raw ASM passes are constructed manually after CFG dump:

1. `SignatureObfuscationTransformer.apply()` when `signatureObfuscation.enabled=true`.
2. `InvokeDynamicMethodTransformer.apply()` when `methodCallObfuscation.enabled=true`.

Both of these override `isEnabled()` to default to `false`.

## 5. Transformer framework classes

### `Transformer`

Path: `dev.skidfuscator.obfuscator.transform.Transformer`

This is the common interface. It extends the EventBus `Listener` interface and requires:

- `getName()` for display/config naming,
- `getConfig()` for transformer-specific config,
- `getChildren()` for child transformers,
- `register()` to register listeners,
- `getResult()` for final status output,
- `isEnabled()` for config gating.

### `AbstractTransformer`

Path: `dev.skidfuscator.obfuscator.transform.AbstractTransformer`

This base class implements common behavior:

- Stores `Skidfuscator`, name, config, child transformers, and success/skipped/failed counters.
- Creates `DefaultTransformerConfig` using the camelCase display name.
- Registers itself and all child transformers on the EventBus.
- Supports `requiresSdk()`, which gates the transformer behind `sdk.enabled`.
- Provides `shouldSkipMethod()`, constructor-obfuscation checks, and a probabilistic large-method skip heuristic.
- Produces final result strings with success, skipped, and failed counts.

### `AbstractExpressionTransformer`

Path: `dev.skidfuscator.obfuscator.transform.AbstractExpressionTransformer`

This base class is used by expression-level replacements. It listens to `RunMethodTransformEvent` at `LOWEST` priority and then:

1. Skips abstract methods, constructors when constructor obfuscation is off, null CFGs, class initializers, and methods over 10,000 bytecode instructions.
2. Iterates a copied set of CFG vertices and statements to avoid concurrent modification problems.
3. Skips blocks flagged `SkidBlock.FLAG_NO_OPAQUE`.
4. Calls `matchesExpression(expr)` for each child expression.
5. Calls `transformExpression(expr, cfg)` when matched.
6. Increments success per transformed expression.

`StringEqualsHashTransformer`, `StringEqualsIgnoreCaseHashTransformer`, and `InstanceOfHashTransformer` extend this class.

## 6. Number and hash infrastructure

These classes are not all EventBus transformers, but several are named transformers and they are essential to how the real transformers work.

### `NumberManager`

Path: `dev.skidfuscator.obfuscator.number.NumberManager`

`NumberManager` is the central selector for number encryption and hash helpers.

Current state in this checkout:

- `TRANSFORMERS` contains only `XorNumberTransformer`.
- `DebugNumberTransformer` and `RandomShiftNumberTransformer` are present but commented out.
- `HASHER` is empty/commented out.
- `encrypt(...)` randomly selects from `TRANSFORMERS`; because only XOR is active, all calls currently use XOR encoding.
- `randomHasher(...)` returns `skidfuscator.getLegacyHasher()` instead of picking from `HASHER`.

That means switch/range flows using `NumberManager.randomHasher()` use the legacy generated hash method, while places calling `skidfuscator.getVmHasher()` use the VM-backed hash transformer.

### `XorNumberTransformer`

Path: `dev.skidfuscator.obfuscator.number.encrypt.impl.XorNumberTransformer`

This helper encodes an integer outcome with a known starting predicate. It returns an IR expression equivalent to:

```text
(outcome ^ starting) ^ runtimeStartingExpression
```

When `runtimeStartingExpression` evaluates to the same predicate value, the expression evaluates back to `outcome`. This is used heavily for numeric constants, class/static predicate initialization, type-check seeds, string seed threading, and fake failure-block state mutation.

### `RandomShiftNumberTransformer`

Path: `dev.skidfuscator.obfuscator.number.encrypt.impl.RandomShiftNumberTransformer`

This is an alternate numeric encoder. It starts from the runtime predicate expression, applies zero to three random operations from shift-right, shift-left, and bitwise-and, alternating between the original predicate and random constants. It then XORs the final mutated seed against the requested outcome.

It is not currently active in `NumberManager.TRANSFORMERS`, but it is intended to create more varied arithmetic encodings than plain XOR.

### `DebugNumberTransformer`

Path: `dev.skidfuscator.obfuscator.number.encrypt.impl.DebugNumberTransformer`

This helper ignores the predicate and returns the raw constant. It exists for debugging/testing and is not active in `NumberManager`.

### `LegacyHashTransformer`

Path: `dev.skidfuscator.obfuscator.number.hash.impl.LegacyHashTransformer`

This helper creates a phantom public static method in the factory class with descriptor `(I)I`. The generated method returns:

```text
0, if input == 0
(((input * 31) >>> 4) % input) ^ (input >>> 16), otherwise
```

The transformer can compute the same value at obfuscation time and emit a static invocation expression at runtime. This is the current `NumberManager.randomHasher()` result.

### `BitwiseHashTransformer`

Path: `dev.skidfuscator.obfuscator.number.hash.impl.BitwiseHashTransformer`

This helper creates a phantom public static method returning:

```text
((input & (7 << 29)) >> 29) | (input << 3)
```

It can also emit a static invocation to that generated method. It is implemented but not selected by the current `NumberManager` defaults.

### `IntelliJHashTransformer`

Path: `dev.skidfuscator.obfuscator.number.hash.impl.IntelliJHashTransformer`

This is a simple inline hash helper:

```text
input ^ (input >>> 16)
```

Unlike `LegacyHashTransformer` and `BitwiseHashTransformer`, it does not create a generated helper method; it emits the arithmetic expression directly. It is implemented but not selected by current defaults.

### `SkidHashTransformer`

Path: `dev.skidfuscator.obfuscator.number.hash.impl.SkidHashTransformer`

This class is a work-in-progress hash transformer. It contains a design for random mutating bitwise operations, but the mutating-operation creation path is unfinished. Its current concrete behavior falls back to a simple XOR/unsigned-shift hash in `hash(int)`, while `hash(expr)` emits a related XOR expression with a shift. It should be treated as incomplete infrastructure rather than an active production path.

### `VmHashTransformer`

Path: `dev.skidfuscator.obfuscator.number.pure.VmHashTransformer`

This is one of the most unusual components in the project. It implements `HashTransformer`, but instead of using a fixed arithmetic function, it embeds SSVM and finds real public static methods from the available runtime/application/library classes that can be used as hash functions.

The process is:

1. Start an embedded `VirtualMachine` with custom boot class finder and file manager.
2. Stub or patch problematic JDK module/native calls so the VM can bootstrap.
3. Install a class supplier that loads classes either from the current runtime or from the obfuscator's active class source.
4. Scan classes and libraries for candidate methods.
5. Candidate methods must be public, static, non-native, non-abstract, non-constructor, return `int`, accept at least one `int` parameter, and not accept object/array parameters.
6. Skip sensitive/internal package prefixes such as `sun/`, `com/sun/`, `jdk/internal/`, `jdk/vm`, `com/oracle/`, and `com/ibm/`.
7. Load/filter candidates inside SSVM.
8. Pick one candidate method and one int parameter as the predicate parameter.
9. Generate fixed random primitive values for all other parameters.
10. At obfuscation time, compute the expected hash by invoking the selected method in SSVM.
11. At runtime, emit a static invocation to the same selected method with the same fixed constants and a runtime predicate expression.

`hash(int starting, BasicBlock vertex, PredicateFlowGetter caller)` computes the expected constant and the matching runtime expression, then rotates to a new randomly selected candidate for the next hash.

This allows control-flow guards to depend on real methods rather than obvious generated hash routines.

## 7. Active EventBus transformers

### `RandomInitTransformer`

Path: `transform.impl.flow.interprocedural.RandomInitTransformer`

Name/config path: `Interprocedural Harden` -> `interproceduralHarden.*`

Default registration: active.

Event: `InitClassTransformEvent`

This transformer prepares per-class opaque predicate storage before later method and flow transformers use it.

Detailed behavior:

1. Reads the class instance predicate and static predicate from the `SkidClassNode`.
2. Skips interfaces, annotations, and enums.
3. Creates a private static integer field with a random name.
4. Ensures the class initializer exists.
5. Generates a random long seed, creates a `java.util.Random(seed)`, calls `nextInt()`, and stores that into a local in `<clinit>`.
6. Stores the static class predicate into the new static field, but encoded through `XorNumberTransformer` using the random `nextInt()` result.
7. Replaces the static predicate getter and setter so later code loads/stores this generated field.
8. Creates a private transient instance integer field.
9. Computes `instancePredicate ^ staticPredicate` as the field's initial value.
10. Replaces the instance predicate getter and setter so later code loads/stores the generated instance field on `this`.
11. Inserts code into every constructor to initialize the instance field as `storedValue ^ staticPredicate`.

Purpose:

- Move class/static/instance predicate values out of simple constants.
- Make later method predicates depend on fields initialized through runtime-looking code.
- Ensure constructors and class initializers establish the predicate state needed by later flow guards.

### `InterproceduralTransformer`

Path: `transform.impl.flow.interprocedural.InterproceduralTransformer`

Name/config path: `Interprocedural` -> `interprocedural.*`

Default registration: active.

Events: `InitGroupTransformEvent`, `InitMethodTransformEvent`

This transformer threads hidden integer predicates through method calls.

Group initialization behavior:

1. Processes a `SkidGroup`, which represents a method family in the hierarchy.
2. Entry-point groups and static groups when `threadStaticMethods=false` are not given the hidden argument; the transformer only computes and stores stack-height metadata.
3. For other groups, computes an insertion index for the new hidden `int` parameter. Dynamic invocation groups have special descriptor handling.
4. Mutates the group descriptor to include the new `int` parameter.
5. For each method in the group:
   - Computes the stack height where the new parameter will live.
   - Shifts existing local indexes at or above that location by one slot.
   - Clears the `ACC_VARARGS` flag because descriptor mutation is incompatible with varargs metadata.
   - Handles hierarchy naming collisions when a descriptor mutation would collide with an existing group.
6. For each call site/invoker:
   - Skips non-IR, null, or already-tainted invocation records.
   - Appends a `SkidConstantExpr` containing the group's public predicate.
   - Marks the invoker as tainted so it is not rewritten twice.
   - For dynamic invocations, also patches the bootstrap method handle descriptor and invokedynamic descriptor.
7. Marks the group as having an injected method predicate.

Method initialization behavior:

1. Installs a getter for the method opaque predicate.
2. For normal methods, the seed comes from either the class static predicate or the instance predicate depending on staticness.
3. For constructors and class initializers, it uses the class random int through `SkidIntegerParseStaticInvocationExpr`, because the class/instance field state may not be safely available yet.
4. If the method group received the hidden int parameter, the seed is XORed with the group's public predicate, and the runtime expression becomes `hiddenParameter ^ previousClassPredicateExpression`.
5. The final method predicate getter returns an `XorNumberTransformer` expression that reconstructs the method's private predicate.

Purpose:

- Tie method-level opaque predicates to call-site data.
- Make branch/string/number predicates harder to evaluate without understanding the transformed call graph.
- Allow later block predicates to be derived from a method predicate whose value is not simply stored in the method body.

### `NumberTransformer`

Path: `transform.impl.number.NumberTransformer`

Name/config path: `Number Encryption` -> `numberEncryption.*`

Default registration: active.

Event: `PostMethodTransformEvent` at `LOW` priority

This transformer rewrites integer-like constants after the main method transformation pass.

Detailed behavior:

1. Skips abstract methods, constructors when constructor obfuscation is disabled, methods over 10,000 instructions, and null CFGs.
2. Iterates CFG blocks, skipping blocks flagged `FLAG_NO_OPAQUE`.
3. In class initializers, applies the large-method heuristic to avoid overgrowth.
4. Scans statement child expressions for `SkidConstantExpr` values of type `int`, `short`, `byte`, or `char`.
5. For each matching constant:
   - Reads the current block's opaque predicate.
   - Computes the encoded value using XOR.
   - Replaces the constant expression with `XorNumberTransformer.getNumber(original, predicate, block, flowPredicateGetter)`.
6. Optional debug mode can insert string trace locals showing original and encoded constants.

At runtime, the replacement evaluates to the original constant only if the block flow predicate expression is correct. This makes constants depend on the control-flow predicate system.

### `SwitchTransformer`

Path: `transform.impl.SwitchTransformer`

Name/config path: `Flow Switch` -> `flowSwitch.*`

Default registration: active.

Event: `RunMethodTransformEvent`

This transformer obfuscates switch keys and switch dispatch expressions.

Detailed behavior:

1. Skips methods according to common rules, methods over 10,000 instructions, and null CFGs.
2. Finds all `SkidSwitchStmt` / `SwitchStmt` nodes in the CFG.
3. Reads the block opaque predicate for the switch block.
4. Rebuilds the switch target map:
   - Original case key `k` becomes `legacyHasher.hash(k ^ blockPredicate)`.
   - The target block remains unchanged.
5. Stores the original switch expression XORed with the runtime block predicate into a new local.
6. Replaces the switch expression with a hash expression over that local.

The static case table no longer contains the original source-level switch labels. At runtime, if the predicate is correct, `hash(originalSwitchValue ^ predicate)` matches the rewritten case key.

### `BasicConditionTransformer`

Path: `transform.impl.flow.condition.BasicConditionTransformer`

Name/config path: `Flow Condition` -> `flowCondition.*`

Default registration: active.

Event: `RunMethodTransformEvent`

This transformer inserts a hashed guard block into real conditional true branches.

Detailed behavior:

1. Skips methods according to common rules and null CFGs.
2. Iterates copied CFG vertices.
3. Skips empty blocks, blocks flagged `FLAG_NO_OPAQUE`, and blocks selected by the large-method heuristic.
4. Finds a real `ConditionalJumpStmt` at the end of a block, excluding `FakeConditionalJumpStmt`.
5. Finds the CFG conditional edge corresponding to the true successor.
6. Creates a new `SkidBlock` between the original conditional and the original true target.
7. Uses `skidfuscator.getVmHasher()` to build a hash expression and expected hash constant for the new block's predicate.
8. Adds a condition in the bridge block: if the hash equals the expected value, jump to the original target.
9. Adds an unconditional jump from the bridge to the CFG failure block for the false path.
10. Replaces the original conditional edge and true successor to point to the new bridge.

The original branch still behaves correctly, but it now passes through an opaque predicate check. Static analysis must account for the VM-hashed predicate to prove the branch target.

### `BasicExceptionTransformer`

Path: `transform.impl.flow.exception.BasicExceptionTransformer`

Name/config path: `Flow Exception` -> `flowException.*`

Default registration: active.

Event: `RunMethodTransformEvent`

Config:

- `flowException.strength = WEAK | GOOD | AGGRESSIVE`
- default strength: `GOOD`

This transformer injects fake failure routes and exception-oriented confusion based on block predicates.

Detailed behavior:

1. Skips abstract methods, null CFGs, and constructors when constructor obfuscation is disabled.
2. Chooses an insertion strategy from config:
   - `WEAK`: probabilistic skip strategy.
   - `GOOD`: normal one-pass strategy.
   - `AGGRESSIVE`: more frequent insertion strategy.
3. Iterates copied `SkidBlock` instances.
4. Skips empty blocks and blocks exempt from exception/opaque work.
5. Applies the large-method heuristic.
6. Uses the selected strategy to decide whether to transform the block.
7. Gets the CFG failure block, sometimes creating an additional failure block for double-hit paths.
8. Computes a VM-backed hash for the block predicate.
9. Mutates either the static or instance class predicate in the failure block using an encrypted random integer. This makes the failure path actively poison future predicate state.
10. Inserts a `FakeConditionalJumpStmt` into the real block: if the runtime hash does not equal the expected hash, jump to the failure block.
11. Adds confusing self-conditions inside the failure block.
12. Optionally inserts debug exception statements when predicate debug mode is enabled.
13. Recomputes edges and ticks the event.

The added check should never fail during normal execution. If a deobfuscator removes or changes predicate state incorrectly, execution can fall into the poisoned failure path.

### `BasicRangeTransformer`

Path: `transform.impl.flow.BasicRangeTransformer`

Name/config path: `Flow Range` -> `flowRange.*`

Default registration: active.

Event: `RunMethodTransformEvent` at `LOW` priority

This transformer rewrites unconditional jumps into exception-range-mediated control flow.

For each eligible unconditional jump from block A to block B:

1. Create a throw dispatcher block that always throws a random exception type.
2. Remove the original direct CFG edge.
3. Create a throw bridge block.
4. In the bridge, insert a fake condition based on a hashed block predicate. Under correct predicate state it routes to the throw dispatcher.
5. Add a `throw null` path in the bridge to confuse decompilers and control-flow simplifiers.
6. Create a target bridge handler block.
7. In the target bridge, pop the caught exception and jump to the original target block B.
8. Repoint the original unconditional jump to the throw bridge instead of B.
9. Create an `ExceptionRange` covering the throw bridge and throw dispatcher, with the target bridge as handler.
10. Add the needed `TryCatchEdge` entries and range metadata.

The original logic still reaches B, but it does so through a try/catch edge instead of a plain goto. This is hostile to decompilers that expect structured, direct jumps.

### `PureHashTransformer`

Path: `transform.impl.pure.PureHashTransformer`

Name/config path: `Pure Encryption` -> `pureEncryption.*`

Default registration: active.

Event: `InitSkidTransformEvent` at `HIGHEST` priority

This transformer initializes the VM-backed hash system by replacing the current VM hasher with a new `VmHashTransformer`.

Important source detail: the code currently checks `if (skidfuscator.getVmHasher() == null) throw new IllegalStateException("VmHasher is null")` before assigning the new VM hasher. That condition appears counterintuitive because it throws when the field is null. The documentation here describes the implementation as-is rather than assuming intent.

Purpose:

- Provide the `skidfuscator.getVmHasher()` used by flow condition and exception transformers.
- Enable hashed predicates based on selected real methods evaluated through SSVM.

### `SdkInjectorTransformer`

Path: `transform.impl.sdk.SdkInjectorTransformer`

Name/config path: `SDK` -> `sdk.*`

Default registration: active.

Event: `InitSkidTransformEvent`

This transformer injects the runtime SDK into the output and class source.

Detailed behavior:

1. Ensures the Skidfuscator cache directory exists.
2. Extracts `resources/sdk.jar` from the obfuscator jar into the cache.
3. Imports that SDK jar through `MapleJarUtil.importPhantomJar`.
4. Adds all SDK class contents into the output jar contents.
5. Creates a `SkidApplicationClassSource` for the SDK and adds it as a library class source.

SDK injection is required for transforms that emit calls such as:

- `sdk/SDK.hash(String)`
- `sdk/SDK.checkType(Object, String, int)`

### `StringEqualsHashTransformer`

Path: `transform.impl.hash.StringEqualsHashTransformer`

Name/config path: `String Equals Hash` -> `stringEqualsHash.*`

Default registration: active, but gated by `requiresSdk()`.

Base class: `AbstractExpressionTransformer`

This transformer rewrites string equality against constants.

Detailed behavior:

1. Matches `java/lang/String.equals(Ljava/lang/Object;)Z` invocation expressions.
2. Requires exactly one side of the comparison to be a constant string.
3. Replaces the constant string with the decimal string form of `LongHashFunction.xx3().hashChars(constant)`.
4. Replaces the non-constant side with `sdk/SDK.hash(nonConstantString)`.
5. Leaves the equality call structure in place, but it now compares hashes as strings.

Example mental model:

```text
input.equals("admin")
```

becomes approximately:

```text
SDK.hash(input).equals("<xx3 hash of admin>")
```

### `StringEqualsIgnoreCaseHashTransformer`

Path: `transform.impl.hash.StringEqualsIgnoreCaseHashTransformer`

Name/config path: `String Eq Ig Case Hash` -> `stringEqIgCaseHash.*`

Default registration: active, but gated by `requiresSdk()`.

Base class: `AbstractExpressionTransformer`

This transformer is the ignore-case variant of string equality hashing.

Detailed behavior:

1. Matches `java/lang/String.equalsIgnoreCase(Ljava/lang/String;)Z` invocation expressions.
2. Requires exactly one side to be a constant string.
3. Lowercases the constant at obfuscation time, hashes it with `LongHashFunction.xx3()`, and stores the decimal hash string.
4. Replaces the dynamic side with `SDK.hash(dynamic.toLowerCase())`.

The runtime comparison still gives ignore-case semantics, but the original constant is not present as a plain string.

### `InstanceOfHashTransformer`

Path: `transform.impl.hash.InstanceOfHashTransformer`

Name/config path: `Type Check` -> `typeCheck.*`

Default registration: active, but gated by `requiresSdk()`.

Base class: `AbstractExpressionTransformer`

This transformer hides object type checks.

Detailed behavior:

1. Matches `InstanceofExpr` expressions.
2. Skips primitive and array checks.
3. Uses a random transformer-level integer seed.
4. Replaces `object instanceof SomeType` with:

```text
sdk/SDK.checkType(object, hashedTypeName, encryptedSeed)
```

Where:

- `hashedTypeName` is `LongHashFunction.xx3(seed).hashChars(typeInternalName)` as a string.
- `encryptedSeed` is produced by `NumberManager.encrypt(seed, blockPredicate, block, flowPredicateGetter)`.

The runtime SDK can reconstruct or validate the type relationship without exposing the raw internal type name directly at the check site.

### `AhegaoTransformer`

Path: `transform.impl.misc.AhegaoTransformer`

Name/config path: `Ahegao` -> `ahegao.*`

Default registration: active.

Event: `FinalClassTransformEvent`

This is a trolling/noise transformer rather than a semantic protection transformer.

Detailed behavior:

1. Skips enums, interfaces, and annotations.
2. Picks a static private string-array field name, defaulting to `nothing_to_see_here` and altering it if the name already exists.
3. Creates the private static `String[]` field.
4. Chooses one bundled ASCII-art string array payload.
5. Ensures the class initializer has a CFG entry/return when needed.
6. Inserts statements into `<clinit>` to allocate the array and store every string element into it.

Purpose:

- Add harmless static noise to classes.
- Waste analyst/decompiler attention.
- Increase output clutter without changing business logic.

### `StringTransformerV2`

Path: `transform.impl.string.StringTransformerV2`

Name/config path: `String Encryption` -> `stringEncryption.*`

Default registration: active for the current `STANDARD` string encryption branch.

Events: `RunMethodTransformEvent`, `PostSkidTransformEvent`

This is the current standard string encryption transformer.

Run-method behavior:

1. Skips methods according to common rules, methods over 10,000 instructions, and null CFGs.
2. Uses one `EncryptionGeneratorV3` per class.
3. On first string transform in a class, randomly selects one generator:
   - `BytesV3EncryptionGenerator`
   - `BytesClinitV3EncryptionGenerator`
   - `ByteBufferClinitV3EncryptionGenerator`
4. Calls `generator.visitPre(class)` once per class to inject decryptor methods/fields.
5. Scans all CFG expressions for non-exempt `SkidConstantExpr` string constants.
6. Skips constructor string constants inside `FLAG_NO_OPAQUE` blocks.
7. Replaces each string constant with the expression returned by `generator.encrypt(string, method, block)`.

Post-skid behavior:

- Calls `generator.visitPost(class)` for each class/generator pair. The ByteBuffer generator uses this to write its pooled string buffer into `<clinit>` after all strings have been collected.

The key design change from the legacy string transformer is that V3 generators produce full replacement expressions directly and can inject annotated helper fields/methods into each target class.

## 8. String encryption generator internals

These are not EventBus transformers, but they are part of the string transformer behavior.

### `EncryptionGeneratorV3`

Path: `transform.impl.string.generator.EncryptionGeneratorV3`

This interface defines:

- `encrypt(String, SkidMethodNode, SkidBlock)` -> replacement expression,
- `decrypt(DecryptorDictionary, int)` -> Java-side test helper,
- `visitPre(SkidClassNode)` -> inject setup,
- `visitPost(SkidClassNode)` -> optional finalization.

It also defines `@InjectMethod` and `@InjectField` annotations. V3 generators annotate their own helper members, and the abstract base copies those helpers into the target class.

### `AbstractEncryptionGeneratorV3`

Path: `transform.impl.string.generator.v3.AbstractEncryptionGeneratorV3`

This class handles helper injection:

1. Reflects over annotated fields in the generator class.
2. Copies those fields into the target `SkidClassNode`.
3. Applies random names, `final`, and interface-compatibility tags.
4. Reflects over annotated methods in the generator class.
5. Copies method bytecode into the target class as public static phantom methods.
6. Remaps copied method and field owner references from the generator class to the target class.
7. Remaps randomly renamed fields/methods inside copied instructions.
8. Recomputes CFGs for injected methods.
9. Provides helper calls for injected methods/fields.
10. Provides array-generator helpers that create private static methods returning byte/int arrays.

It also threads string decryption keys through the existing predicate system:

- `getThreadedStringSeed(method, block)` returns the block predicate, XORed with group public predicate when interprocedural hidden parameters are active.
- `getThreadedStringSeedExpr(method, block)` returns the matching runtime expression using the flow predicate getter and hidden group local when needed.

### `BytesV3EncryptionGenerator`

Path: `transform.impl.string.generator.v3.BytesV3EncryptionGenerator`

This generator stores the random key byte array through a generated array-returning method.

Encryption:

1. Encode plaintext as UTF-16 bytes.
2. Convert the threaded integer seed to decimal string bytes.
3. XOR every plaintext byte with a cyclic seed byte.
4. XOR again with a cyclic random class key byte.
5. Generate a byte-array expression containing encrypted bytes.
6. Emit a call to the injected decryptor with `(encryptedBytes, keyBytes, runtimeSeed)`.

Decryption reverses the same XOR operations and constructs a UTF-16 string.

### `BytesClinitV3EncryptionGenerator`

Path: `transform.impl.string.generator.v3.BytesClinitV3EncryptionGenerator`

This generator uses the same UTF-16 + seed XOR + random key XOR algorithm as `BytesV3EncryptionGenerator`, but it stores the random key byte array in an injected static field initialized in `<clinit>`.

The replacement expression calls a decryptor with `(encryptedBytes, runtimeSeed)`, and the decryptor reads the static key field.

### `ByteBufferClinitV3EncryptionGenerator`

Path: `transform.impl.string.generator.v3.ByteBufferClinitV3EncryptionGenerator`

This generator pools all encrypted strings for a class into one string buffer.

Encryption:

1. Encode plaintext as UTF-16BE bytes.
2. XOR the bytes with the threaded seed bytes.
3. Convert the encrypted bytes back into a UTF-16BE string segment.
4. Append the segment to an internal `StringBuilder` buffer.
5. Encode the segment length and offset into an 8-byte index array.
6. Replace the original string with a decryptor call `(indexBytes, runtimeSeed)`.

Post visit:

- Converts the accumulated buffer to bytes, wraps it in a `ByteBuffer`, turns it into a `CharBuffer`, converts that to a string, and stores it in an injected static field in `<clinit>`.

Runtime decryptor:

- Reads length/offset from the 8-byte index.
- Extracts the encrypted substring from the static buffer.
- XORs it with the runtime seed bytes.
- Returns the original UTF-16BE string.

### `VirtualizedStringEncryptionGenerator`

Path: `transform.impl.string.generator.v3.VirtualizedStringEncryptionGenerator`

This generator exists but is not selected by the current `StringTransformerV2` switch. It is a more complex experimental generator.

It creates:

- random key bytes,
- a shuffled byte map,
- randomized VM opcode mapping,
- injected static fields for keys/map/opcode table,
- an injected bytecode interpreter-style decrypt method.

Encryption emits encrypted data plus a generated bytecode program. Runtime decryption runs the program in the injected mini-VM, with stack, instruction pointer, opcode decoding, anti-debug checks for `jdwp`, integrity-style checks, XOR, reverse shuffle, and array load/store operations.

Because selection is commented out in `StringTransformerV2`, it should be treated as available infrastructure rather than a default active backend.

## 9. Late raw ASM transformers

These are not EventBus listeners in the normal phase pipeline. `Skidfuscator.run()` manually invokes them after MapleIR CFGs are dumped back to bytecode and before final jar dumping.

### `SignatureObfuscationTransformer`

Path: `transform.impl.signature.SignatureObfuscationTransformer`

Name/config path: `Signature Obfuscation` -> `signatureObfuscation.*`

Default enabled: false.

Invocation: manual `apply()` after CFG dump.

This transformer hides internal method parameter descriptors by repacking parameters into two arrays.

Original method descriptor:

```text
(arg1, arg2, ..., argN)ReturnType
```

New descriptor:

```text
([B, [Ljava/lang/Object;)ReturnType
```

Primitive arguments are packed into the byte array. Reference and array arguments are packed into the object array.

Candidate filtering:

A method is rejected when any of these are true:

- It has no internal app call sites.
- It is referenced by a method handle or invokedynamic bootstrap argument.
- It is a constructor or class initializer.
- It is `main(String[])V`.
- It is native, synthetic, bridge, or abstract.
- It has zero arguments.
- The obfuscated descriptor would equal the original descriptor.
- It is an overridable method with an in-jar override family.
- It overrides an external library/interface/Object contract.
- Descriptor collision would occur after transformation.

Call-site rewrite behavior:

1. For every app-to-app call to a selected method, it stores original arguments into scratch locals.
2. Allocates a primitive byte array sized to the exact primitive storage needed.
3. Allocates an object array sized to the reference argument count.
4. Stores primitive values in big-endian byte representation.
5. Stores object/array values into the object array.
6. Reloads receiver if the call is non-static.
7. Pushes the two arrays and updates the call descriptor.

Method rewrite behavior:

1. Inserts an unpacking prologue at method start.
2. Copies byte/object array arguments into scratch locals.
3. Reconstructs original primitive values from bytes.
4. Casts object array entries back to the original reference/array types.
5. Stores reconstructed values into the original local slots.
6. Replaces the method descriptor with `([B[Ljava/lang/Object;)ReturnType`.
7. Clears signature, parameter, parameter annotation, and local-variable metadata.

This is a conservative internal ABI transformer. It avoids external contracts and virtual-dispatch cases that could break semantics.

### `InvokeDynamicMethodTransformer`

Path: `transform.impl.method.InvokeDynamicMethodTransformer`

Name/config path: `Method Call Obfuscation` -> `methodCallObfuscation.*`

Default enabled: false.

Invocation: manual `apply()` after CFG dump.

This transformer replaces eligible method calls with `invokedynamic` call sites.

Eligible call opcodes:

- `INVOKESTATIC`
- `INVOKEVIRTUAL`
- `INVOKEINTERFACE`
- `INVOKESPECIAL`, except constructors

It skips:

- native-sensitive classes such as `org/jnativehook/` and `com/sun/jna/`,
- exempt classes/methods,
- abstract/native methods,
- calls that cannot be resolved in the application/library class map,
- ineligible invocation forms.

For each class with changed calls:

1. Generate a private static synthetic bootstrap method with a random name.
2. Generate a private static synthetic string decrypt method with a random name.
3. Generate a random key array.
4. Replace method calls with `InvokeDynamicInsnNode`.
5. Encrypt the target method name and owner binary name into hex-like `skid$...` strings.
6. Store the original opcode, encrypted owner, original method type, and integer key as bootstrap arguments.
7. Raise class version to at least Java 7 if needed.

Bootstrap behavior:

1. Decrypt target method name and owner binary name.
2. Load the target owner through the caller lookup class loader.
3. Resolve the correct `MethodHandle` using `findStatic`, `findVirtual`, or `findSpecial` based on the original opcode.
4. Adapt the handle to the invokedynamic call-site type using `asType`.
5. Return a `ConstantCallSite`.

For non-static calls, the generated call-site descriptor uses `Object` as the first receiver type to avoid leaking the target owner in the descriptor. The bootstrap converts it back through `MethodHandle.asType`.

The decryptor removes the `skid$` prefix, hex-decodes bytes, XORs them with key-derived bytes and the random class key array, and returns the plaintext name.

## 10. Implemented but not default transformers

### `BasicSimplifierTransformer`

Path: `transform.impl.flow.BasicSimplifierTransformer`

Name/config path: `Block Simplifier` -> `blockSimplifier.*`

Default registration: not in `getTransformers()`.

Event: `InitMethodTransformEvent` at `MONITOR` priority

This transformer normalizes CFG immediate edges into explicit unconditional jumps.

Detailed behavior:

1. Ensures the method entry block exists.
2. Iterates CFG blocks.
3. For each block with an immediate successor edge, removes that immediate edge.
4. Creates a new `UnconditionalJumpEdge` from the block to the immediate successor.
5. Appends an `UnconditionalJumpStmt` to the block.
6. Recomputes edges.
7. Asserts no immediate edges remain.

It looks like a preparatory normalizer that can make later flow transforms easier by making implicit fallthrough explicit.

### `DriverTransformer`

Path: `transform.impl.flow.driver.DriverTransformer`

Name/config path: `Driver` -> `driver.*`

Default enabled: false in `DriverConfig`.

Default registration: not in the default transformer list shown by `Skidfuscator.getTransformers()`.

Events: `InitSkidTransformEvent`, `RunMethodTransformEvent`, `PostSkidTransformEvent`

This is an alternative/older method-predicate driver system.

Init behavior:

1. Loads a predicate cache class from `CacheTemplateDump.dump()`.
2. Renames it to `skid/Driver` internally.
3. Rewrites internal method/field owner references inside the template.
4. Adds it to the class source and output jar contents.
5. Adds a remapper entry so `skid/Driver` maps to the configured `driver.path` value.
6. Finds the template `init` method and stores its entry block as a mutable insertion point.

Run-method behavior:

1. Skips inner/private/nest-sensitive classes.
2. Replaces the method predicate getter with one that can fetch class/static predicate state or driver-cached values.
3. For class initializers, stores random strings and seeds into the driver cache by inserting calls to `skid/Driver.add(String, int)`.
4. For class initializers, predicate reads can call `skid/Driver.get(String)`.
5. For constructors, it can derive the seed from the static class predicate.
6. For normal methods, it uses static or instance class predicates.
7. For non-entry method groups, it XORs in the hidden group predicate parameter.
8. Returns an `XorNumberTransformer` expression for the method private predicate.

Post-skid behavior:

- Dumps the generated predicate driver method.

Purpose:

- Centralize predicate seed caching into a generated driver class.
- Provide a configurable path for that generated driver.

### `LoopConditionTransformer`

Path: `transform.impl.loop.LoopConditionTransformer`

Name/config path: `Loop Condition` -> `loopCondition.*`

Default registration: present in source but commented out in `Skidfuscator.getTransformers()`.

Event: `RunMethodTransformEvent`

This transformer attempts to rewrite integer conditional jumps.

Detailed behavior:

1. Skips null CFGs.
2. Iterates all blocks/statements.
3. Finds `ConditionalJumpStmt` nodes.
4. Only handles cases where both sides are `int` typed.
5. Creates a random small multiplier and random XOR mask.
6. Replaces the left side with `((left ^ mask) * multiplier) % right`.
7. Leaves the right side unchanged.

This implementation is narrow and potentially risky because preserving arbitrary comparison semantics with modulo is not generally valid. It appears experimental and is not active by default.

### `NegationTransformer`

Path: `transform.impl.NegationTransformer`

Name/config path: `Negation` -> `negation.*`

Default registration: not in `getTransformers()`.

Event: `RunMethodTransformEvent`

This transformer rewrites integer-like constants through negation expressions.

Detailed behavior:

1. Skips abstract methods, constructors, methods over 10,000 instructions, and null CFGs.
2. Scans statements for `ConstantExpr` values of type `int`, `short`, `byte`, or `char`.
3. If the constant is negative, replaces it with `NegationExpr(abs(constant))`.
4. If the constant is non-negative, replaces it with double negation: `NegationExpr(NegationExpr(constant))`.
5. Optional debug mode can insert trace strings.

Purpose:

- Add small arithmetic expression noise around numeric constants.
- Make raw constants less directly visible in decompiled output.

### `ObjectDefinalizer`

Paths:

- `transform.impl.misc.ObjectDefinalizer`
- `transform.impl.string.ObjectDefinalizer`

Name/config path: `Object Definalizer` -> `objectDefinalizer.*`

Default registration: not in `getTransformers()`.

Event: `RunMethodTransformEvent`

There are two very similar copies of this class. The comment says the intended idea is to remove primitive final field values and initialize them in `<clinit>` instead, because inline final constants can leak values or interfere with string/constant obfuscation.

Current implementation state:

1. Skips abstract methods, constructors, methods over 10,000 instructions, and null CFGs.
2. Creates an empty `InsnList`.
3. Contains TODO comments about using ASM and recomputing CFGs.
4. Does not currently perform the definalization logic.

This should be documented as a stub/incomplete transformer.

### Legacy `StringTransformer`

Path: `transform.impl.string.StringTransformer`

Name/config path: `String Encryption` -> `stringEncryption.*`

Default registration: superseded by `StringTransformerV2` in the current default path.

Event: `RunMethodTransformEvent`

This is the older string encryption implementation.

Detailed behavior:

1. Maintains one legacy `EncryptionGenerator` per class.
2. Randomly selects one of:
   - `AESEncryptionGenerator`,
   - `StringPoolerEncryptionGenerator`,
   - `BytesEncryptionGenerator`.
3. Injects a decrypt method once per class using the generator's `visit(...)` method.
4. Scans `SkidConstantExpr` string constants similarly to V2.
5. Encrypts each constant into a byte array using the block predicate integer as the key.
6. Creates a private static generated method returning that encrypted byte array.
7. Replaces the string constant with a static call to the decrypt method: `decrypt(generatedByteArray(), flowPredicateExpr)`.

Legacy generator notes:

- `BytesEncryptionGenerator` XORs UTF-16 bytes with decimal key bytes and random key bytes.
- `BasicEncryptionGenerator` performs a similar XOR strategy on platform-default string bytes.
- `AESEncryptionGenerator` uses AES/CBC/PKCS5Padding with a seed-derived key and fixed IV value supplied at construction.
- `StringPoolerEncryptionGenerator` pools encrypted UTF-16BE segments in a static storage field and returns length/offset index bytes.
- `CaesarEncryptionGenerator` rotates byte positions based on key modulo length.
- `IvEncryptionGenerator` is present but has an empty `visit(...)`, so it is not a complete drop-in generator in the same sense as the others.

## 11. Helper/abstract transformer-like classes

### `AbstractExceptionTransformer`

Path: `transform.impl.flow.exception.AbstractExceptionTransformer`

This is a thin abstract subclass of `AbstractTransformer` that sets the default name `Flow Exception` and provides a constructor accepting custom names/children. The active `BasicExceptionTransformer` does not extend this class in the current source; it extends `AbstractTransformer` directly. The class exists as shared exception-transformer infrastructure but currently carries no custom logic.

### `StringEncryptionType`

Path: `transform.impl.string.StringEncryptionType`

This enum currently contains `STANDARD`. `Skidfuscator.getTransformers()` reads `stringEncryption.type` and maps `STANDARD` to `StringTransformerV2`. Unknown/default also falls back to `StringTransformerV2`.

## 12. How transformers interact

The transformations are intentionally interdependent:

1. `RandomInitTransformer` creates class/static/instance predicate storage.
2. `InterproceduralTransformer` threads method predicates through method-group call sites.
3. Method predicates feed block/flow predicates.
4. Flow predicates feed:
   - number constant encryption,
   - string decryptor keys,
   - switch-key rewriting,
   - condition guard hashing,
   - exception/failure checks,
   - type-check seed encryption.
5. `PureHashTransformer` / `VmHashTransformer` make some predicate checks depend on real callable methods evaluated through SSVM.
6. `SdkInjectorTransformer` supplies runtime helper code required by SDK-backed hash/type transforms.
7. `StringTransformerV2` uses the same predicate threading to make each string decrypt correctly only in the right method/block context.
8. Late ASM passes then further hide method signatures and call sites after IR-level transformations are complete.

A typical protected branch or string constant is therefore not isolated. It depends on class initialization, constructor initialization, method-group call-site mutation, block predicate state, and sometimes VM-selected hash methods.

## 13. Important caveats seen in this checkout

- `PureHashTransformer` throws when `getVmHasher()` is null before assigning a new `VmHashTransformer`. That is what the source does; it may be intentional due to earlier initialization not shown here, or it may be a logic bug.
- `NumberManager.HASHER` is empty, but `randomHasher()` bypasses it and returns `legacyHasher`, so code using `NumberManager.hash(...)` directly would be unsafe while code using `randomHasher()` works.
- `SkidHashTransformer` is incomplete/WIP.
- `LoopConditionTransformer` is experimental and not enabled by default.
- Both `ObjectDefinalizer` classes are stubs.
- `DriverTransformer` defaults to disabled and is not in the default transformer list.
- `SignatureObfuscationTransformer` and `InvokeDynamicMethodTransformer` are disabled by default and run outside the EventBus pipeline.
- Several transforms use method-size heuristics to avoid making very large methods worse.
- Some transforms deliberately avoid constructors, class initializers, interfaces, annotations, enums, native-sensitive classes, or exempt regions because those places are fragile.

## 14. Where to start when changing this project

For pipeline behavior, start with:

- `dev.skidfuscator.obfuscator.Skidfuscator`
- `transform/Transformer.java`
- `transform/AbstractTransformer.java`
- `transform/AbstractExpressionTransformer.java`

For predicate-driven flow behavior, start with:

- `RandomInitTransformer`
- `InterproceduralTransformer`
- `BasicConditionTransformer`
- `BasicExceptionTransformer`
- `BasicRangeTransformer`
- `NumberTransformer`
- `VmHashTransformer`

For string behavior, start with:

- `StringTransformerV2`
- `EncryptionGeneratorV3`
- `AbstractEncryptionGeneratorV3`
- the `generator/v3` implementations

For late bytecode-only behavior, start with:

- `SignatureObfuscationTransformer.apply()`
- `InvokeDynamicMethodTransformer.apply()`

The safest way to add or modify a transformer is to keep it EventBus-driven, use `AbstractTransformer` skip/exemption patterns, mutate copied CFG collections when editing, recompute CFG edges after structural changes, and avoid raw ASM instruction edits unless the pass runs after CFG dump or recomputes the method CFG immediately afterward.
