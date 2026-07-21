package io.github.baokhang83.mnemo.warden.controller.schedule;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import io.github.baokhang83.mnemo.warden.crd.ScheduleWindow;
import io.github.baokhang83.mnemo.warden.crd.WardenPolicySpec;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Evaluates {@code spec.schedule} in {@code spec.timezone} to find the currently active profile
 * &mdash; the real replacement for W-302's placeholder. Each {@link ScheduleWindow} means
 * "switch to this profile when its cron fires"; the active profile is whichever window's cron
 * <b>most recently fired</b> relative to {@code now}, not the next one coming up.
 *
 * <p>DST safety comes from {@code cron-utils}'s {@link ExecutionTime}, which operates entirely
 * in {@link ZonedDateTime}: a cron like {@code "0 22 * * *"} keeps meaning 22:00 local wall-clock
 * time across a DST transition, not a fixed UTC offset that would silently drift by an hour
 * twice a year.
 */
public final class ScheduleEvaluator {

  private static final CronParser PARSER =
      new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));

  private ScheduleEvaluator() {}

  /**
   * The profile of whichever {@code spec.schedule} window most recently fired before {@code
   * now}, or empty if none ever have (no schedule, or every window's first occurrence is still
   * in the future).
   */
  public static Optional<String> currentProfile(WardenPolicySpec spec, Instant now) {
    if (spec.getSchedule() == null || spec.getSchedule().isEmpty()) {
      return Optional.empty();
    }

    ZonedDateTime zonedNow = ZonedDateTime.ofInstant(now, ZoneId.of(spec.getTimezone()));

    String bestProfile = null;
    ZonedDateTime bestFireTime = null;
    for (ScheduleWindow window : spec.getSchedule()) {
      Cron cron = PARSER.parse(window.getCron());
      Optional<ZonedDateTime> lastFired = ExecutionTime.forCron(cron).lastExecution(zonedNow);
      if (lastFired.isPresent() && (bestFireTime == null || lastFired.get().isAfter(bestFireTime))) {
        bestFireTime = lastFired.get();
        bestProfile = window.getProfile();
      }
    }
    return Optional.ofNullable(bestProfile);
  }
}
