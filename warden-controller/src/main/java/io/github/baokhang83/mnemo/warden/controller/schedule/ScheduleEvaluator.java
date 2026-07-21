package io.github.baokhang83.mnemo.warden.controller.schedule;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import io.github.baokhang83.mnemo.warden.crd.LeadTime;
import io.github.baokhang83.mnemo.warden.crd.ResourceProfile;
import io.github.baokhang83.mnemo.warden.crd.ScheduleWindow;
import io.github.baokhang83.mnemo.warden.crd.WardenPolicySpec;
import java.time.Duration;
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

  /**
   * {@link #currentProfile} adjusted for W-304's lead time: if the *next* upcoming transition is
   * within its own {@code leadTime} of firing, its profile is returned early instead of the
   * unadjusted {@code currentProfile}. Direction (shrink vs. warm) is classified by comparing the
   * next profile's {@code limit} against the current one's &mdash; smaller uses {@code
   * leadTime.shrink}, larger uses {@code leadTime.warm}. Falls back to the unadjusted {@code
   * currentProfile} whenever there's no established "current" to anticipate relative to, no
   * upcoming transition, or either profile's limit can't be resolved &mdash; this only reasons
   * about one upcoming transition at a time (see the design doc for why).
   */
  public static Optional<String> currentProfileWithLeadTime(WardenPolicySpec spec, Instant now) {
    Optional<String> base = currentProfile(spec, now);
    if (base.isEmpty() || spec.getSchedule() == null || spec.getSchedule().isEmpty()) {
      return base;
    }

    ZonedDateTime zonedNow = ZonedDateTime.ofInstant(now, ZoneId.of(spec.getTimezone()));
    NextTransition next = findNextTransition(spec, zonedNow);
    if (next == null) {
      return base;
    }

    Long baseLimitBytes = limitBytes(spec, base.get());
    Long nextLimitBytes = limitBytes(spec, next.profile());
    if (baseLimitBytes == null || nextLimitBytes == null || baseLimitBytes.equals(nextLimitBytes)) {
      return base;
    }

    Duration leadTime = leadTimeFor(spec.getLeadTime(), nextLimitBytes < baseLimitBytes);
    if (leadTime == null) {
      return base;
    }

    Instant triggerAt = next.fireTime().toInstant().minus(leadTime);
    return now.isBefore(triggerAt) ? base : Optional.of(next.profile());
  }

  private record NextTransition(String profile, ZonedDateTime fireTime) {}

  private static NextTransition findNextTransition(WardenPolicySpec spec, ZonedDateTime zonedNow) {
    NextTransition soonest = null;
    for (ScheduleWindow window : spec.getSchedule()) {
      Cron cron = PARSER.parse(window.getCron());
      Optional<ZonedDateTime> nextFired = ExecutionTime.forCron(cron).nextExecution(zonedNow);
      if (nextFired.isPresent() && (soonest == null || nextFired.get().isBefore(soonest.fireTime()))) {
        soonest = new NextTransition(window.getProfile(), nextFired.get());
      }
    }
    return soonest;
  }

  /**
   * {@code spec.profiles.get(profileName)}'s {@code limit}, parsed to bytes, or {@code null} if
   * the profile or its limit is unresolved. {@code public}: {@code
   * io.github.baokhang83.mnemo.warden.controller.guardrail.ShrinkVetoEvaluator} (W-402) reuses
   * this exact lookup to classify a schedule candidate as a shrink, rather than a second copy of
   * the same profile-name-to-bytes resolution.
   */
  public static Long limitBytes(WardenPolicySpec spec, String profileName) {
    if (spec.getProfiles() == null) {
      return null;
    }
    ResourceProfile profile = spec.getProfiles().get(profileName);
    if (profile == null || profile.getLimit() == null) {
      return null;
    }
    return ResourceQuantity.parseBytes(profile.getLimit());
  }

  /** @param movingToSmaller true if the transition's target profile has a smaller limit (a shrink) */
  private static Duration leadTimeFor(LeadTime leadTime, boolean movingToSmaller) {
    if (leadTime == null) {
      return null;
    }
    String raw = movingToSmaller ? leadTime.getShrink() : leadTime.getWarm();
    return raw == null ? null : ShorthandDuration.parse(raw);
  }
}
