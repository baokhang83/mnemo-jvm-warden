package io.github.baokhang83.mnemo.warden.controller.boost;

import io.fabric8.kubernetes.api.model.ConditionBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.baokhang83.mnemo.warden.controller.intent.CorrelatedIntentEmitter;
import io.github.baokhang83.mnemo.warden.controller.intent.PreparedTransitionStore;
import io.github.baokhang83.mnemo.warden.controller.schedule.BlackoutEvaluator;
import io.github.baokhang83.mnemo.warden.crd.*;
import io.javaoperatorsdk.operator.api.reconciler.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Lifecycle owner. Resource intent remains exclusively in WardenPolicyReconciler. */
@ControllerConfiguration(maxReconciliationInterval = @MaxReconciliationInterval(interval = 10, timeUnit = TimeUnit.SECONDS))
public final class WardenBoostReconciler implements Reconciler<WardenBoost>, Cleaner<WardenBoost> {
  private final Clock clock;
  private final java.util.function.Predicate<KubernetesClient> topology;
  public WardenBoostReconciler() { this(Clock.systemUTC(), ControllerTopology::singleController); }
  public WardenBoostReconciler(Clock clock, java.util.function.Predicate<KubernetesClient> topology) {
    this.clock = Objects.requireNonNull(clock); this.topology = Objects.requireNonNull(topology);
  }

  @Override public UpdateControl<WardenBoost> reconcile(WardenBoost boost, Context<WardenBoost> context) {
    advance(context.getClient(), boost);
    return UpdateControl.noUpdate();
  }

  @Override public DeleteControl cleanup(WardenBoost boost, Context<WardenBoost> context) {
    var client = context.getClient();
    var pod = pod(client, boost);
    if (boost.getStatus() == null || boost.getStatus().getPhase() == BoostPhase.Pending
        || boost.getStatus().getPhase() == BoostPhase.Rejected) {
      if (pod != null && boost.getMetadata().getUid().equals(TargetClaim.owner(pod)))
        TargetClaim.release(client, pod, boost.getMetadata().getUid());
      return DeleteControl.defaultDelete();
    }
    if (pod == null || !Objects.equals(boost.getSpec().getPodRef().getUid(), pod.getMetadata().getUid()))
      return DeleteControl.defaultDelete(); // The bound pod instance is gone.
    if (boost.getStatus().getPhase() == BoostPhase.Orphaned && replacedContainer(pod, boost.getSpec().getContainer(), boost.getStatus().getContainerId())) {
      finishClaim(client, boost, pod);
      return DeleteControl.defaultDelete();
    }
    if (boost.getStatus().getPhase() == BoostPhase.Completed) {
      finishClaim(client, boost, pod);
      return DeleteControl.defaultDelete();
    }
    // Deletion is not proof that application work stopped. Keep the finalizer and floor.
    advance(client, boost);
    return DeleteControl.noFinalizerRemoval().rescheduleAfter(10_000);
  }

  void advance(KubernetesClient client, WardenBoost boost) {
    var meta = boost.getMetadata();
    var spec = boost.getSpec();
    var status = boost.getStatus();
    var phase = status == null || status.getPhase() == null ? BoostPhase.Pending : status.getPhase();
    Pod pod = pod(client, boost);
    if (phase == BoostPhase.Completed) { finishClaim(client, boost, pod); return; }
    if (phase == BoostPhase.Rejected) {
      if (pod != null && meta.getUid().equals(TargetClaim.owner(pod))) TargetClaim.release(client, pod, meta.getUid());
      return;
    }
    if (phase == BoostPhase.Pending) {
      if (topology.test(client)) admit(client, boost, pod); else holdTopology(client, boost);
      return;
    }
    Instant now = clock.instant();
    if (pod == null || !Objects.equals(spec.getPodRef().getUid(), pod.getMetadata().getUid())
        || (phase != BoostPhase.Orphaned && !sameContainer(pod, spec.getContainer(), status.getContainerId()))) {
      if (phase != BoostPhase.Orphaned) {
        var next = copy(client, status); next.setPhase(BoostPhase.Orphaned);
        condition(next, "TargetPresent", false, "TargetInstanceChanged", now);
        BoostStatusStore.compareAndSet(client, boost, next);
      }
      return;
    }
    if (phase == BoostPhase.Orphaned) {
      if (replacedContainer(pod, spec.getContainer(), status.getContainerId())) {
        finishClaim(client, boost, pod);
        if (status.getConditions() == null || status.getConditions().stream().noneMatch(c -> "Released".equals(c.getType()) && "True".equals(c.getStatus()))) {
          var released = copy(client, status); condition(released, "Released", true, "InstanceGone", now);
          BoostStatusStore.compareAndSet(client, boost, released);
        }
      }
      return;
    }
    if (!meta.getUid().equals(TargetClaim.owner(pod))) return;
    var policy = client.resources(WardenPolicy.class).inNamespace(meta.getNamespace()).withName(spec.getPolicyRef()).get();
    boolean policyMatches = policy != null && policy.getMetadata() != null
        && Objects.equals(policy.getMetadata().getUid(), status.getPolicyUid());
    boolean blackout = !policyMatches || BlackoutEvaluator.isBlackedOut(policy.getSpec(), now);
    if (phase == BoostPhase.Preparing && policyMatches
        && !Objects.equals(status.getPolicyGeneration(), policy.getMetadata().getGeneration())) {
      // A later policy may demand a different target. Never grant using an old preparation.
      var changed = copy(client, status); changed.setPhase(BoostPhase.Failed);
      condition(changed, "Ready", false, "PolicyChanged", now);
      BoostStatusStore.compareAndSet(client, boost, changed);
      return;
    }
    boolean evidence = false;
    if (status.getPreparedIntent() != null) {
      var intent = PreparedTransitionStore.current(boost);
      evidence = CorrelatedIntentEmitter.evidence(pod, intent).isPresent();
    }
    boolean releaseTarget = status.getPreparedIntent() != null && status.getReleaseBaselineTransitionId() != null
        && !status.getReleaseBaselineTransitionId().equals(status.getTransitionId()) && policyMatches
        && coversCurrentPolicy(policy, PreparedTransitionStore.current(boost), now);
    Instant acquisition = parse(status.getAcquisitionDeadline());
    Instant execution = parse(status.getExecutionDeadline());
    var decision = BoostLifecycle.evaluate(new BoostLifecycle.Observation(phase, true, true,
        Boolean.TRUE.equals(spec.getCompletionRequested()) || (phase == BoostPhase.Failed && status.getGrantedAt() == null),
        blackout, evidence, releaseTarget, acquisition, execution, now));
    if (decision.phase() == phase) {
      if (phase == BoostPhase.Preparing || phase == BoostPhase.Releasing)
        reportWaiting(client, boost, pod, decision.reason(), now);
      return;
    }
    var next = copy(client, status);
    next.setPhase(decision.phase());
    if (decision.phase() == BoostPhase.Granted) {
      if (!topology.test(client)) { holdTopology(client, boost); return; }
      if (next.getMaxDuration() == null || !coversCurrentPolicy(policy, PreparedTransitionStore.current(boost), now)) return;
      Duration max = Duration.parse(next.getMaxDuration());
      if (max.isNegative() || max.isZero()) return;
      next.setGrantedAt(now.toString()); next.setExecutionDeadline(now.plus(max).toString());
      recordApplied(next, PreparedTransitionStore.current(boost));
      condition(next, "Ready", true, "Prepared", now);
    } else if (decision.phase() == BoostPhase.Releasing) {
      // An unprepared request has not changed the pod; finish without inventing evidence.
      if (next.getPreparedIntent() == null) {
        next.setPhase(BoostPhase.Completed); next.setCompletedAt(now.toString());
        condition(next, "Released", true, "NoTransition", now);
        condition(next, "Ready", false, "Completed", now);
      } else {
        next.setReleaseBaselineTransitionId(next.getTransitionId());
        condition(next, "WorkFinished", true, decision.reason(), now);
        condition(next, "Ready", false, "Releasing", now);
      }
    } else if (decision.phase() == BoostPhase.Completed) {
      next.setCompletedAt(now.toString());
      recordApplied(next, PreparedTransitionStore.current(boost));
      condition(next, "Released", true, "Applied", now);
      condition(next, "Ready", false, "Completed", now);
    } else if (decision.phase() == BoostPhase.Failed || decision.phase() == BoostPhase.Expired) {
      condition(next, "Ready", false, decision.reason(), now);
    }
    BoostStatusStore.compareAndSet(client, boost, next);
  }

  private void admit(KubernetesClient client, WardenBoost boost, Pod pod) {
    var meta = boost.getMetadata(); var spec = boost.getSpec(); Instant now = clock.instant();
    var policy = client.resources(WardenPolicy.class).inNamespace(meta.getNamespace()).withName(spec.getPolicyRef()).get();
    BoostAdmission.Snapshot snapshot;
    try {
      if (pod == null || policy == null) throw new IllegalArgumentException("TargetOrPolicyMissing");
      snapshot = BoostAdmission.validate(client, boost, policy, pod, now);
    } catch (io.fabric8.kubernetes.client.KubernetesClientException e) {
      throw e; // API failures are retried, not recorded as an invalid request.
    } catch (RuntimeException e) {
      if ("Blackout".equals(e.getMessage()) || "ContainerNotRunning".equals(e.getMessage())
          || "AppliedAllocationUnavailable".equals(e.getMessage())) {
        holdPending(client, boost, e.getMessage()); return;
      }
      var rejected = new WardenBoostStatus(); rejected.setPhase(BoostPhase.Rejected);
      String message = Objects.toString(e.getMessage(), "InvalidRequest");
      String reason = java.util.Set.of("AcquisitionTimeout", "TargetNotOptedIn", "PodNotInPolicyTarget",
          "ExistingCorrelatedIntent", "IdentityMismatch", "UnknownBoost", "InvalidFloor",
          "TargetOrPolicyMissing", "AlreadyCompleted").contains(message) ? message : "InvalidRequest";
      condition(rejected, "Ready", false, reason, now);
      BoostStatusStore.compareAndSet(client, boost, rejected);
      return;
    }
    if (!TargetClaim.acquire(client, pod, meta.getUid())) {
      var rejected = new WardenBoostStatus(); rejected.setPhase(BoostPhase.Rejected);
      condition(rejected, "Ready", false, "TargetClaimed", now);
      BoostStatusStore.compareAndSet(client, boost, rejected);
      return;
    }
    var next = new WardenBoostStatus(); next.setPhase(BoostPhase.Preparing);
    next.setObservedGeneration(meta.getGeneration()); next.setPolicyUid(snapshot.policyUid());
    next.setPolicyGeneration(snapshot.policyGeneration()); next.setPodUid(snapshot.podUid());
    next.setContainerId(snapshot.containerId()); next.setRequestFloor(Long.toString(snapshot.requestFloor()));
    next.setLimitFloor(Long.toString(snapshot.limitFloor())); next.setHeapTarget(Long.toString(snapshot.heapTarget()));
    next.setAcquisitionDeadline(snapshot.acquisitionDeadline().toString()); next.setMaxDuration(snapshot.maxDuration().toString());
    next.setTransitionId(UUID.randomUUID().toString());
    condition(next, "Ready", false, "Preparing", now);
    BoostStatusStore.compareAndSet(client, boost, next);
  }

  private static void reportWaiting(KubernetesClient client, WardenBoost boost, Pod pod,
      String fallback, Instant now) {
    String reason = fallback;
    var status = boost.getStatus();
    var annotations = pod.getMetadata().getAnnotations();
    if (!"Blackout".equals(fallback) && status.getPreparedIntent() != null && annotations != null) {
      String encoded = annotations.get(io.github.baokhang83.mnemo.warden.protocol.ResizeIntent.ACK_ANNOTATION);
      if (encoded != null) {
        try {
          var ack = io.github.baokhang83.mnemo.warden.protocol.ResizeAcknowledgement.decode(encoded);
          var current = PreparedTransitionStore.current(boost);
          if (!ack.intent().equals(current)) reason = "StaleAcknowledgement";
          else if (!ack.applied()) {
            if (status.getPhase() == BoostPhase.Releasing) reason = "ReleaseBlocked";
            else if ("UnsupportedJvm".equals(ack.reason())) reason = "UnsupportedJvm";
            else reason = "PreparationFailed";
          }
        } catch (IllegalArgumentException e) { reason = "StaleAcknowledgement"; }
      }
    }
    if (status.getConditions() != null) {
      String observedReason = reason;
      if (status.getConditions().stream().anyMatch(c -> "Ready".equals(c.getType())
          && "False".equals(c.getStatus()) && observedReason.equals(c.getReason()))) return;
    }
    var next = copy(client, status); condition(next, "Ready", false, reason, now);
    BoostStatusStore.compareAndSet(client, boost, next);
  }

  private void holdTopology(KubernetesClient client, WardenBoost boost) {
    holdPending(client, boost, "UnsupportedControllerTopology");
  }
  private void holdPending(KubernetesClient client, WardenBoost boost, String reason) {
    var current = boost.getStatus();
    if (current != null && current.getConditions() != null && current.getConditions().stream()
        .anyMatch(c -> "Ready".equals(c.getType()) && reason.equals(c.getReason()))) return;
    var next = current == null ? new WardenBoostStatus() : copy(client, current);
    if (next.getPhase() == null) next.setPhase(BoostPhase.Pending);
    condition(next, "Ready", false, reason, clock.instant());
    BoostStatusStore.compareAndSet(client, boost, next);
  }

  private static void finishClaim(KubernetesClient client, WardenBoost boost, Pod pod) {
    if (pod == null || !Objects.equals(pod.getMetadata().getUid(), boost.getSpec().getPodRef().getUid())) return;
    String owner = TargetClaim.owner(pod);
    if (owner == null) return;
    if (!owner.equals(boost.getMetadata().getUid())) throw new IllegalStateException("claim belongs to another request");
    TargetClaim.finish(client, pod, owner, boost.getStatus().getPreparedIntent());
  }

  private static Pod pod(KubernetesClient client, WardenBoost boost) {
    return client.pods().inNamespace(boost.getMetadata().getNamespace()).withName(boost.getSpec().getPodRef().getName()).get();
  }
  private static boolean sameContainer(Pod pod, String name, String id) {
    return pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null
        && pod.getStatus().getContainerStatuses().stream().anyMatch(c -> name.equals(c.getName())
            && Objects.equals(id, c.getContainerID()) && c.getState() != null && c.getState().getRunning() != null);
  }
  /** Do not grant from evidence for an intent overtaken by the current schedule/profile. */
  private static boolean coversCurrentPolicy(WardenPolicy policy,
      io.github.baokhang83.mnemo.warden.protocol.ResizeIntent intent, Instant now) {
    try {
      var spec = policy.getSpec();
      var current = io.github.baokhang83.mnemo.warden.controller.schedule.ScheduleEvaluator
          .currentProfileWithLeadTime(spec, now);
      if (current.isEmpty() || spec.getProfiles() == null) return false;
      var names = new java.util.HashSet<String>(); names.add(current.get());
      if (policy.getStatus() != null && policy.getStatus().getCurrentProfile() != null)
        names.add(policy.getStatus().getCurrentProfile());
      for (String name : names) {
        var profile = spec.getProfiles().get(name);
        if (profile == null || profile.getRequest() == null || profile.getLimit() == null
            || io.github.baokhang83.mnemo.warden.controller.schedule.ResourceQuantity.parseBytes(profile.getRequest()) > intent.requestBytes()
            || io.github.baokhang83.mnemo.warden.controller.schedule.ResourceQuantity.parseBytes(profile.getLimit()) > intent.limitBytes()
            || (profile.getHeapTarget() != null && io.github.baokhang83.mnemo.warden.controller.schedule.ResourceQuantity.parseBytes(profile.getHeapTarget()) > intent.heapBytes())) return false;
      }
      return true;
    } catch (RuntimeException e) { return false; }
  }
  private static boolean replacedContainer(Pod pod, String name, String originalId) {
    return pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null
        && pod.getStatus().getContainerStatuses().stream().anyMatch(c -> name.equals(c.getName())
            && c.getContainerID() != null && !Objects.equals(originalId, c.getContainerID()));
  }
  private static WardenBoostStatus copy(KubernetesClient client, WardenBoostStatus status) {
    return client.getKubernetesSerialization().clone(status);
  }
  private static Instant parse(String value) { return value == null ? null : Instant.parse(value); }
  private static void recordApplied(WardenBoostStatus status, io.github.baokhang83.mnemo.warden.protocol.ResizeIntent intent) {
    status.setAppliedRequest(Long.toString(intent.requestBytes()));
    status.setAppliedLimit(Long.toString(intent.limitBytes()));
    status.setAppliedHeapTarget(Long.toString(intent.heapBytes()));
  }
  private static void condition(WardenBoostStatus status, String type, boolean value, String reason, Instant now) {
    var conditions = status.getConditions() == null ? new java.util.ArrayList<io.fabric8.kubernetes.api.model.Condition>()
        : new java.util.ArrayList<>(status.getConditions());
    conditions.removeIf(c -> type.equals(c.getType()));
    conditions.add(new ConditionBuilder().withType(type).withStatus(value ? "True" : "False")
        .withReason(reason).withMessage(reason).withLastTransitionTime(now.toString()).build());
    status.setConditions(conditions);
  }
}
