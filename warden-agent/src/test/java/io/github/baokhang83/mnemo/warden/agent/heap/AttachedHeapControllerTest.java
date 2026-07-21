package io.github.baokhang83.mnemo.warden.agent.heap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import io.github.baokhang83.mnemo.warden.agent.attach.AttachedJvm;
import io.github.baokhang83.mnemo.warden.agent.attach.TargetAttacher;
import io.github.baokhang83.mnemo.warden.agent.testsupport.SpawnedJvm;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Exercises {@link AttachedHeapController} against real target JVMs &mdash; specifically the
 * wiring between {@link SoftMax}, {@link DeepGc}, and {@link RssReader} it composes, and the
 * G1 no-op/rejection behavior {@link HeapController}'s contract promises.
 *
 * <p>Every test here needs real {@code /proc}/cgroup access (via {@link RssReader}), so they're
 * Linux-only, same as {@code RssReaderTest}; CI runs the target directly on the runner, not in a
 * pod, so these point cgroup resolution at the runner's real {@code /sys/fs/cgroup} rather than
 * the deployment-only {@code HOST_CGROUP_ROOT}.
 */
class AttachedHeapControllerTest {

  private static final Path REAL_CGROUP_ROOT = Path.of("/sys/fs/cgroup");

  @Test
  @EnabledOnOs(OS.LINUX)
  void composesSoftMaxDeepGcAndRssReaderOnARealZgcTarget() throws Exception {
    try (SpawnedJvm target =
        SpawnedJvm.garbageChurner("-XX:+UseZGC", "-Xmx512m", "-Xms64m", "-XX:ZUncommitDelay=5")) {
      target.awaitReady();
      try (AttachedJvm attached = TargetAttacher.attach(target.pid())) {
        target.awaitStdoutLine("allocated", Duration.ofSeconds(10));

        AttachedHeapController heap = AttachedHeapController.forTarget(attached, REAL_CGROUP_ROOT);

        assertEquals(Collector.ZGC, heap.capabilities().collector());
        assertTrue(heap.capabilities().supportsSoftMax());
        assertTrue(heap.currentRss() > 0, "a live target must show some resident memory");

        // Delegation check: setSoftMax through the controller, read back through a second SoftMax
        // instance on the same attached JVM — proves the call actually reached the target, not
        // just that no exception was thrown.
        heap.setSoftMax(300L * 1024 * 1024);
        assertEquals(300L * 1024 * 1024, SoftMax.forTarget(attached).softMaxHeapSize());

        heap.deepGcAndUncommit(Duration.ofSeconds(20));
      }
    }
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void setSoftMaxIsANoOpOnARealG1TargetRatherThanThrowing() throws Exception {
    try (SpawnedJvm target = SpawnedJvm.sleeper("-XX:+UseG1GC", "-Xmx512m")) {
      target.awaitReady();
      try (AttachedJvm attached = TargetAttacher.attach(target.pid())) {
        AttachedHeapController heap = AttachedHeapController.forTarget(attached, REAL_CGROUP_ROOT);

        assertEquals(Collector.G1, heap.capabilities().collector());
        assertTrue(heap.capabilities().supportsUncommit());
        assertTrue(!heap.capabilities().supportsSoftMax());

        assertDoesNotThrow(() -> heap.setSoftMax(300L * 1024 * 1024));
      }
    }
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void rejectsCleanlyOnARealSerialGcTarget() throws Exception {
    try (SpawnedJvm target = SpawnedJvm.sleeper("-XX:+UseSerialGC", "-Xmx256m")) {
      target.awaitReady();
      try (AttachedJvm attached = TargetAttacher.attach(target.pid())) {
        UnsupportedCollectorException thrown =
            assertThrows(
                UnsupportedCollectorException.class,
                () -> AttachedHeapController.forTarget(attached, REAL_CGROUP_ROOT));

        assertEquals(Collector.OTHER, thrown.actual());
      }
    }
  }
}
