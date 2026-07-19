package io.github.baokhang83.mnemo.warden.agent.attach;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Finds the target JVM's PID among the processes visible to the agent.
 *
 * <p>With {@code shareProcessNamespace: true} (see {@code deploy/example-sidecar.yaml}), {@link
 * VirtualMachine#list()} sees every JVM in the pod, including the agent itself. A normal pod has
 * exactly one other JVM &mdash; the app &mdash; so excluding the agent's own PID is enough to
 * identify it without any required configuration. {@link #ENV_TARGET_PID} is an escape hatch for
 * the rare pod that runs more than one other JVM.
 */
public final class TargetLocator {

  /** Overrides target selection with an explicit PID; only needed when more than one non-agent
   *  JVM is visible. */
  public static final String ENV_TARGET_PID = "WARDEN_TARGET_PID";

  private TargetLocator() {}

  /** Finds the target using the live process list and environment. */
  public static Optional<VirtualMachineDescriptor> findTarget() {
    return findTarget(VirtualMachine.list(), ProcessHandle.current().pid(), System::getenv);
  }

  /**
   * Package-private seam so tests can supply a fake process list and environment without
   * spawning real JVMs (mirrors {@code AgentConfig.fromEnv(Function)}).
   */
  static Optional<VirtualMachineDescriptor> findTarget(
      List<VirtualMachineDescriptor> candidates, long selfPid, Function<String, String> env) {
    String override = env.apply(ENV_TARGET_PID);
    if (override != null && !override.isBlank()) {
      return candidates.stream().filter(d -> d.id().equals(override.trim())).findFirst();
    }

    List<VirtualMachineDescriptor> others = candidates.stream().filter(d -> !isSelf(d, selfPid)).toList();
    // Ambiguous with 0 or 2+ candidates: stay unattached rather than guess which one is the app.
    return others.size() == 1 ? Optional.of(others.get(0)) : Optional.empty();
  }

  private static boolean isSelf(VirtualMachineDescriptor descriptor, long selfPid) {
    try {
      return Long.parseLong(descriptor.id()) == selfPid;
    } catch (NumberFormatException e) {
      return false;
    }
  }
}
