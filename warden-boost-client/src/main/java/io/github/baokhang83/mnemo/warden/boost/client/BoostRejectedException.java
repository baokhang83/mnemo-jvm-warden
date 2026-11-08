package io.github.baokhang83.mnemo.warden.boost.client;

/**
 * Acquisition observed a phase treated as terminal by the client before returning a lease.
 * Unlike {@link BoostAcquisitionTimeoutException}, the client observed that phase, but it did
 * not receive permission to start work. Resource cleanup may still be in progress, or an
 * expired request may retain resources until the operation is confirmed stopped and completion
 * is requested.
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
