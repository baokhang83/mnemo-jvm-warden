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
}
