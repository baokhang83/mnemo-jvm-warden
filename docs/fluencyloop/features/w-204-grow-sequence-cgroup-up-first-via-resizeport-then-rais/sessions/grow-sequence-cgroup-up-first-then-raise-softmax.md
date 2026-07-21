# Session: Grow sequence: cgroup up first, then raise SoftMax

- **intent:** Grow sequence: cgroup up first, then raise SoftMax
- **started:** 2026-07-21

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

- **`GrowSequence`** — composes the same `HeapController` and `ResizePort` ports `ShrinkSequence`
  depends on, calling them in reverse order: `resizeMemory` (cgroup up) before `setSoftMax`
  (raise) instead of after. No GC step and no verification read — grow never touches
  `deepGcAndUncommit` or `currentRss`. · status: documented
- **`GrowOutcome`** — a plain record carrying `confirmedLimitBytes`, returned only on a fully
  successful grow. There is no failure variant: a kubelet timeout surfaces as the existing
  unchecked `ResizeTimeoutException`, propagating straight out of `growTo` rather than being
  modeled as an outcome. · status: documented
- **`ResizeTimeoutException`** — already unchecked (`RuntimeException`), from W-201/W-203 — this
  slice relies on that to let it propagate through `growTo` without a `throws` clause or a
  wrapping outcome type. · status: documented


## Decision: no verification gate on grow, unlike shrink's RSS check

- **where:** `warden-agent/src/main/java/io/github/baokhang83/mnemo/warden/agent/sequence/GrowSequence.java`
- **why:** Raising SoftMax after the cgroup limit is already up cannot allocate into space the cgroup hasn't granted — the failure mode ShrinkSequence's RSS gate exists to catch (OOMKilling the pod) isn't reachable once the resize happens first. A gate here would check against nothing.
- **alternative:** Add a post-resize verification step mirroring ShrinkSequence's rss < limitBytes check — rejected: there's no real precondition left to verify by the time the cgroup is already up, so it would be speculative generality with no safety payoff.
- **design:** ../design.md#class-diagram
- **constitution:** §1
- **trust:** ⚠ not independently verified

## Decision: GrowOutcome is a plain record, not a sealed interface like ShrinkOutcome

- **where:** `warden-agent/src/main/java/io/github/baokhang83/mnemo/warden/agent/sequence/GrowOutcome.java`
- **why:** ShrinkOutcome is sealed because shrink has two real business outcomes a caller must handle explicitly (Completed / AbortedVerificationFailed). Grow has exactly one — its two failure modes are already distinct exceptions, not alternate success states — so a sealed interface with a single variant would be ceremony around a value the caller can't branch on differently.
- **alternative:** A sealed GrowOutcome interface with a single Completed variant, for surface-level symmetry with ShrinkOutcome — rejected: symmetry with no second variant to distinguish is speculative generality, not a real abstraction.
- **design:** ../design.md#class-diagram
- **constitution:** §1
- **trust:** ⚠ not independently verified
