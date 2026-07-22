package io.github.baokhang83.mnemo.warden.agent.intent;

import io.github.baokhang83.mnemo.warden.agent.AgentConfig;
import io.github.baokhang83.mnemo.warden.agent.AgentLog;
import io.github.baokhang83.mnemo.warden.agent.attach.AttachSupervisor;
import io.github.baokhang83.mnemo.warden.agent.attach.AttachedJvm;
import io.github.baokhang83.mnemo.warden.agent.cache.CacheHookLookup;
import io.github.baokhang83.mnemo.warden.agent.heap.HeapController;
import io.github.baokhang83.mnemo.warden.agent.heap.TargetHeapControllerResolver;
import io.github.baokhang83.mnemo.warden.agent.metrics.AgentMetrics;
import io.github.baokhang83.mnemo.warden.agent.resize.PodResizeClient;
import io.github.baokhang83.mnemo.warden.agent.resize.ResizePort;
import io.github.baokhang83.mnemo.warden.agent.sequence.GrowSequence;
import io.github.baokhang83.mnemo.warden.agent.sequence.ShrinkOutcome;
import io.github.baokhang83.mnemo.warden.agent.sequence.ShrinkSequence;
import io.github.baokhang83.mnemo.warden.cache.CacheHook;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Polls the agent's own pod for W-306 intent (via {@link PodIntentReader}) and drives {@link
 * ShrinkSequence}/{@link GrowSequence} against the {@link AttachSupervisor}'s currently attached
 * target &mdash; the wire between the controller's schedule decision and M2's already-built
 * safety handshake.
 *
 * <p>Direction is decided by comparing the intent's {@code limitBytes} to the target container's
 * <em>actual current</em> limit (read fresh every poll, not cached): smaller shrinks, larger
 * grows, equal is a no-op. Once a resize succeeds, the actual limit matches the intent, so the
 * very next poll's comparison is naturally a no-op &mdash; no separate "last applied" state to
 * keep in sync, and a failed/aborted attempt is simply retried on the next tick.
 *
 * <p>Also samples {@link AgentMetrics}'s RSS and GC gauges every tick a target is attached,
 * regardless of whether an intent resize fires (W-602) &mdash; this is the one place that already
 * resolves the target and holds a {@link HeapController} for it, so it is where the
 * resize/abort/bytes-reclaimed counters are recorded too. {@code ShrinkSequence}/{@code
 * GrowSequence} themselves stay free of any metrics dependency (constitution &sect;2). A metrics
 * sampling failure is isolated from the resize decision (constitution &sect;12): it must never be
 * the reason a real resize/abort is skipped.
 *
 * <p>A target whose collector doesn't support Warden at all ({@link
 * io.github.baokhang83.mnemo.warden.agent.heap.GcCapabilities#supported()} false, e.g. Serial/
 * Parallel/Epsilon) is a permanent state for that attach, not a transient poll failure &mdash; W-603
 * resolves it once per target identity, logs it once, and records it on {@link AgentMetrics} so
 * it's visible on {@code /metrics}, rather than re-attempting and re-logging every tick.
 */
public final class IntentWatcher {

  private final AgentConfig config;
  private final AttachSupervisor attachSupervisor;
  private final PodIntentReader intentReader;
  private final AgentMetrics metrics;
  private final Thread thread;
  private volatile boolean running;

  // Touched only from the poll thread (`thread`, below): no synchronization needed.
  private final TargetHeapControllerResolver heapResolver;

  public IntentWatcher(
      AgentConfig config, AttachSupervisor attachSupervisor, PodIntentReader intentReader, AgentMetrics metrics) {
    this.config = config;
    this.attachSupervisor = attachSupervisor;
    this.intentReader = intentReader;
    this.metrics = metrics;
    this.heapResolver = new TargetHeapControllerResolver(metrics);
    this.thread = new Thread(this::run, "warden-intent-watcher");
    this.thread.setDaemon(true);
  }

  public void start() {
    running = true;
    thread.start();
  }

  public void stop() {
    running = false;
    thread.interrupt();
    try {
      thread.join(config.intentPollInterval().multipliedBy(2).toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void run() {
    while (running) {
      try {
        pollOnce();
      } catch (Exception e) {
        AgentLog.info("intent poll failed, retrying next tick: " + e.getMessage());
      }
      sleep();
    }
  }

  private void pollOnce() throws Exception {
    Optional<AttachedJvm> target = attachSupervisor.currentTarget();
    if (target.isEmpty()) {
      return;
    }
    AttachedJvm jvm = target.get();

    OptionalLong rssBeforeThisTick = sampleMetrics(jvm);

    PodState state = intentReader.read();
    Optional<Intent> intent = state.intent();
    OptionalLong currentLimitBytes = state.currentLimitBytes();
    if (intent.isEmpty() || currentLimitBytes.isEmpty()) {
      return;
    }

    long desiredLimit = intent.get().limitBytes();
    long currentLimit = currentLimitBytes.getAsLong();
    if (desiredLimit == currentLimit) {
      return;
    }

    Optional<HeapController> heapOpt = heapResolver.resolve(jvm);
    if (heapOpt.isEmpty()) {
      return; // target's collector is unsupported; agent is read-only for it (W-603)
    }
    HeapController heap = heapOpt.get();
    ResizePort resizeClient = PodResizeClient.forInClusterAgent();
    Map<String, CacheHook> cacheHooks = CacheHookLookup.lookupAll(jvm);
    long requestBytes = intent.get().requestBytes();

    if (desiredLimit < currentLimit) {
      ShrinkSequence sequence =
          new ShrinkSequence(
              heap,
              resizeClient,
              cacheHooks,
              config.podName(),
              config.targetContainerName(),
              config.gcTimeout(),
              config.resizeTimeout());
      ShrinkOutcome outcome = sequence.shrinkTo(requestBytes, desiredLimit);
      AgentLog.info("intent-driven shrink: " + outcome);
      recordShrinkOutcome(outcome, rssBeforeThisTick);
    } else {
      GrowSequence sequence =
          new GrowSequence(
              heap, resizeClient, cacheHooks, config.podName(), config.targetContainerName(), config.resizeTimeout());
      var outcome = sequence.growTo(requestBytes, desiredLimit);
      AgentLog.info("intent-driven grow: " + outcome);
      metrics.incrementResize("grow");
    }
  }

  private void recordShrinkOutcome(ShrinkOutcome outcome, OptionalLong rssBeforeThisTick) {
    switch (outcome) {
      case ShrinkOutcome.Completed completed -> {
        metrics.incrementResize("shrink");
        if (rssBeforeThisTick.isPresent()) {
          metrics.addBytesReclaimed(rssBeforeThisTick.getAsLong() - completed.finalRssBytes());
        }
      }
      case ShrinkOutcome.AbortedVerificationFailed aborted -> metrics.incrementAborted();
    }
  }

  /**
   * Samples the target's current RSS and cumulative GC stats into {@link #metrics}. Isolated from
   * the resize decision (constitution &sect;12): a sampling failure is logged and swallowed here,
   * never allowed to stop an intent-driven resize/abort from being evaluated this tick. A no-op
   * (not a failure) when the target's collector is unsupported &mdash; {@link TargetHeapControllerResolver#resolve}
   * already logged and recorded that once.
   *
   * @return the sampled RSS, if sampling succeeded &mdash; reused as the "before" baseline for a
   *     shrink's bytes-reclaimed counter, so a shrink never triggers a second RSS read for it.
   */
  private OptionalLong sampleMetrics(AttachedJvm jvm) {
    try {
      Optional<HeapController> heap = heapResolver.resolve(jvm);
      if (heap.isEmpty()) {
        return OptionalLong.empty();
      }
      long rss = heap.get().currentRss();
      metrics.setRss(rss);

      List<GarbageCollectorMXBean> gcBeans =
          ManagementFactory.getPlatformMXBeans(jvm.mbeanConnection(), GarbageCollectorMXBean.class);
      for (GarbageCollectorMXBean bean : gcBeans) {
        long count = bean.getCollectionCount();
        long time = bean.getCollectionTime();
        if (count >= 0 && time >= 0) {
          metrics.setGcStats(bean.getName(), count, time);
        }
      }
      return OptionalLong.of(rss);
    } catch (Exception e) {
      AgentLog.info("metrics sampling failed, continuing: " + e.getMessage());
      return OptionalLong.empty();
    }
  }

  private void sleep() {
    try {
      Thread.sleep(config.intentPollInterval().toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
