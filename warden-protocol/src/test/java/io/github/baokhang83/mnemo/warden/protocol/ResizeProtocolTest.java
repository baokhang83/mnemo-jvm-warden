package io.github.baokhang83.mnemo.warden.protocol;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ResizeProtocolTest {
  private final ResizeIntent intent = new ResizeIntent("transition-1", "request-1", "pod-1", "app", "containerd://abc", 100, 400, 200);
  private final ResizeAcknowledgement ack = new ResizeAcknowledgement(intent, true, "Applied", 100, 400, 200, Instant.parse("2026-11-07T00:00:00Z"));

  @Test void roundTripsExactLongValuesAndEvidence() {
    var large = new ResizeIntent("t", "r", "p", "app", "containerd://a", 9007199254740993L, Long.MAX_VALUE, 9007199254740994L);
    assertEquals(large, ResizeIntent.decode(large.encode()));
    assertEquals(ack, ResizeAcknowledgement.decode(ack.encode()));
    assertTrue(ack.confirms(intent, "pod-1", "containerd://abc", 100, 400));
  }

  @Test void sameNumbersCannotAcknowledgeAnotherTransitionOrInstance() {
    assertFalse(ack.confirms(new ResizeIntent("transition-2", "request-1", "pod-1", "app", "containerd://abc", 100, 400, 200), "pod-1", "containerd://abc", 100, 400));
    assertFalse(ack.confirms(new ResizeIntent("transition-1", "request-2", "pod-1", "app", "containerd://abc", 100, 400, 200), "pod-1", "containerd://abc", 100, 400));
    assertFalse(ack.confirms(intent, "pod-2", "containerd://abc", 100, 400));
    assertFalse(ack.confirms(intent, "pod-1", "containerd://restarted", 100, 400));
    assertFalse(ack.confirms(intent, "pod-1", "containerd://abc", 99, 400));
    assertFalse(new ResizeAcknowledgement(intent, false, "Failed", 100, 400, 200, ack.completedAt())
        .confirms(intent, "pod-1", "containerd://abc", 100, 400));
  }

  @Test void rejectsUnsupportedMalformedAndOversizedPayloads() {
    assertThrows(IllegalArgumentException.class, () -> ResizeIntent.decode(intent.encode().replaceFirst("v1", "v2")));
    assertThrows(IllegalArgumentException.class, () -> ResizeIntent.decode(intent.encode() + ".extra"));
    assertThrows(IllegalArgumentException.class, () -> ResizeIntent.decode("v1." + "x".repeat(9000)));
    assertThrows(IllegalArgumentException.class, () -> new ResizeIntent("t", "r", "p", "app", "cid", 100, 400, 400));
    assertThrows(IllegalArgumentException.class, () -> WireCodec.decode("v1.YQ==", 1));
  }

  @Test void escapesJsonPatchValues() {
    assertEquals("\"quote\\\"slash\\\\line\\u000a\"", JsonString.quote("quote\"slash\\line\n"));
  }
}
