package io.github.baokhang83.mnemo.warden.agent;

import java.time.Duration;
import java.util.function.Function;

/**
 * Immutable agent configuration, read from the environment with sane defaults.
 *
 * <p>Environment variables are the Kubernetes-native way to configure a sidecar, so no config
 * file or YAML library is pulled in.
 *
 * <p>{@code podName} and {@code targetContainerName} are required, with no default (W-306):
 * unlike the other fields here, guessing wrong means silently resizing nothing or resizing the
 * wrong container, so this fails fast instead — the same posture {@code InClusterApiServer}
 * already takes for {@code KUBERNETES_SERVICE_HOST}.
 *
 * @param healthPort TCP port the health endpoints listen on
 * @param podName this pod's own name (Downward API {@code fieldRef: metadata.name}), so the
 *     agent can read back its own pod's intent annotations
 * @param targetContainerName the sibling container {@code ShrinkSequence}/{@code GrowSequence}
 *     resize
 * @param gcTimeout how long a shrink's deep-GC-and-uncommit step waits before giving up
 * @param resizeTimeout how long a resize PATCH waits for kubelet confirmation
 * @param intentPollInterval how often the agent re-reads its own pod's intent annotations
 */
public record AgentConfig(
    int healthPort,
    String podName,
    String targetContainerName,
    Duration gcTimeout,
    Duration resizeTimeout,
    Duration intentPollInterval) {

  /** Default health-probe port. */
  public static final int DEFAULT_HEALTH_PORT = 8080;
  public static final Duration DEFAULT_GC_TIMEOUT = Duration.ofSeconds(30);
  public static final Duration DEFAULT_RESIZE_TIMEOUT = Duration.ofSeconds(30);
  public static final Duration DEFAULT_INTENT_POLL_INTERVAL = Duration.ofSeconds(5);

  public static final String ENV_HEALTH_PORT = "WARDEN_HEALTH_PORT";
  public static final String ENV_POD_NAME = "WARDEN_POD_NAME";
  public static final String ENV_TARGET_CONTAINER_NAME = "WARDEN_TARGET_CONTAINER_NAME";
  public static final String ENV_GC_TIMEOUT_SECONDS = "WARDEN_GC_TIMEOUT_SECONDS";
  public static final String ENV_RESIZE_TIMEOUT_SECONDS = "WARDEN_RESIZE_TIMEOUT_SECONDS";
  public static final String ENV_INTENT_POLL_INTERVAL_SECONDS = "WARDEN_INTENT_POLL_INTERVAL_SECONDS";

  /** Reads configuration from the process environment, falling back to defaults. */
  public static AgentConfig fromEnv() {
    return fromEnv(System::getenv);
  }

  /**
   * Reads configuration from an arbitrary environment lookup. Package-private seam so tests can
   * supply a fake environment without mutating the real process environment.
   */
  static AgentConfig fromEnv(Function<String, String> env) {
    return new AgentConfig(
        parsePort(env.apply(ENV_HEALTH_PORT)),
        requireEnv(env, ENV_POD_NAME),
        requireEnv(env, ENV_TARGET_CONTAINER_NAME),
        parseSeconds(env.apply(ENV_GC_TIMEOUT_SECONDS), DEFAULT_GC_TIMEOUT, ENV_GC_TIMEOUT_SECONDS),
        parseSeconds(env.apply(ENV_RESIZE_TIMEOUT_SECONDS), DEFAULT_RESIZE_TIMEOUT, ENV_RESIZE_TIMEOUT_SECONDS),
        parseSeconds(
            env.apply(ENV_INTENT_POLL_INTERVAL_SECONDS),
            DEFAULT_INTENT_POLL_INTERVAL,
            ENV_INTENT_POLL_INTERVAL_SECONDS));
  }

  private static String requireEnv(Function<String, String> env, String name) {
    String value = env.apply(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is not set; is this running as a Warden sidecar?");
    }
    return value.trim();
  }

  private static int parsePort(String raw) {
    if (raw == null || raw.isBlank()) {
      return DEFAULT_HEALTH_PORT;
    }
    final int port;
    try {
      port = Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(ENV_HEALTH_PORT + " must be an integer, got: " + raw, e);
    }
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException(ENV_HEALTH_PORT + " must be in 1..65535, got: " + port);
    }
    return port;
  }

  private static Duration parseSeconds(String raw, Duration fallback, String envName) {
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      return Duration.ofSeconds(Long.parseLong(raw.trim()));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(envName + " must be an integer number of seconds, got: " + raw, e);
    }
  }
}
