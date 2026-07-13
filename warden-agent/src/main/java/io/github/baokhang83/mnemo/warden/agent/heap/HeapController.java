package io.github.baokhang83.mnemo.warden.agent.heap;

/**
 * Collector-agnostic control over a target JVM's heap.
 *
 * <p>Implementations are per-collector drivers (W-103 / W-106 / W-107). Callers &mdash; notably
 * the M2 resize state machine &mdash; depend only on this port and on {@link #capabilities()},
 * never on the concrete collector, so the safety logic stays GC-blind (constitution §2).
 */
public interface HeapController {

  /** Current resident set size (RSS) of the target process, in bytes. */
  long currentRss();

  /**
   * Requests a soft ceiling on the heap, in bytes. On collectors without a runtime soft max (e.g.
   * G1) this is a no-op &mdash; check {@link GcCapabilities#supportsSoftMax()} first.
   */
  void setSoftMax(long bytes);

  /**
   * Forces a deep GC and returns freed pages to the OS, lowering RSS as far as the collector
   * allows.
   */
  void deepGcAndUncommit();

  /** What this controller's collector actually supports. */
  GcCapabilities capabilities();
}
