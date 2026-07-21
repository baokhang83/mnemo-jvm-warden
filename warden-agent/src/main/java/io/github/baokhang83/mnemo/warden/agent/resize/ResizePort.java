package io.github.baokhang83.mnemo.warden.agent.resize;

import java.io.IOException;
import java.time.Duration;

/**
 * The narrow seam W-203's shrink/grow sequences depend on, instead of the concrete {@link
 * PodResizeClient} &mdash; constitution §2: safety and orchestration logic depends on ports, not
 * directly on the Kubernetes client. Lets the sequencing/gate logic be unit tested with a fake,
 * no real API server required.
 */
public interface ResizePort {

  /**
   * PATCHes {@code containerName}'s memory request/limit and blocks until the kubelet has
   * confirmed it, or {@code timeout} elapses.
   *
   * @throws ResizeTimeoutException if the kubelet hadn't applied it by the deadline
   */
  void resizeMemory(String podName, String containerName, long requestBytes, long limitBytes, Duration timeout)
      throws IOException, InterruptedException;
}
