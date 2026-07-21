# Session: Build lead-time-aware schedule evaluation and verify it against real cluster

- **intent:** Build lead-time-aware schedule evaluation and verify it against real cluster
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

- **`ScheduleEvaluator.currentProfileWithLeadTime`** — looks one step ahead of the already-solved
  W-303 base case: finds the single soonest upcoming transition (via `nextExecution` across all
  windows), classifies its direction by comparing `ResourceProfile.limit` bytes against the base
  profile's, and fires it early if `now` is already within that direction's `leadTime` of the
  transition's nominal fire time. Falls back to the unadjusted base profile whenever there's no
  established base to compare against, no upcoming transition, or either profile's limit can't be
  resolved. · status: documented
- **`ShorthandDuration`** — parses the `"5m"`/`"10m"`/`"30s"` style strings every sample policy's
  `leadTime` already uses, not ISO-8601 (`"PT5M"`), which `java.time.Duration.parse` requires and
  no sample has ever used. · status: documented
- **`ResourceQuantity`** — a controller-local copy of `warden-agent`'s `K8sQuantity` parsing
  logic, kept deliberately separate rather than shared, to preserve the two modules' existing
  decoupling. · status: documented


## Decision: classify transition direction by comparing ResourceProfile.limit size, not profile names

- **where:** `warden-controller/src/main/java/io/github/baokhang83/mnemo/warden/controller/schedule/ScheduleEvaluator.java`
- **why:** LeadTime has exactly two fields (shrink, warm) but ScheduleWindow carries no direction, and WardenPolicySpec.profiles is a generic map with no naming convention for which key is smaller. Comparing the target profile's limit against the currently-active one's limit generalizes past exactly two profiles and needs no hardcoded profile-name convention like off-peak/peak.
- **alternative:** Require specific profile key names (e.g. must be called off-peak/peak) to determine direction — rejected: simpler but brittle, and contradicts profiles being a generic map with no fixed key set.
- **design:** ../design.md
- **constitution:** §1
- **trust:** ✓ verified

## Decision: one-step-ahead algorithm: classify only the single soonest upcoming transition, not every window

- **where:** `warden-controller/src/main/java/io/github/baokhang83/mnemo/warden/controller/schedule/ScheduleEvaluator.java`
- **why:** Lead-time-shifting every window would require classifying every window's direction relative to whatever precedes it in the cycle — a much bigger problem than the alternating two-profile pattern every example in this repo actually uses. Looking only at the single soonest upcoming transition, classified against the already-solved W-303 base profile, is correct for that common case without speculatively generalizing to multiple simultaneous imminent transitions nothing has asked for yet.
- **alternative:** Fully generalize to lead-time-adjust every window in the schedule, handling arbitrarily many profiles and overlapping upcoming transitions — rejected: substantially more complex for a case this repo's examples and acceptance criteria don't exercise.
- **design:** ../design.md
- **constitution:** §1
- **trust:** ✓ verified

## Decision: controller-local ResourceQuantity copy, not a warden-agent dependency

- **where:** `warden-controller/src/main/java/io/github/baokhang83/mnemo/warden/controller/schedule/ResourceQuantity.java`
- **why:** warden-agent already has K8sQuantity for identical Ki/Mi/Gi parsing, but warden-agent and warden-controller are deliberately decoupled modules (each module's own pom.xml says so explicitly), and the agent's copy is tied to a different problem (confirming a PATCHed value against the API server's normalized echo) than this one (comparing two static spec values). A small local copy keeps the module boundary intact.
- **alternative:** Add a warden-controller dependency on warden-agent to reuse K8sQuantity directly — rejected: avoids ~40 lines of duplication but crosses a module boundary this project has kept deliberately closed everywhere else.
- **design:** ../design.md
- **constitution:** §1
- **trust:** ✓ verified
