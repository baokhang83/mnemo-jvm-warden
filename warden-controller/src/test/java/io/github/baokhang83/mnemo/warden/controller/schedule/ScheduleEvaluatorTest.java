package io.github.baokhang83.mnemo.warden.controller.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.baokhang83.mnemo.warden.crd.LeadTime;
import io.github.baokhang83.mnemo.warden.crd.ResourceProfile;
import io.github.baokhang83.mnemo.warden.crd.ScheduleWindow;
import io.github.baokhang83.mnemo.warden.crd.WardenPolicySpec;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link ScheduleEvaluator} with fixed {@link Instant}s, including one straddling a
 * real, historical DST transition &mdash; proving cron-utils keeps a cron anchored to local
 * wall-clock time across the shift, not a fixed UTC offset. The reconcile/patch wiring around
 * this is proven separately, against a real cluster, by {@code
 * deploy/verify-wardenpolicy-schedule.sh} (constitution §8).
 */
class ScheduleEvaluatorTest {

  @Test
  void picksTheMostRecentlyFiredWindow_eveningFavorsOffPeak() {
    WardenPolicySpec spec = spec("Europe/Paris", window("0 22 * * *", "off-peak"), window("0 7 * * *", "peak"));
    Instant now = ZonedDateTime.of(2026, 6, 15, 23, 0, 0, 0, ZoneId.of("Europe/Paris")).toInstant();

    assertEquals(Optional.of("off-peak"), ScheduleEvaluator.currentProfile(spec, now));
  }

  @Test
  void picksTheMostRecentlyFiredWindow_morningFavorsPeak() {
    WardenPolicySpec spec = spec("Europe/Paris", window("0 22 * * *", "off-peak"), window("0 7 * * *", "peak"));
    Instant now = ZonedDateTime.of(2026, 6, 15, 9, 0, 0, 0, ZoneId.of("Europe/Paris")).toInstant();

    assertEquals(Optional.of("peak"), ScheduleEvaluator.currentProfile(spec, now));
  }

  @Test
  void returnsEmptyWhenNoScheduleIsDeclared() {
    WardenPolicySpec spec = spec("Europe/Paris");

    assertEquals(Optional.empty(), ScheduleEvaluator.currentProfile(spec, Instant.now()));
  }

  @Test
  void staysAnchoredToLocalWallClockAcrossARealDstTransition() {
    // US DST spring-forward for 2024 was the real, historical night of March 9->10 (clocks
    // 2:00am -> 3:00am, America/New_York) — not a postulated future rule, so this is exercising
    // actual JDK tzdata, not an assumption about how DST works.
    WardenPolicySpec spec = spec("America/New_York", window("0 22 * * *", "off-peak"));

    Instant beforeTransition =
        ZonedDateTime.of(2024, 3, 9, 23, 0, 0, 0, ZoneId.of("America/New_York")).toInstant();
    Instant afterTransition =
        ZonedDateTime.of(2024, 3, 11, 23, 0, 0, 0, ZoneId.of("America/New_York")).toInstant();

    ZonedDateTime lastFiredBefore = lastFiredAt(spec, beforeTransition);
    ZonedDateTime lastFiredAfter = lastFiredAt(spec, afterTransition);

    assertEquals(22, lastFiredBefore.getHour(), "cron must still mean 22:00 local time before the DST shift");
    assertEquals(22, lastFiredAfter.getHour(), "cron must still mean 22:00 local time after the DST shift");
    assertEquals(ZoneOffset.of("-05:00"), lastFiredBefore.getOffset(), "March 9 is still EST");
    assertEquals(ZoneOffset.of("-04:00"), lastFiredAfter.getOffset(), "March 11 is already EDT");
    assertTrue(
        !lastFiredBefore.getOffset().equals(lastFiredAfter.getOffset()),
        "the UTC offset must differ across the transition — proof this isn't fixed-offset arithmetic");
  }

  /** Re-derives the single window's last-fired instant directly, to inspect its zone/offset — {@code
   *  currentProfile} only exposes the winning profile name, not the {@link ZonedDateTime} behind it. */
  private static ZonedDateTime lastFiredAt(WardenPolicySpec spec, Instant now) {
    assertEquals(Optional.of("off-peak"), ScheduleEvaluator.currentProfile(spec, now));
    ZonedDateTime zonedNow = ZonedDateTime.ofInstant(now, ZoneId.of(spec.getTimezone()));
    return com.cronutils.model.time.ExecutionTime.forCron(
            new com.cronutils.parser.CronParser(
                    com.cronutils.model.definition.CronDefinitionBuilder.instanceDefinitionFor(
                        com.cronutils.model.CronType.UNIX))
                .parse(spec.getSchedule().get(0).getCron()))
        .lastExecution(zonedNow)
        .orElseThrow();
  }

  @Test
  void firesShrinkLeadTimeEarly_beforeTheOffPeakWindowsNominalCronTime() {
    WardenPolicySpec spec = offPeakPeakSpec();
    // 22:00 - leadTime.shrink (5m) = 21:55; 21:56 is inside that lead-time zone.
    Instant now = ZonedDateTime.of(2026, 6, 15, 21, 56, 0, 0, ZoneId.of("Europe/Paris")).toInstant();

    assertEquals(Optional.of("off-peak"), ScheduleEvaluator.currentProfileWithLeadTime(spec, now));
  }

  @Test
  void doesNotFireEarlyBeforeEnteringTheShrinkLeadTimeZone() {
    WardenPolicySpec spec = offPeakPeakSpec();
    Instant now = ZonedDateTime.of(2026, 6, 15, 21, 50, 0, 0, ZoneId.of("Europe/Paris")).toInstant();

    assertEquals(Optional.of("peak"), ScheduleEvaluator.currentProfileWithLeadTime(spec, now));
  }

  @Test
  void firesWarmLeadTimeEarly_beforeThePeakWindowsNominalCronTime() {
    WardenPolicySpec spec = offPeakPeakSpec();
    // 07:00 - leadTime.warm (10m) = 06:50; 06:52 is inside that lead-time zone.
    Instant now = ZonedDateTime.of(2026, 6, 15, 6, 52, 0, 0, ZoneId.of("Europe/Paris")).toInstant();

    assertEquals(Optional.of("peak"), ScheduleEvaluator.currentProfileWithLeadTime(spec, now));
  }

  @Test
  void doesNotFireEarlyBeforeEnteringTheWarmLeadTimeZone() {
    WardenPolicySpec spec = offPeakPeakSpec();
    Instant now = ZonedDateTime.of(2026, 6, 15, 6, 40, 0, 0, ZoneId.of("Europe/Paris")).toInstant();

    assertEquals(Optional.of("off-peak"), ScheduleEvaluator.currentProfileWithLeadTime(spec, now));
  }

  @Test
  void withNoLeadTimeDeclared_behavesLikeTheUnadjustedEvaluator() {
    WardenPolicySpec spec = offPeakPeakSpec();
    spec.setLeadTime(null);
    // Same instant as the shrink lead-time test above, but with no leadTime to act on.
    Instant now = ZonedDateTime.of(2026, 6, 15, 21, 56, 0, 0, ZoneId.of("Europe/Paris")).toInstant();

    assertEquals(Optional.of("peak"), ScheduleEvaluator.currentProfileWithLeadTime(spec, now));
  }

  /** off-peak (256Mi, smaller) at 22:00, peak (512Mi, larger) at 07:00; leadTime shrink=5m, warm=10m. */
  private static WardenPolicySpec offPeakPeakSpec() {
    WardenPolicySpec spec = spec("Europe/Paris", window("0 22 * * *", "off-peak"), window("0 7 * * *", "peak"));

    Map<String, ResourceProfile> profiles = new LinkedHashMap<>();
    profiles.put("off-peak", profile("128Mi", "256Mi"));
    profiles.put("peak", profile("384Mi", "512Mi"));
    spec.setProfiles(profiles);

    LeadTime leadTime = new LeadTime();
    leadTime.setShrink("5m");
    leadTime.setWarm("10m");
    spec.setLeadTime(leadTime);

    return spec;
  }

  private static ResourceProfile profile(String request, String limit) {
    ResourceProfile profile = new ResourceProfile();
    profile.setRequest(request);
    profile.setLimit(limit);
    return profile;
  }

  private static WardenPolicySpec spec(String timezone, ScheduleWindow... windows) {
    WardenPolicySpec spec = new WardenPolicySpec();
    spec.setTimezone(timezone);
    spec.setSchedule(List.of(windows));
    return spec;
  }

  private static ScheduleWindow window(String cron, String profile) {
    ScheduleWindow window = new ScheduleWindow();
    window.setCron(cron);
    window.setProfile(profile);
    return window;
  }
}
