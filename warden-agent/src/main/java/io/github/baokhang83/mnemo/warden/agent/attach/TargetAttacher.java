package io.github.baokhang83.mnemo.warden.agent.attach;

import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import java.io.IOException;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

/**
 * Opens a live connection to a target JVM found by {@link TargetLocator}.
 *
 * <p>Uses {@link VirtualMachine#startLocalManagementAgent()} (JDK 9+) rather than requiring the
 * target to launch with {@code -Dcom.sun.management.jmxremote}: it starts (or reuses) a JMX
 * connector on an already-running target and hands back its address, so attaching needs nothing
 * from the app's own launch configuration.
 */
public final class TargetAttacher {

  private TargetAttacher() {}

  /** Attaches to the described JVM and opens its JMX connection. */
  public static AttachedJvm attach(VirtualMachineDescriptor descriptor)
      throws AttachNotSupportedException, IOException {
    long pid = Long.parseLong(descriptor.id());
    VirtualMachine vm = VirtualMachine.attach(descriptor);
    try {
      String connectorAddress = vm.startLocalManagementAgent();
      JMXConnector connector = JMXConnectorFactory.connect(new JMXServiceURL(connectorAddress));
      return new AttachedJvm(pid, vm, connector, connector.getMBeanServerConnection());
    } catch (IOException e) {
      vm.detach();
      throw e;
    }
  }
}
