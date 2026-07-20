# Session: Slice 1: G1PeriodicGc + DeepGc G1 test

- **intent:** Slice 1: G1PeriodicGc + DeepGc G1 test
- **started:** 2026-07-20

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

- **`G1PeriodicGc`** — reads/writes `G1PeriodicGCInterval` on an attached G1 target via `HotSpotDiagnosticMXBean`; defaults to `Duration.ZERO` (disabled) on a real target, meaning G1 never proactively collects on idle unless told to. · status: documented
- **G1 needs materially more heap headroom than ZGC for the same transient allocation** — `SpawnedJvm.garbageChurner`'s 400MB burst is briefly all-live (referenced by its local array until the method returns) before becoming collectible. ZGC handled this fine at `-Xmx512m`; G1 genuinely OOM'd at both 512m and 768m before 1200m proved reliable. · status: documented
- **G1's default reclaim is fast enough to defeat a before/after timing test** — unlike ZGC/Shenandoah (gated by a multi-second-to-300s uncommit delay), G1 has no equivalent default delay and can already be back at its committed-memory floor within ~1-2 seconds of garbage becoming unreachable — often before a test can even attach and take a "before" reading. `DeepGcTest`'s G1 case asserts only that the real `GC.run` + polling mechanism runs cleanly, not a specific observed-drop outcome. · status: documented


## Decision: guard on collector == G1 directly, not a new GcCapabilities field

- **where:** `warden-agent/src/main/java/io/github/baokhang83/mnemo/warden/agent/heap/G1PeriodicGc.java`
- **why:** G1PeriodicGCInterval does not exist on ZGC or Shenandoah, since ZGC's concurrent cycle runs continuously with no idle concept to trigger on; since exactly one collector will ever use this guard, checking the collector directly avoids a capability field with a single caller
- **alternative:** add supportsPeriodicGc() to GcCapabilities — rejected: would generalize a flag that is inherently G1-specific, for no second caller, violating YAGNI
- **design:** ../design.md#class-diagram
- **trust:** ✓ verified

## Decision: G1 DeepGcTest verifies the mechanism runs cleanly, not a specific observed drop

- **where:** `warden-agent/src/test/java/io/github/baokhang83/mnemo/warden/agent/heap/DeepGcTest.java`
- **why:** a real run showed G1 can already be back at its committed floor before this test can attach and read a before value, so the honest completion algorithm from W-104 (constitution section 7: a verification poll needs positive evidence) correctly reports completed=false when there is genuinely nothing left to observe dropping, not a bug to work around
- **alternative:** force a longer window, like ZGC's ZUncommitDelay override, to reliably observe a drop for G1 too — rejected: G1 has no equivalent uncommit-delay tunable to hold the state open with, so there is no clean way to create the same deterministic window
- **design:** ../design.md#sequence-expose-the-one-g1-specific-lever
- **constitution:** §7
- **trust:** ✓ verified
