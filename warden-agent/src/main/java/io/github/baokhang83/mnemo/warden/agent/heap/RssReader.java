package io.github.baokhang83.mnemo.warden.agent.heap;

import io.github.baokhang83.mnemo.warden.agent.attach.AttachedJvm;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.management.JMException;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

/**
 * Reads a trustworthy resident-set number for the target &mdash; what M2's shrink-verify step
 * (constitution §5) gates on.
 *
 * <p>The agent and target are <em>separate containers</em> in the same pod, so the agent's own
 * {@code /sys/fs/cgroup} is the wrong cgroup entirely. The original mechanism (crossing into the
 * target's mount namespace via {@code /proc/<pid>/root}) turned out to require the agent and
 * target to run as the same UID &mdash; the identical restriction that broke the Attach API
 * (bug #55) &mdash; confirmed {@code Permission denied} under a genuine UID mismatch on a real
 * cluster, with no capability grant able to fix it (bug #57).
 *
 * <p>{@link #resolveCgroupRoot} instead reads the same cgroup files through a {@code hostPath}
 * mount of {@code /sys/fs/cgroup} into the agent container ({@value #HOST_CGROUP_ROOT}) &mdash;
 * confirmed on a real cluster with a genuine UID mismatch that this works, since the restriction
 * was specifically on traversing {@code /proc/PID/root}, not on the cgroup files' own
 * permissions. This is a real, explicit cost: cgroup namespacing hides a container's true
 * absolute path from its own namespaced view (verified: {@code /proc/<pid>/cgroup} reports only
 * an ancestry-obscured relative path), so there is no way to scope the mount to just this pod
 * &mdash; the agent can see every cgroup on the node. No narrower alternative was found; this was
 * weighed and accepted explicitly rather than left implicit.
 *
 * <p>Because the mount hides the real path, the target's specific directory is found by a
 * bounded-depth search under {@value #HOST_CGROUP_ROOT} for one named after the last path segment
 * {@code /proc/<pid>/cgroup} reports (a container-runtime-specific scope name, e.g. {@code
 * cri-containerd-<id>.scope}) &mdash; verified real depth on kind/containerd is 5 levels, but the
 * cgroup driver and exact naming vary by cluster, hence a generous bound rather than a fixed
 * template. This search runs once per {@link #forTarget}, not per {@link #currentRss()} call.
 *
 * <p>Also verified: an alternative that needed no new privilege at all &mdash; reading
 * container-aware memory metrics over the JMX connection bug #55 already established (({@code
 * com.sun.management.OperatingSystemMXBean#getTotalMemorySize()}/{@code getFreeMemorySize()})
 * &mdash; was rejected: those numbers track raw {@code memory.current}, not {@code
 * memory.current - inactive_file}, so adopting them would have silently regressed the exact
 * safety property this class exists for (see below).
 *
 * <p>Raw {@code memory.current} is not by itself trustworthy: verified on a real target that it
 * counted ~80MB of reclaimable page cache as "used." {@link #currentRss()} instead reports
 * {@code memory.current - inactive_file} (from {@code memory.stat}) as {@code workingSetBytes}
 * &mdash; the same "working set" formula kubelet/cAdvisor use for eviction decisions.
 *
 * <p>Native Memory Tracking is reconciled in as a best-effort cross-check: verified that a target
 * without {@code -XX:NativeMemoryTracking} returns the plain string
 * {@code "Native memory tracking is not enabled"} rather than an error, so it is never required.
 */
public final class RssReader {

  /** Where the agent's manifest must {@code hostPath}-mount {@code /sys/fs/cgroup}, read-only. */
  public static final String HOST_CGROUP_ROOT = "/host-cgroup";

  private static final int MAX_SEARCH_DEPTH = 12;

  private static final ObjectName DIAGNOSTIC_COMMAND = objectName("com.sun.management:type=DiagnosticCommand");
  private static final Pattern NMT_COMMITTED = Pattern.compile("Total:.*?committed=(\\d+)KB");

  private final long pid;
  private final Path cgroupRoot;
  private final MBeanServerConnection connection;

  private RssReader(long pid, Path cgroupRoot, MBeanServerConnection connection) {
    this.pid = pid;
    this.cgroupRoot = cgroupRoot;
    this.connection = connection;
  }

  /**
   * @throws CgroupNotFoundException if no matching cgroup directory exists under the host mount
   * @throws UnsupportedCgroupVersionException if found, but it has no {@code memory.current}
   *     (cgroup v1)
   */
  public static RssReader forTarget(AttachedJvm target) throws IOException {
    Path cgroupRoot = resolveCgroupRoot(target.pid(), Path.of(HOST_CGROUP_ROOT));
    return forTarget(target.pid(), cgroupRoot, target.mbeanConnection());
  }

  /** Package-private seam so tests can point at a fake host-mounted cgroup tree. */
  static Path resolveCgroupRoot(long pid, Path hostCgroupRoot) throws IOException {
    String cgroupFile = Files.readString(Path.of("/proc", Long.toString(pid), "cgroup"));
    String scopeName = lastPathSegment(parseCgroupPath(cgroupFile));

    Optional<Path> found = searchForCgroupDirectory(hostCgroupRoot, scopeName);
    if (found.isEmpty()) {
      throw new CgroupNotFoundException(pid, scopeName);
    }
    return found.get();
  }

  /** Package-private so the parsing logic is directly testable against captured real output. */
  static String parseCgroupPath(String cgroupFileContents) {
    // cgroup v2 unified hierarchy: a single line "0::<path>".
    String line = cgroupFileContents.lines().findFirst().orElse("0::/");
    int separator = line.indexOf("::");
    return separator >= 0 ? line.substring(separator + 2) : "/";
  }

  /**
   * The last component of a cgroup path (e.g. {@code "cri-containerd-<id>.scope"} from {@code
   * "/../cri-containerd-<id>.scope"}) &mdash; the only part visible regardless of how deeply
   * cgroup namespacing obscures the ancestry above it.
   */
  static String lastPathSegment(String cgroupPath) {
    String trimmed = cgroupPath.startsWith("/") ? cgroupPath.substring(1) : cgroupPath;
    int lastSlash = trimmed.lastIndexOf('/');
    return lastSlash >= 0 ? trimmed.substring(lastSlash + 1) : trimmed;
  }

  /** Package-private so the bounded-depth search is directly testable against a fake tree. */
  static Optional<Path> searchForCgroupDirectory(Path root, String name) throws IOException {
    if (Files.notExists(root)) {
      return Optional.empty();
    }
    try (var stream = Files.walk(root, MAX_SEARCH_DEPTH)) {
      return stream
          .filter(Files::isDirectory)
          .filter(p -> p.getFileName().toString().equals(name))
          .sorted(Comparator.comparingInt(Path::getNameCount))
          .findFirst();
    }
  }

  /** Package-private seam so tests can supply a fake cgroup layout instead of a real one. */
  static RssReader forTarget(long pid, Path cgroupRoot, MBeanServerConnection connection) {
    if (Files.notExists(cgroupRoot.resolve("memory.current"))) {
      throw new UnsupportedCgroupVersionException(pid);
    }
    return new RssReader(pid, cgroupRoot, connection);
  }

  public RssReading currentRss() throws IOException {
    long current = readLong(cgroupRoot.resolve("memory.current"));
    long inactiveFile = readInactiveFile(cgroupRoot.resolve("memory.stat"));
    long workingSet = Math.max(0, current - inactiveFile);
    return new RssReading(current, workingSet, readNmtCommitted());
  }

  private static long readLong(Path file) throws IOException {
    return Long.parseLong(Files.readString(file).trim());
  }

  /** Package-private so the parsing logic is directly testable without real cgroup files. */
  static long readInactiveFile(Path memoryStat) throws IOException {
    return parseInactiveFile(Files.readString(memoryStat));
  }

  static long parseInactiveFile(String memoryStatContents) {
    return memoryStatContents
        .lines()
        .filter(line -> line.startsWith("inactive_file "))
        .findFirst()
        .map(line -> Long.parseLong(line.substring("inactive_file ".length()).trim()))
        .orElse(0L);
  }

  private OptionalLong readNmtCommitted() throws IOException {
    String report;
    try {
      report = (String) connection.invoke(DIAGNOSTIC_COMMAND, "vmNativeMemory", new Object[] {new String[] {"summary"}}, new String[] {"[Ljava.lang.String;"});
    } catch (JMException e) {
      throw new IOException("failed to invoke VM.native_memory on target", e);
    }
    return parseNmtCommitted(report);
  }

  /** Package-private so the parsing logic is directly testable against captured real output. */
  static OptionalLong parseNmtCommitted(String vmNativeMemoryReport) {
    Matcher matcher = NMT_COMMITTED.matcher(vmNativeMemoryReport);
    return matcher.find() ? OptionalLong.of(Long.parseLong(matcher.group(1)) * 1024) : OptionalLong.empty();
  }

  private static ObjectName objectName(String name) {
    try {
      return new ObjectName(name);
    } catch (MalformedObjectNameException e) {
      throw new AssertionError("hardcoded ObjectName is invalid: " + name, e);
    }
  }
}
