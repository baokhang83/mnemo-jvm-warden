package io.github.baokhang83.mnemo.warden.agent.attach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import io.github.baokhang83.mnemo.warden.agent.HealthState;
import io.github.baokhang83.mnemo.warden.agent.testsupport.SpawnedJvm;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Runs {@link AttachSupervisor} end-to-end against real spawned child JVMs (not mocks) &mdash;
 * this loop is the one piece of W-102 that only a real attach/detach cycle can prove out.
 *
 * <p>Uses the package-private locator seam rather than {@link TargetLocator#findTarget()}: this
 * dev machine routinely has other, unrelated JVMs running, so the "exactly one other JVM"
 * assumption that holds inside a real pod does not hold here.
 */
class AttachSupervisorTest {

  private static final Duration TIMEOUT = AttachSupervisor.POLL_INTERVAL.multipliedBy(10);

  private final List<SpawnedJvm> spawned = new ArrayList<>();
  private final AtomicReference<Long> expectedPid = new AtomicReference<>();

  @AfterEach
  void killAll() {
    spawned.forEach(SpawnedJvm::close);
  }

  @Test
  void attachesOnBootAndReattachesAfterTheTargetRestarts() throws Exception {
    HealthState health = new HealthState();
    AttachSupervisor supervisor = new AttachSupervisor(health, this::locateExpectedPid);
    supervisor.start();
    try {
      SpawnedJvm first = spawn();
      expectedPid.set(first.pid());
      awaitTrue("initial attach", () -> health.ready() && supervisor.currentTarget().isPresent());
      assertEquals(first.pid(), supervisor.currentTarget().orElseThrow().pid());

      first.close();
      first.process().waitFor();
      awaitTrue("not-ready after target death", () -> !health.ready());

      SpawnedJvm second = spawn();
      expectedPid.set(second.pid());
      awaitTrue(
          "reattach to the restarted target",
          () -> health.ready() && supervisor.currentTarget().map(AttachedJvm::pid).orElse(-1L) == second.pid());
    } finally {
      supervisor.stop();
    }
    assertFalse(health.ready());
  }

  /** Finds whichever PID the test currently cares about, ignoring every other visible JVM. */
  private Optional<VirtualMachineDescriptor> locateExpectedPid() {
    Long pid = expectedPid.get();
    if (pid == null) {
      return Optional.empty();
    }
    return VirtualMachine.list().stream().filter(d -> d.id().equals(Long.toString(pid))).findFirst();
  }

  private SpawnedJvm spawn() throws Exception {
    SpawnedJvm jvm = SpawnedJvm.sleeper();
    spawned.add(jvm);
    return jvm;
  }

  private static void awaitTrue(String what, BooleanSupplier condition) throws InterruptedException {
    Instant deadline = Instant.now().plus(TIMEOUT);
    while (Instant.now().isBefore(deadline)) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(100);
    }
    if (!condition.getAsBoolean()) {
      fail("timed out waiting for: " + what);
    }
  }
}
