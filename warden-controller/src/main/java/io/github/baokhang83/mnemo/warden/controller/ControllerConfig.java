package io.github.baokhang83.mnemo.warden.controller;

import java.net.URI;
import java.util.Optional;
import java.util.function.Function;

/**
 * Controller-wide configuration, read from the environment. Mirrors {@code warden-agent}'s
 * {@code AgentConfig.fromEnv} pattern.
 *
 * @param prometheusUri where Prometheus lives (W-401), or empty if guardrail metric evaluation
 *     isn't configured &mdash; optional and safely absent, the same posture the roadmap already
 *     states for {@code CacheHook} (W-501): not every policy needs a guardrail.
 */
public record ControllerConfig(Optional<URI> prometheusUri) {

  public static final String ENV_PROMETHEUS_URL = "WARDEN_PROMETHEUS_URL";

  public static ControllerConfig fromEnv() {
    return fromEnv(System::getenv);
  }

  /**
   * Package-private seam so tests can supply a fake environment without mutating the real
   * process environment.
   */
  static ControllerConfig fromEnv(Function<String, String> env) {
    String raw = env.apply(ENV_PROMETHEUS_URL);
    if (raw == null || raw.isBlank()) {
      return new ControllerConfig(Optional.empty());
    }
    return new ControllerConfig(Optional.of(URI.create(raw.trim())));
  }
}
