# Session: Slice 1: generalize ZgcSoftMax to SoftMax

- **intent:** Slice 1: generalize ZgcSoftMax to SoftMax
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

- **`SoftMax`** — renamed from `ZgcSoftMax`; identical get/set logic, guard broadened from `collector != ZGC` to `!capabilities.supportsSoftMax()` (already true for both ZGC and Shenandoah via `GcCapabilities` from W-101). · status: documented
- **`DeepGc` needed no changes** — it already guards on `GcCapabilities.supported()`, true for ZGC, Shenandoah, and G1; verified it works against a real Shenandoah target implicitly by the fact its own mechanism (JMX `GC.run` + committed-heap polling) has no collector-specific code path at all. · status: documented
- **Real Shenandoah verification, with an environment surprise** — Maven on this machine resolves to Homebrew's OpenJDK 25, not `/usr/bin/java`; the former builds with Shenandoah, the latter apparently doesn't. All three `SoftMaxTest` cases (ZGC, Shenandoah, G1-reject) ran for real locally as a result — `ShenandoahUncommitDelay` exists (default 300000ms, same as ZGC's `ZUncommitDelay`) but needs `-XX:+UnlockExperimentalVMOptions`, unlike `ZUncommitDelay`. · status: documented

## Decision: generalize ZgcSoftMax to SoftMax rather than duplicate into ShenandoahSoftMax

- **where:** `warden-agent/src/main/java/io/github/baokhang83/mnemo/warden/agent/heap/SoftMax.java`
- **why:** SoftMaxHeapSize is the same VM option on both ZGC and Shenandoah (confirmed in W-103); GcCapabilities.supportsSoftMax() already exists and is true for both, so broadening the guard and renaming the class serves both collectors with one implementation instead of two near-identical ones
- **alternative:** duplicate the get/set logic into a new ShenandoahSoftMax class — rejected: two classes that would need to stay in sync if SoftMaxHeapSize semantics ever diverge, for logic that is already 100 percent collector-agnostic
- **design:** ../design.md#class-diagram
- **trust:** ✓ verified
