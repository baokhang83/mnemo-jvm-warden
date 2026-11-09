package io.github.baokhang83.mnemo.warden.controller.intent;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.baokhang83.mnemo.warden.protocol.ResizeIntent;
import org.junit.jupiter.api.Test;

class ReleaseBlockedDetailTest {
  private static final long MI = 1024L * 1024;

  /** Boosted intent: limit 896Mi, heap 384Mi, so 512Mi of non-heap allowance is retained. */
  private static ResizeIntent boosted(long limitMi, long heapMi) {
    return new ResizeIntent("t", "r", "p", "app", "c", 256 * MI, limitMi * MI, heapMi * MI);
  }

  @Test
  void blocksWhenScheduledLimitIsNoLargerThanRetainedAllowance() {
    String detail = IntentEmitter.releaseBlockedDetail("normal", 320 * MI, boosted(896, 384));
    assertTrue(detail.contains("'normal'") && detail.contains(String.valueOf(320 * MI))
        && detail.contains(String.valueOf(512 * MI)), detail);
    assertTrue(IntentEmitter.releaseBlockedDetail("normal", 512 * MI, boosted(896, 384)) != null);
  }

  @Test
  void allowsWhenScheduledLimitExceedsRetainedAllowance() {
    assertNull(IntentEmitter.releaseBlockedDetail("normal", 320 * MI, boosted(640, 384)));
  }
}
