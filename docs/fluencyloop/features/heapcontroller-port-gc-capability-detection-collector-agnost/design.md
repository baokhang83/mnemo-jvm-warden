# Design: HeapController port + GC capability detection (collector-agnostic contract, ZGC/Shenandoah/G1 detection)

started: 2026-07-12

A collector-agnostic contract for controlling a JVM's heap, plus detection of which collector
the target runs and what it can do. The seam that keeps every later safety decision GC-blind
(§2). M1 begins here.

Scope of W-101: the `HeapController` **interface**, the `Collector` enum, the `GcCapabilities`
record, and `GcDetector` (with tests). The per-collector drivers (W-103/106/107) and the M2
consumer plug into the port later. Detection runs against the **local** JVM now; W-102 retargets
the identical logic at the attached target.

Capability map (declared here, verified as each real driver lands):

| Collector | setSoftMax | deepGcAndUncommit | Warden can shrink? |
|---|---|---|---|
| ZGC | real | real | yes |
| SHENANDOAH | real | real | yes |
| G1 | no-op (no soft-max) | real (periodic GC) | weaker |
| OTHER | no-op | no-op | read-only |

## Class diagram — the port and its model

```mermaid
classDiagram
  class HeapController {
    <<interface>>
    +currentRss() long
    +setSoftMax(long bytes)
    +deepGcAndUncommit()
    +capabilities() GcCapabilities
  }
  class GcCapabilities {
    <<record>>
    +Collector collector
    +boolean supportsSoftMax
    +boolean supportsUncommit
    +supported() boolean
  }
  class Collector {
    <<enumeration>>
    ZGC
    SHENANDOAH
    G1
    OTHER
  }
  class GcDetector {
    +detectLocal() GcCapabilities
    +detect(beans) GcCapabilities
  }
  HeapController ..> GcCapabilities : returns
  GcCapabilities --> Collector : has
  GcDetector ..> GcCapabilities : produces
  ZgcHeapController ..|> HeapController : W-103
  ShenandoahHeapController ..|> HeapController : W-106
  G1HeapController ..|> HeapController : W-107
  ResizeStateMachine ..> HeapController : M2 (GC-blind)
```

## Sequence — detection

```mermaid
sequenceDiagram
  participant A as Agent
  participant D as GcDetector
  participant M as ManagementFactory
  A->>D: detectLocal()
  D->>M: getGarbageCollectorMXBeans()
  M-->>D: beans (with names)
  D->>D: classify names to Collector
  D->>D: map Collector to capabilities
  D-->>A: GcCapabilities(collector, softMax, uncommit)
  note over A: OTHER means supported=false means read-only (no crash)
```

## Constitution check

- **§2 (abstractions at the seams):** one `HeapController` port + a `capabilities()` descriptor;
  callers read what's possible instead of branching on the collector, so the M2 state machine
  never sees a GC type.
- **§1 (YAGNI):** interface + detection only; no driver implementation in this slice.
- **§3 (clean units):** detection is a pure `classify(names)` + `capabilitiesFor(collector)`,
  each independently testable.
- **§5 (no unverified shrink / degrade safely):** an unknown collector is `OTHER` → read-only
  capabilities, not an exception; the agent degrades to observe-only rather than crashing.

No conflicts.
