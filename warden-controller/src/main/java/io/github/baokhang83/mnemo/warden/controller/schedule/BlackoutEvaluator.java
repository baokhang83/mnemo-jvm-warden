package io.github.baokhang83.mnemo.warden.controller.schedule;

import io.github.baokhang83.mnemo.warden.crd.BlackoutWindow;
import io.github.baokhang83.mnemo.warden.crd.WardenPolicySpec;
import java.time.Instant;

/**
 * Whether {@code now} falls inside any of {@code spec.blackout}'s windows &mdash; W-305's hard
 * "do not touch" override. {@code start}/{@code end} are absolute ISO-8601 instants (every
 * example uses an explicit {@code Z} offset), not local wall-clock crons, so this compares
 * {@link Instant}s directly: a launch freeze or holiday blackout is "this exact moment in time,"
 * not a recurring local time, so unlike {@link ScheduleEvaluator} there's no DST question here
 * to get right (see the design doc).
 *
 * <p>{@link WardenPolicyReconciler} gates on this once, before calling {@link ScheduleEvaluator}
 * at all: when blacked out, {@code status.currentProfile} is left untouched for this reconcile
 * rather than routed to some blackout-specific profile &mdash; {@link BlackoutWindow} has no
 * such field, and "do not touch" means leave whatever is currently active alone.
 */
public final class BlackoutEvaluator {

  private BlackoutEvaluator() {}

  public static boolean isBlackedOut(WardenPolicySpec spec, Instant now) {
    if (spec.getBlackout() == null) {
      return false;
    }
    for (BlackoutWindow window : spec.getBlackout()) {
      Instant start = Instant.parse(window.getStart());
      Instant end = Instant.parse(window.getEnd());
      if (!now.isBefore(start) && !now.isAfter(end)) {
        return true;
      }
    }
    return false;
  }
}
