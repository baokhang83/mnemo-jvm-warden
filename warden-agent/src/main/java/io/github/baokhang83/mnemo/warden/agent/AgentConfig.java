package io.github.baokhang83.mnemo.warden.agent;

import java.util.function.Function;

/**
 * Immutable agent configuration, read from the environment with sane defaults.
 *
 * <p>Environment variables are the Kubernetes-native way to configure a sidecar, so no config
 * file or YAML library is pulled in.
 *
 * @param healthPort TCP port the health endpoints listen on
 */
public record AgentConfig(int healthPort) {

  /** Default health-probe port. */
  public static final int DEFAULT_HEALTH_PORT = 8080;

  /** Environment variable that overrides {@link #DEFAULT_HEALTH_PORT}. */
  public static final String ENV_HEALTH_PORT = "WARDEN_HEALTH_PORT";

  /** Reads configuration from the process environment, falling back to defaults. */
  public static AgentConfig fromEnv() {
    return fromEnv(System::getenv);
  }

  /**
   * Reads configuration from an arbitrary environment lookup. Package-private seam so tests can
   * supply a fake environment without mutating the real process environment.
   */
  static AgentConfig fromEnv(Function<String, String> env) {
    return new AgentConfig(parsePort(env.apply(ENV_HEALTH_PORT)));
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
}
