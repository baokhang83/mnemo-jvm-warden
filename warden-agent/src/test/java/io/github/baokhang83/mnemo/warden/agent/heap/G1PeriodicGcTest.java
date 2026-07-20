package io.github.baokhang83.mnemo.warden.agent.heap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.baokhang83.mnemo.warden.agent.attach.AttachedJvm;
import io.github.baokhang83.mnemo.warden.agent.attach.TargetAttacher;
import io.github.baokhang83.mnemo.warden.agent.testsupport.SpawnedJvm;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Exercises {@link G1PeriodicGc} against real G1 and ZGC target JVMs (not mocks). */
class G1PeriodicGcTest {

  @Test
  void readsAndSetsThePeriodicIntervalOnARealG1Target() throws Exception {
    try (SpawnedJvm target = SpawnedJvm.sleeper("-XX:+UseG1GC", "-Xmx256m");
        AttachedJvm attached = TargetAttacher.attach(target.awaitDescriptor())) {
      G1PeriodicGc periodicGc = G1PeriodicGc.forTarget(attached);

      assertEquals(Duration.ZERO, periodicGc.periodicGcInterval(), "disabled by default");

      periodicGc.setPeriodicGcInterval(Duration.ofSeconds(30));
      assertEquals(Duration.ofSeconds(30), periodicGc.periodicGcInterval());
    }
  }

  @Test
  void rejectsCleanlyOnARealZgcTarget() throws Exception {
    try (SpawnedJvm target = SpawnedJvm.sleeper("-XX:+UseZGC", "-Xmx256m");
        AttachedJvm attached = TargetAttacher.attach(target.awaitDescriptor())) {
      UnsupportedCollectorException thrown =
          assertThrows(UnsupportedCollectorException.class, () -> G1PeriodicGc.forTarget(attached));

      assertEquals(Collector.ZGC, thrown.actual());
    }
  }
}
