package io.github.baokhang83.mnemo.warden.agent.heap;

import io.github.baokhang83.mnemo.warden.agent.testsupport.SpawnedJvm;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** TEMPORARY — investigating why RssReaderTest fails on the real CI runner. Delete after. */
@EnabledOnOs(OS.LINUX)
class CgroupDiagnosticTest {

  @Test
  void dumpCgroupEnvironment() throws Exception {
    System.out.println("=== /proc/self/cgroup ===");
    dump(Path.of("/proc/self/cgroup"));

    System.out.println("=== /sys/fs/cgroup (direct) ===");
    list(Path.of("/sys/fs/cgroup"));

    System.out.println("=== /sys/fs/cgroup/memory.current (direct) exists? ===");
    System.out.println(Files.exists(Path.of("/sys/fs/cgroup/memory.current")));

    try (SpawnedJvm target = SpawnedJvm.sleeper()) {
      long pid = target.awaitDescriptor().id().equals(Long.toString(target.pid())) ? target.pid() : -1;
      System.out.println("=== spawned child pid=" + pid + " ===");

      System.out.println("=== /proc/" + pid + "/cgroup ===");
      dump(Path.of("/proc/" + pid + "/cgroup"));

      Path viaRoot = Path.of("/proc/" + pid + "/root/sys/fs/cgroup");
      System.out.println("=== " + viaRoot + " exists? " + Files.exists(viaRoot) + " ===");
      if (Files.exists(viaRoot)) {
        list(viaRoot);
      }
    }
  }

  private static void dump(Path file) {
    try {
      System.out.println(Files.readString(file));
    } catch (IOException e) {
      System.out.println("  <could not read " + file + ": " + e + ">");
    }
  }

  private static void list(Path dir) {
    try (var stream = Files.list(dir)) {
      stream.sorted(Comparator.naturalOrder()).forEach(p -> System.out.println("  " + p));
    } catch (IOException e) {
      System.out.println("  <could not list " + dir + ": " + e + ">");
    }
  }
}
