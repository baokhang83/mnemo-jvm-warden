package io.github.baokhang83.mnemo.warden.agent.sequence;

/**
 * The two, and only two, exit states of {@link ShrinkSequence#shrinkTo}. Sealed so a caller
 * switching on it must handle both explicitly &mdash; there is no way to silently ignore an
 * aborted shrink the way a boolean-plus-fields result would allow.
 */
public sealed interface ShrinkOutcome {

  /** The RSS gate passed and the kubelet confirmed the resize. */
  record Completed(long finalRssBytes) implements ShrinkOutcome {}

  /**
   * The RSS gate failed: {@code observedRssBytes} was not below {@code targetBytes} after the
   * deep GC + uncommit step. The cgroup was never touched.
   */
  record AbortedVerificationFailed(long observedRssBytes, long targetBytes) implements ShrinkOutcome {}
}
