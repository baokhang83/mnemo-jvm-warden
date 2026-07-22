package io.github.baokhang83.mnemo.warden.agent.heap;

import io.github.baokhang83.mnemo.warden.agent.AgentLog;
import io.github.baokhang83.mnemo.warden.agent.attach.AttachedJvm;
import io.github.baokhang83.mnemo.warden.agent.metrics.AgentMetrics;
import java.io.IOException;
import java.nio.file.Path;
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
 *
 * <p>Lives in this package (rather than alongside its one caller, {@code IntentWatcher}) so it
 * can reach {@link AttachedHeapController}'s package-private {@code forTarget(AttachedJvm, Path)}
 * seam &mdash; the same one {@code AttachedHeapControllerTest} uses to point cgroup resolution at
 * a real {@code /sys/fs/cgroup} on CI, which runs the target directly on the runner rather than in
 * a pod with the {@code hostPath} mount.
 */
public final class TargetHeapControllerResolver {

  private final AgentMetrics metrics;

  private AttachedJvm cachedTargetJvm;
  private Optional<HeapController> cachedHeap = Optional.empty();

  public TargetHeapControllerResolver(AgentMetrics metrics) {
    this.metrics = metrics;
  }

  /**
   * @return the target's {@link HeapController}, or empty if its collector doesn't support Warden
   *     at all
   */
  public Optional<HeapController> resolve(AttachedJvm target) throws IOException {
    return resolve(target, Path.of(RssReader.HOST_CGROUP_ROOT));
  }

  /**
   * Package-private seam so tests (and CI) can point cgroup resolution at a real {@code
   * /sys/fs/cgroup} instead of the deployment-only {@link RssReader#HOST_CGROUP_ROOT} &mdash;
   * mirrors exactly how {@code AttachedHeapControllerTest} exercises {@link
   * AttachedHeapController} itself.
   */
  Optional<HeapController> resolve(AttachedJvm target, Path hostCgroupRoot) throws IOException {
    if (cachedTargetJvm != target) {
      cachedTargetJvm = target;
      try {
        HeapController heap = AttachedHeapController.forTarget(target, hostCgroupRoot);
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
