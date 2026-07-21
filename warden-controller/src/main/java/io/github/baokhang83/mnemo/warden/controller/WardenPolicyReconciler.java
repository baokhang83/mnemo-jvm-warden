package io.github.baokhang83.mnemo.warden.controller;

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
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches {@code WardenPolicy} objects, patches {@code status.currentProfile} back
 * (W-302/W-303/W-304), PATCHes the target pod's own annotations with the resolved profile
 * (W-306) so {@code warden-agent}'s {@code IntentWatcher} can drive a real resize, and evaluates
 * {@code spec.guardrail.metric} against Prometheus into {@code status.currentMetricValue}
 * (W-401, purely observational this slice — nothing acts on it yet).
 *
 * <p>{@link BlackoutEvaluator} (W-305) gates the schedule-driven writes: while blacked out,
 * neither {@code status.currentProfile} nor the target pod's intent annotations change, a hard
 * "do not touch" override. This is also where a future guardrail/metric *veto* (W-402/W-403)
 * would plug in &mdash; the same gate, not a second override to keep in sync. Metric
 * *observation* itself is unconditional: it's passive telemetry, not a shrink/grow action, so
 * blackout doesn't apply to it.
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
    if (!BlackoutEvaluator.isBlackedOut(policy.getSpec(), now)) {
      ScheduleEvaluator.currentProfileWithLeadTime(policy.getSpec(), now)
          .ifPresent(
              currentProfile -> {
                ensureStatus(policy);
                policy.getStatus().setCurrentProfile(currentProfile);
                emitIntent(policy, context, currentProfile);
              });
    }
    evaluateMetric(policy);
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

  private void evaluateMetric(WardenPolicy policy) {
    if (config.prometheusUri().isEmpty()) {
      return;
    }
    String promQl = policy.getSpec().getGuardrail() == null ? null : policy.getSpec().getGuardrail().getMetric();
    if (promQl == null || promQl.isBlank()) {
      return;
    }
    try {
      PrometheusMetricSource.query(httpClient, config.prometheusUri().get(), promQl)
          .ifPresent(
              value -> {
                ensureStatus(policy);
                policy.getStatus().setCurrentMetricValue(value);
              });
    } catch (Exception e) {
      log.warn(
          "failed to evaluate guardrail metric for WardenPolicy {}/{} (status.currentProfile still updated): {}",
          policy.getMetadata().getNamespace(),
          policy.getMetadata().getName(),
          e.getMessage());
    }
  }

  private static void ensureStatus(WardenPolicy policy) {
    if (policy.getStatus() == null) {
      policy.setStatus(new WardenPolicyStatus());
    }
  }
}
