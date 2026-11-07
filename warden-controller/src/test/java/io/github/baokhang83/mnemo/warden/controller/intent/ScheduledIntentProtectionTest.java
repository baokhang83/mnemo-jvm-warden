package io.github.baokhang83.mnemo.warden.controller.intent;

import static org.junit.jupiter.api.Assertions.*;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.github.baokhang83.mnemo.warden.protocol.ResizeIntent;
import org.junit.jupiter.api.Test;

class ScheduledIntentProtectionTest {
  @Test void scheduleCannotOverwriteAnInFlightCorrelatedIntent() {
    var intent = new ResizeIntent("t", "r", "uid", "app", "cid", 300, 600, 200);
    var pod = new PodBuilder().withNewMetadata().withName("p")
        .addToAnnotations(ResizeIntent.ANNOTATION, intent.encode())
        .addToAnnotations(IntentEmitter.ANNOTATION_REQUEST_BYTES, "300")
        .addToAnnotations(IntentEmitter.ANNOTATION_LIMIT_BYTES, "600").endMetadata().build();
    var result = IntentEmitter.applyScheduled(pod, 100, 200);
    assertEquals(intent.encode(), result.getMetadata().getAnnotations().get(ResizeIntent.ANNOTATION));
    assertEquals("300", result.getMetadata().getAnnotations().get(IntentEmitter.ANNOTATION_REQUEST_BYTES));
    assertEquals("600", result.getMetadata().getAnnotations().get(IntentEmitter.ANNOTATION_LIMIT_BYTES));
  }
  @Test void ordinaryScheduledPodStillReceivesTargets() {
    var pod = new PodBuilder().withNewMetadata().withName("p").endMetadata().build();
    var result = IntentEmitter.applyScheduled(pod, 100, 200);
    assertEquals("100", result.getMetadata().getAnnotations().get(IntentEmitter.ANNOTATION_REQUEST_BYTES));
    assertEquals("200", result.getMetadata().getAnnotations().get(IntentEmitter.ANNOTATION_LIMIT_BYTES));
  }
  @Test void correlatedTargetCanGrowWithoutLosingItsFloorOrIdentity() {
    var intent = new ResizeIntent("old", "request", "uid", "app", "cid", 300, 600, 200);
    var pod = new PodBuilder().withNewMetadata().withName("p").withUid("uid")
        .addToAnnotations(ResizeIntent.ANNOTATION, intent.encode())
        .addToAnnotations(IntentEmitter.ANNOTATION_REQUEST_BYTES, "300")
        .addToAnnotations(IntentEmitter.ANNOTATION_LIMIT_BYTES, "600").endMetadata()
        .withNewStatus().addNewContainerStatus().withName("app").withContainerID("cid")
        .withNewState().withNewRunning().endRunning().endState()
        .withNewResources().addToRequests("memory", new io.fabric8.kubernetes.api.model.Quantity("300"))
        .addToLimits("memory", new io.fabric8.kubernetes.api.model.Quantity("600"))
        .endResources().endContainerStatus().endStatus().build();
    var grown = IntentEmitter.applyScheduled(pod, 400, 800);
    var next = ResizeIntent.decode(grown.getMetadata().getAnnotations().get(ResizeIntent.ANNOTATION));
    assertNotEquals(intent.transitionId(), next.transitionId());
    assertEquals("request", next.requestUid());
    assertEquals(400, next.requestBytes());
    assertEquals(800, next.limitBytes());
    assertEquals(200, next.heapBytes());
    assertEquals(next, ResizeIntent.decode(IntentEmitter.applyScheduled(grown, 400, 800)
        .getMetadata().getAnnotations().get(ResizeIntent.ANNOTATION)));
    var later = IntentEmitter.applyScheduled(grown, 100, 200);
    assertEquals(next, ResizeIntent.decode(later.getMetadata().getAnnotations().get(ResizeIntent.ANNOTATION)));
  }
  @Test void malformedCorrelatedIntentIsNotOverwritten() {
    var pod = new PodBuilder().withNewMetadata().withName("p")
        .addToAnnotations(ResizeIntent.ANNOTATION, "invalid").endMetadata().build();
    assertEquals("invalid", IntentEmitter.applyScheduled(pod, 400, 800)
        .getMetadata().getAnnotations().get(ResizeIntent.ANNOTATION));
  }
}
