# Tamper Protection — Design & Implementation Plan

> Status: **Milestone 1 implemented** (Tier A mesh, hard-fail, opt-in). Milestones 2–3 still design-only.
> Granularity: **cross-class mesh** (Option 2) — staged **Tier A (DAG, whole-file hash) → Tier B (cyclic, method-Code region)**.
> Tamper response: **staged — hard-fail first, seed-couple later**.

---

## 0. Implementation status (Milestone 1)

M1 is built and typechecks (JDK 17 `javac`, testbed recipe). Off by default; when
disabled the legacy single-pass dump path is byte-for-byte unchanged.

**Key deviation from the original §3 plan — injection moved to output time.** Instead of
injecting `Tamper.verify(B.class, <sentinel>)` during transforms and patching the sentinel
LDC later, the check is emitted *at dump time* with the **real** hash already computed. This
removes the design's #1 fragility (a sentinel LDC surviving number-encryption) and the
remapper-interaction question, while keeping the mesh + Tier A whole-file hash + reverse-topo
stamp intact. Trade-off: the check is **not** itself obfuscated in M1 (acceptable — the design
always staged obfuscation/seed-coupling to M3). Re-introducing transform-time injection is the
natural path for M3 seed-coupling.

**Files (M1):**
- `dev.skidfuscator.sdk/.../sdk/Tamper.java` — runtime `verify`/`verifyExit(Class,long)`;
  reads the target's own bytes via an absolute resource path (`/a/b/C.class`), whole-file
  `LongHashFunction.xx3().hashBytes`, hard-fails on mismatch; lenient when bytes can't be read
  (no false positives on exploded/instrumented runs).
- `transform/impl/integrity/IntegrityGraph.java` — edges A→B, post-order DFS `dependencyOrder()`
  (targets before holders), cycle detection, coverage/exposed-source report.
- `transform/impl/integrity/TamperProtectionConfig.java` — `isEnabled()` defaults **false**
  (mirrors `DriverConfig`); `getAction()` = `THROW`|`EXIT`|`SILENT` (unknown ⇒ `THROW`).
- `transform/impl/integrity/TamperProtectionTransformer.java` — enable gate / config anchor /
  `requiresSdk()`; logs the mesh is armed at `FinalSkidTransformEvent`.
- `phantom/jphantom/TamperJarDumper.java` — the two-pass dumper (buffer final remapped bytes →
  reverse-topo stamp each holder's `<clinit>` with `Tamper.verify(B.class, hash(B))` using the
  frozen target bytes, re-serialised with `COMPUTE_MAXS` → write all + resources).
- `util/MapleJarUtil.java` — `dumpJar` branches to `TamperJarDumper` only when
  `tamperProtection.enabled && sdk.enabled && !fileCrasher.enabled`; otherwise legacy path.
- `Skidfuscator.java` — registers the transformer. `gui/TransformerPanel.java` — "Tamper
  Protection" card (id `tamperProtection`) with a `THROW`/`EXIT`/`SILENT` toggle.
  `defaultConfig.hocon` — `tamperProtection { enabled = false, action = THROW }`.

**Mesh shape (M1):** eligible classes (non-exempt, non-SDK, non interface/annotation/enum)
are sorted deterministically and chained — `c_i` verifies `c_{i-1}` — guaranteeing a DAG. The
last class in the order is the unavoidable Tier A exposed source (logged). One check per holder;
redundancy/multi-edge is later hardening (§6).

**Determinism** rests on the same guarantee the codebase already relies on: `SDK.checkType`
embeds a build-time `xx3` hash that the runtime recomputes. The build hashes the exact `byte[]`
it writes to the jar; the runtime reads the exact same entry → hashes match.

A third reaction, **`SILENT`**, has since been added on top of M1 (still output-time injection):
on mismatch the check returns normally and arms a deferred, off-thread, jittered JVM halt, so the
failure is displaced in time and stack from the check and the patched class. This is the
*deferred-reaction* tier of "silent" — it does not yet couple into the flow seed (the full
behavioural corruption of §5 is still M3). See §5.

**M1 limitations / not yet done:** Tier B (method-Code region, true cycles) — §2/§8 M2;
seed-coupled silent *corruption* (behavioural, not just deferred termination) — §5/§8 M3;
multiple checks/in-edges per class — §6; the check call is visible cleartext (not obfuscated);
incompatible with `fileCrasher` (auto-bypassed with a warning); `<clinit>`-only trigger (a class
never initialised at runtime never self-checks).

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

## 5. Silent reaction — shipped deferred tier + Milestone 3 seed-coupling

There are two levels of "silent", staged:

**(Shipped) `SILENT` action — deferred, off-thread termination.** `Tamper.verifySilent`
detects exactly like `verify` but, on mismatch, does **not** fail at the check site: it arms a
one-shot reaction (`AtomicBoolean ARMED`) and returns, so `<clinit>` completes and the program
runs on. The reaction is a non-daemon thread that sleeps a jittered 5–45s delay (derived from
`System.nanoTime() ^ expected`, so there is no fixed timing fingerprint) and then
`Runtime.getRuntime().halt(0)` — an innocuous exit, skipping shutdown hooks. The failure is thus
displaced in **time** (later) and **stack** (a background thread, not the verifying method), and
in a mesh the patched class B, the verifying class A, and the dying thread are three different
places. A clean build never reaches `detonate` (every check matches; lenient on unreadable
bytes), so it is byte-for-byte behaviourally identical. Cost: it still ultimately terminates and
a determined attacker can hook `Runtime.halt`/`Thread.sleep`; it does not corrupt *behaviour*.

**(M3) Seed-coupled silent corruption.** The deeper tier — no termination at all, just wrong
answers. Replace the hard `throw` with integration into the existing flow-seed / opaque-predicate
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
