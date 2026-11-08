package io.github.baokhang83.mnemo.warden.boost.client;

/**
 * The request reached a terminal phase other than {@code Granted} before this client gave up
 * waiting &mdash; a definite failure. Unlike {@link BoostAcquisitionTimeoutException}, there is
 * nothing to reconcile: the controller itself has decided this request will never be granted.
 */
public final class BoostRejectedException extends BoostException {

  private final String phase;

  BoostRejectedException(String requestId, String phase, String message) {
    super(requestId, "boost " + requestId + " reached terminal phase " + phase
        + (message == null ? "" : ": " + message));
    this.phase = phase;
  }

  /** The terminal phase the request reached (e.g. {@code "Rejected"}, {@code "Expired"}). */
  public String phase() {
    return phase;
  }
}
