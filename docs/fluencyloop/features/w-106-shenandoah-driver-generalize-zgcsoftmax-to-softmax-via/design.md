# Design: W-106 — Shenandoah driver

started: 2026-07-20

Smaller than the previous four tickets: both pieces of "the driver contract" already work for
Shenandoah without new mechanism.

- **`DeepGc`** already guards on `GcCapabilities.supported()` (== `supportsUncommit()`), true for
  ZGC, Shenandoah, and G1. Zero changes needed.
- **`ZgcSoftMax`**'s actual get/set logic (`HotSpotDiagnosticMXBean.getVMOption`/`setVMOption` on
  `SoftMaxHeapSize`) is already collector-agnostic &mdash; `SoftMaxHeapSize` is the same flag on
  both collectors (confirmed in W-103). The only ZGC-specific part is its guard
  (`collector != ZGC`), and `GcCapabilities.supportsSoftMax()` &mdash; already `true` for both
  ZGC and Shenandoah &mdash; already exists to replace it.

So W-106 is a **generalize + rename**, not a new class: `ZgcSoftMax` &rarr; `SoftMax`, guard
broadened to `!capabilities.supportsSoftMax()`.

Verified against a real target before finalizing (this dev machine's own JDK doesn't build with
Shenandoah; the project's actual runtime, `eclipse-temurin:21-jdk`, does):

- Real Shenandoah GC bean names are `"Shenandoah Pauses"` / `"Shenandoah Cycles"` &mdash; already
  matched by `GcDetector`'s existing `contains("shenandoah")` check from W-101. No changes needed.
- `ShenandoahUncommitDelay` (the ticket's namesake tunable) exists, defaults to 300000ms &mdash;
  the same 300s default as ZGC's `ZUncommitDelay` &mdash; but is gated behind
  `-XX:+UnlockExperimentalVMOptions`, unlike `ZUncommitDelay` which needs no unlock flag.

## Class diagram

```mermaid
classDiagram
  class SoftMax {
    -HotSpotDiagnosticMXBean diagnostics
    +softMaxHeapSize() long
    +setSoftMaxHeapSize(long bytes)
    +forTarget(AttachedJvm target)$ SoftMax
  }
  class GcCapabilities {
    +supportsSoftMax() boolean
  }
  class DeepGc {
    +forTarget(AttachedJvm target)$ DeepGc
  }
  class UnsupportedCollectorException

  SoftMax ..> GcCapabilities : checks supportsSoftMax()
  SoftMax ..> UnsupportedCollectorException : throws if not supported
  DeepGc ..> GcCapabilities : checks supported() — unchanged since W-104
```

## Sequence: same forTarget() shape, broader guard

```mermaid
sequenceDiagram
  participant Caller
  participant SM as SoftMax
  participant Det as GcDetector
  participant Target as Target JVM (ZGC or Shenandoah)

  Caller->>SM: forTarget(attachedJvm)
  SM->>Target: getPlatformMXBeans(GarbageCollectorMXBean)
  Target-->>SM: GC bean list ("Shenandoah Pauses/Cycles", verified real)
  SM->>Det: detect(beans)
  Det-->>SM: GcCapabilities(collector, supportsSoftMax=true)
  alt !supportsSoftMax()
    SM-->>Caller: throw UnsupportedCollectorException
  else supportsSoftMax()
    SM-->>Caller: new SoftMax(diagnostics)
  end
  Note over SM,Target: identical for ZGC and Shenandoah from here —<br/>same VM option name, same JMX call
```
