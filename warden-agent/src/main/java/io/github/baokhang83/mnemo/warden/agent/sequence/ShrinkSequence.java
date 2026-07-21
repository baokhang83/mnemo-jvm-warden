package io.github.baokhang83.mnemo.warden.agent.sequence;

import io.github.baokhang83.mnemo.warden.agent.heap.HeapController;
import io.github.baokhang83.mnemo.warden.agent.resize.ResizePort;
import java.io.IOException;
import java.time.Duration;

/**
 * The safety handshake M2 is named for: lower SoftMax &rarr; deep GC + uncommit &rarr; verify
 * RSS &lt; target &rarr; cgroup down (constitution §5). Depends only on the {@link HeapController}
 * and {@link ResizePort} ports, never a concrete GC driver or the Kubernetes client (§2), so the
 * ordering/gate logic here is exactly what's under test &mdash; nothing platform-specific.
 *
 * <p>The gate is literal: {@code rss < limitBytes}, no headroom margin. Any richer safety policy
 * (a margin, a veto, an emergency-grow trigger) belongs to the controller-side principles this
 * repo already tracks as separate issues (W-402, W-403) &mdash; adding it here would be
 * speculative generality this class doesn't need (§1).
 *
 * <p>On an aborted (verification-failed) shrink, {@code SoftMax} is deliberately left lowered,
 * not reverted: it's advisory, not a hard limit, so leaving it low carries no OOM risk and gives
 * a subsequent retry a head start. Reverting would need a getter on {@link HeapController} that
 * nothing else needs yet, for a rollback that buys no safety (§1).
 */
public final class ShrinkSequence {

  private final HeapController heap;
  private final ResizePort resizeClient;
  private final String podName;
  private final String containerName;
  private final Duration gcTimeout;
  private final Duration resizeTimeout;

  public ShrinkSequence(
      HeapController heap,
      ResizePort resizeClient,
      String podName,
      String containerName,
      Duration gcTimeout,
      Duration resizeTimeout) {
    this.heap = heap;
    this.resizeClient = resizeClient;
    this.podName = podName;
    this.containerName = containerName;
    this.gcTimeout = gcTimeout;
    this.resizeTimeout = resizeTimeout;
  }

  /**
   * Attempts to shrink the target's memory to {@code requestBytes}/{@code limitBytes}. If the
   * post-GC RSS is not below {@code limitBytes}, aborts without ever calling the resize client
   * &mdash; the cgroup limit is only ever lowered on a verified precondition (§5).
   */
  public ShrinkOutcome shrinkTo(long requestBytes, long limitBytes) throws IOException, InterruptedException {
    heap.setSoftMax(limitBytes);
    heap.deepGcAndUncommit(gcTimeout);

    long rss = heap.currentRss();
    if (rss >= limitBytes) {
      return new ShrinkOutcome.AbortedVerificationFailed(rss, limitBytes);
    }

    resizeClient.resizeMemory(podName, containerName, requestBytes, limitBytes, resizeTimeout);
    return new ShrinkOutcome.Completed(rss);
  }
}
