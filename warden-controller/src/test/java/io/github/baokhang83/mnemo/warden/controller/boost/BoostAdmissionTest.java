package io.github.baokhang83.mnemo.warden.controller.boost;

import static org.junit.jupiter.api.Assertions.*;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.github.baokhang83.mnemo.warden.crd.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BoostAdmissionTest {
  @Test void durationUsesExactGoStyleUnits() {
    assertEquals(Duration.ofMinutes(90).plusMillis(500), BoostAdmission.duration("1h30m0.5s"));
    assertThrows(IllegalArgumentException.class, () -> BoostAdmission.duration("0s"));
    assertThrows(IllegalArgumentException.class, () -> BoostAdmission.duration("1day"));
  }

  @Test void admissionRequiresExactPolicyOptInAndPodInstance() {
    var request = new WardenBoost();
    request.setMetadata(new io.fabric8.kubernetes.api.model.ObjectMetaBuilder().withNamespace("n")
        .withCreationTimestamp("2026-01-01T00:00:00Z").build());
    var ref = new BoostPodRef(); ref.setName("pod"); ref.setUid("pod-uid");
    var spec = new WardenBoostSpec(); spec.setPodRef(ref); spec.setPolicyRef("policy");
    spec.setContainer("app"); spec.setBoost("index"); spec.setCompletionRequested(false); request.setSpec(spec);
    var policy = new WardenPolicy(); policy.setMetadata(new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
        .withNamespace("n").withName("policy").withUid("policy-uid").withGeneration(2L).build());
    var profile = new ResourceProfile(); profile.setRequest("128Mi"); profile.setLimit("512Mi");
    var definition = new BoostDefinition(); definition.setProfile("high"); definition.setHeapTarget("256Mi");
    definition.setAcquisitionTimeout("2m"); definition.setMaxDuration("5m");
    var target = new TargetRef(); target.setKind("Pod"); target.setName("pod");
    var policySpec = new WardenPolicySpec(); policySpec.setTargetRef(target);
    policySpec.setProfiles(Map.of("high", profile)); policySpec.setBoosts(Map.of("index", definition)); policy.setSpec(policySpec);
    var status = new ContainerStatusBuilder().withName("app").withContainerID("containerd://abc")
        .withNewState().withNewRunning().endRunning().endState()
        .withNewResources().addToRequests("memory", new Quantity("64Mi"))
        .addToLimits("memory", new Quantity("256Mi")).endResources().build();
    var pod = new PodBuilder().withNewMetadata().withNamespace("n").withName("pod").withUid("pod-uid")
        .addToLabels(BoostAdmission.POLICY_UID_LABEL, "policy-uid").endMetadata()
        .withNewStatus().withContainerStatuses(status).endStatus().build();
    Instant now = Instant.parse("2026-01-01T00:00:01Z");
    var snapshot = BoostAdmission.validate(null, request, policy, pod, now);
    assertEquals("containerd://abc", snapshot.containerId());
    assertEquals(Duration.ofMinutes(5), snapshot.maxDuration());
    assertEquals(Instant.parse("2026-01-01T00:02:00Z"), snapshot.acquisitionDeadline());
    pod.getMetadata().getLabels().put(BoostAdmission.POLICY_UID_LABEL, "another-policy");
    assertThrows(IllegalArgumentException.class, () -> BoostAdmission.validate(null, request, policy, pod, now));
    pod.getMetadata().getLabels().put(BoostAdmission.POLICY_UID_LABEL, "policy-uid");
    assertThrows(IllegalArgumentException.class, () -> BoostAdmission.validate(null, request, policy, pod, now.plusSeconds(119)));
    pod.getMetadata().setUid("replacement");
    assertThrows(IllegalArgumentException.class, () -> BoostAdmission.validate(null, request, policy, pod, now));
  }
}
