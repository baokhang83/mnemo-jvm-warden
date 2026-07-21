package io.github.baokhang83.mnemo.warden.agent.attach;

import io.github.baokhang83.mnemo.warden.agent.AgentLog;
import io.github.baokhang83.mnemo.warden.agent.HealthState;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Keeps the agent attached to the target JVM for the pod's whole life, including across target
 * restarts.
 *
 * <p>"Attach" and "reattach" are the same loop: whether there is no target yet, the target just
 * exited, or the very first attempt at boot, {@link #run()} always falls back into locate-then-
 * attach and retries on the same {@link #POLL_INTERVAL}. There is no separate reconnect path to
 * keep in sync with the first attach.
 *
 * <p>Drives {@link HealthState}: {@code /readyz} answers 200 only while a target is attached and
 * alive.
 */
public final class AttachSupervisor {

  /** Backoff between attach attempts, and the liveness-poll period once attached. A single
   *  interval keeps this class to one dial instead of two, and stays well clear of a busy-wait
   *  (constitution §4). */
  static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

  private final HealthState health;
  private final Supplier<Optional<Long>> locateTarget;
  private final AtomicReference<AttachedJvm> current = new AtomicReference<>();
  private final Thread thread;
  private volatile boolean running;

  public AttachSupervisor(HealthState health) {
    this(health, TargetLocator::findTarget);
  }

  /**
   * Package-private seam so tests can point the supervisor at one known target without depending
   * on {@link TargetLocator}'s single-other-JVM assumption &mdash; which does not hold on a dev
   * machine running unrelated JVMs, only inside the pod it is designed for.
   */
  AttachSupervisor(HealthState health, Supplier<Optional<Long>> locateTarget) {
    this.health = health;
    this.locateTarget = locateTarget;
    this.thread = new Thread(this::run, "warden-attach-supervisor");
    this.thread.setDaemon(true);
  }

  /** Starts the supervising loop on a background daemon thread. */
  public void start() {
    running = true;
    thread.start();
  }

  /** Stops the loop, marks the agent not ready, and closes any live attachment. */
  public void stop() {
    running = false;
    thread.interrupt();
    try {
      thread.join(POLL_INTERVAL.multipliedBy(2).toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    health.markNotReady();
    closeCurrent();
  }

  /** The currently attached target, if any. */
  public Optional<AttachedJvm> currentTarget() {
    return Optional.ofNullable(current.get());
  }

  private void run() {
    while (running) {
      Optional<Long> pid = locateTarget.get();
      if (pid.isEmpty()) {
        sleep();
        continue;
      }

      AttachedJvm attached;
      try {
        attached = TargetAttacher.attach(pid.get());
      } catch (IOException e) {
        AgentLog.info("attach failed, retrying: " + e.getMessage());
        sleep();
        continue;
      }

      current.set(attached);
      health.markReady();
      AgentLog.info("attached to target pid " + attached.pid());

      while (running && attached.isAlive()) {
        sleep();
      }

      health.markNotReady();
      closeCurrent();
      if (running) {
        AgentLog.info("target detached; retrying");
      }
    }
  }

  private void closeCurrent() {
    AttachedJvm attached = current.getAndSet(null);
    if (attached != null) {
      attached.close();
    }
  }

  private void sleep() {
    try {
      Thread.sleep(POLL_INTERVAL.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
