package io.github.baokhang83.mnemo.warden.boost.client;

/**
 * One observation of a Boost request's lifecycle, as the local sidecar API reports it.
 *
 * @param requestId the stable ID identifying this request
 * @param phase the controller-owned lifecycle phase (e.g. {@code "Granted"}), as a raw string
 *     &mdash; never parsed into an enum here, since this client has no dependency on {@code
 *     warden-crd-model} (the app embedding it should not need the controller's own CRD types)
 * @param message the most recent status condition's message, if any
 */
public record BoostStatus(String requestId, String phase, String message) {

  private static final String GRANTED = "Granted";

  /** True once the request has reached {@code Granted}. */
  public boolean isGranted() {
    return GRANTED.equals(phase);
  }

  /** True once the request has reached any phase it will not move on from. */
  public boolean isTerminal() {
    return switch (phase) {
      case "Completed", "Rejected", "Failed", "Expired", "Orphaned" -> true;
      default -> false;
    };
  }
}
