package io.github.baokhang83.mnemo.warden.controller;

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
 * by {@link ScheduleEvaluator} (W-303) &mdash; replacing W-302's placeholder (the
 * alphabetically-first profile key) with the real, cron-schedule-driven selection. Nothing else
 * about the reconcile/patch wiring changed shape from W-302: same {@link
 * UpdateControl#patchStatus} call site, a real value instead of a placeholder.
 */
@ControllerConfiguration
public class WardenPolicyReconciler implements Reconciler<WardenPolicy> {

  @Override
  public UpdateControl<WardenPolicy> reconcile(WardenPolicy policy, Context<WardenPolicy> context) {
    ScheduleEvaluator.currentProfile(policy.getSpec(), Instant.now())
        .ifPresent(
            currentProfile -> {
              if (policy.getStatus() == null) {
                policy.setStatus(new WardenPolicyStatus());
              }
              policy.getStatus().setCurrentProfile(currentProfile);
            });
    return UpdateControl.patchStatus(policy);
  }
}
