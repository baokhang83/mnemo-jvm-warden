package io.github.baokhang83.mnemo.warden.agent.attach;

import com.sun.tools.attach.VirtualMachine;
import java.io.Closeable;
import java.io.IOException;
import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;

/**
 * A live connection to the target JVM: its PID, the {@link VirtualMachine} handle used to attach,
 * and the JMX connection opened over that attachment.
 *
 * <p>{@link #mbeanConnection()} is what lets W-101's {@code GcDetector} and later heap drivers
 * read/act on the target's MXBeans exactly as they would locally &mdash;
 * {@code ManagementFactory.getPlatformMXBeans(connection, Type.class)} takes any {@link
 * MBeanServerConnection}, local or remote.
 */
public final class AttachedJvm implements Closeable {

  private final long pid;
  private final VirtualMachine virtualMachine;
  private final JMXConnector connector;
  private final MBeanServerConnection mbeanServerConnection;

  AttachedJvm(
      long pid, VirtualMachine virtualMachine, JMXConnector connector, MBeanServerConnection mbeanServerConnection) {
    this.pid = pid;
    this.virtualMachine = virtualMachine;
    this.connector = connector;
    this.mbeanServerConnection = mbeanServerConnection;
  }

  /** The target's PID. */
  public long pid() {
    return pid;
  }

  /** The JMX connection to the target, for reading/acting on its MXBeans. */
  public MBeanServerConnection mbeanConnection() {
    return mbeanServerConnection;
  }

  /** Whether the target process is still running. */
  public boolean isAlive() {
    return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
  }

  /**
   * Closes the JMX connector and detaches from the target. Safe to call once the target has
   * already exited &mdash; that is precisely the case this exists for (cleanup after a dead
   * target, before {@code AttachSupervisor} retries), so a severed connection is not a failure
   * here, just expected.
   */
  @Override
  public void close() {
    try {
      connector.close();
    } catch (IOException e) {
      // The target's RMI connection is already gone; nothing left to close.
    }
    try {
      virtualMachine.detach();
    } catch (IOException e) {
      // Same reasoning: the target may already be gone.
    }
  }
}
