package io.github.baokhang83.mnemo.warden.controller.boost;

import static org.junit.jupiter.api.Assertions.*;
import io.fabric8.kubernetes.api.model.ConditionBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.github.baokhang83.mnemo.warden.crd.BoostPhase;
import io.github.baokhang83.mnemo.warden.crd.WardenBoost;
import io.github.baokhang83.mnemo.warden.crd.WardenBoostStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoostTelemetryTest {
  private static WardenBoost boost(BoostPhase phase, String reason) {
    var b = new WardenBoost();
    b.setMetadata(new ObjectMetaBuilder().withName("private-request-name").withUid("private-uid")
        .withCreationTimestamp("2026-01-01T00:00:00Z").build());
    var status = new WardenBoostStatus(); status.setPhase(phase);
    if (reason != null) status.setConditions(List.of(new ConditionBuilder().withType("Ready")
        .withStatus("False").withReason(reason).withMessage(reason).withLastTransitionTime("2026-01-01T00:01:00Z").build()));
    b.setStatus(status); return b;
  }
  @Test void gaugesDescribeCurrentRequestsWithoutPerObjectLabels() {
    var output = BoostTelemetry.render(List.of(boost(BoostPhase.Granted, null), boost(BoostPhase.Expired, null),
        boost(BoostPhase.Releasing, "ReleaseBlocked"), boost(BoostPhase.Releasing, "Blackout")));
    assertTrue(output.contains("warden_boost_active_grants 1\n"));
    assertTrue(output.contains("warden_boost_expired_grants 1\n"));
    assertTrue(output.contains("warden_boost_blocked_releases 1\n"));
    assertFalse(output.contains("private-request-name"));
    assertFalse(output.contains("private-uid"));
  }
  @Test void recordsSuccessfulTransitionAndAcquisitionDuration() {
    var old = boost(BoostPhase.Preparing, null);
    var granted = boost(BoostPhase.Granted, null);
    granted.getStatus().setGrantedAt("2026-01-01T00:00:20Z");
    String before = BoostTelemetry.render(List.of());
    BoostTelemetry.changed(null, old, granted);
    String after = BoostTelemetry.render(List.of());
    assertNotEquals(before, after);
    assertTrue(after.contains("warden_boost_acquisition_duration_seconds_sum 20.0\n"));
    assertTrue(after.contains("warden_boost_acquisition_duration_seconds_count 1\n"));
  }
}
