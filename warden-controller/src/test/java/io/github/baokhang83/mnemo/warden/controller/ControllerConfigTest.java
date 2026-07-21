package io.github.baokhang83.mnemo.warden.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ControllerConfigTest {

  @Test
  void prometheusUriIsEmptyWhenUnset() {
    ControllerConfig config = ControllerConfig.fromEnv(key -> null);

    assertTrue(config.prometheusUri().isEmpty());
  }

  @Test
  void readsPrometheusUriFromEnvironment() {
    ControllerConfig config =
        ControllerConfig.fromEnv(Map.of("WARDEN_PROMETHEUS_URL", "http://prometheus:9090")::get);

    assertEquals(URI.create("http://prometheus:9090"), config.prometheusUri().orElseThrow());
  }
}
