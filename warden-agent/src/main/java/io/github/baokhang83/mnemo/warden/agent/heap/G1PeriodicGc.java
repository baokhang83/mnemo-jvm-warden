package io.github.baokhang83.mnemo.warden.agent.heap;

import com.sun.management.HotSpotDiagnosticMXBean;
import io.github.baokhang83.mnemo.warden.agent.attach.AttachedJvm;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;

/**
 * Reads and writes G1's idle-triggered collection interval ({@code G1PeriodicGCInterval}) on an
 * attached target.
 *
 * <p>G1 has no runtime soft max ({@link SoftMax} already rejects it) and {@link DeepGc} already
 * covers on-demand uncommit &mdash; this is G1's one collector-specific lever: verified against a
 * real target that the flag is {@code {manageable}} (settable at runtime over JMX, same mechanism
 * as {@code SoftMaxHeapSize}) but defaults to {@code 0} (disabled), meaning G1 never proactively
 * collects on idle unless told to.
 *
 * <p>Unlike {@link SoftMax}/{@link DeepGc}, this flag doesn't exist on ZGC or Shenandoah &mdash;
 * ZGC's concurrent cycle runs continuously, with no "idle" concept to trigger on &mdash; so {@link
 * #forTarget(AttachedJvm)} checks the collector directly rather than adding a single-caller field
 * to {@link GcCapabilities}.
 */
public final class G1PeriodicGc {

  private static final String OPTION = "G1PeriodicGCInterval";

  private final HotSpotDiagnosticMXBean diagnostics;

  private G1PeriodicGc(HotSpotDiagnosticMXBean diagnostics) {
    this.diagnostics = diagnostics;
  }

  /**
   * @throws UnsupportedCollectorException if the target isn't running G1
   * @throws IOException if the JMX connection to the target fails
   */
  public static G1PeriodicGc forTarget(AttachedJvm target) throws IOException {
    List<GarbageCollectorMXBean> gcBeans =
        ManagementFactory.getPlatformMXBeans(target.mbeanConnection(), GarbageCollectorMXBean.class);
    Collector collector = GcDetector.detect(gcBeans).collector();
    if (collector != Collector.G1) {
      throw new UnsupportedCollectorException(OPTION, collector);
    }

    HotSpotDiagnosticMXBean diagnostics =
        ManagementFactory.getPlatformMXBean(target.mbeanConnection(), HotSpotDiagnosticMXBean.class);
    return new G1PeriodicGc(diagnostics);
  }

  /** The current idle-triggered collection interval. {@link Duration#ZERO} means disabled. */
  public Duration periodicGcInterval() {
    return Duration.ofMillis(Long.parseLong(diagnostics.getVMOption(OPTION).getValue()));
  }

  /** Sets the idle-triggered collection interval. Takes effect immediately; no restart needed. */
  public void setPeriodicGcInterval(Duration interval) {
    diagnostics.setVMOption(OPTION, Long.toString(interval.toMillis()));
  }
}
