package io.github.baokhang83.mnemo.warden.agent.heap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.baokhang83.mnemo.warden.agent.attach.AttachedJvm;
import io.github.baokhang83.mnemo.warden.agent.attach.TargetAttacher;
import io.github.baokhang83.mnemo.warden.agent.testsupport.SpawnedJvm;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link DeepGc} against real ZGC and Serial-GC target JVMs (not mocks) &mdash; both
 * the {@code GC.run} JMX invocation and the committed-heap polling are exactly the kind of
 * platform behavior a fake would hide breakage in.
 */
class DeepGcTest {

  @Test
  void freesAndReportsCommittedHeapOnARealZgcTarget() throws Exception {
    // ZUncommitDelay=5s: long enough that the garbage doesn't self-decay before we take the
    // "before" reading, short enough to keep this test's runtime sane. Production defaults to
    // 300s (see DeepGc's javadoc) — this is only about proving the mechanism.
    // Confirmed by spiking against a real target: GC.run does NOT bypass the delay — the
    // uncommitter thread waits out the full configured delay regardless of when GC.run runs.
    try (SpawnedJvm target =
            SpawnedJvm.garbageChurner("-XX:+UseZGC", "-Xmx512m", "-Xms64m", "-XX:ZUncommitDelay=5");
        AttachedJvm attached = TargetAttacher.attach(target.awaitDescriptor())) {
      // Wait for the target's own "allocated" marker rather than guessing a sleep duration —
      // JVM startup + compile time is not fixed, and reading "before" too early would capture
      // the pre-allocation baseline instead of the inflated committed heap.
      target.awaitStdoutLine("allocated", Duration.ofSeconds(10));

      DeepGc deepGc = DeepGc.forTarget(attached);
      UncommitResult result = deepGc.runAndAwaitUncommit(Duration.ofSeconds(20));

      assertTrue(result.completed(), "committed heap should stabilize within the timeout");
      assertTrue(result.bytesUncommitted() > 0, "a churned heap should free some committed memory");
    }
  }

  @Test
  void runsCleanlyAgainstARealG1TargetWithoutRequiringAnObservedDrop() throws Exception {
    // Unlike ZGC/Shenandoah, G1 has no equivalent uncommit-delay tunable and already reclaims
    // fairly eagerly by default — confirmed by spiking against a real target that committed heap
    // can already be back at its floor within ~1-2s of garbage becoming unreachable, well before
    // this test can attach and take a "before" reading. That means completed:true (an *observed*
    // drop, per the honest algorithm from W-104 / constitution §7) isn't reliably reproducible in
    // a fast test here — so this asserts only that the real GC.run + polling mechanism runs
    // cleanly against a real G1 target, not a specific timing outcome G1's own speed makes
    // non-deterministic to catch.
    // Xmx1200m, not 512m: garbageChurner's 400MB is briefly all-live (referenced by its local
    // array until the method returns) before it becomes collectible, and G1 needs materially
    // more headroom than ZGC for that same transient peak — confirmed by hitting a real OOM at
    // 512m and 768m before settling on this size.
    try (SpawnedJvm target = SpawnedJvm.garbageChurner("-XX:+UseG1GC", "-Xmx1200m", "-Xms64m");
        AttachedJvm attached = TargetAttacher.attach(target.awaitDescriptor())) {
      target.awaitStdoutLine("allocated", Duration.ofSeconds(10));

      DeepGc deepGc = DeepGc.forTarget(attached);
      UncommitResult result = deepGc.runAndAwaitUncommit(Duration.ofSeconds(5));

      assertTrue(result.bytesUncommitted() >= 0, "committed heap should never appear to grow");
    }
  }

  @Test
  void rejectsCleanlyOnARealSerialGcTarget() throws Exception {
    try (SpawnedJvm target = SpawnedJvm.sleeper("-XX:+UseSerialGC", "-Xmx256m");
        AttachedJvm attached = TargetAttacher.attach(target.awaitDescriptor())) {
      UnsupportedCollectorException thrown =
          assertThrows(UnsupportedCollectorException.class, () -> DeepGc.forTarget(attached));

      assertEquals(Collector.OTHER, thrown.actual());
    }
  }
}
