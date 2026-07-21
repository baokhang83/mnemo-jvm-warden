package io.github.baokhang83.mnemo.warden.agent.attach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.baokhang83.mnemo.warden.agent.testsupport.SpawnedJvm;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Exercises {@link TargetAttacher} against a real child JVM (not a mock) &mdash; the Attach API
 * and JMX bootstrap are exactly the kind of platform plumbing that a fake would hide breakage in.
 */
class TargetAttacherTest {

  @Test
  void attachesAndReadsTheTargetsGcBeans() throws Exception {
    try (SpawnedJvm target = SpawnedJvm.sleeper()) {
      target.awaitReady();
      try (AttachedJvm attached = TargetAttacher.attach(target.pid())) {
        assertEquals(target.pid(), attached.pid());
        assertTrue(attached.isAlive());

        List<GarbageCollectorMXBean> beans =
            ManagementFactory.getPlatformMXBeans(attached.mbeanConnection(), GarbageCollectorMXBean.class);
        assertFalse(beans.isEmpty(), "the target JVM must expose at least one GC MXBean");
      }
    }
  }

  @Test
  void isAliveGoesFalseAfterTheTargetExits() throws Exception {
    try (SpawnedJvm target = SpawnedJvm.sleeper()) {
      target.awaitReady();
      AttachedJvm attached = TargetAttacher.attach(target.pid());
      try {
        target.close();
        target.process().waitFor();
        assertFalse(attached.isAlive());
      } finally {
        attached.close();
      }
    }
  }
}
