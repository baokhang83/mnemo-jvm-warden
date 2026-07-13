package io.github.baokhang83.mnemo.warden.agent.heap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class GcDetectorTest {

  @Test
  void classifiesZgc() {
    assertEquals(Collector.ZGC, GcDetector.classify(List.of("ZGC Cycles", "ZGC Pauses")));
  }

  @Test
  void classifiesShenandoah() {
    assertEquals(
        Collector.SHENANDOAH,
        GcDetector.classify(List.of("Shenandoah Cycles", "Shenandoah Pauses")));
  }

  @Test
  void classifiesG1() {
    assertEquals(
        Collector.G1, GcDetector.classify(List.of("G1 Young Generation", "G1 Old Generation")));
  }

  @Test
  void classifiesSerialAsOther() {
    assertEquals(Collector.OTHER, GcDetector.classify(List.of("Copy", "MarkSweepCompact")));
  }

  @Test
  void classifiesParallelAsOther() {
    assertEquals(Collector.OTHER, GcDetector.classify(List.of("PS Scavenge", "PS MarkSweep")));
  }

  @Test
  void zgcAndShenandoahAreFullySupported() {
    assertEquals(new GcCapabilities(Collector.ZGC, true, true), GcDetector.capabilitiesFor(Collector.ZGC));
    assertEquals(
        new GcCapabilities(Collector.SHENANDOAH, true, true),
        GcDetector.capabilitiesFor(Collector.SHENANDOAH));
  }

  @Test
  void g1HasNoSoftMaxButUncommits() {
    GcCapabilities g1 = GcDetector.capabilitiesFor(Collector.G1);
    assertFalse(g1.supportsSoftMax(), "G1 has no runtime soft max");
    assertTrue(g1.supportsUncommit(), "G1 uncommits via periodic GC");
    assertTrue(g1.supported());
  }

  @Test
  void otherIsReadOnly() {
    assertFalse(GcDetector.capabilitiesFor(Collector.OTHER).supported());
  }

  @Test
  void detectLocalReturnsAKnownShape() {
    GcCapabilities caps = GcDetector.detectLocal();
    assertNotNull(caps.collector());
    // Invariant: a recognised collector always supports uncommit in our capability map.
    if (caps.collector() != Collector.OTHER) {
      assertTrue(caps.supportsUncommit());
    }
  }
}
