# Session: Formalize the abort path: no-op confirmed, SoftMax-restore rejected, mid-shrink cancel descoped to M4

- **intent:** Formalize the abort path: no-op confirmed, SoftMax-restore rejected, mid-shrink cancel descoped to M4
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

- **`ShrinkOutcome.AbortedVerificationFailed`** — already the safe no-op W-205 asks for on a
  failed RSS gate: `ShrinkSequence` never calls `ResizePort.resizeMemory` in this branch, proven
  by the existing `ShrinkSequenceTest.abortsWithoutTouchingTheCgroupWhenRssDoesNotVerify`. This
  slice added no new code to `ShrinkSequence` itself, only a doc pointer from the record's
  javadoc back to this feature. · status: documented
- **GitHub issue #17** — its acceptance criteria previously conflicted with the shipped W-203
  behavior (asked for a SoftMax restore that was deliberately rejected) and left "load returns
  mid-shrink" as unowned scope. Rewritten to describe the actual shipped behavior and to point
  the cancellation clause explicitly at M4 (W-402, W-403). · status: documented



## Decision: SoftMax restore stays rejected, not implemented

- **where:** `warden-agent/src/main/java/io/github/baokhang83/mnemo/warden/agent/sequence/ShrinkOutcome.java`
- **why:** SoftMaxHeapSize is advisory — it nudges the collector but forces nothing and carries no OOM risk by itself, so restoring it on abort buys no safety. It would need a new getter on HeapController (something to restore from) that nothing else needs yet, and leaving it low is strictly better for a retry: the next attempt starts from a heap already nudged down once.
- **alternative:** Add a HeapController getter and revert SoftMax to its pre-attempt value on abort, matching the issue's literal wording — rejected: the getter would have no other caller, and the restore buys back a rollback with zero safety payoff.
- **design:** ../design.md
- **constitution:** §1
- **trust:** ✓ verified

## Decision: Mid-shrink cancellation descoped to M4, not built here

- **where:** `docs/fluencyloop/features/w-205-abort-path-formalize-the-already-shipped-abort-on-fail/design.md`
- **why:** "Load returns mid-shrink" needs a live cancel signal reaching an in-progress shrink, and nothing in ShrinkSequence or any caller has that today. It's the same class of thing W-203's own design already routed to controller-side principles — a veto (W-402) or emergency-grow trigger (W-403), both M4 tickets that don't exist yet.
- **alternative:** Build a cancellation/interrupt hook into ShrinkSequence now, ahead of any controller-side caller that would drive it — rejected: a hook with no caller is speculative generality, and the real design (what triggers a cancel, how it races the GC step) belongs with the M4 work that defines the signal.
- **design:** ../design.md
- **constitution:** §1
- **trust:** ⚠ not independently verified
