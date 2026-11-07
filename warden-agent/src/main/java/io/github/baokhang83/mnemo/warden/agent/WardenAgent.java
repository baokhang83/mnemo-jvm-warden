package io.github.baokhang83.mnemo.warden.agent;

import io.github.baokhang83.mnemo.warden.agent.attach.AttachSupervisor;
import io.github.baokhang83.mnemo.warden.agent.boost.BoostApiServer;
import io.github.baokhang83.mnemo.warden.agent.boost.BoostRequestClient;
import io.github.baokhang83.mnemo.warden.agent.boost.BoostToken;
import io.github.baokhang83.mnemo.warden.agent.intent.IntentWatcher;
import io.github.baokhang83.mnemo.warden.agent.intent.PodIntentReader;
import io.github.baokhang83.mnemo.warden.agent.metrics.AgentMetrics;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point for the Warden sidecar agent.
 *
 * <p>Loads config, serves health probes, attaches to the target JVM (W-102), watches its own
 * pod's W-306 intent annotations and drives {@code ShrinkSequence}/{@code GrowSequence} against
 * the attached target, and shuts down cleanly on {@code SIGTERM}. Readiness reflects a real
 * attached target, not just the health server being up &mdash; see {@link HealthState}.
 *
 * <p>Also starts the B8 local Boost API ({@link BoostApiServer}) when {@link
 * AgentConfig#boostApiEnabled()} &mdash; optional, so existing scheduled-only sidecars are
 * unaffected.
 */
public final class WardenAgent {

  private WardenAgent() {}

  public static void main(String[] args) throws Exception {
    AgentConfig config = AgentConfig.fromEnv();
    AgentLog.info("starting (health port " + config.healthPort() + ", pod " + config.podName() + ")");

    HealthState health = new HealthState();
    AgentMetrics metrics = new AgentMetrics();
    HealthServer server = new HealthServer(config.healthPort(), health, metrics);
    server.start();

    AttachSupervisor attachSupervisor = new AttachSupervisor(health);
    attachSupervisor.start();
    AgentLog.info("attach supervisor started; waiting for target JVM");

    PodIntentReader intentReader = PodIntentReader.forInClusterAgent(config.podName(), config.targetContainerName());
    IntentWatcher intentWatcher = new IntentWatcher(config, attachSupervisor, intentReader, metrics);
    intentWatcher.start();
    AgentLog.info("intent watcher started; polling own pod every " + config.intentPollInterval());

    BoostApiServer boostApiServer = null;
    if (config.boostApiEnabled()) {
      if (config.podUid() == null) {
        throw new IllegalArgumentException(
            AgentConfig.ENV_POD_UID + " is not set; required when " + AgentConfig.ENV_BOOST_POLICY_REF + " is set");
      }
      String token = BoostToken.generate();
      BoostToken.write(Path.of(config.boostTokenPath()), token);
      boostApiServer =
          new BoostApiServer(
              config.boostPort(),
              token,
              BoostRequestClient.forInClusterAgent(),
              config.boostPolicyRef(),
              config.podName(),
              config.podUid(),
              config.targetContainerName(),
              config.boostAcquireWait());
      boostApiServer.start();
      AgentLog.info("boost API enabled for policy " + config.boostPolicyRef());
    }
    BoostApiServer boostApi = boostApiServer;

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  AgentLog.info("shutting down");
                  intentWatcher.stop();
                  attachSupervisor.stop();
                  if (boostApi != null) {
                    boostApi.stop();
                  }
                  server.stop();
                },
                "warden-shutdown"));

    // Park the main thread until the JVM is terminated (SIGTERM runs the hook above).
    new CountDownLatch(1).await();
  }
}
