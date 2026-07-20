package io.github.baokhang83.mnemo.warden.agent.heap;

import com.sun.management.HotSpotDiagnosticMXBean;
import io.github.baokhang83.mnemo.warden.agent.attach.AttachedJvm;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Reads and writes a collector's runtime soft heap ceiling ({@code SoftMaxHeapSize}) on an
 * attached target &mdash; ZGC and Shenandoah both honor it (verified against a real Shenandoah
 * target on the project's actual runtime image, {@code eclipse-temurin:21-jdk}, since it's the
 * exact same VM option on both collectors); G1 does not.
 *
 * <p>Deliberately not (yet) a {@code HeapController}: {@code currentRss()} and
 * {@code deepGcAndUncommit()} belong to {@link RssReader} and {@link DeepGc}. A
 * {@code HeapController} implementation gets assembled once the M2 resize state machine actually
 * needs the whole contract.
 *
 * <p>{@link #forTarget(AttachedJvm)} checks the target's real capabilities via {@link GcDetector}
 * before ever touching the VM option: {@code HotSpotDiagnosticMXBean.setVMOption} succeeds
 * silently on collectors that ignore {@code SoftMaxHeapSize} (G1 included, verified against a
 * real target), so there is no exception to catch afterward &mdash; the guard has to come first.
 */
public final class SoftMax {

  private static final String OPTION = "SoftMaxHeapSize";

  private final HotSpotDiagnosticMXBean diagnostics;

  private SoftMax(HotSpotDiagnosticMXBean diagnostics) {
    this.diagnostics = diagnostics;
  }

  /**
   * @throws UnsupportedCollectorException if the target's collector doesn't support a runtime
   *     soft max (currently: anything but ZGC and Shenandoah)
   * @throws IOException if the JMX connection to the target fails
   */
  public static SoftMax forTarget(AttachedJvm target) throws IOException {
    List<GarbageCollectorMXBean> gcBeans =
        ManagementFactory.getPlatformMXBeans(target.mbeanConnection(), GarbageCollectorMXBean.class);
    GcCapabilities capabilities = GcDetector.detect(gcBeans);
    if (!capabilities.supportsSoftMax()) {
      throw new UnsupportedCollectorException(OPTION, capabilities.collector());
    }

    HotSpotDiagnosticMXBean diagnostics =
        ManagementFactory.getPlatformMXBean(target.mbeanConnection(), HotSpotDiagnosticMXBean.class);
    return new SoftMax(diagnostics);
  }

  /** The current soft heap ceiling, in bytes. */
  public long softMaxHeapSize() {
    return Long.parseLong(diagnostics.getVMOption(OPTION).getValue());
  }

  /** Sets the soft heap ceiling, in bytes. Takes effect immediately; no restart needed. */
  public void setSoftMaxHeapSize(long bytes) {
    diagnostics.setVMOption(OPTION, Long.toString(bytes));
  }
}
