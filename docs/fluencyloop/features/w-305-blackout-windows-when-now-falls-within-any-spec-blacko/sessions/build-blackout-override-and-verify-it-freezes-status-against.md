# Session: Build blackout override and verify it freezes status against a real cluster

- **intent:** Build blackout override and verify it freezes status against a real cluster
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

- **`BlackoutEvaluator.isBlackedOut`** — pure `Instant` range containment check across every
  window in `spec.blackout`, either endpoint inclusive. No timezone conversion needed, unlike
  `ScheduleEvaluator`: `BlackoutWindow.start`/`end` are absolute ISO-8601 instants with an
  explicit `Z` offset, not recurring local wall-clock crons. · status: documented
- **`WardenPolicyReconciler` gates on blackout once, before calling schedule evaluation at all**
  — `status.currentProfile` is simply left untouched for that reconcile when blacked out, rather
  than being computed and then discarded. This is also the single point where a future
  guardrail/metric veto (M4) would plug in. · status: documented
- **Proving a "never happens" property in a live cluster needs a fixed wait, not a poll-until**
  — `verify-wardenpolicy-reconciler.sh`'s blackout scenario sleeps 5s (giving several real
  reconcile cycles a chance to run) before asserting `status.currentProfile` is still unset;
  poll-until-truthy (the pattern the schedule scenario uses) doesn't work for proving an absence.
  · status: documented


## Decision: blackout freezes status.currentProfile in place, not a switch to a blackout-specific profile

- **where:** `warden-controller/src/main/java/io/github/baokhang83/mnemo/warden/controller/WardenPolicyReconciler.java`
- **why:** The issue frames this as a hard do-not-touch override, and BlackoutWindow has no profile field to switch to. Freezing whatever is currently active (by skipping the status write entirely for the duration of the blackout) is the literal reading of do not touch, and needs no new CRD field.
- **alternative:** Extend BlackoutWindow with its own target profile to force during the window — rejected: contradicts the do-not-touch framing (that's a forced switch, not a freeze) and adds a field the acceptance criteria never asked for.
- **design:** ../design.md
- **constitution:** §1
- **trust:** ✓ verified
