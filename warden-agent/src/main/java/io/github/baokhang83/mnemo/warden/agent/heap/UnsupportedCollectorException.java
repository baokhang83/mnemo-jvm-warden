package io.github.baokhang83.mnemo.warden.agent.heap;

/**
 * Thrown when an operation requires a specific collector's capability and the target is running
 * a different one &mdash; e.g. asking for {@code SoftMaxHeapSize} control on a G1 target, which
 * would otherwise silently no-op (see {@code SoftMax}).
 */
public final class UnsupportedCollectorException extends RuntimeException {

  private final Collector actual;

  public UnsupportedCollectorException(String operation, Collector actual) {
    super("target is running " + actual + ", which does not support: " + operation);
    this.actual = actual;
  }

  /** The collector the target is actually running. */
  public Collector actual() {
    return actual;
  }
}
