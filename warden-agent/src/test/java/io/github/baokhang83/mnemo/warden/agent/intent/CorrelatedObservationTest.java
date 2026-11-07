package io.github.baokhang83.mnemo.warden.agent.intent;

import static org.junit.jupiter.api.Assertions.*;
import io.github.baokhang83.mnemo.warden.protocol.*;
import org.junit.jupiter.api.Test;

class CorrelatedObservationTest {
  final ResizeIntent intent = new ResizeIntent("t", "r", "uid", "app", "containerd://1", 100, 400, 200);
  String json() {
    return "{\"metadata\":{\"uid\":\"uid\",\"resourceVersion\":\"42\",\"annotations\":{"
        + JsonString.quote(ResizeIntent.ANNOTATION) + ":" + JsonString.quote(intent.encode())
        + ",\"warden.mnemo.io/target-request-bytes\":\"100\",\"warden.mnemo.io/target-limit-bytes\":\"400\"}},"
        + "\"spec\":{\"containers\":[{\"name\":\"app\",\"resources\":{\"limits\":{\"memory\":\"400\"}}}]},"
        + "\"status\":{\"containerStatuses\":[{\"name\":\"app\",\"containerID\":\"containerd://1\",\"state\":{\"running\":{}},"
        + "\"resources\":{\"requests\":{\"memory\":\"50\"},\"limits\":{\"memory\":\"300\"}}}]}}";
  }
  @Test void readsAppliedResourcesAndIdentityInsteadOfDesiredSpec() {
    PodState s = PodIntentReader.extractState(json(), "app");
    assertEquals(intent, s.correlatedIntent().orElseThrow());
    assertEquals(50, s.currentRequestBytes().orElseThrow());
    assertEquals(300, s.currentLimitBytes().orElseThrow());
    assertEquals("42", s.resourceVersion());
    assertEquals("containerd://1", s.containerId());
  }
  @Test void conflictingOrUnsupportedProtocolDoesNotFallBackToLegacy() {
    assertThrows(IllegalArgumentException.class, () -> PodIntentReader.extractState(
        json().replace("target-request-bytes\":\"100", "target-request-bytes\":\"99"), "app"));
    assertThrows(IllegalArgumentException.class, () -> PodIntentReader.extractState(
        json().replace("v1.", "v2."), "app"));
  }
  @Test void nonRunningInstanceCannotAuthorizeWork() {
    PodState s = PodIntentReader.extractState(json().replace("\"running\":{}", "\"terminated\":{}"), "app");
    assertNull(s.containerId());
    assertTrue(s.currentRequestBytes().isEmpty());
  }
}
