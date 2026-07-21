package io.github.baokhang83.mnemo.warden.agent.attach;

import java.io.IOException;
import java.util.function.Function;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

/**
 * Opens a live connection to a target JVM found by {@link TargetLocator}, via a JMX port the
 * target itself opens at launch &mdash; not the JDK Attach API.
 *
 * <p>The Attach API (the original W-102 mechanism) turned out to fundamentally require the agent
 * to run as real root (UID 0) to reach a target running under a different UID, since it connects
 * by crossing into the target's own mount namespace via {@code /proc/<pid>/root} to find its
 * {@code .java_pid<pid>} socket. Exhaustively verified on a real cluster that no capability grant
 * fixes this &mdash; not {@code CAP_SYS_PTRACE}, not {@code CAP_DAC_OVERRIDE}/{@code
 * CAP_DAC_READ_SEARCH}, not {@code CAP_SYS_ADMIN}, not unconfined seccomp/AppArmor, and not even
 * exactly matching non-root UID and GID. Only real root works, which most deployments won't (and
 * shouldn't) grant the agent.
 *
 * <p>A JMX port is a network socket over the pod's <em>shared</em> network namespace, not a
 * filesystem crossing, so it needs no special UID or capability at all &mdash; verified against a
 * real cluster with genuinely mismatched UIDs. The target must launch with:
 *
 * <pre>{@code
 * -Dcom.sun.management.jmxremote.port=<PORT>
 * -Dcom.sun.management.jmxremote.rmi.port=<PORT>
 * -Dcom.sun.management.jmxremote.host=127.0.0.1
 * -Dcom.sun.management.jmxremote.authenticate=false
 * -Dcom.sun.management.jmxremote.ssl=false
 * -Djava.rmi.server.hostname=127.0.0.1
 * }</pre>
 *
 * <p><b>{@code jmxremote.host=127.0.0.1} is not optional.</b> Without it, the JMX listener binds
 * to every interface, not just loopback &mdash; verified on a real cluster that the port is then
 * reachable from a completely separate pod's IP, not just from within the target's own pod.
 * Combined with {@code authenticate=false}, that is an unauthenticated, cluster-reachable MBean
 * server: effectively remote code execution. With {@code jmxremote.host=127.0.0.1}, verified on
 * the same live deployment that a separate pod's connection attempt is refused while the
 * sidecar's (same pod, same loopback) still succeeds &mdash; {@code authenticate=false} is only
 * safe because the listener is provably unreachable outside the pod's own trust boundary.
 */
public final class TargetAttacher {

  /** Overrides the target's JMX port; must match the target's {@code jmxremote.port}. */
  public static final String ENV_TARGET_JMX_PORT = "WARDEN_TARGET_JMX_PORT";

  /** Default JMX port, used when {@link #ENV_TARGET_JMX_PORT} is unset. */
  public static final int DEFAULT_JMX_PORT = 9999;

  private TargetAttacher() {}

  /** Attaches to the target PID using the configured (or default) JMX port. */
  public static AttachedJvm attach(long pid) throws IOException {
    return attach(pid, System::getenv);
  }

  /** Package-private seam so tests can supply a fake environment. */
  static AttachedJvm attach(long pid, Function<String, String> env) throws IOException {
    int port = jmxPort(env);
    JMXServiceURL url = new JMXServiceURL("service:jmx:rmi:///jndi/rmi://127.0.0.1:" + port + "/jmxrmi");
    JMXConnector connector = JMXConnectorFactory.connect(url);
    return new AttachedJvm(pid, connector, connector.getMBeanServerConnection());
  }

  private static int jmxPort(Function<String, String> env) {
    String raw = env.apply(ENV_TARGET_JMX_PORT);
    if (raw == null || raw.isBlank()) {
      return DEFAULT_JMX_PORT;
    }
    return Integer.parseInt(raw.trim());
  }
}
