package io.github.baokhang83.mnemo.warden.controller.intent;

import static org.junit.jupiter.api.Assertions.*;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.github.baokhang83.mnemo.warden.protocol.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CorrelatedEvidenceTest {
  final ResizeIntent intent = new ResizeIntent("transition", "request", "pod-uid", "app", "containerd://one", 100, 400, 200);
  final ResizeAcknowledgement ack = new ResizeAcknowledgement(intent, true, "Applied", 100, 400, 200, Instant.now());

  Pod pod() {
    return new PodBuilder().withNewMetadata().withName("pod").withUid("pod-uid")
        .addToAnnotations(ResizeIntent.ANNOTATION, intent.encode())
        .addToAnnotations(ResizeIntent.ACK_ANNOTATION, ack.encode())
        .addToAnnotations(IntentEmitter.ANNOTATION_REQUEST_BYTES, "100")
        .addToAnnotations(IntentEmitter.ANNOTATION_LIMIT_BYTES, "400").endMetadata()
        .withNewStatus().addNewContainerStatus().withName("app").withContainerID("containerd://one")
        .withNewState().withNewRunning().endRunning().endState()
        .withNewResources().addToRequests("memory", new io.fabric8.kubernetes.api.model.Quantity("100"))
        .addToLimits("memory", new io.fabric8.kubernetes.api.model.Quantity("400"))
        .endResources().endContainerStatus().endStatus().build();
  }
  @Test void onlyMatchingAppliedEvidenceIsAccepted() {
    assertEquals(ack, CorrelatedIntentEmitter.evidence(pod(), intent).orElseThrow());
    var changed = new ResizeIntent("another", "request", "pod-uid", "app", "containerd://one", 100, 400, 200);
    assertTrue(CorrelatedIntentEmitter.evidence(pod(), changed).isEmpty());
  }
  @Test void staleInstanceMissingStatusAndMalformedEvidenceFailClosed() {
    Pod p = pod(); p.getStatus().getContainerStatuses().getFirst().setContainerID("containerd://two");
    assertTrue(CorrelatedIntentEmitter.evidence(p, intent).isEmpty());
    p = pod(); p.setStatus(null);
    assertTrue(CorrelatedIntentEmitter.evidence(p, intent).isEmpty());
    p = pod(); p.getMetadata().getAnnotations().put(ResizeIntent.ACK_ANNOTATION, "invalid");
    assertTrue(CorrelatedIntentEmitter.evidence(p, intent).isEmpty());
    p = pod(); p.getStatus().getContainerStatuses().getFirst().getResources().getRequests().clear();
    assertTrue(CorrelatedIntentEmitter.evidence(p, intent).isEmpty());
    p = pod(); p.getMetadata().getAnnotations().put(IntentEmitter.ANNOTATION_REQUEST_BYTES, "99");
    assertTrue(CorrelatedIntentEmitter.evidence(p, intent).isEmpty());
  }
  @Test void intentPatchFencesUidAndResourceVersion() throws Exception {
    var observed = pod(); observed.getMetadata().setResourceVersion("42");
    var operations = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
        CorrelatedIntentEmitter.patch(observed, intent));
    assertEquals("test", operations.get(0).get("op").asText());
    assertEquals("/metadata/uid", operations.get(0).get("path").asText());
    assertEquals("pod-uid", operations.get(0).get("value").asText());
    assertEquals("/metadata/resourceVersion", operations.get(1).get("path").asText());
    assertEquals("42", operations.get(1).get("value").asText());
  }
}
