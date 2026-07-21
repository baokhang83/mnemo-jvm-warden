package io.github.baokhang83.mnemo.warden.agent.heap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.baokhang83.mnemo.warden.agent.attach.AttachedJvm;
import io.github.baokhang83.mnemo.warden.agent.attach.TargetAttacher;
import io.github.baokhang83.mnemo.warden.agent.testsupport.SpawnedJvm;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link SoftMax} against real ZGC, Shenandoah, and G1 target JVMs (not mocks).
 *
 * <p>Shenandoah isn't built into every JDK (this dev machine's own {@code java} doesn't have it;
 * the project's actual runtime, {@code eclipse-temurin:21-jdk}, does) &mdash; that case skips via
 * {@link Assumptions} rather than failing when the target JVM can't start, so this test is
 * meaningful wherever Shenandoah is actually available (verified: it is, in CI) without being
 * flaky where it isn't.
 */
class SoftMaxTest {

  @Test
  void readsAndSetsTheSoftCeilingOnARealZgcTarget() throws Exception {
    try (SpawnedJvm target = SpawnedJvm.sleeper("-XX:+UseZGC", "-Xmx512m", "-Xms64m", "-XX:SoftMaxHeapSize=400m")) {
      target.awaitReady();
      try (AttachedJvm attached = TargetAttacher.attach(target.pid())) {
        SoftMax softMax = SoftMax.forTarget(attached);

        assertEquals(400L * 1024 * 1024, softMax.softMaxHeapSize());

        softMax.setSoftMaxHeapSize(300L * 1024 * 1024);
        assertEquals(300L * 1024 * 1024, softMax.softMaxHeapSize());
      }
    }
  }

  @Test
  void readsAndSetsTheSoftCeilingOnARealShenandoahTarget() throws Exception {
    try (SpawnedJvm target =
        SpawnedJvm.sleeper(
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+UseShenandoahGC",
            "-Xmx512m",
            "-Xms64m",
            "-XX:SoftMaxHeapSize=400m")) {
      Thread.sleep(800); // a JDK without Shenandoah built in fails startup almost immediately
      Assumptions.assumeTrue(target.process().isAlive(), "Shenandoah is not available on this JDK");
      target.awaitReady();

      try (AttachedJvm attached = TargetAttacher.attach(target.pid())) {
        SoftMax softMax = SoftMax.forTarget(attached);

        assertEquals(400L * 1024 * 1024, softMax.softMaxHeapSize());

        softMax.setSoftMaxHeapSize(300L * 1024 * 1024);
        assertEquals(300L * 1024 * 1024, softMax.softMaxHeapSize());
      }
    }
  }

  @Test
  void rejectsCleanlyOnARealG1Target() throws Exception {
    try (SpawnedJvm target = SpawnedJvm.sleeper("-XX:+UseG1GC", "-Xmx512m")) {
      target.awaitReady();
      try (AttachedJvm attached = TargetAttacher.attach(target.pid())) {
        UnsupportedCollectorException thrown =
            assertThrows(UnsupportedCollectorException.class, () -> SoftMax.forTarget(attached));

        assertEquals(Collector.G1, thrown.actual());
      }
    }
  }
}
