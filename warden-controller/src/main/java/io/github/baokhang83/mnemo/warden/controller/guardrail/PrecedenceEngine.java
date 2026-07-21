package io.github.baokhang83.mnemo.warden.controller.guardrail;

import java.util.Optional;

/**
 * W-404's precedence rule: {@code blackout > metric > schedule}, as one pure, deterministic
 * function over the reconcile's four already-resolved signals. {@link
 * io.github.baokhang83.mnemo.warden.controller.schedule.BlackoutEvaluator}, {@link
 * EmergencyGrowEvaluator}, {@link io.github.baokhang83.mnemo.warden.controller.schedule.ScheduleEvaluator},
 * and {@link ShrinkVetoEvaluator} each still own the judgment behind their own signal; this class
 * owns only how those four signals combine into a decision, so the rule exists in exactly one
 * place, testable without a live Kubernetes client.
 *
 * <p>{@code emergencyGrowProfile} and {@code shrinkVetoed} are both facets of the "metric" input:
 * an emergency grow is independent of the schedule, while a veto only makes sense applied to the
 * schedule's own candidate — so the metric's effect travels as a value and a gate, not a single
 * flat enum. See the design doc for the full truth table.
 */
public final class PrecedenceEngine {

  private PrecedenceEngine() {}

  public static Optional<String> resolve(
      boolean blackedOut,
      Optional<String> emergencyGrowProfile,
      Optional<String> scheduleCandidate,
      boolean shrinkVetoed) {
    if (blackedOut) {
      return Optional.empty();
    }
    if (emergencyGrowProfile.isPresent()) {
      return emergencyGrowProfile;
    }
    if (scheduleCandidate.isPresent() && !shrinkVetoed) {
      return scheduleCandidate;
    }
    return Optional.empty();
  }
}
