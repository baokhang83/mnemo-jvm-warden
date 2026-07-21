package io.github.baokhang83.mnemo.warden.agent.harness;

import io.github.baokhang83.mnemo.warden.agent.resize.PodResizeClient;
import io.github.baokhang83.mnemo.warden.agent.resize.ResizePort;
import java.time.Duration;

/**
 * #71 test-only driver, run via {@code kubectl exec} inside one replica's {@code warden}
 * container: attempts a resize PATCH against a <em>different</em> pod's name, using this
 * container's own bound service-account token. Proves (or disproves) that the
 * {@code ValidatingAdmissionPolicy} actually stops one replica's identity from resizing another
 * &mdash; reuses {@link PodResizeClient} exactly as production code does, rather than hand-rolling
 * a second raw HTTP client just for this check.
 *
 * <p>Usage: {@code java -cp warden-agent.jar ...harness.CrossPodResizeAttempt <targetPodName>
 * <containerName> <requestBytes> <limitBytes> <timeoutSeconds>}. Exits {@code 0} if the API
 * server allowed the PATCH, {@code 1} if it was denied (the expected outcome when {@code
 * targetPodName} isn't this container's own pod and the policy is in effect).
 */
public final class CrossPodResizeAttempt {

  static final int EXIT_ALLOWED = 0;
  static final int EXIT_DENIED = 1;

  private CrossPodResizeAttempt() {}

  public static void main(String[] args) {
    if (args.length != 5) {
      System.err.println(
          "usage: CrossPodResizeAttempt <targetPodName> <containerName> <requestBytes> <limitBytes> <timeoutSeconds>");
      System.exit(64); // EX_USAGE
      return;
    }
    String targetPodName = args[0];
    String containerName = args[1];
    long requestBytes = Long.parseLong(args[2]);
    long limitBytes = Long.parseLong(args[3]);
    Duration timeout = Duration.ofSeconds(Long.parseLong(args[4]));

    try {
      ResizePort resizeClient = PodResizeClient.forInClusterAgent();
      resizeClient.resizeMemory(targetPodName, containerName, requestBytes, limitBytes, timeout);
      System.out.println("CROSS_POD_RESIZE_RESULT=allowed");
      System.exit(EXIT_ALLOWED);
    } catch (Exception e) {
      System.out.println("CROSS_POD_RESIZE_RESULT=denied message=" + e.getMessage());
      System.exit(EXIT_DENIED);
    }
  }
}
