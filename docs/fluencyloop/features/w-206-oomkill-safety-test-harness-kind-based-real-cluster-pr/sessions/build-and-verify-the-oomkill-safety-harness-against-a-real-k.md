# Session: Build and verify the OOMKill safety harness against a real kind cluster

- **intent:** Build and verify the OOMKill safety harness against a real kind cluster
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

- **`harness.LoadTarget`'s chunk sizing** — retains live megabytes as 256 KiB `byte[]` chunks,
  not 1 MiB ones. A 1 MiB chunk is a G1 "humongous object" whenever G1's region size is also
  1 MiB (its minimum), since anything over half a region's size gets dedicated region(s) instead
  of packing normally — verified empirically that a 1 MiB chunk needed roughly double the
  intended heap to retain the same nominal megabytes, OOMing well before reaching the target
  size. · status: documented
- **`TargetLocator`'s single-other-java-process assumption breaks under this harness** — production
  code only ever has one "other" java process in the container (the target). Exec'ing
  `ShrinkTrialDriver` into the same `shareProcessNamespace` container adds a *third* java
  process (WardenAgent's own long-running main loop, plus the driver itself, plus the target),
  so auto-discovery goes ambiguous. The harness works around this with an explicit
  `WARDEN_TARGET_PID` override, found by scanning `/proc/*/cmdline` for `harness.LoadTarget` —
  production code itself is untouched. · status: documented
- **Pod `Ready` does not mean `LoadTarget` finished retaining its live set** — readiness reflects
  only the `warden` sidecar's own `/readyz` (a target is attached), which can flip as soon as the
  JMX port opens, before `LoadTarget.main`'s retain loop (real, non-instant work for the 220 MiB
  scenario) completes. `verify-oomkill-safety.sh` polls the app container's own "load-target
  ready" log line before running a trial, rather than trusting pod `Ready`. · status: documented
- **`ShrinkTrialDriver`'s exit codes are a deliberate, disjoint set** — `0` completed, `3` aborted,
  `2` no target found, `70` (`EX_SOFTWARE`) any other failure, explicitly not the JVM's own
  default-uncaught-exception code (`1`) — see the decision below for why that distinction is
  load-bearing, not cosmetic. · status: documented



## Decision: test-only harness.ShrinkTrialDriver instead of building M3's intent handoff early

- **where:** `warden-agent/src/main/java/io/github/baokhang83/mnemo/warden/agent/harness/ShrinkTrialDriver.java`
- **why:** Nothing wires ShrinkSequence into WardenAgent's own runtime yet — that's M3's intent handoff (W-306), which doesn't exist. W-206 only depends on W-203/204/205, so a minimal test-only entry point (kubectl exec'd into the running sidecar, constructing the real AttachedHeapController + PodResizeClient + ShrinkSequence) proves the safety property now without waiting on or prematurely building M3's controller-driven trigger.
- **alternative:** Defer W-206 until W-306 lands, or build a real intent-handoff mechanism early just to unblock this test — rejected: the roadmap explicitly scopes W-206 to M2's own dependencies, and building the M3 trigger ahead of its own milestone would be speculative, driven by a test's convenience rather than an actual caller.
- **design:** ../design.md
- **constitution:** §1
- **trust:** ✓ verified

## Decision: manual-run verification script, not full CI automation, for this slice

- **where:** `deploy/verify-oomkill-safety.sh`
- **why:** kind-based tests need a real cluster and a built image; W-202's verify-lifecycle-ordering.sh already established the manual-run-first pattern for exactly this class of check, keeping the slice reviewable without also designing a new CI job in the same PR.
- **alternative:** Build the GitHub Actions job (kind spin-up, image build/load, run, teardown) in this same PR, matching the issue's literal 'runs in CI' wording — rejected: bundling new CI infrastructure with the safety logic itself would make this slice larger and harder to review, for a requirement the issue itself already allows deferring (or nightly if slow).
- **design:** ../design.md
- **constitution:** §1
- **trust:** ✓ verified

## Decision: disjoint exit codes for ShrinkTrialDriver, not the JVM's default uncaught-exception code

- **where:** `warden-agent/src/main/java/io/github/baokhang83/mnemo/warden/agent/harness/ShrinkTrialDriver.java`
- **why:** A real JMX/RMI connection failure during an actual kind-cluster run threw an uncaught exception that exited 1 — indistinguishable from the originally-chosen EXIT_ABORTED (also 1) — and verify-oomkill-safety.sh logged a false PASS on a run that had actually crashed, not aborted correctly. Moved EXIT_ABORTED to 3 and added an explicit Throwable catch in main that exits 70 (EX_SOFTWARE) on anything unrecognized, so a genuine abort and an unrelated crash can never share an exit code.
- **alternative:** Leave the original exit codes (0 completed, 1 aborted) and rely on stdout message parsing alone to distinguish outcomes — rejected: exit code is what the shell script actually branches on; a message-parsing fallback would be strictly weaker and still leaves the exit code itself misleading to anyone else invoking the driver directly.
- **design:** ../design.md
- **constitution:** §10
- **trust:** ✓ verified
