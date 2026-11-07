package io.github.baokhang83.mnemo.warden.controller.guardrail;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static io.github.baokhang83.mnemo.warden.controller.guardrail.BoostTargetResolver.*;

class BoostTargetResolverTest {
  private Optional<Memory> m(long r, long l) { return Optional.of(new Memory(r, l)); }
  private final Optional<Memory> none = Optional.empty();

  @Test void combinesCrossedScheduleEmergencyAndFloorByComponent() {
    var result = resolve(false, m(500, 600), m(200, 1000), m(700, 800), m(200, 400), true, true);
    assertEquals(Optional.of(new Memory(700, 1000)), result.target());
  }

  @Test void scheduleCanChangeWhileAdmittedFloorRemains() {
    assertEquals(m(300, 600), resolve(false, m(100, 200), none, m(300, 600), m(300, 600), true, false).target());
    assertEquals(m(500, 800), resolve(false, m(500, 800), none, m(300, 600), m(300, 600), true, false).target());
    assertEquals(m(100, 200), resolve(false, m(100, 200), none, none, m(300, 600), true, false).target());
  }

  @Test void blackoutSuppressesEvenEmergencyAndFloorTransitions() {
    assertEquals(Reason.BLACKOUT, resolve(true, m(100, 200), m(200, 400), m(300, 600), m(100, 200), true, false).reason());
  }

  @Test void vetoTestsBothDimensionsAgainstActualStateAfterFloorApplied() {
    assertEquals(Reason.SHRINK_VETOED, resolve(false, m(100, 500), none, m(200, 400), m(300, 500), true, true).reason());
    assertEquals(m(300, 600), resolve(false, m(100, 200), none, m(300, 600), m(300, 600), true, true).target());
    assertEquals(m(400, 700), resolve(false, m(400, 700), none, m(300, 600), m(300, 600), true, true).target());
  }

  @Test void absentObservationFailsClosedWhenGuardConfigured() {
    assertEquals(Reason.OBSERVATION_REQUIRED, resolve(false, m(100, 200), none, none, none, true, true).reason());
    assertEquals(Reason.OBSERVATION_REQUIRED, resolve(false, m(100, 200), none, none, none, false, true).reason());
    assertEquals(m(100, 200), resolve(false, m(100, 200), none, none, none, false, false).target());
    assertEquals(Reason.NO_TARGET, resolve(false, none, none, none, none, true, true).reason());
  }

  @Test void rejectsInvalidQuantities() {
    assertThrows(IllegalArgumentException.class, () -> new Memory(0, 1));
    assertThrows(IllegalArgumentException.class, () -> new Memory(2, 1));
  }
}
