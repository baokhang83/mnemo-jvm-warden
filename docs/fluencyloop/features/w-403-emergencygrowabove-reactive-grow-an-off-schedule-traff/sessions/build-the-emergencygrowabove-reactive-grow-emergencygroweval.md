# Session: Build the emergencyGrowAbove reactive grow: EmergencyGrowEvaluator + reconciler wiring

- **intent:** Build the emergencyGrowAbove reactive grow: EmergencyGrowEvaluator + reconciler wiring
- **started:** 2026-07-21

---

## Knowledge transfer

_The ground this slice makes understandable — the components, roles, and conditions explained,
persisted so the fluency doesn't evaporate with the conversation. About the work, never a
person: it records what the code does, not who knew what (the per-developer picture lives in
the uncommitted, global calibration profile)._

- **`EmergencyGrowEvaluator.emergencyProfile`** — pure static function of `(spec, metricValue)`. Returns empty immediately if `guardrail.emergencyGrowAbove` isn't configured, if `metricValue` is `null` (fail-open), or if `metricValue` doesn't strictly exceed the threshold. When it does fire, it returns the name of whichever profile in `spec.profiles` resolves to the largest `limit` in bytes (ties keep the first one found; in practice profiles don't tie). · status: documented
- **`WardenPolicyReconciler.reconcile` ordering** — inside the `!isBlackedOut` block, `EmergencyGrowEvaluator.emergencyProfile` is checked *before* `ScheduleEvaluator.currentProfileWithLeadTime`. When it returns a profile, the schedule call is skipped entirely for that reconcile — the schedule's own opinion is never asked for, which is the literal meaning of "bypasses the calendar." Only when it's empty does the normal schedule/shrink-veto path run. `evaluateMetric` still runs unconditionally first (outside the blackout gate), same as W-402, so both guardrails share one fresh reading per reconcile. · status: documented
- **`applyEmergencyGrow`** — new private method, sibling to `applyScheduleDecision`. Unlike that method, there's no veto to check — an emergency grow is unconditional once `EmergencyGrowEvaluator` returns a target — so it just writes `status.currentProfile` and calls `emitIntent` directly, with a log line naming the metric value that triggered it. · status: documented
- **Strict `>` vs. `shrinkBelow`'s `>=`** — `emergencyGrowAbove` uses strictly-greater-than (matches the acceptance criteria's exact wording: "metric > emergencyGrowAbove"), while W-402's `shrinkBelow` veto uses `>=`. This asymmetry is intentional, not an inconsistency: `shrinkBelow` is a safety gate (ties should still block the shrink), `emergencyGrowAbove` is a spike trigger (a metric sitting exactly on the threshold isn't yet "spiking past" it). · status: documented

---

## Decision: grow target inferred as the largest declared profile, no new CRD field

- **where:** `warden-controller/.../guardrail/EmergencyGrowEvaluator.java`
- **why:** spec.profiles already declares every profile's limit; the largest one is exactly 'the most headroom this policy has', which is what an emergency grow means, and in the common off-peak/peak shape it resolves to peak anyway
- **alternative:** New guardrail.emergencyProfile CRD field — rejected: changes the schema W-301 already fixed for a single-purpose knob that's redundant in the case that matters most
- **design:** ../design.md#grow-target-infer-the-largest-declared-profile-not-a-new-crd-field
- **constitution:** §1
- **trust:** ✓ verified

## Decision: emergency grow is fail-open on a missing metric reading

- **where:** `warden-controller/.../guardrail/EmergencyGrowEvaluator.java`
- **why:** missing data is not evidence of a spike, so forcing a grow on silence would be an ungrounded guess rather than caution; this is the deliberate mirror of W-402's fail-closed shrink veto, and both defaults land on the same outcome (keep the pod at least as big as it needs to be) from opposite failure directions
- **alternative:** Fail-closed (missing reading also forces a grow) — rejected: would treat every Prometheus outage as an emergency, growing pods with no actual evidence of a spike
- **design:** ../design.md#missing-metric-reading-never-triggers-an-emergency-grow-fail-open-the-opposite-of-w-402
- **constitution:** §5
- **trust:** ✓ verified
