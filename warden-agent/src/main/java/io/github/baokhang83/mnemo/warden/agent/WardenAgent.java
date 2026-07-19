package io.github.baokhang83.mnemo.warden.agent;

import io.github.baokhang83.mnemo.warden.agent.attach.AttachSupervisor;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point for the Warden sidecar agent.
 *
 * <p>Loads config, serves health probes, attaches to the target JVM (W-102), and shuts down
 * cleanly on {@code SIGTERM}. Readiness now reflects a real attached target, not just the health
 * server being up &mdash; see {@link HealthState}.
 */
public final class WardenAgent {

  private WardenAgent() {}

  public static void main(String[] args) throws IOException, InterruptedException {
    AgentConfig config = AgentConfig.fromEnv();
    AgentLog.info("starting (health port " + config.healthPort() + ")");

    HealthState health = new HealthState();
    HealthServer server = new HealthServer(config.healthPort(), health);
    server.start();

    AttachSupervisor attachSupervisor = new AttachSupervisor(health);
    attachSupervisor.start();
    AgentLog.info("attach supervisor started; waiting for target JVM");

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  AgentLog.info("shutting down");
                  attachSupervisor.stop();
                  server.stop();
                },
                "warden-shutdown"));

    // Park the main thread until the JVM is terminated (SIGTERM runs the hook above).
    new CountDownLatch(1).await();
  }
}
