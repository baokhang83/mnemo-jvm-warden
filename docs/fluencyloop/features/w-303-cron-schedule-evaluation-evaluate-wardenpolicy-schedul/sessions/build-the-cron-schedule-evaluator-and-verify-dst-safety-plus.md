# Session: Build the cron schedule evaluator and verify DST-safety plus the real-cluster wiring

- **intent:** Build the cron schedule evaluator and verify DST-safety plus the real-cluster wiring
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

- **`ExecutionTime.lastExecution(ZonedDateTime)`** — cron-utils' core query, returning
  `Optional<ZonedDateTime>`. Verified directly against the interface source at the pinned tag
  (9.2.1), not the README summary, since a wrong assumption about the return type (bare
  `ZonedDateTime` vs `Optional`) would have been a compile error worth avoiding upfront rather
  than discovering mid-build. · status: documented
- **A real, historical DST transition proves the safety property better than a synthetic one** —
  the test pins the actual US spring-forward night of March 9→10, 2024 (`America/New_York`)
  rather than a hypothetical future date, so the JDK's own tzdata (not an assumption about how
  DST rules work) is what the test exercises. · status: documented
- **`wardenpolicy-sample-valid.yaml`'s off-peak/peak schedule became unsuitable for real-cluster
  verification once the reconciler stopped using the W-302 placeholder** — its "off-peak wins"
  assertion was only true because off-peak happened to sort alphabetically first; under real
  schedule evaluation, which window is actually "current" depends on wall-clock time when the
  check runs. `wardenpolicy-sample-schedule.yaml`'s `* * * * *` / `0 0 1 1 *` pairing sidesteps
  this by construction — one has always fired within the last minute, the other months ago.
  · status: documented


## Decision: cron-utils for DST-safe schedule evaluation, not hand-rolled cron parsing

- **where:** `warden-controller/src/main/java/io/github/baokhang83/mnemo/warden/controller/schedule/ScheduleEvaluator.java`
- **why:** cron field parsing plus DST-correct last/next-execution arithmetic is a well-known source of subtle bugs (leap years, day-of-week/day-of-month interaction, the skipped/repeated hour on a transition day). cron-utils computes ExecutionTime entirely in java.time.ZonedDateTime, so a cron like 0 22 * * * keeps meaning 22:00 local wall-clock time across a DST shift — verified directly against the ExecutionTime interface source at the pinned tag (9.2.1), not assumed from the README.
- **alternative:** Hand-roll a minimal cron parser and DST-aware last-execution calculation — rejected: a mature, widely-used library gets tested by a much larger surface than this repo could reasonably replicate for a skeleton scheduler, and DST edge cases are exactly where hand-rolled date arithmetic tends to hide bugs.
- **design:** ../design.md
- **constitution:** §1
- **trust:** ✓ verified

## Decision: most-recently-fired schedule window wins, not the next upcoming one

- **where:** `warden-controller/src/main/java/io/github/baokhang83/mnemo/warden/controller/schedule/ScheduleEvaluator.java`
- **why:** Each ScheduleWindow means switch to this profile when its cron fires, so the currently active profile is whichever window's cron most recently fired relative to now — e.g. at 23:00 with windows firing at 22:00 (off-peak) and 07:00 (peak), off-peak is currently active because it fired more recently, even though peak's next occurrence is sooner.
- **alternative:** Pick the profile of the next upcoming window instead — rejected: that describes what's about to happen, not what's currently active, which is what status.currentProfile needs to reflect.
- **design:** ../design.md
- **constitution:** §1
- **trust:** ✓ verified

## Decision: DST test pins a real historical transition (March 9-10, 2024), not a synthetic one

- **where:** `warden-controller/src/test/java/io/github/baokhang83/mnemo/warden/controller/schedule/ScheduleEvaluatorTest.java`
- **why:** A test built from an assumed transition date (e.g. computed from a DST rule description) only proves the code agrees with the test author's own understanding of when DST shifts, not that it's correct against the JDK's real, authoritative tzdata. The actual US spring-forward night of March 9->10, 2024 in America/New_York is a verifiable historical fact any reader can check, so the test exercises real tzdata.
- **alternative:** Construct a synthetic/future DST date by adding a fixed offset to an assumed rule (e.g. second Sunday in March) — rejected: that bakes the test author's assumption about the rule into the test itself, rather than testing against the JDK's real data.
- **design:** ../design.md
- **constitution:** §11
- **trust:** ✓ verified
