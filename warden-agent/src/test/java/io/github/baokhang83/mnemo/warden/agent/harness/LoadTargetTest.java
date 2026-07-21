package io.github.baokhang83.mnemo.warden.agent.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Exercises the retained-size math directly, without the process-blocking {@code main} &mdash;
 * the chunk-sizing decision (256 KiB, not a round 1 MiB) is exactly what W-206's real-cluster run
 * caught a bug in, so it is worth pinning down in a fast unit test too, not just the manual
 * cluster check.
 */
class LoadTargetTest {

  @Test
  void retainsExactlyTheRequestedMegabytes() {
    List<byte[]> chunks = LoadTarget.retain(10);
    long totalBytes = chunks.stream().mapToLong(c -> c.length).sum();
    assertEquals(10L * 1024 * 1024, totalBytes, "total retained bytes must equal the requested megabytes exactly");
  }

  @Test
  void retainingZeroMegabytesRetainsNothing() {
    assertEquals(0, LoadTarget.retain(0).size());
  }

  @Test
  void parsesRetainMbFromEnvWithZeroDefault() {
    assertEquals(0, LoadTarget.parseRetainMb(null));
    assertEquals(0, LoadTarget.parseRetainMb(""));
    assertEquals(220, LoadTarget.parseRetainMb("220"));
  }
}
