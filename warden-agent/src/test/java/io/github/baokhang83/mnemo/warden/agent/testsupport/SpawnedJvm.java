package io.github.baokhang83.mnemo.warden.agent.testsupport;

import com.sun.tools.attach.VirtualMachine;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

/**
 * Spawns a throwaway JVM (JEP 330 single-file source launch) as a real attach target for tests
 * &mdash; the JMX bootstrap, and collector-specific VM options, are exactly the kind of platform
 * plumbing a mock would hide breakage in.
 *
 * <p>Every spawned target opens the loopback-only JMX port {@link TargetAttacher} connects
 * through (see its javadoc for why: the Attach API cannot cross a real UID mismatch, verified on
 * a real cluster). Tests run sequentially (no parallel execution configured), and each {@link
 * SpawnedJvm} is closed before the next spawns, so a single fixed port is safe here &mdash; no
 * two targets are ever listening on it at once.
 */
public final class SpawnedJvm implements Closeable {

  /** Matches {@link TargetAttacher#DEFAULT_JMX_PORT}. */
  private static final int JMX_PORT = 9999;

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

  /**
   * Spawns a JVM running the given single-file source, exactly as authored (with {@code %s} as
   * the class-name placeholder {@code spawn} fills in) &mdash; for fixtures {@link #sleeper} and
   * {@link #garbageChurner} don't cover, e.g. a target that registers its own MBean.
   */
  public static SpawnedJvm withSource(String sourceTemplate, String... jvmArgs) throws IOException {
    return spawn(sourceTemplate, jvmArgs);
  }

  private static SpawnedJvm spawn(String sourceTemplate, String... jvmArgs) throws IOException {
    Path source = Files.createTempFile("SpawnedJvmTarget", ".java");
    Files.writeString(
        source, sourceTemplate.formatted(source.getFileName().toString().replace(".java", "")));

    List<String> command = new ArrayList<>();
    command.add(System.getProperty("java.home") + "/bin/java");
    command.add("-Dcom.sun.management.jmxremote.port=" + JMX_PORT);
    command.add("-Dcom.sun.management.jmxremote.rmi.port=" + JMX_PORT);
    command.add("-Dcom.sun.management.jmxremote.host=127.0.0.1");
    command.add("-Dcom.sun.management.jmxremote.authenticate=false");
    command.add("-Dcom.sun.management.jmxremote.ssl=false");
    command.add("-Djava.rmi.server.hostname=127.0.0.1");
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

  /**
   * Waits for the target's process to appear in {@link VirtualMachine#list()}, <em>and</em> for
   * its JMX RMI registry to actually complete a connect-and-close round trip.
   *
   * <p>The second wait matters, and a raw TCP connect is not enough to prove it: a TCP {@code
   * SYN}/{@code ACK} succeeds the instant the OS accepts the connection, well before the RMI
   * registry has finished exporting its object table &mdash; observed directly as a flaky
   * "connection refused" (registry not up yet) and, once tests reused the same fixed port back to
   * back, a flakier {@code NoSuchObjectException: no such object in table} (a stale registry
   * reference from the just-replaced previous target's JVM). A full {@code
   * JMXConnectorFactory.connect()} + close is the only check that actually proves the *new*
   * registry is live. {@code AttachSupervisor}'s own retry loop absorbs this timing in
   * production; tests that call {@code TargetAttacher.attach(target.pid())} directly, once, need
   * this method to have already absorbed it instead.
   */
  public void awaitReady() throws InterruptedException {
    String pidString = Long.toString(pid());
    boolean visible = false;
    for (int i = 0; i < 100; i++) {
      if (VirtualMachine.list().stream().anyMatch(d -> d.id().equals(pidString))) {
        visible = true;
        break;
      }
      Thread.sleep(100);
    }
    if (!visible) {
      throw new AssertionError("spawned JVM (pid " + pid() + ") never appeared in VirtualMachine.list()");
    }

    JMXServiceURL url;
    try {
      url = new JMXServiceURL("service:jmx:rmi:///jndi/rmi://127.0.0.1:" + JMX_PORT + "/jmxrmi");
    } catch (java.net.MalformedURLException e) {
      throw new AssertionError("hardcoded JMX service URL is invalid", e);
    }
    for (int i = 0; i < 100; i++) {
      try (JMXConnector connector = JMXConnectorFactory.connect(url)) {
        return;
      } catch (IOException e) {
        Thread.sleep(100);
      }
    }
    throw new AssertionError("spawned JVM (pid " + pid() + ") never opened its JMX port (" + JMX_PORT + ")");
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
