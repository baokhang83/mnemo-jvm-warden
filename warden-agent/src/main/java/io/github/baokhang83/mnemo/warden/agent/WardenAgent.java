package io.github.baokhang83.mnemo.warden.agent;

import io.github.baokhang83.mnemo.warden.agent.attach.AttachSupervisor;
import io.github.baokhang83.mnemo.warden.agent.intent.IntentWatcher;
import io.github.baokhang83.mnemo.warden.agent.intent.PodIntentReader;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point for the Warden sidecar agent.
 *
 * <p>Loads config, serves health probes, attaches to the target JVM (W-102), watches its own
 * pod's W-306 intent annotations and drives {@code ShrinkSequence}/{@code GrowSequence} against
 * the attached target, and shuts down cleanly on {@code SIGTERM}. Readiness reflects a real
 * attached target, not just the health server being up &mdash; see {@link HealthState}.
 */
public final class WardenAgent {

  private WardenAgent() {}

  public static void main(String[] args) throws Exception {
    AgentConfig config = AgentConfig.fromEnv();
    AgentLog.info("starting (health port " + config.healthPort() + ", pod " + config.podName() + ")");

    HealthState health = new HealthState();
    HealthServer server = new HealthServer(config.healthPort(), health);
    server.start();

    AttachSupervisor attachSupervisor = new AttachSupervisor(health);
    attachSupervisor.start();
    AgentLog.info("attach supervisor started; waiting for target JVM");

    PodIntentReader intentReader = PodIntentReader.forInClusterAgent(config.podName(), config.targetContainerName());
    IntentWatcher intentWatcher = new IntentWatcher(config, attachSupervisor, intentReader);
    intentWatcher.start();
    AgentLog.info("intent watcher started; polling own pod every " + config.intentPollInterval());

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  AgentLog.info("shutting down");
                  intentWatcher.stop();
                  attachSupervisor.stop();
                  server.stop();
                },
                "warden-shutdown"));

    // Park the main thread until the JVM is terminated (SIGTERM runs the hook above).
    new CountDownLatch(1).await();
  }
}
