# Session: Build the shrinkBelow veto: ShrinkVetoEvaluator + reconciler wiring

- **intent:** Build the shrinkBelow veto: ShrinkVetoEvaluator + reconciler wiring
- **started:** 2026-07-21

---

## Knowledge transfer

_The ground this slice makes understandable — the components, roles, and conditions explained,
persisted so the fluency doesn't evaporate with the conversation. About the work, never a
person: it records what the code does, not who knew what (the per-developer picture lives in
the uncommitted, global calibration profile)._

- **`ShrinkVetoEvaluator.vetoesShrink`** — pure static function of `(spec, previousProfile, candidateProfile, metricValue)`. Returns `false` immediately if `guardrail.shrinkBelow` isn't configured or there's no `previousProfile` to compare against (nothing established to shrink *from* yet). Classifies direction by comparing `limitBytes(candidateProfile)` against `limitBytes(previousProfile)`; only ever vetoes when the candidate is strictly smaller (a real shrink) — a grow or a no-op transition is never touched by `shrinkBelow`. When it *is* a shrink: `metricValue == null` vetoes (fail-closed), otherwise vetoes when `metricValue >= shrinkBelow`. · status: documented
- **`WardenPolicyReconciler.reconcile` ordering** — `evaluateMetric` now runs *before* the blackout/schedule block (previously after), because the veto needs this reconcile's own fresh metric reading, not the value left over in `status.currentMetricValue` from the prior reconcile. `status.currentMetricValue` is still written as a side effect either way, so this reorder is invisible to anything only watching that field. · status: documented
- **`applyScheduleDecision`** — new private method holding what used to be the inline lambda passed to `ScheduleEvaluator.currentProfileWithLeadTime(...).ifPresent(...)`. Reads `status.currentProfile` *before* overwriting it (that read is `previousProfile`, the "what's active right now" the veto compares against), calls `ShrinkVetoEvaluator.vetoesShrink`, and only on a non-veto does it write the new `status.currentProfile` and call `emitIntent`. A veto is a straight early return: nothing about `status.currentProfile` or the target pod's annotations changes for that reconcile — the same "leave whatever's active alone" effect `BlackoutEvaluator` already produces, just reached for a different reason and only after a candidate was resolved (blackout skips resolving one at all). · status: documented
- **`ScheduleEvaluator.limitBytes` visibility** — widened from `private` to `public static`; behavior unchanged, still "resolve `profileName` in `spec.profiles`, parse its `limit` to bytes, `null` if either step fails." Now has two call sites: `ScheduleEvaluator`'s own lead-time direction check (W-304) and `ShrinkVetoEvaluator`'s shrink-direction check (W-402) — same classification question asked in two different contexts. · status: documented

---

## Decision: shrinkBelow veto is fail-closed on a missing metric reading

- **where:** `warden-controller/.../guardrail/ShrinkVetoEvaluator.java`
- **why:** Once shrinkBelow is configured, absence of a reading is not proof traffic is quiet — silence can't stand in for a verified-below-threshold signal, extending constitution §5's 'no unverified shrink' from the RSS gate to this business-level gate.
- **alternative:** Fail-open (missing reading = no guardrail, let the shrink proceed) — rejected: it would let a Prometheus outage silently disable the safety feature an operator explicitly opted into, right when they can least verify traffic is actually low.
- **design:** ../design.md#fail-closed-on-a-missing-reading-the-key-decision
- **constitution:** §5
- **trust:** ✓ verified

## Decision: ScheduleEvaluator.limitBytes widened to public, reused by ShrinkVetoEvaluator

- **where:** `warden-controller/.../schedule/ScheduleEvaluator.java`
- **why:** The exact 'resolve a named profile in spec.profiles, parse its limit to bytes' lookup already existed privately for W-304's lead-time direction classification; the veto needs the identical classification (is the candidate profile smaller than the previous one), so reusing it avoids a second copy of the same CRD-lookup logic in a new class.
- **alternative:** A second private limitBytes copy inside ShrinkVetoEvaluator — rejected: duplicates profile-resolution logic ScheduleEvaluator already owns, and two copies can silently drift (e.g. if the CRD's limit format handling changes).
- **design:** ../design.md#direction-classification-reuses-scheduleevaluatorlimitbytes
- **constitution:** §1
- **trust:** ✓ verified
