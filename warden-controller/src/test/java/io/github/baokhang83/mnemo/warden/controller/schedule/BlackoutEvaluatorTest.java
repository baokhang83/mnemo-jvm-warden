package io.github.baokhang83.mnemo.warden.controller.schedule;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.baokhang83.mnemo.warden.crd.BlackoutWindow;
import io.github.baokhang83.mnemo.warden.crd.WardenPolicySpec;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlackoutEvaluatorTest {

  @Test
  void isBlackedOutWhenNowFallsWithinAWindow() {
    WardenPolicySpec spec = specWithBlackout("2026-12-24T00:00:00Z", "2026-12-26T00:00:00Z");

    assertTrue(BlackoutEvaluator.isBlackedOut(spec, Instant.parse("2026-12-25T12:00:00Z")));
  }

  @Test
  void treatsBothEndpointsAsInclusive() {
    WardenPolicySpec spec = specWithBlackout("2026-12-24T00:00:00Z", "2026-12-26T00:00:00Z");

    assertTrue(BlackoutEvaluator.isBlackedOut(spec, Instant.parse("2026-12-24T00:00:00Z")));
    assertTrue(BlackoutEvaluator.isBlackedOut(spec, Instant.parse("2026-12-26T00:00:00Z")));
  }

  @Test
  void isNotBlackedOutBeforeOrAfterTheWindow() {
    WardenPolicySpec spec = specWithBlackout("2026-12-24T00:00:00Z", "2026-12-26T00:00:00Z");

    assertFalse(BlackoutEvaluator.isBlackedOut(spec, Instant.parse("2026-12-23T23:59:59Z")));
    assertFalse(BlackoutEvaluator.isBlackedOut(spec, Instant.parse("2026-12-26T00:00:01Z")));
  }

  @Test
  void isNotBlackedOutWhenNoBlackoutIsDeclared() {
    WardenPolicySpec spec = new WardenPolicySpec();

    assertFalse(BlackoutEvaluator.isBlackedOut(spec, Instant.now()));
  }

  @Test
  void matchesIfAnyOfMultipleWindowsContainsNow() {
    WardenPolicySpec spec = new WardenPolicySpec();
    spec.setBlackout(
        List.of(
            blackoutWindow("2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"),
            blackoutWindow("2026-12-24T00:00:00Z", "2026-12-26T00:00:00Z")));

    assertTrue(BlackoutEvaluator.isBlackedOut(spec, Instant.parse("2026-12-25T12:00:00Z")));
    assertFalse(BlackoutEvaluator.isBlackedOut(spec, Instant.parse("2026-06-15T12:00:00Z")));
  }

  private static WardenPolicySpec specWithBlackout(String start, String end) {
    WardenPolicySpec spec = new WardenPolicySpec();
    spec.setBlackout(List.of(blackoutWindow(start, end)));
    return spec;
  }

  private static BlackoutWindow blackoutWindow(String start, String end) {
    BlackoutWindow window = new BlackoutWindow();
    window.setStart(start);
    window.setEnd(end);
    return window;
  }
}
