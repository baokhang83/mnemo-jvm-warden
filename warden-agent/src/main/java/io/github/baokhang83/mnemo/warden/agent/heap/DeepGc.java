package io.github.baokhang83.mnemo.warden.agent.heap;

import io.github.baokhang83.mnemo.warden.agent.attach.AttachedJvm;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.management.JMException;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

/**
 * Forces a full collection on an attached target and waits for the collector's asynchronous
 * uncommit to actually return pages to the OS.
 *
 * <p>Not collector-specific: {@link #forTarget(AttachedJvm)} checks {@link
 * GcCapabilities#supported()} (uncommit support), true for ZGC, Shenandoah, and G1 (via periodic
 * GC) &mdash; only {@link Collector#OTHER} gets rejected.
 *
 * <p>{@code GC.run} is invoked over JMX as the zero-arg {@code gcRun} operation on the
 * {@code com.sun.management:type=DiagnosticCommand} MBean &mdash; the remote equivalent of
 * {@code jcmd <pid> GC.run}; {@code System.gc()} cannot be called on a remote process at all.
 * Uncommit itself has no completion signal to wait on, so {@link #runAndAwaitUncommit} polls
 * {@link MemoryMXBean#getHeapMemoryUsage()}'s committed size until it stabilizes or the caller's
 * timeout elapses. Note for callers: ZGC's default {@code ZUncommitDelay} is 300 seconds, so a
 * short timeout here can validly come back {@code completed: false} in production.
 */
public final class DeepGc {

  private static final String OPERATION = "deep GC + uncommit";
  private static final ObjectName DIAGNOSTIC_COMMAND = objectName("com.sun.management:type=DiagnosticCommand");
  private static final Duration POLL_INTERVAL = Duration.ofMillis(250);
  private static final int STABLE_SAMPLES = 3;

  private final MBeanServerConnection connection;
  private final MemoryMXBean memory;

  private DeepGc(MBeanServerConnection connection, MemoryMXBean memory) {
    this.connection = connection;
    this.memory = memory;
  }

  /**
   * @throws UnsupportedCollectorException if the target's collector doesn't support uncommit
   * @throws IOException if the JMX connection to the target fails
   */
  public static DeepGc forTarget(AttachedJvm target) throws IOException {
    List<GarbageCollectorMXBean> gcBeans =
        ManagementFactory.getPlatformMXBeans(target.mbeanConnection(), GarbageCollectorMXBean.class);
    GcCapabilities capabilities = GcDetector.detect(gcBeans);
    if (!capabilities.supported()) {
      throw new UnsupportedCollectorException(OPERATION, capabilities.collector());
    }

    MemoryMXBean memory = ManagementFactory.getPlatformMXBean(target.mbeanConnection(), MemoryMXBean.class);
    return new DeepGc(target.mbeanConnection(), memory);
  }

  /**
   * Forces {@code GC.run}, then polls until the committed heap has visibly dropped and then held
   * steady for {@link #STABLE_SAMPLES} consecutive samples, or {@code timeout} elapses.
   *
   * <p>"Unchanged" is only trusted as "settled" once at least one drop has actually been
   * observed &mdash; an unchanged reading from the very first sample is indistinguishable from
   * "the {@code ZUncommitDelay} window just hasn't elapsed yet" from "there was nothing to free,"
   * so it does not by itself count as completion. A target with genuinely nothing to uncommit
   * therefore consumes the full timeout before returning {@code completed: false} &mdash; slower,
   * but honest: there is no signal that lets this method tell the two cases apart any earlier.
   */
  public UncommitResult runAndAwaitUncommit(Duration timeout) throws IOException, InterruptedException {
    long before = committed();
    triggerGcRun();

    Instant deadline = Instant.now().plus(timeout);
    long lastSeen = before;
    boolean everDropped = false;
    int stableSamples = 0;

    while (Instant.now().isBefore(deadline)) {
      Thread.sleep(POLL_INTERVAL.toMillis());
      long sample = committed();
      if (sample < lastSeen) {
        everDropped = true;
        stableSamples = 0;
      } else if (sample == lastSeen) {
        stableSamples++;
      } else {
        stableSamples = 0;
      }
      lastSeen = sample;

      if (everDropped && stableSamples >= STABLE_SAMPLES) {
        break;
      }
    }

    boolean completed = everDropped && stableSamples >= STABLE_SAMPLES;
    return new UncommitResult(before - lastSeen, completed);
  }

  private long committed() {
    return memory.getHeapMemoryUsage().getCommitted();
  }

  private void triggerGcRun() throws IOException {
    try {
      connection.invoke(DIAGNOSTIC_COMMAND, "gcRun", new Object[0], new String[0]);
    } catch (JMException e) {
      throw new IOException("failed to invoke GC.run on target", e);
    }
  }

  private static ObjectName objectName(String name) {
    try {
      return new ObjectName(name);
    } catch (MalformedObjectNameException e) {
      throw new AssertionError("hardcoded ObjectName is invalid: " + name, e);
    }
  }
}
