package io.github.baokhang83.mnemo.warden.boost.client;

import java.io.IOException;

/** Base type for an {@link WardenBoostClient#acquireBoost} failure; always names the request. */
public abstract class BoostException extends IOException {

  private final String requestId;

  BoostException(String requestId, String message) {
    super(message);
    this.requestId = requestId;
  }

  /** The stable ID of the request this failure concerns. */
  public String requestId() {
    return requestId;
  }
}
