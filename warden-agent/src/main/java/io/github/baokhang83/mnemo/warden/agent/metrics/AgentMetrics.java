package io.github.baokhang83.mnemo.warden.agent.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * What Warden did, in Prometheus text exposition format &mdash; resizes, aborts, bytes reclaimed,
 * the target's current RSS, the target's cumulative GC pause time (W-602), and whether the
 * target's collector is one Warden can resize at all (W-603).
 *
 * <p>Deliberately not a generic metrics-registry library: {@code warden-agent} has zero runtime
 * dependencies by design (see its {@code pom.xml}), and this covers exactly the fixed, known set
 * of metrics the ticket names, not an arbitrary future one (constitution &sect;1). The per-collector
 * GC fields are the one dynamic part &mdash; the target's collector bean names (e.g. {@code "G1
 * Young Generation"}) aren't known until a target is attached.
 *
 * <p>Thread-safe: {@link #render()} runs on the health server's HTTP handler thread while {@code
 * IntentWatcher}'s poll thread concurrently updates counters and gauges.
 */
public final class AgentMetrics {

  private final LongAdder growResizes = new LongAdder();
  private final LongAdder shrinkResizes = new LongAdder();
  private final LongAdder aborts = new LongAdder();
  private final LongAdder bytesReclaimed = new LongAdder();
  private final AtomicLong rssWorkingSetBytes = new AtomicLong();
  private final Map<String, GcStats> gcStatsByCollector = new ConcurrentHashMap<>();
  private volatile GcSupportState gcSupportState;

  private record GcStats(long collectionCount, long collectionTimeMillis) {}

  private record GcSupportState(String collector, boolean supported) {}

  /** Records a completed resize. {@code direction} is {@code "grow"} or {@code "shrink"}. */
  public void incrementResize(String direction) {
    switch (direction) {
      case "grow" -> growResizes.increment();
      case "shrink" -> shrinkResizes.increment();
      default -> throw new IllegalArgumentException("unknown resize direction: " + direction);
    }
  }

  /** Records a shrink aborted by the RSS verification gate. */
  public void incrementAborted() {
    aborts.increment();
  }

  /** Adds to the cumulative bytes-reclaimed counter. Negative/zero deltas are dropped: a counter never goes down. */
  public void addBytesReclaimed(long bytes) {
    if (bytes > 0) {
      bytesReclaimed.add(bytes);
    }
  }

  /** Sets the target's last observed working-set RSS, in bytes. */
  public void setRss(long workingSetBytes) {
    rssWorkingSetBytes.set(workingSetBytes);
  }

  /** Sets the target's cumulative GC stats for one collector bean (e.g. {@code "G1 Young Generation"}). */
  public void setGcStats(String collectorName, long collectionCount, long collectionTimeMillis) {
    gcStatsByCollector.put(collectorName, new GcStats(collectionCount, collectionTimeMillis));
  }

  /**
   * Sets whether Warden can resize the currently attached target's collector (W-603) &mdash; {@code
   * false} means the agent is read-only for it (e.g. Serial/Parallel/Epsilon, which can't uncommit).
   */
  public void setGcSupported(String collector, boolean supported) {
    gcSupportState = new GcSupportState(collector, supported);
  }

  /** Renders the current state in Prometheus text exposition format (version 0.0.4). */
  public String render() {
    StringBuilder out = new StringBuilder();

    out.append("# HELP warden_resizes_total Resizes completed, by direction.\n");
    out.append("# TYPE warden_resizes_total counter\n");
    out.append("warden_resizes_total{direction=\"grow\"} ").append(growResizes.sum()).append('\n');
    out.append("warden_resizes_total{direction=\"shrink\"} ").append(shrinkResizes.sum()).append('\n');

    out.append("# HELP warden_aborts_total Shrinks aborted by the RSS verification gate.\n");
    out.append("# TYPE warden_aborts_total counter\n");
    out.append("warden_aborts_total ").append(aborts.sum()).append('\n');

    out.append("# HELP warden_bytes_reclaimed_total Cumulative bytes reclaimed by completed shrinks.\n");
    out.append("# TYPE warden_bytes_reclaimed_total counter\n");
    out.append("warden_bytes_reclaimed_total ").append(bytesReclaimed.sum()).append('\n');

    out.append("# HELP warden_target_rss_working_set_bytes Target's last observed working-set RSS.\n");
    out.append("# TYPE warden_target_rss_working_set_bytes gauge\n");
    out.append("warden_target_rss_working_set_bytes ").append(rssWorkingSetBytes.get()).append('\n');

    out.append("# HELP warden_target_gc_collections_total Target's cumulative GC collection count, by collector bean.\n");
    out.append("# TYPE warden_target_gc_collections_total counter\n");
    gcStatsByCollector.forEach(
        (collector, stats) ->
            out.append("warden_target_gc_collections_total{collector=\"")
                .append(escape(collector))
                .append("\"} ")
                .append(stats.collectionCount())
                .append('\n'));

    out.append(
        "# HELP warden_target_gc_collection_time_seconds_total Target's cumulative GC collection time, by collector bean.\n");
    out.append("# TYPE warden_target_gc_collection_time_seconds_total counter\n");
    gcStatsByCollector.forEach(
        (collector, stats) ->
            out.append("warden_target_gc_collection_time_seconds_total{collector=\"")
                .append(escape(collector))
                .append("\"} ")
                .append(stats.collectionTimeMillis() / 1000.0)
                .append('\n'));

    GcSupportState supportState = gcSupportState;
    if (supportState != null) {
      out.append(
          "# HELP warden_gc_supported Whether Warden can resize the attached target's collector (1) or is read-only for it (0).\n");
      out.append("# TYPE warden_gc_supported gauge\n");
      out.append("warden_gc_supported{collector=\"")
          .append(escape(supportState.collector()))
          .append("\"} ")
          .append(supportState.supported() ? 1 : 0)
          .append('\n');
    }

    return out.toString();
  }

  private static String escape(String label) {
    return label.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
