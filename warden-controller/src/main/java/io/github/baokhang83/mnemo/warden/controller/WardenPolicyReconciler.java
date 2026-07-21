package io.github.baokhang83.mnemo.warden.controller;

import io.github.baokhang83.mnemo.warden.controller.intent.IntentEmitter;
import io.github.baokhang83.mnemo.warden.controller.schedule.BlackoutEvaluator;
import io.github.baokhang83.mnemo.warden.controller.schedule.ScheduleEvaluator;
import io.github.baokhang83.mnemo.warden.crd.ResourceProfile;
import io.github.baokhang83.mnemo.warden.crd.WardenPolicy;
import io.github.baokhang83.mnemo.warden.crd.WardenPolicyStatus;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches {@code WardenPolicy} objects, patches {@code status.currentProfile} back
 * (W-302/W-303/W-304), and PATCHes the target pod's own annotations with the resolved profile
 * (W-306) so {@code warden-agent}'s {@code IntentWatcher} can drive a real resize &mdash; the
 * wire between M3's schedule decision and M2's already-built shrink/grow sequences.
 *
 * <p>{@link BlackoutEvaluator} (W-305) gates both writes: while blacked out, neither {@code
 * status.currentProfile} nor the target pod's intent annotations change, a hard "do not touch"
 * override. This is also where a future guardrail/metric veto (M4) would plug in &mdash; the
 * same gate, not a second override to keep in sync.
 *
 * <p>Intent emission is deliberately isolated in its own {@code try}/{@code catch}: a target pod
 * that doesn't exist (yet, or ever, for a misconfigured {@code targetRef}) or any other PATCH
 * failure must not stop {@code status.currentProfile} from reflecting the schedule's actual
 * decision &mdash; caught directly by a real cluster run where a policy's {@code targetRef}
 * pointed at a pod absent from that test's cluster, and the whole reconcile silently failed to
 * patch status at all until this was isolated.
 */
@ControllerConfiguration
public class WardenPolicyReconciler implements Reconciler<WardenPolicy> {

  private static final Logger log = LoggerFactory.getLogger(WardenPolicyReconciler.class);

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
                emitIntent(policy, context, currentProfile);
              });
    }
    return UpdateControl.patchStatus(policy);
  }

  private void emitIntent(WardenPolicy policy, Context<WardenPolicy> context, String currentProfile) {
    try {
      ResourceProfile profile =
          policy.getSpec().getProfiles() == null ? null : policy.getSpec().getProfiles().get(currentProfile);
      IntentEmitter.emit(context.getClient(), policy.getMetadata().getNamespace(), policy.getSpec().getTargetRef(), profile);
    } catch (RuntimeException e) {
      log.warn(
          "failed to emit intent for WardenPolicy {}/{} (status.currentProfile still updated): {}",
          policy.getMetadata().getNamespace(),
          policy.getMetadata().getName(),
          e.getMessage());
    }
  }
}
