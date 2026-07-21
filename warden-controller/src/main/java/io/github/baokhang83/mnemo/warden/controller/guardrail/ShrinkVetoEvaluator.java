package io.github.baokhang83.mnemo.warden.controller.guardrail;

import io.github.baokhang83.mnemo.warden.controller.schedule.ScheduleEvaluator;
import io.github.baokhang83.mnemo.warden.crd.Guardrail;
import io.github.baokhang83.mnemo.warden.crd.WardenPolicySpec;

/**
 * Whether a schedule-resolved shrink candidate must be blocked because live traffic isn't
 * verified quiet &mdash; W-402's {@code shrinkBelow} veto. Same shape as {@link
 * io.github.baokhang83.mnemo.warden.controller.schedule.BlackoutEvaluator}: a pure static
 * function of the spec plus the reconcile's own inputs, no state of its own.
 *
 * <p>Only a <em>shrink</em> is ever vetoed: direction is classified by comparing {@code
 * candidateProfile}'s {@code limit} against {@code previousProfile}'s (the profile {@code
 * status.currentProfile} already reflects before this reconcile) via {@link
 * ScheduleEvaluator#limitBytes}, the same comparison {@link ScheduleEvaluator} already makes
 * internally to pick a lead time (W-304).
 *
 * <p>A missing metric reading is treated as <b>not verified quiet</b>, not as "no guardrail" —
 * fail-closed, deliberately, once {@code shrinkBelow} is configured: silence can't prove traffic
 * is low, and constitution &sect;5 ("no unverified shrink") is exactly the principle that a
 * gated action needs positive proof, not absence of a contrary signal. This doesn't conflict with
 * W-401's own failure isolation (constitution &sect;12) &mdash; that guarantees {@code
 * status.currentProfile} stays correct through a Prometheus outage; it says nothing about
 * whether a shrink should proceed without a reading. See the design doc.
 */
public final class ShrinkVetoEvaluator {

  private ShrinkVetoEvaluator() {}

  public static boolean vetoesShrink(
      WardenPolicySpec spec, String previousProfile, String candidateProfile, Double metricValue) {
    Guardrail guardrail = spec.getGuardrail();
    if (guardrail == null || guardrail.getShrinkBelow() == null || guardrail.getShrinkBelow().isBlank()) {
      return false;
    }
    if (previousProfile == null) {
      return false;
    }

    Long previousBytes = ScheduleEvaluator.limitBytes(spec, previousProfile);
    Long candidateBytes = ScheduleEvaluator.limitBytes(spec, candidateProfile);
    if (previousBytes == null || candidateBytes == null || candidateBytes >= previousBytes) {
      return false;
    }

    if (metricValue == null) {
      return true;
    }
    return metricValue >= Double.parseDouble(guardrail.getShrinkBelow());
  }
}
