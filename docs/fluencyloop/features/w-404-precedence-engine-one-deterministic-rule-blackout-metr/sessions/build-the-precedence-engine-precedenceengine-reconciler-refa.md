# Session: Build the precedence engine: PrecedenceEngine + reconciler refactor

- **intent:** Build the precedence engine: PrecedenceEngine + reconciler refactor
- **started:** 2026-07-21

---

## Knowledge transfer

_The ground this slice makes understandable — the components, roles, and conditions explained,
persisted so the fluency doesn't evaporate with the conversation. About the work, never a
person: it records what the code does, not who knew what (the per-developer picture lives in
the uncommitted, global calibration profile)._

- **`PrecedenceEngine.resolve`** — pure static function of `(blackedOut, emergencyGrowProfile, scheduleCandidate, shrinkVetoed)`. Blackout wins outright regardless of the other three. Otherwise an emergency-grow profile, if present, wins over the schedule unconditionally. Otherwise the schedule's candidate applies unless `shrinkVetoed`. All other combinations resolve to `Optional.empty()` (nothing changes this reconcile). It has no knowledge of `WardenPolicySpec`, `Instant`, or any evaluator — only the four already-resolved values — which is what makes an exhaustive truth table over 16 input combinations a fast, dependency-free unit test. · status: documented
- **`WardenPolicyReconciler.reconcile` restructuring** — now computes each of the four signals explicitly, still short-circuiting exactly as before: `emergencyGrowProfile` and `scheduleCandidate`/`shrinkVetoed` are only ever computed when `!blackedOut`, and `scheduleCandidate`/`shrinkVetoed` are only computed when `emergencyGrowProfile` is empty — so the schedule is still never consulted when an emergency grow fires, preserving W-403's "bypasses the calendar" behavior exactly. The four signals are then handed to `PrecedenceEngine.resolve` in one call; the old nested `if`/`else` that encoded the rule itself is gone. · status: documented
- **`applyResolvedProfile`** — replaces the two former methods `applyEmergencyGrow`/`applyScheduleDecision` with one method, since after precedence is resolved there's only one action left: write `status.currentProfile` and emit intent. It takes `isEmergencyGrow` as a plain boolean (derived as `emergencyGrowProfile.isPresent()` at the call site) purely to pick the right log line — this is safe because `PrecedenceEngine`'s own rule guarantees that whenever `emergencyGrowProfile` is present, it *is* the resolved value (rule 2 always wins over the schedule), so the flag can't disagree with what actually got resolved. The shrink-veto log line stays inline in `reconcile()` itself, since a veto is the one outcome where nothing is applied — there's no "resolved profile" to hand a helper method. · status: documented

---

## Decision: reconciler refactored to delegate to PrecedenceEngine, not left as a second copy of the rule

- **where:** `warden-controller/.../WardenPolicyReconciler.java`
- **why:** the acceptance criteria asks for one deterministic, unit-tested rule; reconcile() already implemented blackout > metric > schedule as nested if/else with no unit test (needs a live K8s client), so extracting PrecedenceEngine.resolve as a pure function over the four pre-resolved signals is what makes the rule both singular and testable as an exhaustive truth table
- **alternative:** Add PrecedenceEngine as a documentation-only class while reconcile() keeps its own independent if/else — rejected: two copies of the same precedence rule can silently drift, the same risk W-402's limitBytes reuse was chosen to avoid
- **design:** ../design.md#the-reconciler-becomes-a-thin-caller-not-a-second-copy-of-the-rule
- **constitution:** §1
- **trust:** ✓ verified
