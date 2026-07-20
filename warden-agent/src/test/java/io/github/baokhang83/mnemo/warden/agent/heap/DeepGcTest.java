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
  void rejectsCleanlyOnARealSerialGcTarget() throws Exception {
    try (SpawnedJvm target = SpawnedJvm.sleeper("-XX:+UseSerialGC", "-Xmx256m");
        AttachedJvm attached = TargetAttacher.attach(target.awaitDescriptor())) {
      UnsupportedCollectorException thrown =
          assertThrows(UnsupportedCollectorException.class, () -> DeepGc.forTarget(attached));

      assertEquals(Collector.OTHER, thrown.actual());
    }
  }
}
