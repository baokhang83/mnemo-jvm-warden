package io.github.baokhang83.mnemo.warden.agent.heap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * Pure parsing/computation logic, verified against real output captured from a live target
 * (see W-105's design.md and session journal) rather than invented fixtures.
 */
class RssReaderParsingTest {

  // Captured verbatim from `cat /proc/<pid>/root/sys/fs/cgroup/memory.stat` on a real kind pod.
  private static final String REAL_MEMORY_STAT =
      """
      anon 262144
      file 83902464
      kernel_stack 32768
      inactive_file 83902464
      active_file 0
      slab 2728712
      """;

  // Captured verbatim from a real target's `vmNativeMemory summary` (NMT enabled).
  private static final String REAL_NMT_SUMMARY_ENABLED =
      """

      Native Memory Tracking:

      (Omitting categories weighting less than 1KB)

      Total: reserved=7902635KB, committed=506523KB
             malloc: 29883KB #66977, peak=47118KB #54008
             mmap:   reserved=7872752KB, committed=476640KB
      """;

  private static final String REAL_NMT_SUMMARY_DISABLED = "Native memory tracking is not enabled";

  // Captured verbatim from /proc/<pid>/cgroup on a real GitHub Actions runner: no container
  // boundary at all, so the target sits deep in the host's own cgroup tree.
  private static final String REAL_CGROUP_NO_CONTAINER_BOUNDARY = "0::/system.slice/hosted-compute-agent.service\n";

  // Captured verbatim from /proc/<pid>/cgroup, read from a sidecar targeting a sibling
  // container's PID in a real kind pod: the "/.." marks the target's cgroup as outside the
  // reader's own (private) cgroup namespace.
  private static final String REAL_CGROUP_SIBLING_CONTAINER =
      "0::/../cri-containerd-7771495e3a0363588a20dd8c3fd02a5679de692a7b2d9cf94b94247488ac9c6e.scope\n";

  @Test
  void parsesCgroupPathWithNoContainerBoundary() {
    assertEquals("/system.slice/hosted-compute-agent.service", RssReader.parseCgroupPath(REAL_CGROUP_NO_CONTAINER_BOUNDARY));
  }

  @Test
  void parsesCgroupPathEscapingToASiblingContainer() {
    assertEquals(
        "/../cri-containerd-7771495e3a0363588a20dd8c3fd02a5679de692a7b2d9cf94b94247488ac9c6e.scope",
        RssReader.parseCgroupPath(REAL_CGROUP_SIBLING_CONTAINER));
  }

  @Test
  void parsesInactiveFileFromRealMemoryStat() {
    assertEquals(83_902_464L, RssReader.parseInactiveFile(REAL_MEMORY_STAT));
  }

  @Test
  void defaultsToZeroWhenInactiveFileIsAbsent() {
    assertEquals(0L, RssReader.parseInactiveFile("anon 262144\nfile 0\n"));
  }

  @Test
  void parsesNmtCommittedFromRealSummary() {
    OptionalLong committed = RssReader.parseNmtCommitted(REAL_NMT_SUMMARY_ENABLED);

    assertTrue(committed.isPresent());
    assertEquals(506_523L * 1024, committed.getAsLong());
  }

  @Test
  void nmtCommittedIsAbsentWhenNotEnabled() {
    assertFalse(RssReader.parseNmtCommitted(REAL_NMT_SUMMARY_DISABLED).isPresent());
  }
}
