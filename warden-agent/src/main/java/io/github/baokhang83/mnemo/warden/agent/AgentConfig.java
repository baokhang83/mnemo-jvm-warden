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
 * @param podUid this pod's own UID (Downward API {@code fieldRef: metadata.uid}), optional
 *     &mdash; only needed when the B8 local Boost API is enabled, so existing sidecars that
 *     don't set it are unaffected
 * @param boostPort TCP port the local Boost API listens on, loopback-only
 * @param boostPolicyRef the {@code WardenPolicy} name to stamp onto self-requested boosts;
 *     {@code null} means the local Boost API is disabled &mdash; this one variable is the
 *     feature flag, since a pod can't discover its own policy's name any other way (it only
 *     carries the policy's UID, not its name, via the existing resize-intent label)
 * @param boostAcquireWait how long an {@code acquire} call blocks waiting for a boost to reach
 *     a terminal/granted phase before returning so the caller can poll instead
 * @param boostTokenPath where the shared app&harr;sidecar bearer token lives
 */
public record AgentConfig(
    int healthPort,
    String podName,
    String targetContainerName,
    Duration gcTimeout,
    Duration resizeTimeout,
    Duration intentPollInterval,
    String podUid,
    int boostPort,
    String boostPolicyRef,
    Duration boostAcquireWait,
    String boostTokenPath) {

  /** Default health-probe port. */
  public static final int DEFAULT_HEALTH_PORT = 8080;
  public static final Duration DEFAULT_GC_TIMEOUT = Duration.ofSeconds(30);
  public static final Duration DEFAULT_RESIZE_TIMEOUT = Duration.ofSeconds(30);
  public static final Duration DEFAULT_INTENT_POLL_INTERVAL = Duration.ofSeconds(5);
  public static final int DEFAULT_BOOST_PORT = 8090;
  public static final Duration DEFAULT_BOOST_ACQUIRE_WAIT = Duration.ofSeconds(25);
  public static final String DEFAULT_BOOST_TOKEN_PATH = "/var/run/warden/boost/token";

  public static final String ENV_HEALTH_PORT = "WARDEN_HEALTH_PORT";
  public static final String ENV_POD_NAME = "WARDEN_POD_NAME";
  public static final String ENV_TARGET_CONTAINER_NAME = "WARDEN_TARGET_CONTAINER_NAME";
  public static final String ENV_GC_TIMEOUT_SECONDS = "WARDEN_GC_TIMEOUT_SECONDS";
  public static final String ENV_RESIZE_TIMEOUT_SECONDS = "WARDEN_RESIZE_TIMEOUT_SECONDS";
  public static final String ENV_INTENT_POLL_INTERVAL_SECONDS = "WARDEN_INTENT_POLL_INTERVAL_SECONDS";
  public static final String ENV_POD_UID = "WARDEN_POD_UID";
  public static final String ENV_BOOST_PORT = "WARDEN_BOOST_PORT";
  public static final String ENV_BOOST_POLICY_REF = "WARDEN_BOOST_POLICY_REF";
  public static final String ENV_BOOST_ACQUIRE_WAIT_SECONDS = "WARDEN_BOOST_ACQUIRE_WAIT_SECONDS";
  public static final String ENV_BOOST_TOKEN_PATH = "WARDEN_BOOST_TOKEN_PATH";

  /** Reads configuration from the process environment, falling back to defaults. */
  public static AgentConfig fromEnv() {
    return fromEnv(System::getenv);
  }

  /**
   * Reads configuration from an arbitrary environment lookup. Package-private seam so tests can
   * supply a fake environment without mutating the real process environment.
   */
  static AgentConfig fromEnv(Function<String, String> env) {
    String tokenPath = optionalEnv(env, ENV_BOOST_TOKEN_PATH);
    return new AgentConfig(
        parsePort(env.apply(ENV_HEALTH_PORT), DEFAULT_HEALTH_PORT, ENV_HEALTH_PORT),
        requireEnv(env, ENV_POD_NAME),
        requireEnv(env, ENV_TARGET_CONTAINER_NAME),
        parseSeconds(env.apply(ENV_GC_TIMEOUT_SECONDS), DEFAULT_GC_TIMEOUT, ENV_GC_TIMEOUT_SECONDS),
        parseSeconds(env.apply(ENV_RESIZE_TIMEOUT_SECONDS), DEFAULT_RESIZE_TIMEOUT, ENV_RESIZE_TIMEOUT_SECONDS),
        parseSeconds(
            env.apply(ENV_INTENT_POLL_INTERVAL_SECONDS),
            DEFAULT_INTENT_POLL_INTERVAL,
            ENV_INTENT_POLL_INTERVAL_SECONDS),
        optionalEnv(env, ENV_POD_UID),
        parsePort(env.apply(ENV_BOOST_PORT), DEFAULT_BOOST_PORT, ENV_BOOST_PORT),
        optionalEnv(env, ENV_BOOST_POLICY_REF),
        parseSeconds(env.apply(ENV_BOOST_ACQUIRE_WAIT_SECONDS), DEFAULT_BOOST_ACQUIRE_WAIT, ENV_BOOST_ACQUIRE_WAIT_SECONDS),
        tokenPath != null ? tokenPath : DEFAULT_BOOST_TOKEN_PATH);
  }

  /** True when the B8 local Boost API should be started ({@link #boostPolicyRef} is set). */
  public boolean boostApiEnabled() {
    return boostPolicyRef != null;
  }

  private static String requireEnv(Function<String, String> env, String name) {
    String value = env.apply(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is not set; is this running as a Warden sidecar?");
    }
    return value.trim();
  }

  private static String optionalEnv(Function<String, String> env, String name) {
    String value = env.apply(name);
    return (value == null || value.isBlank()) ? null : value.trim();
  }

  private static int parsePort(String raw, int fallback, String envName) {
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    final int port;
    try {
      port = Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(envName + " must be an integer, got: " + raw, e);
    }
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException(envName + " must be in 1..65535, got: " + port);
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
