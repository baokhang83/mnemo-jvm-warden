package io.github.baokhang83.mnemo.warden.agent.heap;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;

/**
 * Identifies a JVM's collector from its {@link GarbageCollectorMXBean} names and maps it to
 * {@link GcCapabilities}.
 *
 * <p>The bean names are a reliable runtime fingerprint of the <em>active</em> collector (e.g.
 * {@code "ZGC Cycles"}, {@code "Shenandoah Pauses"}, {@code "G1 Young Generation"}), independent
 * of how the JVM was launched.
 *
 * <p>W-101 detects the local (agent) JVM via {@link #detectLocal()}; W-102 will point the same
 * {@link #detect(List)} logic at the attached target's beans.
 */
public final class GcDetector {

  private GcDetector() {}

  /** Detects the collector of the current JVM. */
  public static GcCapabilities detectLocal() {
    return detect(ManagementFactory.getGarbageCollectorMXBeans());
  }

  /** Detects the collector from a JVM's GC MXBeans. */
  public static GcCapabilities detect(List<GarbageCollectorMXBean> gcBeans) {
    List<String> names = gcBeans.stream().map(GarbageCollectorMXBean::getName).toList();
    return capabilitiesFor(classify(names));
  }

  /** Classifies a collector from its GC MXBean names. Package-private so it can be tested directly. */
  static Collector classify(List<String> gcBeanNames) {
    String joined = String.join(" ", gcBeanNames).toLowerCase(Locale.ROOT);
    if (joined.contains("zgc")) {
      return Collector.ZGC;
    }
    if (joined.contains("shenandoah")) {
      return Collector.SHENANDOAH;
    }
    if (joined.contains("g1")) {
      return Collector.G1;
    }
    return Collector.OTHER;
  }

  /** Maps a collector to what it lets Warden do. */
  static GcCapabilities capabilitiesFor(Collector collector) {
    return switch (collector) {
      // ZGC and Shenandoah both uncommit and honour a runtime SoftMaxHeapSize.
      case ZGC, SHENANDOAH -> new GcCapabilities(collector, true, true);
      // G1 uncommits (periodic GC) but has no runtime soft max.
      case G1 -> new GcCapabilities(Collector.G1, false, true);
      // Serial / Parallel / Epsilon / unknown: no runtime uncommit control -> read-only.
      case OTHER -> new GcCapabilities(Collector.OTHER, false, false);
    };
  }
}
