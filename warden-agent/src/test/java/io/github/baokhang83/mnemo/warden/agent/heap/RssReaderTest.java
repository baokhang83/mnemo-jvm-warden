package io.github.baokhang83.mnemo.warden.agent.heap;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.baokhang83.mnemo.warden.agent.attach.AttachedJvm;
import io.github.baokhang83.mnemo.warden.agent.attach.TargetAttacher;
import io.github.baokhang83.mnemo.warden.agent.testsupport.SpawnedJvm;
import java.nio.file.Path;
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

  /**
   * The rest of RssReader's contract — reading a real target's actual cgroup v2 files through
   * /proc/&lt;pid&gt;/root, plus a real NMT reconciliation — only means something on real Linux
   * cgroups; this dev machine (macOS) has none. CI runs on ubuntu-latest, so this still gets
   * exercised against the real thing there.
   */
  @Test
  @EnabledOnOs(OS.LINUX)
  void readsWorkingSetAndReconcilesNmtOnARealLinuxTarget() throws Exception {
    try (SpawnedJvm target = SpawnedJvm.garbageChurner("-XX:NativeMemoryTracking=summary");
        AttachedJvm attached = TargetAttacher.attach(target.awaitDescriptor())) {
      target.awaitStdoutLine("allocated", java.time.Duration.ofSeconds(10));

      RssReader rssReader = RssReader.forTarget(attached);
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
