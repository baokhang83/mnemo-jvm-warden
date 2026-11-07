package io.github.baokhang83.mnemo.warden.controller.boost;

import static org.junit.jupiter.api.Assertions.*;
import io.github.baokhang83.mnemo.warden.crd.BoostPhase;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class BoostLifecycleTest {
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private BoostLifecycle.Decision evaluate(BoostPhase phase, boolean present, boolean same,
      boolean completed, boolean blackout, boolean evidence, Instant acquire, Instant execution) {
    return BoostLifecycle.evaluate(new BoostLifecycle.Observation(phase, present, same, completed,
        blackout, evidence, true, acquire, execution, NOW));
  }
  @Test void grantRequiresCurrentEvidenceBeforeDeadlineAndOutsideBlackout() {
    assertEquals(BoostPhase.Granted, evaluate(BoostPhase.Preparing,true,true,false,false,true,NOW.plusSeconds(1),null).phase());
    assertEquals(BoostPhase.Preparing, evaluate(BoostPhase.Preparing,true,true,false,true,true,NOW.plusSeconds(1),null).phase());
    assertEquals(BoostPhase.Preparing, evaluate(BoostPhase.Preparing,true,true,false,false,false,NOW.plusSeconds(1),null).phase());
    var timeout = evaluate(BoostPhase.Preparing,true,true,false,false,true,NOW,null);
    assertEquals(BoostPhase.Failed, timeout.phase()); assertTrue(timeout.keepFloor());
  }
  @Test void expiryRetainsFloorUntilExplicitCompletion() {
    var expired = evaluate(BoostPhase.Granted,true,true,false,false,true,null,NOW);
    assertEquals(BoostPhase.Expired, expired.phase()); assertTrue(expired.keepFloor());
    assertEquals(BoostPhase.Releasing, evaluate(BoostPhase.Expired,true,true,true,true,false,null,NOW).phase());
  }
  @Test void releaseNeedsMatchingEvidenceAndNoBlackout() {
    assertEquals(BoostPhase.Releasing, evaluate(BoostPhase.Releasing,true,true,true,true,true,null,null).phase());
    assertEquals(BoostPhase.Releasing, evaluate(BoostPhase.Releasing,true,true,true,false,false,null,null).phase());
    assertEquals(BoostPhase.Completed, evaluate(BoostPhase.Releasing,true,true,true,false,true,null,null).phase());
  }
  @Test void oldAcknowledgementCannotCompleteRelease() {
    var observation = new BoostLifecycle.Observation(BoostPhase.Releasing, true, true, true,
        false, true, false, null, null, NOW);
    assertEquals(BoostPhase.Releasing, BoostLifecycle.evaluate(observation).phase());
  }
  @Test void replacedContainerNeverInheritsGrant() {
    var orphan = evaluate(BoostPhase.Granted,true,false,false,false,true,null,NOW.plusSeconds(10));
    assertEquals(BoostPhase.Orphaned, orphan.phase()); assertTrue(orphan.keepFloor());
  }
}
