package io.github.baokhang83.mnemo.warden.agent.attach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TargetLocatorTest {

  private static final long SELF_PID = 1L;

  private static void javaProcess(Path procRoot, long pid) throws IOException {
    Path dir = procRoot.resolve(Long.toString(pid));
    Files.createDirectory(dir);
    Files.writeString(dir.resolve("comm"), "java\n");
  }

  private static void nonJavaProcess(Path procRoot, long pid, String comm) throws IOException {
    Path dir = procRoot.resolve(Long.toString(pid));
    Files.createDirectory(dir);
    Files.writeString(dir.resolve("comm"), comm + "\n");
  }

  @Test
  void picksTheOnlyOtherJvm(@TempDir Path procRoot) throws IOException {
    javaProcess(procRoot, SELF_PID);
    javaProcess(procRoot, 42);

    Optional<Long> target = TargetLocator.findTarget(procRoot, SELF_PID, key -> null);

    assertTrue(target.isPresent());
    assertEquals(42L, target.get());
  }

  @Test
  void ignoresNonJavaProcessEntries(@TempDir Path procRoot) throws IOException {
    javaProcess(procRoot, SELF_PID);
    javaProcess(procRoot, 42);
    nonJavaProcess(procRoot, 43, "pause");
    nonJavaProcess(procRoot, 44, "sh");

    Optional<Long> target = TargetLocator.findTarget(procRoot, SELF_PID, key -> null);

    assertTrue(target.isPresent());
    assertEquals(42L, target.get());
  }

  @Test
  void staysUnattachedWhenNoOtherJvmIsVisible(@TempDir Path procRoot) throws IOException {
    javaProcess(procRoot, SELF_PID);

    Optional<Long> target = TargetLocator.findTarget(procRoot, SELF_PID, key -> null);

    assertFalse(target.isPresent());
  }

  @Test
  void staysUnattachedWhenMultipleOtherJvmsAreAmbiguous(@TempDir Path procRoot) throws IOException {
    javaProcess(procRoot, SELF_PID);
    javaProcess(procRoot, 42);
    javaProcess(procRoot, 43);

    Optional<Long> target = TargetLocator.findTarget(procRoot, SELF_PID, key -> null);

    assertFalse(target.isPresent());
  }

  @Test
  void envOverrideWinsEvenWithMultipleCandidates(@TempDir Path procRoot) throws IOException {
    javaProcess(procRoot, SELF_PID);
    javaProcess(procRoot, 42);
    javaProcess(procRoot, 43);
    Map<String, String> env = Map.of(TargetLocator.ENV_TARGET_PID, "43");

    Optional<Long> target = TargetLocator.findTarget(procRoot, SELF_PID, env::get);

    assertTrue(target.isPresent());
    assertEquals(43L, target.get());
  }

  @Test
  void envOverridePointingAtAnUnknownPidStaysUnattached(@TempDir Path procRoot) throws IOException {
    javaProcess(procRoot, SELF_PID);
    javaProcess(procRoot, 42);
    Map<String, String> env = Map.of(TargetLocator.ENV_TARGET_PID, "999");

    Optional<Long> target = TargetLocator.findTarget(procRoot, SELF_PID, env::get);

    assertFalse(target.isPresent());
  }
}
