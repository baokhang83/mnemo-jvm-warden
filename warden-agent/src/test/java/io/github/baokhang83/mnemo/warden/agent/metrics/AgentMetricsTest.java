package io.github.baokhang83.mnemo.warden.agent.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AgentMetricsTest {

  @Test
  void freshRegistryRendersZeroValues() {
    AgentMetrics metrics = new AgentMetrics();

    String rendered = metrics.render();

    assertTrue(rendered.contains("warden_resizes_total{direction=\"grow\"} 0"));
    assertTrue(rendered.contains("warden_resizes_total{direction=\"shrink\"} 0"));
    assertTrue(rendered.contains("warden_aborts_total 0"));
    assertTrue(rendered.contains("warden_bytes_reclaimed_total 0"));
    assertTrue(rendered.contains("warden_target_rss_working_set_bytes 0"));
  }

  @Test
  void incrementResizeCountsByDirection() {
    AgentMetrics metrics = new AgentMetrics();

    metrics.incrementResize("grow");
    metrics.incrementResize("grow");
    metrics.incrementResize("shrink");

    String rendered = metrics.render();
    assertTrue(rendered.contains("warden_resizes_total{direction=\"grow\"} 2"));
    assertTrue(rendered.contains("warden_resizes_total{direction=\"shrink\"} 1"));
  }

  @Test
  void incrementResizeRejectsUnknownDirection() {
    AgentMetrics metrics = new AgentMetrics();

    assertThrows(IllegalArgumentException.class, () -> metrics.incrementResize("sideways"));
  }

  @Test
  void abortsAccumulate() {
    AgentMetrics metrics = new AgentMetrics();

    metrics.incrementAborted();
    metrics.incrementAborted();

    assertTrue(metrics.render().contains("warden_aborts_total 2"));
  }

  @Test
  void bytesReclaimedAccumulatesAndDropsNonPositiveDeltas() {
    AgentMetrics metrics = new AgentMetrics();

    metrics.addBytesReclaimed(1000);
    metrics.addBytesReclaimed(500);
    metrics.addBytesReclaimed(-200); // RSS jitter: never subtract from a counter
    metrics.addBytesReclaimed(0);

    assertTrue(metrics.render().contains("warden_bytes_reclaimed_total 1500"));
  }

  @Test
  void rssGaugeReflectsLastSetValue() {
    AgentMetrics metrics = new AgentMetrics();

    metrics.setRss(100);
    metrics.setRss(250);

    assertTrue(metrics.render().contains("warden_target_rss_working_set_bytes 250"));
  }

  @Test
  void gcStatsRenderPerCollectorWithSecondsConversion() {
    AgentMetrics metrics = new AgentMetrics();

    metrics.setGcStats("G1 Young Generation", 42, 1500);
    metrics.setGcStats("G1 Old Generation", 3, 250);

    String rendered = metrics.render();
    assertTrue(rendered.contains("warden_target_gc_collections_total{collector=\"G1 Young Generation\"} 42"));
    assertTrue(rendered.contains("warden_target_gc_collections_total{collector=\"G1 Old Generation\"} 3"));
    assertTrue(rendered.contains("warden_target_gc_collection_time_seconds_total{collector=\"G1 Young Generation\"} 1.5"));
    assertTrue(rendered.contains("warden_target_gc_collection_time_seconds_total{collector=\"G1 Old Generation\"} 0.25"));
  }

  @Test
  void gcStatsForACollectorOverwriteRatherThanAccumulate() {
    AgentMetrics metrics = new AgentMetrics();

    metrics.setGcStats("G1 Young Generation", 1, 100);
    metrics.setGcStats("G1 Young Generation", 5, 400);

    String rendered = metrics.render();
    assertTrue(rendered.contains("warden_target_gc_collections_total{collector=\"G1 Young Generation\"} 5"));
    assertEquals(1, rendered.lines().filter(l -> l.startsWith("warden_target_gc_collections_total{")).count());
  }

  @Test
  void collectorLabelValuesAreEscaped() {
    AgentMetrics metrics = new AgentMetrics();

    metrics.setGcStats("weird \"quoted\" \\ name", 1, 1);

    assertTrue(metrics.render().contains("collector=\"weird \\\"quoted\\\" \\\\ name\""));
  }
}
