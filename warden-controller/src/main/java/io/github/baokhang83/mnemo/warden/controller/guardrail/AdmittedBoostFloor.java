package io.github.baokhang83.mnemo.warden.controller.guardrail;

import io.github.baokhang83.mnemo.warden.controller.schedule.ResourceQuantity;
import io.github.baokhang83.mnemo.warden.crd.BoostPhase;
import io.github.baokhang83.mnemo.warden.crd.WardenBoost;
import java.util.Objects;
import java.util.Optional;

/** Reads a persisted admission snapshot; callers must already authorize the request target. */
public final class AdmittedBoostFloor {
  private AdmittedBoostFloor() {}

  public record Snapshot(BoostTargetResolver.Memory memory, long heapBytes, String requestUid,
                         String transitionId, String podUid, String containerId) {}

  /**
   * An admitted request keeps its floor through preparation, grant and expiry. A release
   * phase may drop it only once the controller has recorded WorkFinished=True. Incomplete
   * snapshots fail closed instead of being treated as absent. This is a reader; it does not
   * create claims, authorize callers, or change status.
   */
  public static Optional<Snapshot> read(WardenBoost boost, String policyUid, String podUid,
                                        String containerName, String containerId) {
    Objects.requireNonNull(boost);
    var spec = Objects.requireNonNull(boost.getSpec(), "missing request spec");
    var status = boost.getStatus();
    if (status == null || status.getPhase() == null || status.getPhase() == BoostPhase.Pending
        || status.getPhase() == BoostPhase.Rejected) return Optional.empty();
    if (spec.getPodRef() == null || !podUid.equals(spec.getPodRef().getUid())
        || !containerName.equals(spec.getContainer())) return Optional.empty();
    if (!policyUid.equals(status.getPolicyUid()) || !podUid.equals(status.getPodUid())
        || !containerId.equals(status.getContainerId()))
      throw new IllegalStateException("admitted Boost identity mismatch");
    if (status.getPhase() == BoostPhase.Completed && status.getConditions() != null
        && status.getConditions().stream().anyMatch(c -> "Released".equals(c.getType())
            && "True".equals(c.getStatus()))) return Optional.empty();
    // An Orphaned label alone does not prove that this still-observed instance disappeared.
    if (status.getPhase() == BoostPhase.Releasing && status.getConditions() != null
        && status.getConditions().stream().anyMatch(c -> "WorkFinished".equals(c.getType())
            && "True".equals(c.getStatus()))) return Optional.empty();
    String uid = Objects.requireNonNull(boost.getMetadata().getUid(), "missing request UID");
    String transition = Objects.requireNonNull(status.getTransitionId(), "missing transition ID");
    if (uid.isBlank() || transition.isBlank()) throw new IllegalStateException("invalid admitted Boost identity");
    long request = ResourceQuantity.parseBytes(Objects.requireNonNull(status.getRequestFloor(), "missing request floor"));
    long limit = ResourceQuantity.parseBytes(Objects.requireNonNull(status.getLimitFloor(), "missing limit floor"));
    long heap = ResourceQuantity.parseBytes(Objects.requireNonNull(status.getHeapTarget(), "missing heap target"));
    if (heap <= 0 || heap >= limit) throw new IllegalStateException("invalid admitted heap target");
    return Optional.of(new Snapshot(new BoostTargetResolver.Memory(request, limit), heap, uid,
        transition, podUid, containerId));
  }
}
