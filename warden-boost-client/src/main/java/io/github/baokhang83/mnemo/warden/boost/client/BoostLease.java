package io.github.baokhang83.mnemo.warden.boost.client;

import java.io.IOException;

/**
 * A granted boost, returned only by {@link WardenBoostClient#acquireBoost}. Application code
 * must close it only after the boosted operation and its child work finish &mdash; this client
 * deliberately has no async/cancellation convenience wrapper (deferred, per the dev plan);
 * {@code try (BoostLease lease = ...) { ... }} is the supported shape.
 */
public final class BoostLease implements AutoCloseable {

  private final WardenBoostClient client;
  private final String requestId;

  BoostLease(WardenBoostClient client, String requestId) {
    this.client = client;
    this.requestId = requestId;
  }

  /** The stable ID this lease was granted under. */
  public String requestId() {
    return requestId;
  }

  /**
   * Re-fetches the current status &mdash; e.g. to confirm a release actually finished
   * reclaiming memory after {@link #close()} (which does not wait for that).
   */
  public BoostStatus status() throws IOException, InterruptedException {
    return client.inspect(requestId);
  }

  /**
   * Requests completion. Idempotent: safe to call more than once, and safe even if a reader
   * already observed {@code Completed}. Does not block until memory reclamation finishes — call
   * {@link #status()} afterward if the caller needs that confirmation.
   */
  @Override
  public void close() throws IOException {
    try {
      client.release(requestId);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted releasing boost " + requestId, e);
    }
  }
}
