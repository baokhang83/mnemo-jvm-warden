package io.github.baokhang83.mnemo.warden.controller;

import io.github.baokhang83.mnemo.warden.crd.ResourceProfile;
import io.github.baokhang83.mnemo.warden.crd.WardenPolicy;
import io.github.baokhang83.mnemo.warden.crd.WardenPolicyStatus;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.util.Map;

/**
 * Watches {@code WardenPolicy} objects and patches {@code status.currentProfile} back &mdash;
 * the skeleton W-302 asks for. {@link #placeholderCurrentProfile} is deliberately not real
 * schedule evaluation: it picks the alphabetically-first key of {@code spec.profiles} so the
 * watch &rarr; reconcile &rarr; status-patch loop is provably working end-to-end, and W-303
 * replaces just that one method with real cron evaluation behind the same {@link
 * UpdateControl#patchStatus} call &mdash; nothing else in this class needs to change shape when
 * that lands.
 */
@ControllerConfiguration
public class WardenPolicyReconciler implements Reconciler<WardenPolicy> {

  @Override
  public UpdateControl<WardenPolicy> reconcile(WardenPolicy policy, Context<WardenPolicy> context) {
    if (policy.getStatus() == null) {
      policy.setStatus(new WardenPolicyStatus());
    }
    policy.getStatus().setCurrentProfile(placeholderCurrentProfile(policy));
    return UpdateControl.patchStatus(policy);
  }

  /**
   * Placeholder for W-303's real schedule evaluation: the alphabetically-first {@code
   * spec.profiles} key, or {@code null} if none are declared. Package-private so {@code
   * WardenPolicyReconcilerTest} can exercise the selection logic directly, without a cluster.
   */
  static String placeholderCurrentProfile(WardenPolicy policy) {
    Map<String, ResourceProfile> profiles = policy.getSpec().getProfiles();
    if (profiles == null || profiles.isEmpty()) {
      return null;
    }
    return profiles.keySet().stream().sorted().findFirst().orElse(null);
  }
}
