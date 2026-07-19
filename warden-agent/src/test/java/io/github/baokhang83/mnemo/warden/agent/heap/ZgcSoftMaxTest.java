package io.github.baokhang83.mnemo.warden.agent.heap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.baokhang83.mnemo.warden.agent.attach.AttachedJvm;
import io.github.baokhang83.mnemo.warden.agent.attach.TargetAttacher;
import io.github.baokhang83.mnemo.warden.agent.testsupport.SpawnedJvm;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link ZgcSoftMax} against real ZGC and G1 target JVMs (not mocks) &mdash; the whole
 * point of this class is a JMX behavior (G1 silently accepting the VM option) that a fake would
 * hide.
 */
class ZgcSoftMaxTest {

  @Test
  void readsAndSetsTheSoftCeilingOnARealZgcTarget() throws Exception {
    try (SpawnedJvm target = SpawnedJvm.sleeper("-XX:+UseZGC", "-Xmx512m", "-XX:SoftMaxHeapSize=400m");
        AttachedJvm attached = TargetAttacher.attach(target.awaitDescriptor())) {
      ZgcSoftMax softMax = ZgcSoftMax.forTarget(attached);

      assertEquals(400L * 1024 * 1024, softMax.softMaxHeapSize());

      softMax.setSoftMaxHeapSize(300L * 1024 * 1024);
      assertEquals(300L * 1024 * 1024, softMax.softMaxHeapSize());
    }
  }

  @Test
  void rejectsCleanlyOnARealG1Target() throws Exception {
    try (SpawnedJvm target = SpawnedJvm.sleeper("-XX:+UseG1GC", "-Xmx512m");
        AttachedJvm attached = TargetAttacher.attach(target.awaitDescriptor())) {
      UnsupportedCollectorException thrown =
          assertThrows(UnsupportedCollectorException.class, () -> ZgcSoftMax.forTarget(attached));

      assertEquals(Collector.G1, thrown.actual());
    }
  }
}
