package io.github.baokhang83.mnemo.warden.agent.heap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.baokhang83.mnemo.warden.agent.attach.AttachedJvm;
import io.github.baokhang83.mnemo.warden.agent.attach.TargetAttacher;
import io.github.baokhang83.mnemo.warden.agent.metrics.AgentMetrics;
import io.github.baokhang83.mnemo.warden.agent.testsupport.SpawnedJvm;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Exercises {@link TargetHeapControllerResolver} against real target JVMs (not mocks), same style
 * as {@link AttachedHeapControllerTest} &mdash; specifically the W-603 read-only gating: a
 * supported collector resolves and reports {@code warden_gc_supported=1}, an unsupported one
 * resolves to empty (not a thrown exception) and reports {@code warden_gc_supported=0}, and a
 * repeat resolve for the same attach doesn't change either outcome.
 *
 * <p>Every test here needs real {@code /proc}/cgroup access (via {@link RssReader}), so they're
 * Linux-only, same as {@code RssReaderTest}/{@code AttachedHeapControllerTest}; CI runs the target
 * directly on the runner, not in a pod, so these point cgroup resolution at the runner's real
 * {@code /sys/fs/cgroup} rather than the deployment-only {@code HOST_CGROUP_ROOT}.
 */
class TargetHeapControllerResolverTest {

  private static final Path REAL_CGROUP_ROOT = Path.of("/sys/fs/cgroup");

  @Test
  @EnabledOnOs(OS.LINUX)
  void resolvesASupportedCollectorAndRecordsItAsSupported() throws Exception {
    AgentMetrics metrics = new AgentMetrics();
    TargetHeapControllerResolver resolver = new TargetHeapControllerResolver(metrics);

    try (SpawnedJvm target = SpawnedJvm.sleeper("-XX:+UseG1GC", "-Xmx512m")) {
      target.awaitReady();
      try (AttachedJvm attached = TargetAttacher.attach(target.pid())) {
        Optional<HeapController> heap = resolver.resolve(attached, REAL_CGROUP_ROOT);

        assertTrue(heap.isPresent());
        assertEquals(Collector.G1, heap.get().capabilities().collector());
        assertTrue(metrics.render().contains("warden_gc_supported{collector=\"G1\"} 1"));
      }
    }
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void resolvesAnUnsupportedCollectorToEmptyRatherThanThrowingAndRecordsItAsUnsupported()
      throws Exception {
    AgentMetrics metrics = new AgentMetrics();
    TargetHeapControllerResolver resolver = new TargetHeapControllerResolver(metrics);

    try (SpawnedJvm target = SpawnedJvm.sleeper("-XX:+UseSerialGC", "-Xmx256m")) {
      target.awaitReady();
      try (AttachedJvm attached = TargetAttacher.attach(target.pid())) {
        Optional<HeapController> heap = resolver.resolve(attached, REAL_CGROUP_ROOT);

        assertTrue(heap.isEmpty());
        assertTrue(metrics.render().contains("warden_gc_supported{collector=\"OTHER\"} 0"));
      }
    }
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void repeatResolveForTheSameAttachStaysEmptyForAnUnsupportedCollector() throws Exception {
    AgentMetrics metrics = new AgentMetrics();
    TargetHeapControllerResolver resolver = new TargetHeapControllerResolver(metrics);

    try (SpawnedJvm target = SpawnedJvm.sleeper("-XX:+UseSerialGC", "-Xmx256m")) {
      target.awaitReady();
      try (AttachedJvm attached = TargetAttacher.attach(target.pid())) {
        Optional<HeapController> first = resolver.resolve(attached, REAL_CGROUP_ROOT);
        Optional<HeapController> second = resolver.resolve(attached, REAL_CGROUP_ROOT);

        assertTrue(first.isEmpty());
        assertTrue(second.isEmpty());
        // Cached, not re-thrown/re-logged: only one gauge line for this collector, not one per call.
        assertEquals(
            1,
            metrics.render().lines().filter(l -> l.startsWith("warden_gc_supported{")).count());
      }
    }
  }
}
