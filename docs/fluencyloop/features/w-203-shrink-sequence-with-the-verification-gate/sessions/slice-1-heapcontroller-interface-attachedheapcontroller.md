# Session: Slice 1: HeapController interface + AttachedHeapController

- **intent:** Slice 1: HeapController interface + AttachedHeapController
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

- **`AttachedHeapController`** — the first real implementation of the `HeapController` port that
  has sat unimplemented since M1; composes `SoftMax` (null on G1, since it has no runtime soft
  max), `DeepGc`, and `RssReader` over one `AttachedJvm`. `forTarget` detects the collector once
  up front and throws `UnsupportedCollectorException` immediately if it can't uncommit at all
  (Serial/Parallel/Epsilon), so the agent never attempts a shrink sequence on a collector where
  it would silently do nothing. · status: documented
- **`HeapController`'s completed signature** — `currentRss()` now declares `throws IOException`
  and `deepGcAndUncommit()` gained a `Duration timeout` parameter and the same checked
  exceptions, matching what `RssReader`/`DeepGc` actually throw underneath. `setSoftMax(long)`
  needed no change: `HotSpotDiagnosticMXBean`'s JMX proxy methods don't declare checked
  exceptions (an unchecked `IOError` on a genuine connection failure), so `SoftMax` was already
  correct as originally sketched. · status: documented
- **Package-private `forTarget(AttachedJvm, Path hostCgroupRoot)` test seam** — mirrors bug #57's
  `RssReaderTest` pattern exactly: the public `forTarget(AttachedJvm)` hardcodes
  `RssReader.HOST_CGROUP_ROOT` (`/host-cgroup`, only real inside a deployed pod with the hostPath
  mount), so CI and local tests instead call the seam with the runner's real `/sys/fs/cgroup` to
  exercise the same composition without needing a cluster. · status: documented
- **`setSoftMax` delegation is verified by reading back through a second `SoftMax` instance**, not
  by mocking — `AttachedHeapControllerTest` calls `heap.setSoftMax(...)` then opens a fresh
  `SoftMax.forTarget(attached)` on the same live JVM and asserts `softMaxHeapSize()` reflects it,
  proving the call actually reached the target rather than just not throwing. · status:
  documented

## Decision: verify real-collector composition against live target JVMs, not mocks of HeapController's own collaborators

- **where:** `warden-agent/.../heap/AttachedHeapControllerTest.java`
- **why:** AttachedHeapController's only real job is correctly wiring SoftMax/DeepGc/RssReader together across the G1-vs-ZGC capability split; a mocked SoftMax/DeepGc/RssReader would prove the wiring compiles but not that it actually reaches a real target, which is exactly the kind of platform behavior this codebase's existing DeepGcTest/RssReaderTest/SoftMaxTest already insist on testing for real
- **alternative:** mock HeapController's three collaborators and assert they're called with the right arguments — rejected: proves the code calls the right methods, not that setSoftMax/currentRss/deepGcAndUncommit actually do anything on a live JVM, which is the one thing worth verifying about a composition class
- **design:** ../design.md
- **constitution:** §8
- **trust:** ✓ verified
