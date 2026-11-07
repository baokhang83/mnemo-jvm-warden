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
    if (status.getPhase() != BoostPhase.Preparing && status.getPhase() != BoostPhase.Granted
        && status.getPhase() != BoostPhase.Expired)
      throw new IllegalStateException("replacement requires an active admission");
    var floor = AdmittedBoostFloor.read(observed, Objects.requireNonNull(status.getPolicyUid()),
        intent.podUid(), intent.containerName(), intent.containerId()).orElseThrow();
    if (intent.requestBytes() < floor.memory().request() || intent.limitBytes() < floor.memory().limit()
        || intent.heapBytes() < floor.heapBytes())
      throw new IllegalArgumentException("replacement is below the admitted floor");
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
