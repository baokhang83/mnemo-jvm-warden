package io.github.baokhang83.mnemo.warden.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentConfigTest {

  private static final Map<String, String> REQUIRED_ENV =
      Map.of("WARDEN_POD_NAME", "my-pod", "WARDEN_TARGET_CONTAINER_NAME", "app");

  @Test
  void usesDefaultsWhenOnlyRequiredFieldsAreSet() {
    AgentConfig config = AgentConfig.fromEnv(REQUIRED_ENV::get);
    assertEquals(AgentConfig.DEFAULT_HEALTH_PORT, config.healthPort());
    assertEquals("my-pod", config.podName());
    assertEquals("app", config.targetContainerName());
    assertEquals(AgentConfig.DEFAULT_GC_TIMEOUT, config.gcTimeout());
    assertEquals(AgentConfig.DEFAULT_RESIZE_TIMEOUT, config.resizeTimeout());
    assertEquals(AgentConfig.DEFAULT_INTENT_POLL_INTERVAL, config.intentPollInterval());
  }

  @Test
  void readsPortFromEnvironment() {
    AgentConfig config =
        AgentConfig.fromEnv(merge(REQUIRED_ENV, Map.of("WARDEN_HEALTH_PORT", "9090"))::get);
    assertEquals(9090, config.healthPort());
  }

  @Test
  void rejectsNonNumericPort() {
    assertThrows(
        IllegalArgumentException.class,
        () -> AgentConfig.fromEnv(merge(REQUIRED_ENV, Map.of("WARDEN_HEALTH_PORT", "abc"))::get));
  }

  @Test
  void rejectsOutOfRangePort() {
    assertThrows(
        IllegalArgumentException.class,
        () -> AgentConfig.fromEnv(merge(REQUIRED_ENV, Map.of("WARDEN_HEALTH_PORT", "70000"))::get));
  }

  @Test
  void rejectsMissingPodName() {
    assertThrows(
        IllegalArgumentException.class,
        () -> AgentConfig.fromEnv(Map.of("WARDEN_TARGET_CONTAINER_NAME", "app")::get));
  }

  @Test
  void rejectsMissingTargetContainerName() {
    assertThrows(
        IllegalArgumentException.class,
        () -> AgentConfig.fromEnv(Map.of("WARDEN_POD_NAME", "my-pod")::get));
  }

  @Test
  void readsTimeoutsAndPollIntervalFromEnvironment() {
    AgentConfig config =
        AgentConfig.fromEnv(
            merge(
                    REQUIRED_ENV,
                    Map.of(
                        "WARDEN_GC_TIMEOUT_SECONDS", "45",
                        "WARDEN_RESIZE_TIMEOUT_SECONDS", "20",
                        "WARDEN_INTENT_POLL_INTERVAL_SECONDS", "10"))
                ::get);
    assertEquals(Duration.ofSeconds(45), config.gcTimeout());
    assertEquals(Duration.ofSeconds(20), config.resizeTimeout());
    assertEquals(Duration.ofSeconds(10), config.intentPollInterval());
  }

  @Test
  void rejectsNonNumericGcTimeout() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AgentConfig.fromEnv(
                merge(REQUIRED_ENV, Map.of("WARDEN_GC_TIMEOUT_SECONDS", "abc"))::get));
  }

  private static Map<String, String> merge(Map<String, String> base, Map<String, String> overrides) {
    Map<String, String> merged = new java.util.HashMap<>(base);
    merged.putAll(overrides);
    return merged;
  }
}
