package io.github.baokhang83.mnemo.warden.controller.guardrail;

import io.github.baokhang83.mnemo.warden.controller.schedule.ScheduleEvaluator;
import io.github.baokhang83.mnemo.warden.crd.Guardrail;
import io.github.baokhang83.mnemo.warden.crd.WardenPolicySpec;
import java.util.Optional;

/**
 * Whether an off-schedule traffic spike must force an immediate grow &mdash; W-403's {@code
 * emergencyGrowAbove} reactive grow. Same shape as {@link ShrinkVetoEvaluator} and {@link
 * io.github.baokhang83.mnemo.warden.controller.schedule.BlackoutEvaluator}: a pure static function
 * of the spec plus the reconcile's own inputs, no state of its own.
 *
 * <p>The grow target is never a new CRD field: it's the profile in {@code spec.profiles} with the
 * largest {@code limit}, resolved via {@link ScheduleEvaluator#limitBytes} (its third call site,
 * after W-304's lead-time direction and W-402's shrink classification). That's the profile
 * declaring the most headroom this policy has &mdash; exactly what "emergency grow" means &mdash;
 * and in the common two-profile off-peak/peak shape it's simply {@code peak}.
 *
 * <p>A missing metric reading returns {@link Optional#empty()}, the mirror of {@link
 * ShrinkVetoEvaluator}'s fail-closed choice: silence isn't evidence of a spike, so there's nothing
 * to react to. When this returns a profile, the reconciler applies it <em>instead of</em>
 * consulting {@link ScheduleEvaluator#currentProfileWithLeadTime} for that reconcile &mdash; the
 * literal "bypasses the calendar" from the acceptance criteria. See the design doc.
 */
public final class EmergencyGrowEvaluator {

  private EmergencyGrowEvaluator() {}

  public static Optional<String> emergencyProfile(WardenPolicySpec spec, Double metricValue) {
    Guardrail guardrail = spec.getGuardrail();
    if (guardrail == null
        || guardrail.getEmergencyGrowAbove() == null
        || guardrail.getEmergencyGrowAbove().isBlank()) {
      return Optional.empty();
    }
    if (metricValue == null) {
      return Optional.empty();
    }
    if (metricValue <= Double.parseDouble(guardrail.getEmergencyGrowAbove())) {
      return Optional.empty();
    }

    return largestProfile(spec);
  }

  private static Optional<String> largestProfile(WardenPolicySpec spec) {
    if (spec.getProfiles() == null) {
      return Optional.empty();
    }

    String largestName = null;
    long largestBytes = -1;
    for (String name : spec.getProfiles().keySet()) {
      Long bytes = ScheduleEvaluator.limitBytes(spec, name);
      if (bytes != null && bytes > largestBytes) {
        largestBytes = bytes;
        largestName = name;
      }
    }
    return Optional.ofNullable(largestName);
  }
}
