package io.github.baokhang83.mnemo.warden.controller;

import io.javaoperatorsdk.operator.Operator;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point for the Warden controller: registers {@link WardenPolicyReconciler} and starts
 * java-operator-sdk's informer machinery. No health server yet (unlike {@code WardenAgent}) —
 * not asked for by W-302's acceptance criteria; adding one now would be scope this ticket
 * doesn't need.
 */
public final class WardenController {

  private WardenController() {}

  public static void main(String[] args) throws InterruptedException {
    ControllerConfig config = ControllerConfig.fromEnv();
    Operator operator = new Operator();
    operator.register(new WardenPolicyReconciler(config));
    operator.start();

    Runtime.getRuntime().addShutdownHook(new Thread(operator::stop, "warden-controller-shutdown"));

    // Park the main thread until the JVM is terminated (SIGTERM runs the hook above) — same
    // pattern WardenAgent.main uses.
    new CountDownLatch(1).await();
  }
}
