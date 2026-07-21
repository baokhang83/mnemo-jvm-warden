package io.github.baokhang83.mnemo.warden.controller;

import io.github.baokhang83.mnemo.warden.controller.guardrail.EmergencyGrowEvaluator;
import io.github.baokhang83.mnemo.warden.controller.guardrail.ShrinkVetoEvaluator;
import io.github.baokhang83.mnemo.warden.controller.intent.IntentEmitter;
import io.github.baokhang83.mnemo.warden.controller.metrics.PrometheusMetricSource;
import io.github.baokhang83.mnemo.warden.controller.schedule.BlackoutEvaluator;
import io.github.baokhang83.mnemo.warden.controller.schedule.ScheduleEvaluator;
import io.github.baokhang83.mnemo.warden.crd.ResourceProfile;
import io.github.baokhang83.mnemo.warden.crd.WardenPolicy;
import io.github.baokhang83.mnemo.warden.crd.WardenPolicyStatus;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.MaxReconciliationInterval;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches {@code WardenPolicy} objects, patches {@code status.currentProfile} back
 * (W-302/W-303/W-304), PATCHes the target pod's own annotations with the resolved profile
 * (W-306) so {@code warden-agent}'s {@code IntentWatcher} can drive a real resize, evaluates
 * {@code spec.guardrail.metric} against Prometheus into {@code status.currentMetricValue}
 * (W-401), vetoes a schedule-resolved shrink candidate when that metric isn't verified quiet
 * (W-402, via {@link ShrinkVetoEvaluator}), and forces an immediate grow &mdash; bypassing the
 * schedule for that reconcile entirely &mdash; when the metric spikes past {@code
 * guardrail.emergencyGrowAbove} (W-403, via {@link EmergencyGrowEvaluator}).
 *
 * <p>{@link BlackoutEvaluator} (W-305) gates the schedule-driven writes entirely: while blacked
 * out, neither {@code status.currentProfile} nor the target pod's intent annotations change, a
 * hard "do not touch" override, and neither a schedule candidate nor an emergency grow is even
 * resolved. Inside that block, {@link EmergencyGrowEvaluator} is checked *first*: when it finds a
 * grow target, {@link ScheduleEvaluator#currentProfileWithLeadTime} is never even called for that
 * reconcile &mdash; the literal "bypasses the calendar." Only when it doesn't fire does the normal
 * schedule path run, where {@link ShrinkVetoEvaluator} is a narrower gate that only fires once a
 * schedule candidate is known, so it can tell a shrink from a grow. Metric *observation* itself
 * stays unconditional — it's passive telemetry, not a shrink/grow action, so blackout doesn't
 * apply to it, and it runs first each reconcile so both guardrails can use this reconcile's own
 * fresh reading (see the W-402/W-403 design docs; W-404 documents the full three-way precedence).
 *
 * <p>Intent emission and metric evaluation are each isolated in their own {@code try}/{@code
 * catch} (constitution §12): a target pod that doesn't exist, or a Prometheus query failure, must
 * not stop {@code status.currentProfile} from reflecting the schedule's actual decision &mdash;
 * the intent-emission case was caught directly by a real cluster run during W-306.
 *
 * <p>{@code maxReconciliationInterval} (#69): for a {@code Deployment}/{@code StatefulSet}
 * {@code targetRef}, a pod created by a later rollout won't have the intent annotation until
 * something re-triggers {@code reconcile()} — a 30s periodic resync catches it well within the
 * schedule's own minute-level grain, without a per-policy dynamic secondary-resource watch (which
 * would need each policy's target selector known statically at controller startup; it isn't).
 * The same resync is also W-401's metric evaluation interval — one clock, not two.
 */
@ControllerConfiguration(maxReconciliationInterval = @MaxReconciliationInterval(interval = 30, timeUnit = TimeUnit.SECONDS))
public class WardenPolicyReconciler implements Reconciler<WardenPolicy> {

  private static final Logger log = LoggerFactory.getLogger(WardenPolicyReconciler.class);

  private final ControllerConfig config;
  private final HttpClient httpClient = HttpClient.newHttpClient();

  public WardenPolicyReconciler(ControllerConfig config) {
    this.config = config;
  }

  @Override
  public UpdateControl<WardenPolicy> reconcile(WardenPolicy policy, Context<WardenPolicy> context) {
    Instant now = Instant.now();
    Double metricValue = evaluateMetric(policy);
    if (!BlackoutEvaluator.isBlackedOut(policy.getSpec(), now)) {
      Optional<String> emergencyProfile = EmergencyGrowEvaluator.emergencyProfile(policy.getSpec(), metricValue);
      if (emergencyProfile.isPresent()) {
        applyEmergencyGrow(policy, context, emergencyProfile.get(), metricValue);
      } else {
        ScheduleEvaluator.currentProfileWithLeadTime(policy.getSpec(), now)
            .ifPresent(candidateProfile -> applyScheduleDecision(policy, context, candidateProfile, metricValue));
      }
    }
    return UpdateControl.patchStatus(policy);
  }

  private void applyEmergencyGrow(
      WardenPolicy policy, Context<WardenPolicy> context, String emergencyProfile, Double metricValue) {
    ensureStatus(policy);
    log.info(
        "guardrail forced emergency grow for WardenPolicy {}/{}: metric={} exceeds emergencyGrowAbove, growing to {}",
        policy.getMetadata().getNamespace(),
        policy.getMetadata().getName(),
        metricValue,
        emergencyProfile);
    policy.getStatus().setCurrentProfile(emergencyProfile);
    emitIntent(policy, context, emergencyProfile);
  }

  private void applyScheduleDecision(
      WardenPolicy policy, Context<WardenPolicy> context, String candidateProfile, Double metricValue) {
    ensureStatus(policy);
    String previousProfile = policy.getStatus().getCurrentProfile();
    if (ShrinkVetoEvaluator.vetoesShrink(policy.getSpec(), previousProfile, candidateProfile, metricValue)) {
      log.info(
          "guardrail vetoed shrink for WardenPolicy {}/{}: metric={} not verified below shrinkBelow, staying on {}",
          policy.getMetadata().getNamespace(),
          policy.getMetadata().getName(),
          metricValue,
          previousProfile);
      return;
    }
    policy.getStatus().setCurrentProfile(candidateProfile);
    emitIntent(policy, context, candidateProfile);
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

  /**
   * Returns this reconcile's freshly evaluated metric value (also recorded to {@code
   * status.currentMetricValue}), or {@code null} if no guardrail is configured, the query is
   * blank, or evaluation fails — {@link ShrinkVetoEvaluator} reads {@code null} as "not verified
   * quiet," not "no reading needed."
   */
  private Double evaluateMetric(WardenPolicy policy) {
    if (config.prometheusUri().isEmpty()) {
      return null;
    }
    String promQl = policy.getSpec().getGuardrail() == null ? null : policy.getSpec().getGuardrail().getMetric();
    if (promQl == null || promQl.isBlank()) {
      return null;
    }
    try {
      OptionalDouble result = PrometheusMetricSource.query(httpClient, config.prometheusUri().get(), promQl);
      if (result.isEmpty()) {
        return null;
      }
      ensureStatus(policy);
      policy.getStatus().setCurrentMetricValue(result.getAsDouble());
      return result.getAsDouble();
    } catch (Exception e) {
      log.warn(
          "failed to evaluate guardrail metric for WardenPolicy {}/{} (status.currentProfile still updated): {}",
          policy.getMetadata().getNamespace(),
          policy.getMetadata().getName(),
          e.getMessage());
      return null;
    }
  }

  private static void ensureStatus(WardenPolicy policy) {
    if (policy.getStatus() == null) {
      policy.setStatus(new WardenPolicyStatus());
    }
  }
}
