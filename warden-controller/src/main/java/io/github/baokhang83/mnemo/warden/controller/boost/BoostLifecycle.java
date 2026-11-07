package io.github.baokhang83.mnemo.warden.controller.boost;

import io.github.baokhang83.mnemo.warden.crd.BoostPhase;
import java.time.Instant;
import java.util.Objects;

/** Pure lifecycle decisions. Authorization, claim and evidence must be checked by the caller. */
public final class BoostLifecycle {
  private BoostLifecycle() {}

  public record Observation(BoostPhase phase, boolean targetPresent, boolean sameInstance,
      boolean completionRequested, boolean blackout, boolean matchingEvidence, boolean releaseTargetConfirmed,
      Instant acquisitionDeadline, Instant executionDeadline, Instant now) {
    public Observation {
      Objects.requireNonNull(now, "now");
    }
  }

  public record Decision(BoostPhase phase, String reason, boolean keepFloor) {}

  public static Decision evaluate(Observation o) {
    Objects.requireNonNull(o);
    BoostPhase phase = o.phase() == null ? BoostPhase.Pending : o.phase();
    if (phase == BoostPhase.Completed || phase == BoostPhase.Rejected)
      return new Decision(phase, "Terminal", false);
    if (phase != BoostPhase.Pending && (!o.targetPresent() || !o.sameInstance()))
      return new Decision(BoostPhase.Orphaned, "TargetInstanceChanged", o.targetPresent());
    if (phase == BoostPhase.Orphaned)
      return new Decision(phase, "TargetInstanceChanged", o.targetPresent());
    if (phase == BoostPhase.Pending)
      return new Decision(phase, "AwaitingAdmission", false);
    if (phase == BoostPhase.Failed)
      return o.completionRequested() ? new Decision(BoostPhase.Releasing, "CleanupRequested", true)
          : new Decision(phase, "CleanupPending", true);
    if (phase == BoostPhase.Preparing) {
      if (o.completionRequested()) return new Decision(BoostPhase.Releasing, "Cancelled", true);
      if (o.acquisitionDeadline() == null || !o.now().isBefore(o.acquisitionDeadline()))
        return new Decision(BoostPhase.Failed, "AcquisitionTimeout", true);
      if (!o.blackout() && o.matchingEvidence()) return new Decision(BoostPhase.Granted, "Prepared", true);
      return new Decision(phase, o.blackout() ? "Blackout" : "AwaitingEvidence", true);
    }
    if (phase == BoostPhase.Granted || phase == BoostPhase.Expired) {
      if (o.completionRequested()) return new Decision(BoostPhase.Releasing, "WorkFinished", true);
      if (phase == BoostPhase.Expired || o.executionDeadline() == null || !o.now().isBefore(o.executionDeadline()))
        return new Decision(BoostPhase.Expired, "ExecutionExpired", true);
      return new Decision(phase, "Active", true);
    }
    if (phase == BoostPhase.Releasing) {
      if (!o.blackout() && o.matchingEvidence() && o.releaseTargetConfirmed()) return new Decision(BoostPhase.Completed, "Released", false);
      return new Decision(phase, o.blackout() ? "Blackout" : "ReleasePending", true);
    }
    throw new IllegalStateException("Unhandled phase " + phase);
  }
}
