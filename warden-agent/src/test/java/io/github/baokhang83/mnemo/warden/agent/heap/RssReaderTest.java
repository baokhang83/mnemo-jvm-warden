package io.github.baokhang83.mnemo.warden.agent.heap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.baokhang83.mnemo.warden.agent.attach.AttachedJvm;
import io.github.baokhang83.mnemo.warden.agent.attach.TargetAttacher;
import io.github.baokhang83.mnemo.warden.agent.testsupport.SpawnedJvm;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class RssReaderTest {

  @Test
  void rejectsCleanlyWhenTargetHasNoMemoryCurrent(@TempDir Path fakeCgroupV1Root) {
    // No memory.current in this directory — the v2 unified-hierarchy marker this class requires.
    assertThrows(
        UnsupportedCgroupVersionException.class, () -> RssReader.forTarget(4242L, fakeCgroupV1Root, null));
  }

  @Test
  void findsTheScopeDirectoryAtARealisticDepthAmongDecoys(@TempDir Path hostRoot) throws Exception {
    // Real depth observed on kind/containerd (see design.md): 5 levels under the mount root.
    Path scopeDir =
        hostRoot.resolve(
            "kubelet.slice/kubelet-kubepods.slice/kubelet-kubepods-burstable.slice/"
                + "kubelet-kubepods-burstable-pod1234.slice/cri-containerd-abc123.scope");
    Files.createDirectories(scopeDir);
    Files.writeString(scopeDir.resolve("memory.current"), "12345\n");
    // A decoy sibling scope, so the search can't just grab the first directory it sees.
    Files.createDirectories(hostRoot.resolve("kubelet.slice/kubelet-kubepods.slice/some-other.scope"));

    Optional<Path> found = RssReader.searchForCgroupDirectory(hostRoot, "cri-containerd-abc123.scope");

    assertEquals(Optional.of(scopeDir), found);
  }

  @Test
  void searchFindsNothingUnderAnUnrelatedTree(@TempDir Path hostRoot) throws Exception {
    Files.createDirectories(hostRoot.resolve("kubelet.slice/some-other.scope"));

    assertEquals(Optional.empty(), RssReader.searchForCgroupDirectory(hostRoot, "cri-containerd-abc123.scope"));
  }

  @Test
  void searchToleratesAMissingHostMountRoot(@TempDir Path hostRoot) throws Exception {
    Path missing = hostRoot.resolve("not-actually-mounted");

    assertEquals(Optional.empty(), RssReader.searchForCgroupDirectory(missing, "cri-containerd-abc123.scope"));
  }

  /**
   * Reads this test's own real {@code /proc/<pid>/cgroup} (guaranteed to exist on Linux), then
   * searches an empty host root that can't possibly contain a matching directory &mdash; a
   * deterministic, environment-independent way to exercise {@code CgroupNotFoundException}
   * without depending on the actual shape of the cgroup tree wherever tests happen to run.
   */
  @Test
  @EnabledOnOs(OS.LINUX)
  void throwsCgroupNotFoundWhenNothingUnderTheHostMountMatches(@TempDir Path emptyHostRoot) {
    long ownPid = ProcessHandle.current().pid();

    assertThrows(CgroupNotFoundException.class, () -> RssReader.resolveCgroupRoot(ownPid, emptyHostRoot));
  }

  /**
   * The rest of RssReader's contract — reading a real target's actual cgroup v2 files through a
   * host-mounted cgroup root, plus a real NMT reconciliation — only means something on real Linux
   * cgroups; this dev machine (macOS) has none. CI runs on ubuntu-latest, so this still gets
   * exercised against the real thing there.
   *
   * <p>CI runs the target directly on the runner rather than inside a pod with the {@code
   * hostPath} mount this class is designed for, so this test points {@link
   * RssReader#resolveCgroupRoot} at the runner's own real {@code /sys/fs/cgroup} instead of
   * {@link RssReader#HOST_CGROUP_ROOT} &mdash; exercising the same search/read logic against a
   * genuine cgroup tree without requiring a cluster.
   */
  @Test
  @EnabledOnOs(OS.LINUX)
  void readsWorkingSetAndReconcilesNmtOnARealLinuxTarget() throws Exception {
    try (SpawnedJvm target = SpawnedJvm.garbageChurner("-XX:NativeMemoryTracking=summary")) {
      target.awaitReady();
      try (AttachedJvm attached = TargetAttacher.attach(target.pid())) {
        target.awaitStdoutLine("allocated", java.time.Duration.ofSeconds(10));

        Path cgroupRoot = RssReader.resolveCgroupRoot(target.pid(), Path.of("/sys/fs/cgroup"));
        RssReader rssReader = RssReader.forTarget(target.pid(), cgroupRoot, attached.mbeanConnection());
        RssReading reading = rssReader.currentRss();

        assertTrue(reading.cgroupMemoryCurrent() > 0, "a live target must show some committed memory");
        assertTrue(
            reading.workingSetBytes() <= reading.cgroupMemoryCurrent(),
            "working set is memory.current minus reclaimable cache, so it cannot exceed it");
        assertTrue(reading.nmtCommittedBytes().isPresent(), "NMT was enabled on this target");
        assertTrue(reading.nmtCommittedBytes().getAsLong() > 0);
      }
    }
  }
}
