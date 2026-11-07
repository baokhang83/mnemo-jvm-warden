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
    assertEquals(null, config.podUid());
    assertEquals(AgentConfig.DEFAULT_BOOST_PORT, config.boostPort());
    assertEquals(null, config.boostPolicyRef());
    assertEquals(AgentConfig.DEFAULT_BOOST_ACQUIRE_WAIT, config.boostAcquireWait());
    assertEquals(AgentConfig.DEFAULT_BOOST_TOKEN_PATH, config.boostTokenPath());
    assertEquals(false, config.boostApiEnabled());
  }

  @Test
  void boostApiIsDisabledUnlessPolicyRefIsSet() {
    AgentConfig withoutPolicy = AgentConfig.fromEnv(REQUIRED_ENV::get);
    assertEquals(false, withoutPolicy.boostApiEnabled());

    AgentConfig withPolicy =
        AgentConfig.fromEnv(merge(REQUIRED_ENV, Map.of("WARDEN_BOOST_POLICY_REF", "my-policy"))::get);
    assertEquals(true, withPolicy.boostApiEnabled());
    assertEquals("my-policy", withPolicy.boostPolicyRef());
  }

  @Test
  void readsBoostFieldsFromEnvironment() {
    AgentConfig config =
        AgentConfig.fromEnv(
            merge(
                    REQUIRED_ENV,
                    Map.of(
                        "WARDEN_POD_UID", "11111111-1111-1111-1111-111111111111",
                        "WARDEN_BOOST_PORT", "9999",
                        "WARDEN_BOOST_POLICY_REF", "my-policy",
                        "WARDEN_BOOST_ACQUIRE_WAIT_SECONDS", "40",
                        "WARDEN_BOOST_TOKEN_PATH", "/tmp/boost-token"))
                ::get);
    assertEquals("11111111-1111-1111-1111-111111111111", config.podUid());
    assertEquals(9999, config.boostPort());
    assertEquals("my-policy", config.boostPolicyRef());
    assertEquals(Duration.ofSeconds(40), config.boostAcquireWait());
    assertEquals("/tmp/boost-token", config.boostTokenPath());
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
