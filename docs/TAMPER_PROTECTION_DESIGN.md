# Tamper Protection — Design & Implementation Plan

> Status: **design only** (no code yet). Scope chosen: plan/design doc.
> Granularity: **cross-class mesh** (Option 2) — staged **Tier A (DAG, whole-file hash) → Tier B (cyclic, method-Code region)**.
> Tamper response: **staged — hard-fail first, seed-couple later**.

---

## 1. Goal & threat model

Inject self-verification code into the obfuscated output so that, at runtime, the
protected program detects whether its own bytecode has been modified after release
(jar patched, class swapped, methods NOP'd) and reacts. In a **mesh**, the verifier for
class B lives inside a *different* class A, so patching B trips a checker in A — an
attacker must locate and defeat the whole web at once, not one class at a time.

**In scope (what we detect):**
- Static patching of the shipped jar / class files (the common crack workflow:
  edit a `.class`, re-zip, run).

**Out of scope for v1 (documented, deferred):**
- Runtime instrumentation that leaves the on-disk file intact (`-javaagent`,
  classloader hooks, debugger-driven bytecode swap). `getResourceAsStream` reads
  the *file*, so it cannot see this. Covered by a later **anti-agent / anti-debug**
  milestone.

**Closed-world assumption** (per project convention): the obfuscated jar has no
external callers, so we don't need to preserve any externally-callable contract for
the checker plumbing, and we don't raise reflection/external-caller caveats.

---

## 2. The mesh and its central problem: the stamp cycle

Model the protection as a directed **check-graph**: an edge **A → B** means "class A
verifies class B" — A's bytecode contains `Tamper.verify(B.class, EXPECTED_B_HASH)`,
where `EXPECTED_B_HASH` is a placeholder constant patched to the real value at build
time. `B.class` is a class literal the existing `ClassRemapper` rewrites to B's final
name, so at runtime A reads the correctly-named resource.

**The cycle problem.** To stamp A we need `hash(B)`, which needs B's *final* bytes. But
B's final bytes include B's own check site (with *its* constant already stamped). So:

> **edge A → B ⇒ B must be fully stamped before A.**

How we satisfy that rule splits the mesh into two tiers:

### Tier A — DAG + whole-file hash
Hash the *entire* `.class` of the target. Then the rule above forbids cycles
(A→B→A needs A-before-B and B-before-A). So the check-graph must be a **DAG**, and we
stamp in **reverse-topological order** (targets before their checkers).

- **Runtime:** simple, robust — hash the whole resource. No class-file parsing.
- **Limitation (fundamental):** a finite DAG always has ≥1 node with in-degree 0 — at
  least one class **nobody checks**. Mitigate by making that the lowest-value / a decoy
  class and ensuring the entrypoint/main is a well-checked **sink**, never a source.
  There is always a single structural weak point in Tier A (the source, or a root that
  covers the sources).

### Tier B — cyclic mesh + method-Code region (the real mesh)
Define each class's **hashed region** to *exclude* the bytes that hold expected-hash
constants: hash only the `Code` attribute of a designated **payload method**, and keep
every class's expected-hash constants in a separate **checker method** that is never in
anyone's hashed region. Stamping A then changes only excluded bytes, so:

- the stamp order no longer matters → **true cycles allowed**,
- **every class is checked** → no structural source, no single point.
- **Cost:** `sdk.Tamper` (runtime) and the build-side stamper must each parse class-file
  structure to extract the designated method's `Code` bytes, and agree byte-for-byte.

**Recommended staging:** ship **Tier A first** (proves the graph builder, the
transformer, and the topological stamp end-to-end with a simple runtime), then **upgrade
to Tier B** to close the source gap and enable cycles. The upgrade touches only the
*hashed-region definition* on the two sides that must agree (runtime `Tamper` + build
stamper); the graph/transformer code is unchanged. Hard-fail in both tiers; seed-couple
last.

---

## 3. Architecture (3 pieces)

```
┌──────────────────────────┐    build time     ┌───────────────────────────────┐
│ TamperProtectionTransf.  │  pick a target B   │ A's body gets:                │
│ (@Listen on method/class │  per protected A,  │  Tamper.verify(B.class,       │
│  transform event)        │  inject check +    │     <sentinel long>)          │
│                          │  sentinel LDC      │ and edge A→B added to graph   │
└──────────────────────────┘ ──────────────────▶└───────────────────────────────┘
            │ records edge A→B in IntegrityGraph                 ▲ at runtime A reads
            ▼                                                    │ B's .class bytes,
┌──────────────────────────┐  post-emit (dump)  ┌────────────────┴──────────────┐
│ Stamping pass in          │ serialize (Tier A: │ sdk.Tamper (runtime):         │
│ MapleJarUtil.dumpJar       │ reverse-topo) →    │  read target resource,        │
│ replace sentinel LDC in A  │ hash(B) → write    │  hash (Tier A: whole file /   │
│ with hash(B), then write   │ into A's LDC       │  Tier B: payload method Code),│
│ all entries                │ ──────────────────▶│  compare to inlined expected  │
└──────────────────────────┘                     └───────────────────────────────┘
```

### 3a. Runtime helper — `sdk.Tamper` (in `dev.skidfuscator.sdk`)
New class alongside [`sdk.SDK`](../dev.skidfuscator.sdk/src/main/java/sdk/SDK.java). It:
- reads the **target** class bytes: `targetClass.getResourceAsStream(targetClass.getSimpleName() + ".class")`
  (the `Class<?>` literal is passed in by the checker, so remapping is automatic),
- hashes them — **Tier A:** whole file via `LongHashFunction.xx3()` (already shipped, no
  new dependency); **Tier B:** parse class-file structure, extract the designated payload
  method's `Code` attribute, hash those bytes,
- compares to the `expected` long inlined at the call site,
- **Milestone 1:** on mismatch, hard-fail (throw / `System.exit`).
- **Milestone 3:** return a value folded into the flow seed (see §5).

Shipping is already solved: the `sdk` module is packed into `resources/sdk.jar` by
[`build.gradle`](../dev.skidfuscator.obfuscator/build.gradle) and injected by
[`SdkInjectorTransformer`](../dev.skidfuscator.obfuscator/src/main/java/dev/skidfuscator/obfuscator/transform/impl/sdk/SdkInjectorTransformer.java).
`Tamper` rides along; the transformer must declare `requiresSdk()`.

### 3b. Transformer — `TamperProtectionTransformer extends AbstractTransformer`
Location: `transform/impl/integrity/TamperProtectionTransformer.java` (new package).
- `@Listen` on a method-level event (e.g. `PostMethodTransformEvent`, like
  [`NumberTransformer`](../dev.skidfuscator.obfuscator/src/main/java/dev/skidfuscator/obfuscator/transform/impl/number/NumberTransformer.java)).
  v1: inject **one** check per protected class; expand to many call sites later.
- For each protected class A, choose a **target** B and build the IR for
  `Tamper.verify(B.class, <sentinel long>)`, splicing it into A's CFG (MapleIR), the
  way existing transformers construct `Expr`/`Stmt` nodes. The sentinel long is a unique
  per-edge nonce so the stamp pass can find and replace exactly that LDC.
- Record edge **A → B** in a build-wide **IntegrityGraph** (see 3d).
- **Target selection = graph construction.** This is where Tier A vs B diverges:
  - Tier A: choose targets so the graph stays a **DAG**; ensure the entrypoint is a sink;
    track in-degrees so coverage is maximal (only the unavoidable source(s) uncovered).
  - Tier B: free choice (cycles fine); aim for high connectivity / redundancy so removing
    any few checkers leaves the rest covering each other.
- Honors `getExemptAnalysis()` and `shouldSkipMethod(...)` (skip abstract/native, the SDK
  classes themselves, and exempt classes — never make them targets or holders).
- `createConfig()` → config key `tamperProtection.*` (camelCase of the name).

### 3c. Stamping pass — two-pass dump in `MapleJarUtil.dumpJar`
Today [`dumpClass`](../dev.skidfuscator.obfuscator/src/main/java/dev/skidfuscator/obfuscator/util/MapleJarUtil.java:53)
serializes each class (`ClassWriter` + `ClassRemapper` → `toByteArray()`) and streams it
straight to the `JarOutputStream`. The mesh needs final bytes of targets before stamping
their checkers, so refactor to:
1. **Pass A — serialize to buffer.** Produce `Map<finalName, byte[]>` using the existing
   per-class logic (exempt path, `COMPUTE_FRAMES`/`COMPUTE_MAXS`, the
   `MethodTooLargeException` failsafe, the `fileCrasher.enabled` trailing-slash, and the
   `cn.node = factory.create(...)` rewrite must all be preserved).
2. **Stamp.** Resolve hashes and replace sentinel LDCs with real values:
   - *Tier A:* walk the IntegrityGraph in **reverse-topological order**; to finalize A,
     ensure every B with A→B is already frozen, set A's check-site LDC(s) to `hash(B)`,
     then (re)serialize A and freeze its bytes.
   - *Tier B:* order-independent. Serialize each class once; compute `hash(payloadCode(B))`
     from B's frozen bytes; write into each checker's LDC. Because LDCs live in the
     excluded checker method, no re-serialization ripple.
3. **Pass B — write.** Emit all buffered entries to the `JarOutputStream`.

Most invasive change and the main risk surface (§7). Do the LDC replacement at the
**ASM tree level** (find the `LdcInsnNode` carrying the sentinel; set its value) rather
than raw constant-pool byte editing.

### 3d. `IntegrityGraph` (build-time)
New small type holding edges (A→B), the per-edge sentinel nonce, and helpers:
in-degree/out-degree, topological sort (Tier A), cycle detection, and a "coverage report"
(which classes are checked, which source is exposed in Tier A). Lives on `Skidfuscator`
or as a dedicated singleton the transformer and the dump pass both reach.

---

## 4. Build-time ↔ runtime contract (must stay in lockstep)

The runtime hash and the build-time hash **must cover the exact same byte region of the
target**.

- **Region:** *Tier A* = the entire final `.class` of B. *Tier B* = the `Code` attribute
  bytes of B's designated payload method (selected by a rule both sides share — e.g. a
  pinned method index, or "the largest non-checker method").
- **Post-remap bytes.** Both sides operate on B's *final, remapped* serialization (what
  actually ships and what `getResourceAsStream` returns). Pass A must apply the same
  `ClassRemapper` it does today.
- **Algorithm:** `LongHashFunction.xx3()`, same seed, same byte order, both sides.
- **Determinism:** the buffered bytes hashed in the stamp step must be byte-identical to
  what Pass B writes. Any divergence = guaranteed false positive. (#1 risk.)
- **Name handling:** never hardcode resource path strings; pass the `Class<?>` literal.

---

## 5. Milestone 3 — seed-coupled silent corruption (deferred, designed here)

Replace the hard `throw` with integration into the existing flow-seed / opaque-predicate
system (`BlockOpaquePredicate`, the seed routing used by
[`NumberTransformer`](../dev.skidfuscator.obfuscator/src/main/java/dev/skidfuscator/obfuscator/transform/impl/number/NumberTransformer.java)).
`Tamper.verify` returns `0` on match and nonzero garbage on mismatch; that value is
**XOR'd into the block seed** consumed by downstream opaque predicates. Correct jar → seed
unchanged → normal routing. Tampered jar → seed corrupted → predicates misroute → control
flow silently breaks *far from the check*, with no obvious `if (tampered) throw` to locate.
In a mesh this compounds: tampering B corrupts the seed inside A, so the failure surfaces
in a third location. Deferred because it requires threading the verify result into the
predicate getter and proving a *clean* jar is always seed-neutral.

---

## 6. Later hardening (after the mesh + seed-coupling)

- **Distribution:** many checks per class at varied call sites, and multiple in-edges per
  class, so no single removal disables protection.
- **Anti-agent / anti-debug:** detect `-javaagent`, `Instrumentation`, debug JVM flags —
  catches the runtime-instrumentation case resource hashing cannot.
- **Optional whole-jar layer:** a single `CodeSource`-based check as defense-in-depth (off
  by default; fragile with fat/exploded jars). In Tier A it can also cover the exposed
  source class.

---

## 7. Risks & open questions

1. **Determinism between buffered hash and shipped bytes** (§4) — #1 false-positive risk.
2. **Tier A source exposure** — at least one class structurally unchecked; pick it
   deliberately and/or cover with the optional whole-jar layer. Tier B removes this.
3. **Two-pass dump memory** — buffering every class as `byte[]` raises peak memory vs.
   streaming. Only buffer when `tamperProtection.enabled`; stream as today otherwise.
4. **Tier B parser agreement** — runtime and build `Code`-extraction must match exactly;
   pin the designated-method selection rule and unit-test both extractors on the same
   classes.
5. **Graph construction quality** — avoid accidental cycles in Tier A; ensure good
   coverage/redundancy in Tier B; never target/hold via exempt or SDK classes.
6. **clinit / injection ordering** — run after string/number encryption finish touching a
   method so the hashed region is final (event priority).
7. **fileCrasher.enabled** appends `/` to entry names — stamp pass and runtime resource
   name must agree under that mode.
8. **Re-serialization ripple (Tier A)** — setting an LDC and re-serializing A changes A's
   bytes; the reverse-topological order is what keeps this sound. Validate the topo sort
   handles disconnected components and singletons.

---

## 8. Concrete file touch-list

### Milestone 1 — Tier A mesh, hard-fail
| File | Change |
|------|--------|
| `dev.skidfuscator.sdk/.../sdk/Tamper.java` | **new** — runtime verify (whole-file xx3 + hard-fail). |
| `transform/impl/integrity/TamperProtectionTransformer.java` | **new** — inject `Tamper.verify(B.class, sentinel)`, add edge A→B, `requiresSdk()`. |
| `transform/impl/integrity/IntegrityGraph.java` | **new** — edges, sentinels, topo sort, coverage report. |
| `util/MapleJarUtil.java` | **modify** `dumpJar` — buffer, reverse-topo stamp (LDC replace), write. |
| `Skidfuscator.java` | register the transformer; expose the `IntegrityGraph`. |
| GUI: `TransformerPanel.java` / `ConfigPanel.java` | add `tamperProtection.enabled` toggle (both already in working tree). |
| config defaults | `tamperProtection.enabled=false`. |

### Milestone 2 — upgrade to Tier B (cyclic, method-Code region)
| File | Change |
|------|--------|
| `sdk/Tamper.java` | add class-file parse + payload-method `Code` extraction; switch region. |
| `util/MapleJarUtil.java` (stamp) | mirror the same `Code` extraction; drop the topo-order requirement. |
| `IntegrityGraph` / transformer | allow cycles; raise connectivity/redundancy. |
| shared | a tiny class-file/Code reader used by both build and runtime (or duplicated, kept in lockstep). |

### Milestone 3 — seed-coupling (replace hard-fail)
| File | Change |
|------|--------|
| `sdk/Tamper.java` | return seed delta instead of throwing. |
| transformer | XOR verify result into the `BlockOpaquePredicate` block seed. |

## 9. Testing (project testbed)

Builds run in `C:\Users\Ambassator\Documents\Skidfuscator\` (Gradle can't run in the
sandbox). Validation loop:
1. Obfuscate a small multi-class sample jar with `tamperProtection.enabled=true`.
2. Run it → identical behavior to the un-tampered baseline (no false positive). Inspect
   the coverage report; confirm exempt + SDK classes are neither targets nor holders.
3. Patch one byte in class **B** inside the output jar, re-zip, run → the checker in
   **A** (A→B) must hard-fail. Confirm the trip happens in a *different* class than the
   one edited (the mesh property).
4. Tier A only: verify the exposed source class is the intended low-value/decoy one.
5. Tier B: unit-test that the build-side and runtime `Code` extractors return identical
   bytes for the same class.
```
