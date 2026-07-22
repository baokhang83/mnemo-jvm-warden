package io.github.baokhang83.mnemo.warden.agent.sequence;

import io.github.baokhang83.mnemo.warden.agent.AgentLog;
import io.github.baokhang83.mnemo.warden.agent.heap.HeapController;
import io.github.baokhang83.mnemo.warden.agent.resize.ResizePort;
import io.github.baokhang83.mnemo.warden.cache.CacheHook;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/**
 * The mirror image of {@link ShrinkSequence}'s handshake: cgroup up &rarr; raise SoftMax &rarr;
 * pre-warm app caches (W-503) (constitution &sect;5: "grow &rarr; resize &rarr; raise"). Depends
 * only on the {@link HeapController} and {@link ResizePort} ports, never a concrete GC driver or
 * the Kubernetes client (&sect;2), for the same reason {@code ShrinkSequence} does. {@code
 * cacheHooks}, like {@code ShrinkSequence}'s, is a plain {@link Map} of app-registered {@link
 * CacheHook}s, not a port &mdash; nothing platform-specific to abstract away.
 *
 * <p>There is no verification gate here, unlike shrink. Raising {@code SoftMax} after the
 * cgroup limit is already up cannot allocate into space the cgroup hasn't granted, so the
 * failure mode {@code ShrinkSequence}'s gate exists to catch &mdash; OOMKilling the pod &mdash;
 * isn't reachable in this order. Adding a check here anyway would be a gate against nothing
 * (&sect;1).
 */
public final class GrowSequence {

  private final HeapController heap;
  private final ResizePort resizeClient;
  private final Map<String, CacheHook> cacheHooks;
  private final String podName;
  private final String containerName;
  private final Duration resizeTimeout;

  public GrowSequence(
      HeapController heap,
      ResizePort resizeClient,
      Map<String, CacheHook> cacheHooks,
      String podName,
      String containerName,
      Duration resizeTimeout) {
    this.heap = heap;
    this.resizeClient = resizeClient;
    this.cacheHooks = cacheHooks;
    this.podName = podName;
    this.containerName = containerName;
    this.resizeTimeout = resizeTimeout;
  }

  /**
   * Grows the target to {@code requestBytes}/{@code limitBytes}: resizes the cgroup first, then
   * raises {@code SoftMax} to match once the kubelet has confirmed the extra room actually
   * exists, then pre-warms every registered app cache now that both the cgroup and the heap
   * ceiling already reflect the new size.
   */
  public GrowOutcome growTo(long requestBytes, long limitBytes) throws IOException, InterruptedException {
    resizeClient.resizeMemory(podName, containerName, requestBytes, limitBytes, resizeTimeout);
    heap.setSoftMax(limitBytes);
    preWarmCaches();
    return new GrowOutcome(limitBytes);
  }

  /**
   * Gives every registered app cache a chance to repopulate now that there's room for it on both
   * axes (cgroup, SoftMax). A single hook's failure is isolated to that hook (constitution §12),
   * the same isolation {@code ShrinkSequence} applies to {@code flushEvictable()}: an app owner's
   * cache is untrusted, third-party code from this sequence's point of view, and must not be able
   * to fail a grow that has already succeeded by throwing.
   */
  private void preWarmCaches() {
    for (Map.Entry<String, CacheHook> entry : cacheHooks.entrySet()) {
      try {
        entry.getValue().preWarm();
      } catch (RuntimeException e) {
        AgentLog.info("cache hook '" + entry.getKey() + "' preWarm() failed, continuing: " + e.getMessage());
      }
    }
  }
}
