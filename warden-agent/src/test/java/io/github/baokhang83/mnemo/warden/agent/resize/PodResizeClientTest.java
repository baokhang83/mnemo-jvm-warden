package io.github.baokhang83.mnemo.warden.agent.resize;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Unit-tests the pure logic (patch-body construction, status-JSON navigation) directly, using
 * real captured request/response bodies from a kind cluster. The full HTTP round-trip &mdash;
 * auth, TLS, the actual PATCH-then-poll sequence against a real API server &mdash; is verified
 * manually against a real cluster instead (see {@code deploy/README.md}'s existing kind-based
 * verification pattern; this class isn't the first thing in this repo that only means something
 * against a real cluster).
 */
class PodResizeClientTest {

  @Test
  void buildsAMemoryOnlyStrategicMergePatchBody() {
    // Matches the exact shape verified accepted by a real API server: memory only, no cpu key
    // at all, plain decimal byte counts with no unit suffix.
    assertEquals(
        "{\"spec\":{\"containers\":[{\"name\":\"app\",\"resources\":{"
            + "\"requests\":{\"memory\":\"209715200\"},\"limits\":{\"memory\":\"293601280\"}}}]}}",
        PodResizeClient.patchBody("app", 209_715_200L, 293_601_280L));
  }

  @Test
  void extractsConfirmedMemoryFromARealCapturedGetResponse() {
    // Captured verbatim from a real kind cluster's GET response after a resize PATCH — the
    // status the API server actually reports back, normalized to "Mi" suffixes.
    String getResponse =
        """
        {
          "status": {
            "containerStatuses": [
              {
                "name": "app",
                "resources": {
                  "limits": {"cpu": "200m", "memory": "333Mi"},
                  "requests": {"cpu": "100m", "memory": "200Mi"}
                }
              }
            ]
          }
        }
        """;

    long[] confirmed = PodResizeClient.extractConfirmedMemory(getResponse, "app");

    assertArrayEquals(new long[] {209_715_200L, 349_175_808L}, confirmed);
  }

  @Test
  void returnsNullWhenTheContainerHasNoStatusYet() {
    String getResponse = """
        {"status": {"containerStatuses": []}}
        """;

    assertNull(PodResizeClient.extractConfirmedMemory(getResponse, "app"));
  }

  @Test
  void returnsNullWhenStatusIsAbsentEntirely() {
    assertNull(PodResizeClient.extractConfirmedMemory("{\"spec\":{}}", "app"));
  }

  @Test
  void ignoresOtherContainersInTheStatusList() {
    String getResponse =
        """
        {
          "status": {
            "containerStatuses": [
              {"name": "warden", "resources": {"limits": {"memory": "64Mi"}, "requests": {"memory": "32Mi"}}},
              {"name": "app", "resources": {"limits": {"memory": "256Mi"}, "requests": {"memory": "128Mi"}}}
            ]
          }
        }
        """;

    long[] confirmed = PodResizeClient.extractConfirmedMemory(getResponse, "app");

    assertArrayEquals(new long[] {134_217_728L, 268_435_456L}, confirmed);
  }
}
