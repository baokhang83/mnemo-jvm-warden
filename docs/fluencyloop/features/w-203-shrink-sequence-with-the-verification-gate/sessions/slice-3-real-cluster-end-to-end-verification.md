# Session: Slice 3: real cluster end-to-end verification

- **intent:** Slice 3: real cluster end-to-end verification
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

- **The example manifest's "default collector (G1)" comment was wrong** — confirmed via `jcmd
  <pid> VM.flags` against a real target that at this container's memory size (512Mi limit), the
  JVM's own ergonomics actually pick Serial GC (`Collector.OTHER`), not G1. `example-sidecar.yaml`
  now sets `-XX:+UseG1GC` explicitly rather than relying on an unverified default, and the comment
  was corrected to state that plainly. Never would have surfaced without trying to actually run
  the full sequence against the real deployed example. · status: documented
- **Full end-to-end verification on a real kind v1.36 cluster, genuine UID mismatch** — same
  setup as bug #57 (warden uid 999, app defaults to root). Compiled a harness
  (`AttachedHeapController.forTarget` + `PodResizeClient.forInClusterAgent` + `ShrinkSequence`)
  inside the live `warden` container and ran it against the real `app` container's pid twice:
  · status: documented
- **Happy path, verified** — `shrinkTo(request=200Mi, limit=300Mi)` against a target with
  baseline RSS ~115MB returned `Completed[finalRssBytes=119898112]`, and `kubectl get pod`
  confirmed both `spec.containers[0].resources` (raw bytes, `209715200`/`314572800`) and
  `status.containerStatuses[0].resources` (kubelet-normalized, `200Mi`/`300Mi`) actually changed
  — proving the resize really reached the kubelet, not just that `ShrinkSequence` returned the
  right type. · status: documented
- **Abort path, verified** — a second attempt, `shrinkTo(request=20Mi, limit=30Mi)` (a limit no
  running JVM's fixed overhead — code cache, metaspace, thread stacks — could ever fit under),
  returned `AbortedVerificationFailed[observedRssBytes=125292544, targetBytes=31457280]`, and
  `kubectl get pod` afterward showed `spec`/`status` resources byte-for-byte unchanged from the
  prior successful resize (`300Mi`/`200Mi`) — direct proof the cgroup is genuinely never touched
  on a failed gate, not just that the code path looks like it skips the call. · status: documented


## Decision: pick an intentionally impossible target for the abort-path proof, not a marginal one

- **where:** `verification harness (ad hoc; deploy/README.md and tests carry the durable record)`
- **why:** the goal was an unambiguous, repeatable proof that the gate blocks the resize call entirely — a limit no JVM's baseline overhead could ever fit under (30Mi, when code cache alone reserves hundreds of MB by default) makes the abort deterministic across runs, rather than depending on GC timing variance to land just above some marginal threshold
- **alternative:** pick a limit just below the observed baseline RSS to test a close call — rejected for this verification pass: useful for exploring gate sensitivity, but not what this slice needed to prove, which was structural (does a failed gate ever leak through to a resize call), not numerical precision at the boundary, already covered by ShrinkSequenceTest.treatsRssEqualToTheTargetAsAFailedGate's exact-boundary unit test
- **design:** ../design.md
- **constitution:** §8
- **trust:** ✓ verified
