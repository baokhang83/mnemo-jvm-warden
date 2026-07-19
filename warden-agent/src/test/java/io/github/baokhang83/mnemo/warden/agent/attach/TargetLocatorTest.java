package io.github.baokhang83.mnemo.warden.agent.attach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.tools.attach.VirtualMachineDescriptor;
import com.sun.tools.attach.spi.AttachProvider;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TargetLocatorTest {

  private static final AttachProvider PROVIDER = AttachProvider.providers().get(0);
  private static final long SELF_PID = 1L;

  private static VirtualMachineDescriptor descriptor(long pid) {
    return new VirtualMachineDescriptor(PROVIDER, Long.toString(pid), "test");
  }

  @Test
  void picksTheOnlyOtherJvm() {
    Optional<VirtualMachineDescriptor> target =
        TargetLocator.findTarget(
            List.of(descriptor(SELF_PID), descriptor(42)), SELF_PID, key -> null);

    assertTrue(target.isPresent());
    assertEquals("42", target.get().id());
  }

  @Test
  void staysUnattachedWhenNoOtherJvmIsVisible() {
    Optional<VirtualMachineDescriptor> target =
        TargetLocator.findTarget(List.of(descriptor(SELF_PID)), SELF_PID, key -> null);

    assertFalse(target.isPresent());
  }

  @Test
  void staysUnattachedWhenMultipleOtherJvmsAreAmbiguous() {
    Optional<VirtualMachineDescriptor> target =
        TargetLocator.findTarget(
            List.of(descriptor(SELF_PID), descriptor(42), descriptor(43)), SELF_PID, key -> null);

    assertFalse(target.isPresent());
  }

  @Test
  void envOverrideWinsEvenWithMultipleCandidates() {
    Map<String, String> env = Map.of(TargetLocator.ENV_TARGET_PID, "43");

    Optional<VirtualMachineDescriptor> target =
        TargetLocator.findTarget(
            List.of(descriptor(SELF_PID), descriptor(42), descriptor(43)), SELF_PID, env::get);

    assertTrue(target.isPresent());
    assertEquals("43", target.get().id());
  }

  @Test
  void envOverridePointingAtAnUnknownPidStaysUnattached() {
    Map<String, String> env = Map.of(TargetLocator.ENV_TARGET_PID, "999");

    Optional<VirtualMachineDescriptor> target =
        TargetLocator.findTarget(List.of(descriptor(SELF_PID), descriptor(42)), SELF_PID, env::get);

    assertFalse(target.isPresent());
  }
}
