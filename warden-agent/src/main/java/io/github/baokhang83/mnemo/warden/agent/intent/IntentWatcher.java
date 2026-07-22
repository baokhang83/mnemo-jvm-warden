package io.github.baokhang83.mnemo.warden.agent.intent;

import io.github.baokhang83.mnemo.warden.agent.AgentConfig;
import io.github.baokhang83.mnemo.warden.agent.AgentLog;
import io.github.baokhang83.mnemo.warden.agent.attach.AttachSupervisor;
import io.github.baokhang83.mnemo.warden.agent.attach.AttachedJvm;
import io.github.baokhang83.mnemo.warden.agent.cache.CacheHookLookup;
import io.github.baokhang83.mnemo.warden.agent.heap.AttachedHeapController;
import io.github.baokhang83.mnemo.warden.agent.heap.HeapController;
import io.github.baokhang83.mnemo.warden.agent.resize.PodResizeClient;
import io.github.baokhang83.mnemo.warden.agent.resize.ResizePort;
import io.github.baokhang83.mnemo.warden.agent.sequence.GrowSequence;
import io.github.baokhang83.mnemo.warden.agent.sequence.ShrinkSequence;
import io.github.baokhang83.mnemo.warden.cache.CacheHook;
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
 */
public final class IntentWatcher {

  private final AgentConfig config;
  private final AttachSupervisor attachSupervisor;
  private final PodIntentReader intentReader;
  private final Thread thread;
  private volatile boolean running;

  public IntentWatcher(AgentConfig config, AttachSupervisor attachSupervisor, PodIntentReader intentReader) {
    this.config = config;
    this.attachSupervisor = attachSupervisor;
    this.intentReader = intentReader;
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

    HeapController heap = AttachedHeapController.forTarget(target.get());
    ResizePort resizeClient = PodResizeClient.forInClusterAgent();
    Map<String, CacheHook> cacheHooks = CacheHookLookup.lookupAll(target.get());
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
      var outcome = sequence.shrinkTo(requestBytes, desiredLimit);
      AgentLog.info("intent-driven shrink: " + outcome);
    } else {
      GrowSequence sequence =
          new GrowSequence(
              heap, resizeClient, cacheHooks, config.podName(), config.targetContainerName(), config.resizeTimeout());
      var outcome = sequence.growTo(requestBytes, desiredLimit);
      AgentLog.info("intent-driven grow: " + outcome);
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
