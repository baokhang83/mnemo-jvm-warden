# Session: Slice 1: ZgcSoftMax + UnsupportedCollectorException

- **intent:** Slice 1: ZgcSoftMax + UnsupportedCollectorException
- **started:** 2026-07-19

<!--
FluencyLoop Stage 3 — a session is a slice of the build. It holds two persistent records:

1. Knowledge transfer — what the developer was actually made fluent in during this slice.
2. Decisions — the genuine forks, one `## Decision:` block each.

Both are appended at the slice boundary, from the *live* teaching. One bullet per field, so it
renders one-per-line (plain `key: value` lines collapse into a paragraph when rendered). No
`commits:` field: the feature is a branch, so the PR view derives commits live from git.

DECISION fields:
  where        — file/area the decision lives in (NOT a line number — survives refactoring)
  why          — the rationale, taught live before it was written
  alternative  — the rejected option and why (this is what makes it rationale, not description)
  design       — (optional) ../design.md#anchor — the diagram this decision shaped or used
  constitution — (optional) §N — the principle this decision serves or trades off against
  trust        — ✓ verified | ⚠ not independently verified  (about the DECISION, never the person)

KNOWLEDGE-TRANSFER fields (one bullet per component/role/mechanism explained):
  subject      — the component, role, or mechanism (e.g. a class, an agent, a rule)
  what         — what it does, and under what conditions it does it
  status       — documented (captured here) | follow-up (worth covering later)
  Describe the WORK, never a person: no competence, no prior-knowledge, no "who learned what".
  These files are committed and name an identifiable author via git — keep them GDPR-safe.

Delete this comment and the examples below once real content lands.
-->

---

## Knowledge transfer

_The ground this slice makes understandable — the components, roles, and conditions explained,
persisted so the fluency doesn't evaporate with the conversation. About the work, never a
person: it records what the code does, not who knew what (the per-developer picture lives in
the uncommitted, global calibration profile)._

- **`ZgcSoftMax`** — reads/writes `SoftMaxHeapSize` on an attached target via `com.sun.management.HotSpotDiagnosticMXBean`; deliberately not a `HeapController` implementation yet, since `currentRss()` (W-105) and `deepGcAndUncommit()` (W-104) don't exist. · status: documented
- **`SoftMaxHeapSize` is collector-agnostic at the JMX layer** — the VM option exists and is settable on every HotSpot collector, including G1; only ZGC (and Shenandoah) actually read it at runtime. Verified against a real G1 target: `setVMOption` returns normally and `getVMOption` reflects the new value, but G1's sizing logic never consults it. · status: documented
- **`UnsupportedCollectorException`** — new checked-by-design runtime exception in `agent.heap`, carrying the detected `Collector`; reusable by the Shenandoah (W-106) and G1 (W-107) drivers for the same "wrong collector" guard. · status: documented
- **`SpawnedJvm`** — new shared test helper (`agent.testsupport`) extracted from duplicated spawn/await logic in `TargetAttacherTest` and `AttachSupervisorTest`, now also used by `ZgcSoftMaxTest`; this was the third occurrence of the same 20-line pattern. · status: documented

---

## Decision: narrow ZgcSoftMax class now, compose HeapController later

- **where:** `warden-agent/src/main/java/io/github/baokhang83/mnemo/warden/agent/heap/ZgcSoftMax.java`
- **why:** currentRss() needs W-105's cgroup/NMT reader and deepGcAndUncommit() needs W-104, neither of which exist yet; a real caller (M2's resize state machine) for the whole HeapController contract doesn't exist yet either, so composing it now would be premature
- **alternative:** implement HeapController today with currentRss()/deepGcAndUncommit() throwing UnsupportedOperationException — rejected: lets a caller type-check against the interface and still get a runtime surprise, a promise the class can't keep yet
- **design:** ../design.md#class-diagram
- **trust:** ✓ verified

## Decision: reject by checking the collector, not by catching a JMX error

- **where:** `warden-agent/src/main/java/io/github/baokhang83/mnemo/warden/agent/heap/ZgcSoftMax.java`
- **why:** HotSpotDiagnosticMXBean.setVMOption(SoftMaxHeapSize, ...) succeeds silently on G1 (verified against a real G1 target: no exception, getVMOption reflects the new value, but G1's sizing logic never reads it), so forTarget() runs GcDetector.detect() against the target's real GC beans and refuses before ever calling the API
- **alternative:** call setVMOption and trust the API to fail on the wrong collector — rejected: verified against a real target that it does not fail, it silently no-ops
- **design:** ../design.md#sequence-forTarget-validates-before-touching-the-vm-option
- **trust:** ✓ verified
