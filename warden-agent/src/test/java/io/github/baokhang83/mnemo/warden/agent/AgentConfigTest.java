package io.github.baokhang83.mnemo.warden.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentConfigTest {

  @Test
  void usesDefaultPortWhenUnset() {
    AgentConfig config = AgentConfig.fromEnv(key -> null);
    assertEquals(AgentConfig.DEFAULT_HEALTH_PORT, config.healthPort());
  }

  @Test
  void readsPortFromEnvironment() {
    AgentConfig config = AgentConfig.fromEnv(Map.of("WARDEN_HEALTH_PORT", "9090")::get);
    assertEquals(9090, config.healthPort());
  }

  @Test
  void rejectsNonNumericPort() {
    assertThrows(
        IllegalArgumentException.class,
        () -> AgentConfig.fromEnv(Map.of("WARDEN_HEALTH_PORT", "abc")::get));
  }

  @Test
  void rejectsOutOfRangePort() {
    assertThrows(
        IllegalArgumentException.class,
        () -> AgentConfig.fromEnv(Map.of("WARDEN_HEALTH_PORT", "70000")::get));
  }
}
