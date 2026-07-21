package io.github.baokhang83.mnemo.warden.agent.attach;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Finds the target JVM's PID among the processes visible to the agent, by scanning {@code /proc}
 * directly &mdash; not the JDK's own {@code VirtualMachine.list()}.
 *
 * <p>{@code VirtualMachine.list()} (what this used originally) discovers candidates via each
 * JVM's {@code hsperfdata} file, which lives in that JVM's own private {@code /tmp} &mdash; not
 * shared across containers even with {@code shareProcessNamespace: true}, which only shares the
 * PID namespace. Verified on a real cluster (the same investigation that led to {@link
 * TargetAttacher} dropping the Attach API, bug #55): a target under a different UID from the
 * agent is invisible to {@code jps}/{@code VirtualMachine.list()} even though {@code ps} sees it
 * fine. Plain {@code /proc/<pid>/comm} reads are not subject to that restriction &mdash; verified
 * readable across the same UID mismatch &mdash; so this scans {@code /proc} directly and treats
 * any PID whose {@code comm} is exactly {@code "java"} as a candidate.
 */
public final class TargetLocator {

  /** Overrides target selection with an explicit PID; only needed when more than one non-agent
   *  JVM is visible. */
  public static final String ENV_TARGET_PID = "WARDEN_TARGET_PID";

  private static final Path PROC_ROOT = Path.of("/proc");

  private TargetLocator() {}

  /** Finds the target using the live process list and environment. */
  public static Optional<Long> findTarget() {
    return findTarget(PROC_ROOT, ProcessHandle.current().pid(), System::getenv);
  }

  /**
   * Package-private seam so tests can supply a fake {@code /proc}-shaped directory and
   * environment without needing real processes at arbitrary PIDs (mirrors {@code
   * AgentConfig.fromEnv(Function)} and {@code RssReader.forTarget(pid, cgroupRoot, connection)}).
   */
  static Optional<Long> findTarget(Path procRoot, long selfPid, Function<String, String> env) {
    String override = env.apply(ENV_TARGET_PID);
    if (override != null && !override.isBlank()) {
      long pid = Long.parseLong(override.trim());
      return isJavaProcess(procRoot, pid) ? Optional.of(pid) : Optional.empty();
    }

    List<Long> others = listJavaPids(procRoot).stream().filter(pid -> pid != selfPid).toList();
    // Ambiguous with 0 or 2+ candidates: stay unattached rather than guess which one is the app.
    return others.size() == 1 ? Optional.of(others.get(0)) : Optional.empty();
  }

  private static List<Long> listJavaPids(Path procRoot) {
    try (var entries = Files.list(procRoot)) {
      return entries
          .map(p -> p.getFileName().toString())
          .filter(TargetLocator::isNumeric)
          .map(Long::parseLong)
          .filter(pid -> isJavaProcess(procRoot, pid))
          .toList();
    } catch (IOException e) {
      return List.of();
    }
  }

  private static boolean isJavaProcess(Path procRoot, long pid) {
    try {
      return Files.readString(procRoot.resolve(Long.toString(pid)).resolve("comm")).trim().equals("java");
    } catch (IOException e) {
      // Gone between listing and reading, or (for an explicit override) never existed — either
      // way, not a usable candidate.
      return false;
    }
  }

  private static boolean isNumeric(String name) {
    return !name.isEmpty() && name.chars().allMatch(Character::isDigit);
  }
}
