# Session: Write docs/configuration.md: full WardenPolicy field reference + operator runbook

- **intent:** Write docs/configuration.md: full WardenPolicy field reference + operator runbook
- **started:** 2026-07-22

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

- **`PrecedenceEngine.resolve`** — the single deterministic rule combining blackout/emergency-grow/schedule/veto into one resolved profile (or none); blackout skips even *resolving* the other signals, not just acting on them · status: documented
- **`WardenPolicyReconciler.evaluateMetric`** — a failed or unconfigured guardrail query returns `null`, which `ShrinkVetoEvaluator` treats as "not verified quiet" — i.e. missing data vetoes a shrink rather than letting it through (fail-safe, not fail-open) · status: documented
- **`BlackoutEvaluator`** — compares absolute `Instant`s, not local wall-clock crons, so `spec.blackout` windows are UTC-fixed instants regardless of `spec.timezone` — unlike `spec.schedule`, there's no DST question here · status: documented
- **`TargetHeapControllerResolver`** — resolves and caches the target's `HeapController` once per attach (keyed on JVM reference identity); an unsupported collector is a permanent verdict for that attach, logged once, not re-attempted per poll tick · status: documented
- **`ShrinkSequence.shrinkTo`** — on a failed RSS verification, `SoftMax` is deliberately left lowered rather than reverted (advisory only, no OOM risk, gives the next attempt a head start) · status: documented
- **`@ControllerConfiguration(maxReconciliationInterval = 30s)`** — a ceiling on how long a reconcile can be delayed, not the only trigger; spec/status changes reconcile immediately, this just bounds the worst case (e.g. picking up a new pod from a Deployment rollout) · status: documented

---

## Decision: docs/configuration.md covers agent/controller env vars and Helm values, not just the WardenPolicy CRD

- **where:** `docs/configuration.md`
- **why:** the runbook's failure modes (read-only GC mode, cgroup hostPath mount, guardrail metric not evaluating) are only diagnosable/fixable through WARDEN_* env vars and Helm chart knobs the CRD itself says nothing about; a CRD-only reference would leave an operator stuck mid-runbook with no pointer to the actual fix
- **alternative:** WardenPolicy fields only, per the roadmap's literal wording — rejected: would make the runbook section reference config it never defines
- **design:** ../design.md#1-the-schema-the-reference-documents
- **trust:** ✓ verified
