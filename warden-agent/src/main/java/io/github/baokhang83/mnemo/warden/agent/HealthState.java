package io.github.baokhang83.mnemo.warden.agent;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The agent's readiness signal, shared between the lifecycle thread and the health endpoints
 * (hence thread-safe).
 *
 * <p>Liveness is deliberately <em>not</em> stored here: if an HTTP handler runs at all, the
 * process is alive, so {@code /healthz} needs no flag. Readiness is explicit and is the seam for
 * M1 &mdash; the agent will stay not-ready until it has attached to the target JVM.
 */
public final class HealthState {

  private final AtomicBoolean ready = new AtomicBoolean(false);

  /** Whether the agent is ready to do its job. */
  public boolean ready() {
    return ready.get();
  }

  public void markReady() {
    ready.set(true);
  }

  public void markNotReady() {
    ready.set(false);
  }
}
