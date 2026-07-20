package io.github.baokhang83.mnemo.warden.agent.resize;

/**
 * Thrown when a resize PATCH succeeded but the kubelet hadn't applied it to {@code
 * status.containerStatuses[]} by the caller's deadline.
 */
public final class ResizeTimeoutException extends RuntimeException {

  public ResizeTimeoutException(String podName, String containerName) {
    super("kubelet did not confirm the resize of " + podName + "/" + containerName + " in time");
  }
}
