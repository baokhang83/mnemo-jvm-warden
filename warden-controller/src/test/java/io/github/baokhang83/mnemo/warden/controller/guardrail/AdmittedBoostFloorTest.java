package io.github.baokhang83.mnemo.warden.controller.guardrail;

import static org.junit.jupiter.api.Assertions.*;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.ConditionBuilder;
import io.github.baokhang83.mnemo.warden.crd.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdmittedBoostFloorTest {
  WardenBoost boost(BoostPhase phase) {
    var boost = new WardenBoost();
    boost.setMetadata(new ObjectMetaBuilder().withName("b").withUid("request").build());
    var pod = new BoostPodRef(); pod.setName("p"); pod.setUid("pod");
    var spec = new WardenBoostSpec(); spec.setPodRef(pod); spec.setContainer("app"); boost.setSpec(spec);
    var status = new WardenBoostStatus(); status.setPhase(phase); status.setPolicyUid("policy");
    status.setPodUid("pod"); status.setContainerId("cid"); status.setRequestFloor("1Gi");
    status.setLimitFloor("2Gi"); status.setHeapTarget("512Mi"); status.setTransitionId("transition");
    boost.setStatus(status); return boost;
  }
  @Test void admittedSnapshotsRemainFloorsUntilVerifiedCompletion() {
    for (var phase : List.of(BoostPhase.Preparing, BoostPhase.Granted, BoostPhase.Expired,
                             BoostPhase.Releasing, BoostPhase.Failed, BoostPhase.Orphaned, BoostPhase.Completed))
      assertTrue(AdmittedBoostFloor.read(boost(phase), "policy", "pod", "app", "cid").isPresent());
    var release = boost(BoostPhase.Releasing);
    release.getStatus().setConditions(List.of(new ConditionBuilder().withType("WorkFinished").withStatus("True").build()));
    assertTrue(AdmittedBoostFloor.read(release, "policy", "pod", "app", "cid").isEmpty());
  }
  @Test void ignoresDifferentTargetButRejectsBrokenAdmissionIdentity() {
    var boost = boost(BoostPhase.Granted);
    assertTrue(AdmittedBoostFloor.read(boost, "policy", "another-pod", "app", "cid").isEmpty());
    assertThrows(IllegalStateException.class, () -> AdmittedBoostFloor.read(boost, "policy", "pod", "app", "restarted"));
    assertThrows(IllegalStateException.class, () -> AdmittedBoostFloor.read(boost, "other-policy", "pod", "app", "cid"));
  }
  @Test void invalidOrMissingAdmittedFloorNeverBecomesZero() {
    var boost = boost(BoostPhase.Granted); boost.getStatus().setRequestFloor("bad");
    assertThrows(IllegalArgumentException.class, () -> AdmittedBoostFloor.read(boost, "policy", "pod", "app", "cid"));
    boost.getStatus().setRequestFloor("1Gi"); boost.getStatus().setHeapTarget("2Gi");
    assertThrows(IllegalStateException.class, () -> AdmittedBoostFloor.read(boost, "policy", "pod", "app", "cid"));
  }
}
