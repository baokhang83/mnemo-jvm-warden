package io.github.baokhang83.mnemo.warden.agent.attach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link TargetAttacher} against a real child JVM (not a mock) &mdash; the Attach API
 * and JMX bootstrap are exactly the kind of platform plumbing that a fake would hide breakage in.
 */
class TargetAttacherTest {

  private Process child;

  @AfterEach
  void killChild() {
    if (child != null) {
      child.destroyForcibly();
    }
  }

  @Test
  void attachesAndReadsTheTargetsGcBeans() throws Exception {
    long childPid = spawnSleeper();
    VirtualMachineDescriptor descriptor = awaitDescriptor(childPid);

    try (AttachedJvm attached = TargetAttacher.attach(descriptor)) {
      assertEquals(childPid, attached.pid());
      assertTrue(attached.isAlive());

      List<GarbageCollectorMXBean> beans =
          ManagementFactory.getPlatformMXBeans(attached.mbeanConnection(), GarbageCollectorMXBean.class);
      assertFalse(beans.isEmpty(), "the target JVM must expose at least one GC MXBean");
    }
  }

  @Test
  void isAliveGoesFalseAfterTheTargetExits() throws Exception {
    long childPid = spawnSleeper();
    VirtualMachineDescriptor descriptor = awaitDescriptor(childPid);

    AttachedJvm attached = TargetAttacher.attach(descriptor);
    try {
      child.destroyForcibly();
      child.waitFor();
      assertFalse(attached.isAlive());
    } finally {
      attached.close();
    }
  }

  /** Launches a throwaway JVM (JEP 330 single-file source launch) that just parks, as an attach target. */
  private long spawnSleeper() throws IOException {
    Path source = Files.createTempFile("TargetAttacherTestSleeper", ".java");
    Files.writeString(
        source,
        """
        public class %s {
          public static void main(String[] args) throws InterruptedException {
            Thread.sleep(60_000);
          }
        }
        """
            .formatted(baseName(source)));

    String javaBin = System.getProperty("java.home") + "/bin/java";
    child = new ProcessBuilder(javaBin, source.toString()).inheritIO().start();
    return child.pid();
  }

  private static String baseName(Path source) {
    String name = source.getFileName().toString();
    return name.substring(0, name.length() - ".java".length());
  }

  /** The child's attach socket can take a moment to appear after the process starts. */
  private static VirtualMachineDescriptor awaitDescriptor(long pid) throws InterruptedException {
    for (int i = 0; i < 100; i++) {
      Optional<VirtualMachineDescriptor> found =
          VirtualMachine.list().stream().filter(d -> d.id().equals(Long.toString(pid))).findFirst();
      if (found.isPresent()) {
        return found.get();
      }
      Thread.sleep(100);
    }
    throw new AssertionError("child JVM (pid " + pid + ") never appeared in VirtualMachine.list()");
  }
}
