package io.github.baokhang83.mnemo.warden.agent;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point for the Warden sidecar agent.
 *
 * <p>This is the no-op runtime skeleton (W-003): it loads config, serves health probes, and
 * shuts down cleanly on {@code SIGTERM}. JVM heap control and the in-place resize arrive in
 * M1/M2; today the agent is deliberately ready as soon as it is serving.
 */
public final class WardenAgent {

  private WardenAgent() {}

  public static void main(String[] args) throws IOException, InterruptedException {
    AgentConfig config = AgentConfig.fromEnv();
    AgentLog.info("starting (health port " + config.healthPort() + ")");

    HealthState health = new HealthState();
    HealthServer server = new HealthServer(config.healthPort(), health);
    server.start();

    // No-op agent: there is nothing to attach to yet, so it is ready once it is serving.
    health.markReady();
    AgentLog.info("ready");

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  AgentLog.info("shutting down");
                  health.markNotReady();
                  server.stop();
                },
                "warden-shutdown"));

    // Park the main thread until the JVM is terminated (SIGTERM runs the hook above).
    new CountDownLatch(1).await();
  }
}
