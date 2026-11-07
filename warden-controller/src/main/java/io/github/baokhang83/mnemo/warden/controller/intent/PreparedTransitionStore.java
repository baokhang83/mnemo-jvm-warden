package io.github.baokhang83.mnemo.warden.controller.intent;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.github.baokhang83.mnemo.warden.crd.WardenBoost;
import io.github.baokhang83.mnemo.warden.crd.BoostPhase;
import io.github.baokhang83.mnemo.warden.controller.guardrail.AdmittedBoostFloor;
import io.github.baokhang83.mnemo.warden.protocol.JsonString;
import io.github.baokhang83.mnemo.warden.protocol.ResizeIntent;
import java.util.Objects;

/** Atomically records a complete replacement on the Boost status subresource. */
public final class PreparedTransitionStore {
  private PreparedTransitionStore() {}

  /**
   * Persist before publishing to the pod. A conflict must cause a fresh reconcile, not a blind
   * retry. The caller owns authorization, target claiming, and lifecycle validation.
   */
  public static WardenBoost prepare(KubernetesClient client, WardenBoost observed, ResizeIntent intent) {
    Objects.requireNonNull(observed.getMetadata());
    var meta = observed.getMetadata();
    var status = Objects.requireNonNull(observed.getStatus(), "missing admitted status");
    var spec = Objects.requireNonNull(observed.getSpec(), "missing request spec");
    if (!intent.requestUid().equals(meta.getUid()) || !intent.podUid().equals(status.getPodUid())
        || !intent.containerId().equals(status.getContainerId()) || spec.getPodRef() == null
        || !intent.podUid().equals(spec.getPodRef().getUid())
        || !intent.containerName().equals(spec.getContainer()) || meta.getResourceVersion() == null)
      throw new IllegalArgumentException("transition does not match admitted request identity");
    boolean releasing = status.getPhase() == BoostPhase.Releasing && status.getConditions() != null
        && status.getConditions().stream().anyMatch(c -> "WorkFinished".equals(c.getType()) && "True".equals(c.getStatus()));
    if (!releasing && status.getPhase() != BoostPhase.Preparing && status.getPhase() != BoostPhase.Granted
        && status.getPhase() != BoostPhase.Expired)
      throw new IllegalStateException("replacement requires an active admission or verified completion");
    var floor = AdmittedBoostFloor.read(observed, Objects.requireNonNull(status.getPolicyUid()),
        intent.podUid(), intent.containerName(), intent.containerId());
    if (!releasing) {
      var held = floor.orElseThrow();
      if (intent.requestBytes() < held.memory().request() || intent.limitBytes() < held.memory().limit()
          || intent.heapBytes() < held.heapBytes())
        throw new IllegalArgumentException("replacement is below the admitted floor");
    } else if (floor.isPresent()) {
      throw new IllegalStateException("completion was not confirmed");
    }
    if (intent.transitionId().equals(status.getTransitionId())) {
      if (!intent.encode().equals(status.getPreparedIntent()))
        throw new IllegalArgumentException("transition ID reused for a different target");
    }
    String patch = "[{\"op\":\"test\",\"path\":\"/metadata/uid\",\"value\":" + JsonString.quote(meta.getUid())
        + "},{\"op\":\"test\",\"path\":\"/metadata/resourceVersion\",\"value\":" + JsonString.quote(meta.getResourceVersion())
        + "},{\"op\":\"add\",\"path\":\"/status/transitionId\",\"value\":" + JsonString.quote(intent.transitionId())
        + "},{\"op\":\"add\",\"path\":\"/status/preparedIntent\",\"value\":" + JsonString.quote(intent.encode()) + "}]";
    WardenBoost saved = client.resources(WardenBoost.class).inNamespace(meta.getNamespace()).withName(meta.getName())
        .subresource("status").patch(PatchContext.of(PatchType.JSON), patch);
    if (!current(saved).equals(intent)) throw new IllegalStateException("persisted transition differs from request");
    return saved;
  }

  /**
   * Capture the pod before preparing, persist the exact intent, then patch that captured pod
   * using its UID/resourceVersion. A delayed publisher cannot overwrite a newer pod write.
   * A successful status write followed by a failed pod write is recovered by reconciling again.
   * The caller must still serialize target ownership and check current status before granting.
   */
  public static void prepareAndPublish(KubernetesClient client, WardenBoost observed, ResizeIntent intent) {
    var spec = Objects.requireNonNull(observed.getSpec(), "missing request spec");
    var podRef = Objects.requireNonNull(spec.getPodRef(), "missing pod reference");
    var pods = client.pods().inNamespace(observed.getMetadata().getNamespace()).withName(podRef.getName());
    var pod = pods.get();
    if (CorrelatedIntentEmitter.currentContainer(pod, intent).isEmpty()
        || pod.getMetadata().getResourceVersion() == null)
      throw new IllegalStateException("pod instance changed before preparation");
    String patch = CorrelatedIntentEmitter.patch(pod, intent);
    WardenBoost saved = prepare(client, observed, intent);
    var latest = client.resources(WardenBoost.class).inNamespace(saved.getMetadata().getNamespace())
        .withName(saved.getMetadata().getName()).get();
    requireUnchanged(saved, latest, intent);
    pods.patch(PatchContext.of(PatchType.JSON), patch);
  }

  /** Fail closed on any intervening lifecycle or status change, even when target bytes match. */
  static void requireUnchanged(WardenBoost saved, WardenBoost latest, ResizeIntent intent) {
    if (latest == null || latest.getMetadata() == null || saved.getMetadata() == null
        || !Objects.equals(saved.getMetadata().getUid(), latest.getMetadata().getUid())
        || saved.getMetadata().getResourceVersion() == null
        || !saved.getMetadata().getResourceVersion().equals(latest.getMetadata().getResourceVersion())
        || !current(latest).equals(intent))
      throw new IllegalStateException("prepared transition was superseded before publication");
  }

  /** Fail closed on missing, stale, or partially persisted evidence. */
  public static ResizeIntent current(WardenBoost boost) {
    var status = Objects.requireNonNull(boost.getStatus(), "missing status");
    var intent = ResizeIntent.decode(status.getPreparedIntent());
    if (!intent.transitionId().equals(status.getTransitionId())
        || !intent.requestUid().equals(boost.getMetadata().getUid())
        || !intent.podUid().equals(status.getPodUid())
        || !intent.containerId().equals(status.getContainerId()))
      throw new IllegalStateException("prepared transition does not match persisted admission");
    return intent;
  }
}
