package io.github.baokhang83.mnemo.warden.agent.testsupport;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Spawns a throwaway JVM (JEP 330 single-file source launch) as a real attach target for tests
 * &mdash; the Attach API / JMX bootstrap, and now collector-specific VM options, are exactly the
 * kind of platform plumbing a mock would hide breakage in.
 */
public final class SpawnedJvm implements Closeable {

  private final Process process;

  private SpawnedJvm(Process process) {
    this.process = process;
  }

  /** Spawns a JVM that just parks, with the given extra VM options (e.g. {@code -XX:+UseZGC}). */
  public static SpawnedJvm sleeper(String... jvmArgs) throws IOException {
    Path source = Files.createTempFile("SpawnedJvmTarget", ".java");
    Files.writeString(
        source,
        """
        public class %s {
          public static void main(String[] args) throws InterruptedException {
            Thread.sleep(120_000);
          }
        }
        """
            .formatted(source.getFileName().toString().replace(".java", "")));

    List<String> command = new ArrayList<>();
    command.add(System.getProperty("java.home") + "/bin/java");
    command.addAll(List.of(jvmArgs));
    command.add(source.toString());
    return new SpawnedJvm(new ProcessBuilder(command).inheritIO().start());
  }

  public long pid() {
    return process.pid();
  }

  public Process process() {
    return process;
  }

  /** Waits for the target's attach socket to appear, then returns its descriptor. */
  public VirtualMachineDescriptor awaitDescriptor() throws InterruptedException {
    String pidString = Long.toString(pid());
    for (int i = 0; i < 100; i++) {
      Optional<VirtualMachineDescriptor> found =
          VirtualMachine.list().stream().filter(d -> d.id().equals(pidString)).findFirst();
      if (found.isPresent()) {
        return found.get();
      }
      Thread.sleep(100);
    }
    throw new AssertionError("spawned JVM (pid " + pid() + ") never appeared in VirtualMachine.list()");
  }

  /** Forcibly kills the process. Safe to call even if it already exited. */
  @Override
  public void close() {
    process.destroyForcibly();
  }
}
