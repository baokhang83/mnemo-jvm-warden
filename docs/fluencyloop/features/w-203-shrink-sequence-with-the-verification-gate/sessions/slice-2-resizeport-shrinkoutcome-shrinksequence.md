# Session: Slice 2: ResizePort + ShrinkOutcome + ShrinkSequence

- **intent:** Slice 2: ResizePort + ShrinkOutcome + ShrinkSequence
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

- **`ResizePort`** — a one-method interface (`resizeMemory`) extracted from `PodResizeClient`,
  which now `implements` it. Exists purely so `ShrinkSequence` depends on an abstraction rather
  than the concrete Kubernetes HTTP client (§2), and so its ordering/gate logic can be unit
  tested with a fake instead of hitting a real API server. · status: documented
- **`ShrinkOutcome`** — a sealed interface with two record implementations, `Completed(long
  finalRssBytes)` and `AbortedVerificationFailed(long observedRssBytes, long targetBytes)`. A
  caller pattern-matching on it must handle both explicitly; there's no boolean-plus-fields shape
  that could let an abort silently fall through as a success. · status: documented
- **`ShrinkSequence.shrinkTo(requestBytes, limitBytes)`** — the actual §5 handshake: `setSoftMax`
  → `deepGcAndUncommit` → `currentRss()` → branch. The resize call to `ResizePort` only exists
  inside the `rss < limitBytes` branch, so there is no code path where a resize and a failed
  verification can both happen — the invariant is structural, not caller discipline. SoftMax is
  set to `limitBytes` (the new hard ceiling), not `requestBytes`. · status: documented
- **Test doubles are hand-rolled, not a mocking framework** — `FakeHeapController` and
  `FakeResizePort` in `ShrinkSequenceTest` directly implement the two ports and record calls in a
  list; no Mockito/EasyMock dependency exists anywhere in this project. `FakeHeapController`'s
  `capabilities()` throws unconditionally, since `ShrinkSequence` should never call it (staying
  GC-blind, §2) — any future regression that does would fail every test in the class immediately.
  · status: documented


## Decision: extract ResizePort from PodResizeClient rather than passing the concrete class

- **where:** `warden-agent/.../resize/ResizePort.java, PodResizeClient.java`
- **why:** constitution §2 states safety/orchestration logic depends on narrow ports, never directly on the Kubernetes client — ShrinkSequence is exactly that kind of logic, and the concrete PodResizeClient requires a real in-cluster API server connection to construct at all, which would make the ordering/gate logic untestable without a live cluster
- **alternative:** have ShrinkSequence depend on PodResizeClient directly — rejected: violates §2 outright, and forces every unit test of the gate logic to either mock a concrete final class (awkward without a mocking framework this project doesn't use) or stand up a real cluster just to test string/long plumbing that has nothing to do with Kubernetes specifics
- **design:** ../design.md
- **constitution:** §2
- **trust:** ✓ verified

## Decision: the resize call sits strictly inside the verified branch, with a test asserting zero calls on the other branch

- **where:** `warden-agent/.../sequence/ShrinkSequence.java`
- **why:** constitution §5 requires the shrink-verify-resize ordering be enforced in code, not by convention — the strongest code-level proof of that is a test asserting the resize collaborator was never invoked at all when the gate fails, not just that some boolean was set correctly
- **alternative:** compute a shouldResize boolean and let the caller decide whether to call resize — rejected: this pushes the safety-critical decision back onto every caller, exactly the 'ordering left to caller discipline' failure mode §5 explicitly calls out as a violation
- **design:** ../design.md
- **constitution:** §5
- **trust:** ✓ verified
