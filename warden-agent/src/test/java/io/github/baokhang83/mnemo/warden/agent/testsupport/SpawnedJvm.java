package io.github.baokhang83.mnemo.warden.agent.testsupport;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Spawns a throwaway JVM (JEP 330 single-file source launch) as a real attach target for tests
 * &mdash; the Attach API / JMX bootstrap, and now collector-specific VM options, are exactly the
 * kind of platform plumbing a mock would hide breakage in.
 */
public final class SpawnedJvm implements Closeable {

  private final Process process;
  private final BlockingQueue<String> stdoutLines = new LinkedBlockingQueue<>();
  private final Thread stdoutReader;

  private SpawnedJvm(Process process) {
    this.process = process;
    this.stdoutReader = new Thread(this::pumpStdout, "spawned-jvm-stdout-reader");
    this.stdoutReader.setDaemon(true);
    this.stdoutReader.start();
  }

  /** Spawns a JVM that just parks, with the given extra VM options (e.g. {@code -XX:+UseZGC}). */
  public static SpawnedJvm sleeper(String... jvmArgs) throws IOException {
    return spawn(
        """
        public class %s {
          public static void main(String[] args) throws InterruptedException {
            Thread.sleep(120_000);
          }
        }
        """,
        jvmArgs);
  }

  /**
   * Spawns a JVM that allocates a large block, drops it (unreachable, but not yet collected),
   * prints {@code "allocated"}, then parks &mdash; a real GC/uncommit target, not an idle one.
   * Callers should wait on that marker via {@link #awaitStdoutLine} before taking a "before"
   * committed-heap reading, rather than guessing how long startup + allocation takes.
   */
  public static SpawnedJvm garbageChurner(String... jvmArgs) throws IOException {
    return spawn(
        """
        public class %s {
          public static void main(String[] args) throws InterruptedException {
            allocateAndDiscard();
            System.out.println("allocated");
            Thread.sleep(120_000);
          }

          private static void allocateAndDiscard() {
            byte[][] garbage = new byte[400][];
            for (int i = 0; i < garbage.length; i++) {
              garbage[i] = new byte[1024 * 1024];
            }
            // garbage[] goes out of scope here — unreachable as soon as this method returns.
          }
        }
        """,
        jvmArgs);
  }

  private static SpawnedJvm spawn(String sourceTemplate, String... jvmArgs) throws IOException {
    Path source = Files.createTempFile("SpawnedJvmTarget", ".java");
    Files.writeString(
        source, sourceTemplate.formatted(source.getFileName().toString().replace(".java", "")));

    List<String> command = new ArrayList<>();
    command.add(System.getProperty("java.home") + "/bin/java");
    command.addAll(List.of(jvmArgs));
    command.add(source.toString());
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    return new SpawnedJvm(builder.start());
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

  /**
   * Blocks until the child prints a line equal to {@code expected}, so callers can synchronize on
   * a real event in the child (e.g. "allocation finished") instead of guessing a sleep duration.
   */
  public void awaitStdoutLine(String expected, Duration timeout) throws InterruptedException {
    Instant deadline = Instant.now().plus(timeout);
    while (true) {
      Duration remaining = Duration.between(Instant.now(), deadline);
      if (remaining.isNegative()) {
        throw new AssertionError("spawned JVM never printed: " + expected);
      }
      String line = stdoutLines.poll(remaining.toMillis(), TimeUnit.MILLISECONDS);
      if (line == null) {
        throw new AssertionError("spawned JVM never printed: " + expected);
      }
      if (line.equals(expected)) {
        return;
      }
    }
  }

  private void pumpStdout() {
    try (BufferedReader reader = process.inputReader()) {
      String line;
      while ((line = reader.readLine()) != null) {
        stdoutLines.add(line);
      }
    } catch (IOException e) {
      // The process ended; nothing left to pump.
    }
  }

  /** Forcibly kills the process. Safe to call even if it already exited. */
  @Override
  public void close() {
    process.destroyForcibly();
  }
}
