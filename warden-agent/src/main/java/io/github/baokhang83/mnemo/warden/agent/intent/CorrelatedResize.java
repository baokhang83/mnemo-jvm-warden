package io.github.baokhang83.mnemo.warden.agent.intent;

import io.github.baokhang83.mnemo.warden.agent.heap.HeapController;
import io.github.baokhang83.mnemo.warden.agent.resize.ResizePort;
import io.github.baokhang83.mnemo.warden.agent.sequence.GrowSequence;
import io.github.baokhang83.mnemo.warden.agent.sequence.ShrinkSequence;
import io.github.baokhang83.mnemo.warden.agent.sequence.ShrinkOutcome;
import io.github.baokhang83.mnemo.warden.cache.CacheHook;
import io.github.baokhang83.mnemo.warden.protocol.ResizeAcknowledgement;
import io.github.baokhang83.mnemo.warden.protocol.ResizeIntent;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/** Reuses the existing handshake, then gathers fresh evidence for the exact transition. */
public final class CorrelatedResize {
  @FunctionalInterface
  public interface Observation { PodState read() throws IOException, InterruptedException; }

  private CorrelatedResize() {}

  public static ResizeAcknowledgement execute(ResizeIntent intent, String podName,
      HeapController heap, ResizePort resize, Map<String, CacheHook> hooks,
      Duration gcTimeout, Duration resizeTimeout, Observation observation, Clock clock)
      throws IOException, InterruptedException {
    PodState before = observation.read();
    requireCurrent(intent, before);
    // G1 has no runtime soft max (GcCapabilities.supportsSoftMax() is false for it), so the only
    // bound that applies is the fixed launch-time ceiling — G1 workloads must be launched with
    // -Xmx already covering every boost heapTarget they'll ever request (B11; see
    // docs/warden-boost.md's G1 section for why this is a weaker guarantee than ZGC/Shenandoah).
    if (intent.heapBytes() > heap.maxHeapBytes()) throw new IOException("UnsupportedHeapTarget");
    if (before.currentRequestBytes().isEmpty() || before.currentLimitBytes().isEmpty())
      throw new IOException("AppliedAllocationUnavailable");

    // Identity is re-read at the resource mutation boundary; production ResizePort also uses
    // an API-server resourceVersion/UID precondition so replacement cannot race this check.
    ResizePort fenced = (pod, container, request, limit, timeout) -> {
      requireCurrent(intent, observation.read());
      resize.resizeMemory(pod, container, request, limit, timeout);
    };
    long request = before.currentRequestBytes().getAsLong();
    long limit = before.currentLimitBytes().getAsLong();
    if (intent.requestBytes() < request || intent.limitBytes() < limit) {
      var outcome = new ShrinkSequence(heap, fenced, hooks, podName, intent.containerName(), gcTimeout, resizeTimeout)
          .shrinkTo(intent.requestBytes(), intent.limitBytes(), intent.heapBytes());
      if (!(outcome instanceof ShrinkOutcome.Completed)) throw new IOException("VerificationFailed");
    } else if (intent.requestBytes() != request || intent.limitBytes() != limit) {
      new GrowSequence(heap, fenced, hooks, podName, intent.containerName(), resizeTimeout)
          .growTo(intent.requestBytes(), intent.limitBytes(), intent.heapBytes());
    } else {
      // Equal container resources still require actual JVM preparation for a new transition.
      heap.setSoftMax(intent.heapBytes());
    }
    // G1 has no soft max to read back; its only evidence is the allocation transition above
    // actually landing, already re-confirmed below, so the heap figure is vacuously the request.
    long observedHeap = heap.capabilities().supportsSoftMax() ? heap.softMaxBytes() : intent.heapBytes();
    PodState after = observation.read();
    requireCurrent(intent, after);
    if (after.currentRequestBytes().orElse(-1) != intent.requestBytes()
        || after.currentLimitBytes().orElse(-1) != intent.limitBytes()
        || observedHeap != intent.heapBytes()) throw new IOException("PreparationNotConfirmed");
    return new ResizeAcknowledgement(intent, true, "Applied", intent.requestBytes(),
        intent.limitBytes(), observedHeap, clock.instant());
  }

  public static void requireCurrent(ResizeIntent intent, PodState state) throws IOException {
    if (!intent.podUid().equals(state.podUid()) || !intent.containerId().equals(state.containerId())
        || state.correlatedIntent().isEmpty() || !intent.equals(state.correlatedIntent().get()))
      throw new IOException("StaleTransitionIdentity");
  }
}
