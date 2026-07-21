package io.github.baokhang83.mnemo.warden.controller;

import io.github.baokhang83.mnemo.warden.controller.schedule.BlackoutEvaluator;
import io.github.baokhang83.mnemo.warden.controller.schedule.ScheduleEvaluator;
import io.github.baokhang83.mnemo.warden.crd.WardenPolicy;
import io.github.baokhang83.mnemo.warden.crd.WardenPolicyStatus;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.time.Instant;

/**
 * Watches {@code WardenPolicy} objects and patches {@code status.currentProfile} back, evaluated
 * by {@link ScheduleEvaluator#currentProfileWithLeadTime} (W-303/W-304) &mdash; replacing
 * W-302's placeholder (the alphabetically-first profile key) with the real, cron-schedule-driven
 * selection, fired {@code leadTime} early where applicable.
 *
 * <p>{@link BlackoutEvaluator} (W-305) gates the write: while blacked out, {@code
 * status.currentProfile} is left exactly as it was this reconcile, a hard "do not touch" override
 * that beats the schedule. This is also where a future guardrail/metric veto (M4) would plug in
 * &mdash; the same gate, not a second override to keep in sync.
 */
@ControllerConfiguration
public class WardenPolicyReconciler implements Reconciler<WardenPolicy> {

  @Override
  public UpdateControl<WardenPolicy> reconcile(WardenPolicy policy, Context<WardenPolicy> context) {
    Instant now = Instant.now();
    if (!BlackoutEvaluator.isBlackedOut(policy.getSpec(), now)) {
      ScheduleEvaluator.currentProfileWithLeadTime(policy.getSpec(), now)
          .ifPresent(
              currentProfile -> {
                if (policy.getStatus() == null) {
                  policy.setStatus(new WardenPolicyStatus());
                }
                policy.getStatus().setCurrentProfile(currentProfile);
              });
    }
    return UpdateControl.patchStatus(policy);
  }
}
