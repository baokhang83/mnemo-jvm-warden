package io.github.baokhang83.mnemo.warden.agent.sequence;

import io.github.baokhang83.mnemo.warden.agent.AgentLog;
import io.github.baokhang83.mnemo.warden.agent.heap.HeapController;
import io.github.baokhang83.mnemo.warden.agent.resize.ResizePort;
import io.github.baokhang83.mnemo.warden.cache.CacheHook;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/**
 * The safety handshake M2 is named for: lower SoftMax &rarr; flush evictable app caches (W-502)
 * &rarr; deep GC + uncommit &rarr; verify RSS &lt; target &rarr; cgroup down (constitution §5).
 * Depends only on the {@link HeapController} and {@link ResizePort} ports, never a concrete GC
 * driver or the Kubernetes client (§2), so the ordering/gate logic here is exactly what's under
 * test &mdash; nothing platform-specific. {@code cacheHooks} is the one exception: it's a plain
 * {@link Map} of app-registered {@link CacheHook}s (from {@code CacheHookLookup}, over JMX), not a
 * port &mdash; there's nothing platform-specific to abstract away, just app-owner callbacks.
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
  private final Map<String, CacheHook> cacheHooks;
  private final String podName;
  private final String containerName;
  private final Duration gcTimeout;
  private final Duration resizeTimeout;

  public ShrinkSequence(
      HeapController heap,
      ResizePort resizeClient,
      Map<String, CacheHook> cacheHooks,
      String podName,
      String containerName,
      Duration gcTimeout,
      Duration resizeTimeout) {
    this.heap = heap;
    this.resizeClient = resizeClient;
    this.cacheHooks = cacheHooks;
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
    flushEvictableCaches();
    heap.deepGcAndUncommit(gcTimeout);

    long rss = heap.currentRss();
    if (rss >= limitBytes) {
      return new ShrinkOutcome.AbortedVerificationFailed(rss, limitBytes);
    }

    resizeClient.resizeMemory(podName, containerName, requestBytes, limitBytes, resizeTimeout);
    return new ShrinkOutcome.Completed(rss);
  }

  /**
   * Gives every registered app cache a chance to shed its evictable entries before the deep GC
   * runs, so those entries are garbage the same GC pass reclaims. A single hook's failure is
   * isolated to that hook (constitution §12): an app owner's cache is untrusted, third-party code
   * from this sequence's point of view, and must not be able to veto Warden's own memory-safety
   * handshake by throwing.
   */
  private void flushEvictableCaches() {
    for (Map.Entry<String, CacheHook> entry : cacheHooks.entrySet()) {
      try {
        entry.getValue().flushEvictable();
      } catch (RuntimeException e) {
        AgentLog.info("cache hook '" + entry.getKey() + "' flushEvictable() failed, continuing: " + e.getMessage());
      }
    }
  }
}
