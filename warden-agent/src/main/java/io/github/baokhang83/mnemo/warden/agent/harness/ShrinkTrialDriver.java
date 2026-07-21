package io.github.baokhang83.mnemo.warden.agent.harness;

import io.github.baokhang83.mnemo.warden.agent.attach.AttachedJvm;
import io.github.baokhang83.mnemo.warden.agent.attach.TargetAttacher;
import io.github.baokhang83.mnemo.warden.agent.attach.TargetLocator;
import io.github.baokhang83.mnemo.warden.agent.heap.AttachedHeapController;
import io.github.baokhang83.mnemo.warden.agent.heap.HeapController;
import io.github.baokhang83.mnemo.warden.agent.resize.PodResizeClient;
import io.github.baokhang83.mnemo.warden.agent.resize.ResizePort;
import io.github.baokhang83.mnemo.warden.agent.sequence.ShrinkOutcome;
import io.github.baokhang83.mnemo.warden.agent.sequence.ShrinkSequence;
import java.time.Duration;
import java.util.Optional;

/**
 * W-206 test-only driver, run via {@code kubectl exec} inside the {@code warden} container
 * (never the production entry point &mdash; {@link io.github.baokhang83.mnemo.warden.agent.WardenAgent}
 * is unchanged). Nothing wires {@code ShrinkSequence} into the running agent yet (that's M3's
 * intent-handoff, W-306); this exists solely so {@code deploy/verify-oomkill-safety.sh} can drive
 * one real shrink attempt against the real attached target and real Kubernetes API, using exactly
 * the production collaborators ({@link AttachedHeapController}, {@link PodResizeClient}) rather
 * than fakes.
 *
 * <p>Usage: {@code java -cp warden-agent.jar ...harness.ShrinkTrialDriver <podName> <containerName>
 * <requestBytes> <limitBytes> <gcTimeoutSeconds> <resizeTimeoutSeconds>}. Exits {@code 0} on a
 * completed shrink, {@code 3} on a verification-gate abort, {@code 2} if no target was found, and
 * {@code 70} on any other failure. {@code 70}, not the JVM's own default-uncaught-exception exit
 * code ({@code 1}), is deliberate: a bare uncaught exception exits {@code 1} too, which would be
 * indistinguishable from a deliberately chosen {@code EXIT_ABORTED} of {@code 1} &mdash;
 * indistinguishable enough that an early version of this driver used {@code 1} for both, and
 * {@code deploy/verify-oomkill-safety.sh} logged a false PASS when a real JMX connection failure
 * happened to share the abort scenario's expected exit code.
 */
public final class ShrinkTrialDriver {

  static final int EXIT_COMPLETED = 0;
  static final int EXIT_NO_TARGET = 2;
  static final int EXIT_ABORTED = 3;
  static final int EXIT_UNEXPECTED_FAILURE = 70; // EX_SOFTWARE

  private ShrinkTrialDriver() {}

  public static void main(String[] args) {
    try {
      run(args);
    } catch (Throwable t) {
      t.printStackTrace();
      System.err.println("SHRINK_TRIAL_RESULT=unexpected-failure");
      System.exit(EXIT_UNEXPECTED_FAILURE);
    }
  }

  private static void run(String[] args) throws Exception {
    if (args.length != 6) {
      System.err.println(
          "usage: ShrinkTrialDriver <podName> <containerName> <requestBytes> <limitBytes> "
              + "<gcTimeoutSeconds> <resizeTimeoutSeconds>");
      System.exit(64); // EX_USAGE
      return;
    }
    String podName = args[0];
    String containerName = args[1];
    long requestBytes = Long.parseLong(args[2]);
    long limitBytes = Long.parseLong(args[3]);
    Duration gcTimeout = Duration.ofSeconds(Long.parseLong(args[4]));
    Duration resizeTimeout = Duration.ofSeconds(Long.parseLong(args[5]));

    Optional<Long> targetPid = TargetLocator.findTarget();
    if (targetPid.isEmpty()) {
      System.err.println("SHRINK_TRIAL_RESULT=no-target");
      System.exit(EXIT_NO_TARGET);
      return;
    }

    try (AttachedJvm target = TargetAttacher.attach(targetPid.get())) {
      HeapController heap = AttachedHeapController.forTarget(target);
      ResizePort resizeClient = PodResizeClient.forInClusterAgent();
      ShrinkSequence sequence =
          new ShrinkSequence(heap, resizeClient, podName, containerName, gcTimeout, resizeTimeout);

      ShrinkOutcome outcome = sequence.shrinkTo(requestBytes, limitBytes);
      switch (outcome) {
        case ShrinkOutcome.Completed completed -> {
          System.out.println("SHRINK_TRIAL_RESULT=completed finalRssBytes=" + completed.finalRssBytes());
          System.exit(EXIT_COMPLETED);
        }
        case ShrinkOutcome.AbortedVerificationFailed aborted -> {
          System.out.println(
              "SHRINK_TRIAL_RESULT=aborted observedRssBytes="
                  + aborted.observedRssBytes()
                  + " targetBytes="
                  + aborted.targetBytes());
          System.exit(EXIT_ABORTED);
        }
      }
    }
  }
}
