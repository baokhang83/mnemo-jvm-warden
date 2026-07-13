# Session: HeapController port, GcCapabilities model, and MXBean-name GC detection

- **intent:** HeapController port, GcCapabilities model, and MXBean-name GC detection
- **started:** 2026-07-12

---

## Knowledge transfer

- **`HeapController` (interface)** — the collector-agnostic port: `currentRss()`,
  `setSoftMax(bytes)`, `deepGcAndUncommit()`, `capabilities()`. Per-collector drivers implement
  it later; the M2 state machine depends only on it. · status: documented
- **`GcCapabilities` (record)** — `collector`, `supportsSoftMax`, `supportsUncommit`, plus
  `supported()`. Describes what a target's GC lets Warden do. · status: documented
- **`Collector` (enum)** — ZGC, SHENANDOAH, G1, OTHER. · status: documented
- **`GcDetector`** — `classify(names)` fingerprints the collector from `GarbageCollectorMXBean`
  names; `capabilitiesFor(collector)` maps it to capabilities; `detectLocal()` reads the current
  JVM's beans. W-102 will point `detect(beans)` at the attached target. · status: documented
- **Per-collector memory return** — ZGC/Shenandoah uncommit and honour a runtime
  `SoftMaxHeapSize`; G1 uncommits only via periodic GC and has no runtime soft max; Serial/Parallel
  have no runtime uncommit control. This is what the capability map encodes. · status: documented

---

## Decision: a port + capability descriptor, not a GC-typed API

- **where:** `warden-agent/.../heap/HeapController.java`, `GcCapabilities.java`
- **why:** One interface plus a `capabilities()` descriptor lets callers read what is possible
  (soft-max? uncommit?) instead of branching on the collector. The M2 resize state machine can
  then stay entirely GC-blind — it calls the port and checks capabilities, never switches on ZGC
  vs G1.
- **alternative:** Collector-specific methods / a `switch (collector)` in the caller — rejected:
  welds the safety logic to the GC matrix, so every new collector edits the state machine.
- **design:** [../design.md](../design.md) — `HeapController` and the dashed drivers/consumer.
- **constitution:** §2 (abstractions at the seams)
- **trust:** ✓ verified — reactor green; the port compiles and detection returns capabilities.

## Decision: detect from MXBean names, not launch flags

- **where:** `warden-agent/.../heap/GcDetector.java` (`classify`)
- **why:** `GarbageCollectorMXBean` names ("ZGC Cycles", "Shenandoah Pauses", "G1 Young
  Generation") are a reliable fingerprint of the *running* collector, independent of how the JVM
  was started, and the same logic works locally now and against the attached target in W-102.
- **alternative:** Parse launch flags / `-XX` options — rejected: brittle (flags may be implicit,
  come from JAVA_TOOL_OPTIONS, or be a default) and not what actually ended up running.
- **constitution:** §3 (small, intention-revealing units — pure `classify`/`capabilitiesFor`)
- **trust:** ✓ verified — ran `detectLocal()` under real collectors: `UseG1GC`->G1 (no soft-max),
  `UseZGC`->ZGC (full), `UseSerialGC`/`UseParallelGC`->OTHER (read-only); Shenandoah name-path
  covered by unit test (not in this JDK build).

## Decision: an unknown collector is read-only, not an error

- **where:** `GcDetector.capabilitiesFor` (`OTHER` branch), `GcCapabilities.supported()`
- **why:** If Warden can't recognise/drive the collector it must degrade to observe-only, never
  crash the sidecar. `OTHER` maps to `supportsUncommit=false` so `supported()` is false, which the
  agent (W-603) reads as "read-only". `supported()` keys off uncommit because without uncommit,
  lowering a soft max returns nothing to the OS.
- **alternative:** Throw on an unrecognised collector — rejected: turns an unsupported-but-healthy
  target into a failing sidecar; violates the safety posture (§5).
- **constitution:** §5 (degrade safely), §2
- **trust:** ✓ verified — Serial and Parallel both resolve to OTHER/read-only when run.
