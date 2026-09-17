# Native AOT and VM protection

Native protection is opt-in. `native.enabled = false` is the default, and the
native pipeline does not select methods, resolve a toolchain, or change the
output lifecycle while it remains disabled.

## Architecture and trust boundary

```text
MapleIR -> typed Skid Native IR -> canonical LLVM IR
                                     |-> AOT native code
                                     `-> encrypted register-VM program
```

This repository owns annotation/configuration handling, candidate selection,
MapleIR lowering, native-IR verification and emission, authenticated toolchain
resolution, method/wrapper mutation, the Java loader, and JAR packaging. The
separate SkidLLVM repository owns LLVM passes, target code generation, JNI
trampolines and semantic runtime, native VM interpreter, linking, export
control, self-hosting, and signed host archives.

The Java side never searches `PATH` for a compiler or native library. An
external installation is a directory containing
`skidllvm-toolchain.manifest` and `bin/skidllvm` (`bin/skidllvm.exe` on
Windows). The manifest, detached signature stored in it, version, target set,
native-IR ABI, and installed driver digest are verified before execution. The
release contract is currently SkidLLVM `1.0.0-alpha.1`, toolchain manifest
schema `1`, compiler manifest schema `1`, and native-IR ABI `1`.

## Selection and mutation

Selection uses this precedence:

1. `native.exempt`
2. Explicit `@NativeObfuscation(mode = AOT|VM)`
3. Last matching `native.rules` entry
4. Default-mode annotation or `native.include`
5. Leave the method as Java bytecode

An unsupported explicit annotation fails the build. Matcher-selected methods
that cannot be lowered are reported and kept as Java bytecode. Candidates are
reserved before structural transforms and revalidated against the finalized
MapleIR graph. Final class, method, field, descriptor, exception, and dynamic
linkage identities are remapped before native registration data is emitted.

Directly eligible methods become `ACC_NATIVE` only after every requested
library and the compiler manifest have passed validation and the loader and
resources have been staged. Constructors keep their proven initialization
prefix and call a synthetic native tail helper. Class initializers and concrete
interface methods remain Java wrappers. Interface helpers are placed in a
generated non-interface companion. Commit is transactional: staging or late
mutation failure restores code, access flags, annotations, initializers,
companions, and resources.

## Loading and artifacts

The generated Java 8 loader normalizes these targets:

- `windows-x86_64`, `windows-aarch64`
- `linux-x86_64`, `linux-aarch64`
- `macos-x86_64`, `macos-aarch64`

Libraries live below
`META-INF/skidfuscator/native/<build-id>/<target>/`. The loader reads only the
canonical embedded path, enforces authenticated manifest and artifact sizes,
verifies SHA-256 before extraction, uses a class-loader-specific temporary
directory, rejects symlink substitution, uses an atomic move and restrictive
permissions, and calls `System.load` with an absolute path. It does not use
`PATH` or `java.library.path`.

The requested output remains the universal JAR. `artifacts = BOTH` additionally
writes one deterministic JAR per target to the configured native artifact
directory; each contains ordinary resources, relevant build manifests, and
only libraries for that target. Native mode rejects APK/DEX output.

## Implemented and locally tested

The Java-side implementation currently includes:

- Compatible annotation, HOCON model, CLI/session overrides, and Gradle plugin
  inputs plus a declared platform-artifact output directory
- Strict/warn selection behavior and transform reservation gates
- Typed SSA/CFG native IR, verifier, source locations, exception edges,
  monitors, Java calls, fields, arrays, allocation, casts, dynamic linkage,
  and constructor-tail analysis
- Final-name remapping for registration, semantic members, descriptors,
  exception types, bootstrap handles, method-type constants, and
  constant-dynamic payloads
- Authenticated `AUTO`, `BUNDLED`, `DOWNLOAD`, `EXTERNAL`, and `DISABLED`
  toolchain resolution with bounded I/O and process execution
- Transactional multi-method/multi-target compilation installation, Java 8
  loader generation, wrapper/direct commits, and deterministic universal plus
  six-target platform JAR packaging
- Structural PE, ELF, and Mach-O validation requiring `JNI_OnLoad` to be the
  sole exported symbol
- A randomized typed register-VM payload encoder with per-method operand masks,
  per-build opcode maps, handler-aligned key fragmentation, generated
  superinstructions, independent XChaCha20-Poly1305 blocks, and authenticated
  method/opcode/policy metadata
- Bounded VM payload decoding, XChaCha verification against a libsodium vector,
  structural mutation fuzzing, ciphertext/opcode/operand tamper tests, and a
  pure encrypted-VM differential harness covering integer edges and randomized
  inputs
- Artifact, loader, manifest/library tamper, rollback, annotation removal,
  constructor/interface wrapper, remapping, and all-six-target filtering tests

These tests establish Java-side invariants. They do **not** establish that the
current native runtime is production-ready.

## Release and runtime gates still open

The following work or external evidence is still required before a native
release can be called complete:

- Finish and validate canonical LLVM emission for every accepted numeric,
  conversion, exception, synchronized, and Java-semantic Native IR path
- Implement real JNI dispatch and class-loader-correct class/member resolution
  in the native semantic runtime, including local/global reference ownership,
  GC safety, pending-exception routing, monitors, dynamic bridges, and caller-
  sensitive behavior
- Complete the native typed register interpreter and trampolines, including
  recursion, reentrancy, bounded decoded-block caching, handler cloning,
  diversified dispatch, superinstruction execution, and configured runtime
  checks without recursively virtualizing the central dispatch loop
- Prove all native hardening passes preserve JNI registration, unwinding, crash
  reporting, semantic helpers, and VM execution; complete the three-stage
  self-host build and reproducibility/smoke gates
- Pin an actual SkidLLVM release public key and publish signed Windows, Linux,
  and macOS host archives. This checkout intentionally contains no placeholder
  trust root, so `DOWNLOAD` and signed runtime acceptance cannot pass yet
- Run original/AOT/VM differential suites on all six targets under Java 8, 17,
  21, and the newest supported JVM, including arrays, fields, inheritance,
  interfaces, dynamic calls/constants, lambdas, constructors, class
  initialization, synchronization, recursion, exceptions/finally, GC pressure,
  reflection, concurrency, and multiple class loaders
- Tamper real linked libraries, registration tables, VM blocks, and opcode
  tables and verify deterministic configured fail-closed reactions on every
  target
- Produce AOT and VM benchmark results. The `1.25x` AOT computational target
  and `4x` median / `8x` p95 VM targets have not been demonstrated
- Verify release binaries expose only `JNI_OnLoad`, retain private symbol
  archives, survive LLVM/Clang/LLD smoke suites, and compile representative
  protected projects on every host

`.github/workflows/native-runtime.yml` is a manual, fail-closed acceptance
matrix for both backends, six targets, and Java 8/17/21/current. It refuses to
use an unauthenticated or fake compiler. It is intentionally red until a public
release key and matching signed SkidLLVM archives exist. The portable Java 8
runtime harness in `scripts/native-acceptance/NativeRuntimeAcceptance.java`
compares exit codes, stdout, stderr, and secret visibility, then scans every
decompressed protected-JAR entry for the fixture secret. Its separate Java 21
performance matrix runs a computational fixture on every target and enforces
the configured AOT and VM median/p95 limits; no result is recorded until real
signed libraries compile and execute. Its explicitly annotated Java 8 semantic
fixture exercises integer/floating edges, NaN, objects, fields, arrays,
inheritance/interface dispatch, lambdas, method handles, exceptions/finally,
constructor tails, synchronization, recursion, GC pressure, reflection,
concurrency, and independent class loaders. Unsupported annotated lowering
therefore fails the job instead of shrinking coverage silently.

The workflow records one platform/runtime exception explicitly: maintained
Temurin Java 8 binaries are not available for Windows ARM64. That target is
still compiled and executed on Java 17, 21, and current, while Java 8 Windows
ARM64 remains an external runtime-availability gap rather than being emulated
or silently counted as a pass.

## Threat model

The objective is to raise static-analysis resistance and dynamic-analysis cost,
not to promise permanent secrecy against an attacker controlling the running
process. Runtime responses are restricted to `THROW`, `HALT`, and
`DELAYED_HALT`. Native protection does not introduce destructive filesystem,
network, or process actions.
