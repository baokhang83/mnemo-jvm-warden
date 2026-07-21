package io.github.baokhang83.mnemo.warden.agent.sequence;

import io.github.baokhang83.mnemo.warden.agent.heap.HeapController;
import io.github.baokhang83.mnemo.warden.agent.resize.ResizePort;
import java.io.IOException;
import java.time.Duration;

/**
 * The mirror image of {@link ShrinkSequence}'s handshake: cgroup up &rarr; raise SoftMax
 * (constitution &sect;5: "grow &rarr; resize &rarr; raise"). Depends only on the {@link
 * HeapController} and {@link ResizePort} ports, never a concrete GC driver or the Kubernetes
 * client (&sect;2), for the same reason {@code ShrinkSequence} does.
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
  private final String podName;
  private final String containerName;
  private final Duration resizeTimeout;

  public GrowSequence(
      HeapController heap,
      ResizePort resizeClient,
      String podName,
      String containerName,
      Duration resizeTimeout) {
    this.heap = heap;
    this.resizeClient = resizeClient;
    this.podName = podName;
    this.containerName = containerName;
    this.resizeTimeout = resizeTimeout;
  }

  /**
   * Grows the target to {@code requestBytes}/{@code limitBytes}: resizes the cgroup first, then
   * raises {@code SoftMax} to match, once the kubelet has confirmed the extra room actually
   * exists.
   */
  public GrowOutcome growTo(long requestBytes, long limitBytes) throws IOException, InterruptedException {
    resizeClient.resizeMemory(podName, containerName, requestBytes, limitBytes, resizeTimeout);
    heap.setSoftMax(limitBytes);
    return new GrowOutcome(limitBytes);
  }
}
