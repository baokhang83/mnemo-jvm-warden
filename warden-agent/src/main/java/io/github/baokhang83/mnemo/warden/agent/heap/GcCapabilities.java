package io.github.baokhang83.mnemo.warden.agent.heap;

/**
 * What a target JVM's collector lets Warden do.
 *
 * @param collector the detected collector
 * @param supportsSoftMax whether a runtime soft heap ceiling ({@code SoftMaxHeapSize}) can be set;
 *     true for ZGC and Shenandoah, false for G1 (it has no runtime soft max)
 * @param supportsUncommit whether the collector returns freed pages to the OS
 */
public record GcCapabilities(
    Collector collector, boolean supportsSoftMax, boolean supportsUncommit) {

  /**
   * Whether Warden can shrink this JVM at all. Uncommit is the floor: without it, lowering a soft
   * max would never actually return memory to the OS, so there would be nothing to gain and the
   * agent should stay read-only.
   */
  public boolean supported() {
    return supportsUncommit;
  }
}
