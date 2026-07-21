package io.github.baokhang83.mnemo.warden.controller.guardrail;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exhaustive truth table over {@link PrecedenceEngine#resolve}'s four boolean/Optional inputs
 * (blackedOut &times; emergencyGrowProfile present/absent &times; scheduleCandidate present/absent
 * &times; shrinkVetoed) &mdash; the acceptance criteria's own wording for W-404.
 */
class PrecedenceEngineTest {

  private static final Optional<String> GROW = Optional.of("burst");
  private static final Optional<String> CANDIDATE = Optional.of("off-peak");

  @ParameterizedTest(name = "blackedOut={0} emergencyGrow={1} scheduleCandidate={2} shrinkVetoed={3} -> {4}")
  @MethodSource("truthTable")
  void resolvesAccordingToThePrecedenceRule(
      boolean blackedOut,
      Optional<String> emergencyGrowProfile,
      Optional<String> scheduleCandidate,
      boolean shrinkVetoed,
      Optional<String> expected) {
    assertEquals(
        expected, PrecedenceEngine.resolve(blackedOut, emergencyGrowProfile, scheduleCandidate, shrinkVetoed));
  }

  private static Stream<Arguments> truthTable() {
    return Stream.of(
        // blackout wins outright, regardless of every other input
        Arguments.of(true, Optional.empty(), Optional.empty(), false, Optional.empty()),
        Arguments.of(true, Optional.empty(), Optional.empty(), true, Optional.empty()),
        Arguments.of(true, Optional.empty(), CANDIDATE, false, Optional.empty()),
        Arguments.of(true, Optional.empty(), CANDIDATE, true, Optional.empty()),
        Arguments.of(true, GROW, Optional.empty(), false, Optional.empty()),
        Arguments.of(true, GROW, Optional.empty(), true, Optional.empty()),
        Arguments.of(true, GROW, CANDIDATE, false, Optional.empty()),
        Arguments.of(true, GROW, CANDIDATE, true, Optional.empty()),

        // not blacked out, emergency grow present: wins over the schedule, veto irrelevant
        Arguments.of(false, GROW, Optional.empty(), false, GROW),
        Arguments.of(false, GROW, Optional.empty(), true, GROW),
        Arguments.of(false, GROW, CANDIDATE, false, GROW),
        Arguments.of(false, GROW, CANDIDATE, true, GROW),

        // not blacked out, no emergency grow: schedule applies unless vetoed
        Arguments.of(false, Optional.empty(), CANDIDATE, false, CANDIDATE),
        Arguments.of(false, Optional.empty(), CANDIDATE, true, Optional.empty()),

        // not blacked out, no emergency grow, no schedule candidate: nothing to apply
        Arguments.of(false, Optional.empty(), Optional.empty(), false, Optional.empty()),
        Arguments.of(false, Optional.empty(), Optional.empty(), true, Optional.empty()));
  }
}
