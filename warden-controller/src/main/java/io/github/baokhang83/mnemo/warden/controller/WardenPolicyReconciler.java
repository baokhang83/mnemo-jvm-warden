package io.github.baokhang83.mnemo.warden.controller;

import io.github.baokhang83.mnemo.warden.controller.guardrail.EmergencyGrowEvaluator;
import io.github.baokhang83.mnemo.warden.controller.guardrail.PrecedenceEngine;
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
 * <p>{@code reconcile()} computes each of those signals and hands them to {@link
 * PrecedenceEngine#resolve} (W-404), the single place {@code blackout > metric > schedule} is
 * written down. While blacked out, neither a schedule candidate nor an emergency grow is even
 * resolved &mdash; the hard "do not touch" override. Not blacked out, {@link
 * EmergencyGrowEvaluator} is checked first: when it finds a grow target, {@link
 * ScheduleEvaluator#currentProfileWithLeadTime} is never even called for that reconcile &mdash;
 * the literal "bypasses the calendar." Only when it doesn't fire is the schedule consulted, and
 * only then does {@link ShrinkVetoEvaluator} have a candidate to veto. {@link PrecedenceEngine}
 * itself doesn't know any of that context — it just combines whichever of the four signals
 * {@code reconcile()} handed it. Metric *observation* itself stays unconditional — it's passive
 * telemetry, not a shrink/grow action, so blackout doesn't apply to it, and it runs first each
 * reconcile so every guardrail shares this reconcile's own fresh reading (see the
 * W-402/W-403/W-404 design docs).
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
    boolean blackedOut = BlackoutEvaluator.isBlackedOut(policy.getSpec(), now);
    String previousProfile = policy.getStatus() == null ? null : policy.getStatus().getCurrentProfile();

    Optional<String> emergencyGrowProfile = Optional.empty();
    Optional<String> scheduleCandidate = Optional.empty();
    boolean shrinkVetoed = false;
    if (!blackedOut) {
      emergencyGrowProfile = EmergencyGrowEvaluator.emergencyProfile(policy.getSpec(), metricValue);
      if (emergencyGrowProfile.isEmpty()) {
        scheduleCandidate = ScheduleEvaluator.currentProfileWithLeadTime(policy.getSpec(), now);
        shrinkVetoed =
            scheduleCandidate.isPresent()
                && ShrinkVetoEvaluator.vetoesShrink(
                    policy.getSpec(), previousProfile, scheduleCandidate.get(), metricValue);
      }
    }

    Optional<String> resolved = PrecedenceEngine.resolve(blackedOut, emergencyGrowProfile, scheduleCandidate, shrinkVetoed);
    if (resolved.isPresent()) {
      applyResolvedProfile(policy, context, resolved.get(), emergencyGrowProfile.isPresent(), metricValue);
    } else if (shrinkVetoed) {
      log.info(
          "guardrail vetoed shrink for WardenPolicy {}/{}: metric={} not verified below shrinkBelow, staying on {}",
          policy.getMetadata().getNamespace(),
          policy.getMetadata().getName(),
          metricValue,
          previousProfile);
    }
    return UpdateControl.patchStatus(policy);
  }

  private void applyResolvedProfile(
      WardenPolicy policy,
      Context<WardenPolicy> context,
      String resolvedProfile,
      boolean isEmergencyGrow,
      Double metricValue) {
    ensureStatus(policy);
    if (isEmergencyGrow) {
      log.info(
          "guardrail forced emergency grow for WardenPolicy {}/{}: metric={} exceeds emergencyGrowAbove, growing to {}",
          policy.getMetadata().getNamespace(),
          policy.getMetadata().getName(),
          metricValue,
          resolvedProfile);
    }
    policy.getStatus().setCurrentProfile(resolvedProfile);
    emitIntent(policy, context, resolvedProfile);
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
