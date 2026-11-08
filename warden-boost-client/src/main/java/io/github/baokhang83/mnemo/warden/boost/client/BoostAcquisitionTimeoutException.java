package io.github.baokhang83.mnemo.warden.boost.client;

/**
 * This client gave up waiting for a grant before the request reached {@code Granted} or any
 * terminal phase &mdash; the outcome is <strong>uncertain</strong>, not failed. The request may
 * still be granted by the controller after this exception is thrown; per the dev plan, a
 * timeout must never be treated as proof the operation could not have started.
 *
 * <p>Deliberately does not release or otherwise touch the request: doing so on a merely
 * uncertain outcome risks releasing a grant that is about to succeed. Callers should retain
 * {@link #requestId()} (log it, persist it, whatever fits) and either retry acquisition with
 * the same ID later ({@code WardenBoostClient#acquireBoost(String, String, Duration)}) or
 * inspect it ({@code WardenBoostClient#inspect(String)}) to reconcile what actually happened.
 */
public final class BoostAcquisitionTimeoutException extends BoostException {

  private final String lastObservedPhase;

  BoostAcquisitionTimeoutException(String requestId, String lastObservedPhase) {
    super(requestId, "timed out waiting for boost " + requestId
        + " to be granted (last observed phase: " + lastObservedPhase + ")");
    this.lastObservedPhase = lastObservedPhase;
  }

  /** The last phase observed before this client stopped waiting. */
  public String lastObservedPhase() {
    return lastObservedPhase;
  }
}
