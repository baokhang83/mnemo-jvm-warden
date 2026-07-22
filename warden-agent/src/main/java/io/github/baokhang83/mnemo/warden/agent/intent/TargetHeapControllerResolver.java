package io.github.baokhang83.mnemo.warden.agent.intent;

import io.github.baokhang83.mnemo.warden.agent.AgentLog;
import io.github.baokhang83.mnemo.warden.agent.attach.AttachedJvm;
import io.github.baokhang83.mnemo.warden.agent.heap.AttachedHeapController;
import io.github.baokhang83.mnemo.warden.agent.heap.HeapController;
import io.github.baokhang83.mnemo.warden.agent.heap.UnsupportedCollectorException;
import io.github.baokhang83.mnemo.warden.agent.metrics.AgentMetrics;
import java.io.IOException;
import java.util.Optional;

/**
 * Resolves an attached target's {@link HeapController} once per attach and caches the verdict
 * &mdash; including a permanently-unsupported collector &mdash; keyed on {@link AttachedJvm}
 * reference identity.
 *
 * <p>Construction resolves the target's cgroup directory via a bounded-depth filesystem walk
 * ({@code RssReader.resolveCgroupRoot}) &mdash; expensive enough that redoing it every poll tick
 * just to sample a gauge would violate the agent's lean-footprint principle (constitution
 * &sect;4). An unsupported collector ({@link UnsupportedCollectorException} on construction) is a
 * permanent fact about the attach, not a transient failure, so it is resolved and logged exactly
 * once here &mdash; not re-attempted and re-logged on every tick &mdash; and recorded on {@link
 * AgentMetrics} so it's visible on {@code /metrics} (W-603).
 */
final class TargetHeapControllerResolver {

  private final AgentMetrics metrics;

  private AttachedJvm cachedTargetJvm;
  private Optional<HeapController> cachedHeap = Optional.empty();

  TargetHeapControllerResolver(AgentMetrics metrics) {
    this.metrics = metrics;
  }

  /**
   * @return the target's {@link HeapController}, or empty if its collector doesn't support Warden
   *     at all
   */
  Optional<HeapController> resolve(AttachedJvm target) throws IOException {
    if (cachedTargetJvm != target) {
      cachedTargetJvm = target;
      try {
        HeapController heap = AttachedHeapController.forTarget(target);
        cachedHeap = Optional.of(heap);
        metrics.setGcSupported(heap.capabilities().collector().toString(), true);
      } catch (UnsupportedCollectorException e) {
        cachedHeap = Optional.empty();
        metrics.setGcSupported(e.actual().toString(), false);
        AgentLog.info(
            "target's collector (" + e.actual() + ") does not support Warden resize operations;"
                + " agent is read-only for this target");
      }
    }
    return cachedHeap;
  }
}
