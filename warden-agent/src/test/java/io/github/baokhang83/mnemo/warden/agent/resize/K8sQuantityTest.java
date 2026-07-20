package io.github.baokhang83.mnemo.warden.agent.resize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class K8sQuantityTest {

  @Test
  void parsesAPlainByteCount() {
    // Exactly what PodResizeClient sends in a PATCH body — verified accepted by a real target.
    assertEquals(209_715_200L, K8sQuantity.parseBytes("209715200"));
  }

  @Test
  void parsesTheNormalizedStatusStringForTheSameValue() {
    // Captured from a real target's status.containerStatuses[].resources after PATCHing
    // "209715200" — the API server normalizes the representation (see class javadoc).
    assertEquals(209_715_200L, K8sQuantity.parseBytes("200Mi"));
  }

  @Test
  void parsesBinarySuffixes() {
    assertEquals(1024L, K8sQuantity.parseBytes("1Ki"));
    assertEquals(1024L * 1024, K8sQuantity.parseBytes("1Mi"));
    assertEquals(1024L * 1024 * 1024, K8sQuantity.parseBytes("1Gi"));
  }

  @Test
  void parsesDecimalSuffixes() {
    assertEquals(1_000L, K8sQuantity.parseBytes("1k"));
    assertEquals(1_000_000L, K8sQuantity.parseBytes("1M"));
    assertEquals(1_000_000_000L, K8sQuantity.parseBytes("1G"));
  }

  @Test
  void parsesFractionalValues() {
    // Real values seen from the API server, e.g. "0.5Gi".
    assertEquals(512L * 1024 * 1024, K8sQuantity.parseBytes("0.5Gi"));
  }

  @Test
  void rejectsAnUnrecognizedSuffix() {
    assertThrows(IllegalArgumentException.class, () -> K8sQuantity.parseBytes("200Xi"));
  }

  @Test
  void rejectsGarbage() {
    assertThrows(IllegalArgumentException.class, () -> K8sQuantity.parseBytes("not-a-quantity"));
  }
}
