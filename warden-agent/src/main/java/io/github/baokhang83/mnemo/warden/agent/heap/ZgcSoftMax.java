package io.github.baokhang83.mnemo.warden.agent.heap;

import com.sun.management.HotSpotDiagnosticMXBean;
import io.github.baokhang83.mnemo.warden.agent.attach.AttachedJvm;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Reads and writes ZGC's runtime soft heap ceiling ({@code SoftMaxHeapSize}) on an attached
 * target.
 *
 * <p>Deliberately not (yet) a {@code HeapController}: {@code currentRss()} needs W-105's
 * cgroup/NMT reader and {@code deepGcAndUncommit()} needs W-104. A {@code ZgcHeapController}
 * gets assembled once those exist and the M2 resize state machine actually needs the whole
 * contract.
 *
 * <p>{@link #forTarget(AttachedJvm)} checks the target's real collector via {@link GcDetector}
 * before ever touching the VM option: {@code HotSpotDiagnosticMXBean.setVMOption} succeeds
 * silently on collectors that ignore {@code SoftMaxHeapSize} (G1 included, verified against a
 * real target), so there is no exception to catch afterward &mdash; the guard has to come first.
 */
public final class ZgcSoftMax {

  private static final String OPTION = "SoftMaxHeapSize";

  private final HotSpotDiagnosticMXBean diagnostics;

  private ZgcSoftMax(HotSpotDiagnosticMXBean diagnostics) {
    this.diagnostics = diagnostics;
  }

  /**
   * @throws UnsupportedCollectorException if the target isn't running ZGC
   * @throws IOException if the JMX connection to the target fails
   */
  public static ZgcSoftMax forTarget(AttachedJvm target) throws IOException {
    List<GarbageCollectorMXBean> gcBeans =
        ManagementFactory.getPlatformMXBeans(target.mbeanConnection(), GarbageCollectorMXBean.class);
    Collector collector = GcDetector.detect(gcBeans).collector();
    if (collector != Collector.ZGC) {
      throw new UnsupportedCollectorException(OPTION, collector);
    }

    HotSpotDiagnosticMXBean diagnostics =
        ManagementFactory.getPlatformMXBean(target.mbeanConnection(), HotSpotDiagnosticMXBean.class);
    return new ZgcSoftMax(diagnostics);
  }

  /** The current soft heap ceiling, in bytes. */
  public long softMaxHeapSize() {
    return Long.parseLong(diagnostics.getVMOption(OPTION).getValue());
  }

  /** Sets the soft heap ceiling, in bytes. Takes effect immediately; ZGC needs no restart. */
  public void setSoftMaxHeapSize(long bytes) {
    diagnostics.setVMOption(OPTION, Long.toString(bytes));
  }
}
