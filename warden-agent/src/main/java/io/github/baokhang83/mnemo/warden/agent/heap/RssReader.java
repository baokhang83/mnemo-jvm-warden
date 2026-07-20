package io.github.baokhang83.mnemo.warden.agent.heap;

import io.github.baokhang83.mnemo.warden.agent.attach.AttachedJvm;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * {@code /sys/fs/cgroup} is the wrong cgroup entirely. {@link #resolveCgroupRoot} finds the right
 * one by reading {@code /proc/<pid>/cgroup} and combining it with {@code /proc/<pid>/root} &mdash;
 * see its javadoc for the two real environments (private cgroup namespace vs. none) this had to
 * be verified against before it worked in both.
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

  /** @throws UnsupportedCgroupVersionException if the target is not on cgroup v2 */
  public static RssReader forTarget(AttachedJvm target) throws IOException {
    Path cgroupRoot = resolveCgroupRoot(target.pid());
    return forTarget(target.pid(), cgroupRoot, target.mbeanConnection());
  }

  /**
   * {@code /proc/<pid>/root/sys/fs/cgroup} is only already the target's own resolved cgroup when
   * the target has its own private cgroup namespace &mdash; true for a separate container in a
   * pod (verified against a real kind pod), signaled by a {@code "/.."} escape prefix in {@code
   * /proc/<pid>/cgroup}, since that marks the target's cgroup as living outside the *reader's*
   * own cgroup namespace. Without that escape &mdash; no container boundary at all, verified on a
   * real GitHub Actions runner &mdash; crossing into {@code /proc/<pid>/root} only changes the
   * mount namespace, not the cgroup one, so the reported path must be appended by hand.
   */
  static Path resolveCgroupRoot(long pid) throws IOException {
    Path procRoot = Path.of("/proc", Long.toString(pid), "root", "sys", "fs", "cgroup");
    String cgroupFile = Files.readString(Path.of("/proc", Long.toString(pid), "cgroup"));
    String relative = parseCgroupPath(cgroupFile);
    if (relative.contains("..")) {
      return procRoot;
    }
    String trimmed = relative.startsWith("/") ? relative.substring(1) : relative;
    return trimmed.isEmpty() ? procRoot : procRoot.resolve(trimmed);
  }

  /** Package-private so the parsing logic is directly testable against captured real output. */
  static String parseCgroupPath(String cgroupFileContents) {
    // cgroup v2 unified hierarchy: a single line "0::<path>".
    String line = cgroupFileContents.lines().findFirst().orElse("0::/");
    int separator = line.indexOf("::");
    return separator >= 0 ? line.substring(separator + 2) : "/";
  }

  /** Package-private seam so tests can point at a fake cgroup layout instead of a real one. */
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
