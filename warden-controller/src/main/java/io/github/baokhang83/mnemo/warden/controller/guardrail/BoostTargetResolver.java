package io.github.baokhang83.mnemo.warden.controller.guardrail;

import java.util.Objects;
import java.util.Optional;

/** Resolves a per-container memory target before the existing agent performs any resize. */
public final class BoostTargetResolver {
  private BoostTargetResolver() {}

  /** Resource values are bytes, and a request must fit within its limit. */
  public record Memory(long request, long limit) {
    public Memory {
      if (request <= 0 || limit < request) throw new IllegalArgumentException("invalid memory target");
    }

    public Memory max(Memory other) {
      return new Memory(Math.max(request, other.request), Math.max(limit, other.limit));
    }

    public boolean reduces(Memory observed) {
      return request < observed.request || limit < observed.limit;
    }
  }

  public enum Reason { TARGET, NO_TARGET, BLACKOUT, SHRINK_VETOED, OBSERVATION_REQUIRED }

  /** A missing target means emit no resource transition. The floor remains owned by the caller. */
  public record Decision(Optional<Memory> target, Reason reason) {}

  /**
   * Inputs are already authenticated and bound to the same pod/container by the caller.
   * An admitted floor is a persisted snapshot, not a fresh lookup of a mutable policy.
   * A configured guardrail requires a current allocation before deciding if shrink is safe.
   */
  public static Decision resolve(boolean blackout, Optional<Memory> scheduled,
      Optional<Memory> emergency, Optional<Memory> admittedFloor, Optional<Memory> observed,
      boolean shrinkGuardConfigured, boolean shrinkVetoed) {
    Objects.requireNonNull(scheduled);
    Objects.requireNonNull(emergency);
    Objects.requireNonNull(admittedFloor);
    Objects.requireNonNull(observed);
    if (blackout) return new Decision(Optional.empty(), Reason.BLACKOUT);
    Optional<Memory> candidate = scheduled;
    if (emergency.isPresent()) candidate = Optional.of(candidate.map(m -> m.max(emergency.get())).orElse(emergency.get()));
    if (admittedFloor.isPresent()) candidate = Optional.of(candidate.map(m -> m.max(admittedFloor.get())).orElse(admittedFloor.get()));
    if (candidate.isEmpty()) return new Decision(Optional.empty(), Reason.NO_TARGET);
    if (shrinkGuardConfigured && observed.isEmpty())
      return new Decision(Optional.empty(), Reason.OBSERVATION_REQUIRED);
    if (shrinkVetoed && observed.isPresent() && candidate.get().reduces(observed.get()))
      return new Decision(Optional.empty(), Reason.SHRINK_VETOED);
    return new Decision(candidate, Reason.TARGET);
  }
}
