package io.github.baokhang83.mnemo.warden.agent.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * Exercises the JSON extraction directly against hand-built API-server-shaped responses, mirroring
 * {@code PodResizeClientTest}'s own pattern &mdash; no live HTTP round trip needed.
 */
class PodIntentReaderTest {

  @Test
  void extractsIntentWhenBothAnnotationsArePresent() {
    String json =
        """
        {"metadata":{"annotations":{
          "warden.mnemo.io/target-request-bytes":"134217728",
          "warden.mnemo.io/target-limit-bytes":"268435456"
        }}}
        """;

    Optional<Intent> intent = PodIntentReader.extractIntent(json);

    assertTrue(intent.isPresent());
    assertEquals(134217728L, intent.get().requestBytes());
    assertEquals(268435456L, intent.get().limitBytes());
  }

  @Test
  void isEmptyWhenAnnotationsAreAbsent() {
    assertEquals(Optional.empty(), PodIntentReader.extractIntent("{\"metadata\":{}}"));
  }

  @Test
  void isEmptyWhenOnlyOneAnnotationIsPresent() {
    String json = "{\"metadata\":{\"annotations\":{\"warden.mnemo.io/target-limit-bytes\":\"268435456\"}}}";

    assertEquals(Optional.empty(), PodIntentReader.extractIntent(json));
  }

  @Test
  void extractsTheNamedContainersCurrentLimit() {
    String json =
        """
        {"status":{"containerStatuses":[
          {"name":"warden","resources":{"limits":{"memory":"67108864"}}},
          {"name":"app","resources":{"limits":{"memory":"268435456"}}}
        ]}}
        """;

    assertEquals(OptionalLong.of(268435456L), PodIntentReader.extractCurrentLimitBytes(json, "app"));
  }

  @Test
  void currentLimitIsEmptyWhenTheContainerHasNoStatusYet() {
    assertEquals(OptionalLong.empty(), PodIntentReader.extractCurrentLimitBytes("{\"status\":{}}", "app"));
  }
}
