package io.github.baokhang83.mnemo.warden.agent.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.baokhang83.mnemo.warden.agent.attach.AttachedJvm;
import io.github.baokhang83.mnemo.warden.agent.attach.TargetAttacher;
import io.github.baokhang83.mnemo.warden.agent.heap.Collector;
import io.github.baokhang83.mnemo.warden.agent.heap.HeapController;
import io.github.baokhang83.mnemo.warden.agent.metrics.AgentMetrics;
import io.github.baokhang83.mnemo.warden.agent.testsupport.SpawnedJvm;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Exercises {@link TargetHeapControllerResolver} against real target JVMs (not mocks), same style
 * as {@code AttachedHeapControllerTest} &mdash; specifically the W-603 read-only gating: a
 * supported collector resolves and reports {@code warden_gc_supported=1}, an unsupported one
 * resolves to empty (not a thrown exception) and reports {@code warden_gc_supported=0}, and a
 * repeat resolve for the same attach doesn't change either outcome.
 *
 * <p>Real {@code /proc}/cgroup access needed for the supported-collector path (via {@code
 * RssReader}), so Linux-only, same as {@code AttachedHeapControllerTest}.
 */
class TargetHeapControllerResolverTest {

  @Test
  @EnabledOnOs(OS.LINUX)
  void resolvesASupportedCollectorAndRecordsItAsSupported() throws Exception {
    AgentMetrics metrics = new AgentMetrics();
    TargetHeapControllerResolver resolver = new TargetHeapControllerResolver(metrics);

    try (SpawnedJvm target = SpawnedJvm.sleeper("-XX:+UseG1GC", "-Xmx512m")) {
      target.awaitReady();
      try (AttachedJvm attached = TargetAttacher.attach(target.pid())) {
        Optional<HeapController> heap = resolver.resolve(attached);

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
        Optional<HeapController> heap = resolver.resolve(attached);

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
        Optional<HeapController> first = resolver.resolve(attached);
        Optional<HeapController> second = resolver.resolve(attached);

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
